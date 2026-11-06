package com.xebolax.lector.audio

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.modelo.LibroAudio
import com.xebolax.lector.modelo.MarcaAudio
import com.xebolax.lector.modelo.Posicion
import java.util.concurrent.CopyOnWriteArrayList

/** Unidades por las que se mueve el reproductor con Anterior/Siguiente. */
enum class UnidadAudio(val nivel: Int = 0, val segundos: Int = 0) {
	CAPITULO,
	NIVEL_1(nivel = 1), NIVEL_2(nivel = 2), NIVEL_3(nivel = 3), NIVEL_4(nivel = 4), NIVEL_5(nivel = 5), NIVEL_6(nivel = 6),
	PAGINA,
	FRASE,
	MARCADOR,
	PISTA,
	S10(segundos = 10), S30(segundos = 30), M1(segundos = 60), M5(segundos = 300), M10(segundos = 600),
}

/**
 * Estado y órdenes del audiolibro en reproducción (uno para toda la app).
 * El ExoPlayer lo crea ServicioAudio; aquí está la lógica del libro:
 * capítulos y niveles DAISY, páginas, frases sincronizadas, marcadores,
 * saltos, velocidad, temporizador con bajada de volumen, retroceso al
 * reanudar y guardado de la posición.
 */
@SuppressLint("StaticFieldLeak")
object ControlAudio {

	fun interface Oyente {
		fun alCambiar()
	}

	private lateinit var app: Context
	private val principal = Handler(Looper.getMainLooper())
	private val oyentes = CopyOnWriteArrayList<Oyente>()

	var jugador: ExoPlayer? = null
		internal set
	var libro: LibroAudio? = null
		private set
	var idLibro: Long = -1
		private set
	var marcadores: List<MarcaAudio> = emptyList()

	private var controlador: ListenableFuture<MediaController>? = null
	private var pausadoEn = 0L
	private var finTemporizador = 0L
	var alFinalDelCapitulo = false
		private set
	private var refuerzo: LoudnessEnhancer? = null
	private var ultimoGuardado = 0L
	private var inicioSesion = 0L
	private var minutosElegidos = 0
	private var paradoPorTemporizador = 0L
	private val agitar by lazy { com.xebolax.lector.voz.Agitar(app) }
	private val dejarDeAgitar = Runnable { agitar.parar() }

	fun iniciar(c: Context) {
		if (!::app.isInitialized) app = c.applicationContext
	}

	/**
	 * Audio que no es de la biblioteca (un episodio de podcast, el audio de una
	 * noticia): quien lo reproduce guarda la posición y decide qué pasa al terminar.
	 */
	class Externo(
		/** Id negativo y único (no choca con los libros). */
		val id: Long,
		val titulo: String,
		val autor: String,
		val url: String,
		val posicionMs: Long,
		val capitulos: List<MarcaAudio> = emptyList(),
		/** Velocidad propia (%) o null para la de Ajustes. */
		val velocidad: Int? = null,
		/** Segundos que saltar al empezar (intro) y antes del final. */
		val saltarInicio: Int = 0,
		val saltarFinal: Int = 0,
		val alGuardar: (ms: Long, duracion: Long) -> Unit = { _, _ -> },
		val alTerminar: () -> Unit = {},
		val alCambiarVelocidad: (Int) -> Unit = {},
	)

	var externo: Externo? = null
		private set

	/** Lo que la pantalla del reproductor debe abrir (se pone antes de abrirla). */
	@Volatile
	var externoPendiente: Externo? = null

	private var escuchandoFin = false

	/** Carga un audio externo y lo coloca donde se quedó. */
	fun cargarExterno(e: Externo) {
		val j = jugador ?: return
		if (esActual(e.id)) {
			externo = e
			return
		}
		guardar(forzar = true)
		externo = e
		idLibro = e.id
		val incrustados = capitulosPendientes?.takeIf { it.first == e.id }?.second.orEmpty()
		capitulosPendientes = null
		libro = LibroAudio(e.titulo, e.autor, pistas = listOf(com.xebolax.lector.modelo.Pista(e.url, e.titulo)), capitulos = e.capitulos.ifEmpty { incrustados })
		val elemento = MediaItem.Builder()
			.setUri(Uri.parse(e.url))
			.setMediaId("${e.id}:0")
			.setMediaMetadata(MediaMetadata.Builder().setTitle(e.titulo).setArtist(e.autor).build())
			.build()
		val inicio = if (e.posicionMs > 0) e.posicionMs else e.saltarInicio * 1000L
		j.setMediaItem(elemento, inicio)
		j.playbackParameters = PlaybackParameters((e.velocidad ?: Preferencias(app).velocidadAudio) / 100f)
		j.skipSilenceEnabled = Preferencias(app).saltarSilencios
		j.prepare()
		marcadores = emptyList()
		escucharFin(j)
		aplicarRefuerzo()
		avisar()
	}

