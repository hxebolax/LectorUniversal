package com.xebolax.lector.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.xebolax.lector.R
import com.xebolax.lector.datos.Preferencias

/**
 * Reproducción de audiolibros y DAISY en segundo plano: notificación con
 * controles, pantalla de bloqueo, auriculares y Bluetooth. "Siguiente" y
 * "Anterior" (también los de los auriculares) pasan de capítulo o saltan
 * unos segundos, según Ajustes.
 */
@UnstableApi
class ServicioAudio : MediaSessionService() {

	private var sesion: MediaSession? = null

	private val dispositivos = object : android.media.AudioDeviceCallback() {
		override fun onAudioDevicesAdded(nuevos: Array<out android.media.AudioDeviceInfo>) {
			val salida = nuevos.any { d ->
				d.isSink && d.type in setOf(
					android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
					android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
				)
			}
			if (salida) ControlAudio.alConectarAuriculares()
		}
	}

	override fun onCreate() {
		super.onCreate()
		ControlAudio.iniciar(this)
		val p = Preferencias(this)
		// Decodificadores del móvil y, de reserva, los de FFmpeg (MP2 de algunas radios HLS, AC-3…)
		// Sonido en mono (Ajustes): una mezcla propia antes de la salida de audio
		MezclaMono.activo = p.p.getBoolean("audio_mono", false)
		val decodificadores = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
			override fun buildAudioSink(contexto: android.content.Context, flotante: Boolean, parametros: Boolean): androidx.media3.exoplayer.audio.AudioSink =
				androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(contexto)
					.setEnableFloatOutput(flotante)
					.setEnableAudioTrackPlaybackParams(parametros)
					.setAudioProcessors(arrayOf(MezclaMono))
					.build()
		}.setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
		val exo = ExoPlayer.Builder(this, decodificadores)
			// Internet con agente propio, redirecciones http↔https y reserva a http (FuenteRed)
			// Grabaciones de radio y audios sin índice (MP3 sin cabecera Xing, AAC en ADTS): se salta
			// calculando por la tasa constante; sin esto, adelantar o retroceder volvía al principio
			.setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(FuenteRed.fabrica(this),
				androidx.media3.extractor.DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)))
			// Con TalkBack hablando: pausa y sigue después (voz) o baja el volumen (música), según Ajustes
			.setAudioAttributes(ControlAudio.atributos(this), ControlAudio.manejaFoco(this))
			.setHandleAudioBecomingNoisy(true)
			// Radio y episodios por internet: también el Wi-Fi despierto con la pantalla apagada (si no, cortes)
			.setWakeMode(C.WAKE_MODE_NETWORK)
			.setSeekBackIncrementMs(p.saltoAtras * 1000L)
			.setSeekForwardIncrementMs(p.saltoAdelante * 1000L)
			.build()
		exo.addListener(object : Player.Listener {
			override fun onEvents(player: Player, events: Player.Events) {
				ControlAudio.avisar()
			}

			override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
				// Auriculares desconectados: se apunta para seguir al volver a conectarlos
				if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) ControlAudio.alDesconectarAuriculares()
			}

			override fun onAudioSessionIdChanged(audioSessionId: Int) {
				ControlAudio.aplicarRefuerzo()
			}
		})
		ControlAudio.jugador = exo
		// Auriculares o altavoz conectados (sin permisos: lo dice el sistema de audio)
		(getSystemService(AUDIO_SERVICE) as android.media.AudioManager).registerAudioDeviceCallback(dispositivos, null)

		// Siguiente/anterior: capítulo o salto de segundos
		val envoltorio = object : ForwardingPlayer(exo) {
			override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
				.addAll(
					Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
					Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
					Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
				).build()

			override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

			private fun mover(delante: Boolean) {
				// Radio: emisora siguiente o anterior
				ControlAudio.externo?.alPasar?.let { return it(delante) }
				val pr = Preferencias(this@ServicioAudio)
				// Sin capítulos (un episodio o un audio de una sola pista) también salta unos segundos
				val sinCapitulos = ControlAudio.marcas(UnidadAudio.CAPITULO).size <= 1
				if (pr.botonesAuriculares == "salto" || sinCapitulos) ControlAudio.saltarSegundos(if (delante) pr.saltoAdelante else -pr.saltoAtras)
				else ControlAudio.saltar(UnidadAudio.CAPITULO, delante)
			}

			// Retroceder y avanzar (notificación, reloj): con los segundos de Ajustes de ahora, no los de cuando se creó el servicio
			override fun seekBack() = ControlAudio.saltarSegundos(-Preferencias(this@ServicioAudio).saltoAtras)
			override fun seekForward() = ControlAudio.saltarSegundos(Preferencias(this@ServicioAudio).saltoAdelante)
			override fun getSeekBackIncrement(): Long = Preferencias(this@ServicioAudio).saltoAtras * 1000L
			override fun getSeekForwardIncrement(): Long = Preferencias(this@ServicioAudio).saltoAdelante * 1000L

			override fun seekToNext() = mover(true)
			override fun seekToPrevious() = mover(false)
			override fun seekToNextMediaItem() = mover(true)
			override fun seekToPreviousMediaItem() = mover(false)
			override fun play() = ControlAudio.reproducir()
			override fun pause() = ControlAudio.pausar()
		}
		sesion = MediaSession.Builder(this, envoltorio).setId("audio").also { s -> abrir()?.let { s.setSessionActivity(it) } }.build()
		ControlAudio.registrar(alCambiar)
		setMediaNotificationProvider(
			DefaultMediaNotificationProvider.Builder(this)
				.setChannelId("audiolibros")
				.setChannelName(R.string.canal_audio)
				.build()
		)
	}

	/** Tocar la notificación o la pantalla de bloqueo abre lo que suena ahora (libro, episodio o radio). */
	private fun abrir(): PendingIntent? {
		val i = Sonando.pantalla(this, Sonando.Fuente.AUDIO) ?: return null
		return PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
	}

	private var ultimoAbrir = ""
	private val alCambiar = ControlAudio.Oyente {
		val clave = "${ControlAudio.idLibro}:${Sonando.esRadio()}"
		if (clave != ultimoAbrir) {
			ultimoAbrir = clave
			abrir()?.let { sesion?.setSessionActivity(it) }
		}
	}

	override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = sesion

	override fun onTaskRemoved(rootIntent: Intent?) {
		val j = sesion?.player
		if (j == null || !j.playWhenReady || j.mediaItemCount == 0) stopSelf()
	}

	override fun onDestroy() {
		ControlAudio.desregistrar(alCambiar)
		ControlAudio.guardar(forzar = true)
		sesion?.run {
			player.release()
			release()
		}
		sesion = null
		// El refuerzo de volumen va con la sesión de audio del reproductor que se cierra
		ControlAudio.soltarRefuerzo()
		ControlAudio.jugador = null
		(getSystemService(AUDIO_SERVICE) as android.media.AudioManager).unregisterAudioDeviceCallback(dispositivos)
		super.onDestroy()
	}
}
