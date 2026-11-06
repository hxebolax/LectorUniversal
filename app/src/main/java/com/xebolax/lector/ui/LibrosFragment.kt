package com.xebolax.lector.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.xebolax.lector.R
import com.xebolax.lector.databinding.FragmentLibrosBinding
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Estado
import com.xebolax.lector.datos.Libro
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.lectura.Abiertos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pestañas con libros:
 * - Leyendo: los abiertos recientemente (el primero, el último que se leyó).
 * - Biblioteca: las carpetas de la biblioteca y los libros de la carpeta en
 *   la que se está, como en un explorador de archivos (la primera fila sube
 *   a la carpeta anterior, y también Atrás). Al buscar o al elegir otro
 *   filtro (todos, favoritos, leyendo, sin empezar, terminados, audio,
 *   texto) se ven los libros de todas las carpetas juntos, cada uno con la
 *   carpeta en la que está.
 */
class LibrosFragment : Fragment() {

	enum class Modo { RECIENTES, BIBLIOTECA }

	companion object {
		private const val MODO = "modo"
		fun nuevo(modo: Modo) = LibrosFragment().apply { arguments = bundleOf(MODO to modo.name) }
	}

	/** El primero (por carpetas) es la vista normal; los demás muestran todos los libros juntos. */
	enum class Filtro { CARPETAS, TODOS, FAVORITOS, LEYENDO, NUEVOS, TERMINADOS, AUDIO, TEXTO, QUIERO_LEER, ABANDONADOS, AUTORES, SERIES, COLECCIONES }

	/** Vistas por grupos: se entra en un autor, una serie o una colección como en una carpeta. */
	private val porGrupos get() = filtro in setOf(Filtro.AUTORES, Filtro.SERIES, Filtro.COLECCIONES) && busqueda.isBlank()

	/** El grupo abierto (autor, serie o id de colección), o null para la lista de grupos. */
	private var grupo: String? = null

	private var _b: FragmentLibrosBinding? = null
	private val b get() = _b!!
	private lateinit var modo: Modo
	private lateinit var adaptador: AdaptadorLibros
	private val db by lazy { BaseDatos.obtener(requireContext()) }
	private var filtro = Filtro.CARPETAS
	private var busqueda = ""
	private var botonFiltro: MaterialButton? = null
	private var botonNuevaCarpeta: MaterialButton? = null
	private var titulo: TextView? = null
	private var campoBusqueda: TextInputEditText? = null
	private var botonSeleccion: MaterialButton? = null
	/** Libros seleccionados (acciones para varios a la vez). */
	private val seleccionados = linkedSetOf<Long>()

	/** Carpeta abierta (null = raíz de la biblioteca). Los libros que se añaden van a ella. */
	var carpetaActual: Long? = null
		private set

	/** ¿Se está viendo la biblioteca por carpetas (y no una búsqueda o un filtro)? */
	val porCarpetas: Boolean get() = modo == Modo.BIBLIOTECA && filtro == Filtro.CARPETAS && busqueda.isBlank()

	private val oyente = BaseDatos.Oyente { view?.post { recargar() } }

	override fun onCreateView(inflador: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
		_b = FragmentLibrosBinding.inflate(inflador, contenedor, false)
		return b.root
	}

	override fun onViewCreated(vista: View, estado: Bundle?) {
		modo = runCatching { Modo.valueOf(requireArguments().getString(MODO, Modo.RECIENTES.name)) }.getOrDefault(Modo.BIBLIOTECA)
		carpetaActual = estado?.getLong("carpeta", -1)?.takeIf { it >= 0 }
		filtro = estado?.getString("filtro")?.let { runCatching { Filtro.valueOf(it) }.getOrNull() } ?: Filtro.CARPETAS
		val actividad = requireActivity() as AppCompatActivity
		adaptador = AdaptadorLibros(actividad, ::alAbrir, ::accionesDe)
		b.recycler.layoutManager = LinearLayoutManager(requireContext())
		b.recycler.sinFocoPropio()
		b.recycler.adapter = adaptador
		if (modo == Modo.BIBLIOTECA) construirCabecera()
		recargar()
	}

