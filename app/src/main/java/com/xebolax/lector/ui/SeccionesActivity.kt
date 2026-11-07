package com.xebolax.lector.ui

import android.os.Bundle
import android.view.Gravity
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
 * Ajustes, Portada y secciones: el orden de Biblioteca, Podcasts, Noticias y
 * Radio (en el menú lateral, en la Portada y en las estadísticas) y cuáles
 * salen en la Portada.
 *
 * Cada fila es una sola parada de TalkBack que se lee como un interruptor (el
 * doble toque la pone o la quita de la Portada); se mueve con las acciones
 * Subir, Bajar, Poner la primera y Poner la última (o manteniendo pulsado).
 * Al moverla, el foco sigue en ella y se anuncia su nueva posición.
 */
class SeccionesActivity : AppCompatActivity() {

	private lateinit var b: ActivityListaBinding
	private var orden: List<Seccion> = emptyList()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityListaBinding.inflate(layoutInflater)
		setContentView(b.root)
		configurarBarra(b.barra, getString(R.string.aj_orden_secciones))
		b.filtroContenedor.isVisible = false
		b.estado.isVisible = true
		b.estado.text = getString(R.string.secciones_explicacion)
		orden = OrdenSecciones.orden(this)
		b.recycler.layoutManager = LinearLayoutManager(this)
		b.recycler.sinFocoPropio()
		b.recycler.adapter = Adaptador().also { it.setHasStableIds(true) }
	}

	private fun mover(s: Seccion, aPosicion: Int) {
		val lista = orden.toMutableList()
		lista.remove(s)
		val destino = aPosicion.coerceIn(0, lista.size)
		lista.add(destino, s)
		orden = lista
		OrdenSecciones.guardar(this, lista)
		b.recycler.adapter?.notifyDataSetChanged()
		b.recycler.scrollToPosition(destino)
		b.recycler.postDelayed({
			b.recycler.findViewHolderForAdapterPosition(destino)?.itemView?.let { v ->
				if (lectorActivo()) v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
			}
			avisar(getString(R.string.seccion_movida, getString(s.nombre), destino + 1, lista.size), mostrar = false)
		}, 250)
	}

	private fun alternar(s: Seccion) {
		val sale = !OrdenSecciones.enPortada(this, s)
		OrdenSecciones.ponerEnPortada(this, s, sale)
		b.recycler.adapter?.notifyItemChanged(orden.indexOf(s))
		avisar(getString(if (sale) R.string.seccion_en_portada else R.string.seccion_fuera_portada, getString(s.nombre)))
	}

	/** Mover (y, en el menú de la pulsación larga, también ponerla o quitarla de la Portada). */
	private fun opciones(s: Seccion): List<Accion> {
		val i = orden.indexOf(s)
		val r = mutableListOf<Accion>(
			getString(if (OrdenSecciones.enPortada(this, s)) R.string.seccion_quitar_portada else R.string.seccion_poner_portada) to { alternar(s) },
		)
		if (i > 0) {
			r += getString(R.string.accion_subir) to { mover(s, i - 1) }
			r += getString(R.string.accion_primera) to { mover(s, 0) }
		}
		if (i < orden.size - 1) {
			r += getString(R.string.accion_bajar) to { mover(s, i + 1) }
			r += getString(R.string.accion_ultima) to { mover(s, orden.size - 1) }
		}
		return r
	}

	private class Fila(val raiz: LinearLayout, val texto: TextView, val detalle: TextView, val interruptor: MaterialSwitch) : RecyclerView.ViewHolder(raiz)

	private inner class Adaptador : RecyclerView.Adapter<Fila>() {
		override fun getItemCount() = orden.size
		override fun getItemId(posicion: Int) = orden[posicion].ordinal.toLong()

		override fun onCreateViewHolder(padre: ViewGroup, tipo: Int): Fila {
			val c = padre.context
			val raiz = LinearLayout(c).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = Gravity.CENTER_VERTICAL
				minimumHeight = dp(c, 56)
				setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10))
				isFocusable = true
				isClickable = true
				val fondo = android.util.TypedValue()
				c.theme.resolveAttribute(android.R.attr.selectableItemBackground, fondo, true)
				setBackgroundResource(fondo.resourceId)
				layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			}
			val textos = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
			val texto = TextView(c).apply {
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
				importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
			}
			val detalle = TextView(c).apply {
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
				setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
				importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
			}
			textos.addView(texto)
			textos.addView(detalle)
			raiz.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			// El interruptor se ve, pero el que se pulsa y lee es la fila entera (una sola parada)
			val interruptor = MaterialSwitch(c).apply {
				isClickable = false
				isFocusable = false
				importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
			}
			raiz.addView(interruptor)
			return Fila(raiz, texto, detalle, interruptor)
		}

		override fun onBindViewHolder(v: Fila, posicion: Int) {
			val s = orden[posicion]
			val enPortada = OrdenSecciones.enPortada(this@SeccionesActivity, s)
			v.texto.text = getString(s.nombre)
			v.detalle.text = getString(R.string.seccion_detalle, posicion + 1, orden.size,
				getString(if (enPortada) R.string.seccion_sale_portada else R.string.seccion_no_sale_portada))
			v.interruptor.isChecked = enPortada
			// «Podcasts, posición 2 de 4, en la Portada, activado, interruptor»
			v.raiz.contentDescription = "${v.texto.text}, ${getString(R.string.accion_posicion, posicion + 1, orden.size)}, ${getString(R.string.seccion_en_la_portada)}"
			ViewCompat.setAccessibilityDelegate(v.raiz, object : AccessibilityDelegateCompat() {
				override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
					super.onInitializeAccessibilityNodeInfo(host, info)
					info.className = android.widget.Switch::class.java.name
					info.isCheckable = true
					info.isChecked = enPortada
				}
			})
			v.raiz.setOnClickListener { alternar(s) }
			val opciones = opciones(s)
			// Ponerla o quitarla de la Portada ya es el doble toque: en las acciones, solo mover
			v.raiz.accionesAccesibles(opciones.drop(1))
			v.raiz.setOnLongClickListener {
				elegir(v.texto.text.toString(), opciones)
				true
			}
		}
	}
}
