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

	fun alAbrir(actividad: android.app.Activity) {
		val p = Preferencias(actividad)
		if (p.carpetasVigiladas.isEmpty()) return
		val ultima = p.p.getLong("ultima_revision", 0L)
		if (System.currentTimeMillis() - ultima < CADA_MS) return
		p.p.edit().putLong("ultima_revision", System.currentTimeMillis()).apply()
		val app = actividad.applicationContext
		val debil = java.lang.ref.WeakReference(actividad)
		LectorApp.ambito.launch {
			val n = runCatching { Importador.revisarVigiladas(app) }.getOrDefault(0)
			val a = debil.get() ?: return@launch
			if (n > 0) a.runOnUiThread {
				if (!a.isFinishing && !a.isDestroyed) a.avisar(a.resources.getQuantityString(R.plurals.libros_nuevos_carpetas, n, n))
			}
		}
	}

	/** Lee los datos reales de libros recién añadidos (sin bloquear la pantalla). */
	fun completar(contexto: Context, ids: List<Long>) {
		val app = contexto.applicationContext
		LectorApp.ambito.launch { Importador.completarDatos(app, ids) }
	}
}
