package com.xebolax.lector.rss

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.xebolax.lector.R
import com.xebolax.lector.databinding.FragmentLibrosBinding
import com.xebolax.lector.databinding.ItemListaBinding
import com.xebolax.lector.ui.conDeshacer
import com.xebolax.lector.ui.accionesOrden
import com.xebolax.lector.ui.pedirNombreCarpeta
import com.xebolax.lector.ui.elegirCarpeta
import com.xebolax.lector.ui.ZonaAcciones
import com.xebolax.lector.ui.Accion
import com.xebolax.lector.ui.accionesAccesibles
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.conEspera
import com.xebolax.lector.ui.campoTexto
import com.xebolax.lector.ui.confirmar
import com.xebolax.lector.ui.dp
import com.xebolax.lector.ui.sinFocoPropio
import com.xebolax.lector.ui.elegir
import com.xebolax.lector.ui.elegirUno
import com.xebolax.lector.ui.pedirTexto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Una pestaña de Noticias. Cada fila es un solo elemento para TalkBack con
 * todo lo que hay que saber ("No leído, título, fuente, hace 2 horas") y sus
 * acciones en el menú de acciones; tocar abre, mantener pulsado muestra las acciones.
 */
class RssListaFragment : Fragment() {

	enum class Modo(val nombre: Int, val icono: Int) {
		SIN_LEER(R.string.rss_pestana_sin_leer, R.drawable.ic_novedades),
		FUENTES(R.string.rss_pestana_fuentes, R.drawable.ic_rss),
		FAVORITOS(R.string.rss_pestana_favoritos, R.drawable.ic_estrella),
		MAS_TARDE(R.string.rss_pestana_mas_tarde, R.drawable.ic_mas_tarde),
		BUSCAR(R.string.rss_pestana_buscar, R.drawable.ic_buscar),
	}

	companion object {
		fun nuevo(modo: Modo) = RssListaFragment().apply { arguments = bundleOf("modo" to modo.name) }
	}

	// Lo que necesitan las acciones de cada fila se calcula al cargar (fuera del hilo principal), no al dibujarla
	private sealed class Fila {
		class Subir(val nombre: String) : Fila()
		class Categoria(val nombre: String, val sinLeer: Int, val fuentes: Int) : Fila()
		/** vecinas: las fuentes de su misma carpeta, en su orden (para ordenarla entre ellas). */
		class DeFuente(val fuente: Fuente, val vecinas: List<Long>) : Fila()
		/** guardado: ya está en la biblioteca. */
		class DeArticulo(val articulo: Articulo, val guardado: Boolean = false) : Fila()
	}

	private var _b: FragmentLibrosBinding? = null
	private val b get() = _b!!
	private lateinit var modo: Modo
	private val db by lazy { RssBaseDatos.obtener(requireContext()) }
	private var filas: List<Fila> = emptyList()
	private var carga: Job? = null
	/** La búsqueda espera a que se deje de escribir. */
	private var escribiendo: Job? = null

	// Fuentes: dónde se está
	private var categoria: String? = null
	private var fuente: Long? = null
	private var soloSinLeer = true
	// Sin leer: filtro por categoría
	private var filtroCategoria: String? = null
	private var busqueda = ""
	// Calculado al cargar: las carpetas en su orden y el nombre de la fuente abierta
	private var carpetas: List<String> = emptyList()
	private var nombreFuente: String? = null

	private val actividad get() = activity as? RssActivity

	private val oyente = RssBaseDatos.Oyente { view?.post { recargar() } }

	private val atras = object : OnBackPressedCallback(false) {
		override fun handleOnBackPressed() {
			subir()
		}
	}

	override fun onCreateView(inflador: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
		_b = FragmentLibrosBinding.inflate(inflador, contenedor, false)
		return b.root
	}