	private var capitulosPendientes: Pair<Long, List<MarcaAudio>>? = null

	/** Capítulos que llegan después (los incrustados en un episodio que se está escuchando). */
	fun ponerCapitulos(id: Long, capitulos: List<MarcaAudio>) {
		if (capitulos.isEmpty()) return
		// Si llegan antes de que se cargue el episodio, se guardan para entonces
		if (idLibro != id) {
			capitulosPendientes = id to capitulos
			return
		}
		val l = libro ?: return
		if (l.capitulos.isNotEmpty()) return
		libro = LibroAudio(l.titulo, l.autor, l.narrador, l.pistas, capitulos)
		avisar()
	}

	/** Al terminar un audio externo (o al llegar a "saltar final"), se avisa a quien lo reproduce. */
	private fun escucharFin(j: ExoPlayer) {
		if (escuchandoFin) return
		escuchandoFin = true
		j.addListener(object : Player.Listener {
			override fun onPlaybackStateChanged(estado: Int) {
				if (estado == Player.STATE_ENDED) principal.post { terminarExterno() }
			}
		})
	}

	private fun terminarExterno() {
		val e = externo ?: return
		if (idLibro != e.id) return
		e.alGuardar(0, duracionPista())
		e.alTerminar()
	}

	/**
	 * Cuando habla TalkBack: con audio "de voz" el reproductor se pausa y sigue
	 * cuando TalkBack calla; con audio "de música" solo baja el volumen.
	 */
	fun atributos(contexto: Context): androidx.media3.common.AudioAttributes = androidx.media3.common.AudioAttributes.Builder()
		.setUsage(C.USAGE_MEDIA)
		.setContentType(if (Preferencias(contexto).talkbackAlHablar == "pausar") C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
		.build()

	/** "seguir": sin foco de audio (ni TalkBack ni otras apps lo pausan o bajan). */
	fun manejaFoco(contexto: Context) = Preferencias(contexto).talkbackAlHablar != "seguir"

	fun aplicarAtributos() {
		if (::app.isInitialized) jugador?.setAudioAttributes(atributos(app), manejaFoco(app))
	}

	fun registrar(o: Oyente) = oyentes.addIfAbsent(o)
	fun desregistrar(o: Oyente) = oyentes.remove(o)
	fun avisar() = principal.post { oyentes.forEach { runCatching { it.alCambiar() } } }

	fun esActual(id: Long) = libro != null && idLibro == id

	/** Conecta con el servicio (lo crea con su ExoPlayer) y llama a alListo cuando hay reproductor. */
	fun conectar(c: Context, alListo: () -> Unit) {
		iniciar(c)
		if (controlador == null) {
			controlador = MediaController.Builder(app, SessionToken(app, ComponentName(app, ServicioAudio::class.java))).buildAsync()
		}
		val futuro = controlador!!
		futuro.addListener({ if (jugador != null) alListo() else principal.postDelayed({ if (jugador != null) alListo() }, 300) }, { principal.post(it) })
	}

	/** Carga un libro en el reproductor y lo coloca en su posición guardada. */
	fun cargar(id: Long, l: LibroAudio, posicion: Posicion?) {
		val j = jugador ?: return
		if (esActual(id)) return
		guardar(forzar = true)
		idLibro = id
		libro = l
		externo = null
		val elementos = l.pistas.mapIndexed { i, pista ->
			MediaItem.Builder()
				.setUri(Uri.parse(pista.uri))
				.setMediaId("$id:$i")
				.setMediaMetadata(
					MediaMetadata.Builder()
						.setTitle(l.titulo)
						.setArtist(l.autor.ifBlank { l.narrador })
						.setAlbumTitle(l.titulo)
						.setTrackNumber(i + 1)
						.setTotalTrackCount(l.pistas.size)
						.build()
				)
				.build()
		}
		val p = Preferencias(app)
		j.setMediaItems(elementos, posicion?.pista?.coerceIn(0, (elementos.size - 1).coerceAtLeast(0)) ?: 0, posicion?.ms ?: 0L)
		// Cada audiolibro recuerda su velocidad; si no tiene, la de Ajustes
		j.playbackParameters = PlaybackParameters((ajustesLibro().optInt("velocidad_audio", 0).takeIf { it > 0 } ?: p.velocidadAudio) / 100f)
		j.skipSilenceEnabled = p.saltarSilencios
		j.prepare()
		cargarMarcadores()
		aplicarRefuerzo()
		avisar()
	}

	fun cargarMarcadores() {
		val l = libro ?: return
		marcadores = BaseDatos.obtener(app).marcadores(idLibro).filter { it.posicion.audio }
			.map { MarcaAudio(it.nota.ifBlank { it.lugar }, 0, it.posicion.pista, it.posicion.ms) }
			.sortedWith(compareBy({ it.pista }, { it.ms }))
		if (l.pistas.isEmpty()) marcadores = emptyList()
	}

	// --- Órdenes -------------------------------------------------------------

	val reproduciendo: Boolean get() = jugador?.let { it.playWhenReady && it.playbackState != Player.STATE_ENDED } == true

	fun alternar() = if (reproduciendo) pausar() else reproducir()

	fun reproducir() {
		val j = jugador ?: return
		// Retroceso al reanudar (para no perder el hilo), solo tras una pausa de más de 10 s
		val atras = Preferencias(app).retrocesoReanudar * 1000L
		if (pausadoEn > 0 && SystemClock.elapsedRealtime() - pausadoEn > 10_000 && atras > 0) {
			j.seekTo((j.currentPosition - atras).coerceAtLeast(0))
		}
		if (j.playbackState == Player.STATE_ENDED) j.seekTo(0, 0)
		j.volume = 1f
		j.play()
		pausadoPorDesconexion = 0
		// Temporizador automático por la noche (Ajustes, Audiolibros): si no hay ya uno puesto
		Preferencias(app).let { pr ->
			if (pr.temporizadorAuto && minutosTemporizador() == 0 && dentroDeFranja(pr.temporizadorAutoDesde, pr.temporizadorAutoHasta)) {
				temporizador(pr.temporizadorAutoMinutos)
				temporizadorAutomatico = true
			}
		}
		inicioSesion = SystemClock.elapsedRealtime()
		programarLatido()
		avisar()
	}

	fun pausar() {
		val j = jugador ?: return
		agitar.parar()
		principal.removeCallbacks(dejarDeAgitar)
		j.pause()
		pausadoEn = SystemClock.elapsedRealtime()
		guardar(forzar = true)
		avisar()
	}

	fun detener() {
		pausar()
		jugador?.stop()
		jugador?.clearMediaItems()
		libro = null
		idLibro = -1
		quitarTemporizador()
		controlador?.let { MediaController.releaseFuture(it) }
		controlador = null
		avisar()
	}

	/** Posición actual: (pista, ms). */
	fun posicion(): Pair<Int, Long> {
		val j = jugador ?: return 0 to 0L
		return j.currentMediaItemIndex to j.currentPosition.coerceAtLeast(0)
	}

	fun duracionPista(): Long = jugador?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: libro?.pistas?.getOrNull(posicion().first)?.duracionMs ?: 0L

	fun irA(pista: Int, ms: Long) {
		val j = jugador ?: return
		j.seekTo(pista.coerceIn(0, (j.mediaItemCount - 1).coerceAtLeast(0)), ms.coerceAtLeast(0))
		guardar(forzar = true)
		avisar()
	}

	/** Avanza o retrocede unos segundos (cruzando pistas si hace falta). */
	fun saltarSegundos(segundos: Int) {
		val j = jugador ?: return
		val (pista, ms) = posicion()
		var destino = ms + segundos * 1000L
		var p = pista
		if (destino < 0) {
			if (p > 0) {
				p--
				val dur = libro?.pistas?.getOrNull(p)?.duracionMs ?: 0L
				destino = (dur + destino).coerceAtLeast(0)
			} else destino = 0
		} else {
			val dur = duracionPista()
			if (dur > 0 && destino >= dur && p + 1 < j.mediaItemCount) {
				destino -= dur
				p++
			}
		}
		irA(p, destino)
	}

	/** Marcas de una unidad (capítulos, nivel N, páginas, frases, marcadores, pistas). */
	fun marcas(u: UnidadAudio): List<MarcaAudio> {
		val l = libro ?: return emptyList()
		return when (u) {
			UnidadAudio.CAPITULO -> l.capitulos.ifEmpty { pistasComoMarcas(l) }
			UnidadAudio.PAGINA -> l.paginas
			UnidadAudio.FRASE -> l.frases.map { MarcaAudio("", 0, it.pista, it.inicioMs) }
			UnidadAudio.MARCADOR -> marcadores
			UnidadAudio.PISTA -> pistasComoMarcas(l)
			else -> if (u.nivel > 0) l.capitulos.filter { it.nivel <= u.nivel } else emptyList()
		}
	}

	private fun pistasComoMarcas(l: LibroAudio) = l.pistas.mapIndexed { i, p -> MarcaAudio(p.titulo, 1, i, 0) }

	/** Unidades que tiene el libro (solo los niveles DAISY que existen). */
	fun unidadesDisponibles(): List<UnidadAudio> {
		val l = libro ?: return emptyList()
		val r = mutableListOf<UnidadAudio>()
		if (l.capitulos.isNotEmpty() || l.pistas.size > 1) r += UnidadAudio.CAPITULO
		if (l.niveles.size > 1) l.niveles.forEach { n -> if (n in 1..6) r += UnidadAudio.entries.first { it.nivel == n } }
		if (l.paginas.isNotEmpty()) r += UnidadAudio.PAGINA
		if (l.frases.isNotEmpty()) r += UnidadAudio.FRASE
		r += UnidadAudio.MARCADOR
		if (l.pistas.size > 1 && l.capitulos.isNotEmpty()) r += UnidadAudio.PISTA
		r += listOf(UnidadAudio.S10, UnidadAudio.S30, UnidadAudio.M1, UnidadAudio.M5, UnidadAudio.M10)
		return r
	}

	/** Salta a la siguiente/anterior marca de la unidad. Devuelve la marca o null si no hay más. */
	fun saltar(u: UnidadAudio, haciaDelante: Boolean): MarcaAudio? {
		if (u.segundos > 0) {
			saltarSegundos(if (haciaDelante) u.segundos else -u.segundos)
			return MarcaAudio("", 0, 0, 0)
		}
		val lista = marcas(u)
		val (pista, ms) = posicion()
		fun antes(m: MarcaAudio, margen: Long) = m.pista < pista || (m.pista == pista && m.ms < ms - margen)
		val destino = if (haciaDelante) {
			lista.firstOrNull { it.pista > pista || (it.pista == pista && it.ms > ms + 500) }
		} else {
			// Si lleva más de 3 s en la marca actual, vuelve a su principio
			val actualIdx = lista.indexOfLast { antes(it, -500) }
			val actual = lista.getOrNull(actualIdx)
			if (actual != null && (actual.pista < pista || ms - actual.ms > 3000)) actual else lista.getOrNull(actualIdx - 1)
		} ?: return null
		irA(destino.pista, destino.ms)
		return destino
	}

	fun capituloActual(): MarcaAudio? {
		val l = libro ?: return null
		val (pista, ms) = posicion()
		return l.capituloEn(pista, ms) ?: l.pistas.getOrNull(pista)?.let { MarcaAudio(it.titulo, 1, pista, 0) }
	}

	/** Inicio y final (ms dentro del libro si se conocen las duraciones) del capítulo actual. */
	fun limitesCapitulo(): Pair<MarcaAudio?, MarcaAudio?> {
		val lista = marcas(UnidadAudio.CAPITULO)
		val (pista, ms) = posicion()
		val i = lista.indexOfLast { it.pista < pista || (it.pista == pista && it.ms <= ms + 300) }
		return lista.getOrNull(i) to lista.getOrNull(i + 1)
	}

	fun cambiarVelocidad(porcentaje: Int) {
		val v = porcentaje.coerceIn(25, 400)
		// Se guarda en el libro (y como velocidad para los audiolibros nuevos)
		Preferencias(app).p.edit().putInt("velocidad_audio", v).apply()
		if (idLibro > 0) BaseDatos.obtener(app).guardarAjustesLibro(idLibro, ajustesLibro().put("velocidad_audio", v))
		externo?.takeIf { it.id == idLibro }?.alCambiarVelocidad?.invoke(v)
		jugador?.playbackParameters = PlaybackParameters(v / 100f)
		avisar()
	}

	private fun ajustesLibro(): org.json.JSONObject =
		runCatching { org.json.JSONObject(BaseDatos.obtener(app).libro(idLibro)?.ajustes.orEmpty()) }.getOrDefault(org.json.JSONObject())

	// Redondeado: 0,9999 es 100 %, no 99 %
	fun velocidad(): Int = Math.round((jugador?.playbackParameters?.speed ?: 1f) * 100)

	fun cambiarSaltarSilencios(activo: Boolean) {
		Preferencias(app).p.edit().putBoolean("saltar_silencios", activo).apply()
		jugador?.skipSilenceEnabled = activo
	}

	fun aplicarRefuerzo() {
		val j = jugador ?: return
		val ganancia = Preferencias(app).refuerzoVolumen
		runCatching {
			refuerzo?.release()
			refuerzo = null
			if (ganancia > 0 && j.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
				refuerzo = LoudnessEnhancer(j.audioSessionId).apply {
					setTargetGain(ganancia * 100)
					enabled = true
				}
			}
		}
	}

	// --- Temporizador --------------------------------------------------------------

	private val latido = object : Runnable {
		override fun run() {
			val j = jugador ?: return
			if (!j.isPlaying) return
			guardar()
			val ahora = SystemClock.elapsedRealtime()
			if (finTemporizador > 0) {
				val queda = finTemporizador - ahora
				if (queda <= 0) {
					quitarTemporizador()
					pausar()
					marcarParadaPorTemporizador()
					return
				}
				// Baja el volumen en los últimos 20 segundos
				if (queda < 20_000) j.volume = (queda / 20_000f).coerceIn(0.1f, 1f)
			}
			if (alFinalDelCapitulo) {
				val (_, siguiente) = limitesCapitulo()
				val (pista, ms) = posicion()
				if (siguiente != null && siguiente.pista == pista && siguiente.ms - ms in 0..1200) {
					alFinalDelCapitulo = false
					pausar()
					marcarParadaPorTemporizador()
					return
				}
			}
			principal.postDelayed(this, 1000)
		}
	}

	private fun programarLatido() {
		principal.removeCallbacks(latido)
		principal.postDelayed(latido, 1000)
	}

	/** El temporizador ha parado la reproducción: agitar en los 3 minutos siguientes la reanuda. */
	private fun marcarParadaPorTemporizador() {
		paradoPorTemporizador = SystemClock.elapsedRealtime()
		agitar.empezar { alAgitar() }
		principal.removeCallbacks(dejarDeAgitar)
		principal.postDelayed(dejarDeAgitar, 3 * 60_000L)
	}

	/** Agitar: reproduciendo, el temporizador vuelve a empezar; si acaba de parar, sigue la reproducción. */
	private fun alAgitar() {
		val minutos = minutosElegidos
		when {
			reproduciendo && minutos != 0 -> temporizador(minutos)
			!reproduciendo && paradoPorTemporizador > 0 && SystemClock.elapsedRealtime() - paradoPorTemporizador < 3 * 60_000L -> {
				reproducir()
				temporizador(minutos)
			}
		}
		avisar()
	}

	/** Episodios que faltan para parar (temporizador «al final del episodio» o «tras N episodios»); 0 = no. */
	private var episodiosTemporizador = 0

	/**
	 * Ha terminado un episodio: si el temporizador es por episodios, cuenta uno y, si era el
	 * último, devuelve true (no se sigue con la cola) y queda como parada por temporizador.
	 */
	fun pararTrasEpisodio(): Boolean {
		if (episodiosTemporizador <= 0) return false
		episodiosTemporizador--
		if (episodiosTemporizador > 0) return false.also { avisar() }
		marcarParadaPorTemporizador()
		avisar()
		return true
	}

	/** Pausado porque se desconectaron los auriculares: al volver a conectarlos puede seguir solo. */
	private var pausadoPorDesconexion = 0L

	fun alDesconectarAuriculares() {
		pausadoPorDesconexion = SystemClock.elapsedRealtime()
	}

	/** Se han conectado unos auriculares (o un altavoz Bluetooth): sigue si se pausó al quitarlos hace poco. */
	fun alConectarAuriculares() {
		if (pausadoPorDesconexion == 0L || reproduciendo) return
		val reciente = SystemClock.elapsedRealtime() - pausadoPorDesconexion < 15 * 60_000L
		pausadoPorDesconexion = 0
		if (reciente && Preferencias(app).reanudarAlConectar) reproducir()
	}

	fun temporizador(minutos: Int) {
		minutosElegidos = minutos
		episodiosTemporizador = when {
			minutos == -2 -> 1
			minutos <= -100 -> -minutos - 100
			else -> 0
		}
		paradoPorTemporizador = 0
		principal.removeCallbacks(dejarDeAgitar)
		if (minutos != 0) agitar.empezar { alAgitar() } else agitar.parar()
		finTemporizador = 0
		alFinalDelCapitulo = false
		jugador?.volume = 1f
		when {
			minutos > 0 -> finTemporizador = SystemClock.elapsedRealtime() + minutos * 60_000L
			minutos == -1 -> alFinalDelCapitulo = true
		}
		programarLatido()
		avisar()
	}

	/** Se acaba de poner el temporizador automático (el reproductor lo dice y lo borra). */
	var temporizadorAutomatico = false

	/** ¿La hora de ahora está entre «desde» y «hasta» (HH:MM; puede pasar de medianoche)? */
	private fun dentroDeFranja(desde: String, hasta: String): Boolean {
		fun minutos(t: String) = t.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
		val ahora = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
		val a = minutos(desde)
		val b = minutos(hasta)
		return if (a <= b) ahora in a until b else ahora >= a || ahora < b
	}

	private fun quitarTemporizador() {
		finTemporizador = 0
		alFinalDelCapitulo = false
		jugador?.volume = 1f
	}

	/** Minutos que quedan (0 = sin temporizador, -1 = al final del capítulo, -2 = al final del episodio, -10N = tras N episodios). */
	fun minutosTemporizador(): Int = when {
		episodiosTemporizador == 1 -> -2
		episodiosTemporizador > 1 -> -100 - episodiosTemporizador
		alFinalDelCapitulo -> -1
		finTemporizador > 0 -> ((finTemporizador - SystemClock.elapsedRealtime()) / 60_000 + 1).toInt()
		else -> 0
	}

	// --- Guardado --------------------------------------------------------------------

	/** Porcentaje escuchado (por duración si se conoce; si no, por pistas). */
	fun progreso(): Double {
		val l = libro ?: return 0.0
		val (pista, ms) = posicion()
		val total = l.duracionTotal
		if (total > 0) return ((l.inicioDePista(pista) + ms).toDouble() / total * 100).coerceIn(0.0, 100.0)
		val dur = duracionPista().coerceAtLeast(1)
		return ((pista + ms.toDouble() / dur) / l.pistas.size.coerceAtLeast(1) * 100).coerceIn(0.0, 100.0)
	}

	fun guardar(forzar: Boolean = false) {
		val e = externo
		if (e != null && idLibro == e.id && libro != null) {
			val ahora = SystemClock.elapsedRealtime()
			if (!forzar && ahora - ultimoGuardado < 5_000) return
			ultimoGuardado = ahora
			val dur = duracionPista()
			val ms = posicion().second
			e.alGuardar(ms, dur)
			// Saltar el final (por ejemplo, la despedida del podcast)
			if (e.saltarFinal > 0 && dur > 0 && reproduciendo && ms >= dur - e.saltarFinal * 1000L) {
				jugador?.pause()
				principal.post { terminarExterno() }
			}
			return
		}
		if (idLibro <= 0 || libro == null) return
		val ahora = SystemClock.elapsedRealtime()
		if (!forzar && ahora - ultimoGuardado < 10_000) return
		ultimoGuardado = ahora
		val (pista, ms) = posicion()
		val db = BaseDatos.obtener(app)
		db.guardarPosicion(idLibro, Posicion.audio(pista, ms), progreso())
		if (inicioSesion > 0) {
			db.sumarLectura(idLibro, (ahora - inicioSesion) / 1000, 0)
			inicioSesion = if (reproduciendo) ahora else 0L
		}
	}
}
