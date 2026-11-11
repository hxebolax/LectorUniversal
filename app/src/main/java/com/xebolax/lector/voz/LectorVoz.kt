package com.xebolax.lector.voz

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.xebolax.lector.R
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.modelo.Documento
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.Posicion
import com.xebolax.lector.modelo.Segmentador
import com.xebolax.lector.modelo.TipoBloque
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lectura en voz alta con el motor de voz del sistema, frase a frase. Una
 * sola para toda la app; sigue con la pantalla apagada gracias a ServicioVoz
 * (notificación, pantalla de bloqueo y botones de auriculares).
 *
 * Cómo funciona (como Paperback y los lectores profesionales): el motor de
 * voz no habla directamente, sino que genera el audio de las frases
 * siguientes en archivos (unas pocas por delante) y la app los reproduce con
 * su propio reproductor (ExoPlayer). Así:
 * - TalkBack no tiene que esperar a que acabe la frase del libro para hablar
 *   (comparten el motor de voz; antes TalkBack se quedaba "bloqueado").
 * - Cuando habla TalkBack, el libro se pausa (o baja el volumen, según
 *   Ajustes) y sigue solo cuando TalkBack calla. Lo gestiona Android con el
 *   foco de audio, como con cualquier reproductor.
 * - Pausar y reanudar sigue a mitad de frase, y el botón Reproducir/Pausar
 *   siempre dice el estado real.
 * - Los botones de auriculares y Bluetooth llegan a la lectura.
 *
 * Además: voz, motor, velocidad y tono de Ajustes (o los propios del libro),
 * cambio de voz si un párrafo está en otro idioma, diccionario de
 * pronunciación, pausa entre párrafos, saltar notas, temporizador (minutos o
 * fin del capítulo, con bajada de volumen y "agitar para prolongar") y
 * guardado de la posición.
 *
 * Con el motor «ElevenLabs» (voces en la nube con la clave del usuario) el
 * audio de cada trozo lo genera ElevenLabs en vez del motor del móvil; la
 * velocidad y el tono los pone el reproductor. Si ElevenLabs falla, se dice
 * por qué y se sigue con la voz del móvil (o se para, según Ajustes).
 */
@SuppressLint("StaticFieldLeak")
object LectorVoz {

	data class Estado(
		val libro: Long,
		val activo: Boolean,
		val leyendo: Boolean,
		val bloque: Int,
		val inicio: Int,
		val fin: Int,
		/** Minutos que quedan del temporizador (0 = sin temporizador; -1 = al final del capítulo). */
		val temporizador: Int,
		val titulo: String,
	)

	fun interface Oyente {
		fun alCambiar(estado: Estado)
	}

	/** Frases que se generan por delante de la que suena. */
	private const val POR_DELANTE = 3
	private const val ETIQUETA = "LectorVoz"
	private const val PEDIDO = 0
	private const val LISTO = 1
	private const val FALLIDO = 2

	private val principal = Handler(Looper.getMainLooper())
	private val oyentes = CopyOnWriteArrayList<Oyente>()
	private lateinit var app: Context

	private var tts: TextToSpeech? = null
	private var motorActual: String? = null
	private var listo = false
	private var pendiente = false
	private var jugador: ExoPlayer? = null

	var documento: Documento? = null
		private set
	var libro: Long = -1
		private set
	var titulo = ""
		private set
	/** Pantalla a la que vuelve el mini reproductor y la notificación (una noticia, que no tiene id de libro). */
	var volver: android.content.Intent? = null
	var bloque = 0
		private set
	private var frases: List<IntRange> = emptyList()
	private var frase = 0
	/** El usuario quiere que lea (aunque esté un momento callada porque habla TalkBack). */
	var leyendo = false
		private set
	private var generacion = 0
	/** La posición cambió (salto, velocidad, voz): al reanudar hay que generar desde ahí. */
	private var rehacer = true
	private var pronunciador: Pronunciador? = null
	/** Voz propia del libro o de las noticias (lo que no tenga, como en Ajustes). */
	private var perfil = PerfilVoz()
	/** El documento es una noticia (perfil de voz de las noticias y «Fin del artículo»). */
	private var esNoticia = false
	private var vozElegida: Voice? = null
	/** ElevenLabs falló en esta lectura: se sigue con la voz del móvil hasta que el usuario vuelva a pulsar Reproducir. */
	@Volatile private var nubeFallida = false
	/** Ya se ha dicho por qué falló ElevenLabs (una vez por lectura). */
	private var nubeAvisada = false

	/** ¿Lee ElevenLabs? (motor elegido, con clave y sin haber fallado en esta lectura). */
	private val nube: Boolean
		get() = ElevenLabs.esMotor(perfil.motorEfectivo(Preferencias(app))) && !nubeFallida && ElevenLabs.configurada(app)

	/** Frase pedida al motor y aún no puesta en el reproductor. */
	private class Segmento(val gen: Int, val b: Int, val f: Int, val archivo: File, val nuevoBloque: Boolean) {
		var estado = PEDIDO
		val pedido = SystemClock.elapsedRealtime()
		val id get() = "$gen:$b:$f"
	}

	private val cola = ArrayDeque<Segmento>()
	/** Última frase pedida al motor (bloque, frase). */
	private var ultimaPedida = -1 to -1

	private var finTemporizador = 0L
	private var alFinalDelCapitulo = false
	private val temporizador = Runnable { pararPorTemporizador() }
	private var minutosElegidos = 0
	private var paradoPorTemporizador = 0L
	private var parandoPorTemporizador = false
	private val agitar by lazy { Agitar(app) }
	private val dejarDeAgitar = Runnable { agitar.parar() }

	private var inicioSesion = 0L
	private var ultimoGuardado = 0L
	private var bloqueAlEmpezar = 0
	private var bloqueoCpu: PowerManager.WakeLock? = null
	private var controlador: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

	/** Cada segundo mientras lee: guarda la posición y baja el volumen al final del temporizador. */
	private val latido = object : Runnable {
		override fun run() {
			if (!leyendo) return
			guardar()
			aplicarVolumen()
			vigilarMotor()
			principal.postDelayed(this, 1000)
		}
	}

