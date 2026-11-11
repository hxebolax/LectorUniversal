package com.xebolax.lector.actualizaciones

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.xebolax.lector.BuildConfig
import com.xebolax.lector.R
import com.xebolax.lector.datos.FalloEstado
import com.xebolax.lector.datos.FalloIo
import com.xebolax.lector.datos.Preferencias
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/** Versión publicada en el servidor (version.json) o en GitHub (la última release). */
data class VersionPublicada(
	val version: String,
	val versionCode: Int,
	val archivo: String,
	val sha256: String,
	val tamano: Long,
	val fecha: String,
	val notas: String,
	val base: String,
	/** Dirección del APK si no es base/archivo (GitHub). */
	val urlDirecta: String = "",
	/** De dónde viene, para decirlo al ofrecerla: el servidor o «GitHub». */
	val origen: String = "",
) {
	val url: String get() = urlDirecta.ifEmpty { "$base/$archivo" }
}

/**
 * Actualizaciones desde el servidor propio, desde GitHub, desde los dos (la
 * versión más nueva; si uno no responde, el otro) o desde una dirección
 * personalizada (Ajustes > General > Actualizaciones):
 *
 * 1. Se lee `version.json` (o la última release de GitHub, con su
 *    version.json adjunto si lo tiene) y se compara con la versión instalada.
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

	/**
	 * La versión publicada en la fuente elegida en Ajustes (con un reintento si
	 * la red falla). Con «Los dos», la más nueva de las que respondan. Lanza
	 * excepción si no se puede leer ninguna.
	 */
	suspend fun versionPublicada(contexto: Context): VersionPublicada = withContext(Dispatchers.IO) {
		val p = Preferencias(contexto)
		val servidor = urlBase(contexto)
		when (p.fuenteActualizaciones) {
			"servidor" -> conReintento { leerVersion(servidor) }
			"github" -> conReintento { leerGitHub(BuildConfig.REPO_GITHUB) }
			"personalizada" -> {
				val url = p.urlPersonalizada
				if (!url.startsWith("http")) throw FalloEstado(R.string.cj_act_sin_direccion)
				val repo = repoDeGitHub(url)
				conReintento { if (repo != null) leerGitHub(repo) else leerVersion(url) }
			}
			else -> {
				// Las dos a la vez; vale la que responda y, si responden las dos, la más nueva
				val pedidas = listOf(
					async { runCatching { conReintento { leerVersion(servidor) } } },
					async { runCatching { conReintento { leerGitHub(BuildConfig.REPO_GITHUB) } } },
				).awaitAll()
				pedidas.mapNotNull { it.getOrNull() }.maxWithOrNull(::compararPublicadas)
					?: throw (pedidas.first().exceptionOrNull() ?: IllegalStateException())
			}
		}
	}

	/** Más nueva: por versionCode si las dos lo dicen; si no, por el número de versión. */
	private fun compararPublicadas(a: VersionPublicada, b: VersionPublicada): Int =
		if (a.versionCode > 0 && b.versionCode > 0) a.versionCode.compareTo(b.versionCode) else compararVersiones(a.version, b.version)

	/** Dos intentos cortos: es un archivo diminuto y quien busca a mano está esperando. */
	private suspend fun <T> conReintento(leer: () -> T): T {
		try {
			return leer()
		} catch (e: IOException) {
			Log.w(ETIQUETA, "No se pudo leer la versión publicada", e)
			delay(1500)
		}
		return leer()
	}

	/** «https://github.com/usuario/repositorio» → «usuario/repositorio» (null si no es de GitHub). */
	fun repoDeGitHub(url: String): String? =
		Regex("^https?://(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:/.*)?$").find(url.trim())
			?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }

	/**
	 * La última release publicada de un repositorio de GitHub: su etiqueta (v1.2.3),
	 * sus notas y el APK adjunto (tamaño y SHA-256 que calcula GitHub). Si la release
	 * lleva también un version.json, de él salen el versionCode y las novedades.
	 */
	private fun leerGitHub(repo: String): VersionPublicada {
		val c = conexion("https://api.github.com/repos/$repo/releases/latest", conectar = 8_000)
		c.setRequestProperty("Accept", "application/vnd.github+json")
		c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
		val o = try {
			when (val codigo = c.responseCode) {
				200 -> JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
				404 -> throw FalloEstado(R.string.cj_act_github_sin_versiones, repo)
				403, 429 -> throw FalloIo(R.string.cj_act_github_limite)
				else -> throw FalloEstado(R.string.cj_act_github_respondio, codigo)
			}
		} finally {
			c.disconnect()
		}
		val adjuntos = o.optJSONArray("assets") ?: org.json.JSONArray()
		val lista = (0 until adjuntos.length()).map { adjuntos.getJSONObject(it) }
		val apk = lista.firstOrNull { Regex("[A-Za-z0-9._-]+\\.apk").matches(it.optString("name")) }
			?: throw FalloEstado(R.string.cj_act_github_sin_apk)
		val urlApk = apk.optString("browser_download_url")
		if (!urlApk.startsWith("https://github.com/")) throw FalloEstado(R.string.cj_act_github_apk_no_valido)
		// version.json adjunto (lo sube publicar_actualizacion.ps1): versionCode y novedades
		val json = lista.firstOrNull { it.optString("name") == "version.json" }?.let { a ->
			runCatching {
				val cj = conexion(a.optString("browser_download_url"), conectar = 8_000)
				try {
					if (cj.responseCode == 200) JSONObject(cj.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }) else null
				} finally {
					cj.disconnect()
				}
			}.getOrNull()
		}
		val version = json?.optString("version")?.trim()?.takeIf { it.isNotEmpty() } ?: o.optString("tag_name").trim().removePrefix("v").removePrefix("V")
		if (version.isEmpty()) throw FalloEstado(R.string.cj_act_github_sin_numero)
		val digest = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
		return VersionPublicada(
			version = version,
			versionCode = json?.optInt("versionCode", 0) ?: 0,
			archivo = apk.optString("name"),
			sha256 = (digest ?: json?.optString("sha256")).orEmpty().trim().lowercase(),
			tamano = apk.optLong("size", 0L),
			fecha = o.optString("published_at").take(10),
			notas = json?.optString("notas")?.trim()?.takeIf { it.isNotEmpty() } ?: o.optString("body").trim(),
			base = "https://github.com/$repo",
			urlDirecta = urlApk,
			origen = "GitHub",
		)
	}

	private fun leerVersion(base: String): VersionPublicada {
		val c = conexion("$base/version.json?t=${System.currentTimeMillis()}", conectar = 8_000)
		try {
			if (c.responseCode != 200) throw FalloEstado(R.string.cj_act_servidor_respondio, c.responseCode)
			val o = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
			val version = o.optString("version").trim()
			val archivo = o.optString("file").trim()
			if (version.isEmpty() || archivo.isEmpty()) throw FalloEstado(R.string.cj_act_version_no_valida)
			// Solo un nombre de archivo, nunca una ruta ni otra web
			if (!Regex("[A-Za-z0-9._-]+\\.apk").matches(archivo)) throw FalloEstado(R.string.cj_act_version_no_valida)
			return VersionPublicada(
				version = version,
				versionCode = o.optInt("versionCode", 0),
				archivo = archivo,
				sha256 = o.optString("sha256").trim().lowercase(),
				tamano = o.optLong("size", 0L),
				fecha = o.optString("date"),
				notas = o.optString("notas").trim(),
				base = base,
				origen = runCatching { URL(base).host }.getOrDefault(base),
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
							throw FalloEstado(R.string.cj_act_servidor_respondio_descarga, codigo)
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
							throw FalloEstado(R.string.cj_act_cortes)
						}
						delay(2000L * minOf(intentos, 5))
					} finally {
						c.disconnect()
					}
				}
				if (v.tamano > 0 && parcial.length() != v.tamano) throw FalloEstado(R.string.cj_act_incompleta)
				if (v.sha256.isNotEmpty() && sha256(parcial) != v.sha256) {
					throw FalloEstado(R.string.cj_act_sha)
				}
				if (!parcial.renameTo(destino)) throw FalloEstado(R.string.cj_act_no_guardada)
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
			?: throw FalloEstado(R.string.cj_act_no_app)
		if (nueva.packageName != contexto.packageName) throw FalloEstado(R.string.cj_act_no_lector)
		val codigoNuevo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) nueva.longVersionCode else @Suppress("DEPRECATION") nueva.versionCode.toLong()
		if (codigoNuevo <= BuildConfig.VERSION_CODE) throw FalloEstado(R.string.cj_act_no_nueva)
		val instalada = pm.getPackageInfo(contexto.packageName, banderas)
		val firmasNuevas = firmas(nueva)
		// Si el sistema no da las firmas del archivo, el instalador las comprobará igualmente
		if (firmasNuevas.isNotEmpty() && firmasNuevas.intersect(firmas(instalada)).isEmpty()) {
			throw FalloEstado(R.string.cj_act_firma)
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
