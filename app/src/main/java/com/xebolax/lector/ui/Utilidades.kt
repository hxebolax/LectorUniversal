package com.xebolax.lector.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.text.InputType
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityViewCommand
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.xebolax.lector.R

/** Una acción con nombre (menús, acciones de TalkBack). */
typealias Accion = Pair<String, () -> Unit>

/** ¿Está activo TalkBack u otro lector que explora por tacto? */
fun Context.lectorActivo(): Boolean {
	val gestor = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
	return gestor.isEnabled && gestor.isTouchExplorationEnabled
}

fun dp(contexto: Context, valor: Int): Int = (valor * contexto.resources.displayMetrics.density).toInt()

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).toInt()

/** Marca una vista como encabezado para la navegación por encabezados de TalkBack. */
fun View.encabezado(): View {
	ViewCompat.setAccessibilityHeading(this, true)
	return this
}

/**
 * Lee un texto con el lector de pantalla; si no hay lector activo, lo muestra brevemente.
 * Con 'mostrar' se ve siempre; entonces TalkBack ya lee el aviso en pantalla y no se anuncia otra vez.
 */
fun Activity.avisar(texto: String, mostrar: Boolean = !lectorActivo()) {
	if (mostrar) {
		Toast.makeText(this, texto, if (texto.length > 60) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
		if (lectorActivo()) return
	}
	@Suppress("DEPRECATION")
	window.decorView.announceForAccessibility(texto)
}

/** Configura la barra superior con título y botón "Navegar hacia arriba". */
fun AppCompatActivity.configurarBarra(barra: MaterialToolbar, titulo: String, conAtras: Boolean = true) {
	setSupportActionBar(barra)
	supportActionBar?.title = titulo
	if (conAtras) {
		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		barra.setNavigationContentDescription(R.string.volver)
		barra.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
	}
	title = titulo
}

/**
 * Añade acciones personalizadas al menú de acciones de TalkBack de una
 * vista, sustituyendo las anteriores.
 */
fun View.accionesAccesibles(acciones: List<Accion>) {
	@Suppress("UNCHECKED_CAST")
	(getTag(R.id.acciones_accesibles) as? List<Int>)?.forEach { ViewCompat.removeAccessibilityAction(this, it) }
	val ids = acciones.map { (etiqueta, accion) ->
		ViewCompat.addAccessibilityAction(this, etiqueta, AccessibilityViewCommand { _, _ -> accion(); true })
	}
	setTag(R.id.acciones_accesibles, ids)
}

/** Cambia el nombre que TalkBack dice para el doble toque ("Toca dos veces para <texto>"). */
fun View.etiquetaPulsar(texto: String) {
	ViewCompat.replaceAccessibilityAction(this, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, texto, null)
}

/** Hace que TalkBack anuncie la vista como botón. */
fun View.comoBoton() {
	ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
		override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
			super.onInitializeAccessibilityNodeInfo(host, info)
			info.className = android.widget.Button::class.java.name
		}
	})
}

fun Context.copiar(texto: String) {
	val portapapeles = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
	portapapeles.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), texto))
	// Android 13+ ya avisa al copiar
	if (android.os.Build.VERSION.SDK_INT < 33 && this is Activity) avisar(getString(R.string.copiado))
}

// --- Diálogos ---------------------------------------------------------------

/** Lista de opciones; al elegir una se ejecuta su acción. */
fun Context.elegir(titulo: String, opciones: List<Accion>) {
	MaterialAlertDialogBuilder(this)
		.setTitle(titulo)
		.setItems(opciones.map { it.first }.toTypedArray()) { _, i -> opciones[i].second() }
		.setNegativeButton(R.string.cancelar, null)
		.show()
}

/** Elegir un valor de una lista con el actual marcado. */
fun Context.elegirUno(titulo: String, nombres: List<String>, actual: Int, alElegir: (Int) -> Unit) {
	MaterialAlertDialogBuilder(this)
		.setTitle(titulo)
		.setSingleChoiceItems(nombres.toTypedArray(), actual) { d, i ->
			d.dismiss()
			alElegir(i)
		}
		.setNegativeButton(R.string.cancelar, null)
		.show()
}

/** Minutos que se ofrecen en el temporizador de apagado (-1 = al final del capítulo). */
val MINUTOS_TEMPORIZADOR = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, -1)

/**
 * Elegir temporizador de apagado: devuelve los minutos (-1 = al final del
 * capítulo, 0 = sin temporizador si conQuitar).
 */
