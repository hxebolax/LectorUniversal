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
	/** Lo que quedaba del temporizador al pausar (ms); vuelve a contar al reanudar. */
	private var restanteTemporizador = 0L
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
		/** Radio en directo: sin posición ni duración, se reconecta si se corta y dice la canción que suena. */
		val directo: Boolean = false,
		/** Tipo del audio si no se deduce de la dirección (HLS: application/x-mpegURL). */
		val tipo: String? = null,
		/** Emisora siguiente (true) o anterior (false) con los botones de la notificación y los auriculares. */
		val alPasar: ((Boolean) -> Unit)? = null,
		/** Ha cambiado la canción que dice la emisora. */
		val alCambiarCancion: (String) -> Unit = {},
		/** Es un despertador: si la emisora no suena, suena la alarma del sistema. */
		val despertador: Boolean = false,
		/** Sección en cuyas estadísticas cuenta el tiempo (vacío: radio si es en directo, si no podcasts). */
		val seccion: String = "",
		/** Refuerzo de volumen propio en dB (el de su podcast); null = el general del reproductor. Se cambia desde el reproductor. */
		var refuerzo: Int? = null,
	) {
		val seccionActividad: String
			get() = seccion.ifEmpty { if (directo) com.xebolax.lector.datos.Actividad.RADIO else com.xebolax.lector.datos.Actividad.PODCASTS }
	}

	/** Lo que dice la emisora que suena ahora (radio en directo). */
	var cancion = ""
		private set

	/** Radio: qué le pasa a la conexión (vacío si nada): «Reconectando…», «Sin conexión». */
	var problema = ""
		private set
	private var reintentos = 0
	private var alarmaDeReserva = false

	var externo: Externo? = null
		private set

	/** Lo que la pantalla del reproductor debe abrir (se pone antes de abrirla). */
	@Volatile
	var externoPendiente: Externo? = null

	/** ¿Puede FuenteRed pasar de https a http si el certificado falla? Solo con la radio en directo (sonido público). */
	@Volatile
	var permitirHttp = false
		private set

	/** El reproductor al que ya se le puso el oyente (si el servicio se vuelve a crear, hay que ponérselo al nuevo). */
	private var escuchandoA: ExoPlayer? = null

	/** Carga un audio externo y lo coloca donde se quedó. */
	fun cargarExterno(e: Externo) {
		val j = jugador ?: return
		if (esActual(e.id) && !alarmaDeReserva) {
			// El mismo audio, pero puede venir ahora como despertador (o dejar de serlo)
			usarAlarma(e.despertador)
			permitirHttp = e.directo
			externo = e
			return
		}
		guardar(forzar = true)
		usarAlarma(e.despertador)
		// Solo la radio en directo puede pasar de https a http si el certificado falla
		permitirHttp = e.directo
		externo = e
		idLibro = e.id
		terminadoId = 0
		paradoTrasEpisodio = false
		cancion = ""
		problema = ""
		reintentos = 0
		alarmaDeReserva = false
		principal.removeCallbacks(reintentar)
		val incrustados = capitulosPendientes?.takeIf { it.first == e.id }?.second.orEmpty()
		capitulosPendientes = null
		libro = LibroAudio(e.titulo, e.autor, pistas = listOf(com.xebolax.lector.modelo.Pista(e.url, e.titulo)), capitulos = e.capitulos.ifEmpty { incrustados })
		val elemento = MediaItem.Builder()
			.setUri(Uri.parse(e.url))
			.setMediaId("${e.id}:0")
			.apply { e.tipo?.let { setMimeType(it) } }
			.setMediaMetadata(MediaMetadata.Builder().setTitle(e.titulo).setArtist(e.autor).build())
			.build()
		val inicio = if (e.directo) 0L else if (e.posicionMs > 0) e.posicionMs else e.saltarInicio * 1000L
		j.repeatMode = Player.REPEAT_MODE_OFF
		j.setMediaItem(elemento, inicio)
		// La radio va siempre a su velocidad y sin quitar silencios (es música y voz en directo)
		j.playbackParameters = PlaybackParameters(if (e.directo) 1f else (e.velocidad ?: Preferencias(app).velocidadAudio) / 100f)
		j.skipSilenceEnabled = !e.directo && Preferencias(app).saltarSilencios
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
		if (escuchandoA === j) return
		escuchandoA = j
		j.addListener(object : Player.Listener {
			// Empieza o deja de sonar de verdad (tras cargar, al reconectar): las estadísticas y quien escuche lo necesitan
			override fun onIsPlayingChanged(sonando: Boolean) = avisar().let { }

			override fun onPlaybackStateChanged(estado: Int) {
				if (estado == Player.STATE_ENDED) principal.post { if (externo?.directo == true) alCortarse(null) else terminarExterno() }
				if (estado == Player.STATE_READY && externo?.directo == true && problema.isNotEmpty()) {
					problema = ""
					reintentos = 0
					avisar()
				}
			}

			// Radio: la canción que suena llega en los metadatos ICY del directo (o en los de HLS)
			override fun onMetadata(metadatos: androidx.media3.common.Metadata) {
				val e = externo ?: return
				if (!e.directo) return
				for (i in 0 until metadatos.length()) {
					val titulo = when (val m = metadatos[i]) {
						is androidx.media3.extractor.metadata.icy.IcyInfo -> m.title
						is androidx.media3.extractor.metadata.id3.TextInformationFrame -> m.values.firstOrNull()?.takeIf { m.id == "TIT2" }
						else -> null
					}?.trim()?.takeIf { it.isNotBlank() && it != "-" } ?: continue
					principal.post {
						if (externo === e && titulo != cancion) {
							cancion = titulo
							e.alCambiarCancion(titulo)
							avisar()
						}
					}
				}
			}

			override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
				if (externo?.directo == true) principal.post { alCortarse(error) }
			}

			// Radio que «suena» en silencio: hay sonido, pero en un formato que ni el móvil ni FFmpeg saben leer
			override fun onTracksChanged(pistas: androidx.media3.common.Tracks) {
				val e = externo ?: return
				if (!e.directo) return
				val audio = pistas.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
				if (audio.isNotEmpty() && audio.none { it.isSupported }) {
					val mime = audio.first().getTrackFormat(0).sampleMimeType?.substringAfter('/')?.uppercase() ?: "?"
					principal.post {
						if (externo !== e) return@post
						problema = app.getString(com.xebolax.lector.R.string.radio_formato_no_compatible, mime)
						avisar()
					}
				}
			}
		})
	}

	/** Reintentos de la radio cuando se corta (Ajustes, Radio). */
	private fun maxReintentos() = Preferencias(app).p.getString("radio_reintentos", "5")?.toIntOrNull() ?: 5

	private val reintentar = Runnable {
		val j = jugador ?: return@Runnable
		if (externo?.directo != true) return@Runnable
		j.seekToDefaultPosition()
		j.prepare()
		j.play()
	}

	/** Código HTTP del error (si el servidor respondió con uno), buscando en las causas. */
	private fun Throwable.codigoHttp(): Int? = generateSequence(this as Throwable?) { it.cause }
		.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode

	/** Errores que no se arreglan reintentando: el servidor rechaza la conexión, no existe o el formato no se puede leer. */
	private fun Throwable.esDefinitivo(): Boolean {
		val codigo = (this as? androidx.media3.common.PlaybackException)?.errorCode ?: return false
		val http = codigoHttp()
		return codigo in setOf(
			androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
			androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
			androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
			androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
			androidx.media3.common.PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
		) || (codigo == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && http != null && http in 400..499)
	}

	/** El motivo dicho con palabras («La emisora no deja conectarse (error 403 del servidor)»). */
	private fun Throwable.explicacion(): String {
		val codigo = (this as? androidx.media3.common.PlaybackException)?.errorCode
		val http = codigoHttp()
		return when {
			http != null && http in 400..499 -> app.getString(com.xebolax.lector.R.string.radio_error_rechaza, http)
			http != null -> app.getString(com.xebolax.lector.R.string.radio_error_servidor, http)
			codigo == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
				codigo == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
				codigo == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> app.getString(com.xebolax.lector.R.string.radio_formato_no_compatible, "?")
			else -> app.getString(com.xebolax.lector.R.string.radio_sin_conexion) + " (" + app.getString(com.xebolax.lector.R.string.radio_error_codigo, errorCodeName()) + ")"
		}
	}

	/** Se ha cortado la radio: se reconecta unas cuantas veces y, si es un despertador, suena la alarma del sistema. */
	private fun alCortarse(error: Throwable?) {
		val e = externo ?: return
		if (alarmaDeReserva) return
		val maximo = maxReintentos()
		// Lo que no se arregla reintentando se dice ya (sin esperar a los reintentos)
		if (error != null && error.esDefinitivo()) {
			problema = error.explicacion()
			principal.removeCallbacks(reintentar)
			if (e.despertador) sonarAlarmaDeReserva()
			avisar()
			return
		}
		if (jugador?.playWhenReady == true && (maximo < 0 || reintentos < maximo)) {
			reintentos++
			problema = app.getString(com.xebolax.lector.R.string.radio_reconectando, reintentos)
			principal.removeCallbacks(reintentar)
			// Cada vez se espera un poco más (3, 6, 9… hasta 30 segundos)
			principal.postDelayed(reintentar, (3_000L * reintentos).coerceAtMost(30_000L))
		} else {
			problema = error?.explicacion() ?: app.getString(com.xebolax.lector.R.string.radio_sin_conexion)
			if (e.despertador) sonarAlarmaDeReserva()
		}
		avisar()
	}

	private fun Throwable.errorCodeName(): String = (this as? androidx.media3.common.PlaybackException)?.errorCodeName ?: javaClass.simpleName

	/** El despertador no consigue sonar con la emisora: suena el tono de alarma del móvil, en bucle. */
	fun sonarAlarmaDeReserva() {
		val j = jugador ?: return
		val tono = android.media.RingtoneManager.getActualDefaultRingtoneUri(app, android.media.RingtoneManager.TYPE_ALARM)
			?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
			?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE) ?: return
		alarmaDeReserva = true
		usarAlarma(true)
		principal.removeCallbacks(reintentar)
		problema = app.getString(com.xebolax.lector.R.string.radio_alarma_reserva)
		j.setMediaItem(MediaItem.Builder().setUri(tono).setMediaId("alarma").setMediaMetadata(MediaMetadata.Builder().setTitle(app.getString(com.xebolax.lector.R.string.radio_despertador)).build()).build())
		j.repeatMode = Player.REPEAT_MODE_ONE
		j.prepare()
		j.volume = 1f
		j.play()
		avisar()
	}

	private val subida = object : Runnable {
		var paso = 0
		var pasos = 1
		/** Está subiendo el volumen (el temporizador no debe ponerlo al máximo de golpe). */
		var activa = false
		override fun run() {
			val j = jugador ?: return run { activa = false }
			paso++
			j.volume = (0.08f + 0.92f * paso / pasos).coerceAtMost(1f)
			activa = paso < pasos && reproduciendo
			if (activa) principal.postDelayed(this, 1000)
		}
	}

	/** Volumen al máximo, salvo si el despertador lo está subiendo poco a poco. */
	private fun volumenNormal() {
		if (!subida.activa) jugador?.volume = 1f
	}

	/** Despertador: el volumen empieza bajo y sube poco a poco durante esos segundos. */
	fun subirPocoAPoco(segundos: Int) {
		val j = jugador ?: return
		principal.removeCallbacks(subida)
		subida.activa = false
		if (segundos <= 0) return
		j.volume = 0.08f
		subida.paso = 0
		subida.pasos = segundos
		subida.activa = true
		principal.postDelayed(subida, 1000)
	}

	/** El externo que ya ha terminado (al saltar el final): no se vuelve a terminar si se reanuda. */
	private var terminadoId = 0L

	/** Lo que decidió el temporizador por episodios al terminar el último audio externo: true = parar ahí. */
	private var paradoTrasEpisodio = false

	private fun terminarExterno() {
		val e = externo ?: return
		if (idLibro != e.id || terminadoId == e.id) return
		terminadoId = e.id
		e.alGuardar(0, duracionPista())
		// El temporizador «al final del episodio» cuenta cualquier audio que termina (también una noticia o un episodio sin suscribirse)
		paradoTrasEpisodio = contarEpisodioTerminado()
		// «Al final del capítulo» en el último capítulo (o sin capítulos): para aquí, como al final del episodio
		if (alFinalDelCapitulo) {
			alFinalDelCapitulo = false
			paradoTrasEpisodio = true
			marcarParadaPorTemporizador()
			avisar()
		}
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
		// Mientras suena el despertador sigue con el volumen de alarma (cambiar Ajustes no lo pasa a multimedia)
		if (comoAlarma && atributosDe === jugador) return
		if (::app.isInitialized) jugador?.setAudioAttributes(atributos(app), manejaFoco(app))
	}

	/** ¿Suena ahora como alarma (despertador)? */
	private var comoAlarma = false
	/** El reproductor al que se le pusieron esos atributos (si el servicio se recrea, el nuevo empieza como multimedia). */
	private var atributosDe: ExoPlayer? = null

	/** El despertador, con el volumen de alarma (suena aunque el multimedia esté a 0); lo demás, como multimedia. */
	private fun usarAlarma(si: Boolean) {
		val j = jugador ?: return
		if (si == comoAlarma && atributosDe === j) return
		comoAlarma = si
		atributosDe = j
		if (si) j.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_ALARM).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
		else aplicarAtributos()
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
		futuro.addListener({
			// Si no se pudo conectar, se olvida: la próxima vez se vuelve a intentar
			if (runCatching { futuro.get() }.isFailure) {
				if (controlador === futuro) controlador = null
				return@addListener
			}
			if (jugador != null) alListo() else principal.postDelayed({ if (jugador != null) alListo() }, 300)
		}, { principal.post(it) })
	}

	/** Carga un libro en el reproductor y lo coloca en su posición guardada. */
	fun cargar(id: Long, l: LibroAudio, posicion: Posicion?) {
		usarAlarma(false)
		val j = jugador ?: return
		if (esActual(id)) return
		guardar(forzar = true)
		permitirHttp = false
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

	/** ¿Suena de verdad? (no cargando ni reconectando, ni el tono de alarma de reserva): para las estadísticas. */
	val sonandoDeVerdad: Boolean get() = jugador?.isPlaying == true && !alarmaDeReserva

	fun alternar() = if (reproduciendo) pausar() else reproducir()

	fun reproducir() {
		val j = jugador ?: return
		// Solo suena una cosa: si se estaba leyendo en voz alta, se pausa
		if (com.xebolax.lector.voz.LectorVoz.leyendo) com.xebolax.lector.voz.LectorVoz.pausar()
		Sonando.ultima = Sonando.Fuente.AUDIO
		if (externo?.directo == true) {
			// Radio: tras una pausa (o un corte) se vuelve al directo, no a lo que quedó en el búfer
			if (alarmaDeReserva) Unit
			else if (j.playbackState == Player.STATE_IDLE || j.playbackState == Player.STATE_ENDED || (pausadoEn > 0 && SystemClock.elapsedRealtime() - pausadoEn > 10_000)) {
				j.seekToDefaultPosition()
				j.prepare()
			}
			reintentos = 0
			problema = ""
		} else {
			// Retroceso al reanudar (para no perder el hilo), solo tras una pausa de más de 10 s
			val atras = Preferencias(app).retrocesoReanudar * 1000L
			if (pausadoEn > 0 && SystemClock.elapsedRealtime() - pausadoEn > 10_000 && atras > 0) {
				j.seekTo((j.currentPosition - atras).coerceAtLeast(0))
			}
			if (j.playbackState == Player.STATE_ENDED) j.seekTo(0, 0)
			if (j.playbackState == Player.STATE_IDLE) j.prepare()
		}
		volumenNormal()
		j.play()
		pausadoPorDesconexion = 0
		// El temporizador que se paró con la pausa vuelve a contar desde donde estaba
		if (restanteTemporizador > 0) {
			finTemporizador = SystemClock.elapsedRealtime() + restanteTemporizador
			restanteTemporizador = 0
		}
		// Con temporizador puesto, agitar vuelve a funcionar (la pausa lo apagó)
		if (minutosTemporizador() != 0) agitar.empezar { alAgitar() }
		// Temporizador automático por la noche (Ajustes, Audiolibros): si no hay ya uno puesto (y nunca al despertador)
		Preferencias(app).let { pr ->
			if (pr.temporizadorAuto && externo?.despertador != true && minutosTemporizador() == 0 && dentroDeFranja(pr.temporizadorAutoDesde, pr.temporizadorAutoHasta)) {
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
		principal.removeCallbacks(reintentar)
		principal.removeCallbacks(subida)
		subida.activa = false
		j.pause()
		pausadoEn = SystemClock.elapsedRealtime()
		if (finTemporizador > 0) {
			restanteTemporizador = (finTemporizador - pausadoEn).coerceAtLeast(1000)
			finTemporizador = 0
		}
		guardar(forzar = true)
		avisar()
	}

	fun detener() {
		pausar()
		jugador?.stop()
		jugador?.clearMediaItems()
		jugador?.repeatMode = Player.REPEAT_MODE_OFF
		libro = null
		idLibro = -1
		externo = null
		cancion = ""
		problema = ""
		alarmaDeReserva = false
		// Después de pausar (que guarda lo que quedaba): se borra todo
		borrarTemporizador()
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
		if (externo == null) r += UnidadAudio.MARCADOR
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
		// Se guarda en el libro (y como velocidad para los audiolibros nuevos); la de un episodio va a su podcast
		if (idLibro > 0) Preferencias(app).p.edit().putInt("velocidad_audio", v).apply()
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

	/** Suelta el refuerzo de volumen (al cerrarse el servicio de audio). */
	fun soltarRefuerzo() {
		runCatching { refuerzo?.release() }
		refuerzo = null
	}

	fun aplicarRefuerzo() {
		val j = jugador ?: return
		// El del podcast si tiene uno propio; si no, el general
		val ganancia = externo?.refuerzo ?: Preferencias(app).refuerzoVolumen
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
			// En pausa de verdad se para; cargando (búfer) o con TalkBack hablando, sigue contando
			if (!j.playWhenReady) return
			if (j.isPlaying) guardar()
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
				// Al final de la pista solo si ahí acaba el capítulo: el siguiente empieza al principio de la pista
				// siguiente (no a mitad de un capítulo de varias pistas, DAISY o carpetas) o es la última pista.
				// Un audio externo termina solo y lo para terminarExterno (así cuenta como terminado)
				val ultimaPista = (libro?.pistas?.size ?: 1) - 1
				val finPista = externo == null && duracionPista().let { d -> d > 0 && d - ms in 0..1200 } &&
					(if (siguiente == null) pista == ultimaPista else siguiente.pista == pista + 1 && siguiente.ms <= 300)
				if (finPista || (siguiente != null && siguiente.pista == pista && siguiente.ms - ms in 0..1200)) {
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
				val e = externo
				// paradoTrasEpisodio: el temporizador lo paró al terminar este mismo episodio (se borra al cargar otro)
				if (e != null && !e.directo && paradoTrasEpisodio && e.id == idLibro) {
					// Paró al terminar el episodio: se sigue con la cola (no se vuelve a empezar el mismo)
					paradoTrasEpisodio = false
					temporizador(minutos)
					e.alTerminar()
				} else {
					reproducir()
					temporizador(minutos)
				}
			}
		}
		avisar()
	}

	/** Episodios que faltan para parar (temporizador «al final del episodio» o «tras N episodios»); 0 = no. */
	private var episodiosTemporizador = 0

	/**
	 * Al terminar el último audio externo, ¿lo paró el temporizador por episodios? (Podcasts
	 * lo consulta para no seguir con la cola; el contador ya lo ha gastado terminarExterno.)
	 */
	fun pararTrasEpisodio(): Boolean = paradoTrasEpisodio

	/**
	 * «Dar por escuchado y pasar al siguiente»: también cuenta para el temporizador por
	 * episodios. Devuelve true si toca parar (queda en pausa; agitar sigue con la cola).
	 */
	fun pasarEpisodio(): Boolean {
		val e = externo
		if (episodiosTemporizador == 1) {
			pausar()
			if (e != null) terminadoId = e.id
		}
		paradoTrasEpisodio = contarEpisodioTerminado()
		return paradoTrasEpisodio
	}

	/**
	 * Ha terminado un audio externo: si el temporizador es por episodios, cuenta uno y, si era el
	 * último, devuelve true (no se sigue con la cola) y queda como parada por temporizador.
	 */
	private fun contarEpisodioTerminado(): Boolean {
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
		paradoTrasEpisodio = false
		principal.removeCallbacks(dejarDeAgitar)
		if (minutos != 0) agitar.empezar { alAgitar() } else agitar.parar()
		finTemporizador = 0
		restanteTemporizador = 0
		alFinalDelCapitulo = false
		// Con el despertador subiendo poco a poco, no se pone al máximo de golpe
		volumenNormal()
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
		// Escrita a mano en versiones anteriores podía ser «23.00» o «2300»: se lee igual que «23:00»
		fun minutos(t: String) = (com.xebolax.lector.ui.normalizarHora(t) ?: t).split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
		val ahora = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
		val a = minutos(desde)
		val b = minutos(hasta)
		return if (a <= b) ahora in a until b else ahora >= a || ahora < b
	}

	private fun quitarTemporizador() {
		finTemporizador = 0
		alFinalDelCapitulo = false
		volumenNormal()
	}

	/** Al detener: no queda nada del temporizador (lo siguiente que suene no hereda los minutos que quedaban, ni agitar). */
	private fun borrarTemporizador() {
		quitarTemporizador()
		restanteTemporizador = 0
		episodiosTemporizador = 0
		paradoTrasEpisodio = false
		minutosElegidos = 0
		paradoPorTemporizador = 0
		temporizadorAutomatico = false
		principal.removeCallbacks(latido)
		principal.removeCallbacks(dejarDeAgitar)
		if (::app.isInitialized) agitar.parar()
	}

	/** Minutos que quedan (0 = sin temporizador, -1 = al final del capítulo, -2 = al final del episodio, -10N = tras N episodios). */
	fun minutosTemporizador(): Int = when {
		episodiosTemporizador == 1 -> -2
		episodiosTemporizador > 1 -> -100 - episodiosTemporizador
		alFinalDelCapitulo -> -1
		finTemporizador > 0 -> ((finTemporizador - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 60_000 + 1).toInt()
		restanteTemporizador > 0 -> (restanteTemporizador / 60_000 + 1).toInt()
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
		// La radio no tiene posición que guardar
		if (e != null && e.directo) return
		if (e != null && idLibro == e.id && libro != null) {
			val ahora = SystemClock.elapsedRealtime()
			if (!forzar && ahora - ultimoGuardado < 5_000) return
			ultimoGuardado = ahora
			val dur = duracionPista()
			val ms = posicion().second
			e.alGuardar(ms, dur)
			// Ha vuelto atrás tras terminar: puede volver a terminar
			// Se ha retrocedido: el episodio ya no está terminado (agitar o Reproducir lo siguen, no pasan al siguiente)
			if (terminadoId == e.id && dur > 0 && ms < dur - e.saltarFinal * 1000L - 10_000) {
				terminadoId = 0
				paradoTrasEpisodio = false
			}
			// Saltar el final (por ejemplo, la despedida del podcast), una sola vez: si se reanuda, sigue sonando
			if (e.saltarFinal > 0 && dur > 0 && reproduciendo && terminadoId != e.id && ms >= dur - e.saltarFinal * 1000L) {
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
