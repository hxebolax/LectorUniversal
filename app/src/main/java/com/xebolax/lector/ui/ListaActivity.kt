package com.xebolax.lector.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xebolax.lector.R
import com.xebolax.lector.databinding.ActivityListaBinding
import com.xebolax.lector.databinding.ItemListaBinding
import java.text.Normalizer

/**
 * Lista para elegir un sitio del libro: índice, encabezados, páginas,
 * marcadores, resultados de búsqueda, capítulos del audiolibro... Tiene un
 * filtro de texto (sin tildes ni mayúsculas) y se abre con el foco en el
 * elemento actual. Devuelve el valor del elemento elegido.
 */
class ListaActivity : AppCompatActivity() {

	/** Un elemento: texto, detalle (nivel, página...), valor que se devuelve, nivel (sangría) e id opcional. */
	class Item(val texto: String, val detalle: String, val valor: Int, val nivel: Int = 1, val id: Long = 0)

	class Datos(val titulo: String, val items: List<Item>, val seleccionado: Int)

	companion object {
		const val RESULTADO = "valor"
		const val RESULTADO_ID = "id"
		const val ACCION_EDITAR = "editar"
		const val ACCION_BORRAR = "borrar"

		/** Los datos se pasan en memoria (las listas largas no caben en un Intent). */
		var datos: Datos? = null
		/** Si se da, los elementos tienen acciones Editar y Borrar (marcadores). */
		var alAccion: ((Item, String) -> Unit)? = null
		/** Nombre de la acción Editar (por defecto «Editar nota»; en los subrayados, «Cambiar la categoría»). */
		var etiquetaEditar: String? = null
	}

	private lateinit var b: ActivityListaBinding
	private var todos: List<Item> = emptyList()
	private var visibles: List<Item> = emptyList()
	private var accion: ((Item, String) -> Unit)? = null
	private var editar = ""

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityListaBinding.inflate(layoutInflater)
		setContentView(b.root)
		val d = datos ?: run {
			finish()
			return
		}
		accion = alAccion
		alAccion = null
		editar = etiquetaEditar ?: getString(R.string.editar_nota)
		etiquetaEditar = null
		configurarBarra(b.barra, d.titulo)
		todos = d.items
		visibles = todos
		b.recycler.layoutManager = LinearLayoutManager(this)
		b.recycler.sinFocoPropio()
		b.recycler.adapter = Adaptador()
		b.filtro.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
			override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
			override fun afterTextChanged(s: Editable?) = filtrar(s?.toString().orEmpty())
		})
		b.filtroContenedor.isVisible = todos.size > 8
		actualizarEstado()
		val inicial = d.seleccionado.coerceIn(0, (todos.size - 1).coerceAtLeast(0))
		(b.recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(inicial, 200)
		enfocar(inicial)
	}

	private fun enfocar(posicion: Int, intentos: Int = 0) {
		b.recycler.post {
			val v = b.recycler.findViewHolderForAdapterPosition(posicion)?.itemView
			if (v == null) {
				if (intentos < 10) b.recycler.postDelayed({ enfocar(posicion, intentos + 1) }, 60)
				return@post
			}
			if (lectorActivo()) v.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
			else v.requestFocus()
		}
	}

	private fun normal(t: String) = Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

	private fun filtrar(texto: String) {
		val clave = normal(texto.trim())
		visibles = if (clave.isEmpty()) todos else todos.filter { normal(it.texto).contains(clave) || normal(it.detalle).contains(clave) }
		@Suppress("NotifyDataSetChanged")
		b.recycler.adapter?.notifyDataSetChanged()
		actualizarEstado()
	}

	private fun actualizarEstado() {
		b.estado.text = resources.getQuantityString(R.plurals.elementos, visibles.size, visibles.size)
	}

	private fun elegir(item: Item) {
		setResult(RESULT_OK, Intent().putExtra(RESULTADO, item.valor).putExtra(RESULTADO_ID, item.id))
		finish()
	}

	private inner class Fila(val f: ItemListaBinding) : RecyclerView.ViewHolder(f.root)

	private inner class Adaptador : RecyclerView.Adapter<Fila>() {
		override fun getItemCount() = visibles.size

		override fun onCreateViewHolder(padre: ViewGroup, tipo: Int) =
			Fila(ItemListaBinding.inflate(LayoutInflater.from(padre.context), padre, false))

		override fun onBindViewHolder(fila: Fila, posicion: Int) {
			val item = visibles[posicion]
			fila.f.texto.text = item.texto
			fila.f.detalle.text = item.detalle
			fila.f.detalle.isVisible = item.detalle.isNotBlank()
			val sangria = dp(this@ListaActivity, 16 + 16 * (item.nivel - 1).coerceIn(0, 5))
			fila.f.root.setPaddingRelative(sangria, fila.f.root.paddingTop, fila.f.root.paddingEnd, fila.f.root.paddingBottom)
			fila.f.root.contentDescription = listOf(item.texto, item.detalle).filter { it.isNotBlank() }.joinToString(", ")
			fila.f.root.setOnClickListener { elegir(item) }
			val acciones = accion?.let { a ->
				listOf<Accion>(
					editar to { a(item, ACCION_EDITAR); finish() },
					getString(R.string.borrar) to {
						a(item, ACCION_BORRAR)
						todos = todos - item
						filtrar(b.filtro.text.toString())
						avisar(getString(R.string.borrado))
					},
				)
			}.orEmpty()
			fila.f.root.accionesAccesibles(acciones)
			fila.f.root.setOnLongClickListener {
				if (acciones.isEmpty()) false else {
					this@ListaActivity.elegir(item.texto.take(60), acciones)
					true
				}
			}
			ViewCompat.setAccessibilityHeading(fila.f.root, false)
		}
	}
}