fun Context.elegirTemporizador(titulo: String, conQuitar: Boolean, conEpisodios: Boolean = false, conCapitulo: Boolean = true, alElegir: (Int) -> Unit) {
	// Podcasts: también al final del episodio o tras 2 o 3 episodios; la radio no tiene capítulos
	val opciones = (if (conQuitar) listOf(0) else emptyList()) + MINUTOS_TEMPORIZADOR.filter { conCapitulo || it > 0 } +
		(if (conEpisodios) listOf(-2, -102, -103) else emptyList())
	val nombres = opciones.map {
		when (it) {
			0 -> getString(R.string.sin_temporizador)
			else -> nombreTemporizador(it)
		}
	} + getString(R.string.otro_tiempo)
	MaterialAlertDialogBuilder(this)
		.setTitle(titulo)
		.setItems(nombres.toTypedArray()) { _, i ->
			if (i < opciones.size) alElegir(opciones[i])
			else pedirTexto(titulo, getString(R.string.minutos_ayuda), teclado = Teclado.TIEMPO) { texto ->
				// Minutos, o horas:minutos
				val minutos = textoATiempo(texto)?.let { (it / 60_000).toInt() } ?: return@pedirTexto
				if (minutos in 1..1440) alElegir(minutos)
			}
		}
		.setNegativeButton(R.string.cancelar, null)
		.show()
}

fun Context.nombreTemporizador(minutos: Int): String = when {
	minutos == 0 -> getString(R.string.sin_temporizador)
	minutos == -1 -> getString(R.string.al_final_capitulo)
	minutos == -2 -> getString(R.string.al_final_episodio)
	minutos <= -100 -> resources.getQuantityString(R.plurals.tras_n_episodios, -minutos - 100, -minutos - 100)
	else -> resources.getQuantityString(R.plurals.minutos, minutos, minutos)
}

/**
 * Botón de reproducir que, mantenido pulsado, pide el temporizador y empieza
 * con él. TalkBack dice "Toca dos veces y mantén pulsado para reproducir con
 * temporizador" y lo ofrece también en el menú de acciones.
 */
fun View.conTemporizadorAlMantener(alMantener: () -> Unit) {
	setOnLongClickListener {
		alMantener()
		true
	}
	val texto = context.getString(R.string.reproducir_con_temporizador)
	ViewCompat.replaceAccessibilityAction(this, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK, texto) { _, _ ->
		alMantener()
		true
	}
}

/** Pide un texto. alAceptar recibe el texto sin espacios sobrantes. */
/** Teclado de un campo: texto, números (con decimales), hora o minutos («1:30»), o dirección web. */
enum class Teclado { TEXTO, NUMERO, TIEMPO, DIRECCION }

fun Context.pedirTexto(
	titulo: String,
	pista: String,
	inicial: String = "",
	teclado: Teclado = Teclado.TEXTO,
	multilinea: Boolean = false,
	alAceptar: (String) -> Unit,
) {
	val campo = TextInputEditText(this).apply {
		setText(inicial)
		setSelection(inicial.length)
		inputType = when {
			teclado == Teclado.NUMERO -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
			teclado == Teclado.TIEMPO -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
			teclado == Teclado.DIRECCION -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
			multilinea -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
			else -> InputType.TYPE_CLASS_TEXT
		}
		if (multilinea) {
			minLines = 3
			maxLines = 8
		}
	}
	val contenedor = TextInputLayout(this).apply {
		hint = pista
		addView(campo)
	}
	// El contenedor recalcula la altura del campo: con 2 dp más de relleno arriba y abajo llega a 48 dp
	campo.post { campo.setPaddingRelative(campo.paddingStart, campo.paddingTop + dp(this, 2), campo.paddingEnd, campo.paddingBottom + dp(this, 2)) }
	val marco = FrameLayout(this).apply {
		val margen = dp(this@pedirTexto, 20)
		setPadding(margen, margen / 2, margen, 0)
		addView(contenedor)
	}
	val dialogo = MaterialAlertDialogBuilder(this)
		.setTitle(titulo)
		.setView(marco)
		.setPositiveButton(R.string.aceptar) { _, _ -> alAceptar(campo.text?.toString()?.trim().orEmpty()) }
		.setNegativeButton(R.string.cancelar, null)
		.create()
	if (!multilinea) campo.setOnEditorActionListener { _, _, _ ->
		alAceptar(campo.text?.toString()?.trim().orEmpty())
		dialogo.dismiss()
		true
	}
	dialogo.setOnShowListener { campo.requestFocus() }
	dialogo.show()
}

