package com.xebolax.lector.compartidos

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.ui.PaginaWeb

/**
 * Lo compartido que no se pudo descargar (sin conexión) se queda en
 * Compartidos como «pendiente de descargar» y se descarga aquí en cuanto
 * vuelve la red, aunque la app esté cerrada.
 */
class DescargasPendientes(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {

	companion object {
		private const val NOMBRE = "compartidos_pendientes"

		fun programar(c: Context) {
			val peticion = OneTimeWorkRequestBuilder<DescargasPendientes>()
				.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
				.build()
			WorkManager.getInstance(c).enqueueUniqueWork(NOMBRE, ExistingWorkPolicy.KEEP, peticion)
		}

		/** ¿Está pendiente de descargar? */
		fun pendiente(ajustes: String) = runCatching { org.json.JSONObject(ajustes).optBoolean("pendiente") }.getOrDefault(false)

		/** Todo lo pendiente de descargar, esté en Compartidos o ya pasado a la biblioteca. */
		fun pendientes(c: Context) = BaseDatos.obtener(c).todosConCompartidos().filter { pendiente(it.ajustes) && it.origen.startsWith("http") }

		/**
		 * Al abrir Compartidos: si queda algo pendiente, se vuelve a programar
		 * (tras varios intentos fallidos el trabajo se rinde).
		 */
		suspend fun reprogramarSiHace(c: Context) {
			val hay = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pendientes(c).isNotEmpty() }
			if (hay) programar(c)
		}
	}

	override suspend fun doWork(): Result {
		val c = applicationContext
		var fallos = 0
		for (l in pendientes(c)) {
			try {
				PaginaWeb.guardar(c, l.origen, compartido = true, forzar = true)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				fallos++
			}
		}
		return if (fallos > 0 && runAttemptCount < 5) Result.retry() else Result.success()
	}
}
