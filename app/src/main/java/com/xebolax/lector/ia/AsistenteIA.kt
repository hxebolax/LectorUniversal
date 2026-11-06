package com.xebolax.lector.ia

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import com.xebolax.lector.R
import com.xebolax.lector.datos.Preferencias
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Inteligencia artificial para describir imágenes, resumir y responder
 * preguntas sobre el libro. Tres caminos:
 *
 * - Descargada en la app: Gemma 4 E2B (ModeloLocal), como en la app de
 *   Windows. Sin internet, en cualquier móvil de 64 bits potente.
 * - En el propio móvil: Gemini Nano (ML Kit GenAI), que traen algunos móviles
 *   recientes (Pixel 9 o posterior, Galaxy S25 o posterior...). Sin internet,
 *   nada sale del móvil. La primera vez el sistema baja el modelo.
 * - En internet con tu propia clave: Gemini (Google), OpenAI o Claude
 *   (Anthropic). Funciona en cualquier móvil; la imagen o el texto se envían
 *   a ese servicio.
 *
 * Ajustes, Inteligencia artificial: «Automático» usa el móvil si puede y si no
 * la primera clave que haya.
 */
object AsistenteIA {

	enum class Proveedor { LOCAL, DISPOSITIVO, GEMINI, OPENAI, CLAUDE }

	/** Un turno de la conversación (la imagen va siempre con la primera pregunta). */
	class Mensaje(val deUsuario: Boolean, val texto: String)

	class ErrorIA(mensaje: String) : Exception(mensaje)

	const val MODELO_GEMINI = "gemini-flash-latest"
	const val MODELO_OPENAI = "gpt-5-mini"
	const val MODELO_CLAUDE = "claude-haiku-5-5"

	/** Proveedor que se usará, o null si no hay ninguno configurado. */
	suspend fun elegir(c: Context): Proveedor? {
		val p = Preferencias(c)
		return when (p.iaProveedor) {
			"local" -> Proveedor.LOCAL
			"dispositivo" -> Proveedor.DISPOSITIVO
			"gemini" -> Proveedor.GEMINI
			"openai" -> Proveedor.OPENAI
			"claude" -> Proveedor.CLAUDE
			"ninguno" -> null
			else -> when {
				ModeloLocal.estado(c) == ModeloLocal.Estado.DESCARGADO -> Proveedor.LOCAL
				Nano.estado() in listOf(Nano.DISPONIBLE, Nano.DESCARGABLE, Nano.DESCARGANDO) -> Proveedor.DISPOSITIVO
				p.iaClaveGemini.isNotBlank() -> Proveedor.GEMINI
				p.iaClaveOpenai.isNotBlank() -> Proveedor.OPENAI
				p.iaClaveClaude.isNotBlank() -> Proveedor.CLAUDE
				else -> null
			}
		}
	}

	fun nombre(c: Context, proveedor: Proveedor): String = c.getString(
		when (proveedor) {
			Proveedor.LOCAL -> R.string.ia_local
			Proveedor.DISPOSITIVO -> R.string.ia_dispositivo
			Proveedor.GEMINI -> R.string.ia_gemini
			Proveedor.OPENAI -> R.string.ia_openai
			Proveedor.CLAUDE -> R.string.ia_claude
		}
	)

	/** Instrucciones generales: idioma de la app y respuestas pensadas para escuchar. */
	fun instrucciones(c: Context): String {
		val idioma = Locale.getDefault().getDisplayLanguage(Locale.getDefault())
		return c.getString(R.string.ia_instrucciones, idioma)
	}

	/**
	 * Envía la conversación (con una imagen opcional en la primera pregunta) y
	 * devuelve la respuesta. alAvisar dice en qué está (descargando el modelo...).
	 */
	suspend fun preguntar(
		c: Context,
		conversacion: List<Mensaje>,
		imagen: File? = null,
		alAvisar: (String) -> Unit = {},
	): String {
		val proveedor = elegir(c) ?: throw ErrorIA(c.getString(R.string.ia_sin_configurar))
		val sistema = instrucciones(c)
		val p = Preferencias(c)
		val respuesta = when (proveedor) {
			Proveedor.LOCAL -> ModeloLocal.preguntar(c, sistema, conversacion, imagen, alAvisar)
			Proveedor.DISPOSITIVO -> Nano.preguntar(c, sistema, conversacion, imagen, alAvisar)
			Proveedor.GEMINI -> withContext(Dispatchers.IO) { gemini(c, p.iaClaveGemini, p.iaModeloGemini, sistema, conversacion, imagen) }
			Proveedor.OPENAI -> withContext(Dispatchers.IO) { openai(c, p.iaClaveOpenai, p.iaModeloOpenai, sistema, conversacion, imagen) }
			Proveedor.CLAUDE -> withContext(Dispatchers.IO) { claude(c, p.iaClaveClaude, p.iaModeloClaude, sistema, conversacion, imagen) }
		}
		return respuesta.trim().ifBlank { throw ErrorIA(c.getString(R.string.ia_sin_respuesta)) }
	}

