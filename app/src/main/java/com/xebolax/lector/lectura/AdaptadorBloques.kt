package com.xebolax.lector.lectura

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.xebolax.lector.R
import com.xebolax.lector.modelo.Bloque
import com.xebolax.lector.modelo.Documento
import com.xebolax.lector.modelo.Enlace
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.TipoBloque
import com.xebolax.lector.ui.Accion
import com.xebolax.lector.ui.accionesAccesibles
import com.xebolax.lector.ui.dp

/**
 * Vista de un bloque del documento. Avisa cuando TalkBack pone el foco en
 * ella (así se sabe por dónde va la lectura) y dice su papel (encabezado de
 * nivel 2, elemento de lista, fila de tabla...) sin tocar el texto, para que
 * la navegación por caracteres, palabras y líneas de TalkBack siga funcionando.
 */
class VistaBloque(contexto: Context) : AppCompatTextView(contexto) {

	var posicion = -1
	var rol: String? = null
	var alEnfocar: ((Int) -> Unit)? = null

	override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
		super.onInitializeAccessibilityNodeInfo(info)
		rol?.let { AccessibilityNodeInfoCompat.wrap(info).roleDescription = it }
	}

	override fun performAccessibilityAction(accion: Int, argumentos: Bundle?): Boolean {
		if (accion == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS && posicion >= 0) alEnfocar?.invoke(posicion)
		return super.performAccessibilityAction(accion, argumentos)
	}
}

/** Frase que se está leyendo en voz alta (para resaltarla). */
data class Resaltado(val bloque: Int, val inicio: Int, val fin: Int)

/**
 * Un bloque del documento por fila: cada deslizamiento de TalkBack pasa al
 * siguiente párrafo, "Leer desde el siguiente elemento" recorre todo el libro
 * desplazando la lista sola, y los encabezados son encabezados de verdad.
 */
