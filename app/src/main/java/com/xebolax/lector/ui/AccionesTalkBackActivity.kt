package com.xebolax.lector.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.xebolax.lector.R
import com.xebolax.lector.databinding.ActivityListaBinding

/**
 * Ajustes, Acciones de TalkBack. Sin zona: la lista de zonas de la app. Con
 * zona: sus acciones, cada una con un interruptor (sale o no en las acciones
 * de TalkBack) y, las que salen, en su orden; se reordenan con las acciones
 * Subir y Bajar de cada fila (o manteniendo pulsado).
 */
class AccionesTalkBackActivity : AppCompatActivity() {

	companion object {
		const val EXTRA_ZONA = "zona"

		fun abrir(c: Context, zona: ZonaAcciones? = null) {
			c.startActivity(Intent(c, AccionesTalkBackActivity::class.java).apply { zona?.let { putExtra(EXTRA_ZONA, it.name) } })
		}
	}

	private lateinit var b: ActivityListaBinding
	private var zona: ZonaAcciones? = null
	/** Las entradas de la zona: primero las que salen (en su orden), después las demás. */
	private var filas: List<EntradaAccion> = emptyList()
	private var elegidas: List<String> = emptyList()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityListaBinding.inflate(layoutInflater)
		setContentView(b.root)
		zona = intent.getStringExtra(EXTRA_ZONA)?.let { n -> ZonaAcciones.entries.firstOrNull { it.name == n } }
		configurarBarra(b.barra, zona?.let { getString(it.titulo) } ?: getString(R.string.aj_acciones_talkback))
		b.filtroContenedor.isVisible = false
		b.estado.isVisible = true
		b.estado.text = zona?.let { getString(R.string.acciones_explicacion_zona, getString(it.titulo).lowercase()) } ?: getString(R.string.acciones_explicacion)
		b.recycler.layoutManager = LinearLayoutManager(this)
		b.recycler.sinFocoPropio()
		// Ids estables y sin animación al refrescar: las filas se repintan en su sitio y TalkBack no pierde el foco
		b.recycler.sinAnimarCambios()
		b.recycler.adapter = if (zona == null) AdaptadorZonas().also { it.setHasStableIds(true) } else AdaptadorAcciones().also { it.setHasStableIds(true) }
		cargar()
	}

	override fun onResume() {
		super.onResume()
		// Al volver de una zona, su resumen (cuántas salen) puede haber cambiado: se repintan en su sitio
		if (zona == null) b.recycler.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		if (zona != null) menu.add(Menu.NONE, 1, Menu.NONE, R.string.restablecer_acciones)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		val z = zona
		if (item.itemId == 1 && z != null) {
			confirmar(getString(R.string.confirmar_restablecer_acciones, getString(z.titulo).lowercase()), getString(R.string.restablecer_acciones)) {
				AccionesTalkBack.restablecer(this, z)
				cargar()
				b.recycler.adapter?.notifyDataSetChanged()
				avisar(getString(R.string.acciones_restablecidas))
			}
			return true
		}
		return super.onOptionsItemSelected(item)
	}

	private fun cargar() {
		val z = zona ?: return
		val todas = AccionesTalkBack.entradas(this, z)
		elegidas = AccionesTalkBack.elegidas(this, z).filter { c -> todas.any { it.clave == c } }
		filas = elegidas.mapNotNull { c -> todas.firstOrNull { it.clave == c } } + todas.filter { it.clave !in elegidas }
	}

	/** Guarda el nuevo orden, redibuja y deja el foco de TalkBack en la acción que se ha movido. */
	private fun cambiar(nuevas: List<String>, clave: String, aviso: String) {
		val z = zona ?: return
		AccionesTalkBack.guardar(this, z, nuevas)
		cargar()
		b.recycler.adapter?.notifyDataSetChanged()
		val posicion = filas.indexOfFirst { it.clave == clave }
		if (posicion >= 0) {
			b.recycler.scrollToPosition(posicion)
			b.recycler.postDelayed({
				b.recycler.findViewHolderForAdapterPosition(posicion)?.itemView?.let { v ->
					if (lectorActivo()) v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
				}
				avisar(aviso, mostrar = false)
			}, 250)
		}
	}

	private fun alternar(e: EntradaAccion) {
		val sale = e.clave in elegidas
		cambiar(if (sale) elegidas - e.clave else elegidas + e.clave, e.clave,
			getString(if (sale) R.string.accion_quitada else R.string.accion_anadida, AccionesTalkBack.nombre(this, e)))
	}

	private fun mover(e: EntradaAccion, aPosicion: Int) {
		val lista = elegidas.toMutableList()
		if (!lista.remove(e.clave)) return
		val destino = aPosicion.coerceIn(0, lista.size)
		lista.add(destino, e.clave)
		cambiar(lista, e.clave, getString(R.string.accion_posicion, destino + 1, lista.size))
	}

	/** Lo que se puede hacer con una acción: mostrarla o quitarla y, si sale, moverla. */
	private fun opciones(e: EntradaAccion): List<Accion> {
		val i = elegidas.indexOf(e.clave)
		val r = mutableListOf<Accion>(
			getString(if (i >= 0) R.string.quitar_de_acciones else R.string.poner_en_acciones) to { alternar(e) },
		)
		if (i > 0) {
			r += getString(R.string.accion_subir) to { mover(e, i - 1) }
			r += getString(R.string.accion_primera) to { mover(e, 0) }
		}
		if (i >= 0 && i < elegidas.size - 1) {
			r += getString(R.string.accion_bajar) to { mover(e, i + 1) }
			r += getString(R.string.accion_ultima) to { mover(e, elegidas.size - 1) }
		}
		return r
	}

	private class Fila(val raiz: LinearLayout, val texto: TextView, val detalle: TextView, val interruptor: MaterialSwitch?) : RecyclerView.ViewHolder(raiz)

	private fun nuevaFila(padre: ViewGroup, conInterruptor: Boolean): Fila {
		val dp = resources.displayMetrics.density
		val raiz = LinearLayout(padre.context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			minimumHeight = (56 * dp).toInt()
			setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
			isFocusable = true
			isClickable = true
			val fondo = android.util.TypedValue()
			context.theme.resolveAttribute(android.R.attr.selectableItemBackground, fondo, true)
			setBackgroundResource(fondo.resourceId)
			layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		}
		val textos = LinearLayout(padre.context).apply { orientation = LinearLayout.VERTICAL }
		val texto = TextView(padre.context).apply {
			setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
			importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
		}
		val detalle = TextView(padre.context).apply {
			setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
			setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
			importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
		}
		textos.addView(texto)
		textos.addView(detalle)
		raiz.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		// El interruptor se ve, pero el que se pulsa y lee es la fila entera (una sola parada de TalkBack)
		val interruptor = if (!conInterruptor) null else MaterialSwitch(padre.context).apply {
			isClickable = false
			isFocusable = false
			importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
		}
		interruptor?.let { raiz.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
		return Fila(raiz, texto, detalle, interruptor)
	}

	private inner class AdaptadorZonas : RecyclerView.Adapter<Fila>() {
		override fun getItemCount() = ZonaAcciones.entries.size
		override fun getItemId(posicion: Int) = ZonaAcciones.entries[posicion].ordinal.toLong()
		override fun onCreateViewHolder(padre: ViewGroup, tipo: Int) = nuevaFila(padre, false)
		override fun onBindViewHolder(v: Fila, posicion: Int) {
			val z = ZonaAcciones.entries[posicion]
			val todas = AccionesTalkBack.entradas(this@AccionesTalkBackActivity, z)
			val salen = AccionesTalkBack.elegidas(this@AccionesTalkBackActivity, z).count { c -> todas.any { it.clave == c } }
			v.texto.text = getString(z.titulo)
			v.detalle.text = getString(R.string.acciones_n_de_m, salen, todas.size)
			v.raiz.contentDescription = "${v.texto.text}, ${v.detalle.text}"
			v.raiz.setOnClickListener { abrir(this@AccionesTalkBackActivity, z) }
		}
	}

	private inner class AdaptadorAcciones : RecyclerView.Adapter<Fila>() {
		override fun getItemCount() = filas.size
		override fun getItemId(posicion: Int) = filas[posicion].clave.hashCode().toLong()
		override fun onCreateViewHolder(padre: ViewGroup, tipo: Int) = nuevaFila(padre, true)
		override fun onBindViewHolder(v: Fila, posicion: Int) {
			val e = filas[posicion]
			val i = elegidas.indexOf(e.clave)
			val sale = i >= 0
			v.texto.text = AccionesTalkBack.nombre(this@AccionesTalkBackActivity, e)
			v.detalle.text = if (sale) getString(R.string.accion_posicion, i + 1, elegidas.size) else getString(R.string.accion_no_sale)
			v.interruptor?.isChecked = sale
			// Se lee como un interruptor: «Leer en voz alta, posición 2 de 6, activado, interruptor»
			v.raiz.contentDescription = if (sale) "${v.texto.text}, ${v.detalle.text}" else v.texto.text
			ViewCompat.setAccessibilityDelegate(v.raiz, object : AccessibilityDelegateCompat() {
				override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
					super.onInitializeAccessibilityNodeInfo(host, info)
					info.className = android.widget.Switch::class.java.name
					info.isCheckable = true
					info.isChecked = sale
				}
			})
			v.raiz.setOnClickListener { alternar(e) }
			val opciones = opciones(e)
			// Mostrar o quitar ya es el doble toque: en las acciones, solo mover
			v.raiz.accionesAccesibles(opciones.drop(1))
			v.raiz.setOnLongClickListener {
				elegir(v.texto.text.toString(), opciones)
				true
			}
		}
	}
}
