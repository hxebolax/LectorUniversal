package com.xebolax.lector.ui

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
import com.xebolax.lector.datos.Tipos

/**
 * Ajustes, Pestañas y tipos de la Biblioteca: qué pestañas salen arriba en
 * la Biblioteca (Todo, Libros, Documentos, Audiolibros, Páginas web y los
 * tipos propios), en qué orden, y crear, renombrar o borrar tipos propios.
 *
 * Como Portada y secciones: cada fila es una sola parada que se lee como un
 * interruptor (el doble toque la muestra u oculta) y se mueve con las
 * acciones Subir, Bajar, Poner la primera y Poner la última. Al moverla, el
 * foco sigue en ella y se dice su nueva posición.
 */
class PestanasBibliotecaActivity : AppCompatActivity() {

	private lateinit var b: ActivityListaBinding
	private var orden: List<String> = emptyList()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityListaBinding.inflate(layoutInflater)
		setContentView(b.root)
		configurarBarra(b.barra, getString(R.string.pestanas_titulo))
		b.filtroContenedor.isVisible = false
		b.estado.isVisible = true
		b.estado.text = getString(R.string.pestanas_explicacion)
		orden = Tipos.orden(this)
		b.recycler.layoutManager = LinearLayoutManager(this)
		b.recycler.sinFocoPropio()
		b.recycler.sinAnimarCambios()
		b.recycler.adapter = Adaptador().also { it.setHasStableIds(true) }
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menu.add(Menu.NONE, 1, Menu.NONE, R.string.tipo_nuevo).setIcon(R.drawable.ic_anadir).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		if (item.itemId != 1) return super.onOptionsItemSelected(item)
		pedirTexto(getString(R.string.tipo_nuevo), getString(R.string.nombre)) { n ->
			if (n.isBlank()) return@pedirTexto
			val t = Tipos.crearPropio(this, n)
			recargar()
			avisar(getString(R.string.tipo_creado, t.nombre))
		}
		return true
	}

	private fun recargar() {
		orden = Tipos.orden(this)
		b.recycler.adapter?.notifyDataSetChanged()
	}

	private fun mover(clave: String, aPosicion: Int) {
		val lista = orden.toMutableList()
		lista.remove(clave)
		val destino = aPosicion.coerceIn(0, lista.size)
		lista.add(destino, clave)
		orden = lista
		Tipos.guardarOrden(this, lista)
		b.recycler.adapter?.notifyDataSetChanged()
		b.recycler.scrollToPosition(destino)
		b.recycler.postDelayed({
			b.recycler.findViewHolderForAdapterPosition(destino)?.itemView?.let { v ->
				if (lectorActivo()) v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
			}
			avisar(getString(R.string.pestana_movida, Tipos.nombre(this, clave), destino + 1, lista.size), mostrar = false)
		}, 250)
	}

	private fun alternar(clave: String) {
		val ver = !Tipos.visible(this, clave)
		// Siempre tiene que quedar al menos una a la vista
		if (!ver && Tipos.visibles(this).let { it.size == 1 && it[0] == clave }) return avisar(getString(R.string.pestana_ultima_visible))
		Tipos.ponerVisible(this, clave, ver)
		b.recycler.adapter?.notifyItemChanged(orden.indexOf(clave))
		avisar(getString(if (ver) R.string.pestana_ahora_visible else R.string.pestana_ahora_oculta, Tipos.nombre(this, clave)))
	}

	/** Mostrar u ocultar, moverla y, si es un tipo propio, renombrarlo o borrarlo. */
	private fun opciones(clave: String): List<Accion> {
		val i = orden.indexOf(clave)
		val r = mutableListOf<Accion>(
			getString(if (Tipos.visible(this, clave)) R.string.pestana_ocultar else R.string.pestana_mostrar) to { alternar(clave) },
		)
		if (i > 0) {
			r += getString(R.string.accion_subir) to { mover(clave, i - 1) }
			r += getString(R.string.accion_primera) to { mover(clave, 0) }
		}
		if (i < orden.size - 1) {
			r += getString(R.string.accion_bajar) to { mover(clave, i + 1) }
			r += getString(R.string.accion_ultima) to { mover(clave, orden.size - 1) }
		}
		if (Tipos.esPropio(clave)) {
			val nombre = Tipos.nombre(this, clave)
			r += getString(R.string.renombrar) to {
				pedirTexto(getString(R.string.renombrar), getString(R.string.nombre), nombre) { n ->
					if (n.isNotBlank()) {
						Tipos.renombrarPropio(this, clave, n)
						recargar()
						avisar(getString(R.string.guardado))
					}
				}
			}
			r += getString(R.string.borrar_tipo) to {
				confirmar(getString(R.string.confirmar_borrar_tipo, nombre), getString(R.string.borrar)) {
					Tipos.borrarPropio(this, clave)
					recargar()
					avisar(getString(R.string.tipo_borrado, nombre))
				}
			}
		}
		return r
	}

	private class Fila(val raiz: LinearLayout, val texto: TextView, val detalle: TextView, val interruptor: MaterialSwitch) : RecyclerView.ViewHolder(raiz)

	private inner class Adaptador : RecyclerView.Adapter<Fila>() {
		override fun getItemCount() = orden.size
		override fun getItemId(posicion: Int) = orden[posicion].hashCode().toLong()

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
			val clave = orden[posicion]
			val visible = Tipos.visible(this@PestanasBibliotecaActivity, clave)
			val nombre = Tipos.nombre(this@PestanasBibliotecaActivity, clave)
			val propio = Tipos.esPropio(clave)
			v.texto.text = nombre
			v.detalle.text = listOfNotNull(
				getString(R.string.pestana_detalle, posicion + 1, orden.size, getString(if (visible) R.string.pestana_se_ve else R.string.pestana_no_se_ve)),
				getString(R.string.pestana_tipo_propio).takeIf { propio },
			).joinToString(", ")
			v.interruptor.isChecked = visible
			// «Documentos, posición 3 de 6, pestaña visible, activado, interruptor»
			v.raiz.contentDescription = listOfNotNull(nombre, getString(R.string.accion_posicion, posicion + 1, orden.size),
				getString(R.string.pestana_tipo_propio).takeIf { propio }, getString(R.string.pestana_visible)).joinToString(", ")
			ViewCompat.setAccessibilityDelegate(v.raiz, object : AccessibilityDelegateCompat() {
				override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
					super.onInitializeAccessibilityNodeInfo(host, info)
					info.className = android.widget.Switch::class.java.name
					info.isCheckable = true
					info.isChecked = visible
				}
			})
			v.raiz.setOnClickListener { alternar(clave) }
			val opciones = opciones(clave)
			// Mostrar u ocultar ya es el doble toque: en las acciones, lo demás
			v.raiz.accionesAccesibles(opciones.drop(1))
			v.raiz.setOnLongClickListener {
				elegir(nombre, opciones)
				true
			}
		}
	}
}