	override fun onViewCreated(vista: View, estado: Bundle?) {
		modo = Modo.valueOf(requireArguments().getString("modo", Modo.SIN_LEER.name))
		soloSinLeer = Rss.prefs(requireContext()).getBoolean("rss_ocultar_leidos", true)
		b.recycler.layoutManager = LinearLayoutManager(requireContext())
		b.recycler.sinFocoPropio()
		b.recycler.adapter = Adaptador()
		requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, atras)
		construirCabecera()
	}

	override fun onResume() {
		super.onResume()
		db.suscribir(oyente)
		recargar()
		atras.isEnabled = modo == Modo.FUENTES && (categoria != null || fuente != null)
	}

	override fun onPause() {
		db.desuscribir(oyente)
		atras.isEnabled = false
		super.onPause()
	}

	override fun onDestroyView() {
		_b = null
		super.onDestroyView()
	}

	// --- Cabecera ---------------------------------------------------------------------------

	private fun boton(texto: String, accion: () -> Unit) = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
		text = texto
		minHeight = dp(requireContext(), 48)
		setOnClickListener { accion() }
	}

	/** Sin leer: qué se ve (0 sin leer, 1 de hoy, 2 todos, 3 leídos). */
	private var vista = 0

	private fun nombreVista() = resources.getStringArray(R.array.rss_vistas)[vista]

	/** Los artículos que se ven ahora (para exportarlos). */
	fun visibles(): List<Articulo> = articulosVisibles()

	private fun busquedasGuardadas(): List<String> = Rss.prefs(requireContext()).getString("rss_busquedas", "").orEmpty().lines().filter { it.isNotBlank() }

	private fun guardarBusquedas(lista: List<String>) = Rss.prefs(requireContext()).edit().putString("rss_busquedas", lista.distinct().joinToString("\n")).apply()

	private fun construirCabecera() {
		val c = b.cabecera
		c.removeAllViews()
		var fila = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
		fun anadir(v: View) = fila.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		// Más de dos o tres botones no caben bien con letra grande: se pasa a otra fila
		fun otraFila() {
			c.addView(fila)
			fila = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
		}
		when (modo) {
			Modo.BUSCAR -> {
				val (contenedor, campo) = requireContext().campoTexto(getString(R.string.rss_buscar_articulos))
				(contenedor as TextInputLayout).endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
				campo.setText(busqueda)
				campo.addTextChangedListener(object : TextWatcher {
					override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
					override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
					override fun afterTextChanged(s: Editable?) {
						busqueda = s?.toString().orEmpty()
						// Se busca al dejar de escribir (no con cada letra: la línea de estado lo diría tras cada una)
						escribiendo?.cancel()
						escribiendo = viewLifecycleOwner.lifecycleScope.launch {
							delay(600)
							recargar()
						}
					}
				})
				c.addView(contenedor)
				anadir(boton(getString(R.string.rss_guardar_busqueda)) {
					val t = busqueda.trim()
					if (t.length < 2) return@boton actividad?.avisar(getString(R.string.rss_escribe_busqueda)) ?: Unit
					guardarBusquedas(busquedasGuardadas() + t)
					actividad?.avisar(getString(R.string.rss_busqueda_guardada, t))
				})
				anadir(boton(getString(R.string.rss_busquedas_guardadas)) {
					val lista = busquedasGuardadas()
					if (lista.isEmpty()) return@boton actividad?.avisar(getString(R.string.rss_sin_busquedas)) ?: Unit
					requireContext().elegir(getString(R.string.rss_busquedas_guardadas), lista.map { t ->
						t to {
							requireContext().elegir(t, listOf(
								getString(R.string.buscar) to {
									busqueda = t
									construirCabecera()
									recargar()
								},
								getString(R.string.borrar) to { guardarBusquedas(busquedasGuardadas() - t) },
							))
						}
					})
				})
				c.addView(fila)
				return
			}
			Modo.SIN_LEER -> {
				anadir(boton(getString(R.string.rss_leer_todo)) { leerTodo() })
				anadir(boton(getString(R.string.rss_leer_titulares)) { leerTitulares() })
				otraFila()
				anadir(boton(getString(R.string.rss_ver_vista, nombreVista())) {
					requireContext().elegirUno(getString(R.string.rss_ver), resources.getStringArray(R.array.rss_vistas).toList(), vista) { i ->
						vista = i
						construirCabecera()
						recargar()
					}
				})
				// Dice qué es, no solo el nombre de la carpeta: «Carpeta: Deportes»
				anadir(boton(getString(R.string.noticias_filtro_carpeta, filtroCategoria ?: getString(R.string.noticias_filtro_todas))) { elegirFiltro() })
			}
			Modo.FUENTES -> when {
				fuente != null -> {
					anadir(boton(getString(R.string.rss_leer_todo)) { leerTodo() })
				anadir(boton(getString(R.string.rss_leer_titulares)) { leerTitulares() })
				otraFila()
					anadir(boton(getString(if (soloSinLeer) R.string.rss_ver_todos else R.string.rss_ver_sin_leer)) {
						soloSinLeer = !soloSinLeer
						construirCabecera()
						recargar()
					})
					anadir(boton(getString(R.string.rss_opciones_fuente)) {
						val id = fuente ?: return@boton
						// Con sus cuentas (sin leer) y sus vecinas, fuera del hilo principal
						viewLifecycleOwner.lifecycleScope.launch {
							val todas = withContext(Dispatchers.IO) { db.fuentes() }
							val f = todas.firstOrNull { it.id == id } ?: return@launch
							opcionesFuente(f, todas.filter { it.categoria.equals(f.categoria, ignoreCase = true) }.map { it.id })
						}
					})
				}
				categoria != null -> {
					anadir(boton(getString(R.string.rss_leer_todo)) { leerTodo() })
				anadir(boton(getString(R.string.rss_leer_titulares)) { leerTitulares() })
				otraFila()
					anadir(boton(getString(R.string.rss_anadir_fuente_aqui)) { actividad?.anadirFuente(categoria!!) })
					anadir(boton(getString(R.string.opciones_carpeta)) {
						categoria?.let { n -> actividad?.elegir(n, accionesCategoria(n, filas.filterIsInstance<Fila.DeFuente>().sumOf { it.fuente.sinLeer })) }
					})
				}
				// Arriba ya está «Añadir fuente»: aquí, crear carpetas
				else -> anadir(boton(getString(R.string.nueva_carpeta)) { nuevaCarpeta() })
			}
			Modo.FAVORITOS, Modo.MAS_TARDE -> {
				anadir(boton(getString(R.string.rss_leer_todo)) { leerTodo() })
				anadir(boton(getString(R.string.rss_leer_titulares)) { leerTitulares() })
			}
		}
		c.addView(fila)
	}

	private fun elegirFiltro() {
		viewLifecycleOwner.lifecycleScope.launch {
			val categorias = withContext(Dispatchers.IO) { db.categorias() }
			if (_b == null) return@launch
			val nombres = listOf(getString(R.string.rss_todas_categorias)) + categorias
			requireContext().elegirUno(getString(R.string.rss_categoria), nombres, categorias.indexOf(filtroCategoria) + 1) { i ->
				filtroCategoria = if (i == 0) null else categorias[i - 1]
				construirCabecera()
				recargar()
			}
		}
	}

	// --- Datos ---------------------------------------------------------------------------

	private fun articulosVisibles() = filas.mapNotNull { (it as? Fila.DeArticulo)?.articulo }

	private fun recargar() {
		if (_b == null) return
		val antiguos = Rss.prefs(requireContext()).getBoolean("rss_antiguos_primero", false)
		val m = modo
		val cat = categoria
		val fu = fuente
		val solo = soloSinLeer
		val filtro = filtroCategoria
		val texto = busqueda
		val vistaActual = vista
		// Los textos y la base de datos de la biblioteca se toman aquí: en el otro hilo el fragmento puede haberse separado
		val nombreFuentes = getString(R.string.rss_pestana_fuentes)
		val biblioteca = com.xebolax.lector.datos.BaseDatos.obtener(requireContext())
		carga?.cancel()
		carga = viewLifecycleOwner.lifecycleScope.launch {
			var titulo: String? = null
			var ordenCarpetas: List<String> = emptyList()
			val nuevas: List<Fila> = withContext(Dispatchers.IO) {
				// Las fuentes de cada carpeta, en su orden (para «Subir», «Bajar»…)
				fun conVecinas(todas: List<Fuente>, lista: List<Fuente>): List<Fila> {
					val porCarpeta = todas.groupBy { it.categoria.lowercase() }.mapValues { (_, l) -> l.map { it.id } }
					return lista.map { Fila.DeFuente(it, porCarpeta[it.categoria.lowercase()].orEmpty()) }
				}
				// Si ya está en la biblioteca (y sigue ahí), para ofrecer «Abrir en la biblioteca»
				fun deArticulos(lista: List<Articulo>): List<Fila> {
					val guardados = lista.map { it.enBiblioteca }.filter { it > 0 }.distinct().filter { biblioteca.libro(it) != null }.toSet()
					return lista.map { Fila.DeArticulo(it, it.enBiblioteca in guardados) }
				}
				when (m) {
					Modo.SIN_LEER -> deArticulos(when (vistaActual) {
						1 -> db.deHoy(filtro)
						2 -> db.recientes(filtro)
						3 -> db.leidos(filtro)
						else -> db.sinLeer(antiguos, filtro)
					})
					Modo.FAVORITOS -> deArticulos(db.favoritos())
					Modo.MAS_TARDE -> deArticulos(db.masTarde())
					Modo.BUSCAR -> if (texto.trim().length < 2) emptyList() else deArticulos(db.buscar(texto))
					Modo.FUENTES -> {
						ordenCarpetas = db.categorias()
						when {
							fu != null -> {
								val f = db.fuente(fu) ?: return@withContext emptyList()
								titulo = f.titulo
								listOf<Fila>(Fila.Subir(cat ?: nombreFuentes)) + deArticulos(db.deFuente(fu, solo, antiguos))
							}
							cat != null -> {
								val todas = db.fuentes()
								listOf<Fila>(Fila.Subir(nombreFuentes)) + conVecinas(todas, todas.filter { it.categoria.equals(cat, ignoreCase = true) })
							}
							else -> {
								// Las carpetas en su orden (también las vacías) y después las fuentes sin carpeta
								val todas = db.fuentes()
								val lista = ordenCarpetas.let { c -> c + todas.map { it.categoria }.filter { n -> n.isNotBlank() && c.none { it.equals(n, ignoreCase = true) } }.distinct() }
								lista.map { n ->
									val l = todas.filter { it.categoria.equals(n, ignoreCase = true) }
									Fila.Categoria(n, l.sumOf { it.sinLeer }, l.size)
								} + conVecinas(todas, todas.filter { it.categoria.isBlank() })
							}
						}
					}
				}
			}
			if (_b == null) return@launch
			filas = nuevas
			carpetas = ordenCarpetas
			nombreFuente = titulo
			@Suppress("NotifyDataSetChanged")
			b.recycler.adapter?.notifyDataSetChanged()
			// Solo si cambia: la línea de estado es una región viva y TalkBack la repetiría en cada recarga
			estado().let { if (b.estado.text.toString() != it) b.estado.text = it }
			b.estado.isVisible = true
		}
	}

	private fun estado(): String {
		val n = articulosVisibles().size
		return when {
			modo == Modo.BUSCAR && busqueda.trim().length < 2 -> getString(R.string.rss_buscar_ayuda_articulos)
			modo == Modo.FUENTES && fuente == null -> {
				val f = filas.count { it is Fila.DeFuente } + filas.filterIsInstance<Fila.Categoria>().sumOf { it.fuentes }
				if (f == 0) getString(R.string.rss_sin_fuentes) else resources.getQuantityString(R.plurals.rss_n_fuentes, f, f)
			}
			n == 0 -> getString(
				when (modo) {
					Modo.SIN_LEER -> R.string.rss_vacio_sin_leer
					Modo.FAVORITOS -> R.string.rss_vacio_favoritos
					Modo.MAS_TARDE -> R.string.rss_vacio_mas_tarde
					else -> R.string.rss_vacio
				}
			)
			else -> resources.getQuantityString(R.plurals.rss_n_articulos, n, n)
		}
	}

	private fun subir() {
		when {
			fuente != null -> fuente = null
			categoria != null -> categoria = null
		}
		atras.isEnabled = categoria != null || fuente != null
		construirCabecera()
		recargar()
		b.recycler.scrollToPosition(0)
	}

	private fun entrarCategoria(n: String) {
		categoria = n
		atras.isEnabled = true
		construirCabecera()
		recargar()
		(activity as? AppCompatActivity)?.avisar(n, mostrar = false)
	}

	/** Abre una fuente (desde otra pestaña con «Ir a la fuente»). */
	fun abrirFuente(id: Long) {
		lifecycleScope.launch {
			val f = withContext(Dispatchers.IO) { db.fuente(id) } ?: return@launch
			if (_b == null) return@launch
			categoria = f.categoria.ifBlank { null }
			entrarFuente(f)
		}
	}

	private fun entrarFuente(f: Fuente) {
		fuente = f.id
		if (categoria == null && f.categoria.isNotBlank()) categoria = f.categoria
		atras.isEnabled = true
		construirCabecera()
		recargar()
		(activity as? AppCompatActivity)?.avisar(f.titulo, mostrar = false)
	}

	// --- Acciones ---------------------------------------------------------------------------

	private fun leerTodo() {
		val a = activity as? AppCompatActivity ?: return
		val lista = articulosVisibles()
		val titulo = when {
			fuente != null -> nombreFuente ?: getString(R.string.noticias)
			categoria != null -> categoria!!
			else -> getString(modo.nombre)
		}
		RssAcciones.leerSeguidos(a, titulo, lista, marcarLeidos = modo != Modo.FAVORITOS && modo != Modo.MAS_TARDE)
	}

	/** Solo los titulares, seguidos en voz alta; cada uno lleva a su artículo. */
	private fun leerTitulares() {
		val a = activity as? AppCompatActivity ?: return
		val titulo = when {
			fuente != null -> nombreFuente ?: getString(R.string.noticias)
			categoria != null -> categoria!!
			else -> getString(modo.nombre)
		}
		RssAcciones.leerTitulares(a, getString(R.string.rss_titulares_de, titulo), articulosVisibles())
	}

	private fun opcionesFuente(f: Fuente, vecinas: List<Long>) {
		actividad?.elegir(f.titulo, accionesFuente(f, vecinas))
	}

	/** Mover a otra carpeta: las carpetas se leen al pulsar, fuera del hilo principal. */
	private fun moverACarpeta(a: RssActivity, f: Fuente) {
		a.lifecycleScope.launch {
			val lista = withContext(Dispatchers.IO) { db.categorias() }
			a.elegirCarpeta(getString(R.string.mover_a_carpeta), lista, f.categoria) { destino ->
				RssAcciones.enFondo(a, {
					db.ponerCategoria(f.id, destino)
					// Movida por el usuario: si era de ejemplo, ya es suya (se sube a la cuenta)
					Rss.tocarFuente(a, f.id)
				}) {
					a.avisar(if (destino.isBlank()) a.getString(R.string.sin_carpeta) else a.getString(R.string.movida_a_carpeta, destino))
				}
			}
		}
	}

	/** Marca como leído (todo lo de una fuente o una carpeta) fuera del hilo principal, con Deshacer. */
	private fun marcarTodo(a: RssActivity, fuente: Long? = null, categoria: String? = null) {
		a.lifecycleScope.launch {
			val ids = withContext(Dispatchers.IO) { db.marcarTodoLeido(fuente = fuente, categoria = categoria) }
			if (ids.isEmpty()) return@launch a.avisar(a.getString(R.string.noticias_nada_sin_leer))
			a.conDeshacer(a.resources.getQuantityString(R.plurals.rss_marcados_leidos, ids.size, ids.size)) {
				RssAcciones.enFondo(a, { db.marcarLeidos(ids, false) })
			}
		}
	}

	/**
	 * Lo que se puede hacer con una fuente (su menú y, si el usuario las añade, acciones de TalkBack).
	 * vecinas: las fuentes de su misma carpeta, en su orden (se calculan al cargar, no al dibujar la fila).
	 */
	private fun accionesFuente(f: Fuente, vecinas: List<Long>): List<Accion> {
		val a = actividad ?: return emptyList()
		return listOf<Accion>(
			getString(R.string.mover_a_carpeta) to { moverACarpeta(a, f) },
		) + a.accionesOrden(vecinas, f.id) { nueva -> RssAcciones.enFondo(a, { db.ordenarFuentes(nueva) }) } + listOf(
			getString(R.string.abrir) to { entrarFuente(f) },
			getString(R.string.rss_actualizar) to {
				lifecycleScope.launch {
					// Si está enlazada con el servidor de sincronización, se sincroniza (no se duplican sus artículos); después, las reglas
					val n = runCatching { a.conEspera(getString(R.string.rss_actualizar), getString(R.string.rss_actualizando)) { Rss.actualizarUna(a, f) } }
						.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; -1 }
					a.avisar(if (n < 0) a.getString(R.string.rss_error_fuente, f.titulo) else a.resources.getQuantityString(R.plurals.rss_nuevos, n, n))
				}
				Unit
			},
		) + listOfNotNull<Accion>(
			// Solo si hay algo sin leer (no se ofrece lo que ya está hecho)
			(getString(R.string.rss_marcar_todo_leido) to {
				a.confirmar(getString(R.string.confirmar_marcar_leido_x, f.titulo), getString(R.string.rss_marcar_todo_leido)) { marcarTodo(a, fuente = f.id) }
			}).takeIf { f.sinLeer > 0 },
		) + listOf<Accion>(
			getString(R.string.rss_editar_fuente) to { a.editarFuente(f) },
			getString(R.string.rss_info_fuente) to {
				val texto = listOfNotNull(
					f.url, f.sitio.takeIf { it.isNotBlank() },
					getString(R.string.rss_ultima_actualizacion, Rss.fechaLegible(f.ultima).ifBlank { "—" }),
					f.error.takeIf { it.isNotBlank() }?.let { getString(R.string.rss_ultimo_error, it) },
					resources.getQuantityString(R.plurals.rss_n_articulos, f.total, f.total),
				).joinToString("\n")
				com.google.android.material.dialog.MaterialAlertDialogBuilder(a).setTitle(f.titulo).setMessage(texto).setPositiveButton(R.string.aceptar, null).show()
			},
			getString(R.string.abrir_navegador) to { RssAcciones.abrirNavegador(a, f.sitio.ifBlank { f.url }) },
			getString(R.string.rss_borrar_fuente) to {
				a.confirmar(getString(R.string.rss_confirmar_borrar_fuente, f.titulo), getString(R.string.borrar)) {
					a.lifecycleScope.launch {
						val remoto = withContext(Dispatchers.IO) {
							db.remotosDeFuentes()[f.id].also { db.borrarFuente(f.id) }
						}
						// También en el servidor de sincronización, si estaba enlazada
						remoto?.let { r -> a.lifecycleScope.launch { RssSincronizacion.borrarFuente(a, r) } }
						if (fuente == f.id && _b != null) subir()
						a.avisar(a.getString(R.string.rss_fuente_borrada))
					}
				}
			},
		)
	}

	/**
	 * Lo que se puede hacer con una carpeta. sinLeer: sus artículos sin leer (para no
	 * ofrecer «Marcar todo como leído» si no hay ninguno). El orden de las carpetas se calcula al cargar.
	 */
	private fun accionesCategoria(n: String, sinLeer: Int): List<Accion> {
		val a = actividad ?: return emptyList()
		return listOf<Accion>(
			getString(R.string.abrir) to { entrarCategoria(n) },
			getString(R.string.rss_leer_todo) to {
				a.lifecycleScope.launch {
					val lista = withContext(Dispatchers.IO) { db.deCategoria(n, soloSinLeer = true) }
					RssAcciones.leerSeguidos(a, n, lista)
				}
			},
		) + listOfNotNull<Accion>(
			(getString(R.string.rss_marcar_todo_leido) to {
				a.confirmar(getString(R.string.confirmar_marcar_leido_x, n), getString(R.string.rss_marcar_todo_leido)) { marcarTodo(a, categoria = n) }
			}).takeIf { sinLeer > 0 },
		) + listOf<Accion>(
			getString(R.string.renombrar) to {
				a.pedirNombreCarpeta(getString(R.string.renombrar), n) { nuevo ->
					a.lifecycleScope.launch {
						val guardado = withContext(Dispatchers.IO) {
							db.renombrarCategoria(n, nuevo)
							db.categorias().firstOrNull { it.equals(nuevo, ignoreCase = true) } ?: nuevo
						}
						if (_b == null) return@launch
						if (categoria == n) categoria = guardado
						construirCabecera()
						a.avisar(getString(R.string.carpeta_renombrada, nuevo))
					}
				}
			},
			getString(R.string.borrar_carpeta) to { borrarCarpeta(n) },
		) + a.accionesOrden(carpetas, carpetas.firstOrNull { it.equals(n, ignoreCase = true) } ?: n) { nueva ->
			RssAcciones.enFondo(a, { db.ordenarCarpetas(nueva) })
		}
	}

	private fun nuevaCarpeta() {
		val a = actividad ?: return
		a.pedirNombreCarpeta(getString(R.string.nueva_carpeta)) { nombre ->
			a.lifecycleScope.launch {
				val (ya, n) = withContext(Dispatchers.IO) {
					val ya = db.categorias().any { it.equals(nombre, ignoreCase = true) }
					ya to db.crearCarpeta(nombre)
				}
				a.avisar(a.getString(if (ya) R.string.carpeta_ya_existe else R.string.carpeta_creada, n))
			}
		}
	}

	/** Borrar una carpeta: si tiene fuentes, se pregunta si borrarlas también o dejarlas sin carpeta. */
	private fun borrarCarpeta(n: String) {
		val a = actividad ?: return
		a.lifecycleScope.launch {
			val suyas = withContext(Dispatchers.IO) { db.fuentes().count { it.categoria.equals(n, ignoreCase = true) } }
			borrarCarpeta(a, n, suyas)
		}
	}

	private fun borrarCarpeta(a: RssActivity, n: String, suyas: Int) {
		val hecho = {
			if (categoria.equals(n, ignoreCase = true) && _b != null) subir()
			a.avisar(a.getString(R.string.carpeta_borrada_x, n))
		}
		if (suyas == 0) return a.confirmar(getString(R.string.confirmar_borrar_carpeta_vacia, n), getString(R.string.borrar)) {
			RssAcciones.enFondo(a, { db.borrarCarpeta(n, conFuentes = false) }, hecho)
		}
		a.elegir(getString(R.string.borrar_carpeta_x, n), listOf<Accion>(
			resources.getQuantityString(R.plurals.borrar_solo_carpeta, suyas, suyas) to {
				RssAcciones.enFondo(a, { db.borrarCarpeta(n, conFuentes = false) }, hecho)
			},
			resources.getQuantityString(R.plurals.borrar_carpeta_y_fuentes, suyas, suyas) to {
				a.confirmar(resources.getQuantityString(R.plurals.confirmar_borrar_carpeta_y_fuentes, suyas, n, suyas), getString(R.string.borrar)) {
					borrarCarpetaYFuentes(a, n, hecho)
				}
			},
		))
	}

	/** Borra la carpeta con sus fuentes (también en el servidor de sincronización las que estaban enlazadas). */
	private fun borrarCarpetaYFuentes(a: RssActivity, n: String, hecho: () -> Unit) {
		a.lifecycleScope.launch {
			val remotosBorrados = withContext(Dispatchers.IO) {
				val remotos = db.remotosDeFuentes()
				db.borrarCarpeta(n, conFuentes = true).mapNotNull { remotos[it.id] }
			}
			remotosBorrados.forEach { r -> a.lifecycleScope.launch { RssSincronizacion.borrarFuente(a, r) } }
			hecho()
		}
	}

	private fun accionesArticulo(art: Articulo, guardado: Boolean, posicion: Int): List<Accion> {
		val a = activity as? AppCompatActivity ?: return emptyList()
		val lista = RssAcciones.acciones(a, art, { articulosVisibles().map { it.id }.toLongArray() }, guardado).toMutableList()
		// Marcar como leídos los de debajo (en esta lista)
		val debajo = filas.drop(posicion + 1).mapNotNull { (it as? Fila.DeArticulo)?.articulo?.takeIf { x -> !x.leido }?.id }
		if (debajo.isNotEmpty()) lista += getString(R.string.rss_marcar_debajo) to { marcarVarios(a, debajo) }
		// Y los de encima (lo ya pasado al bajar por la lista)
		val encima = filas.take(posicion).mapNotNull { (it as? Fila.DeArticulo)?.articulo?.takeIf { x -> !x.leido }?.id }
		if (encima.isNotEmpty()) lista += getString(R.string.rss_marcar_encima) to { marcarVarios(a, encima) }
		if (modo != Modo.FUENTES || fuente == null) lista += getString(R.string.rss_ir_fuente) to { actividad?.irAFuente(art.fuente) }
		return lista
	}

	/** Marca como leídos varios artículos fuera del hilo principal, con Deshacer. */
	private fun marcarVarios(a: AppCompatActivity, ids: List<Long>) {
		RssAcciones.enFondo(a, { db.marcarLeidos(ids) }) {
			a.conDeshacer(a.resources.getQuantityString(R.plurals.rss_marcados_leidos, ids.size, ids.size)) {
				RssAcciones.enFondo(a, { db.marcarLeidos(ids, false) })
			}
		}
	}

	// --- Lista -------------------------------------------------------------------------------

	private fun descripcion(art: Articulo): Pair<String, String> {
		val estado = listOfNotNull(
			getString(R.string.rss_no_leido).takeIf { !art.leido },
			getString(R.string.rss_favorito).takeIf { art.favorito },
			getString(R.string.rss_en_mas_tarde).takeIf { art.masTarde },
			getString(R.string.rss_con_audio).takeIf { art.esPodcast },
		)
		val cuando = if (art.fecha > 0) DateUtils.getRelativeTimeSpanString(art.fecha, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() else ""
		val fuenteTexto = if (modo == Modo.FUENTES && fuente != null) "" else art.nombreFuente
		val detalle = listOf(fuenteTexto, cuando).filter { it.isNotBlank() }.joinToString(" · ")
		// Qué se dice de cada artículo (Ajustes, Noticias): la fuente, la fecha y el resumen
		val p = Rss.prefs(requireContext())
		// Tiempo de lectura (unas 180 palabras por minuto en voz alta); no en los podcasts, que dicen su duración
		val lectura = if (p.getBoolean("rss_decir_duracion", true) && !art.esPodcast && art.palabras >= 60) {
			val minutos = ((art.palabras + 179) / 180).coerceAtLeast(1)
			resources.getQuantityString(R.plurals.rss_minutos_lectura, minutos, minutos)
		} else ""
		val resumen = if (p.getBoolean("rss_decir_resumen", false)) org.jsoup.Jsoup.parse(art.resumen.ifBlank { art.contenido }).text().take(300) else ""
		val voz = (estado + listOf(art.titulo, fuenteTexto.takeIf { p.getBoolean("rss_decir_fuente", true) }.orEmpty(),
			cuando.takeIf { p.getBoolean("rss_decir_fecha", true) }.orEmpty(), lectura, resumen)).filter { it.isNotBlank() }.joinToString(", ")
		return listOf(detalle, lectura).filter { it.isNotBlank() }.joinToString(" · ") to voz
	}

	private inner class Vista(val f: ItemListaBinding) : RecyclerView.ViewHolder(f.root)

	// Ids estables: al recargar la lista (tras marcar como leído, por ejemplo) TalkBack
	// no pierde el sitio, porque cada fila sigue siendo la misma
	private inner class Adaptador : RecyclerView.Adapter<Vista>() {
		init {
			setHasStableIds(true)
		}

		override fun getItemId(posicion: Int): Long = when (val f = filas[posicion]) {
			is Fila.Subir -> -1L
			is Fila.Categoria -> -2_000_000L - (f.nombre.hashCode().toLong() and 0xFFFFFF)
			is Fila.DeFuente -> -1_000_000_000L - f.fuente.id
			is Fila.DeArticulo -> f.articulo.id
		}

		override fun getItemCount() = filas.size

		override fun onCreateViewHolder(padre: ViewGroup, tipo: Int) = Vista(ItemListaBinding.inflate(LayoutInflater.from(padre.context), padre, false))

		override fun onBindViewHolder(v: Vista, posicion: Int) {
			val fila = filas[posicion]
			val a = activity as? AppCompatActivity ?: return
			val (texto, detalle, voz) = when (fila) {
				is Fila.Subir -> Triple(getString(R.string.subir_a, fila.nombre), "", getString(R.string.subir_a, fila.nombre))
				is Fila.Categoria -> {
					val d = if (fila.fuentes == 0) getString(R.string.carpeta_vacia_corto) else listOf(
						resources.getQuantityString(R.plurals.rss_n_sin_leer, fila.sinLeer, fila.sinLeer),
						resources.getQuantityString(R.plurals.rss_n_fuentes, fila.fuentes, fila.fuentes),
					).joinToString(", ")
					Triple(fila.nombre, d, getString(R.string.carpeta_x, fila.nombre) + ", " + d)
				}
				is Fila.DeFuente -> {
					val f = fila.fuente
					val estado = listOfNotNull(
						getString(R.string.rss_pausada_corto).takeIf { f.pausada },
						getString(R.string.rss_con_error).takeIf { f.error.isNotBlank() },
					)
					val sinLeer = resources.getQuantityString(R.plurals.rss_n_sin_leer, f.sinLeer, f.sinLeer)
					val d = (listOf(sinLeer) + estado).joinToString(", ")
					// TalkBack: el estado primero («pausada, con error, El País, 3 sin leer»)
					val nombre = f.titulo.ifBlank { f.url }
					Triple(nombre, d, (estado + listOf(nombre, sinLeer)).joinToString(", "))
				}
				is Fila.DeArticulo -> {
					val (d, vz) = descripcion(fila.articulo)
					Triple(fila.articulo.titulo, d, vz)
				}
			}
			v.f.texto.text = texto
			v.f.texto.setTypeface(null, if (fila is Fila.DeArticulo && !fila.articulo.leido) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
			v.f.detalle.text = detalle
			v.f.detalle.isVisible = detalle.isNotBlank()
			v.f.root.contentDescription = voz
			ViewCompat.setStateDescription(v.f.root, null)
			v.f.root.setOnClickListener {
				when (fila) {
					is Fila.Subir -> subir()
					is Fila.Categoria -> entrarCategoria(fila.nombre)
					is Fila.DeFuente -> entrarFuente(fila.fuente)
					is Fila.DeArticulo -> RssAcciones.abrir(a, fila.articulo, articulosVisibles().map { it.id }.toLongArray())
				}
			}
			val acciones: List<Accion> = when (fila) {
				is Fila.Subir -> emptyList()
				is Fila.Categoria -> accionesCategoria(fila.nombre, fila.sinLeer)
				is Fila.DeFuente -> listOf(getString(R.string.rss_opciones_fuente) to { opcionesFuente(fila.fuente, fila.vecinas) }) + accionesFuente(fila.fuente, fila.vecinas)
				is Fila.DeArticulo -> accionesArticulo(fila.articulo, fila.guardado, posicion)
			}
			// Cada tipo de fila con las acciones que el usuario eligió para su zona (Ajustes, Acciones de TalkBack)
			v.f.root.accionesAccesibles(acciones, when (fila) {
				is Fila.Categoria -> ZonaAcciones.CATEGORIA
				is Fila.DeFuente -> ZonaAcciones.FUENTE
				is Fila.DeArticulo -> ZonaAcciones.ARTICULO
				else -> null
			})
			v.f.root.setOnLongClickListener {
				when {
					fila is Fila.DeFuente -> { opcionesFuente(fila.fuente, fila.vecinas); true }
					acciones.isEmpty() -> false
					else -> { a.elegir(texto.take(60), acciones); true }
				}
			}
		}
	}
}
