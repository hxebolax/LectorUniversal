package com.xebolax.lector

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.xebolax.lector.audio.ControlAudio
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.voz.LectorVoz
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LectorApp : Application() {

	companion object {
		/** Tareas de fondo que no dependen de ninguna pantalla (importar, leer datos de libros). */
		val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)

		fun aplicarTema(tema: String) {
			AppCompatDelegate.setDefaultNightMode(
				when (tema) {
					"claro" -> AppCompatDelegate.MODE_NIGHT_NO
					"oscuro" -> AppCompatDelegate.MODE_NIGHT_YES
					else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
				}
			)
		}
	}

	override fun onCreate() {
		super.onCreate()
		// Al cerrar un diálogo o volver a una pantalla, el foco de TalkBack vuelve a donde estaba
		registerActivityLifecycleCallbacks(com.xebolax.lector.ui.MemoriaFoco)
		Preferencias(this).migrar()
		// Claves y contraseñas, aparte de los ajustes (copias de seguridad)
		com.xebolax.lector.datos.Secretos.migrar(this)
		aplicarTema(Preferencias(this).temaApp)
		LectorVoz.iniciar(this)
		ControlAudio.iniciar(this)
		// Estadísticas: tiempo escuchando podcasts, noticias y radio
		com.xebolax.lector.datos.Actividad.empezar(this)
		// El widget dice el libro y si está leyendo
		var ultimo: Pair<Long, Boolean>? = null
		LectorVoz.registrar { e ->
			val ahora = e.libro to e.leyendo
			if (ahora != ultimo) {
				ultimo = ahora
				com.xebolax.lector.widget.WidgetLectura.actualizar(this)
			}
		}
	}
}
