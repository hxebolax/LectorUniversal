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
import com.xebolax.lector.lectura.LectorActivity

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
		val exo = ExoPlayer.Builder(this)
			// Con TalkBack hablando: pausa y sigue después (voz) o baja el volumen (música), según Ajustes
			.setAudioAttributes(ControlAudio.atributos(this), ControlAudio.manejaFoco(this))
			.setHandleAudioBecomingNoisy(true)
			.setWakeMode(C.WAKE_MODE_LOCAL)
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
				val pr = Preferencias(this@ServicioAudio)
				if (pr.botonesAuriculares == "salto") ControlAudio.saltarSegundos(if (delante) pr.saltoAdelante else -pr.saltoAtras)
				else ControlAudio.saltar(UnidadAudio.CAPITULO, delante)
			}

			override fun seekToNext() = mover(true)
			override fun seekToPrevious() = mover(false)
			override fun seekToNextMediaItem() = mover(true)
			override fun seekToPreviousMediaItem() = mover(false)
			override fun play() = ControlAudio.reproducir()
			override fun pause() = ControlAudio.pausar()
		}
		val abrir = PendingIntent.getActivity(
			this, 1,
			Intent(this, ReproductorActivity::class.java).putExtra(LectorActivity.EXTRA_LIBRO, ControlAudio.idLibro)
				.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		sesion = MediaSession.Builder(this, envoltorio).setId("audio").setSessionActivity(abrir).build()
		setMediaNotificationProvider(
			DefaultMediaNotificationProvider.Builder(this)
				.setChannelId("audiolibros")
				.setChannelName(R.string.canal_audio)
				.build()
		)
	}

	override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = sesion

	override fun onTaskRemoved(rootIntent: Intent?) {
		val j = sesion?.player
		if (j == null || !j.playWhenReady || j.mediaItemCount == 0) stopSelf()
	}

	override fun onDestroy() {
		ControlAudio.guardar(forzar = true)
		sesion?.run {
			player.release()
			release()
		}
		sesion = null
		ControlAudio.jugador = null
		(getSystemService(AUDIO_SERVICE) as android.media.AudioManager).unregisterAudioDeviceCallback(dispositivos)
		super.onDestroy()
	}
}
