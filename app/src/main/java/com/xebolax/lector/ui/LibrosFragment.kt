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
import com.xebolax.lector.datos.Carpeta
import com.xebolax.lector.datos.Estado
import com.xebolax.lector.datos.Libro
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.datos.TipoContenido
import com.xebolax.lector.datos.Tipos
import com.xebolax.lector.datos.claveTipo
import com.xebolax.lector.lectura.Abiertos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pestañas con libros:
 * - Leyendo: los abiertos recientemente (el primero, el último que se leyó).
 * - Biblioteca: arriba, qué se quiere ver (todo, libros, documentos,
 *   audiolibros, páginas web o los tipos propios, en el orden y con las
 *   pestañas elegidas en Ajustes); debajo, las carpetas de ese tipo y lo que
 *   hay en la carpeta en la que se está, como en un explorador de archivos (la primera
 *   fila sube a la carpeta anterior, y también Atrás). Al buscar o al elegir
 *   otro filtro (todos, favoritos, leyendo, sin empezar, terminados…) se ve
 *   lo de todas las carpetas junto, cada uno con la carpeta en la que está.
 */
class LibrosFragment : Fragment() {

	enum class Modo { RECIENTES, BIBLIOTECA }

	companion object {
		private const val MODO = "modo"
		/** Pestaña de tipo elegida (se recuerda al volver a la app). */
		private const val PREF_TIPO = "biblioteca_tipo"
		fun nuevo(modo: Modo) = LibrosFragment().apply { arguments = bundleOf(MODO to modo.name) }
	}

	/** El primero (por carpetas) es la vista normal; los demás muestran todo junto. */
	enum class Filtro { CARPETAS, TODOS, FAVORITOS, LEYENDO, NUEVOS, TERMINADOS, QUIERO_LEER, ABANDONADOS, AUTORES, SERIES, COLECCIONES }

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
	private var pestanasTipo: Pestanas? = null
	/** Libros seleccionados (acciones para varios a la vez). */
	private val seleccionados = linkedSetOf<Long>()
	private var carga: Job? = null

	/** De la última carga (calculado fuera del hilo principal): la ruta, el padre y el tipo de la carpeta abierta, y el nombre del grupo abierto. */
	private var cargada: Long? = null
	private var rutaActual = ""
	private var padreActual: Long? = null
	private var tipoCarpetaActual = ""
	private var nombreGrupoActual = ""
	/** Al terminar de cargar, decir en qué carpeta se está (tras abrir una o subir). */
	private var anunciarAlCargar = false

	/** Carpeta abierta (null = raíz de la biblioteca). Los libros que se añaden van a ella. */
	var carpetaActual: Long? = null
		private set

	/** Clave del tipo que se está viendo (Tipos.TODO = todo). Lo que se añade aquí se guarda con ese tipo. */
	var tipoActual: String = Tipos.TODO
		private set

	/** El tipo que se está viendo, o null en «Todo». */
	val tipoVisto: String? get() = tipoActual.takeIf { it != Tipos.TODO }

	/** ¿Se está viendo la biblioteca por carpetas (y no una búsqueda o un filtro)? */
	val porCarpetas: Boolean get() = modo == Modo.BIBLIOTECA && filtro == Filtro.CARPETAS && busqueda.isBlank()

	/**
	 * La biblioteca cambió: se recarga una sola vez aunque lleguen muchos
	 * avisos seguidos (al añadir una carpeta, cada libro avisa al leer sus
	 * datos; antes la lista se rehacía con cada uno y TalkBack saltaba).
	 */
	private val recargaDiferida = Runnable { recargar() }
	private val oyente = BaseDatos.Oyente {
		view?.let { v ->
			v.removeCallbacks(recargaDiferida)
			v.postDelayed(recargaDiferida, 400)
		}
	}

	override fun onCreateView(inflador: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
		_b = FragmentLibrosBinding.inflate(inflador, contenedor, false)
		return b.root
	}