class AdaptadorBloques(
	private val contexto: Context,
	val doc: Documento,
	private val oyente: Oyente,
) : RecyclerView.Adapter<AdaptadorBloques.Fila>() {

	interface Oyente {
		fun alEnfocar(bloque: Int)
		fun alTocar(bloque: Int)
		fun alMantener(bloque: Int)
		fun alAbrirEnlace(bloque: Int, enlace: Enlace)
		fun acciones(bloque: Int): List<Accion>
		/** Estado que TalkBack lee tras el texto: "página 12, con marcador". */
		fun estado(bloque: Int): String?
		/** Fragmentos subrayados del bloque (se dibujan subrayados). */
		fun subrayados(bloque: Int): List<IntRange> = emptyList()
	}

	var apariencia: Apariencia = Apariencia.de(contexto)
	/**
	 * Frase resaltada. Se cambia el color sobre el texto ya mostrado (sin
	 * volver a ponerlo): así TalkBack no vuelve a leer el párrafo enfocado
	 * cada vez que la voz pasa de frase.
	 */
	var resaltado: Resaltado? = null
		set(valor) {
			val anterior = field
			field = valor
			anterior?.let { pintarResaltado(it.bloque) }
			valor?.let { if (it.bloque != anterior?.bloque) pintarResaltado(it.bloque) }
		}

	private var lista: RecyclerView? = null

	override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
		lista = recyclerView
	}

	override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
		lista = null
	}

	/** El color del resaltado (para poder quitarlo después sin tocar otros). */
	private class SpanResaltado(color: Int) : BackgroundColorSpan(color)

	private fun pintarResaltado(bloque: Int) {
		val vista = (lista?.findViewHolderForAdapterPosition(bloque) as? Fila)?.vista ?: return
		val texto = vista.text as? Spannable ?: return
		texto.getSpans(0, texto.length, SpanResaltado::class.java).forEach { texto.removeSpan(it) }
		resaltado?.takeIf { it.bloque == bloque }?.let { r ->
			val ini = r.inicio.coerceIn(0, texto.length)
			val fin = r.fin.coerceIn(ini, texto.length)
			if (fin > ini) texto.setSpan(SpanResaltado(apariencia.resaltado), ini, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
	}
	var seleccionable = false
	var anunciarNivel = true
	/** Anunciar el tipo de bloque (elemento de lista, fila de tabla, cita...). */
	var anunciarTipo = true

	init {
		setHasStableIds(true)
	}

	override fun getItemId(posicion: Int) = posicion.toLong()

	override fun getItemCount() = doc.bloques.size

	class Fila(val vista: VistaBloque) : RecyclerView.ViewHolder(vista)

	override fun onCreateViewHolder(padre: ViewGroup, tipo: Int): Fila {
		val v = VistaBloque(padre.context).apply {
			layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			isFocusable = true
		}
		return Fila(v)
	}

	override fun onBindViewHolder(fila: Fila, posicion: Int) {
		val b = doc.bloques[posicion]
		val v = fila.vista
		val a = apariencia
		v.posicion = posicion
		v.alEnfocar = { oyente.alEnfocar(it) }

		// --- Aspecto
		val escala = when (b.tipo) {
			TipoBloque.ENCABEZADO -> 1f + (7 - b.nivel.coerceIn(1, 6)) * 0.08f
			TipoBloque.NOTA -> 0.9f
			else -> 1f
		}
		v.setTextSize(TypedValue.COMPLEX_UNIT_SP, a.tamano * escala)
		v.setTextColor(a.texto)
		v.setLinkTextColor(a.enlace)
		v.setLineSpacing(0f, a.interlineado)
		v.letterSpacing = if (b.tipo == TipoBloque.CODIGO) 0f else a.letras
		v.typeface = when (b.tipo) {
			TipoBloque.ENCABEZADO -> Typeface.create(a.tipo, Typeface.BOLD)
			TipoBloque.CODIGO -> Typeface.MONOSPACE
			TipoBloque.CITA, TipoBloque.IMAGEN -> Typeface.create(a.tipo, Typeface.ITALIC)
			else -> a.tipo
		}
		v.gravity = if (b.tipo == TipoBloque.SEPARADOR) Gravity.CENTER_HORIZONTAL else Gravity.START
		if (android.os.Build.VERSION.SDK_INT >= 26) {
			v.justificationMode = if (a.justificar && b.tipo == TipoBloque.PARRAFO) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE
		}
		val sangria = when (b.tipo) {
			TipoBloque.ELEMENTO_LISTA -> 12 * b.nivel.coerceIn(1, 6)
			TipoBloque.CITA, TipoBloque.NOTA -> 20
			else -> 0
		}
		val arriba = when {
			b.tipo == TipoBloque.ENCABEZADO -> a.espacio * 2
			b.tipo == TipoBloque.ELEMENTO_LISTA || b.tipo == TipoBloque.FILA_TABLA -> a.espacio / 3
			else -> a.espacio / 2
		}
		v.setPadding(dp(contexto, a.margen + sangria), dp(contexto, arriba), dp(contexto, a.margen), dp(contexto, a.espacio / 2))

		// --- Texto con enlaces y resaltado
		val texto = SpannableString(b.texto)
		for (e in b.enlaces) {
			if (e.inicio < 0 || e.fin > texto.length || e.inicio >= e.fin) continue
			texto.setSpan(object : ClickableSpan() {
				override fun onClick(widget: View) = oyente.alAbrirEnlace(posicion, e)
				override fun updateDrawState(ds: TextPaint) {
					super.updateDrawState(ds)
					ds.color = a.enlace
				}
			}, e.inicio, e.fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
		if (b.tipo == TipoBloque.FILA_TABLA && b.tiene(Marca.ENCABEZADO_TABLA)) {
			texto.setSpan(StyleSpan(Typeface.BOLD), 0, texto.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
		if (b.tipo == TipoBloque.CODIGO) texto.setSpan(TypefaceSpan("monospace"), 0, texto.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		// Viñeta solo dibujada (TalkBack no la lee: ya dice "elemento de lista")
		if (b.tipo == TipoBloque.ELEMENTO_LISTA && texto.isNotEmpty() && !texto[0].isDigit()) {
			texto.setSpan(BulletSpan(dp(contexto, 8), a.texto), 0, texto.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
		}
		resaltado?.takeIf { it.bloque == posicion }?.let { r ->
			val ini = r.inicio.coerceIn(0, texto.length)
			val fin = r.fin.coerceIn(ini, texto.length)
			if (fin > ini) texto.setSpan(SpanResaltado(a.resaltado), ini, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
		for (rango in oyente.subrayados(posicion)) {
			val ini = rango.first.coerceIn(0, texto.length)
			val fin = (rango.last + 1).coerceIn(ini, texto.length)
			if (fin > ini) texto.setSpan(android.text.style.UnderlineSpan(), ini, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
		v.setTextIsSelectable(false)
		v.setText(texto, android.widget.TextView.BufferType.SPANNABLE)
		v.movementMethod = if (b.enlaces.isNotEmpty() && !seleccionable) LinkMovementMethod.getInstance() else null
		if (seleccionable) v.setTextIsSelectable(true)
		v.isFocusable = true

		// --- Accesibilidad
		ViewCompat.setAccessibilityHeading(v, b.tipo == TipoBloque.ENCABEZADO)
		v.rol = rol(b)
		ViewCompat.setStateDescription(v, estado(b, posicion))
		v.accionesAccesibles(oyente.acciones(posicion))
		if (seleccionable) {
			v.setOnClickListener(null)
			v.setOnLongClickListener(null)
		} else {
			v.setOnClickListener { oyente.alTocar(posicion) }
			v.setOnLongClickListener {
				oyente.alMantener(posicion)
				true
			}
		}
	}

	override fun onBindViewHolder(fila: Fila, posicion: Int, cargas: MutableList<Any>) {
		if (cargas.contains(CARGA_ESTADO)) {
			ViewCompat.setStateDescription(fila.vista, estado(doc.bloques[posicion], posicion))
			fila.vista.accionesAccesibles(oyente.acciones(posicion))
			if (cargas.all { it == CARGA_ESTADO }) return
		}
		onBindViewHolder(fila, posicion)
	}

	/**
	 * Estado del bloque: el nivel si es un encabezado (TalkBack ya dice
	 * «encabezado» por la marca de encabezado; con un papel propio lo diría dos
	 * veces) y sus marcadores o notas.
	 */
	private fun estado(b: Bloque, posicion: Int): String? {
		val nivel = if (b.tipo == TipoBloque.ENCABEZADO && anunciarNivel) contexto.getString(R.string.nivel_n, b.nivel) else null
		return listOfNotNull(nivel, oyente.estado(posicion)?.toString()?.takeIf { it.isNotBlank() }).joinToString(", ").ifBlank { null }
	}

	private fun rol(b: Bloque): String? = if (!anunciarTipo && b.tipo != TipoBloque.ENCABEZADO) null else when (b.tipo) {
		TipoBloque.ENCABEZADO -> null
		TipoBloque.ELEMENTO_LISTA -> contexto.getString(R.string.rol_elemento_lista)
		TipoBloque.FILA_TABLA -> contexto.getString(if (b.tiene(Marca.ENCABEZADO_TABLA)) R.string.rol_encabezado_tabla else R.string.rol_fila_tabla)
		TipoBloque.CITA -> contexto.getString(R.string.rol_cita)
		TipoBloque.IMAGEN -> contexto.getString(R.string.rol_imagen)
		TipoBloque.CODIGO -> contexto.getString(R.string.rol_codigo)
		TipoBloque.NOTA -> contexto.getString(R.string.rol_nota)
		TipoBloque.SEPARADOR -> contexto.getString(R.string.rol_separador)
		else -> null
	}

	/** Vuelve a calcular el estado (marcadores) de un bloque sin redibujarlo entero. */
	fun refrescarEstado(bloque: Int) {
		if (bloque in 0 until itemCount) notifyItemChanged(bloque, CARGA_ESTADO)
	}

	companion object {
		const val CARGA_ESTADO = "estado"
	}
}
