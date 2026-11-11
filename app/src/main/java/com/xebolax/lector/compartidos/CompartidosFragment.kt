package com.xebolax.lector.compartidos

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.xebolax.lector.R
import com.xebolax.lector.databinding.FragmentLibrosBinding
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Estado
import com.xebolax.lector.lectura.Abiertos
import com.xebolax.lector.ui.AccionesCompartido
import com.xebolax.lector.ui.AdaptadorLibros
import com.xebolax.lector.ui.Fila
import com.xebolax.lector.ui.campoTexto
import com.xebolax.lector.ui.dp
import com.xebolax.lector.ui.sinAnimarCambios
import com.xebolax.lector.ui.sinFocoPropio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Una pestaña de Compartidos: lo pendiente, lo leído o todo (con buscador). */
class CompartidosFragment : Fragment() {

	enum class Modo(val nombre: Int, val icono: Int) {
		PENDIENTES(R.string.comp_pestana_pendientes, R.drawable.ic_compartido),
		LEIDOS(R.string.comp_pestana_leidos, R.drawable.ic_seleccionado),
		TODO(R.string.comp_pestana_todo, R.drawable.ic_lista),
	}

	companion object {
		fun nuevo(modo: Modo) = CompartidosFragment().apply { arguments = bundleOf("modo" to modo.name) }
	}

	private var _b: FragmentLibrosBinding? = null
	private val b get() = _b!!
	private lateinit var modo: Modo
	private lateinit var adaptador: AdaptadorLibros
	private val db by lazy { BaseDatos.obtener(requireContext()) }
	private var busqueda = ""
	private var carga: Job? = null

	private val recargaDiferida = Runnable { recargar() }
	private val oyente = BaseDatos.Oyente {
		view?.let { v ->
			v.removeCallbacks(recargaDiferida)
			v.postDelayed(recargaDiferida, 300)
		}
	}

	override fun onCreateView(inflador: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
		_b = FragmentLibrosBinding.inflate(inflador, contenedor, false)
		return b.root
	}

	override fun onViewCreated(vista: View, estado: Bundle?) {
		modo = runCatching { Modo.valueOf(requireArguments().getString("modo", Modo.PENDIENTES.name)) }.getOrDefault(Modo.PENDIENTES)
		val a = requireActivity() as AppCompatActivity
		adaptador = AdaptadorLibros(a, { f -> (f as? Fila.DeCompartido)?.let { Abiertos.abrirPantalla(a, it.libro) } }) { f ->
			(f as? Fila.DeCompartido)?.let { AccionesCompartido.acciones(a, it.libro) }.orEmpty()
		}
		b.recycler.layoutManager = LinearLayoutManager(requireContext())
		b.recycler.sinFocoPropio()
		b.recycler.sinAnimarCambios()
		b.recycler.adapter = adaptador
		if (modo == Modo.TODO) {
			val (contenedor, campo) = requireContext().campoTexto(getString(R.string.comp_buscar))
			(contenedor as TextInputLayout).endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
			(campo as TextInputEditText).isSingleLine = true
			campo.minHeight = dp(requireContext(), 56)
			campo.addTextChangedListener(object : TextWatcher {
				override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
				override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
				override fun afterTextChanged(s: Editable?) {
					busqueda = s?.toString().orEmpty().trim()
					recargar()
				}
			})
			b.cabecera.addView(contenedor)
		}
		recargar()
	}

	override fun onStart() {
		super.onStart()
		db.suscribir(oyente)
		recargar()
	}

	override fun onStop() {
		db.desuscribir(oyente)
		view?.removeCallbacks(recargaDiferida)
		super.onStop()
	}

	override fun onDestroyView() {
		_b = null
		super.onDestroyView()
	}

	fun recargar() {
		if (_b == null) return
		val texto = busqueda
		val res = resources
		carga?.cancel()
		carga = viewLifecycleOwner.lifecycleScope.launch {
			val (filas, estado) = withContext(Dispatchers.IO) {
				val todos = db.compartidos()
				val lista = when (modo) {
					Modo.PENDIENTES -> todos.filter { it.estado != Estado.TERMINADO }
					Modo.LEIDOS -> todos.filter { it.estado == Estado.TERMINADO }
					Modo.TODO -> if (texto.isBlank()) todos else todos.filter { it.tituloVisible.contains(texto, true) || it.origen.contains(texto, true) }
				}
				val estado = when {
					lista.isNotEmpty() -> res.getQuantityString(R.plurals.elementos, lista.size, lista.size)
					todos.isEmpty() -> res.getString(R.string.comp_vacio)
					modo == Modo.PENDIENTES -> res.getString(R.string.comp_nada_pendiente)
					modo == Modo.LEIDOS -> res.getString(R.string.comp_nada_leido)
					else -> res.getString(R.string.sin_resultados, texto)
				}
				lista.map { Fila.DeCompartido(it) } to estado
			}
			if (_b == null) return@launch
			adaptador.reemplazar(filas)
			if (b.estado.text.toString() != estado) b.estado.text = estado
		}
	}
}