	override fun onViewCreated(vista: View, estado: Bundle?) {
		modo = runCatching { Modo.valueOf(requireArguments().getString(MODO, Modo.RECIENTES.name)) }.getOrDefault(Modo.BIBLIOTECA)
		carpetaActual = estado?.getLong("carpeta", -1)?.takeIf { it >= 0 }
		filtro = estado?.getString("filtro")?.let { runCatching { Filtro.valueOf(it) }.getOrNull() } ?: Filtro.CARPETAS
		tipoActual = if (modo == Modo.BIBLIOTECA) Preferencias(requireContext()).p.getString(PREF_TIPO, null) ?: Tipos.TODO else Tipos.TODO
		val actividad = requireActivity() as AppCompatActivity
		adaptador = AdaptadorLibros(actividad, ::alAbrir, ::accionesDe)
		b.recycler.layoutManager = LinearLayoutManager(requireContext())
		b.recycler.sinFocoPropio()
		b.recycler.sinAnimarCambios()
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
		// Las pestañas pueden haber cambiado en Ajustes (orden, cuáles se ven, tipos nuevos)
		ponerPestanasTipo()
		recargar()
	}

	/** Las pestañas de arriba: las elegidas en Ajustes, en su orden. Si la vista ya no existe, se vuelve a la primera. */
	private fun ponerPestanasTipo() {
		val p = pestanasTipo ?: return
		val c = requireContext()
		val visibles = Tipos.visibles(c)
		if (tipoActual !in visibles) {
			tipoActual = visibles.first()
			carpetaActual = null
		}
		p.ponerNombres(visibles.map { it to Tipos.nombre(c, it) }, tipoActual) { elegirTipo(it) }
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

	private fun construirCabecera() {
		val c = requireContext()
		// Dónde se está: "Biblioteca" o "Documentos › Apuntes › Historia" (encabezado)
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

		// Qué se ve: todo, libros, documentos, audiolibros, páginas web o un tipo propio (pestañas de arriba)
		pestanasTipo = Pestanas(c)
		b.cabecera.addView(pestanasTipo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		ponerPestanasTipo()

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

	/**
	 * Cambia lo que se ve (todo, libros, documentos…). Cada tipo tiene sus
	 * carpetas: si la carpeta abierta es de otro tipo, se vuelve a la raíz.
	 */
	private fun elegirTipo(tipo: String) {
		if (tipo == tipoActual) return
		tipoActual = tipo
		grupo = null
		if (carpetaActual != null && tipoCarpetaActual.isNotEmpty() && tipoCarpetaActual != tipo && tipo != Tipos.TODO) carpetaActual = null
		Preferencias(requireContext()).p.edit().putString(PREF_TIPO, tipo).apply()
		recargar()
	}

	/** Nombre de lo que se ve: «Biblioteca» (todo) o «Documentos». */
	private fun nombreVista(): String = tipoVisto?.let { Tipos.nombre(requireContext(), it) } ?: getString(R.string.pestana_biblioteca)

	private fun actualizarCabecera() {
		val nombres = resources.getStringArray(R.array.filtros_biblioteca)
		botonFiltro?.text = getString(R.string.mostrar_x, nombres[filtro.ordinal])
		botonNuevaCarpeta?.isVisible = porCarpetas || (filtro == Filtro.COLECCIONES && grupo == null && busqueda.isBlank())
		botonNuevaCarpeta?.text = getString(if (filtro == Filtro.COLECCIONES) R.string.nueva_coleccion else R.string.nueva_carpeta)
		botonSeleccion?.isVisible = seleccionados.isNotEmpty()
		botonSeleccion?.text = resources.getQuantityString(R.plurals.seleccionados_acciones, seleccionados.size, seleccionados.size)
		titulo?.text = when {
			busqueda.isNotBlank() -> getString(R.string.resultados_busqueda_biblioteca)
			porGrupos && grupo != null -> nombres[filtro.ordinal] + " › " + nombreGrupoActual.ifBlank { grupo!! }
			filtro != Filtro.CARPETAS -> listOf(nombreVista(), nombres[filtro.ordinal]).joinToString(" › ")
			else -> listOf(nombreVista()).plus(
				rutaActual.takeIf { carpetaActual != null && carpetaActual == cargada && it.isNotBlank() }?.let { listOf(it) }.orEmpty()
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

	private fun nuevaColeccion() {
		val a = activity as? AppCompatActivity ?: return
		a.pedirTexto(getString(R.string.nueva_coleccion), getString(R.string.nombre)) { nombre ->
			if (nombre.isNotBlank()) a.enSegundoPlano({ db.crearColeccion(nombre) }) { a.avisar(a.getString(R.string.coleccion_creada, nombre.trim())) }
		}
	}

	private fun nuevaCarpeta() {
		if (filtro == Filtro.COLECCIONES) return nuevaColeccion()
		val a = activity as? AppCompatActivity ?: return
		val padre = carpetaActual
		val tipo = tipoVisto.orEmpty()
		a.pedirTexto(getString(R.string.nueva_carpeta), getString(R.string.nombre)) { nombre ->
			// En la pestaña de un tipo, la carpeta es de ese tipo (solo se ve ahí y en Todo)
			if (nombre.isNotBlank()) a.enSegundoPlano({ db.crearCarpeta(nombre, padre, tipo) }) { a.avisar(a.getString(R.string.carpeta_creada, nombre)) }
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
		// El padre se sabe de la última carga; si aún no ha llegado la de esta carpeta, se mira (es una consulta pequeña)
		carpetaActual = if (cargada == carpetaActual) padreActual else carpetaActual?.let { db.carpeta(it) }?.padre
		anunciarAlCargar = true
		recargar()
		return true
	}

	/** Lo que se ve, ya calculado fuera del hilo principal. carpeta: la abierta (null si ya no existe). */
	private class Vista(
		val filas: List<Fila>,
		val estado: String,
		val carpeta: Long? = null,
		val ruta: String = "",
		val padre: Long? = null,
		val tipoCarpeta: String = "",
		val nombreCarpeta: String = "",
		val nombreGrupo: String = "",
	)

	fun recargar() {
		if (_b == null) return
		val c = requireContext()
		val orden = Preferencias(c).ordenBiblioteca
		val pedida = carpetaActual
		val vistaCarpetas = porCarpetas
		val texto = busqueda.trim()
		val filtroActual = filtro
		val grupos = porGrupos
		val grupoActual = grupo
		val tipo = tipoVisto
		val subirA = nombreVista()
		val res = c.resources
		val vistaNombre = nombreVista()
		carga?.cancel()
		carga = viewLifecycleOwner.lifecycleScope.launch {
			val vista = withContext(Dispatchers.IO) {
				// Todo de una vez y en memoria: con muchas carpetas y libros, una consulta por fila era muy lenta
				val carpetas = db.todasLasCarpetas()
				val porId = carpetas.associateBy { it.id }
				val hijas = carpetas.groupBy { it.padre }
				// La carpeta abierta puede haberse borrado (desde una búsqueda o en otra pantalla): se vuelve a la raíz
				val carpeta = pedida?.takeIf { porId.containsKey(it) }
				val rutas = HashMap<Long, String>()
				fun ruta(id: Long?): String {
					if (id == null) return ""
					return rutas.getOrPut(id) {
						val nombres = ArrayList<String>()
						var actual = porId[id]
						var vueltas = 0
						while (actual != null && vueltas++ < 30) {
							nombres.add(0, actual.nombre)
							actual = actual.padre?.let { porId[it] }
						}
						nombres.joinToString(" › ")
					}
				}
				val conTipo = tipo == null
				val nombresTipos = Tipos.todos(c).associate { it.clave to it.nombreSingular }
				fun deLibro(l: Libro, conRuta: Boolean = true) = Fila.DeLibro(l, if (conRuta) ruta(l.carpeta) else "", conTipo, nombresTipos[l.claveTipo].orEmpty())

				if (modo == Modo.RECIENTES) {
					val filas = db.recientes().map { Fila.DeLibro(it, ruta(it.carpeta), conTipo = true, nombreTipo = nombresTipos[it.claveTipo].orEmpty()) }
					return@withContext Vista(filas, if (filas.isEmpty()) res.getString(R.string.vacio_recientes) else contar(res, filas.size, null))
				}

				val todos = db.todos(orden)
				val delTipo = if (tipo == null) todos else todos.filter { it.claveTipo == tipo }

				// Cuántos hay de este tipo en cada carpeta, contando sus subcarpetas (y cuántos de cualquier tipo)
				val directos = delTipo.groupingBy { it.carpeta }.eachCount()
				val cualquiera = todos.groupingBy { it.carpeta }.eachCount()
				val total = HashMap<Long, Pair<Int, Int>>()
				fun cuenta(id: Long): Pair<Int, Int> = total.getOrPut(id) {
					var deTipo = directos[id] ?: 0
					var todo = cualquiera[id] ?: 0
					hijas[id].orEmpty().forEach { h -> cuenta(h.id).let { deTipo += it.first; todo += it.second } }
					deTipo to todo
				}
				// Cada tipo, sus carpetas: las creadas en su pestaña y las de cualquier tipo que tienen algo de este
				// (o están vacías, para poder llenarlas). En «Todo», todas.
				fun seVe(h: Carpeta): Boolean {
					if (tipo == null || h.tipo == tipo) return true
					if (h.tipo.isNotEmpty()) return false
					val (deTipo, todo) = cuenta(h.id)
					return deTipo > 0 || todo == 0
				}
				fun deCarpeta(h: Carpeta) = Fila.DeCarpeta(h, cuenta(h.id).first, hijas[h.id].orEmpty().count { seVe(it) }, if (tipo == null) nombresTipos[h.tipo].orEmpty() else "")
				// El nombre del grupo abierto (de una colección, su nombre; de un autor o una serie, el mismo)
				val nombreGrupo = when {
					grupoActual == null -> ""
					filtroActual == Filtro.COLECCIONES -> db.colecciones().firstOrNull { it.id.toString() == grupoActual }?.nombre ?: grupoActual
					else -> grupoActual
				}

				val (filasVista, estadoVista) = when {
					grupos && grupoActual == null -> {
						val filas = when (filtroActual) {
							Filtro.AUTORES -> delTipo.filter { it.autor.isNotBlank() }.groupBy { it.autor.trim().lowercase() }.values
								.map { l -> Fila.Grupo(l.first().autor.trim(), l.first().autor.trim(), l.size) }.sortedBy { it.nombre.lowercase() }
							Filtro.SERIES -> delTipo.filter { it.serie.isNotBlank() }.groupBy { it.serie.trim().lowercase() }.values
								.map { l -> Fila.Grupo(l.first().serie.trim(), l.first().serie.trim(), l.size) }.sortedBy { it.nombre.lowercase() }
							else -> db.colecciones().map { col ->
								val n = if (tipo == null) col.libros else db.deColeccion(col.id, orden).count { it.claveTipo == tipo }
								Fila.Grupo(col.id.toString(), col.nombre, n)
							}
						}
						val estado = if (filas.isEmpty()) res.getString(when (filtroActual) {
							Filtro.COLECCIONES -> R.string.sin_colecciones
							Filtro.SERIES -> R.string.sin_series
							else -> R.string.sin_libros
						}) else contar(res, filas.sumOf { it.libros }, tipo)
						filas to estado
					}
					grupos -> {
						val lista = when (filtroActual) {
							Filtro.AUTORES -> delTipo.filter { it.autor.trim().equals(grupoActual, ignoreCase = true) }
							Filtro.SERIES -> delTipo.filter { it.serie.trim().equals(grupoActual, ignoreCase = true) }.sortedWith(compareBy({ it.numeroSerie }, { it.tituloVisible.lowercase() }))
							else -> db.deColeccion(grupoActual!!.toLongOrNull() ?: -1, orden).filter { tipo == null || it.claveTipo == tipo }
						}
						val nombre = res.getStringArray(R.array.filtros_biblioteca)[filtroActual.ordinal]
						(listOf<Fila>(Fila.Subir(nombre)) + lista.map { deLibro(it) }) to (if (lista.isEmpty()) res.getString(R.string.sin_libros) else contar(res, lista.size, tipo))
					}
					vistaCarpetas -> {
						val filas = mutableListOf<Fila>()
						if (carpeta != null) filas += Fila.Subir(porId[carpeta]?.padre?.let { porId[it]?.nombre } ?: subirA)
						hijas[carpeta].orEmpty().sortedWith(compareBy<Carpeta>({ it.orden }, { it.nombre.lowercase() })).forEach { h ->
							if (seVe(h)) filas += deCarpeta(h)
						}
						val libros = delTipo.filter { it.carpeta == carpeta }
						libros.forEach { filas += deLibro(it, conRuta = false) }
						val nCarpetas = filas.count { it is Fila.DeCarpeta }
						val estado = when {
							filas.isEmpty() && carpeta == null && tipo == null -> res.getString(R.string.vacio_biblioteca)
							filas.isEmpty() && carpeta == null -> res.getString(R.string.vacio_tipo, vistaNombre.lowercase())
							nCarpetas == 0 && libros.isEmpty() -> res.getString(R.string.carpeta_vacia)
							else -> listOfNotNull(
								nCarpetas.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.carpetas, it, it) },
								contar(res, libros.size, tipo),
							).joinToString(", ")
						}
						filas to estado
					}
					else -> {
						val base = if (texto.isBlank()) delTipo else delTipo.filter { l ->
							l.titulo.contains(texto, true) || l.autor.contains(texto, true) || l.nombre.contains(texto, true)
						}
						val filas = mutableListOf<Fila>()
						// Al buscar también salen las carpetas cuyo nombre coincide (solo las que se verían en esta pestaña, con lo que tienen de este tipo)
						if (texto.isNotBlank()) carpetas.filter { it.nombre.contains(texto, ignoreCase = true) && seVe(it) }
							.forEach { h -> filas += deCarpeta(h) }
						val libros = base.filter { l ->
							when (filtroActual) {
								Filtro.FAVORITOS -> l.favorito
								Filtro.LEYENDO -> l.estado == Estado.LEYENDO
								Filtro.NUEVOS -> l.estado == Estado.NUEVO
								Filtro.TERMINADOS -> l.estado == Estado.TERMINADO
								Filtro.QUIERO_LEER -> l.estado == Estado.QUIERO_LEER
								Filtro.ABANDONADOS -> l.estado == Estado.ABANDONADO
								// Todos, o buscando en una vista por grupos: todos los que coinciden
								else -> true
							}
						}
						libros.forEach { filas += deLibro(it) }
						filas to (if (filas.isEmpty()) res.getString(R.string.sin_resultados_tipo, vistaNombre.lowercase()) else listOfNotNull(
							filas.count { it is Fila.DeCarpeta }.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.carpetas, it, it) },
							contar(res, libros.size, tipo),
						).joinToString(", "))
					}
				}
				val abierta = carpeta?.let { porId[it] }
				Vista(filasVista, estadoVista, carpeta, ruta(carpeta), abierta?.padre, abierta?.tipo.orEmpty(), abierta?.nombre.orEmpty(), nombreGrupo)
			}
			if (_b == null) return@launch
			// La carpeta abierta ya no existe: se está en la raíz
			if (modo == Modo.BIBLIOTECA && carpetaActual == pedida) carpetaActual = vista.carpeta
			cargada = vista.carpeta
			rutaActual = vista.ruta
			padreActual = vista.padre
			tipoCarpetaActual = vista.tipoCarpeta
			nombreGrupoActual = vista.nombreGrupo
			// Los libros quitados de la biblioteca dejan de estar seleccionados
			if (seleccionados.isNotEmpty()) {
				val existen = vista.filas.mapNotNull { (it as? Fila.DeLibro)?.libro?.id }.toSet()
				val antes = seleccionados.toList()
				val quitados = withContext(Dispatchers.IO) { antes.filter { it !in existen && db.libro(it)?.takeIf { l -> !l.compartido } == null } }
				seleccionados.removeAll(quitados.toSet())
			}
			adaptador.seleccionados = seleccionados.toSet()
			adaptador.reemplazar(vista.filas)
			actualizarCabecera()
			// Solo si cambia: la línea de estado es una región viva y TalkBack la leería en cada recarga
			if (b.estado.text.toString() != vista.estado) b.estado.text = vista.estado
			// Tras abrir una carpeta o subir: dónde se está
			if (anunciarAlCargar) {
				anunciarAlCargar = false
				(activity as? AppCompatActivity)?.avisar(getString(R.string.carpeta_x, vista.nombreCarpeta.ifBlank { nombreVista() }), mostrar = false)
			}
		}
	}

	/** «3 libros», «1 documento», «5 elementos» (todo y tipos propios). */
	private fun contar(res: android.content.res.Resources, n: Int, tipo: String?): String = res.getQuantityString(when (TipoContenido.de(tipo)) {
		null -> R.plurals.elementos
		TipoContenido.LIBRO -> R.plurals.libros
		TipoContenido.DOCUMENTO -> R.plurals.documentos
		TipoContenido.AUDIOLIBRO -> R.plurals.audiolibros
		TipoContenido.WEB -> R.plurals.paginas_web
	}, n, n)

	private fun alAbrir(fila: Fila) {
		val a = activity as? AppCompatActivity ?: return
		when (fila) {
			// Con libros seleccionados, tocar otro lo selecciona (o lo quita)
			is Fila.DeLibro -> if (seleccionados.isNotEmpty()) alternarSeleccion(fila.libro) else Abiertos.abrirPantalla(a, fila.libro)
			is Fila.DeCompartido -> Abiertos.abrirPantalla(a, fila.libro)
			is Fila.DeCarpeta -> {
				// Desde una búsqueda o un filtro, abrir una carpeta vuelve a la vista por carpetas
				filtro = Filtro.CARPETAS
				carpetaActual = fila.carpeta.id
				if (busqueda.isNotBlank()) {
					busqueda = ""
					campoBusqueda?.setText("")
				}
				anunciarAlCargar = true
				recargar()
			}
			is Fila.Subir -> subir()
			is Fila.Grupo -> {
				grupo = fila.clave
				nombreGrupoActual = fila.nombre
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
		// La base de datos, fuera del hilo principal; después se avisa y se quita la selección
		fun hacer(trabajo: () -> Unit, texto: () -> String) = a.enSegundoPlano(trabajo) { hecho(texto()) }
		val n = ids.size
		a.elegir(resources.getQuantityString(R.plurals.seleccionados_acciones, n, n), listOf(
			getString(R.string.mover_a_carpeta) to {
				AccionesLibro.elegirCarpeta(a, getString(R.string.mover_a_carpeta), null) { destino ->
					a.enSegundoPlano({
						ids.forEach { db.moverLibro(it, destino) }
						destino?.let { db.rutaCarpeta(it) }
					}) { ruta -> hecho(a.getString(R.string.movido_a, ruta ?: a.getString(R.string.biblioteca_raiz))) }
				}
			},
			getString(R.string.cambiar_tipo) to {
				AccionesLibro.elegirTipo(a, null) { tipo ->
					hacer({ db.cambiarTipo(ids, tipo) }) { a.getString(R.string.tipo_cambiado, AccionesLibro.nombreTipo(a, tipo)) }
				}
			},
			getString(R.string.anadir_favoritos) to { hacer({ ids.forEach { db.cambiarFavorito(it, true) } }) { a.getString(R.string.anadido_favoritos) } },
			getString(R.string.quitar_favoritos) to { hacer({ ids.forEach { db.cambiarFavorito(it, false) } }) { a.getString(R.string.quitado_favoritos) } },
			getString(R.string.estado_de_lectura) to {
				AccionesLibro.elegirEstado(a, ids) {
					seleccionados.clear()
					recargar()
				}
			},
			getString(R.string.colecciones_puntos) to { AccionesLibro.elegirColecciones(a, ids, resources.getQuantityString(R.plurals.libros, n, n)) },
			getString(R.string.marcar_terminado) to { hacer({ ids.forEach { db.cambiarEstado(it, Estado.TERMINADO) } }) { a.getString(R.string.hecho) } },
			getString(R.string.marcar_no_leido) to {
				a.confirmar(getString(R.string.confirmar_no_leido), getString(R.string.marcar_no_leido)) {
					hacer({ ids.forEach { db.cambiarEstado(it, Estado.NUEVO) } }) { a.getString(R.string.hecho) }
				}
			},
			getString(R.string.quitar_de_biblioteca) to {
				a.confirmar(resources.getQuantityString(R.plurals.confirmar_quitar_varios, n, n), getString(R.string.quitar)) {
					hacer({ ids.forEach { id -> db.libro(id)?.let { AccionesLibro.quitar(a, id, it.uri) } } }) { a.getString(R.string.quitado_de_biblioteca) }
				}
			},
			getString(R.string.seleccionar_todos) to {
				adaptador.filas.forEach { if (it is Fila.DeLibro) seleccionados += it.libro.id }
				a.avisar(resources.getQuantityString(R.plurals.seleccionados_acciones, seleccionados.size, seleccionados.size))
				adaptador.seleccionados = seleccionados.toSet()
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
			is Fila.DeCompartido -> AccionesCompartido.acciones(a, fila.libro)
			is Fila.DeCarpeta -> AccionesLibro.accionesCarpeta(a, fila.carpeta) { alAbrir(fila) }
			is Fila.Subir -> emptyList()
			is Fila.Grupo -> if (filtro != Filtro.COLECCIONES) listOf(getString(R.string.abrir) to { alAbrir(fila) }) else {
				val id = fila.clave.toLongOrNull() ?: -1
				listOf(
					getString(R.string.abrir) to { alAbrir(fila) },
					getString(R.string.renombrar) to {
						a.pedirTexto(getString(R.string.renombrar), getString(R.string.nombre), fila.nombre) { n ->
							if (n.isNotBlank()) a.enSegundoPlano({ db.renombrarColeccion(id, n) }) { hecho ->
								if (!hecho) a.avisar(a.getString(R.string.coleccion_ya_existe, n.trim()))
							}
						}
					},
					getString(R.string.borrar_coleccion) to {
						a.confirmar(getString(R.string.confirmar_borrar_coleccion, fila.nombre), getString(R.string.borrar)) {
							a.enSegundoPlano({ db.borrarColeccion(id) }) { a.avisar(a.getString(R.string.coleccion_borrada, fila.nombre)) }
						}
					},
				)
			}
		}
	}
}
