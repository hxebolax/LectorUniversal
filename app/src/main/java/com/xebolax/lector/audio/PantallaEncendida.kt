package com.xebolax.lector.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.xebolax.lector.voz.LectorVoz

/**
 * Pausar al encender la pantalla o al desbloquear el móvil, como hace @Voice
 * (a gusto de cada uno, en Ajustes): la lectura en voz alta (`voz_al_encender`,
 * en Lectura en voz alta) y los audiolibros y podcasts (`audio_al_encender`, en
 * Audiolibros y DAISY y en Podcasts). La radio no se pausa: es un directo.
 *
 * Android solo avisa de que se enciende la pantalla a los receptores
 * registrados en marcha, así que se registra al arrancar la app (mientras algo
 * suena, el servicio en primer plano la mantiene viva).
 */
object PantallaEncendida {

	private var registrado = false

	private val receptor = object : BroadcastReceiver() {
		override fun onReceive(c: Context, intent: Intent) {
			val momento = when (intent.action) {
				Intent.ACTION_SCREEN_ON -> "encender"
				Intent.ACTION_USER_PRESENT -> "desbloquear"
				else -> return
			}
			val prefs = PreferenceManager.getDefaultSharedPreferences(c)
			if (prefs.getString("voz_al_encender", "seguir") == momento && LectorVoz.leyendo) LectorVoz.pausar()
			if (prefs.getString("audio_al_encender", "seguir") == momento && ControlAudio.reproduciendo && ControlAudio.externo?.directo != true) ControlAudio.pausar()
		}
	}

	fun iniciar(c: Context) {
		if (registrado) return
		registrado = true
		val filtro = IntentFilter().apply {
			addAction(Intent.ACTION_SCREEN_ON)
			addAction(Intent.ACTION_USER_PRESENT)
		}
		// Avisos del sistema: llegan aunque el receptor no esté exportado
		ContextCompat.registerReceiver(c.applicationContext, receptor, filtro, ContextCompat.RECEIVER_NOT_EXPORTED)
	}
}
