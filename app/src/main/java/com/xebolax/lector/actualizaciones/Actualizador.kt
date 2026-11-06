package com.xebolax.lector.actualizaciones

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.xebolax.lector.BuildConfig
import com.xebolax.lector.datos.Preferencias
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Versión publicada en el servidor (version.json). */
data class VersionPublicada(
	val version: String,
	val versionCode: Int,
	val archivo: String,
	val sha256: String,
	val tamano: Long,
	val fecha: String,
	val notas: String,
	val base: String,
) {
	val url: String get() = "$base/$archivo"
}

/**
 * Actualizaciones desde el servidor propio, como en AdultHub:
 *
 * 1. Se lee `version.json` y se compara con la versión instalada.
 * 2. Se descarga `LectorUniversal_v<versión>.apk` a la caché de la app.
 * 3. Se comprueba que el archivo es el publicado (tamaño y SHA-256) y que es
 *    Lector Universal, más nueva y firmada con la MISMA clave que la instalada. Android
 *    además rechaza instalar encima una app con otra firma, así que un
 *    servidor manipulado no puede colar otra aplicación.
 * 4. El instalador del sistema pide la confirmación al usuario.
 */
object Actualizador {

	/** Dirección del servidor (Ajustes > General; por defecto la de la compilación). */
	fun urlBase(contexto: Context): String = Preferencias(contexto).urlActualizaciones
	private const val AGENTE = "LectorUniversal-Actualizador"
	private const val ESPERA_MS = 20_000
	/** Cortes seguidos SIN avanzar antes de rendirse (si avanza, el contador vuelve a cero). */
	private const val REINTENTOS = 8
	private const val ETIQUETA = "Actualizador"

	/** Carpeta de la caché donde se descarga el APK (compartida con el instalador por FileProvider). */
	fun carpeta(contexto: Context) = File(contexto.cacheDir, "actualizaciones").apply { mkdirs() }

	private fun conexion(url: String, conectar: Int = ESPERA_MS): HttpURLConnection =
		(URL(url).openConnection() as HttpURLConnection).apply {
			connectTimeout = conectar
			readTimeout = ESPERA_MS
			useCaches = false
			setRequestProperty("User-Agent", AGENTE)
			setRequestProperty("Cache-Control", "no-cache")
			// Conexión nueva cada vez: una reutilizada que el servidor ya cerró corta la descarga
			setRequestProperty("Connection", "close")
		}

	/** "1.2.10" > "1.2.9": compara número a número. */
	fun compararVersiones(a: String, b: String): Int {
		val x = Regex("\\d+").findAll(a).map { it.value.toInt() }.toList()
		val y = Regex("\\d+").findAll(b).map { it.value.toInt() }.toList()
		for (i in 0 until maxOf(x.size, y.size)) {
			val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
			if (c != 0) return c
		}
		return 0
	}

	/** Lee version.json del servidor (con reintentos si la red falla). Lanza excepción si no se puede. */
	suspend fun versionPublicada(contexto: Context): VersionPublicada = withContext(Dispatchers.IO) {
		val base = urlBase(contexto)
		// Dos intentos cortos: es un archivo diminuto y quien busca a mano está esperando
		try {
			return@withContext leerVersion(base)
		} catch (e: IOException) {
			Log.w(ETIQUETA, "No se pudo leer version.json", e)
			delay(1500)
		}
		leerVersion(base)
	}

	private fun leerVersion(base: String): VersionPublicada {
		val c = conexion("$base/version.json?t=${System.currentTimeMillis()}", conectar = 8_000)
		try {
			if (c.responseCode != 200) throw IllegalStateException("El servidor respondió ${c.responseCode}.")
			val o = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
			val version = o.optString("version").trim()
			val archivo = o.optString("file").trim()
			require(version.isNotEmpty() && archivo.isNotEmpty()) { "El archivo de versión del servidor no es válido." }
			// Solo un nombre de archivo, nunca una ruta ni otra web
			require(Regex("[A-Za-z0-9._-]+\\.apk").matches(archivo)) { "El archivo de versión del servidor no es válido." }
			return VersionPublicada(
				version = version,
				versionCode = o.optInt("versionCode", 0),
				archivo = archivo,
				sha256 = o.optString("sha256").trim().lowercase(),
				tamano = o.optLong("size", 0L),
				fecha = o.optString("date"),
				notas = o.optString("notas").trim(),
				base = base,
			)
		} finally {
			c.disconnect()
		}
	}

	/** ¿La versión publicada es más nueva que la instalada? */
	fun esNueva(v: VersionPublicada): Boolean =
		if (v.versionCode > 0) v.versionCode > BuildConfig.VERSION_CODE
		else compararVersiones(v.version, BuildConfig.VERSION_NAME) > 0

