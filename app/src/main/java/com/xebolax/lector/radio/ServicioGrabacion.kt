package com.xebolax.lector.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xebolax.lector.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Graba una emisora en segundo plano (con su propia conexión, así se puede
 * grabar sin escucharla, o escuchar otra cosa). Se guarda tal como llega
 * (MP3, AAC, OGG…), sin perder calidad. Si se corta, vuelve a conectar y
 * sigue en el mismo archivo. Para al pulsar Detener o al acabar los minutos.
 */
class ServicioGrabacion : Service() {

	companion object {
		private const val ACCION_GRABAR = "grabar"
		private const val ACCION_PARAR = "parar"
		private const val EXTRA_EMISORA = "emisora"
		private const val EXTRA_MINUTOS = "minutos"
		private const val CANAL = "radio_grabacion"
		private const val NOTIFICACION = 7301

		/** Lo que se está grabando (null si nada). */
		@Volatile
		var grabando: Emisora? = null
			private set

		@Volatile
		var desde = 0L
			private set

		/** Minutos que se grabarán (0 = hasta que se pare). */
		@Volatile
		var minutos = 0
			private set

		fun grabar(c: Context, e: Emisora, minutos: Int = 0) {
			ContextCompat.startForegroundService(c, Intent(c, ServicioGrabacion::class.java).setAction(ACCION_GRABAR)
				.putExtra(EXTRA_EMISORA, e.json().toString()).putExtra(EXTRA_MINUTOS, minutos))
		}

		fun parar(c: Context) {
			if (grabando == null) return
			c.startService(Intent(c, ServicioGrabacion::class.java).setAction(ACCION_PARAR))
		}

		/** Las emisoras HLS (por trozos) no se pueden grabar así. */
		fun sePuedeGrabar(e: Emisora) = !e.hls && !e.url.substringBefore('?').endsWith(".m3u8", true)

		fun grabandoEsta(e: Emisora) = grabando?.uuid == e.uuid
	}