	/** Máximo de texto del libro que se envía de una vez (el móvil admite mucho menos). */
	suspend fun maximoTexto(c: Context): Int = when (elegir(c)) {
		Proveedor.DISPOSITIVO -> 9_000
		Proveedor.LOCAL -> 18_000
		else -> 120_000
	}

	// --- Servicios en internet ---------------------------------------------------------

	private fun base64(imagen: File) = Base64.encodeToString(imagen.readBytes(), Base64.NO_WRAP)

	private fun gemini(c: Context, clave: String, modelo: String, sistema: String, conversacion: List<Mensaje>, imagen: File?): String {
		if (clave.isBlank()) throw ErrorIA(c.getString(R.string.ia_falta_clave, c.getString(R.string.ia_gemini)))
		val contenidos = JSONArray()
		conversacion.forEachIndexed { i, m ->
			val partes = JSONArray()
			if (i == 0 && imagen != null) partes.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", base64(imagen))))
			partes.put(JSONObject().put("text", m.texto))
			contenidos.put(JSONObject().put("role", if (m.deUsuario) "user" else "model").put("parts", partes))
		}
		val cuerpo = JSONObject()
			.put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sistema))))
			.put("contents", contenidos)
		val url = "https://generativelanguage.googleapis.com/v1beta/models/${modelo.ifBlank { MODELO_GEMINI }}:generateContent"
		val r = enviar(c, url, mapOf("x-goog-api-key" to clave), cuerpo)
		val partes = r.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
		return (0 until partes.length()).joinToString("") { partes.optJSONObject(it)?.optString("text").orEmpty() }
	}

	private fun openai(c: Context, clave: String, modelo: String, sistema: String, conversacion: List<Mensaje>, imagen: File?): String {
		if (clave.isBlank()) throw ErrorIA(c.getString(R.string.ia_falta_clave, c.getString(R.string.ia_openai)))
		val mensajes = JSONArray().put(JSONObject().put("role", "system").put("content", sistema))
		conversacion.forEachIndexed { i, m ->
			if (i == 0 && imagen != null) {
				val partes = JSONArray()
					.put(JSONObject().put("type", "text").put("text", m.texto))
					.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + base64(imagen))))
				mensajes.put(JSONObject().put("role", "user").put("content", partes))
			} else mensajes.put(JSONObject().put("role", if (m.deUsuario) "user" else "assistant").put("content", m.texto))
		}
		val cuerpo = JSONObject().put("model", modelo.ifBlank { MODELO_OPENAI }).put("messages", mensajes)
		val r = enviar(c, "https://api.openai.com/v1/chat/completions", mapOf("Authorization" to "Bearer $clave"), cuerpo)
		return r.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
	}

	private fun claude(c: Context, clave: String, modelo: String, sistema: String, conversacion: List<Mensaje>, imagen: File?): String {
		if (clave.isBlank()) throw ErrorIA(c.getString(R.string.ia_falta_clave, c.getString(R.string.ia_claude)))
		val mensajes = JSONArray()
		conversacion.forEachIndexed { i, m ->
			val partes = JSONArray()
			if (i == 0 && imagen != null) {
				partes.put(JSONObject().put("type", "image").put("source", JSONObject()
					.put("type", "base64").put("media_type", "image/jpeg").put("data", base64(imagen))))
			}
			partes.put(JSONObject().put("type", "text").put("text", m.texto))
			mensajes.put(JSONObject().put("role", if (m.deUsuario) "user" else "assistant").put("content", partes))
		}
		val cuerpo = JSONObject()
			.put("model", modelo.ifBlank { MODELO_CLAUDE })
			.put("max_tokens", 2000)
			.put("system", sistema)
			.put("messages", mensajes)
		val r = enviar(c, "https://api.anthropic.com/v1/messages", mapOf("x-api-key" to clave, "anthropic-version" to "2023-06-01"), cuerpo)
		val partes = r.optJSONArray("content") ?: return ""
		return (0 until partes.length()).mapNotNull { partes.optJSONObject(it) }.filter { it.optString("type") == "text" }
			.joinToString("") { it.optString("text") }
	}

	private fun enviar(c: Context, url: String, cabeceras: Map<String, String>, cuerpo: JSONObject): JSONObject {
		val conexion = URL(url).openConnection() as HttpURLConnection
		try {
			conexion.requestMethod = "POST"
			conexion.connectTimeout = 20_000
			conexion.readTimeout = 120_000
			conexion.doOutput = true
			conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
			cabeceras.forEach { (k, v) -> conexion.setRequestProperty(k, v) }
			conexion.outputStream.use { it.write(cuerpo.toString().toByteArray()) }
			val codigo = conexion.responseCode
			val texto = (if (codigo in 200..299) conexion.inputStream else conexion.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
			if (codigo !in 200..299) {
				// El mensaje de error del servicio (clave incorrecta, sin saldo, modelo que no existe...)
				val detalle = runCatching {
					val j = JSONObject(texto)
					j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: j.optString("message")
				}.getOrNull().orEmpty().ifBlank { texto.take(300) }
				throw ErrorIA(c.getString(R.string.ia_error_servicio, codigo, detalle))
			}
			return JSONObject(texto)
		} catch (e: ErrorIA) {
			throw e
		} catch (e: java.io.IOException) {
			throw ErrorIA(c.getString(R.string.ia_sin_conexion, e.message.orEmpty()))
		} finally {
			conexion.disconnect()
		}
	}

	// --- En el móvil: Gemini Nano --------------------------------------------------------

	/** Gemini Nano con ML Kit GenAI (Android 8 o superior y solo en móviles que lo traen). */
	object Nano {
		const val NO_DISPONIBLE = 0
		const val DESCARGABLE = 1
		const val DESCARGANDO = 2
		const val DISPONIBLE = 3

		private var cliente: com.google.mlkit.genai.prompt.GenerativeModel? = null

		private fun modelo(): com.google.mlkit.genai.prompt.GenerativeModel? {
			if (Build.VERSION.SDK_INT < 26) return null
			cliente?.let { return it }
			return runCatching { com.google.mlkit.genai.prompt.Generation.getClient() }.getOrNull()?.also { cliente = it }
		}

		/** Estado de la IA del móvil (NO_DISPONIBLE si no la tiene). */
		suspend fun estado(): Int {
			val m = modelo() ?: return NO_DISPONIBLE
			return runCatching {
				when (m.checkStatus()) {
					com.google.mlkit.genai.common.FeatureStatus.AVAILABLE -> DISPONIBLE
					com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE -> DESCARGABLE
					com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING -> DESCARGANDO
					else -> NO_DISPONIBLE
				}
			}.getOrDefault(NO_DISPONIBLE)
		}

		/** Baja el modelo si hace falta (lo baja el sistema, una sola vez). */
		private suspend fun preparar(c: Context, alAvisar: (String) -> Unit) {
			val m = modelo() ?: throw ErrorIA(c.getString(R.string.ia_dispositivo_no))
			when (estado()) {
				DISPONIBLE -> return
				NO_DISPONIBLE -> throw ErrorIA(c.getString(R.string.ia_dispositivo_no))
				else -> {
					alAvisar(c.getString(R.string.ia_descargando_modelo))
					var fallo: String? = null
					m.download().collect { s ->
						if (s is com.google.mlkit.genai.common.DownloadStatus.DownloadFailed) fallo = s.e.message ?: s.toString()
					}
					fallo?.let { throw ErrorIA(c.getString(R.string.ia_descarga_fallida, it)) }
				}
			}
		}

		suspend fun preguntar(c: Context, sistema: String, conversacion: List<Mensaje>, imagen: File?, alAvisar: (String) -> Unit): String {
			preparar(c, alAvisar)
			val m = modelo() ?: throw ErrorIA(c.getString(R.string.ia_dispositivo_no))
			// El modelo del móvil no guarda la conversación: se le pasa entera como texto
			val texto = buildString {
				appendLine(sistema)
				appendLine()
				for (msg in conversacion) {
					append(if (msg.deUsuario) c.getString(R.string.ia_turno_usuario) else c.getString(R.string.ia_turno_asistente))
					append(": ")
					appendLine(msg.texto)
				}
				append(c.getString(R.string.ia_turno_asistente)).append(":")
			}
			val peticion = if (imagen != null) {
				val mapa = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(imagen.path) } ?: throw ErrorIA(c.getString(R.string.imagen_no_disponible))
				com.google.mlkit.genai.prompt.generateContentRequest(
					com.google.mlkit.genai.prompt.ImagePart(mapa), com.google.mlkit.genai.prompt.TextPart(texto),
				) {}
			} else com.google.mlkit.genai.prompt.generateContentRequest(com.google.mlkit.genai.prompt.TextPart(texto)) {}
			return try {
				m.generateContent(peticion).candidates.firstOrNull()?.text.orEmpty()
			} catch (e: com.google.mlkit.genai.common.GenAiException) {
				throw ErrorIA(c.getString(R.string.ia_error_dispositivo, e.message.orEmpty()))
			}
		}
	}
}
