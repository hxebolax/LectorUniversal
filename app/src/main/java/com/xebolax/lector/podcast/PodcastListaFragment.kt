package com.xebolax.lector.podcast

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.xebolax.lector.R
import com.xebolax.lector.databinding.FragmentLibrosBinding
import com.xebolax.lector.databinding.ItemListaBinding
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
import com.xebolax.lector.ui.pedirTexto
import com.xebolax.lector.ui.elegirUno
import com.xebolax.lector.ui.mensaje
import com.xebolax.lector.ui.tiempoCorto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Una pestaña de Podcasts. Cada fila es un solo elemento para TalkBack
 * ("Nuevo, título, podcast, hace 2 días, 45 minutos") con sus acciones en el
 * menú de acciones; tocar escucha o abre, mantener pulsado muestra las acciones.
 */
class PodcastListaFragment : Fragment() {

	enum class Modo(val nombre: Int, val icono: Int) {
		COLA(R.string.pod_pestana_cola, R.drawable.ic_cola),
		NOVEDADES(R.string.pod_pestana_novedades, R.drawable.ic_novedades),
		SUSCRIPCIONES(R.string.pod_pestana_suscripciones, R.drawable.ic_voz),
		/** Descargas, Favoritos o Historial, según se elija arriba. */
		EPISODIOS(R.string.pod_pestana_episodios, R.drawable.ic_lista),
		EN_CURSO(R.string.pod_pestana_en_curso, R.drawable.ic_reproducir),
		DESCARGAS(R.string.pod_pestana_descargas, R.drawable.ic_descargar),
		FAVORITOS(R.string.pod_pestana_favoritos, R.drawable.ic_estrella),
		HISTORIAL(R.string.pod_pestana_historial, R.drawable.ic_recientes),
		DESCUBRIR(R.string.pod_pestana_descubrir, R.drawable.ic_globo),
	}

	companion object {
		/** Las pestañas de abajo: cinco, para que se vean todas. */
		val PESTANAS = listOf(Modo.SUSCRIPCIONES, Modo.NOVEDADES, Modo.COLA, Modo.EPISODIOS, Modo.DESCUBRIR)
		private val VISTAS = listOf(Modo.EN_CURSO, Modo.DESCARGAS, Modo.FAVORITOS, Modo.HISTORIAL)
		/** Pestañas de episodios que se pueden filtrar por carpeta. */
		private val CON_FILTRO = setOf(Modo.COLA, Modo.NOVEDADES, Modo.EN_CURSO, Modo.DESCARGAS, Modo.FAVORITOS, Modo.HISTORIAL)

		fun nuevo(modo: Modo) = PodcastListaFragment().apply { arguments = bundleOf("modo" to modo.name) }
	}

	private sealed class Fila {
		class Subir(val nombre: String = "") : Fila()
		class Carpeta(val nombre: String, val sinEscuchar: Int, val podcasts: Int) : Fila()
		class DePodcast(val podcast: Podcast) : Fila()
		class DeEpisodio(val episodio: Episodio) : Fila()
		class DeSugerencia(val sugerencia: Podcasts.Sugerencia) : Fila()
		class Encontrado(val e: Podcasts.EpisodioEncontrado) : Fila()
	}

	private var _b: FragmentLibrosBinding? = null
	private val b get() = _b!!
	private lateinit var modo: Modo
	private val db by lazy { PodcastsBaseDatos.obtener(requireContext()) }
	private var filas: List<Fila> = emptyList()
	private var carga: Job? = null
	private var podcast: Long? = null
	/** Suscripciones: la carpeta abierta (null = la lista principal). */
	private var carpeta: String? = null
	private var soloSinEscuchar = true
	private var categoria = 0
	private var sugerencias: List<Podcasts.Sugerencia> = emptyList()
	/** Descubrir: resultados de una búsqueda (null: los más escuchados). */
	private var resultados: List<Podcasts.Sugerencia>? = null
	private var episodiosEncontrados: List<Podcasts.EpisodioEncontrado>? = null
	private var textoBuscado = ""
	private var busqueda = ""
	private var vista = Modo.DESCARGAS
	/** Filtro por carpeta en las pestañas de episodios: null = todas; "" = los podcasts sin carpeta. */
	private var filtroCarpeta: String? = null

	/** Lo que muestra la pestaña: en Episodios, la vista elegida. */
	private val efectivo get() = if (modo == Modo.EPISODIOS) vista else modo

	private val actividad get() = activity as? PodcastActivity
	private val oyente = PodcastsBaseDatos.Oyente { view?.post { recargar() } }
	private val atras = object : OnBackPressedCallback(false) {
		override fun handleOnBackPressed() = subir()
	}

	override fun onCreateView(inflador: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
		_b = FragmentLibrosBinding.inflate(inflador, contenedor, false)
		return b.root
	}