	/**
	 * Descarga y verifica el APK. progreso(bytes, total) se llama desde el
	 * hilo de fondo. Devuelve el archivo listo para instalar.
	 */
	suspend fun descargar(contexto: Context, v: VersionPublicada, progreso: (Long, Long) -> Unit): File =
		withContext(Dispatchers.IO) {
			val carpeta = carpeta(contexto)
			carpeta.listFiles()?.forEach { it.delete() }
			val destino = File(carpeta, v.archivo)
			val parcial = File(carpeta, v.archivo + ".parte")
			try {
				var leidos = 0L
				var total = v.tamano
				var intentos = 0
				// Si la conexión se corta (datos móviles, cambio de red...), se reintenta
				// y se continúa donde se quedó (petición Range) en lugar de empezar de cero
				while (true) {
					ensureActive()
					val c = conexion(v.url)
					val alEmpezar = leidos
					if (leidos > 0) c.setRequestProperty("Range", "bytes=$leidos-")
					try {
						val codigo = c.responseCode
						if (codigo == 200) {
							// El servidor manda el archivo entero (sin Range): se empieza de nuevo
							leidos = 0
							total = c.contentLengthLong.takeIf { it > 0 } ?: v.tamano
						} else if (codigo != 206 || leidos == 0L) {
							throw IllegalStateException("El servidor respondió $codigo al descargar.")
						}
						c.inputStream.use { entrada ->
							FileOutputStream(parcial, leidos > 0).use { salida ->
								val bufer = ByteArray(64 * 1024)
								while (true) {
									ensureActive()
									val n = entrada.read(bufer)
									if (n < 0) break
									salida.write(bufer, 0, n)
									leidos += n
									progreso(leidos, total)
								}
							}
						}
						if (total > 0 && leidos < total) throw IOException("Faltan ${total - leidos} bytes")
						break
					} catch (e: IOException) {
						Log.w(ETIQUETA, "Descarga cortada en $leidos de $total bytes", e)
						if (leidos > alEmpezar) intentos = 0
						if (++intentos > REINTENTOS) {
							throw IllegalStateException("La conexión se cortó varias veces (${e.message}). Inténtalo de nuevo.")
						}
						delay(2000L * minOf(intentos, 5))
					} finally {
						c.disconnect()
					}
				}
				if (v.tamano > 0 && parcial.length() != v.tamano) throw IllegalStateException("La descarga está incompleta. Inténtalo de nuevo.")
				if (v.sha256.isNotEmpty() && sha256(parcial) != v.sha256) {
					throw IllegalStateException("El archivo descargado no coincide con el publicado (SHA-256). No se instalará.")
				}
				if (!parcial.renameTo(destino)) throw IllegalStateException("No se pudo guardar la actualización.")
				comprobarApk(contexto, destino)
				destino
			} catch (e: Exception) {
				parcial.delete()
				destino.delete()
				throw e
			}
		}

	private fun sha256(archivo: File): String {
		val resumen = MessageDigest.getInstance("SHA-256")
		archivo.inputStream().use { entrada ->
			val bufer = ByteArray(1024 * 1024)
			while (true) {
				val n = entrada.read(bufer)
				if (n < 0) break
				resumen.update(bufer, 0, n)
			}
		}
		return resumen.digest().joinToString("") { "%02x".format(it) }
	}

	/** El APK debe ser Lector Universal, más nueva y con la misma firma que la instalada. */
	private fun comprobarApk(contexto: Context, apk: File) {
		val pm = contexto.packageManager
		val banderas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
		else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
		val nueva = pm.getPackageArchiveInfo(apk.absolutePath, banderas)
			?: throw IllegalStateException("El archivo descargado no es una aplicación válida.")
		if (nueva.packageName != contexto.packageName) throw IllegalStateException("El archivo descargado no es Lector Universal.")
		val codigoNuevo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) nueva.longVersionCode else @Suppress("DEPRECATION") nueva.versionCode.toLong()
		if (codigoNuevo <= BuildConfig.VERSION_CODE) throw IllegalStateException("El archivo descargado no es más nuevo que la versión instalada.")
		val instalada = pm.getPackageInfo(contexto.packageName, banderas)
		val firmasNuevas = firmas(nueva)
		// Si el sistema no da las firmas del archivo, el instalador las comprobará igualmente
		if (firmasNuevas.isNotEmpty() && firmasNuevas.intersect(firmas(instalada)).isEmpty()) {
			throw IllegalStateException("La actualización no está firmada con la clave de Lector Universal. No se instalará.")
		}
	}

	private fun firmas(info: PackageInfo): Set<String> {
		val lista = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.let {
			if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
		} else @Suppress("DEPRECATION") info.signatures
		return lista.orEmpty().map { firma ->
			MessageDigest.getInstance("SHA-256").digest(firma.toByteArray()).joinToString("") { "%02x".format(it) }
		}.toSet()
	}
}