	/** Veces seguidas que el motor no entregó la frase a tiempo. */
	private var atascos = 0

	/**
	 * Si debería sonar y no suena porque el motor no entrega la frase pedida
	 * (le pasa a algunos motores, como MultiTTS, cuando TalkBack habla con el
	 * mismo motor justo al empezar), se vuelve a pedir desde donde va; a la
	 * tercera, se reinicia el motor.
	 */
	private fun vigilarMotor() {
		val j = jugador ?: return
		val primero = cola.firstOrNull()
		if (!debeSonar() || j.isPlaying || primero == null || primero.estado != PEDIDO ||
			SystemClock.elapsedRealtime() - primero.pedido < (if (nube) 45_000 else 4000)) {
			if (j.isPlaying) atascos = 0
			return
		}
		atascos++
		Log.w(ETIQUETA, "El motor no entregó la frase ${primero.id} (atasco $atascos): se vuelve a pedir")
		if (atascos >= 3) {
			atascos = 0
			reiniciarMotor()
		} else hablar()
	}

	fun iniciar(c: Context) {
		if (::app.isInitialized) return
		app = c.applicationContext
	}

	fun registrar(o: Oyente) {
		oyentes.addIfAbsent(o)
		o.alCambiar(estado())
	}

	fun desregistrar(o: Oyente) {
		oyentes.remove(o)
	}

	fun estado(): Estado {
		val rango = frases.getOrNull(frase)
		val minutos = when {
			alFinalDelCapitulo -> -1
			finTemporizador > 0 -> ((finTemporizador - SystemClock.elapsedRealtime()) / 60000 + 1).toInt().coerceAtLeast(1)
			else -> 0
		}
		return Estado(libro, documento != null, leyendo, bloque, rango?.first ?: 0, (rango?.last ?: -1) + 1, minutos, titulo)
	}

	private fun avisar() {
		val e = estado()
		principal.post { oyentes.forEach { runCatching { it.alCambiar(e) } } }
	}

	/** ¿Está leyendo (o en pausa) este libro? */
	fun esActual(idLibro: Long) = documento != null && libro == idLibro

	// --- Turno de audio ---------------------------------------------------------
	//
	// La app lleva el turno de audio ella misma, con reglas fijas (antes lo
	// llevaba el reproductor y, según cómo coincidían TalkBack y la lectura, el
	// libro se quedaba parado o seguía):
	// - El usuario pulsa Reproducir: "leyendo" = true. Pausar: false. Solo el
	//   usuario (o el temporizador, los auriculares desconectados u otra app
	//   de música) cambia "leyendo".
	// - Habla TalkBack (o una notificación, una llamada): el libro baja el
	//   volumen ("atenuado", lo normal), o se calla ("suspendido"), o sigue
	//   igual, según Ajustes. Cuando TalkBack termina, vuelve a sonar como
	//   estaba, solo si "leyendo" sigue siendo true.
	// - Se sabe que habla TalkBack por el turno de audio (TalkBack lo pide al
	//   hablar si tiene activado "atenuar audio") y, además, viendo si suena
	//   algo de accesibilidad en el sistema (por si no lo pide).

	private val audio by lazy { app.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
	private var peticionFoco: AudioFocusRequest? = null
	/** Callado un momento porque habla TalkBack (o hay una llamada). */
	private var suspendido = false
	/** Volumen bajado porque habla TalkBack (opción "bajar"). */
	private var atenuado = false

	/** Otra app tiene el turno un momento (TalkBack, una notificación, una llamada). */
	private var sinFoco = false
	/** Suena algo de accesibilidad (TalkBack hablando o sus sonidos). */
	private var hablaAccesibilidad = false

	private val alCambiarFoco = AudioManager.OnAudioFocusChangeListener { cambio -> principal.post { cambioDeFoco(cambio) } }

	private fun cambioDeFoco(cambio: Int) {
		Log.d(ETIQUETA, "Foco de audio: $cambio (leyendo=$leyendo, suspendido=$suspendido)")
		when (cambio) {
			// Otra app de música o audiolibros: pausa de verdad
			AudioManager.AUDIOFOCUS_LOSS -> pausar()
			AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
				sinFoco = true
				actualizarTalkBack()
			}
			AudioManager.AUDIOFOCUS_GAIN -> {
				sinFoco = false
				actualizarTalkBack()
			}
		}
	}

