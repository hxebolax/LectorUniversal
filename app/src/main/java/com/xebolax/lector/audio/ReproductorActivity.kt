package com.xebolax.lector.audio

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.xebolax.lector.R
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Libro
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.databinding.ActivityReproductorBinding
import com.xebolax.lector.formatos.ExcepcionFormato
import com.xebolax.lector.lectura.Abiertos
import com.xebolax.lector.lectura.LectorActivity
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.LibroAudio
import com.xebolax.lector.modelo.MarcaAudio
import com.xebolax.lector.modelo.Posicion
import com.xebolax.lector.ui.Accion
import com.xebolax.lector.ui.AjustesActivity
import com.xebolax.lector.ui.ListaActivity
import com.xebolax.lector.ui.Sonidos
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.conEspera
import com.xebolax.lector.ui.configurarBarra
import com.xebolax.lector.ui.elegir
import com.xebolax.lector.ui.elegirUno
import com.xebolax.lector.ui.elegirTemporizador
import com.xebolax.lector.ui.nombreTemporizador
import com.xebolax.lector.ui.conTemporizadorAlMantener
import com.xebolax.lector.ui.mensaje
import com.xebolax.lector.ui.pedirTexto
import com.xebolax.lector.ui.textoATiempo
import com.xebolax.lector.ui.tamanoLegible
import com.xebolax.lector.ui.tiempoCorto
import com.xebolax.lector.ui.tiempoHablado
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reproductor de audiolibros (m4b, mp3, carpetas) y libros DAISY:
 *
 * - Anterior/Siguiente por la unidad elegida: capítulo, nivel DAISY 1-6
 *   (solo los que tiene el libro), página, frase, marcador, pista o 10 s,
 *   30 s, 1 min, 5 min, 10 min. El selector de unidad y el botón de
 *   reproducir se ajustan deslizando arriba/abajo con TalkBack (en el
 *   botón de reproducir, desliza para moverte por la unidad).
 * - Velocidad (25-400 % sin cambiar el tono), retroceder/avanzar, capítulos,
 *   marcadores con nota, ir a un tiempo, temporizador, saltar silencios y
 *   refuerzo de volumen. En DAISY con texto, se ve el texto que suena.
 */
class ReproductorActivity : AppCompatActivity() {

	companion object {
		/** Abrir un audio que no es de la biblioteca (lo deja preparado ControlAudio.externoPendiente). */
		const val EXTRA_EXTERNO = "externo"
		/** Audio de una noticia (dirección, título y fuente). */
		const val EXTRA_AUDIO_URL = "audio_url"
		const val EXTRA_AUDIO_TITULO = "audio_titulo"
		const val EXTRA_AUDIO_AUTOR = "audio_autor"
	}

	private lateinit var b: ActivityReproductorBinding
	private lateinit var p: Preferencias
	private var libroDb: Libro? = null
	private var libro: LibroAudio? = null
	private var unidad = UnidadAudio.CAPITULO
	private val principal = Handler(Looper.getMainLooper())
	private var ultimoTextoBloque = -1

	private val oyente = ControlAudio.Oyente { principal.post { actualizar() } }

	private val refresco = object : Runnable {
		override fun run() {
			actualizar()
			principal.postDelayed(this, 1000)
		}
	}

