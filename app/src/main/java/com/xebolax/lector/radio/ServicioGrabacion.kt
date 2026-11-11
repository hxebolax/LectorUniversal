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
		private const val NOTIFICACION_FALLO = 7304
		private const val CANAL_AVISOS = "radio_grabacion_avisos"

		/** Lo que se está grabando (null si nada). */
		@Volatile
		var grabando: Emisora? = null
		/** El archivo de la grabación en marcha (para no renombrarlo ni borrarlo mientras se graba). */
		@Volatile
		var archivoActual: String? = null
			private set

		@Volatile
		var desde = 0L
			private set

		/** Minutos que se grabarán (0 = hasta que se pare). */
		@Volatile
		var minutos = 0
			private set

		fun grabar(c: Context, e: Emisora, minutos: Int = 0) {
			// Las emisoras HLS (por trozos) no se pueden grabar así
			if (!sePuedeGrabar(e)) return
			runCatching {
				ContextCompat.startForegroundService(c, Intent(c, ServicioGrabacion::class.java).setAction(ACCION_GRABAR)
					.putExtra(EXTRA_EMISORA, e.json().toString()).putExtra(EXTRA_MINUTOS, minutos))
			}
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
	/** La conexión de la grabación en marcha (para cortarla al parar). */
	@Volatile
	private var conexion: HttpURLConnection? = null

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
			ACCION_GRABAR -> {
				val e = Emisora.deJson(intent.getStringExtra(EXTRA_EMISORA).orEmpty())
				if (e == null) {
					pararServicio()
					return START_NOT_STICKY
				}
				// Siempre en primer plano en cuanto se pide (lo exige Android); si Android no lo deja (límite diario), no se graba
				if (grabando != null) terminar()
				val min = intent.getIntExtra(EXTRA_MINUTOS, 0)
				minutos = min
				if (runCatching { enPrimerPlano(e) }.isFailure) {
					stopSelf()
					return START_NOT_STICKY
				}
				empezar(e, min)
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

	/** Mientras graba, el móvil no se duerme ni apaga el Wi-Fi (con la pantalla apagada la grabación se cortaba). */
	private var despierto: android.os.PowerManager.WakeLock? = null
	private var wifiDespierto: android.net.wifi.WifiManager.WifiLock? = null

	private fun mantenerDespierto(min: Int) {
		soltarDespierto()
		// Con un límite: los minutos de la grabación y un margen, o un día si es «hasta que la pares»
		val limite = if (min > 0) min * 60_000L + 120_000L else 24 * 3_600_000L
		despierto = runCatching {
			(getSystemService(POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "LectorUniversal:grabacion")
				.apply { setReferenceCounted(false); acquire(limite) }
		}.getOrNull()
		wifiDespierto = runCatching {
			val wifi = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
			@Suppress("DEPRECATION")
			val modo = android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
			wifi.createWifiLock(modo, "LectorUniversal:grabacion").apply { setReferenceCounted(false); acquire() }
		}.getOrNull()
	}

	private fun soltarDespierto() {
		runCatching { despierto?.takeIf { it.isHeld }?.release() }
		runCatching { wifiDespierto?.takeIf { it.isHeld }?.release() }
		despierto = null
		wifiDespierto = null
	}

	private fun empezar(e: Emisora, min: Int) {
		grabando = e
		minutos = min
		mantenerDespierto(min)
		val inicio = SystemClock.elapsedRealtime()
		desde = inicio
		val db = RadioBaseDatos.obtener(this)
		val fin = if (min > 0) inicio + min * 60_000L else Long.MAX_VALUE
		trabajo = ambito.launch {
			// Todo lo de esta grabación es suyo (si se para y empieza otra mientras conecta, no se mezclan)
			var salida: FileOutputStream? = null
			var archivoLocal: File? = null
			var idLocal = 0L
			var fallos = 0
			var motivo = ""
			try {
				while (isActive && SystemClock.elapsedRealtime() < fin && fallos < 10) {
					// Como el reproductor: agente propio, redirecciones http↔https y reserva a http (sin metadatos ICY mezclados)
					val c = try {
						Flujos.abrir(e.url, icy = false, conectar = 10_000, leer = 20_000).conexion
					} catch (x: Flujos.ErrorFlujo) {
						motivo = x.motivo
						null
					} catch (x: Exception) {
						motivo = "red"
						null
					}
					// Se ha parado mientras conectaba: no se crea nada
					if (!isActive) {
						c?.disconnect()
						break
					}
					if (c == null || c.responseCode !in 200..299) {
						if (c != null) motivo = "http:" + c.responseCode
						c?.disconnect()
						fallos++
						delay(3_000L * fallos)
						continue
					}
					conexion = c
					if (salida == null) {
						val f = nombreArchivo(e, extension(c.contentType, e))
						archivoLocal = f
						archivoActual = f.absolutePath
						salida = FileOutputStream(f)
						idLocal = db.nuevaGrabacion(f.absolutePath, e)
					}
					val destino = salida!!
					try {
						c.inputStream.use { entrada ->
							val bufer = ByteArray(16 * 1024)
							while (isActive && SystemClock.elapsedRealtime() < fin) {
								val n = entrada.read(bufer)
								if (n < 0) {
									// El servidor cortó: se reintenta con una espera creciente (no sin parar)
									motivo = "red"
									fallos++
									delay(2_000L * fallos)
									break
								}
								destino.write(bufer, 0, n)
								fallos = 0
							}
						}
					} catch (_: java.io.IOException) {
						motivo = "red"
						fallos++
						if (isActive) delay(2_000)
					} finally {
						if (conexion === c) conexion = null
						c.disconnect()
					}
				}
			} finally {
				runCatching { salida?.close() }
				// La cierra su propio trabajo: duración y tamaño, o se borra si quedó vacía
				if (idLocal > 0) {
					val tam = archivoLocal?.length() ?: 0L
					if (tam <= 0) {
						archivoLocal?.delete()
						db.borrarGrabacion(idLocal)
					} else db.terminarGrabacion(idLocal, SystemClock.elapsedRealtime() - inicio, tam)
				}
				if (archivoActual == archivoLocal?.absolutePath) archivoActual = null
				// No se pudo grabar (10 intentos sin conseguirlo): se avisa con el motivo
				val fallida = fallos >= 10 && isActive
				if (fallida) avisarFallo(e, motivo, conAlgo = idLocal > 0 && (archivoLocal?.length() ?: 0L) > 0)
				val propio = coroutineContext[Job]
				launch(Dispatchers.Main) {
					// Terminó sola (minutos cumplidos o no se pudo conectar): se cierra el servicio (si no ha empezado otra)
					if (trabajo === propio && grabando?.uuid == e.uuid) {
						terminar()
						pararServicio()
					}
				}
			}
		}
	}

	/** Aviso de que la grabación no se pudo hacer (o se cortó), con el motivo en palabras. */
	private fun avisarFallo(e: Emisora, motivo: String, conAlgo: Boolean) {
		// Canal propio de importancia normal (el de la grabación en marcha es silencioso y este aviso se perdía)
		if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
			.createNotificationChannel(NotificationChannel(CANAL_AVISOS, getString(R.string.audio_canal_grabacion_avisos), NotificationManager.IMPORTANCE_DEFAULT))
		val explicacion = when {
			motivo.startsWith("http:") -> {
				val codigo = motivo.removePrefix("http:").toIntOrNull() ?: 0
				getString(if (codigo in 400..499) R.string.radio_error_rechaza else R.string.radio_error_servidor, codigo)
			}
			motivo == "ssl" -> getString(R.string.radio_motivo_ssl)
			motivo == "no_estandar" -> getString(R.string.radio_motivo_no_estandar)
			motivo == "redirecciones" -> getString(R.string.radio_motivo_redirecciones)
			else -> getString(R.string.radio_motivo_red)
		}.replaceFirstChar { it.titlecase() }
		val abrir = PendingIntent.getActivity(this, 3, Intent(this, RadioActivity::class.java).setAction(RadioActivity.ACCION_GRABACIONES), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
		val titulo = getString(if (conAlgo) R.string.audio_grabacion_cortada else R.string.audio_grabacion_fallida, e.nombre)
		val n = NotificationCompat.Builder(this, CANAL_AVISOS)
			.setSmallIcon(R.drawable.ic_grabar)
			.setContentTitle(titulo)
			.setContentText(explicacion)
			.setStyle(NotificationCompat.BigTextStyle().bigText(explicacion))
			.setContentIntent(abrir)
			.setAutoCancel(true)
			.build()
		runCatching { androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICACION_FALLO, n) }
	}

	/** Android 15: el tiempo de un servicio así se acaba (6 horas al día): se guarda lo grabado y se cierra. */
	override fun onTimeout(startId: Int, tipo: Int) {
		terminar()
		pararServicio()
	}

	/** Para la grabación: su trabajo la cierra y apunta su duración y tamaño (o la borra si quedó vacía). */
	private fun terminar() {
		trabajo?.cancel()
		trabajo = null
		// Si está esperando datos del servidor, se corta la conexión para que pare ya
		conexion?.let { c -> ambito.launch { runCatching { c.disconnect() } } }
		grabando = null
		minutos = 0
		soltarDespierto()
		RadioBaseDatos.obtener(this).avisar()
	}

	private fun pararServicio() {
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
		stopSelf()
	}

	override fun onDestroy() {
		if (grabando != null) terminar()
		soltarDespierto()
		ambito.cancel()
		super.onDestroy()
	}
}
