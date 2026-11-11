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
		/** La app (para textos en su idioma desde código sin pantalla, como los errores de los catálogos). */
		lateinit var instancia: LectorApp
			private set

		fun texto(recurso: Int, vararg args: Any): String = instancia.getString(recurso, *args)

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
		instancia = this
		// Accesos directos del icono (mantener pulsado): Leer lo copiado, Leer lo pendiente, Guardar una página web
		com.xebolax.lector.compartidos.Compartidos.ponerAtajos(this)
		// Si la app se cierra de golpe, el error se guarda solo en el móvil (nunca se envía solo)
		com.xebolax.lector.datos.RegistroFallos.iniciar(this)
		// Al cerrar un diálogo o volver a una pantalla, el foco de TalkBack vuelve a donde estaba
		registerActivityLifecycleCallbacks(com.xebolax.lector.ui.MemoriaFoco)
		Preferencias(this).migrar()
		// Claves y contraseñas, aparte de los ajustes (copias de seguridad)
		com.xebolax.lector.datos.Secretos.migrar(this)
		aplicarTema(Preferencias(this).temaApp)
		// Qué son los PDF, Word y textos que no dicen nada (Ajustes, Biblioteca)
		com.xebolax.lector.datos.TipoContenido.ambiguosComo = com.xebolax.lector.datos.TipoContenido.de(Preferencias(this).p.getString("tipo_ambiguos", "LIBRO")) ?: com.xebolax.lector.datos.TipoContenido.LIBRO
		com.xebolax.lector.datos.Pais.iniciar(this)
		LectorVoz.iniciar(this)
		ControlAudio.iniciar(this)
		// Pausar al encender la pantalla o al desbloquear (si se elige en Ajustes)
		com.xebolax.lector.audio.PantallaEncendida.iniciar(this)
		// Estadísticas: tiempo escuchando podcasts, noticias y radio
		com.xebolax.lector.datos.Actividad.empezar(this)
		// El widget dice el libro y si está leyendo
		var ultimo: Pair<Long, Boolean>? = null
		val principal = android.os.Handler(android.os.Looper.getMainLooper())
		val widget = Runnable { com.xebolax.lector.widget.WidgetLectura.actualizar(this) }
		com.xebolax.lector.datos.BaseDatos.obtener(this).suscribir {
			principal.removeCallbacks(widget)
			principal.postDelayed(widget, 1500)
		}
		LectorVoz.registrar { e ->
			val ahora = e.libro to e.leyendo
			if (ahora != ultimo) {
				ultimo = ahora
				com.xebolax.lector.widget.WidgetLectura.actualizar(this)
			}
		}
	}
}