	private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private var trabajo: Job? = null
	private var idGrabacion = 0L
	private var archivo: File? = null

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
			ACCION_GRABAR -> {
				val e = Emisora.deJson(intent.getStringExtra(EXTRA_EMISORA).orEmpty())
				if (e == null) {
					pararServicio()
					return START_NOT_STICKY
				}
				// Siempre en primer plano en cuanto se pide (lo exige Android)
				enPrimerPlano(e)
				if (grabando != null) terminar()
				empezar(e, intent.getIntExtra(EXTRA_MINUTOS, 0))
			}
			ACCION_PARAR -> {
				if (grabando == null) {
					pararServicio()
					return START_NOT_STICKY
				}
				terminar()
				pararServicio()
			}
			else -> pararServicio()
		}
		return START_NOT_STICKY
	}

	private fun canal() {
		if (Build.VERSION.SDK_INT < 26) return
		val nm = getSystemService(NotificationManager::class.java)
		nm.createNotificationChannel(NotificationChannel(CANAL, getString(R.string.radio_canal_grabacion), NotificationManager.IMPORTANCE_LOW))
	}

	private fun notificacion(e: Emisora): Notification {
		canal()
		val parar = PendingIntent.getService(this, 2, Intent(this, ServicioGrabacion::class.java).setAction(ACCION_PARAR), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
		val abrir = PendingIntent.getActivity(this, 3, Intent(this, RadioActivity::class.java).setAction(RadioActivity.ACCION_GRABACIONES), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
		val texto = if (minutos > 0) getString(R.string.radio_grabando_minutos, minutos) else getString(R.string.radio_grabando_hasta_parar)
		return NotificationCompat.Builder(this, CANAL)
			.setSmallIcon(R.drawable.ic_grabar)
			.setContentTitle(getString(R.string.radio_grabando_x, e.nombre))
			.setContentText(texto)
			.setContentIntent(abrir)
			.setOngoing(true)
			.setUsesChronometer(true)
			.setWhen(System.currentTimeMillis())
			.addAction(R.drawable.ic_detener, getString(R.string.radio_detener_grabacion), parar)
			.build()
	}

	private fun enPrimerPlano(e: Emisora) {
		ServiceCompat.startForeground(this, NOTIFICACION, notificacion(e),
			if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
	}

	private fun extension(tipo: String?, e: Emisora): String {
		val t = (tipo ?: "").lowercase()
		val c = e.codec.lowercase()
		return when {
			"aac" in t || "aac" in c || "mp4" in t -> "aac"
			"ogg" in t || "ogg" in c || "vorbis" in c -> "ogg"
			"opus" in t || "opus" in c -> "opus"
			"flac" in t || "flac" in c -> "flac"
			else -> "mp3"
		}
	}

	private fun nombreArchivo(e: Emisora, ext: String): File {
		val fecha = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())
		val base = (e.nombre.replace(Regex("""[\\/:*?"<>|]"""), " ").trim().take(60).ifBlank { "Radio" }) + " " + fecha
		val carpeta = Radio.carpetaGrabaciones(this)
		var f = File(carpeta, "$base.$ext")
		var n = 2
		while (f.exists()) f = File(carpeta, "$base ($n).$ext").also { n++ }
		return f
	}

	private fun empezar(e: Emisora, min: Int) {
		grabando = e
		minutos = min
		desde = SystemClock.elapsedRealtime()
		val db = RadioBaseDatos.obtener(this)
		val fin = if (min > 0) SystemClock.elapsedRealtime() + min * 60_000L else Long.MAX_VALUE
		trabajo = ambito.launch {
			var salida: FileOutputStream? = null
			var fallos = 0
			try {
				while (isActive && SystemClock.elapsedRealtime() < fin && fallos < 10) {
					val c = runCatching {
						(URL(e.url).openConnection() as HttpURLConnection).apply {
							connectTimeout = 10_000
							readTimeout = 20_000
							setRequestProperty("User-Agent", "LectorUniversal/" + com.xebolax.lector.BuildConfig.VERSION_NAME)
							// Sin metadatos ICY mezclados con el sonido
							setRequestProperty("Icy-MetaData", "0")
							connect()
						}
					}.getOrNull()
					if (c == null || c.responseCode !in 200..299) {
						c?.disconnect()
						fallos++
						delay(3_000L * fallos)
						continue
					}
					if (salida == null) {
						val f = nombreArchivo(e, extension(c.contentType, e))
						archivo = f
						salida = FileOutputStream(f)
						idGrabacion = db.nuevaGrabacion(f.absolutePath, e)
					}
					val destino = salida!!
					try {
						c.inputStream.use { entrada ->
							val bufer = ByteArray(16 * 1024)
							while (isActive && SystemClock.elapsedRealtime() < fin) {
								val n = entrada.read(bufer)
								if (n < 0) break
								destino.write(bufer, 0, n)
								fallos = 0
							}
						}
					} catch (_: java.io.IOException) {
						fallos++
						delay(2_000)
					} finally {
						c.disconnect()
					}
				}
			} finally {
				runCatching { salida?.close() }
				launch(Dispatchers.Main) {
					// Terminó sola (minutos cumplidos o no se pudo conectar): se cierra el servicio
					if (grabando?.uuid == e.uuid && trabajo?.isCancelled != true) {
						terminar()
						pararServicio()
					}
				}
			}
		}
	}

	/** Cierra la grabación y apunta su duración y tamaño. */
	private fun terminar() {
		trabajo?.cancel()
		trabajo = null
		val f = archivo
		val db = RadioBaseDatos.obtener(this)
		if (idGrabacion > 0) {
			val tam = f?.length() ?: 0L
			if (tam <= 0) {
				f?.delete()
				db.borrarGrabacion(idGrabacion)
			} else db.terminarGrabacion(idGrabacion, SystemClock.elapsedRealtime() - desde, tam)
		}
		idGrabacion = 0
		archivo = null
		grabando = null
		minutos = 0
		db.avisar()
	}

	private fun pararServicio() {
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
		stopSelf()
	}

	override fun onDestroy() {
		if (grabando != null) terminar()
		ambito.cancel()
		super.onDestroy()
	}
}
