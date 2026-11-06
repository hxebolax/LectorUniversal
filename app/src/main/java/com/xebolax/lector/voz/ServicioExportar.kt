package com.xebolax.lector.voz

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.xebolax.lector.R
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.lectura.Abiertos
import com.xebolax.lector.modelo.Contenido
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Exporta un libro a audio en segundo plano (puede tardar bastante), con el
 * progreso en la notificación y un botón para cancelar.
 */
class ServicioExportar : Service() {

	companion object {
		private const val CANAL = "exportar"
		private const val AVISO = 41
		private const val EXTRA_LIBRO = "libro"
		private const val EXTRA_CARPETA = "carpeta"
		private const val CANCELAR = "cancelar"

		/** ¿Hay una exportación en marcha? */
		@Volatile
		var exportando = false
			private set

		fun empezar(c: Context, idLibro: Long, carpeta: Uri) {
			val intent = Intent(c, ServicioExportar::class.java).putExtra(EXTRA_LIBRO, idLibro).putExtra(EXTRA_CARPETA, carpeta.toString())
			if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(intent) else c.startService(intent)
		}
	}

	private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var trabajo: Job? = null

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == CANCELAR) {
			trabajo?.cancel()
			return START_NOT_STICKY
		}
		val idLibro = intent?.getLongExtra(EXTRA_LIBRO, -1) ?: -1
		val carpeta = intent?.getStringExtra(EXTRA_CARPETA)?.let { Uri.parse(it) }
		crearCanal()
		val primera = aviso(getString(R.string.exportar_audio_preparando), 0, true)
		ServiceCompat.startForeground(
			this, AVISO, primera,
			if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
		)
		if (exportando || idLibro <= 0 || carpeta == null) {
			if (!exportando) detener()
			return START_NOT_STICKY
		}
		exportando = true
		trabajo = ambito.launch {
			val libro = withContext(Dispatchers.IO) { BaseDatos.obtener(this@ServicioExportar).libro(idLibro) }
			var final: String
			try {
				libro ?: error(getString(R.string.error_abrir, ""))
				val contenido = withContext(Dispatchers.IO) { Abiertos.abrir(this@ServicioExportar, libro, Abiertos.opciones(this@ServicioExportar)) }
				val doc = (contenido as? Contenido.Texto)?.documento ?: error(getString(R.string.error_abrir, ""))
				val titulo = doc.titulo.ifBlank { libro.tituloVisible }
				val n = ExportadorAudio(this@ServicioExportar).exportar(doc, libro.id, titulo, carpeta) { cap, total, porcentaje ->
					avisar(getString(R.string.exportar_audio_progreso, cap, total), porcentaje)
				}
				final = resources.getQuantityString(R.plurals.exportar_audio_hecho, n, n, titulo)
			} catch (e: CancellationException) {
				final = getString(R.string.exportar_audio_cancelado)
			} catch (e: Exception) {
				final = getString(R.string.exportar_audio_error, e.message.orEmpty())
			}
			exportando = false
			// El aviso final se queda (sin el servicio)
			ServiceCompat.stopForeground(this@ServicioExportar, ServiceCompat.STOP_FOREGROUND_REMOVE)
			getSystemService(NotificationManager::class.java)?.notify(AVISO + 1, aviso(final, -1, false))
			stopSelf()
		}
		return START_NOT_STICKY
	}

	private fun detener() {
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
		stopSelf()
	}

	private fun avisar(texto: String, porcentaje: Int) {
		getSystemService(NotificationManager::class.java)?.notify(AVISO, aviso(texto, porcentaje, true))
	}

	private fun crearCanal() {
		if (Build.VERSION.SDK_INT < 26) return
		val canal = NotificationChannel(CANAL, getString(R.string.exportar_audio), NotificationManager.IMPORTANCE_LOW)
		getSystemService(NotificationManager::class.java)?.createNotificationChannel(canal)
	}

	private fun aviso(texto: String, porcentaje: Int, enCurso: Boolean) = NotificationCompat.Builder(this, CANAL)
		.setSmallIcon(R.drawable.ic_voz)
		.setContentTitle(getString(R.string.exportar_audio))
		.setContentText(texto)
		.setStyle(NotificationCompat.BigTextStyle().bigText(texto))
		.setOngoing(enCurso)
		.setOnlyAlertOnce(true)
		.apply {
			if (enCurso) {
				setProgress(100, porcentaje.coerceIn(0, 100), porcentaje < 0)
				val cancelar = PendingIntent.getService(
					this@ServicioExportar, 1, Intent(this@ServicioExportar, ServicioExportar::class.java).setAction(CANCELAR),
					PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
				)
				addAction(0, getString(R.string.cancelar), cancelar)
			}
		}
		.build()

	override fun onDestroy() {
		trabajo?.cancel()
		exportando = false
		super.onDestroy()
	}
}