	override fun onSaveInstanceState(estado: Bundle) {
		super.onSaveInstanceState(estado)
		estado.putLong("carpeta", carpetaActual ?: -1)
		estado.putString("filtro", filtro.name)
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

	private fun construirCabecera() {
		val c = requireContext()
		// Dónde se está: "Biblioteca" o "Biblioteca › Novela › Clásicos" (encabezado)
		val fila = LinearLayout(c).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
		}
		titulo = TextView(c).apply {
			setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
			minHeight = dp(c, 48)
			gravity = Gravity.CENTER_VERTICAL
			encabezado()
		}
		fila.addView(titulo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		botonNuevaCarpeta = MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
			text = getString(R.string.nueva_carpeta)
			minHeight = dp(c, 48)
			setOnClickListener { nuevaCarpeta() }
		}
		fila.addView(botonNuevaCarpeta)
		b.cabecera.addView(fila)

		val (contenedor, campo) = c.campoTexto(getString(R.string.buscar_en_biblioteca))
		(contenedor as TextInputLayout).endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
		campo.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
			override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
			override fun afterTextChanged(s: Editable?) {
				busqueda = s?.toString().orEmpty()
				recargar()
			}
		})
		(campo as TextInputEditText).isSingleLine = true
		// Una sola línea quita la altura mínima: se vuelve a poner (56 dp, para tocarlo bien)
		campo.minHeight = dp(c, 56)
		campoBusqueda = campo
		b.cabecera.addView(contenedor)

		botonFiltro = MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
			minHeight = dp(c, 48)
			setOnClickListener { elegirFiltro() }
		}
		b.cabecera.addView(botonFiltro, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		botonSeleccion = MaterialButton(c).apply {
			minHeight = dp(c, 48)
			isVisible = false
			setOnClickListener { accionesSeleccion() }
		}
		b.cabecera.addView(botonSeleccion, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		actualizarCabecera()
	}

	private fun actualizarCabecera() {
		val nombres = resources.getStringArray(R.array.filtros_biblioteca)
		botonFiltro?.text = getString(R.string.mostrar_x, nombres[filtro.ordinal])
		botonNuevaCarpeta?.isVisible = porCarpetas || (filtro == Filtro.COLECCIONES && grupo == null && busqueda.isBlank())
		botonNuevaCarpeta?.text = getString(if (filtro == Filtro.COLECCIONES) R.string.nueva_coleccion else R.string.nueva_carpeta)
		botonSeleccion?.isVisible = seleccionados.isNotEmpty()
		botonSeleccion?.text = resources.getQuantityString(R.plurals.seleccionados_acciones, seleccionados.size, seleccionados.size)
		titulo?.text = when {
			busqueda.isNotBlank() -> getString(R.string.resultados_busqueda_biblioteca)
			porGrupos && grupo != null -> nombres[filtro.ordinal] + " › " + nombreGrupo(grupo!!)
			filtro != Filtro.CARPETAS -> nombres[filtro.ordinal]
			else -> listOf(getString(R.string.pestana_biblioteca)).plus(
				carpetaActual?.let { db.rutaCarpeta(it) }?.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
			).joinToString(" › ")
		}
	}

	private fun elegirFiltro() {
		val nombres = resources.getStringArray(R.array.filtros_biblioteca).toList()
		requireContext().elegirUno(getString(R.string.mostrar), nombres, filtro.ordinal) { i ->
			filtro = Filtro.entries[i]
			grupo = null
			recargar()
		}
	}

	private fun nombreGrupo(clave: String): String =
		if (filtro == Filtro.COLECCIONES) db.colecciones().firstOrNull { it.id.toString() == clave }?.nombre ?: clave else clave

	private fun nuevaColeccion() {
		requireContext().pedirTexto(getString(R.string.nueva_coleccion), getString(R.string.nombre)) { nombre ->
			if (nombre.isNotBlank()) {
				db.crearColeccion(nombre)
				(activity as? AppCompatActivity)?.avisar(getString(R.string.coleccion_creada, nombre.trim()))
			}
		}
	}

	private fun nuevaCarpeta() {
		if (filtro == Filtro.COLECCIONES) return nuevaColeccion()
		requireContext().pedirTexto(getString(R.string.nueva_carpeta), getString(R.string.nombre)) { nombre ->
			if (nombre.isNotBlank()) {
				db.crearCarpeta(nombre, carpetaActual)
				(activity as? AppCompatActivity)?.avisar(getString(R.string.carpeta_creada, nombre))
			}
		}
	}

	/** Atrás dentro de una carpeta sube a la anterior. Devuelve true si lo ha hecho. */
	fun subir(): Boolean {
		// En un autor, serie o colección: vuelve a la lista de grupos
		if (porGrupos && grupo != null) {
			grupo = null
			recargar()
			return true
		}
		if (!porCarpetas || carpetaActual == null) return false
		carpetaActual = db.carpeta(carpetaActual!!)?.padre
		recargar()
		anunciarCarpeta()
		return true
	}

	private fun anunciarCarpeta() {
		val nombre = carpetaActual?.let { db.carpeta(it)?.nombre } ?: getString(R.string.pestana_biblioteca)
		(activity as? AppCompatActivity)?.avisar(getString(R.string.carpeta_x, nombre), mostrar = false)
	}

	fun recargar() {
		if (_b == null) return
		val orden = Preferencias(requireContext()).ordenBiblioteca
		val carpeta = carpetaActual
		val vistaCarpetas = porCarpetas
		val texto = busqueda
		val filtroActual = filtro
		val grupos = porGrupos
		val grupoActual = grupo
		viewLifecycleOwner.lifecycleScope.launch {
			val filas = withContext(Dispatchers.IO) {
				when {
					modo == Modo.RECIENTES -> db.recientes().map { Fila.DeLibro(it, db.rutaCarpeta(it.carpeta)) }
					grupos && grupoActual == null -> when (filtroActual) {
						Filtro.AUTORES -> db.autores().map { (n, c) -> Fila.Grupo(n, n, c) }
						Filtro.SERIES -> db.series().map { (n, c) -> Fila.Grupo(n, n, c) }
						else -> db.colecciones().map { c -> Fila.Grupo(c.id.toString(), c.nombre, c.libros) }
					}
					grupos -> listOf<Fila>(Fila.Subir(resources.getStringArray(R.array.filtros_biblioteca)[filtroActual.ordinal])) + when (filtroActual) {
						Filtro.AUTORES -> db.deAutor(grupoActual!!, orden)
						Filtro.SERIES -> db.deSerie(grupoActual!!)
						else -> db.deColeccion(grupoActual!!.toLongOrNull() ?: -1, orden)
					}.map { Fila.DeLibro(it, db.rutaCarpeta(it.carpeta)) }
					vistaCarpetas -> {
						val lista = mutableListOf<Fila>()
						if (carpeta != null) {
							val padre = db.carpeta(carpeta)?.padre
							lista += Fila.Subir(padre?.let { db.carpeta(it)?.nombre } ?: getString(R.string.pestana_biblioteca))
						}
						db.carpetas(carpeta).forEach { c -> lista += Fila.DeCarpeta(c, db.contarEnCarpeta(c.id), db.carpetas(c.id).size) }
						db.deCarpeta(carpeta, orden).forEach { lista += Fila.DeLibro(it) }
						lista
					}
					else -> {
						val base: List<Libro> = if (texto.isNotBlank()) db.buscarLibros(texto, orden) else db.todos(orden)
						val filas = mutableListOf<Fila>()
						// Al buscar también salen las carpetas cuyo nombre coincide
						if (texto.isNotBlank()) db.todasLasCarpetas().filter { it.nombre.contains(texto.trim(), ignoreCase = true) }
							.forEach { c -> filas += Fila.DeCarpeta(c, db.contarEnCarpeta(c.id), db.carpetas(c.id).size) }
						base.filter { l ->
							when (filtroActual) {
								Filtro.CARPETAS, Filtro.TODOS -> true
								Filtro.FAVORITOS -> l.favorito
								Filtro.LEYENDO -> l.estado == Estado.LEYENDO
								Filtro.NUEVOS -> l.estado == Estado.NUEVO
								Filtro.TERMINADOS -> l.estado == Estado.TERMINADO
								Filtro.AUDIO -> l.audio
								Filtro.TEXTO -> !l.audio
								Filtro.QUIERO_LEER -> l.estado == Estado.QUIERO_LEER
								Filtro.ABANDONADOS -> l.estado == Estado.ABANDONADO
								// Buscando en una vista por grupos: todos los que coinciden
								Filtro.AUTORES, Filtro.SERIES, Filtro.COLECCIONES -> true
							}
						}.forEach { filas += Fila.DeLibro(it, db.rutaCarpeta(it.carpeta)) }
						filas
					}
				}
			}
			if (_b == null) return@launch
			// Los libros quitados de la biblioteca dejan de estar seleccionados
			if (seleccionados.isNotEmpty()) {
				val existen = withContext(Dispatchers.IO) { seleccionados.filter { db.libro(it) != null } }
				seleccionados.retainAll(existen.toSet())
			}
			adaptador.seleccionados = seleccionados.toSet()
			adaptador.reemplazar(filas)
			actualizarCabecera()
			b.estado.text = when {
				filas.isEmpty() && modo == Modo.RECIENTES -> getString(R.string.vacio_recientes)
				filas.isEmpty() && vistaCarpetas && carpeta == null -> getString(R.string.vacio_biblioteca)
				filas.none { it !is Fila.Subir } && vistaCarpetas -> getString(R.string.carpeta_vacia)
				filas.isEmpty() && grupos && grupoActual == null -> getString(when (filtroActual) {
					Filtro.COLECCIONES -> R.string.sin_colecciones
					Filtro.SERIES -> R.string.sin_series
					else -> R.string.sin_libros
				})
				filas.isEmpty() -> getString(R.string.sin_libros)
				else -> {
					val libros = filas.count { it is Fila.DeLibro } + filas.filterIsInstance<Fila.Grupo>().sumOf { it.libros }
					val carpetas = filas.count { it is Fila.DeCarpeta }
					listOfNotNull(
						carpetas.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.carpetas, it, it) },
						resources.getQuantityString(R.plurals.libros, libros, libros),
					).joinToString(", ")
				}
			}
		}
	}

	private fun alAbrir(fila: Fila) {
		val a = activity as? AppCompatActivity ?: return
		when (fila) {
			// Con libros seleccionados, tocar otro lo selecciona (o lo quita)
			is Fila.DeLibro -> if (seleccionados.isNotEmpty()) alternarSeleccion(fila.libro) else Abiertos.abrirPantalla(a, fila.libro)
			is Fila.DeCarpeta -> {
				// Desde una búsqueda o un filtro, abrir una carpeta vuelve a la vista por carpetas
				filtro = Filtro.CARPETAS
				carpetaActual = fila.carpeta.id
				if (busqueda.isNotBlank()) {
					busqueda = ""
					campoBusqueda?.setText("")
				}
				recargar()
				anunciarCarpeta()
			}
			is Fila.Subir -> subir()
			is Fila.Grupo -> {
				grupo = fila.clave
				recargar()
				a.avisar(fila.nombre, mostrar = false)
			}
		}
	}

	private fun alternarSeleccion(l: Libro) {
		val a = activity as? AppCompatActivity ?: return
		if (!seleccionados.remove(l.id)) seleccionados += l.id
		a.avisar(
			if (l.id in seleccionados) getString(R.string.seleccionado_n, l.tituloVisible, seleccionados.size)
			else getString(R.string.deseleccionado_n, l.tituloVisible, seleccionados.size)
		)
		adaptador.seleccionados = seleccionados.toSet()
		adaptador.notifyDataSetChanged()
		actualizarCabecera()
	}

	/** Acciones para todos los libros seleccionados a la vez. */
	private fun accionesSeleccion() {
		val a = activity as? AppCompatActivity ?: return
		val ids = seleccionados.toList()
		if (ids.isEmpty()) return
		fun hecho(texto: String) {
			a.avisar(texto)
			seleccionados.clear()
			recargar()
		}
		val n = ids.size
		a.elegir(resources.getQuantityString(R.plurals.seleccionados_acciones, n, n), listOf(
			getString(R.string.mover_a_carpeta) to {
				AccionesLibro.elegirCarpeta(a, getString(R.string.mover_a_carpeta), null) { destino ->
					ids.forEach { db.moverLibro(it, destino) }
					hecho(getString(R.string.movido_a, if (destino == null) getString(R.string.biblioteca_raiz) else db.rutaCarpeta(destino)))
				}
			},
			getString(R.string.anadir_favoritos) to { ids.forEach { db.cambiarFavorito(it, true) }; hecho(getString(R.string.anadido_favoritos)) },
			getString(R.string.quitar_favoritos) to { ids.forEach { db.cambiarFavorito(it, false) }; hecho(getString(R.string.quitado_favoritos)) },
			getString(R.string.estado_de_lectura) to {
				AccionesLibro.elegirEstado(a, ids) {
					seleccionados.clear()
					recargar()
				}
			},
			getString(R.string.colecciones_puntos) to { AccionesLibro.elegirColecciones(a, ids, resources.getQuantityString(R.plurals.libros, n, n)) },
			getString(R.string.marcar_terminado) to { ids.forEach { db.cambiarEstado(it, Estado.TERMINADO) }; hecho(getString(R.string.hecho)) },
			getString(R.string.marcar_no_leido) to {
				a.confirmar(getString(R.string.confirmar_no_leido), getString(R.string.marcar_no_leido)) {
					ids.forEach { db.cambiarEstado(it, Estado.NUEVO) }
					hecho(getString(R.string.hecho))
				}
			},
			getString(R.string.quitar_de_biblioteca) to {
				a.confirmar(resources.getQuantityString(R.plurals.confirmar_quitar_varios, n, n), getString(R.string.quitar)) {
					for (id in ids) {
						val uri = db.libro(id)?.uri?.let { android.net.Uri.parse(it) }
						if (uri?.scheme == "file" && uri.path?.startsWith(a.filesDir.absolutePath) == true) runCatching { java.io.File(uri.path!!).delete() }
						db.quitarLibro(id)
					}
					hecho(getString(R.string.quitado_de_biblioteca))
				}
			},
			getString(R.string.seleccionar_todos) to {
				adaptador.filas.forEach { if (it is Fila.DeLibro) seleccionados += it.libro.id }
				a.avisar(resources.getQuantityString(R.plurals.seleccionados_acciones, seleccionados.size, seleccionados.size))
				adaptador.seleccionados = seleccionados.toSet()
				adaptador.notifyDataSetChanged()
				actualizarCabecera()
			},
			getString(R.string.quitar_toda_seleccion) to { hecho(getString(R.string.seleccion_vacia)) },
		))
	}

	private fun accionesDe(fila: Fila): List<Accion> {
		val a = activity as? AppCompatActivity ?: return emptyList()
		return when (fila) {
			is Fila.DeLibro -> {
				val l = fila.libro
				val seleccion = (if (l.id in seleccionados) getString(R.string.quitar_seleccion) else getString(R.string.seleccionar)) to { alternarSeleccion(l) }
				listOf(seleccion) + AccionesLibro.acciones(a, l)
			}
			is Fila.DeCarpeta -> AccionesLibro.accionesCarpeta(a, fila.carpeta) { alAbrir(fila) }
			is Fila.Subir -> emptyList()
			is Fila.Grupo -> if (filtro != Filtro.COLECCIONES) listOf(getString(R.string.abrir) to { alAbrir(fila) }) else {
				val id = fila.clave.toLongOrNull() ?: -1
				listOf(
					getString(R.string.abrir) to { alAbrir(fila) },
					getString(R.string.renombrar) to {
						a.pedirTexto(getString(R.string.renombrar), getString(R.string.nombre), fila.nombre) { n -> if (n.isNotBlank()) db.renombrarColeccion(id, n) }
					},
					getString(R.string.borrar_coleccion) to {
						a.confirmar(getString(R.string.confirmar_borrar_coleccion, fila.nombre), getString(R.string.borrar)) {
							db.borrarColeccion(id)
							a.avisar(getString(R.string.coleccion_borrada, fila.nombre))
						}
					},
				)
			}
		}
	}
}
