package com.xebolax.lector.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xebolax.lector.R
import com.xebolax.lector.audio.ReproductorActivity
import com.xebolax.lector.databinding.FragmentLibrosBinding
import com.xebolax.lector.databinding.ItemListaBinding
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Libro
import com.xebolax.lector.datos.Marcador
import com.xebolax.lector.lectura.LectorActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pestaña con todos los marcadores y notas de la biblioteca, los más recientes primero. */
class MarcadoresFragment : Fragment() {

	private var _b: FragmentLibrosBinding? = null
	private val b get() = _b!!
	private val db by lazy { BaseDatos.obtener(requireContext()) }
	private var filas: List<Pair<Marcador, Libro>> = emptyList()
	private val oyente = BaseDatos.Oyente { view?.post { recargar() } }

	override fun onCreateView(inflador: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
		_b = FragmentLibrosBinding.inflate(inflador, contenedor, false)
		return b.root
	}

	override fun onViewCreated(vista: View, estado: Bundle?) {
		b.recycler.layoutManager = LinearLayoutManager(requireContext())
		b.recycler.sinFocoPropio()
		b.recycler.adapter = Adaptador()
	}

	override fun onStart() {
		super.onStart()
		db.suscribir(oyente)
		recargar()
	}

	override fun onStop() {
		db.desuscribir(oyente)
		super.onStop()
	}

	override fun onDestroyView() {
		_b = null
		super.onDestroyView()
	}

	private fun recargar() {
		if (_b == null) return
		viewLifecycleOwner.lifecycleScope.launch {
			val lista = withContext(Dispatchers.IO) {
				val libros = HashMap<Long, Libro?>()
				db.todosLosMarcadores().mapNotNull { m -> libros.getOrPut(m.libro) { db.libro(m.libro) }?.let { m to it } }
			}
			if (_b == null) return@launch
			filas = lista
			@Suppress("NotifyDataSetChanged")
			b.recycler.adapter?.notifyDataSetChanged()
			b.estado.text = if (lista.isEmpty()) getString(R.string.vacio_marcadores)
			else resources.getQuantityString(R.plurals.marcadores, lista.size, lista.size)
		}
	}

	private fun ir(m: Marcador, l: Libro) {
		val c = requireContext()
		if (m.posicion.audio) {
			db.guardarPosicion(l.id, m.posicion, l.progreso)
			startActivity(Intent(c, ReproductorActivity::class.java).putExtra(LectorActivity.EXTRA_LIBRO, l.id))
		} else {
			startActivity(Intent(c, LectorActivity::class.java).putExtra(LectorActivity.EXTRA_LIBRO, l.id).putExtra(LectorActivity.EXTRA_BLOQUE, m.posicion.bloque))
		}
	}

	private fun acciones(m: Marcador, l: Libro): List<Accion> {
		val a = activity as? AppCompatActivity ?: return emptyList()
		return listOf(
			getString(R.string.ir_al_marcador) to { ir(m, l) },
			getString(R.string.editar_nota) to {
				a.pedirTexto(getString(R.string.editar_nota), getString(R.string.nota), m.nota, multilinea = true) { db.editarNota(m.id, it) }
			},
			getString(R.string.copiar) to { a.copiar(listOf(m.extracto, m.nota).filter { it.isNotBlank() }.joinToString("\n")) },
			getString(R.string.borrar) to {
				db.borrarMarcador(m.id)
				a.avisar(getString(R.string.borrado))
			},
		)
	}

	private inner class Vista(val f: ItemListaBinding) : RecyclerView.ViewHolder(f.root)

	private inner class Adaptador : RecyclerView.Adapter<Vista>() {
		override fun getItemCount() = filas.size
		override fun onCreateViewHolder(padre: ViewGroup, tipo: Int) = Vista(ItemListaBinding.inflate(LayoutInflater.from(padre.context), padre, false))

		override fun onBindViewHolder(v: Vista, posicion: Int) {
			val (m, l) = filas[posicion]
			val texto = if (m.nota.isNotBlank()) getString(R.string.nota_x, m.nota) else m.extracto.take(140)
			val detalle = listOf(l.tituloVisible, m.lugar).filter { it.isNotBlank() }.joinToString(" · ")
			v.f.texto.text = texto
			v.f.detalle.text = detalle
			v.f.detalle.isVisible = detalle.isNotBlank()
			v.f.root.contentDescription = "$texto, $detalle"
			v.f.root.setOnClickListener { ir(m, l) }
			val lista = acciones(m, l)
			v.f.root.accionesAccesibles(lista, ZonaAcciones.MARCADOR)
			v.f.root.setOnLongClickListener {
				requireContext().elegir(texto.take(60), lista)
				true
			}
		}
	}
}