	private val lista = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
		if (r.resultCode != RESULT_OK) return@registerForActivityResult
		val valor = r.data?.getIntExtra(ListaActivity.RESULTADO, -1) ?: -1
		val marca = listaActual.getOrNull(valor) ?: return@registerForActivityResult
		ControlAudio.irA(marca.pista, marca.ms)
		ControlAudio.reproducir()
	}
	private var listaActual: List<MarcaAudio> = emptyList()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityReproductorBinding.inflate(layoutInflater)
		setContentView(b.root)
		p = Preferencias(this)
		// Audio de fuera de la biblioteca: un episodio de podcast o el audio de una noticia
		val url = intent.getStringExtra(EXTRA_AUDIO_URL)
		if (url != null) {
			val idArticulo = intent.getLongExtra(com.xebolax.lector.rss.RssAcciones.EXTRA_ARTICULO, 0)
			val db = com.xebolax.lector.rss.RssBaseDatos.obtener(this)
			ControlAudio.externoPendiente = ControlAudio.Externo(
				id = -2_000_000_000L - idArticulo,
				titulo = intent.getStringExtra(EXTRA_AUDIO_TITULO).orEmpty(),
				autor = intent.getStringExtra(EXTRA_AUDIO_AUTOR).orEmpty(),
				url = url,
				posicionMs = db.articulo(idArticulo)?.posicionAudio ?: 0,
				alGuardar = { ms, _ -> db.guardarPosicionAudio(idArticulo, ms) },
				seccion = com.xebolax.lector.datos.Actividad.NOTICIAS,
			)
		}
		if (url != null || intent.getBooleanExtra(EXTRA_EXTERNO, false)) {
			val e = ControlAudio.externoPendiente ?: ControlAudio.externo ?: run {
				finish()
				return
			}
			prepararExterno(e)
			return
		}
		val id = intent.getLongExtra(LectorActivity.EXTRA_LIBRO, -1)
		val l = BaseDatos.obtener(this).libro(id) ?: run {
			finish()
			return
		}
		libroDb = l
		configurarBarra(b.barra, l.tituloVisible)
		configurarControles()
		b.capitulo.text = getString(R.string.abriendo)
		lifecycleScope.launch {
			try {
				val contenido = withContext(Dispatchers.IO) { Abiertos.abrir(this@ReproductorActivity, l, Abiertos.opciones(this@ReproductorActivity)) }
				when (contenido) {
					is Contenido.Texto -> {
						startActivity(Intent(this@ReproductorActivity, LectorActivity::class.java).putExtra(LectorActivity.EXTRA_LIBRO, l.id))
						finish()
					}
					is Contenido.Audio -> preparar(l, contenido.libro)
				}
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Throwable) {
				Sonidos.sonar(this@ReproductorActivity, Sonidos.Tipo.ERROR)
				b.capitulo.text = (e as? ExcepcionFormato)?.message ?: getString(R.string.error_abrir, e.message ?: e.javaClass.simpleName)
			}
		}
	}

	/** Un audio de fuera de la biblioteca: no tiene marcadores ni texto, y su posición la guarda quien lo abre. */
	private fun prepararExterno(e: ControlAudio.Externo) {
		ControlAudio.externoPendiente = null
		libroDb = Libro(e.id, e.url, e.titulo, e.titulo, e.autor, "", true, 0, null, 0, 0, null, 0.0, 0, false, "", 0, "")
		configurarBarra(b.barra, e.titulo)
		configurarControles()
		b.autor.text = e.autor
		b.autor.isVisible = e.autor.isNotBlank()
		b.botonTexto.isVisible = false
		b.textoSincronizado.isVisible = false
		// Los marcadores son de los libros de la biblioteca: un episodio o una noticia no los guarda
		b.botonMarcadores.isVisible = false
		ControlAudio.conectar(this) {
			val nuevo = !ControlAudio.esActual(e.id)
			ControlAudio.cargarExterno(e)
			libro = ControlAudio.libro
			// Los capítulos pueden haber llegado ya (se buscan en segundo plano)
			unidad = if ((ControlAudio.libro?.capitulos?.size ?: 0) > 0) UnidadAudio.CAPITULO else UnidadAudio.M5
			actualizarUnidad()
			actualizar()
			if (nuevo) ControlAudio.reproducir()
		}
	}

	private fun preparar(l: Libro, audio: LibroAudio) {
		libro = audio
		if (audio.titulo.isNotBlank()) {
			title = audio.titulo
			supportActionBar?.title = audio.titulo
		}
		b.autor.text = listOf(audio.autor, audio.narrador.takeIf { it.isNotBlank() }?.let { getString(R.string.narrado_por, it) })
			.filter { !it.isNullOrBlank() }.joinToString(" · ")
		b.autor.isVisible = b.autor.text.isNotBlank()
		b.botonTexto.isVisible = audio.texto != null
		b.textoSincronizado.isVisible = audio.texto != null
		ControlAudio.conectar(this) {
			val nuevo = !ControlAudio.esActual(l.id)
			ControlAudio.cargar(l.id, audio, BaseDatos.obtener(this).libro(l.id)?.posicion?.takeIf { it.audio })
			unidad = unidades().firstOrNull() ?: UnidadAudio.M5
			actualizarUnidad()
			actualizar()
			BaseDatos.obtener(this).marcarAbierto(l.id)
			if (nuevo && p.reproducirAlAbrir) ControlAudio.reproducir()
			if (audio.avisos.isNotEmpty()) mensaje(getString(R.string.avisos), audio.avisos.joinToString("\n\n"))
		}
	}

	override fun onStart() {
		super.onStart()
		ControlAudio.registrar(oyente)
		principal.post(refresco)
	}

	override fun onStop() {
		ControlAudio.desregistrar(oyente)
		principal.removeCallbacks(refresco)
		ControlAudio.guardar(forzar = true)
		// Podcast o noticia: las listas dicen por dónde va
		if ((libroDb?.id ?: 0) < 0) {
			com.xebolax.lector.podcast.PodcastsBaseDatos.obtener(this).avisar()
			com.xebolax.lector.rss.RssBaseDatos.obtener(this).avisar()
		}
		super.onStop()
	}

	// --- Controles -------------------------------------------------------------

	private fun configurarControles() {
		// Reproducir o pausar; mantener pulsado: reproducir con temporizador
		b.botonReproducir.setOnClickListener {
			ControlAudio.alternar()
			avisar(getString(if (ControlAudio.reproduciendo) R.string.reproduciendo else R.string.en_pausa), mostrar = false)
			// Si se acaba de poner solo el temporizador de la noche, se dice
			if (ControlAudio.temporizadorAutomatico) {
				ControlAudio.temporizadorAutomatico = false
				avisar(getString(R.string.temporizador_automatico, nombreTemporizador(ControlAudio.minutosTemporizador())))
				actualizar()
			}
		}
		b.botonReproducir.conTemporizadorAlMantener {
			elegirTemporizador(getString(R.string.reproducir_con_temporizador), conQuitar = false, conEpisodios = ControlAudio.externo != null) { minutos ->
				if (!ControlAudio.reproduciendo) ControlAudio.reproducir()
				ControlAudio.temporizador(minutos)
				avisar(getString(R.string.temporizador_puesto, nombreTemporizador(minutos)))
				actualizar()
			}
		}
		// Retroceder y avanzar unos segundos (Ajustes) y decir dónde se queda
		b.botonRetroceder.setOnClickListener { saltarTiempo(-p.saltoAtras) }
		b.botonAvanzar.setOnClickListener { saltarTiempo(p.saltoAdelante) }
		b.botonRetroceder.contentDescription = getString(R.string.retroceder_n, p.saltoAtras)
		b.botonAvanzar.contentDescription = getString(R.string.avanzar_n, p.saltoAdelante)
		ViewCompat.setTooltipText(b.botonRetroceder, b.botonRetroceder.contentDescription)
		ViewCompat.setTooltipText(b.botonAvanzar, b.botonAvanzar.contentDescription)

		// Moverse por capítulos u otra unidad: anterior, cuál (se cambia deslizando arriba o abajo) y siguiente
		b.botonAnterior.setOnClickListener { saltar(false) }
		b.botonSiguiente.setOnClickListener { saltar(true) }
		b.selectorUnidad.rol = getString(R.string.rol_selector)
		b.selectorUnidad.alSubir = { cambiarUnidad(+1) }
		b.selectorUnidad.alBajar = { cambiarUnidad(-1) }
		b.selectorUnidad.setOnClickListener { elegirUnidad() }

		// Velocidad: deslizar arriba o abajo de 5 en 5; tocar: elegirla de la lista
		b.selectorVelocidad.rol = getString(R.string.rol_velocidad)
		b.selectorVelocidad.alSubir = { cambiarVelocidad(+5) }
		b.selectorVelocidad.alBajar = { cambiarVelocidad(-5) }
		b.selectorVelocidad.setOnClickListener { elegirVelocidad() }

		b.botonCapitulos.setOnClickListener { capitulos() }
		b.botonMarcadores.setOnClickListener { marcadores() }
		b.botonIr.setOnClickListener { irATiempo() }
		b.botonTemporizador.setOnClickListener { temporizador() }
		b.botonTexto.setOnClickListener { abrirTexto() }
		describirAlPedir(b.tiempo) { descripcionTiempo }
		describirAlPedir(b.tiempoCapitulo) { descripcionTiempoCapitulo }
		b.barraPosicion.alMover = { ms -> ControlAudio.irA(ControlAudio.posicion().first, ms) }
		b.barraPosicion.describir = { pos, dur -> describirPosicion(pos, dur) }
	}

	/** «12 minutos de 45, 28 por ciento, quedan 33 minutos» (lo que queda, a la velocidad actual). */
	private fun describirPosicion(pos: Long, dur: Long): String {
		if (dur <= 0) return tiempoHablado(pos)
		val queda = ((dur - pos).coerceAtLeast(0) / (ControlAudio.velocidad() / 100.0).coerceAtLeast(0.25)).toLong()
		return getString(R.string.posicion_estado, tiempoHablado(pos), tiempoHablado(dur, conSegundos = false),
			(pos * 100 / dur).toInt(), tiempoHablado(queda, conSegundos = false))
	}

	private fun saltarTiempo(segundos: Int) {
		ControlAudio.saltarSegundos(segundos)
		avisar(tiempoHablado(ControlAudio.posicion().second), mostrar = false)
		actualizar()
	}

	private fun nombreUnidad(u: UnidadAudio): String = when (u) {
		UnidadAudio.CAPITULO -> getString(R.string.u_capitulo)
		UnidadAudio.PAGINA -> getString(R.string.u_pagina)
		UnidadAudio.FRASE -> getString(R.string.u_frase)
		UnidadAudio.MARCADOR -> getString(R.string.u_marcador)
		UnidadAudio.PISTA -> getString(R.string.u_pista)
		else -> if (u.nivel > 0) getString(R.string.u_nivel, u.nivel) else getString(R.string.u_saltos_de, tiempoHablado(u.segundos * 1000L))
	}

	/** Qué hacen Anterior y Siguiente con esa unidad, dicho entero («Capítulo siguiente», «Avanzar 5 minutos»). */
	private fun accionUnidad(u: UnidadAudio, delante: Boolean): String = when (u) {
		UnidadAudio.CAPITULO -> getString(if (delante) R.string.nav_capitulo_siguiente else R.string.nav_capitulo_anterior)
		UnidadAudio.PAGINA -> getString(if (delante) R.string.nav_pagina_siguiente else R.string.nav_pagina_anterior)
		UnidadAudio.FRASE -> getString(if (delante) R.string.nav_frase_siguiente else R.string.nav_frase_anterior)
		UnidadAudio.MARCADOR -> getString(if (delante) R.string.nav_marcador_siguiente else R.string.nav_marcador_anterior)
		UnidadAudio.PISTA -> getString(if (delante) R.string.nav_pista_siguiente else R.string.nav_pista_anterior)
		else -> if (u.nivel > 0) getString(if (delante) R.string.nav_nivel_siguiente else R.string.nav_nivel_anterior, u.nivel)
		else getString(if (delante) R.string.avanzar_x else R.string.retroceder_x, tiempoHablado(u.segundos * 1000L))
	}

	/** Unidades para Anterior y Siguiente: sin los saltos de 10 y 30 s, que ya hacen Retroceder y Avanzar. */
	private fun unidades(): List<UnidadAudio> = ControlAudio.unidadesDisponibles().filter { it.segundos == 0 || it.segundos >= 60 }

	private fun cambiarUnidad(paso: Int) {
		val lista = unidades().ifEmpty { return }
		val i = lista.indexOf(unidad).coerceAtLeast(0)
		unidad = lista[(i + paso + lista.size) % lista.size]
		actualizarUnidad()
		avisar(nombreUnidad(unidad), mostrar = false)
	}

	private fun elegirUnidad() {
		val lista = unidades()
		elegirUno(getString(R.string.moverse_por), lista.map { nombreUnidad(it) }, lista.indexOf(unidad)) { i ->
			unidad = lista[i]
			actualizarUnidad()
		}
	}

	private fun actualizarUnidad() {
		if (unidad !in unidades()) unidad = unidades().firstOrNull() ?: UnidadAudio.M5
		val n = nombreUnidad(unidad)
		b.selectorUnidad.text = n
		b.selectorUnidad.valor = n
		b.selectorUnidad.contentDescription = getString(R.string.moverse_por)
		b.botonAnterior.contentDescription = accionUnidad(unidad, false)
		b.botonSiguiente.contentDescription = accionUnidad(unidad, true)
		ViewCompat.setTooltipText(b.botonAnterior, b.botonAnterior.contentDescription)
		ViewCompat.setTooltipText(b.botonSiguiente, b.botonSiguiente.contentDescription)
	}

	private fun saltar(delante: Boolean) {
		val marca = ControlAudio.saltar(unidad, delante)
		if (marca == null) {
			Sonidos.sonar(this, Sonidos.Tipo.LIMITE)
			avisar(getString(if (delante) R.string.no_hay_siguiente else R.string.no_hay_anterior, nombreUnidad(unidad)))
			return
		}
		// Se dice dónde ha llegado (título del capítulo o la hora)
		val texto = when {
			unidad.segundos > 0 -> tiempoHablado(ControlAudio.posicion().second)
			unidad == UnidadAudio.FRASE -> null
			marca.titulo.isNotBlank() -> marca.titulo
			else -> tiempoHablado(marca.ms)
		}
		texto?.let { avisar(it, mostrar = false) }
	}

	/** De 5 en 5 y siempre en múltiplos de 5: de 100 a 105 o a 95 (y de 102 a 105 o a 100). */
	private fun cambiarVelocidad(paso: Int) {
		val v = ControlAudio.velocidad()
		val nueva = if (paso > 0) (v / 5 + 1) * 5 else ((v - 1) / 5) * 5
		ControlAudio.cambiarVelocidad(nueva.coerceIn(25, 400))
		actualizar()
		avisar(getString(R.string.velocidad_n, ControlAudio.velocidad()), mostrar = false)
	}

	private fun elegirVelocidad() {
		val valores = (5..80).map { it * 5 }
		val actual = ControlAudio.velocidad()
		elegirUno(getString(R.string.velocidad), valores.map { "$it %" }, valores.indexOfFirst { it >= actual }.coerceAtLeast(0)) { i ->
			ControlAudio.cambiarVelocidad(valores[i])
			actualizar()
		}
	}

	// --- Estado ----------------------------------------------------------------------

	private fun poner(v: android.widget.TextView, texto: CharSequence) {
		if (v.text.toString() != texto.toString()) v.text = texto
	}

	private fun ponerDescripcion(v: android.view.View, texto: CharSequence) {
		if (v.contentDescription?.toString() != texto.toString()) v.contentDescription = texto
	}

	/**
	 * El tiempo cambia cada segundo: su nombre hablado se da al pedirlo TalkBack (al llegar
	 * a él), sin cambiar contentDescription cada segundo, que TalkBack volvería a leer.
	 */
	private var descripcionTiempo = ""
	private var descripcionTiempoCapitulo = ""

	private fun describirAlPedir(v: android.view.View, texto: () -> String) {
		ViewCompat.setAccessibilityDelegate(v, object : androidx.core.view.AccessibilityDelegateCompat() {
			override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
				super.onInitializeAccessibilityNodeInfo(host, info)
				texto().takeIf { it.isNotBlank() }?.let { info.contentDescription = it }
			}
		})
	}

	private fun actualizar() {
		// Podcast: al terminar un episodio empieza el siguiente de la cola; la pantalla pasa a él
		val ext = ControlAudio.externo
		if (ext != null && (libroDb?.id ?: 0) < 0 && ControlAudio.idLibro == ext.id && libroDb?.id != ext.id) {
			libroDb = Libro(ext.id, ext.url, ext.titulo, ext.titulo, ext.autor, "", true, 0, null, 0, 0, null, 0.0, 0, false, "", 0, "")
			libro = ControlAudio.libro
			title = ext.titulo
			supportActionBar?.title = ext.titulo
			b.autor.text = ext.autor
			b.autor.isVisible = ext.autor.isNotBlank()
			avisar(ext.titulo, mostrar = false)
			invalidateOptionsMenu()
		}
		// Los capítulos incrustados del episodio llegan un poco después
		if ((libroDb?.id ?: 0) < 0 && ControlAudio.idLibro == libroDb?.id && ControlAudio.libro != null && ControlAudio.libro !== libro) {
			val antes = libro?.capitulos?.size ?: 0
			libro = ControlAudio.libro
			if (antes == 0 && (libro?.capitulos?.size ?: 0) > 0 && unidad.segundos > 0) {
				unidad = UnidadAudio.CAPITULO
				actualizarUnidad()
				avisar(resources.getQuantityString(R.plurals.pod_capitulos_encontrados, libro!!.capitulos.size, libro!!.capitulos.size), mostrar = false)
			}
		}
		val l = libro ?: return
		if (!ControlAudio.esActual(libroDb?.id ?: -1)) return
		val reproduciendo = ControlAudio.reproduciendo
		b.botonReproducir.setIconResource(if (reproduciendo) R.drawable.ic_pausa else R.drawable.ic_reproducir)
		ponerDescripcion(b.botonReproducir, getString(if (reproduciendo) R.string.pausar else R.string.reproducir))
		val (pista, ms) = ControlAudio.posicion()
		val capitulo = ControlAudio.capituloActual()
		val pagina = l.paginaEn(pista, ms)
		// Sin capítulos ni varias pistas no se repite el título (ya está arriba)
		val hayCapitulos = l.capitulos.isNotEmpty() || l.pistas.size > 1
		poner(b.capitulo, if (!hayCapitulos) "" else listOfNotNull(capitulo?.titulo?.ifBlank { null }, pagina?.let { getString(R.string.pagina_n, it.titulo) }).joinToString(" · ")
			.ifBlank { l.pistas.getOrNull(pista)?.titulo.orEmpty() })
		b.capitulo.isVisible = b.capitulo.text.isNotBlank()
		val duracion = ControlAudio.duracionPista()
		val total = l.duracionTotal
		val transcurrido = if (total > 0) l.inicioDePista(pista) + ms else ms
		val finTotal = if (total > 0) total else duracion
		var textoTiempo = getString(R.string.tiempo_de, tiempoCorto(transcurrido), tiempoCorto(finTotal)) +
			if (l.pistas.size > 1) " · " + getString(R.string.pista_de, pista + 1, l.pistas.size) else ""
		// Lo que queda del libro a la velocidad actual
		val velocidad = (ControlAudio.velocidad() / 100.0).coerceAtLeast(0.25)
		val queda = if (finTotal > 0) ((finTotal - transcurrido).coerceAtLeast(0) / velocidad).toLong() else 0L
		if (queda > 0 && total > 0) textoTiempo += " · " + getString(R.string.quedan_x, tiempoCorto(queda))
		poner(b.tiempo, textoTiempo)
		descripcionTiempo = (getString(R.string.tiempo_de, tiempoHablado(transcurrido), tiempoHablado(finTotal)) +
			(if (l.pistas.size > 1) ". " + getString(R.string.pista_de, pista + 1, l.pistas.size) else "") +
			". " + getString(R.string.porcentaje_n, ControlAudio.progreso().toInt()) +
			(if (queda > 0 && total > 0) ". " + getString(R.string.quedan_x, tiempoHablado(queda, conSegundos = false)) else ""))
		// Tiempo dentro del capítulo y lo que queda
		val (inicioCap, finCap) = ControlAudio.limitesCapitulo()
		if (hayCapitulos && inicioCap != null && inicioCap.pista == pista) {
			val dentro = ms - inicioCap.ms
			val finMs = if (finCap != null && finCap.pista == pista) finCap.ms else duracion
			val queda = (finMs - ms).coerceAtLeast(0)
			poner(b.tiempoCapitulo, getString(R.string.capitulo_tiempo, tiempoCorto(dentro), tiempoCorto(queda)))
			descripcionTiempoCapitulo = getString(R.string.capitulo_tiempo, tiempoHablado(dentro), tiempoHablado(queda))
			b.tiempoCapitulo.isVisible = true
		} else b.tiempoCapitulo.isVisible = false
		b.barraPosicion.poner(ms, duracion)
		b.barraPosicion.contentDescription = getString(if (l.pistas.size > 1) R.string.posicion_en_pista else R.string.posicion_en_audio)
		val v = ControlAudio.velocidad()
		poner(b.selectorVelocidad, "$v %")
		b.selectorVelocidad.valor = "$v %"
		b.selectorVelocidad.contentDescription = getString(R.string.velocidad)
		val minutos = ControlAudio.minutosTemporizador()
		poner(b.botonTemporizador, when {
			minutos > 0 -> "$minutos′"
			minutos == -1 -> getString(R.string.fin_cap_corto)
			minutos < -1 -> getString(R.string.fin_episodio_corto)
			else -> getString(R.string.temporizador_corto)
		})
		// Al final del capítulo, del episodio o tras N episodios: lo dice como en la lista del temporizador
		ponerDescripcion(b.botonTemporizador, when {
			minutos > 0 -> getString(R.string.temporizador_queda, resources.getQuantityString(R.plurals.minutos, minutos, minutos))
			minutos < 0 -> getString(R.string.temporizador_queda, nombreTemporizador(minutos))
			else -> getString(R.string.temporizador)
		})
		// Texto sincronizado (DAISY)
		l.texto?.let { doc ->
			val frase = l.fraseEn(pista, ms)
			val bloque = l.frases.getOrNull(frase)?.bloque ?: -1
			if (bloque >= 0 && bloque != ultimoTextoBloque) {
				ultimoTextoBloque = bloque
				b.textoSincronizado.text = doc.bloques.getOrNull(bloque)?.texto.orEmpty()
			}
		}
	}

	// --- Listas y diálogos ------------------------------------------------------------------

	private fun capitulos() {
		val l = libro ?: return
		val marcas = ControlAudio.marcas(UnidadAudio.CAPITULO)
		if (marcas.isEmpty()) return avisar(getString(R.string.sin_capitulos))
		listaActual = marcas
		val (pista, ms) = ControlAudio.posicion()
		val actual = marcas.indexOfLast { it.pista < pista || (it.pista == pista && it.ms <= ms + 300) }.coerceAtLeast(0)
		val items = marcas.mapIndexed { i, m ->
			val inicio = if (l.duracionTotal > 0) l.inicioDePista(m.pista) + m.ms else m.ms
			ListaActivity.Item(m.titulo.ifBlank { getString(R.string.capitulo_n, i + 1) }, getString(R.string.nivel_tiempo, m.nivel, tiempoCorto(inicio)), i, m.nivel)
		}
		ListaActivity.datos = ListaActivity.Datos(getString(R.string.capitulos), items, actual)
		lista.launch(Intent(this, ListaActivity::class.java))
	}

	private fun lugarActual(): String {
		val (pista, ms) = ControlAudio.posicion()
		val l = libro
		val total = if ((l?.duracionTotal ?: 0) > 0) l!!.inicioDePista(pista) + ms else ms
		return listOfNotNull(ControlAudio.capituloActual()?.titulo?.ifBlank { null }, tiempoCorto(total)).joinToString(" · ")
	}

	private fun marcadores() {
		val l = libroDb ?: return
		val db = BaseDatos.obtener(this)
		val opciones = mutableListOf<Accion>(
			getString(R.string.anadir_marcador_aqui) to {
				val (pista, ms) = ControlAudio.posicion()
				db.anadirMarcador(l.id, Posicion.audio(pista, ms), lugarActual(), "", lugarActual())
				ControlAudio.cargarMarcadores()
				Sonidos.sonar(this, Sonidos.Tipo.MARCADOR)
				avisar(getString(R.string.marcador_anadido))
			},
			getString(R.string.anadir_nota_aqui) to {
				val (pista, ms) = ControlAudio.posicion()
				val lugar = lugarActual()
				pedirTexto(getString(R.string.anadir_nota), getString(R.string.nota), multilinea = true) { nota ->
					if (nota.isBlank()) return@pedirTexto
					db.anadirMarcador(l.id, Posicion.audio(pista, ms), lugar, nota, lugar)
					ControlAudio.cargarMarcadores()
					Sonidos.sonar(this, Sonidos.Tipo.NOTA)
					avisar(getString(R.string.nota_anadida))
				}
			},
		)
		val guardados = db.marcadores(l.id).filter { it.posicion.audio }
		if (guardados.isNotEmpty()) opciones += getString(R.string.ver_marcadores_n, guardados.size) to {
			listaActual = guardados.map { MarcaAudio(it.lugar, 1, it.posicion.pista, it.posicion.ms) }
			val items = guardados.mapIndexed { i, m ->
				ListaActivity.Item(if (m.nota.isNotBlank()) getString(R.string.nota_x, m.nota) else m.lugar, if (m.nota.isNotBlank()) m.lugar else "", i, 1, m.id)
			}
			ListaActivity.alAccion = { item, accion ->
				when (accion) {
					ListaActivity.ACCION_BORRAR -> db.borrarMarcador(item.id)
					ListaActivity.ACCION_EDITAR -> {
						val m = guardados.firstOrNull { it.id == item.id }
						pedirTexto(getString(R.string.editar_nota), getString(R.string.nota), m?.nota.orEmpty(), multilinea = true) { db.editarNota(item.id, it) }
					}
				}
				ControlAudio.cargarMarcadores()
			}
			ListaActivity.datos = ListaActivity.Datos(getString(R.string.marcadores_y_notas), items, 0)
			lista.launch(Intent(this, ListaActivity::class.java))
		}
		elegir(getString(R.string.marcadores_y_notas), opciones)
	}

	private fun irATiempo() {
		val l = libro ?: return
		// Dónde se está y cuánto dura (del libro entero si se sabe; si no, de la pista)
		val (pistaActual, msActual) = ControlAudio.posicion()
		val entero = l.duracionTotal > 0
		val actual = if (entero) l.pistas.take(pistaActual).sumOf { it.duracionMs } + msActual else msActual
		val total = if (entero) l.duracionTotal else ControlAudio.duracionPista()
		val titulo = if (total > 0) getString(R.string.ir_a_tiempo_donde, tiempoCorto(actual), tiempoCorto(total)) else getString(R.string.ir_a_tiempo)
		// Teclado normal: hacen falta «+», «-», «:» y «%»
		pedirTexto(titulo, getString(R.string.tiempo_ayuda_saltos), teclado = com.xebolax.lector.ui.Teclado.TEXTO) { texto ->
			val ms = textoATiempo(texto, actual, total) ?: return@pedirTexto avisar(getString(R.string.tiempo_no_valido))
			if (total > 0 && ms > total) return@pedirTexto avisar(getString(R.string.tiempo_pasa_del_final, tiempoHablado(total)))
			if (entero) {
				// Tiempo del libro entero: se busca la pista
				var resto = ms
				var pista = 0
				while (pista < l.pistas.size - 1 && resto >= l.pistas[pista].duracionMs) {
					resto -= l.pistas[pista].duracionMs
					pista++
				}
				ControlAudio.irA(pista, resto)
			} else ControlAudio.irA(ControlAudio.posicion().first, ms)
		}
	}

	private fun temporizador() {
		elegirTemporizador(getString(R.string.temporizador), conQuitar = true, conEpisodios = ControlAudio.externo != null) { minutos ->
			ControlAudio.temporizador(minutos)
			avisar(if (minutos == 0) getString(R.string.temporizador_quitado) else getString(R.string.temporizador_puesto, nombreTemporizador(minutos)))
			actualizar()
		}
	}

	private fun abrirTexto() {
		val l = libroDb ?: return
		startActivity(
			Intent(this, LectorActivity::class.java)
				.putExtra(LectorActivity.EXTRA_LIBRO, l.id)
				.putExtra(LectorActivity.EXTRA_TEXTO_DE_AUDIO, true)
				.putExtra(LectorActivity.EXTRA_BLOQUE, ultimoTextoBloque.coerceAtLeast(0))
		)
	}

	/** El episodio de podcast que suena (si lo es). */
	private fun episodio(): com.xebolax.lector.podcast.Episodio? {
		val id = libroDb?.id ?: return null
		if (id > -1_000_000_000L || id <= -2_000_000_000L) return null
		return com.xebolax.lector.podcast.PodcastsBaseDatos.obtener(this).episodio(-1_000_000_000L - id)
	}

	private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

	/**
	 * Toda la información, como un documento con encabezados (se lee y se
	 * navega mejor que un cuadro de diálogo): dónde vas, el episodio o el libro,
	 * sus notas y sus capítulos.
	 */
	private fun informacion() {
		val l = libro ?: return
		val (pista, ms) = ControlAudio.posicion()
		val total = if (l.duracionTotal > 0) l.duracionTotal else ControlAudio.duracionPista()
		val transcurrido = if (l.duracionTotal > 0) l.inicioDePista(pista) + ms else ms
		val e = episodio()
		val html = buildString {
			append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(esc(l.titulo)).append("</title></head><body>")
			append("<h1>").append(esc(l.titulo)).append("</h1>")
			// Dónde vas
			append("<h2>").append(getString(R.string.info_donde_vas)).append("</h2><ul>")
			append("<li>").append(esc(describirPosicion(transcurrido, total))).append("</li>")
			if (l.capitulos.isNotEmpty()) ControlAudio.capituloActual()?.let { c ->
				val todos = ControlAudio.marcas(UnidadAudio.CAPITULO)
				val n = todos.indexOfFirst { it.pista == c.pista && it.ms == c.ms } + 1
				append("<li>").append(esc(getString(R.string.info_capitulo_actual, c.titulo.ifBlank { "—" }, n, todos.size))).append("</li>")
			}
			append("<li>").append(esc(getString(R.string.velocidad_n, ControlAudio.velocidad()))).append("</li>")
			val minutos = ControlAudio.minutosTemporizador()
			if (minutos != 0) append("<li>").append(esc(getString(R.string.temporizador_queda,
				if (minutos > 0) resources.getQuantityString(R.plurals.minutos, minutos, minutos) else nombreTemporizador(minutos)))).append("</li>")
			if (p.saltarSilencios) append("<li>").append(getString(R.string.saltar_silencios)).append("</li>")
			// El refuerzo que se aplica: el propio del podcast si tiene, si no el general
			val refuerzo = ControlAudio.externo?.takeIf { it.id == ControlAudio.idLibro }?.refuerzo ?: p.refuerzoVolumen
			if (refuerzo > 0) append("<li>").append(esc(getString(R.string.refuerzo_volumen) + ": +" + refuerzo + " dB")).append("</li>")
			append("</ul>")
			if (e != null) {
				val db = com.xebolax.lector.podcast.PodcastsBaseDatos.obtener(this@ReproductorActivity)
				val podcast = db.podcast(e.podcast)
				append("<h2>").append(getString(R.string.info_episodio)).append("</h2><ul>")
				listOfNotNull(
					if (e.temporada > 0 && e.numero > 0) getString(R.string.pod_temporada_episodio, e.temporada, e.numero) else if (e.numero > 0) getString(R.string.pod_episodio_n, e.numero) else null,
					com.xebolax.lector.rss.Rss.fechaLegible(e.fecha).takeIf { it.isNotBlank() }?.let { getString(R.string.info_publicado, it) },
					if (e.duracion > 0) getString(R.string.info_duracion, tiempoHablado(e.duracion)) else null,
					if (e.descargado) getString(R.string.info_descargado, tamanoLegible(java.io.File(e.archivo).length())) else getString(R.string.info_en_linea),
					if (e.enCola) getString(R.string.pod_posicion_cola, e.cola) else null,
					getString(R.string.rss_favorito).takeIf { e.favorito },
					getString(R.string.pod_tiene_transcripciones).takeIf { e.transcripcion.isNotBlank() },
				).forEach { append("<li>").append(esc(it)).append("</li>") }
				append("</ul>")
				if (podcast != null) {
					append("<h2>").append(getString(R.string.info_podcast)).append("</h2><ul>")
					listOf(podcast.titulo, podcast.autor, podcast.sitio).filter { it.isNotBlank() }.forEach { append("<li>").append(esc(it)).append("</li>") }
					append("</ul>")
				}
				if (e.descripcion.isNotBlank()) {
					append("<h2>").append(getString(R.string.info_notas)).append("</h2>")
					append(if (e.descripcion.contains('<')) e.descripcion else "<p>" + esc(e.descripcion).replace("\n", "<br>") + "</p>")
				}
			} else {
				append("<h2>").append(getString(R.string.info_libro)).append("</h2><ul>")
				if (l.autor.isNotBlank()) append("<li>").append(esc(getString(R.string.info_autor, l.autor))).append("</li>")
				if (l.narrador.isNotBlank()) append("<li>").append(esc(getString(R.string.narrado_por, l.narrador))).append("</li>")
				for ((k, v) in l.metadatos) append("<li>").append(esc("$k: $v")).append("</li>")
				append("<li>").append(esc(getString(R.string.info_pistas, l.pistas.size))).append("</li>")
				if (l.paginas.isNotEmpty()) append("<li>").append(esc(getString(R.string.info_paginas, l.paginas.size))).append("</li>")
				if (l.duracionTotal > 0) append("<li>").append(esc(getString(R.string.info_duracion, tiempoHablado(l.duracionTotal)))).append("</li>")
				for (a in l.avisos) append("<li>").append(esc(a)).append("</li>")
				append("</ul>")
			}
			val capitulos = ControlAudio.marcas(UnidadAudio.CAPITULO)
			if (capitulos.isNotEmpty()) {
				append("<h2>").append(esc(getString(R.string.info_capitulos, capitulos.size))).append("</h2><ul>")
				capitulos.forEachIndexed { i, m ->
					val inicio = if (l.duracionTotal > 0) l.inicioDePista(m.pista) + m.ms else m.ms
					append("<li>").append(esc("${m.titulo.ifBlank { getString(R.string.capitulo_n, i + 1) }}, ${tiempoCorto(inicio)}")).append("</li>")
				}
				append("</ul>")
			}
			append("</body></html>")
		}
		com.xebolax.lector.podcast.Podcasts.abrirTexto(this, "informacion_audio", getString(R.string.informacion_de, l.titulo), html)
	}

	// --- Menú y teclas --------------------------------------------------------------------

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.reproductor, menu)
		menu.findItem(R.id.menu_saltar_silencios)?.isChecked = p.saltarSilencios
		// Episodio de podcast: sus notas, su transcripción y pasar al siguiente de la cola
		episodio()?.let { e ->
			menu.add(Menu.NONE, 101, 1, R.string.pod_notas)
			if (e.transcripcion.isNotBlank()) menu.add(Menu.NONE, 102, 2, R.string.pod_transcripcion)
			menu.add(Menu.NONE, 103, 3, R.string.pod_terminar_siguiente)
		}
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.menu_saltar_silencios -> {
				item.isChecked = !item.isChecked
				ControlAudio.cambiarSaltarSilencios(item.isChecked)
			}
			R.id.menu_refuerzo -> {
				val valores = listOf(0, 3, 6, 9, 12, 15)
				// Un episodio de un podcast con volumen propio: se cambia el suyo (el general no le afecta)
				val ext = ControlAudio.externo?.takeIf { it.refuerzo != null && it.id == ControlAudio.idLibro }
				val idPodcast = if (ext != null) episodio()?.podcast else null
				val actual = if (idPodcast != null) ext?.refuerzo ?: 0 else p.refuerzoVolumen
				val titulo = if (idPodcast != null) getString(R.string.audio_refuerzo_podcast) else getString(R.string.refuerzo_volumen)
				elegirUno(titulo, valores.map { if (it == 0) getString(R.string.sin_refuerzo) else "+$it dB" }, valores.indexOf(actual).coerceAtLeast(0)) { i ->
					if (ext != null && idPodcast != null) {
						ext.refuerzo = valores[i]
						val db = com.xebolax.lector.podcast.PodcastsBaseDatos.obtener(this)
						lifecycleScope.launch(Dispatchers.IO) { db.guardarVolumen(idPodcast, valores[i]) }
					} else p.p.edit().putInt("refuerzo_volumen", valores[i]).apply()
					ControlAudio.aplicarRefuerzo()
				}
			}
			R.id.menu_informacion -> informacion()
			R.id.menu_detener -> {
				ControlAudio.detener()
				finish()
			}
			101 -> episodio()?.let { com.xebolax.lector.podcast.Podcasts.abrirTexto(this, "notas_${it.id}", it.titulo, com.xebolax.lector.podcast.Podcasts.htmlNotas(this, it)) }
			102 -> episodio()?.let { e ->
				lifecycleScope.launch {
					runCatching { conEspera(getString(R.string.pod_transcripcion), getString(R.string.cargando)) { com.xebolax.lector.podcast.Podcasts.transcripcion(this@ReproductorActivity, e) } }
						.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
						.onSuccess { t ->
							// Se abre en el párrafo de lo que está sonando
							val bloque = t.bloqueEn(ControlAudio.posicion().second)
							com.xebolax.lector.podcast.Podcasts.abrirTexto(this@ReproductorActivity, "transcripcion_${e.id}", getString(R.string.pod_transcripcion_de, e.titulo), t.html, bloque)
						}
						.onFailure { avisar(getString(R.string.pod_sin_transcripcion)) }
				}
			}
			103 -> episodio()?.let { e ->
				lifecycleScope.launch {
					if (!com.xebolax.lector.podcast.Podcasts.terminarYSeguir(this@ReproductorActivity, e)) avisar(getString(R.string.pod_cola_vacia))
				}
			}
			else -> return super.onOptionsItemSelected(item)
		}
		return true
	}

	override fun onKeyDown(codigo: Int, evento: KeyEvent): Boolean {
		when {
			codigo == KeyEvent.KEYCODE_SPACE -> ControlAudio.alternar()
			codigo == KeyEvent.KEYCODE_DPAD_LEFT && evento.isCtrlPressed -> saltar(false)
			codigo == KeyEvent.KEYCODE_DPAD_RIGHT && evento.isCtrlPressed -> saltar(true)
			codigo == KeyEvent.KEYCODE_DPAD_UP && evento.isCtrlPressed -> cambiarVelocidad(+5)
			codigo == KeyEvent.KEYCODE_DPAD_DOWN && evento.isCtrlPressed -> cambiarVelocidad(-5)
			codigo == KeyEvent.KEYCODE_B && evento.isCtrlPressed && (libroDb?.id ?: 0) > 0 -> marcadores()
			codigo == KeyEvent.KEYCODE_T && evento.isCtrlPressed -> capitulos()
			codigo == KeyEvent.KEYCODE_G && evento.isCtrlPressed -> irATiempo()
			else -> return super.onKeyDown(codigo, evento)
		}
		return true
	}
}
