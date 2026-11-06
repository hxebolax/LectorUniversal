package com.xebolax.lector.ia

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import com.xebolax.lector.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * IA descargada dentro de la app, como en la app de Windows (allí con
 * Ollama): Gemma 4 E2B de Google, libre (Apache 2.0), con LiteRT-LM. Describe
 * imágenes y responde sin internet en cualquier móvil de 64 bits con unos 6 GB
 * de RAM o más. El modelo (2,6 GB) se baja una vez con el gestor de descargas
 * del sistema (sigue aunque se cierre la app y se reanuda si se corta).
 */
object ModeloLocal {

	const val NOMBRE = "Gemma 4 E2B"
	private const val ARCHIVO = "gemma-4-E2B-it.litertlm"
	private const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$ARCHIVO"
	const val TAMANO = 2_588_147_712L
	private const val CLAVE_DESCARGA = "ia_descarga_modelo"

	enum class Estado { NO_DESCARGADO, DESCARGANDO, DESCARGADO, NO_COMPATIBLE }

	fun archivo(c: Context): File = File(c.getExternalFilesDir("modelos") ?: File(c.filesDir, "modelos"), ARCHIVO)

	/** Solo móviles de 64 bits (la librería no existe para los de 32). */
	fun compatible() = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

	/** Memoria total del móvil en GB (aviso si es poca). */
	fun memoriaGb(c: Context): Double {
		val info = ActivityManager.MemoryInfo()
		(c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
		return info.totalMem / 1_073_741_824.0
	}

	private fun preferencias(c: Context) = androidx.preference.PreferenceManager.getDefaultSharedPreferences(c)

	private fun descargas(c: Context) = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

	fun estado(c: Context): Estado {
		if (!compatible()) return Estado.NO_COMPATIBLE
		val id = preferencias(c).getLong(CLAVE_DESCARGA, -1)
		if (id >= 0) {
			when (estadoDescarga(c, id)?.first) {
				DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> return Estado.DESCARGANDO
				else -> preferencias(c).edit().remove(CLAVE_DESCARGA).apply()
			}
		}
		val f = archivo(c)
		return if (f.exists() && f.length() == TAMANO) Estado.DESCARGADO else Estado.NO_DESCARGADO
	}

	/** (estado, porcentaje) de la descarga en curso. */
	private fun estadoDescarga(c: Context, id: Long): Pair<Int, Int>? =
		descargas(c).query(DownloadManager.Query().setFilterById(id))?.use { cur ->
			if (!cur.moveToFirst()) return null
			val estado = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
			val bajado = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
			estado to (bajado * 100 / TAMANO).toInt()
		}

	fun porcentaje(c: Context): Int {
		val id = preferencias(c).getLong(CLAVE_DESCARGA, -1)
		return if (id >= 0) estadoDescarga(c, id)?.second ?: 0 else 0
	}

	/** Empieza a bajar el modelo (con aviso en la notificación). soloWifi: no gastar datos. */
	fun descargar(c: Context, soloWifi: Boolean) {
		val destino = archivo(c)
		destino.parentFile?.mkdirs()
		destino.delete()
		val peticion = DownloadManager.Request(Uri.parse(URL))
			.setTitle(c.getString(R.string.ia_local_descarga_titulo, NOMBRE))
			.setDescription(c.getString(R.string.app_name))
			.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
			.setDestinationUri(Uri.fromFile(destino))
			.setAllowedOverMetered(!soloWifi)
			.setAllowedOverRoaming(false)
		val id = descargas(c).enqueue(peticion)
		preferencias(c).edit().putLong(CLAVE_DESCARGA, id).apply()
	}

	fun cancelar(c: Context) {
		val id = preferencias(c).getLong(CLAVE_DESCARGA, -1)
		if (id >= 0) descargas(c).remove(id)
		preferencias(c).edit().remove(CLAVE_DESCARGA).apply()
		archivo(c).delete()
	}

	/** Cambió en Ajustes procesador o tarjeta gráfica: se vuelve a cargar la próxima vez. */
	fun reiniciar() = cerrar()

	fun borrar(c: Context) {
		cerrar()
		archivo(c).delete()
	}

	// --- Respuestas -------------------------------------------------------------------

	private var motor: com.google.ai.edge.litertlm.Engine? = null
	private val cerrojo = Mutex()

	private fun cerrar() {
		runCatching { motor?.close() }
		motor = null
	}

	/**
	 * Carga el modelo (la primera vez tarda unos segundos). Con el procesador,
	 * que funciona en todos los móviles; con la tarjeta gráfica si se activa en
	 * Ajustes (más rápida, pero en algunos móviles su controlador falla).
	 */
	private fun cargar(c: Context): com.google.ai.edge.litertlm.Engine {
		motor?.let { return it }
		val ruta = archivo(c).absolutePath
		val cache = File(c.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
		fun crear(gpu: Boolean): com.google.ai.edge.litertlm.Engine {
			val backend = if (gpu) com.google.ai.edge.litertlm.Backend.GPU() else com.google.ai.edge.litertlm.Backend.CPU()
			val config = com.google.ai.edge.litertlm.EngineConfig(
				modelPath = ruta,
				backend = backend,
				visionBackend = backend,
				maxNumTokens = 8192,
				maxNumImages = 1,
				cacheDir = cache,
			)
			return com.google.ai.edge.litertlm.Engine(config).also { it.initialize() }
		}
		val usarGpu = androidx.preference.PreferenceManager.getDefaultSharedPreferences(c).getBoolean("ia_local_gpu", false)
		val m = if (!usarGpu) crear(gpu = false) else try {
			crear(gpu = true)
		} catch (e: Throwable) {
			if (e is OutOfMemoryError) throw e
			crear(gpu = false)
		}
		motor = m
		return m
	}

	suspend fun preguntar(c: Context, sistema: String, conversacion: List<AsistenteIA.Mensaje>, imagen: File?, alAvisar: (String) -> Unit): String {
		if (estado(c) != Estado.DESCARGADO) throw AsistenteIA.ErrorIA(c.getString(R.string.ia_local_falta))
		return cerrojo.withLock {
			withContext(Dispatchers.Default) {
				try {
					if (motor == null) alAvisar(c.getString(R.string.ia_local_cargando))
					val m = cargar(c)
					val texto = buildString {
						appendLine(sistema)
						appendLine()
						for (msg in conversacion) {
							append(if (msg.deUsuario) c.getString(R.string.ia_turno_usuario) else c.getString(R.string.ia_turno_asistente))
							append(": ")
							appendLine(msg.texto)
						}
					}
					val partes = mutableListOf<com.google.ai.edge.litertlm.Content>()
					if (imagen != null) partes += com.google.ai.edge.litertlm.Content.ImageFile(imagen.absolutePath)
					partes += com.google.ai.edge.litertlm.Content.Text(texto)
					m.createConversation().use { conversacionModelo ->
						val respuesta = conversacionModelo.sendMessage(com.google.ai.edge.litertlm.Contents.of(partes))
						respuesta.contents.contents.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>().joinToString("") { it.text }
					}
				} catch (e: AsistenteIA.ErrorIA) {
					throw e
				} catch (e: OutOfMemoryError) {
					cerrar()
					throw AsistenteIA.ErrorIA(c.getString(R.string.ia_local_memoria))
				} catch (e: Throwable) {
					cerrar()
					throw AsistenteIA.ErrorIA(c.getString(R.string.ia_local_error, e.message ?: e.javaClass.simpleName))
				}
			}
		}
	}
}