	override fun onViewCreated(vista: View, estado: Bundle?) {
		modo = Modo.valueOf(requireArguments().getString("modo", Modo.COLA.name))
		b.recycler.layoutManager = LinearLayoutManager(requireContext())
		b.recycler.sinFocoPropio()
		b.recycler.adapter = Adaptador()
		requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, atras)
		construirCabecera()
		if (modo == Modo.DESCUBRIR) cargarMasEscuchados()
	}

	override fun onResume() {
		super.onResume()
		db.suscribir(oyente)
		recargar()
		atras.isEnabled = podcast != null || carpeta != null
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

	// --- Cabecera ------------------------------------------------------------------------

	private fun boton(texto: String, accion: () -> Unit) = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
		text = texto
		minHeight = dp(requireContext(), 48)
		setOnClickListener { accion() }
	}

	private fun construirCabecera() {
		val c = b.cabecera
		c.removeAllViews()
		val fila = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
		fun anadir(v: View) = fila.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		val a = actividad
		if (modo == Modo.EPISODIOS) c.addView(boton(getString(R.string.pod_ver, getString(vista.nombre))) {
			requireContext().elegirUno(getString(R.string.pod_pestana_episodios), VISTAS.map { getString(it.nombre) }, VISTAS.indexOf(vista)) { i ->
				vista = VISTAS[i]
				busqueda = ""
				construirCabecera()
				recargar()
			}
		})
		// Ver solo los episodios de una carpeta (si hay carpetas)
		if (efectivo in CON_FILTRO && db.carpetas().isNotEmpty()) c.addView(boton(getString(R.string.etiqueta_valor, getString(R.string.carpeta),
			when (filtroCarpeta) {
				null -> getString(R.string.todas_las_carpetas)
				"" -> getString(R.string.sin_carpeta)
				else -> filtroCarpeta!!
			})) {
			val carpetas = db.carpetas()
			val nombres = listOf(getString(R.string.todas_las_carpetas), getString(R.string.sin_carpeta)) + carpetas
			val actual = when (filtroCarpeta) {
				null -> 0
				"" -> 1
				else -> carpetas.indexOfFirst { it.equals(filtroCarpeta, ignoreCase = true) } + 2
			}
			requireContext().elegirUno(getString(R.string.carpeta), nombres, actual) { i ->
				filtroCarpeta = when (i) {
					0 -> null
					1 -> ""
					else -> carpetas[i - 2]
				}
				construirCabecera()
				recargar()
			}
		})
		when (efectivo) {
			Modo.COLA -> {
				anadir(boton(getString(R.string.pod_escuchar_cola)) { db.cola().firstOrNull()?.let { escuchar(it) } ?: a?.avisar(getString(R.string.pod_cola_vacia)) })
				anadir(boton(getString(R.string.pod_ordenar_cola)) { ordenarCola() })
				anadir(boton(getString(R.string.pod_vaciar_cola)) {
					a?.confirmar(getString(R.string.pod_confirmar_vaciar_cola), getString(R.string.pod_vaciar_cola)) { db.vaciarCola() }
				})
			}
			Modo.NOVEDADES -> {
				anadir(boton(getString(R.string.pod_todo_a_cola)) {
					val ids = db.novedades().reversed().map { it.id }
					if (ids.isEmpty()) return@boton a?.avisar(getString(R.string.pod_vacio_novedades)) ?: Unit
					db.anadirACola(ids)
					a?.avisar(resources.getQuantityString(R.plurals.pod_anadidos_cola, ids.size, ids.size))
				})
				anadir(boton(getString(R.string.pod_vaciar_novedades)) {
					db.vaciarNovedades()
					a?.avisar(getString(R.string.pod_novedades_vaciadas))
				})
			}
			Modo.SUSCRIPCIONES -> if (podcast != null) {
				anadir(boton(getString(if (soloSinEscuchar) R.string.rss_ver_todos else R.string.pod_ver_sin_escuchar)) {
					soloSinEscuchar = !soloSinEscuchar
					construirCabecera()
					recargar()
				})
				anadir(boton(getString(R.string.pod_opciones_podcast)) { db.podcast(podcast!!)?.let { opcionesPodcast(it) } })
				// Orden de sus episodios: los más nuevos o los más antiguos primero (series)
				db.podcast(podcast!!)?.let { p ->
					anadir(boton(getString(if (p.antiguosPrimero) R.string.pod_orden_antiguos else R.string.pod_orden_nuevos)) {
						db.ordenEpisodios(p.id, !p.antiguosPrimero)
						a?.avisar(getString(if (p.antiguosPrimero) R.string.pod_orden_nuevos else R.string.pod_orden_antiguos))
						construirCabecera()
						recargar()
					})
				}
				c.addView(fila)
				val (contenedor, campo) = requireContext().campoTexto(getString(R.string.pod_buscar_episodios))
				(contenedor as TextInputLayout).endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
				campo.setText(busqueda)
				campo.addTextChangedListener(object : TextWatcher {
					override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
					override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
					override fun afterTextChanged(s: Editable?) {
						busqueda = s?.toString().orEmpty()
						recargar()
					}
				})
				c.addView(contenedor)
				return
			} else {
				if (carpeta != null) anadir(boton(getString(R.string.opciones_carpeta)) { carpeta?.let { n -> a?.elegir(n, accionesCarpeta(n)) } })
				else anadir(boton(getString(R.string.nueva_carpeta)) { nuevaCarpeta() })
				// Orden de las suscripciones: a mano, por nombre, por episodios sin escuchar o por lo último publicado
				val nombres = resources.getStringArray(R.array.pod_ordenes).toList()
				val actual = Podcasts.prefs(requireContext()).getInt("pod_orden_suscripciones", 0).coerceIn(0, nombres.size - 1)
				anadir(boton(getString(R.string.etiqueta_valor, getString(R.string.orden), nombres[actual])) {
					requireContext().elegirUno(getString(R.string.orden), nombres, actual) { i ->
						Podcasts.prefs(requireContext()).edit().putInt("pod_orden_suscripciones", i).apply()
						construirCabecera()
						recargar()
					}
				})
			}
			Modo.DESCARGAS -> anadir(boton(getString(R.string.pod_borrar_escuchados)) {
				val c = requireContext().applicationContext
				viewLifecycleOwner.lifecycleScope.launch {
					val n = withContext(Dispatchers.IO) { Podcasts.borrarEscuchados(c) }
					a?.avisar(resources.getQuantityString(R.plurals.pod_descargas_borradas, n, n))
				}
			})
			Modo.DESCUBRIR -> if (resultados != null || episodiosEncontrados != null) {
				anadir(boton(getString(R.string.pod_nueva_busqueda)) { a?.buscar() })
				anadir(boton(getString(R.string.pod_volver_mas_escuchados)) {
					resultados = null
					episodiosEncontrados = null
					construirCabecera()
					recargar()
				})
			} else {
				anadir(boton(getString(R.string.pod_buscar)) { a?.buscar() })
				anadir(boton(getString(R.string.pod_buscar_episodios_todos)) { buscarEpisodios() })
				anadir(boton(getString(Podcasts.CATEGORIAS.first { it.first == categoria }.second)) { elegirCategoria() })
				// Los más escuchados van por país: se dice cuál y se puede cambiar
				anadir(boton(getString(R.string.pod_pais_x, com.xebolax.lector.datos.Pais.nombre(com.xebolax.lector.datos.Pais.podcasts(requireContext())))) { elegirPais() })
			}
			Modo.FAVORITOS, Modo.HISTORIAL -> {
				val (contenedor, campo) = requireContext().campoTexto(getString(R.string.pod_buscar_episodios))
				(contenedor as TextInputLayout).endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
				campo.setText(busqueda)
				campo.addTextChangedListener(object : TextWatcher {
					override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
					override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
					override fun afterTextChanged(s: Editable?) {
						busqueda = s?.toString().orEmpty()
						recargar()
					}
				})
				if (efectivo == Modo.FAVORITOS || efectivo == Modo.HISTORIAL) {
					c.addView(contenedor)
					return
				}
			}
			else -> Unit
		}
		c.addView(fila)
	}

	/** Busca episodios de cualquier podcast y los muestra aquí. */
	private fun buscarEpisodios() {
		val a = actividad ?: return
		a.pedirTexto(getString(R.string.pod_buscar_episodios_todos), getString(R.string.pod_buscar_ayuda)) { texto ->
			if (texto.isBlank()) return@pedirTexto
			viewLifecycleOwner.lifecycleScope.launch {
				val lista = runCatching { a.conEspera(getString(R.string.pod_buscar_episodios_todos), getString(R.string.buscando_x, texto)) { Podcasts.buscarEpisodios(texto) } }
					.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
				if (lista.isNullOrEmpty()) return@launch a.mensaje(getString(R.string.pod_buscar_episodios_todos), getString(R.string.rss_sin_resultados))
				episodiosEncontrados = lista
				resultados = null
				textoBuscado = texto
				construirCabecera()
				recargar()
				b.recycler.scrollToPosition(0)
			}
		}
	}

	private fun accionesEncontrado(x: Podcasts.EpisodioEncontrado): List<Accion> {
		val a = actividad ?: return emptyList()
		val e = x.elemento
		val r = mutableListOf<Accion>(
			getString(R.string.pod_escuchar_sin_suscribir) to { lifecycleScope.launch { Podcasts.escucharSinSuscribir(a, x.podcast, e) } },
			getString(R.string.pod_notas) to { Podcasts.abrirTexto(a, "notas_encontrado", e.titulo, Podcasts.htmlNotas(a, x.podcast, e)) },
			getString(R.string.pod_ver_ficha) to {
				if (x.urlPodcast.isNotBlank()) PodcastFichaActivity.abrir(a, x.urlPodcast, x.podcast)
				else PodcastFichaActivity.abrir(a, Podcasts.Sugerencia(x.idPodcast, x.podcast, "", "", "", 0, ""))
			},
			getString(R.string.compartir) to {
				startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
					.putExtra(Intent.EXTRA_TEXT, listOf(e.titulo, x.podcast, e.enlace.ifBlank { e.audio }).filter { it.isNotBlank() }.joinToString("\n")), getString(R.string.compartir)))
			},
		)
		return r
	}

	/** Muestra los resultados de una búsqueda en Descubrir. */
	fun mostrarBusqueda(texto: String, lista: List<Podcasts.Sugerencia>) {
		episodiosEncontrados = null
		resultados = lista
		textoBuscado = texto
		if (_b == null) return
		construirCabecera()
		recargar()
		b.recycler.scrollToPosition(0)
	}

	/** País de los más escuchados y de las búsquedas: el detectado o uno elegido. */
	private fun elegirPais() {
		val c = requireContext()
		val detectado = com.xebolax.lector.datos.Pais.detectado(c)
		val paises = com.xebolax.lector.datos.Pais.todos()
		val nombres = listOf(getString(R.string.pod_pais_automatico, com.xebolax.lector.datos.Pais.nombre(detectado))) + paises.map { it.second }
		val elegido = androidx.preference.PreferenceManager.getDefaultSharedPreferences(c).getString("pod_pais", "").orEmpty()
		c.elegirUno(getString(R.string.pod_pais), nombres, if (elegido.isBlank()) 0 else paises.indexOfFirst { it.first == elegido } + 1) { i ->
			com.xebolax.lector.datos.Pais.ponerPodcasts(c, if (i == 0) "" else paises[i - 1].first)
			construirCabecera()
			cargarMasEscuchados()
		}
	}

	private fun elegirCategoria() {
		val nombres = Podcasts.CATEGORIAS.map { getString(it.second) }
		requireContext().elegirUno(getString(R.string.pod_categoria), nombres, Podcasts.CATEGORIAS.indexOfFirst { it.first == categoria }) { i ->
			categoria = Podcasts.CATEGORIAS[i].first
			construirCabecera()
			cargarMasEscuchados()
		}
	}

	/** El error al cargar Los más escuchados (para no quedarse en «Cargando…» para siempre). */
	private var errorCarga: String? = null

	private fun cargarMasEscuchados() {
		errorCarga = null
		b.estado.text = getString(R.string.cargando)
		viewLifecycleOwner.lifecycleScope.launch {
			sugerencias = try {
				Podcasts.masEscuchados(categoria)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				// Se salió de la pestaña mientras cargaba: ya no hay vista
				errorCarga = getString(R.string.error_catalogo, e.message.orEmpty())
				_b?.estado?.text = errorCarga
				return@launch
			}
			recargar()
		}
	}

	// --- Datos -----------------------------------------------------------------------------

	private fun episodiosVisibles() = filas.mapNotNull { (it as? Fila.DeEpisodio)?.episodio }

	/** Los podcasts suscritos (se leen en cada recarga, fuera del hilo principal). */
	private var misPodcasts: List<Podcast> = emptyList()

	/** ¿Estás suscrito a esta sugerencia? Con la lista ya leída (sin consultar la base de datos al dibujar). */
	private fun suscritoA(s: Podcasts.Sugerencia): Podcast? =
		(if (s.url.isNotBlank()) misPodcasts.firstOrNull { it.url == s.url } else null)
			?: misPodcasts.firstOrNull { it.titulo.trim().equals(s.titulo.trim(), ignoreCase = true) }

	private fun recargar() {
		if (_b == null) return
		val m = efectivo
		val p = podcast
		val car = carpeta
		val solo = soloSinEscuchar
		val texto = busqueda.trim()
		carga?.cancel()
		carga = viewLifecycleOwner.lifecycleScope.launch {
			// Los podcasts suscritos, una vez por recarga (las filas los consultan al dibujarse: sin base de datos en el hilo principal)
			misPodcasts = withContext(Dispatchers.IO) { db.podcasts() }
			val nuevas: List<Fila> = withContext(Dispatchers.IO) {
				fun filtrar(l: List<Episodio>) = if (texto.length < 2) l else l.filter {
					it.titulo.contains(texto, true) || it.nombrePodcast.contains(texto, true)
				}
				when (m) {
					Modo.COLA -> db.cola().map { Fila.DeEpisodio(it) }
					Modo.NOVEDADES -> db.novedades().map { Fila.DeEpisodio(it) }
					Modo.SUSCRIPCIONES -> when {
						p != null -> listOf<Fila>(Fila.Subir(car ?: "")) +
							filtrar(db.dePodcast(p, solo && texto.length < 2, antiguosPrimero = db.podcast(p)?.antiguosPrimero == true)).map { Fila.DeEpisodio(it) }
						car != null -> listOf<Fila>(Fila.Subir()) + ordenados(db.podcasts().filter { it.carpeta.equals(car, ignoreCase = true) }).map { Fila.DePodcast(it) }
						else -> {
							// Las carpetas en su orden (también las vacías) y después los podcasts sin carpeta
							val todos = ordenados(db.podcasts())
							db.carpetas().map { n ->
								val l = todos.filter { it.carpeta.equals(n, ignoreCase = true) }
								Fila.Carpeta(n, l.sumOf { it.sinEscuchar }, l.size)
							} + todos.filter { it.carpeta.isBlank() || db.carpetas().none { n -> n.equals(it.carpeta, ignoreCase = true) } }.map { Fila.DePodcast(it) }
						}
					}
					Modo.EN_CURSO -> db.enCurso().map { Fila.DeEpisodio(it) }
					Modo.DESCARGAS -> db.descargados().map { Fila.DeEpisodio(it) }
					Modo.FAVORITOS -> filtrar(db.favoritos()).map { Fila.DeEpisodio(it) }
					Modo.HISTORIAL -> filtrar(if (texto.length >= 2) db.buscar(texto) else db.historial()).map { Fila.DeEpisodio(it) }
					Modo.DESCUBRIR -> episodiosEncontrados?.map { Fila.Encontrado(it) } ?: (resultados ?: sugerencias).map { Fila.DeSugerencia(it) }
					Modo.EPISODIOS -> emptyList()
				}
			}
			if (_b == null) return@launch
			// Filtro por carpeta: solo los episodios de los podcasts de esa carpeta
			val filtro = filtroCarpeta
			filas = if (filtro == null || m !in CON_FILTRO) nuevas else {
				val carpetas = db.carpetas()
				val suyos = db.podcasts().filter { p ->
					if (filtro.isEmpty()) p.carpeta.isBlank() || carpetas.none { it.equals(p.carpeta, ignoreCase = true) } else p.carpeta.equals(filtro, ignoreCase = true)
				}.map { it.id }.toSet()
				nuevas.filter { it !is Fila.DeEpisodio || it.episodio.podcast in suyos }
			}
			@Suppress("NotifyDataSetChanged")
			b.recycler.adapter?.notifyDataSetChanged()
			// Solo si cambia: la línea de estado es una región viva y TalkBack la repetiría en cada recarga
			estado().let { if (b.estado.text.toString() != it) b.estado.text = it }
		}
	}

	private fun estado(): String {
		val n = episodiosVisibles().size
		return when {
			modo == Modo.DESCUBRIR && episodiosEncontrados != null -> getString(R.string.resultados_de, episodiosEncontrados!!.size, textoBuscado)
			modo == Modo.DESCUBRIR && resultados != null -> getString(R.string.resultados_de, resultados!!.size, textoBuscado)
			modo == Modo.DESCUBRIR -> if (sugerencias.isEmpty()) errorCarga ?: getString(R.string.cargando) else getString(R.string.pod_descubrir_ayuda)
			modo == Modo.SUSCRIPCIONES && podcast == null -> {
				val p = filas.count { it is Fila.DePodcast } + filas.filterIsInstance<Fila.Carpeta>().sumOf { it.podcasts }
				if (p == 0) getString(R.string.pod_sin_suscripciones) else resources.getQuantityString(R.plurals.pod_n_podcasts, p, p)
			}
			n == 0 -> getString(
				when (efectivo) {
					Modo.COLA -> R.string.pod_cola_vacia
					Modo.NOVEDADES -> R.string.pod_vacio_novedades
					Modo.EN_CURSO -> R.string.pod_vacio_en_curso
					Modo.DESCARGAS -> R.string.pod_vacio_descargas
					Modo.FAVORITOS -> R.string.pod_vacio_favoritos
					Modo.HISTORIAL -> R.string.pod_vacio_historial
					else -> R.string.rss_vacio
				}
			)
			efectivo == Modo.COLA -> {
				val queda = episodiosVisibles().sumOf { (it.duracion - it.posicion).coerceAtLeast(0) }
				resources.getQuantityString(R.plurals.pod_n_episodios, n, n) + if (queda > 0) ", " + getString(R.string.pod_quedan, tiempoCorto(queda)) else ""
			}
			else -> resources.getQuantityString(R.plurals.pod_n_episodios, n, n)
		}
	}

	/** Los podcasts en el orden elegido (a mano es el de la base de datos). */
	private fun ordenados(lista: List<Podcast>): List<Podcast> = when (Podcasts.prefs(requireContext()).getInt("pod_orden_suscripciones", 0)) {
		1 -> lista.sortedBy { it.titulo.lowercase() }
		2 -> lista.sortedByDescending { it.sinEscuchar }
		3 -> db.ultimoEpisodio().let { u -> lista.sortedByDescending { u[it.id] ?: 0L } }
		else -> lista
	}

	/** Ordenar la cola de una vez (en lugar de subir y bajar episodio a episodio). */
	private fun ordenarCola() {
		val a = actividad ?: return
		val cola = db.cola()
		if (cola.size < 2) return a.avisar(getString(R.string.pod_cola_vacia))
		val opciones = listOf<Pair<Int, (List<Episodio>) -> List<Episodio>>>(
			R.string.pod_cola_antiguos to { l -> l.sortedBy { it.fecha } },
			R.string.pod_cola_nuevos to { l -> l.sortedByDescending { it.fecha } },
			R.string.pod_cola_cortos to { l -> l.sortedBy { if (it.duracion > 0) it.duracion - it.posicion else Long.MAX_VALUE } },
			R.string.pod_cola_largos to { l -> l.sortedByDescending { it.duracion - it.posicion } },
			R.string.pod_cola_por_podcast to { l -> l.sortedWith(compareBy<Episodio> { it.nombrePodcast.lowercase() }.thenBy { it.fecha }) },
			R.string.pod_cola_invertir to { l -> l.reversed() },
			R.string.pod_cola_azar to { l -> l.shuffled() },
		)
		a.elegir(getString(R.string.pod_ordenar_cola), opciones.map { (texto, orden) ->
			getString(texto) to {
				db.ordenarCola(orden(cola).map { it.id })
				a.avisar(getString(R.string.pod_cola_ordenada))
			}
		})
	}

	private fun subir() {
		// Desde un podcast se vuelve a su carpeta; desde una carpeta, a la lista principal
		if (podcast != null) podcast = null else carpeta = null
		atras.isEnabled = podcast != null || carpeta != null
		construirCabecera()
		recargar()
	}

	/** Abre un podcast (desde otra pestaña con «Ir al podcast»). */
	fun abrirPodcast(id: Long) {
		db.podcast(id)?.let { entrarPodcast(it) }
	}

	private fun entrarCarpeta(n: String) {
		carpeta = n
		atras.isEnabled = true
		construirCabecera()
		recargar()
		actividad?.avisar(getString(R.string.carpeta_x, n), mostrar = false)
	}

	private fun nuevaCarpeta() {
		val a = actividad ?: return
		a.pedirNombreCarpeta(getString(R.string.nueva_carpeta)) { nombre ->
			val ya = db.carpetas().any { it.equals(nombre, ignoreCase = true) }
			val n = db.crearCarpeta(nombre)
			a.avisar(getString(if (ya) R.string.carpeta_ya_existe else R.string.carpeta_creada, n))
		}
	}

	/** Lo que se puede hacer con una carpeta de podcasts. */
	private fun accionesCarpeta(n: String): List<Accion> {
		val a = actividad ?: return emptyList()
		val carpetas = db.carpetas()
		return listOf<Accion>(
			getString(R.string.abrir) to { entrarCarpeta(n) },
			getString(R.string.pod_actualizar_carpeta) to {
				lifecycleScope.launch {
					val suyos = db.podcasts().filter { it.carpeta.equals(n, ignoreCase = true) }
					val nuevos = runCatching {
						a.conEspera(getString(R.string.rss_actualizar), getString(R.string.rss_actualizando)) { suyos.sumOf { p -> runCatching { Podcasts.actualizar(a, p).size }.getOrDefault(0) } }
					}.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; 0 }
					a.avisar(resources.getQuantityString(R.plurals.pod_nuevos, nuevos, nuevos))
				}
			},
			getString(R.string.pod_marcar_todo_escuchado) to {
				db.podcasts().filter { it.carpeta.equals(n, ignoreCase = true) }.forEach { db.marcarTodoEscuchado(it.id) }
				a.avisar(getString(R.string.hecho))
			},
			getString(R.string.renombrar) to {
				a.pedirNombreCarpeta(getString(R.string.renombrar), n) { nuevo ->
					db.renombrarCarpeta(n, nuevo)
					if (carpeta == n) carpeta = db.carpetas().firstOrNull { it.equals(nuevo, ignoreCase = true) } ?: nuevo
					a.avisar(getString(R.string.carpeta_renombrada, nuevo))
				}
			},
			getString(R.string.borrar_carpeta) to { borrarCarpeta(n) },
		) + a.accionesOrden(carpetas, carpetas.firstOrNull { it.equals(n, ignoreCase = true) } ?: n) { db.ordenarCarpetas(it) }
	}

	/** Borrar una carpeta: si tiene podcasts, se pregunta si borrar también sus suscripciones o dejarlos sin carpeta. */
	private fun borrarCarpeta(n: String) {
		val a = actividad ?: return
		val suyos = db.podcasts().count { it.carpeta.equals(n, ignoreCase = true) }
		val hecho = {
			if (carpeta.equals(n, ignoreCase = true)) {
				carpeta = null
				podcast = null
				atras.isEnabled = false
				construirCabecera()
			}
			a.avisar(getString(R.string.carpeta_borrada_x, n))
		}
		if (suyos == 0) return a.confirmar(getString(R.string.confirmar_borrar_carpeta_vacia, n), getString(R.string.borrar)) {
			db.borrarCarpeta(n, conPodcasts = false)
			hecho()
		}
		a.elegir(getString(R.string.borrar_carpeta_x, n), listOf<Accion>(
			resources.getQuantityString(R.plurals.borrar_solo_carpeta_pod, suyos, suyos) to {
				db.borrarCarpeta(n, conPodcasts = false)
				hecho()
			},
			resources.getQuantityString(R.plurals.borrar_carpeta_y_podcasts, suyos, suyos) to {
				a.confirmar(resources.getQuantityString(R.plurals.confirmar_borrar_carpeta_y_podcasts, suyos, n, suyos), getString(R.string.borrar)) {
					for (p in db.borrarCarpeta(n, conPodcasts = true)) {
						db.dePodcast(p.id, false).filter { it.descargado || it.descarga != 0L }.forEach { Podcasts.borrarDescarga(a, it) }
						db.borrarPodcast(p.id)
					}
					hecho()
				}
			},
		))
	}

	private fun entrarPodcast(p: Podcast) {
		// Desde otra pestaña («Ir al podcast»), se entra por su carpeta para que Subir vuelva a ella
		carpeta = p.carpeta.ifBlank { null }
		podcast = p.id
		atras.isEnabled = true
		construirCabecera()
		recargar()
		actividad?.avisar(p.titulo, mostrar = false)
	}

	// --- Acciones ------------------------------------------------------------------------

	/** Los capítulos sin reproducir; al elegir uno, empieza a sonar desde ahí. */
	private fun verCapitulos(e: Episodio) {
		val a = actividad ?: return
		lifecycleScope.launch {
			val lista = a.conEspera(getString(R.string.pod_ver_capitulos), getString(R.string.cargando)) { Podcasts.capitulosDe(a, e) }
			if (lista.isEmpty()) return@launch a.mensaje(getString(R.string.pod_ver_capitulos), getString(R.string.pod_sin_capitulos))
			a.elegir(getString(R.string.pod_capitulos_de, e.titulo), lista.map { (titulo, ms) ->
				"${titulo.ifBlank { "—" }}, ${tiempoCorto(ms)}" to {
					db.guardarPosicion(e.id, ms, e.duracion)
					escuchar(e.copy(posicion = ms, estado = if (e.terminado) 0 else e.estado))
				}
			})
		}
	}

	private fun escuchar(e: Episodio) {
		val c = activity ?: return
		lifecycleScope.launch { Podcasts.escuchar(c, e) }
	}

	private fun opcionesPodcast(p: Podcast) {
		actividad?.elegir(p.titulo, accionesPodcast(p))
	}

	/** Lo que se puede hacer con un podcast (su menú y, si el usuario las añade, acciones de TalkBack). */
	private fun accionesPodcast(p: Podcast): List<Accion> {
		val a = actividad ?: return emptyList()
		// Los de su misma carpeta, para ordenarlo entre ellos
		val vecinos = db.podcasts().filter { it.carpeta.equals(p.carpeta, ignoreCase = true) }
		return listOf<Accion>(
			getString(R.string.mover_a_carpeta) to {
				a.elegirCarpeta(getString(R.string.mover_a_carpeta), db.carpetas(), p.carpeta) { destino ->
					db.ponerCarpeta(p.id, destino)
					a.avisar(if (destino.isBlank()) getString(R.string.sin_carpeta) else getString(R.string.movida_a_carpeta, destino))
				}
			},
		) + a.accionesOrden(vecinos.map { it.id }, p.id) { db.ordenarPodcasts(it) } + listOf(
			getString(R.string.abrir) to { entrarPodcast(p) },
			getString(R.string.rss_actualizar) to {
				lifecycleScope.launch {
					val n = runCatching { a.conEspera(getString(R.string.rss_actualizar), getString(R.string.rss_actualizando)) { Podcasts.actualizar(a, p).size } }
						.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; -1 }
					a.avisar(if (n < 0) getString(R.string.rss_error_fuente, p.titulo) else resources.getQuantityString(R.plurals.pod_nuevos, n, n))
				}
			},
			getString(R.string.pod_ajustes_podcast) to { a.ajustesPodcast(p) },
			getString(R.string.pod_marcar_todo_escuchado) to {
				db.marcarTodoEscuchado(p.id)
				a.avisar(getString(R.string.hecho))
			},
			getString(R.string.pod_info) to {
				val texto = listOfNotNull(p.autor.takeIf { it.isNotBlank() }, p.descripcion.takeIf { it.isNotBlank() },
					getString(R.string.rss_ultima_actualizacion, com.xebolax.lector.rss.Rss.fechaLegible(p.ultima).ifBlank { "—" }),
					p.error.takeIf { it.isNotBlank() }?.let { getString(R.string.rss_ultimo_error, it) }, p.url).joinToString("\n\n")
				MaterialAlertDialogBuilder(a).setTitle(p.titulo).setMessage(texto).setPositiveButton(R.string.aceptar, null).show()
			},
			getString(R.string.abrir_navegador) to { runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(p.sitio.ifBlank { p.url }))) } },
			getString(R.string.pod_borrar_suscripcion) to {
				a.confirmar(getString(R.string.pod_confirmar_borrar, p.titulo), getString(R.string.borrar)) {
					db.dePodcast(p.id, false).filter { it.descargado || it.descarga != 0L }.forEach { Podcasts.borrarDescarga(a, it) }
					db.borrarPodcast(p.id)
					if (podcast == p.id) subir()
					a.avisar(getString(R.string.pod_suscripcion_borrada))
				}
			},
		)
	}

	private fun accionesEpisodio(e: Episodio, posicion: Int): List<Accion> {
		val a = actividad ?: return emptyList()
		val r = mutableListOf<Accion>(getString(R.string.pod_escuchar) to { escuchar(e) })
		if (e.enCola) {
			if (modo == Modo.COLA) {
				r += getString(R.string.pod_subir_cola) to { db.moverEnCola(e.id, paso = -1); a.avisar(getString(R.string.pod_posicion_cola, (e.cola - 1).coerceAtLeast(1))) }
				r += getString(R.string.pod_bajar_cola) to { db.moverEnCola(e.id, paso = 1); a.avisar(getString(R.string.pod_posicion_cola, (e.cola + 1).coerceAtMost(db.cola().size))) }
				r += getString(R.string.pod_al_principio) to { db.moverEnCola(e.id, aPosicion = 0); a.avisar(getString(R.string.pod_posicion_cola, 1)) }
				r += getString(R.string.pod_al_final) to { db.moverEnCola(e.id, aPosicion = Int.MAX_VALUE); a.avisar(getString(R.string.hecho)) }
			}
			r += getString(R.string.pod_quitar_cola) to { db.quitarDeCola(listOf(e.id)); a.avisar(getString(R.string.pod_quitado_cola)) }
		} else {
			r += getString(R.string.pod_anadir_cola) to { db.anadirACola(listOf(e.id)); a.avisar(getString(R.string.pod_anadido_cola)) }
			r += getString(R.string.pod_anadir_cola_principio) to { db.anadirACola(listOf(e.id), alPrincipio = true); a.avisar(getString(R.string.pod_anadido_cola)) }
		}
		when {
			e.descargado -> r += getString(R.string.pod_borrar_descarga) to { Podcasts.borrarDescarga(a, e); a.avisar(getString(R.string.pod_descarga_borrada)) }
			e.descarga != 0L -> r += getString(R.string.pod_cancelar_descarga) to { Podcasts.borrarDescarga(a, e); a.avisar(getString(R.string.pod_descarga_cancelada)) }
			else -> r += getString(if (e.fallos >= 3) R.string.pod_reintentar_descarga else R.string.pod_descargar) to {
				if (e.fallos >= 3) db.fallosDescarga(e.id, 0)
				if (Podcasts.esperaWifi(a)) a.elegir(getString(R.string.pod_sin_wifi), listOf(
					getString(R.string.pod_descargar_con_datos) to { Podcasts.descargar(a, e, datosMoviles = true); a.avisar(getString(R.string.pod_descargando, e.titulo)) },
					getString(R.string.pod_esperar_wifi) to { Podcasts.descargar(a, e); a.avisar(getString(R.string.pod_esperando_wifi_aviso, e.titulo)) },
				)) else {
					Podcasts.descargar(a, e)
					a.avisar(getString(R.string.pod_descargando, e.titulo))
				}
			}
		}
		r += (if (e.terminado) getString(R.string.pod_marcar_no_escuchado) else getString(R.string.pod_marcar_escuchado)) to {
			db.marcarEscuchados(listOf(e.id), !e.terminado)
			a.avisar(getString(if (e.terminado) R.string.pod_marcado_no_escuchado else R.string.pod_marcado_escuchado))
		}
		if (e.nuevo) r += getString(R.string.pod_quitar_novedades) to { db.quitarDeNovedades(listOf(e.id)); a.avisar(getString(R.string.hecho)) }
		r += (if (e.favorito) getString(R.string.rss_quitar_favorito) else getString(R.string.rss_anadir_favorito)) to {
			db.marcarFavorito(e.id, !e.favorito)
			a.avisar(getString(if (e.favorito) R.string.rss_quitado_favorito else R.string.rss_anadido_favorito))
		}
		r += getString(R.string.pod_notas) to { Podcasts.abrirTexto(a, "notas_${e.id}", e.titulo, Podcasts.htmlNotas(a, e)) }
		if (e.transcripcion.isNotBlank()) r += getString(R.string.pod_transcripcion) to {
			lifecycleScope.launch {
				val html = runCatching { a.conEspera(getString(R.string.pod_transcripcion), getString(R.string.cargando)) { Podcasts.htmlTranscripcion(a, e) } }
					.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull() ?: return@launch a.mensaje(getString(R.string.pod_transcripcion), getString(R.string.pod_sin_transcripcion))
				Podcasts.abrirTexto(a, "transcripcion_${e.id}", getString(R.string.pod_transcripcion_de, e.titulo), html)
			}
		}
		r += getString(R.string.compartir) to {
			val texto = listOf(e.titulo, e.nombrePodcast, e.enlace.ifBlank { e.audio }).filter { it.isNotBlank() }.joinToString("\n")
			startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), getString(R.string.compartir)))
		}
		r += getString(R.string.pod_ver_capitulos) to { verCapitulos(e) }
		// Escuchar seguido desde aquí (del más antiguo al más nuevo) y marcar los anteriores o los más nuevos,
		// sin tener que seleccionar uno a uno
		r += getString(R.string.pod_escuchar_desde_aqui) to {
			val todos = db.dePodcast(e.podcast, false, antiguosPrimero = true)
			val desde = todos.dropWhile { it.id != e.id }.filter { it.id == e.id || !it.terminado }
			db.anadirACola(desde.map { it.id }, alPrincipio = true)
			a.avisar(resources.getQuantityString(R.plurals.pod_anadidos_cola, desde.size, desde.size))
			escuchar(e)
		}
		r += getString(R.string.pod_marcar_anteriores) to {
			val ids = db.dePodcast(e.podcast, true, antiguosPrimero = true).filter { it.fecha < e.fecha }.map { it.id }
			db.marcarEscuchados(ids, true)
			a.avisar(resources.getQuantityString(R.plurals.pod_marcados_escuchados, ids.size, ids.size))
		}
		r += getString(R.string.pod_marcar_posteriores) to {
			val ids = db.dePodcast(e.podcast, true).filter { it.fecha > e.fecha }.map { it.id }
			db.marcarEscuchados(ids, true)
			a.avisar(resources.getQuantityString(R.plurals.pod_marcados_escuchados, ids.size, ids.size))
		}
		if (modo != Modo.SUSCRIPCIONES) r += getString(R.string.pod_ir_podcast) to { a.irAPodcast(e.podcast) }
		return r
	}

	// --- Lista ---------------------------------------------------------------------------

	private fun descripcion(e: Episodio): Pair<String, String> {
		val c = requireContext()
		val progreso = Podcasts.progresoDescarga(c, e)
		val estado = listOfNotNull(
			getString(R.string.pod_nuevo).takeIf { e.nuevo },
			if (modo == Modo.COLA) getString(R.string.pod_posicion_cola, e.cola) else getString(R.string.pod_en_cola).takeIf { e.enCola },
			when {
				e.descargado -> getString(R.string.pod_descargado)
				progreso == Podcasts.ESPERANDO_WIFI -> getString(R.string.pod_esperando_wifi)
				progreso >= 0 -> getString(R.string.pod_descargando_n, progreso)
				e.fallos >= 3 -> getString(R.string.pod_descarga_fallida)
				else -> null
			},
			when {
				e.terminado -> getString(R.string.pod_escuchado)
				e.posicion > 0 && e.duracion > 0 -> getString(R.string.pod_escuchado_n, (e.posicion * 100 / e.duracion).toInt())
				e.posicion > 0 -> getString(R.string.pod_empezado)
				else -> null
			},
			getString(R.string.rss_favorito).takeIf { e.favorito },
		)
		val cuando = if (e.fecha > 0) DateUtils.getRelativeTimeSpanString(e.fecha, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() else ""
		val duracion = if (e.duracion > 0) tiempoCorto(e.duracion) else ""
		val podcastTexto = if (modo == Modo.SUSCRIPCIONES) "" else e.nombrePodcast
		val numero = if (e.numero > 0) (if (e.temporada > 0) getString(R.string.pod_temporada_episodio, e.temporada, e.numero) else getString(R.string.pod_episodio_n, e.numero)) else ""
		val detalle = listOf(podcastTexto, numero, cuando, duracion).filter { it.isNotBlank() }.joinToString(" · ")
		val voz = (estado + listOf(e.titulo, podcastTexto, numero, cuando, duracion)).filter { it.isNotBlank() }.joinToString(", ")
		return (estado.joinToString(", ") + (if (estado.isNotEmpty() && detalle.isNotBlank()) " · " else "") + detalle) to voz
	}

	private inner class Vista(val f: ItemListaBinding) : RecyclerView.ViewHolder(f.root)

	// Ids estables: al recargar (tras marcar, descargar o mover en la cola) TalkBack no pierde el sitio
	private inner class Adaptador : RecyclerView.Adapter<Vista>() {
		init {
			setHasStableIds(true)
		}

		override fun getItemId(posicion: Int): Long = when (val f = filas[posicion]) {
			is Fila.Subir -> -1L
			is Fila.Carpeta -> -4_000_000_000L - (f.nombre.lowercase().hashCode().toLong() and 0x7fffffff)
			is Fila.DePodcast -> -1_000_000_000L - f.podcast.id
			is Fila.DeEpisodio -> f.episodio.id
			is Fila.DeSugerencia -> -2_000_000_000L - f.sugerencia.id
			is Fila.Encontrado -> -3_000_000_000L - (f.e.elemento.audio.hashCode().toLong() and 0x7fffffff)
		}

		override fun getItemCount() = filas.size

		override fun onCreateViewHolder(padre: ViewGroup, tipo: Int) = Vista(ItemListaBinding.inflate(LayoutInflater.from(padre.context), padre, false))

		override fun onBindViewHolder(v: Vista, posicion: Int) {
			val fila = filas[posicion]
			val a = actividad ?: return
			val (texto, detalle, voz) = when (fila) {
				is Fila.Subir -> getString(R.string.subir_a, fila.nombre.ifBlank { getString(R.string.pod_pestana_suscripciones) }).let { Triple(it, "", it) }
				is Fila.Carpeta -> {
					val d = if (fila.podcasts == 0) getString(R.string.carpeta_vacia_corto) else listOf(
						resources.getQuantityString(R.plurals.pod_n_podcasts, fila.podcasts, fila.podcasts),
						resources.getQuantityString(R.plurals.pod_n_sin_escuchar, fila.sinEscuchar, fila.sinEscuchar),
					).joinToString(", ")
					Triple(fila.nombre, d, getString(R.string.carpeta_x, fila.nombre) + ", " + d)
				}
				is Fila.DePodcast -> {
					val p = fila.podcast
					val d = listOfNotNull(
						p.autor.takeIf { it.isNotBlank() },
						resources.getQuantityString(R.plurals.pod_n_sin_escuchar, p.sinEscuchar, p.sinEscuchar),
						getString(R.string.pod_pausado_corto).takeIf { p.pausado },
						getString(R.string.rss_con_error).takeIf { p.error.isNotBlank() },
					).joinToString(", ")
					Triple(p.titulo, d, p.titulo + ", " + d)
				}
				is Fila.DeEpisodio -> {
					val (d, vz) = descripcion(fila.episodio)
					Triple(fila.episodio.titulo, d, vz)
				}
				is Fila.Encontrado -> {
					val e = fila.e.elemento
					val d = listOf(fila.e.podcast, com.xebolax.lector.rss.Rss.fechaLegible(e.fecha), if (e.duracion > 0) tiempoCorto(e.duracion) else "").filter { it.isNotBlank() }.joinToString(" · ")
					Triple(e.titulo, d, "${e.titulo}, $d")
				}
				is Fila.DeSugerencia -> {
					val s = fila.sugerencia
					// Si ya estás suscrito, se dice (y sus acciones cambian)
					val suscrito = suscritoA(s) != null
					val d = listOfNotNull(getString(R.string.pod_suscrito).takeIf { suscrito }, s.autor.ifBlank { null }, s.genero.ifBlank { null }).joinToString(" · ")
					Triple("${posicion + 1}. ${s.titulo}", d, "${posicion + 1}. ${s.titulo}, " + d.replace(" · ", ", "))
				}
			}
			v.f.texto.text = texto
			v.f.texto.setTypeface(null, if (fila is Fila.DeEpisodio && fila.episodio.nuevo) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
			v.f.detalle.text = detalle
			v.f.detalle.isVisible = detalle.isNotBlank()
			v.f.root.contentDescription = voz
			v.f.root.setOnClickListener {
				when (fila) {
					is Fila.Subir -> subir()
					is Fila.Carpeta -> entrarCarpeta(fila.nombre)
					is Fila.DePodcast -> entrarPodcast(fila.podcast)
					is Fila.DeEpisodio -> escuchar(fila.episodio)
					is Fila.DeSugerencia -> PodcastFichaActivity.abrir(a, fila.sugerencia)
					is Fila.Encontrado -> a.elegir(fila.e.elemento.titulo, accionesEncontrado(fila.e))
				}
			}
			val acciones: List<Accion> = when (fila) {
				is Fila.Subir -> emptyList()
				is Fila.Carpeta -> accionesCarpeta(fila.nombre)
				is Fila.DePodcast -> listOf(getString(R.string.pod_opciones_podcast) to { opcionesPodcast(fila.podcast) }) + accionesPodcast(fila.podcast)
				is Fila.DeEpisodio -> accionesEpisodio(fila.episodio, posicion)
				is Fila.Encontrado -> accionesEncontrado(fila.e)
				is Fila.DeSugerencia -> listOf(
					getString(R.string.pod_ver_ficha) to { PodcastFichaActivity.abrir(a, fila.sugerencia) },
					suscritoA(fila.sugerencia)?.let { p -> getString(R.string.pod_ver_suscrito) to { a.irAPodcast(p.id) } }
						?: (getString(R.string.pod_suscribirse) to { a.suscribirSugerencia(fila.sugerencia) }),
					getString(R.string.compartir) to {
						val texto = listOf(fila.sugerencia.titulo, fila.sugerencia.url.ifBlank { "https://podcasts.apple.com/podcast/id${fila.sugerencia.id}" }).joinToString("\n")
						startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), getString(R.string.compartir)))
					},
				)
			}
			// Cada tipo de fila con las acciones que el usuario eligió para su zona (Ajustes, Acciones de TalkBack)
			v.f.root.accionesAccesibles(acciones, when (fila) {
				is Fila.Carpeta -> ZonaAcciones.CARPETA_PODCAST
				is Fila.DePodcast, is Fila.DeSugerencia -> ZonaAcciones.PODCAST
				is Fila.DeEpisodio, is Fila.Encontrado -> ZonaAcciones.EPISODIO
				else -> null
			})
			v.f.root.setOnLongClickListener {
				when {
					fila is Fila.DePodcast -> { opcionesPodcast(fila.podcast); true }
					acciones.isEmpty() -> false
					else -> { a.elegir(texto.take(60), acciones); true }
				}
			}
		}
	}
}