/** Pregunta de confirmación (Sí / Cancelar). */
fun Context.confirmar(mensaje: String, textoSi: String, alConfirmar: () -> Unit) {
	MaterialAlertDialogBuilder(this)
		.setMessage(mensaje)
		.setPositiveButton(textoSi) { _, _ -> alConfirmar() }
		.setNegativeButton(R.string.cancelar, null)
		.show()
}

/**
 * Aviso con «Deshacer» tras un cambio en bloque (marcar como leídos...). Dura
 * lo que pida el sistema para quien necesita más tiempo (TalkBack, «Tiempo
 * para actuar»), y lo dice también TalkBack.
 */
fun Activity.conDeshacer(texto: String, deshacer: () -> Unit) {
	val vista = findViewById<android.view.View>(android.R.id.content) ?: return
	val gestor = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
	val ms = if (android.os.Build.VERSION.SDK_INT >= 29)
		gestor.getRecommendedTimeoutMillis(10_000, AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS)
	else 10_000
	com.google.android.material.snackbar.Snackbar.make(vista, texto, ms)
		.setAction(R.string.deshacer) {
			deshacer()
			avisar(getString(R.string.deshecho), mostrar = false)
		}
		.show()
}

/** Mensaje informativo con un botón Aceptar (el texto se puede recorrer con TalkBack y copiar). */
fun Context.mensaje(titulo: String, texto: String, alCerrar: (() -> Unit)? = null) {
	MaterialAlertDialogBuilder(this)
		.setTitle(titulo)
		.setMessage(texto)
		.setPositiveButton(R.string.aceptar, null)
		.apply { if (alCerrar != null) setOnDismissListener { alCerrar() } }
		.show()
}

/** Campo de texto con etiqueta flotante, para formularios creados en código. */
/**
 * La lista no es un elemento para TalkBack (sus filas sí): vacía, TalkBack se
 * pararía en ella y solo diría «Lista». El teclado sigue llegando a las filas.
 */
fun androidx.recyclerview.widget.RecyclerView.sinFocoPropio() {
	isFocusable = false
	isFocusableInTouchMode = false
}

fun Context.campoTexto(pista: String, inicial: String = ""): Pair<TextInputLayout, EditText> {
	// 56 dp de alto, como el campo de texto de Material 3 (nunca menos de 48 para tocarlo bien).
	// Ojo: isSingleLine o setLines quitan esta altura mínima; quien los use debe volver a ponerla
	val campo = TextInputEditText(this).apply {
		setText(inicial)
		minHeight = dp(this@campoTexto, 56)
	}
	val contenedor = TextInputLayout(this).apply {
		hint = pista
		addView(campo)
	}
	return contenedor to campo
}

// --- Tiempos y tamaños ------------------------------------------------------------

/** "1 hora 2 minutos 5 segundos" para TalkBack. */
fun Context.tiempoHablado(ms: Long, conSegundos: Boolean = true): String {
	val total = (ms / 1000).coerceAtLeast(0)
	val h = total / 3600
	val m = (total % 3600) / 60
	val s = total % 60
	val partes = mutableListOf<String>()
	if (h > 0) partes += resources.getQuantityString(R.plurals.horas, h.toInt(), h.toInt())
	if (m > 0) partes += resources.getQuantityString(R.plurals.minutos, m.toInt(), m.toInt())
	if ((s > 0 && conSegundos) || partes.isEmpty()) partes += resources.getQuantityString(R.plurals.segundos, s.toInt(), s.toInt())
	return partes.joinToString(" ")
}

/** "1:02:05" para la vista. */
fun tiempoCorto(ms: Long): String {
	val total = (ms / 1000).coerceAtLeast(0)
	val h = total / 3600
	val m = (total % 3600) / 60
	val s = total % 60
	return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Convierte "12", "12:30" o "1:02:30" en milisegundos (minutos si es un solo número). */
fun textoATiempo(texto: String): Long? {
	val partes = texto.trim().split(":", ".", ",", "h", "m").filter { it.isNotBlank() }.map { it.trim().toLongOrNull() ?: return null }
	return when (partes.size) {
		1 -> partes[0] * 60_000
		2 -> (partes[0] * 60 + partes[1]) * 1000
		3 -> (partes[0] * 3600 + partes[1] * 60 + partes[2]) * 1000
		else -> null
	}
}

fun tamanoLegible(bytes: Long): String = when {
	bytes <= 0 -> ""
	bytes < 1024 -> "$bytes B"
	bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
	bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
	else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