	/** Vigila si suena audio de accesibilidad (TalkBack) aunque no pida el turno. */
	private val alCambiarReproducciones by lazy {
		if (Build.VERSION.SDK_INT < 26) null
		else object : AudioManager.AudioPlaybackCallback() {
			override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
				val suena = configs.orEmpty().any {
					it.audioAttributes.usage == android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
				}
				if (suena != hablaAccesibilidad) {
					hablaAccesibilidad = suena
					actualizarTalkBack()
				}
			}
		}
	}

	private fun vigilarAccesibilidad(activar: Boolean) {
		if (Build.VERSION.SDK_INT < 26) return
		val cb = alCambiarReproducciones ?: return
		runCatching {
			if (activar) {
				audio.unregisterAudioPlaybackCallback(cb)
				audio.registerAudioPlaybackCallback(cb, principal)
			} else {
				audio.unregisterAudioPlaybackCallback(cb)
				hablaAccesibilidad = false
			}
		}
	}

	/** TalkBack habla o calla: el libro baja el volumen, se calla o sigue igual, según Ajustes. */
	private fun actualizarTalkBack() {
		Log.d(ETIQUETA, "TalkBack: sinFoco=$sinFoco, accesibilidad=$hablaAccesibilidad, leyendo=$leyendo")
		principal.removeCallbacks(volverDeTalkBack)
		if (sinFoco || hablaAccesibilidad) {
			if (!leyendo) return
			when (Preferencias(app).talkbackAlHablar) {
				"seguir" -> Unit
				"pausar" -> if (!suspendido) {
					suspendido = true
					jugador?.pause()
				}
				else -> if (!atenuado) {
					Log.d(ETIQUETA, "TalkBack habla: volumen bajo")
					atenuado = true
					aplicarVolumen()
				}
			}
		} else if (atenuado || suspendido) {
			// Un instante de margen: entre frase y frase de TalkBack no sube y baja el volumen
			principal.postDelayed(volverDeTalkBack, 350)
		}
	}

	private val volverDeTalkBack = Runnable {
		Log.d(ETIQUETA, "TalkBack calla: volumen normal")
		atenuado = false
		aplicarVolumen()
		if (suspendido) {
			suspendido = false
			if (leyendo) jugador?.play()
		}
	}

	/** Pide el turno de audio al empezar a leer. Si lo tiene otra app (una llamada), espera a que lo suelte. */
	private fun pedirFoco() {
		val atributosSistema = android.media.AudioAttributes.Builder()
			.setUsage(android.media.AudioAttributes.USAGE_MEDIA)
			.setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
			.build()
		val r = if (Build.VERSION.SDK_INT >= 26) {
			val peticion = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
				.setAudioAttributes(atributosSistema)
				// Avisos de "bajar" en lugar de que el sistema baje el volumen solo: así se decide aquí
				.setWillPauseWhenDucked(true)
				.setAcceptsDelayedFocusGain(true)
				.setOnAudioFocusChangeListener(alCambiarFoco, principal)
				.build()
			peticionFoco = peticion
			audio.requestAudioFocus(peticion)
		} else {
			@Suppress("DEPRECATION")
			audio.requestAudioFocus(alCambiarFoco, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
		}
		// Retrasado (llamada en curso): suena cuando llegue el turno
		suspendido = r == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
		sinFoco = suspendido
		atenuado = false
		vigilarAccesibilidad(true)
		Log.d(ETIQUETA, "Pedir foco: $r")
	}

	private fun soltarFoco() {
		principal.removeCallbacks(volverDeTalkBack)
		suspendido = false
		atenuado = false
		sinFoco = false
		vigilarAccesibilidad(false)
		if (Build.VERSION.SDK_INT >= 26) peticionFoco?.let { audio.abandonAudioFocusRequest(it) }
		else @Suppress("DEPRECATION") audio.abandonAudioFocus(alCambiarFoco)
	}

	/** Volumen: más bajo si habla TalkBack (opción "bajar") y bajando en los últimos 20 s del temporizador. */
	private fun aplicarVolumen() {
		val j = jugador ?: return
		val queda = if (finTemporizador > 0) finTemporizador - SystemClock.elapsedRealtime() else Long.MAX_VALUE
		val temporizador = if (queda < 20_000) (queda / 20_000f).coerceIn(0.1f, 1f) else 1f
		j.volume = temporizador * (if (atenuado) Preferencias(app).volumenConTalkBack / 100f else 1f)
	}

	// --- Reproductor ---------------------------------------------------------

	/** El reproductor de la lectura (lo usa también la sesión multimedia de ServicioVoz). */
	fun jugador(): ExoPlayer {
		jugador?.let { return it }
		val j = ExoPlayer.Builder(app)
			.setAudioAttributes(
				AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
				false, // el turno de audio lo lleva LectorVoz
			)
			.setHandleAudioBecomingNoisy(true)
			.setWakeMode(C.WAKE_MODE_LOCAL)
			.build()
		j.addListener(object : Player.Listener {
			override fun onMediaItemTransition(elemento: MediaItem?, motivo: Int) {
				principal.post { alCambiarDeFrase() }
			}

			override fun onPlaybackStateChanged(estado: Int) {
				if (estado == Player.STATE_ENDED) principal.post { alAcabarLoGenerado() }
			}

			override fun onPlayWhenReadyChanged(playWhenReady: Boolean, motivo: Int) {
				// Auriculares desconectados: pausa de verdad
				if (!playWhenReady && motivo == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) principal.post { pausar() }
			}
		})
		jugador = j
		return j
	}

	/** Cambió en Ajustes qué hacer cuando habla TalkBack (se aplica la próxima vez que hable). */
	fun aplicarAtributos() {
		atenuado = false
		if (suspendido && !sinFoco) {
			suspendido = false
			if (leyendo) jugador?.play()
		}
		aplicarVolumen()
	}

	/** El reproductor debe sonar: el usuario quiere leer y no está callado por TalkBack. */
	private fun debeSonar() = leyendo && !suspendido

	// --- Motor --------------------------------------------------------------

	private fun carpeta() = File(app.cacheDir, "voz").apply { mkdirs() }

	private fun motor(): TextToSpeech {
		val deseado = perfil.motorEfectivo(Preferencias(app)).ifBlank { null }?.takeUnless { ElevenLabs.esMotor(it) }
		tts?.let { if (motorActual == deseado) return it }
		tts?.shutdown()
		listo = false
		motorActual = deseado
		val nuevo = TextToSpeech(app, { estado ->
			principal.post {
				listo = estado == TextToSpeech.SUCCESS
				if (listo) {
					aplicarAjustes()
					if (pendiente) {
						pendiente = false
						hablar()
					}
				} else {
					leyendo = false
					avisar()
				}
			}
		}, deseado)
		nuevo.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
			override fun onStart(id: String?) {}

			override fun onDone(id: String?) {
				principal.post { generada(id, true) }
			}

			@Deprecated("Deprecated in Java")
			override fun onError(id: String?) {
				principal.post { generada(id, false) }
			}

			override fun onError(id: String?, codigo: Int) {
				principal.post { generada(id, false) }
			}
		})
		tts = nuevo
		return nuevo
	}

	/** Aplica voz, velocidad y tono guardados (o los del libro) para las frases que se generen. */
	fun aplicarAjustes() {
		val t = tts ?: return
		if (!listo) return
		val p = Preferencias(app)
		if (nube) {
			// ElevenLabs: el audio llega a velocidad normal y la velocidad y el tono los pone el reproductor
			t.setSpeechRate(1f)
			t.setPitch(1f)
			jugador().playbackParameters = PlaybackParameters(perfil.velocidadEfectiva(p) / 100f, perfil.tonoEfectivo(p) / 100f)
		} else {
			t.setSpeechRate(perfil.velocidadEfectiva(p) / 100f)
			t.setPitch(perfil.tonoEfectivo(p) / 100f)
			jugador().playbackParameters = PlaybackParameters.DEFAULT
		}
		val nombre = perfil.vozEfectiva(p)
		vozElegida = if (nombre.isNotBlank() && !ElevenLabs.esVozSuya(nombre)) runCatching { t.voices?.firstOrNull { it.name == nombre } }.getOrNull() else null
		if (vozElegida != null) t.voice = vozElegida
		else {
			val idioma = documento?.idioma?.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
			runCatching { t.language = idioma }
		}
		pronunciador = if (p.pronunciacionActiva) Pronunciador(app, libro.takeIf { it > 0 }) else null
	}

	/** Cambió la voz, el tono o la pronunciación en Ajustes: se aplica desde la frase actual. */
	fun ajustesCambiados() {
		aplicarAjustes()
		rehacer = true
		if (leyendo) hablar()
	}

	fun voces(alObtener: (List<Voice>) -> Unit) = conMotor {
		val lista = runCatching { tts?.voices?.toList() }.getOrNull().orEmpty()
			.filter { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
			.sortedWith(compareBy({ it.locale.getDisplayName(Locale.getDefault()) }, { it.name }))
		alObtener(lista)
	}

	/** Los motores instalados y, si hay clave, ElevenLabs. */
	fun motores(alObtener: (List<Pair<String, String>>) -> Unit) = conMotor {
		val nubes = if (ElevenLabs.configurada(app)) listOf(ElevenLabs.MOTOR to app.getString(R.string.el_motor)) else emptyList()
		alObtener(tts?.engines?.map { it.name to it.label }.orEmpty() + nubes)
	}

	private fun conMotor(bloque: () -> Unit) {
		motor()
		if (listo) {
			bloque()
			return
		}
		var intentos = 0
		principal.postDelayed(object : Runnable {
			override fun run() {
				if (listo || intentos++ > 40) bloque() else principal.postDelayed(this, 150)
			}
		}, 150)
	}

	/** Cambió el motor en Ajustes. */
	fun reiniciarMotor() {
		nubeFallida = false
		nubeAvisada = false
		val estaba = leyendo
		tts?.shutdown()
		tts = null
		listo = false
		rehacer = true
		if (estaba) {
			pendiente = true
			motor()
		}
	}

	/** Prueba la voz con una frase (directamente con el motor). */
	fun probar(texto: String) = conMotor {
		aplicarAjustes()
		tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "prueba")
	}

	/** Prueba la voz propia de un libro o de las noticias (con un motor aparte: no toca la lectura). */
	fun probarPerfil(idLibro: Long, texto: String) = VocesMotor.probar(app, PerfilesVoz.efectivo(app, idLibro, idLibro == PerfilesVoz.NOTICIAS), texto)

	// --- Control -------------------------------------------------------------

	/** noticia: es un artículo de Noticias (se lee con la voz de las noticias). */
	fun cargar(doc: Documento, idLibro: Long, tituloLibro: String, desde: Int, caracter: Int = 0, noticia: Boolean = false) {
		if (documento !== doc || libro != idLibro) {
			// Otro libro que no es el siguiente de la lectura seguida: la lista se olvida
			ListaLectura.alCargar(idLibro)
			if (leyendo) pausar()
			// Quien abra la lectura dice cómo volver a ella (si no, se vuelve al libro por su id)
			volver = null
			documento = doc
			libro = idLibro
			titulo = tituloLibro
			esNoticia = noticia
			leerAjustesLibro()
			rehacer = true
			irA(desde, caracter)
			detectarIdiomas()
			return
		}
		irA(desde, caracter)
	}

	/**
	 * Cambió la voz propia de un libro (o la de las noticias, con
	 * PerfilesVoz.NOTICIAS): si es lo que se lee, se aplica desde la frase actual.
	 */
	fun ajustesLibroCambiados(idLibro: Long) {
		if (documento == null) return
		// ¿Afecta a lo que se lee? Su libro, o la sección a la que pertenece
		val afecta = when (idLibro) {
			PerfilesVoz.NOTICIAS -> esNoticia
			PerfilesVoz.BIBLIOTECA, PerfilesVoz.COMPARTIDOS -> !esNoticia && PerfilesVoz.seccionDe(app, libro) == idLibro
			else -> libro == idLibro && !esNoticia
		}
		if (!afecta) return
		val motorAntes = perfil.motorEfectivo(Preferencias(app))
		leerAjustesLibro()
		// Otro motor: se arranca el nuevo y sigue leyendo desde donde iba
		if (perfil.motorEfectivo(Preferencias(app)) != motorAntes) {
			reiniciarMotor()
			detectarIdiomas()
			return
		}
		detectarIdiomas()
		leerAjustesLibro()
		aplicarAjustes()
		rehacer = true
		if (leyendo) hablar()
	}

	// --- Idioma de cada párrafo ---------------------------------------------------------

	/** Idiomas detectados (bloque → idioma) en párrafos que el libro no marca. */
	private val idiomasDetectados = java.util.concurrent.ConcurrentHashMap<Int, String>()
	private var deteccion: kotlinx.coroutines.Job? = null

	/**
	 * Detecta en segundo plano, sin internet (ML Kit, dentro de la app), el
	 * idioma de los párrafos desde el actual, para leer con la voz de ese idioma
	 * los que estén en otro (Ajustes: «Detectar el idioma de cada párrafo»).
	 */
	/** Cambió en Ajustes la detección del idioma: se vuelve a detectar y se aplica desde la frase actual. */
	fun idiomasCambiados() {
		detectarIdiomas()
		ajustesCambiados()
	}

	private fun detectarIdiomas() {
		deteccion?.cancel()
		idiomasDetectados.clear()
		val p = Preferencias(app)
		val doc = documento ?: return
		if (!perfil.segunIdiomaEfectivo(p) || !p.detectarIdioma) return
		val desde = bloque
		val base = doc.idioma.ifBlank { Locale.getDefault().language }.substringBefore('-').lowercase()
		deteccion = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
			val cliente = runCatching {
				com.google.mlkit.nl.languageid.LanguageIdentification.getClient(
					com.google.mlkit.nl.languageid.LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.85f).build()
				)
			}.getOrNull() ?: return@launch
			try {
				for (b in (desde until doc.bloques.size) + (0 until desde)) {
					ensureActive()
					val bl = doc.bloques[b]
					if (bl.idioma != null || bl.texto.length < 60 || bl.tipo == TipoBloque.CODIGO) continue
					val idioma = runCatching { cliente.identifyLanguage(bl.texto.take(600)).await() }.getOrNull() ?: continue
					if (idioma != "und" && idioma.substringBefore('-') != base) idiomasDetectados[b] = idioma
				}
			} finally {
				cliente.close()
			}
		}
	}

	/** La voz de lo que se lee: la del libro sobre la de su sección (Biblioteca o Compartidos), o la de las noticias; lo demás, la general. */
	private fun leerAjustesLibro() {
		perfil = PerfilesVoz.efectivo(app, libro, esNoticia)
	}

	fun leerDesde(doc: Documento, idLibro: Long, tituloLibro: String, desde: Int, caracter: Int = 0, noticia: Boolean = false) {
		cargar(doc, idLibro, tituloLibro, desde, caracter, noticia)
		reanudar()
	}

	fun reanudar() {
		if (documento == null) return
		Log.d(ETIQUETA, "Reanudar")
		// Solo suena una cosa: si sonaba un audiolibro, un episodio o la radio, se pausa
		if (com.xebolax.lector.audio.ControlAudio.reproduciendo) com.xebolax.lector.audio.ControlAudio.pausar()
		com.xebolax.lector.audio.Sonando.ultima = com.xebolax.lector.audio.Sonando.Fuente.VOZ
		if (!leyendo) {
			pedirFoco()
			// Reproducir otra vez: si ElevenLabs había fallado, se vuelve a probar
			if (nubeFallida) {
				nubeFallida = false
				nubeAvisada = false
				rehacer = true
				aplicarAjustes()
			}
		}
		leyendo = true
		inicioSesion = SystemClock.elapsedRealtime()
		bloqueAlEmpezar = bloque
		conectarServicio()
		bloquearCpu(true)
		principal.removeCallbacks(latido)
		principal.post(latido)
		val j = jugador()
		motor()
		when {
			!listo -> pendiente = true
			rehacer || j.mediaItemCount == 0 -> {
				aplicarAjustes()
				hablar()
			}
			else -> {
				if (j.playbackState == Player.STATE_IDLE) j.prepare()
				j.playWhenReady = debeSonar()
			}
		}
		avisar()
	}

	fun pausar() {
		if (!leyendo) {
			principal.removeCallbacks(temporizador)
			return
		}
		if (!parandoPorTemporizador) {
			agitar.parar()
			principal.removeCallbacks(dejarDeAgitar)
		}
		Log.d(ETIQUETA, "Pausar")
		leyendo = false
		jugador?.pause()
		soltarFoco()
		principal.removeCallbacks(temporizador)
		principal.removeCallbacks(latido)
		finTemporizador = 0
		alFinalDelCapitulo = false
		aplicarVolumen()
		guardar(forzar = true)
		bloquearCpu(false)
		avisar()
	}

	fun alternar() = if (leyendo) pausar() else reanudar()

	/** Deja de leer y olvida el documento (cierra la notificación). */
	fun detener() {
		pausar()
		generacion++
		cola.clear()
		jugador?.stop()
		jugador?.clearMediaItems()
		borrarArchivos()
		documento = null
		libro = -1
		frases = emptyList()
		rehacer = true
		avisar()
		desconectarServicio()
	}

	/** Coloca la lectura en un bloque (y carácter) sin cambiar si lee o no. */
	fun irA(nuevoBloque: Int, caracter: Int = 0) {
		val doc = documento ?: return
		bloque = nuevoBloque.coerceIn(0, (doc.bloques.size - 1).coerceAtLeast(0))
		frases = frasesDe(bloque)
		frase = frases.indexOfLast { it.first <= caracter }.coerceAtLeast(0)
		rehacer = true
		if (leyendo) hablar() else avisar()
	}

	fun siguienteFrase() {
		val doc = documento ?: return
		// Si la siguiente ya está generada, se salta a ella sin esperar
		val j = jugador
		if (leyendo && j != null && !rehacer) {
			for (i in j.currentMediaItemIndex + 1 until j.mediaItemCount) {
				val id = j.getMediaItemAt(i).mediaId
				if (id.startsWith("$generacion:")) {
					j.seekTo(i, 0)
					return
				}
			}
		}
		if (frase + 1 < frases.size) frase++
		else {
			val siguiente = siguienteBloqueLegible(bloque + 1)
			if (siguiente >= doc.bloques.size) return
			bloque = siguiente
			frases = frasesDe(bloque)
			frase = 0
		}
		rehacer = true
		if (leyendo) hablar() else avisar()
	}

	fun anteriorFrase() {
		// Si lleva más de 2 segundos en la frase, vuelve a su principio
		val j = jugador
		if (leyendo && j != null && !rehacer && j.currentPosition > 2000) {
			j.seekTo(0)
			return
		}
		if (frase > 0) frase--
		else {
			var b = bloque - 1
			while (b >= 0 && frasesDe(b).isEmpty()) b--
			if (b < 0) return
			bloque = b
			frases = frasesDe(b)
			frase = (frases.size - 1).coerceAtLeast(0)
		}
		rehacer = true
		if (leyendo) hablar() else avisar()
	}

	fun siguienteParrafo() {
		val doc = documento ?: return
		val s = siguienteBloqueLegible(bloque + 1)
		if (s < doc.bloques.size) irA(s)
	}

	fun anteriorParrafo() {
		if (frase > 0) irA(bloque) else if (bloque > 0) irA(bloque - 1)
	}

	/** Cambia la velocidad (en %); se aplica desde la frase actual. */
	fun cambiarVelocidad(porcentaje: Int) {
		val v = porcentaje.coerceIn(10, 600)
		// Se cambia donde está puesta: la del libro, la de su sección o, si no, la general
		val propio = if (esNoticia) null else libro.takeIf { it > 0 }
		val seccion = if (esNoticia) PerfilesVoz.NOTICIAS else PerfilesVoz.seccionDe(app, libro)
		when {
			propio != null && PerfilesVoz.de(app, propio).velocidad != null -> PerfilesVoz.guardar(app, propio, PerfilesVoz.de(app, propio).copy(velocidad = v))
			PerfilesVoz.de(app, seccion).velocidad != null -> PerfilesVoz.guardar(app, seccion, PerfilesVoz.de(app, seccion).copy(velocidad = v))
			else -> Preferencias(app).p.edit().putInt("velocidad_voz", v).apply()
		}
		perfil = perfil.copy(velocidad = if (perfil.velocidad != null) v else null)
		// ElevenLabs: la velocidad la pone el reproductor, al momento y sin volver a pedir el audio
		if (nube) {
			jugador?.playbackParameters = PlaybackParameters(v / 100f, perfil.tonoEfectivo(Preferencias(app)) / 100f)
			avisar()
			return
		}
		tts?.setSpeechRate(v / 100f)
		rehacer = true
		if (leyendo) hablar()
	}

	fun velocidadActual(): Int = perfil.velocidadEfectiva(Preferencias(app))

	// --- Temporizador ---------------------------------------------------------

	/** minutos > 0: parar dentro de esos minutos; -1: al final del capítulo; 0: quitar. */
	fun temporizador(minutos: Int) {
		principal.removeCallbacks(temporizador)
		principal.removeCallbacks(dejarDeAgitar)
		minutosElegidos = minutos
		paradoPorTemporizador = 0
		if (minutos != 0) agitar.empezar { alAgitar() } else agitar.parar()
		finTemporizador = 0
		alFinalDelCapitulo = false
		aplicarVolumen()
		when {
			minutos > 0 -> {
				finTemporizador = SystemClock.elapsedRealtime() + minutos * 60_000L
				principal.postDelayed(temporizador, minutos * 60_000L)
			}
			minutos == -1 -> alFinalDelCapitulo = true
		}
		avisar()
	}

	/** El temporizador ha terminado: pausa, pero agitar en los 3 minutos siguientes vuelve a leer. */
	private fun pararPorTemporizador() {
		parandoPorTemporizador = true
		pausar()
		parandoPorTemporizador = false
		paradoPorTemporizador = SystemClock.elapsedRealtime()
		principal.removeCallbacks(dejarDeAgitar)
		principal.postDelayed(dejarDeAgitar, 3 * 60_000L)
	}

	private fun alAgitar() {
		val minutos = minutosElegidos
		when {
			leyendo && minutos != 0 -> temporizador(minutos)
			!leyendo && paradoPorTemporizador > 0 && SystemClock.elapsedRealtime() - paradoPorTemporizador < 3 * 60_000L -> {
				reanudar()
				temporizador(minutos)
			}
		}
	}

	// --- Generación y reproducción de las frases -------------------------------------

	private fun frasesDe(b: Int): List<IntRange> {
		val doc = documento ?: return emptyList()
		val bloqueDoc = doc.bloques.getOrNull(b) ?: return emptyList()
		if (bloqueDoc.tipo == TipoBloque.SEPARADOR) return emptyList()
		if (bloqueDoc.tipo == TipoBloque.NOTA && Preferencias(app).saltarNotas) return emptyList()
		val frases = Segmentador.frases(bloqueDoc.texto)
		if (!nube) return frases
		// ElevenLabs: el párrafo entero (o trozos de frases completas) en cada petición: menos peticiones y mejor entonación
		val trozos = mutableListOf<IntRange>()
		for (r in frases) {
			val ultimo = trozos.lastOrNull()
			if (ultimo != null && r.last - ultimo.first < ElevenLabs.TROZO) trozos[trozos.size - 1] = ultimo.first..r.last
			else trozos += r
		}
		return trozos
	}

	private fun siguienteBloqueLegible(desde: Int): Int {
		val doc = documento ?: return desde
		var b = desde
		while (b < doc.bloques.size && frasesDe(b).isEmpty()) b++
		return b
	}

	/** Empieza a generar y reproducir desde la frase actual (borra lo generado antes). */
	private fun hablar() {
		val t = tts
		if (t == null || !listo) {
			pendiente = true
			motor()
			return
		}
		generacion++
		cola.clear()
		val j = jugador()
		j.stop()
		j.clearMediaItems()
		borrarArchivos()
		// Los trozos dependen del motor (frases con el del móvil, párrafos con ElevenLabs): se vuelven a calcular
		if (frases.isNotEmpty()) {
			val inicio = frases.getOrNull(frase)?.first ?: 0
			frases = frasesDe(bloque)
			frase = frases.indexOfLast { it.first <= inicio }.coerceAtLeast(0)
		}
		if (frases.isEmpty()) {
			val s = siguienteBloqueLegible(bloque)
			if (s >= (documento?.bloques?.size ?: 0)) {
				terminarLibro()
				return
			}
			bloque = s
			frases = frasesDe(s)
			frase = 0
		}
		ultimaPedida = bloque to (frase - 1)
		rehacer = false
		j.playWhenReady = debeSonar()
		pedir(t, POR_DELANTE + 1, primeraTanda = true)
		avisar()
	}

	private val URLS = Regex("""(?i)\b(?:https?://|www\.)\S+""")
	private val PARENTESIS = Regex("""\s*\([^()]{0,300}\)""")
	private val CORCHETES = Regex("""\s*\[[^\[\]]{0,300}]""")
	private val NOTAS = Regex("""\[\d{1,4}\]|\[[*†‡]\]""")

	/** Pide al motor que genere las 'cuantas' frases siguientes a la última pedida. */
	private fun pedir(t: TextToSpeech, cuantas: Int, primeraTanda: Boolean = false) {
		val doc = documento ?: return
		val p = Preferencias(app)
		var (b, f) = ultimaPedida
		var pedidas = 0
		var primera = primeraTanda
		while (pedidas < cuantas) {
			var lista = if (b == bloque) frases else frasesDe(b)
			f++
			var nuevoBloque = false
			if (f >= lista.size) {
				b = siguienteBloqueLegible(b + 1)
				if (b >= doc.bloques.size) return
				lista = frasesDe(b)
				f = 0
				nuevoBloque = true
			}
			val bloqueDoc = doc.bloques[b]
			val rango = lista[f]
			var texto = bloqueDoc.texto.substring(rango.first, rango.last + 1)
			if (p.saltarNotas) texto = NOTAS.replace(texto, "")
			if (p.saltarUrls) texto = URLS.replace(texto, "")
			if (p.saltarParentesis) texto = PARENTESIS.replace(texto, "")
			if (p.saltarCorchetes) texto = CORCHETES.replace(texto, "")
			pronunciador?.let { if (!it.vacio) texto = it.aplicar(texto) }
			if (f == 0 && p.leerPaginas && bloqueDoc.tiene(Marca.PAGINA)) {
				doc.paginaDe(b)?.let { pagina -> texto = app.getString(R.string.pagina_n, pagina.etiqueta) + ". " + texto }
			}
			val segmento = Segmento(generacion, b, f, File(carpeta(), "${generacion}_${b}_$f.wav"), nuevoBloque && !primera)
			primera = false
			cola.addLast(segmento)
			// Otro idioma en el párrafo: voz de ese idioma; encabezados algo más agudos
			val idioma = bloqueDoc.idioma ?: idiomasDetectados[b]
			if (nube && texto.isNotBlank()) {
				val gen = generacion
				val id = segmento.id
				val anterior = lista.getOrNull(f - 1)?.let { bloqueDoc.texto.substring(it.first, it.last + 1) }.orEmpty()
				val siguiente = lista.getOrNull(f + 1)?.let { bloqueDoc.texto.substring(it.first, it.last + 1) }.orEmpty()
				val dicho = texto
				ElevenLabs.sintetizar(app, texto, perfil.vozEfectiva(p), idioma ?: doc.idioma, anterior, siguiente, segmento.archivo,
					{ gen == generacion && !nubeFallida }) { e -> principal.post { if (e == null) generada(id, true) else falloNube(id, e, dicho) } }
				ultimaPedida = b to f
				pedidas++
				continue
			}
			val cambiaIdioma = idioma != null && perfil.segunIdiomaEfectivo(p) && vozElegida?.locale?.language != Locale.forLanguageTag(idioma).language
			if (cambiaIdioma) runCatching { t.language = Locale.forLanguageTag(idioma!!) }
			val destacar = bloqueDoc.tipo == TipoBloque.ENCABEZADO && p.destacarEncabezados
			val tono = perfil.tonoEfectivo(p) / 100f
			if (destacar) t.setPitch(tono * 1.12f)
			val r = if (texto.isBlank()) TextToSpeech.ERROR else t.synthesizeToFile(texto, Bundle(), segmento.archivo, segmento.id)
			if (r != TextToSpeech.SUCCESS) segmento.estado = FALLIDO
			if (destacar) t.setPitch(tono)
			if (cambiaIdioma) {
				vozElegida?.let { v -> runCatching { t.voice = v } } ?: runCatching {
					t.language = doc.idioma.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
				}
			}
			ultimaPedida = b to f
			pedidas++
		}
		alimentar()
	}

	/**
	 * ElevenLabs no ha podido generar un trozo: se dice por qué (una vez) y se
	 * sigue con la voz del móvil, o se para si así se eligió en Ajustes.
	 */
	private fun falloNube(id: String, e: Throwable, texto: String) {
		val s = cola.firstOrNull { it.id == id } ?: return
		Log.w(ETIQUETA, "ElevenLabs: ${e.message}")
		val yaFallida = nubeFallida
		nubeFallida = true
		val t = tts
		val seguir = ElevenLabs.seguirConMovil(app) && t != null && listo
		if (!yaFallida && !nubeAvisada) {
			nubeAvisada = true
			val aviso = ElevenLabs.mensaje(app, e) + " " + app.getString(if (seguir) R.string.el_sigo_con_movil else R.string.el_lectura_parada)
			t?.speak(aviso, TextToSpeech.QUEUE_ADD, null, "aviso_elevenlabs")
			ultimoAvisoNube = aviso
		}
		if (!seguir) {
			s.estado = FALLIDO
			pausar()
			return
		}
		// Este trozo y los siguientes, con la voz del móvil
		if (!yaFallida) aplicarAjustes()
		val r = t!!.synthesizeToFile(texto, Bundle(), s.archivo, s.id)
		if (r != TextToSpeech.SUCCESS) {
			s.estado = FALLIDO
			alimentar()
		}
	}

	/** Lo último que dijo la lectura sobre ElevenLabs (se ve en Ajustes). */
	var ultimoAvisoNube = ""
		private set

	/** El motor terminó de generar una frase. */
	private fun generada(id: String?, ok: Boolean) {
		val s = cola.firstOrNull { it.id == id } ?: return
		s.estado = if (ok && s.archivo.length() > 44) LISTO else FALLIDO
		alimentar()
	}

	/** Pasa al reproductor, en orden, las frases ya generadas. */
	private fun alimentar() {
		val j = jugador()
		val antes = j.mediaItemCount
		val p = Preferencias(app)
		val pausa = perfil.pausaEfectiva(p)
		val pausaFrase = p.pausaFrases
		while (cola.isNotEmpty() && cola.first().estado != PEDIDO) {
			val s = cola.removeFirst()
			if (s.gen != generacion || s.estado != LISTO) continue
			if (s.nuevoBloque && pausa > 0) j.addMediaSource(SilenceMediaSource(pausa * 1000L))
			else if (!s.nuevoBloque && pausaFrase > 0 && j.mediaItemCount > 0) j.addMediaSource(SilenceMediaSource(pausaFrase * 1000L))
			j.addMediaItem(
				MediaItem.Builder()
					.setUri(Uri.fromFile(s.archivo))
					.setMediaId(s.id)
					.setMediaMetadata(
						MediaMetadata.Builder()
							.setTitle(titulo)
							.setArtist(documento?.capituloDe(s.b)?.titulo.orEmpty())
							.build()
					)
					.build()
			)
		}
		if (j.mediaItemCount == antes) {
			// Nada nuevo y el reproductor ya acabó lo que tenía: quizá es el final del libro
			if (cola.isEmpty() && j.playbackState == Player.STATE_ENDED) alAcabarLoGenerado()
			return
		}
		when (j.playbackState) {
			Player.STATE_IDLE -> j.prepare()
			// Había acabado lo generado (el motor iba más lento): sigue con lo nuevo
			Player.STATE_ENDED -> j.seekTo(antes, 0)
		}
		if (debeSonar()) j.playWhenReady = true
	}

	/** Empieza a sonar otra frase: se marca, se borran las ya leídas y se piden más. */
	private fun alCambiarDeFrase() {
		val j = jugador ?: return
		val id = j.currentMediaItem?.mediaId ?: return
		val partes = id.split(':')
		if (partes.size != 3 || partes[0].toIntOrNull() != generacion) return
		val b = partes[1].toIntOrNull() ?: return
		val f = partes[2].toIntOrNull() ?: return
		val doc = documento ?: return
		if (b != bloque) {
			// Fin de capítulo con el temporizador "al final del capítulo"
			if (alFinalDelCapitulo && (doc.bloques[b].tiene(Marca.INICIO_SECCION) || doc.indice.any { it.bloque == b })) {
				pararPorTemporizador()
				return
			}
			bloque = b
			frases = frasesDe(b)
		}
		frase = f
		// Lo ya leído sale del reproductor (y sus archivos)
		val actual = j.currentMediaItemIndex
		if (actual > 0) {
			for (i in 0 until actual) borrarArchivo(j.getMediaItemAt(i).mediaId)
			j.removeMediaItems(0, actual)
		}
		val porDelante = (j.mediaItemCount - 1 - j.currentMediaItemIndex) + cola.size
		tts?.let { t -> if (listo && porDelante < POR_DELANTE) pedir(t, POR_DELANTE - porDelante) }
		guardar()
		avisar()
	}

	/** El reproductor terminó todo lo generado. Si no queda nada por generar, es el final del libro. */
	private fun alAcabarLoGenerado() {
		if (!leyendo) return
		if (cola.isNotEmpty()) return
		val doc = documento ?: return
		val (b, f) = ultimaPedida
		val ultimaDelLibro = siguienteBloqueLegible(b + 1) >= doc.bloques.size && f >= frasesDe(b).size - 1
		if (ultimaDelLibro) terminarLibro()
		else tts?.let { t -> if (listo) pedir(t, POR_DELANTE) }
	}

	private fun terminarLibro() {
		pausar()
		rehacer = true
		val doc = documento ?: return
		BaseDatos.obtener(app).guardarPosicion(libro, Posicion.texto(bloque), 100.0)
		avisar()
		// «Fin del artículo» en las noticias, «Fin del documento» en lo que no es de la biblioteca
		val fin = when {
			esNoticia -> R.string.fin_articulo
			libro <= 0 -> R.string.fin_documento
			else -> R.string.fin_del_libro
		}
		// Lectura seguida: «Siguiente: título» y empieza el siguiente
		val siguiente = ListaLectura.alTerminar(app, libro)
		val texto = if (siguiente != null) app.getString(fin) + " " + app.getString(R.string.seguido_siguiente, siguiente) else app.getString(fin)
		if (doc.bloques.isNotEmpty() || siguiente != null) tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "fin")
	}

	private fun borrarArchivo(id: String) {
		val partes = id.split(':')
		if (partes.size == 3) File(carpeta(), "${partes[0]}_${partes[1]}_${partes[2]}.wav").delete()
	}

	private fun borrarArchivos() {
		carpeta().listFiles()?.forEach { it.delete() }
	}

	/** Guarda posición y tiempo leído (como mucho cada 10 segundos salvo si se fuerza). */
	private fun guardar(forzar: Boolean = false) {
		val doc = documento ?: return
		if (libro <= 0) return
		val ahora = SystemClock.elapsedRealtime()
		if (!forzar && ahora - ultimoGuardado < 10_000) return
		ultimoGuardado = ahora
		val caracter = frases.getOrNull(frase)?.first ?: 0
		val db = BaseDatos.obtener(app)
		db.guardarPosicion(libro, Posicion.texto(bloque, caracter), doc.porcentaje(bloque, caracter))
		if (inicioSesion > 0) {
			val segundos = (ahora - inicioSesion) / 1000
			val avance = doc.inicioBloque[bloque.coerceIn(0, doc.bloques.size)] - doc.inicioBloque[bloqueAlEmpezar.coerceIn(0, doc.bloques.size)]
			db.sumarLectura(libro, segundos, avance)
			inicioSesion = if (leyendo) ahora else 0L
			bloqueAlEmpezar = bloque
		}
	}

	// --- Sistema -------------------------------------------------------------------

	/** La CPU sigue despierta mientras lee (el motor genera las frases aunque la pantalla esté apagada). */
	private fun bloquearCpu(activar: Boolean) {
		if (activar) {
			if (bloqueoCpu == null) {
				bloqueoCpu = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
					.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LectorUniversal:voz")
			}
			if (bloqueoCpu?.isHeld == false) bloqueoCpu?.acquire(4 * 60 * 60 * 1000L)
		} else if (bloqueoCpu?.isHeld == true) bloqueoCpu?.release()
	}

	/** Conecta con ServicioVoz para que la lectura siga en segundo plano con su notificación. */
	private fun conectarServicio() {
		if (controlador != null) return
		val token = SessionToken(app, ComponentName(app, ServicioVoz::class.java))
		controlador = MediaController.Builder(app, token).buildAsync()
	}

	private fun desconectarServicio() {
		controlador?.let { MediaController.releaseFuture(it) }
		controlador = null
	}
}
