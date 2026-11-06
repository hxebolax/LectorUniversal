package com.xebolax.lector.ui

import android.content.Context
import com.xebolax.lector.LectorApp
import com.xebolax.lector.R
import com.xebolax.lector.biblioteca.Importador
import com.xebolax.lector.datos.Preferencias
import kotlinx.coroutines.launch

/**
 * Tareas en segundo plano al abrir la app: revisar las carpetas vigiladas
 * (como mucho cada 6 horas) y leer el título y autor de los libros nuevos.
 */
object TareasInicio {

	private const val CADA_MS = 6 * 60 * 60 * 1000L

	fun alAbrir(actividad: PrincipalActivity) {
		val p = Preferencias(actividad)
		if (p.carpetasVigiladas.isEmpty()) return
		val ultima = p.p.getLong("ultima_revision", 0L)
		if (System.currentTimeMillis() - ultima < CADA_MS) return
		p.p.edit().putLong("ultima_revision", System.currentTimeMillis()).apply()
		val app = actividad.applicationContext
		LectorApp.ambito.launch {
			val n = runCatching { Importador.revisarVigiladas(app) }.getOrDefault(0)
			if (n > 0) actividad.runOnUiThread {
				if (!actividad.isFinishing) actividad.avisar(actividad.resources.getQuantityString(R.plurals.libros_nuevos_carpetas, n, n))
			}
		}
	}

	/** Lee los datos reales de libros recién añadidos (sin bloquear la pantalla). */
	fun completar(contexto: Context, ids: List<Long>) {
		val app = contexto.applicationContext
		LectorApp.ambito.launch { Importador.completarDatos(app, ids) }
	}
}
