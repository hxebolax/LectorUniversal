package com.xebolax.lector.voz

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.xebolax.lector.R
import com.xebolax.lector.lectura.LectorActivity

/**
 * Lectura en voz alta en segundo plano: notificación con controles,
 * pantalla de bloqueo y botones de auriculares o Bluetooth. Usa el mismo
 * reproductor que LectorVoz; reproducir y pausar pasan por LectorVoz (así
 * el estado es siempre el mismo en la app y en la notificación), y
 * siguiente/anterior pasan de frase. Los relojes (Wear OS) muestran los
 * controles solos.
 */
@UnstableApi
class ServicioVoz : MediaSessionService() {

	private var sesion: MediaSession? = null

	override fun onCreate() {
		super.onCreate()
		LectorVoz.iniciar(this)
		val envoltorio = object : ForwardingPlayer(LectorVoz.jugador()) {
			override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
				.addAll(
					Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP,
					Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
					Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
				).build()

			override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

			// La notificación dice lo mismo que la app: "leyendo" aunque calle un momento mientras habla TalkBack
			override fun getPlayWhenReady(): Boolean = LectorVoz.leyendo
			override fun isPlaying(): Boolean = LectorVoz.leyendo && playbackState == Player.STATE_READY

			override fun play() = LectorVoz.reanudar()
			override fun pause() = LectorVoz.pausar()
			override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) LectorVoz.reanudar() else LectorVoz.pausar()
			override fun stop() = LectorVoz.detener()
			override fun seekToNext() = LectorVoz.siguienteFrase()
			override fun seekToNextMediaItem() = LectorVoz.siguienteFrase()
			override fun seekToPrevious() = LectorVoz.anteriorFrase()
			override fun seekToPreviousMediaItem() = LectorVoz.anteriorFrase()

			// La lista de frases la lleva LectorVoz: otros controles no pueden cambiarla
			override fun setMediaItems(mediaItems: MutableList<MediaItem>) = Unit
			override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) = Unit
			override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) = Unit
			override fun setMediaItem(mediaItem: MediaItem) = Unit
			override fun addMediaItems(mediaItems: MutableList<MediaItem>) = Unit
			override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) = Unit
			override fun prepare() = Unit
		}
		val abrir = PendingIntent.getActivity(
			this, 0,
			Intent(this, LectorActivity::class.java).putExtra(LectorActivity.EXTRA_LIBRO, LectorVoz.libro)
				.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		sesion = MediaSession.Builder(this, envoltorio).setId("voz").setSessionActivity(abrir).build()
		setMediaNotificationProvider(
			DefaultMediaNotificationProvider.Builder(this)
				.setChannelId("lectura_voz")
				.setChannelName(R.string.canal_voz)
				.build()
		)
	}

	override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = sesion

	override fun onTaskRemoved(rootIntent: Intent?) {
		// Si se cierra la app desde Recientes sin estar leyendo, se cierra también el servicio
		if (!LectorVoz.leyendo) stopSelf()
	}

	override fun onDestroy() {
		// El reproductor es de LectorVoz: solo se suelta la sesión
		sesion?.release()
		sesion = null
		super.onDestroy()
	}
}
