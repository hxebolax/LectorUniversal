package com.xebolax.lector.voz

import android.content.Context
import androidx.preference.PreferenceManager
import com.xebolax.lector.R
import com.xebolax.lector.datos.Secretos
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Voces de ElevenLabs (en la nube) con la clave de API del propio usuario: la
 * app no tiene servidor ni clave suya, llama directamente a api.elevenlabs.io.
 * Es opcional: se elige como motor «ElevenLabs» en Lectura en voz alta o en la
 * voz propia de un libro. Lo que se lee se envía a ElevenLabs (y lo guardan en
 * el historial de la cuenta); cuesta créditos de la cuenta del usuario.
 *
 * - Se pide un trozo cada vez (el plan gratuito admite 2 a la vez) y cada
 *   audio se guarda en la caché: volver atrás o releer no gasta créditos.
 * - Velocidad y tono los pone el reproductor (sin volver a pedir el audio).
 * - Si falla (sin créditos, clave mala, sin red), quien lo usa sigue con la
 *   voz del móvil si así se eligió en Ajustes.
 */
object ElevenLabs {

	/** Valor del motor en los ajustes de voz. */
	const val MOTOR = "elevenlabs"
	const val CLAVE = "elevenlabs_clave"
	private const val API = "https://api.elevenlabs.io"
	const val MODELO_LIBROS = "eleven_multilingual_v2"
	const val MODELO_RAPIDO = "eleven_flash_v2_5"
	/** Tamaño de cada trozo (el máximo por petición es 10.000 en Multilingual v2). */
	const val TROZO = 1500

	/** Lo que ElevenLabs ha contestado mal: el tipo decide si se sigue intentando. */
	class Error(val tipo: Tipo, val detalle: String = "") : IOException(detalle) {
		enum class Tipo { CLAVE, CREDITOS, VOZ, PERMISOS, RED, OCUPADO, OTRO }
		/** Con estos no sirve volver a pedir: la clave, los créditos o la voz no van a cambiar solos. */
		val definitivo get() = tipo == Tipo.CLAVE || tipo == Tipo.CREDITOS || tipo == Tipo.VOZ || tipo == Tipo.PERMISOS
	}

	class Voz(val id: String, val nombre: String, val descripcion: String)

	class Cuenta(val plan: String, val usados: Long, val limite: Long, val renueva: Long)

	private val prefs = { c: Context -> PreferenceManager.getDefaultSharedPreferences(c) }

	fun clave(c: Context) = Secretos.leer(c, CLAVE).trim()
	fun configurada(c: Context) = clave(c).isNotEmpty()
	fun modelo(c: Context) = prefs(c).getString("el_modelo", MODELO_LIBROS) ?: MODELO_LIBROS
	/** Si falla, seguir con la voz del móvil (si no, se para la lectura). */
	fun seguirConMovil(c: Context) = prefs(c).getBoolean("el_al_fallar", true)
	fun esMotor(motor: String?) = motor == MOTOR

	/** El texto que se dice para cada error (por voz y en pantalla). */
	fun mensaje(c: Context, e: Throwable): String {
		val t = (e as? Error)?.tipo ?: Error.Tipo.RED
		return c.getString(when (t) {
			Error.Tipo.CLAVE -> R.string.el_error_clave
			Error.Tipo.CREDITOS -> R.string.el_error_creditos
			Error.Tipo.VOZ -> R.string.el_error_voz
			Error.Tipo.PERMISOS -> R.string.el_error_permisos
			Error.Tipo.RED -> R.string.el_error_red
			Error.Tipo.OCUPADO -> R.string.el_error_ocupado
			Error.Tipo.OTRO -> R.string.el_error_otro
		})
	}

	// --- Llamadas a la API ------------------------------------------------------------------

	private fun conexion(c: Context, ruta: String, metodo: String = "GET"): HttpURLConnection {
		val k = clave(c)
		if (k.isEmpty()) throw Error(Error.Tipo.CLAVE, "sin clave")
		return (URL(API + ruta).openConnection() as HttpURLConnection).apply {
			requestMethod = metodo
			connectTimeout = 15_000
			readTimeout = 60_000
			setRequestProperty("xi-api-key", k)
			setRequestProperty("Accept", if (ruta.startsWith("/v1/text-to-speech")) "audio/mpeg" else "application/json")
		}
	}

	/** Convierte la respuesta de error de ElevenLabs en un Error con su tipo. */
	private fun error(con: HttpURLConnection): Error {
		val codigo = con.responseCode
		val cuerpo = runCatching { con.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
		val detalle = runCatching { JSONObject(cuerpo).optJSONObject("detail") }.getOrNull()
		val clave = listOfNotNull(detalle?.optString("code"), detalle?.optString("status")).joinToString(" ")
		val tipo = when {
			codigo == 402 || "credits" in clave || "quota" in clave || "limit_reached" in clave -> Error.Tipo.CREDITOS
			codigo == 401 || "api_key" in clave -> Error.Tipo.CLAVE
			"voice" in clave && (codigo == 400 || codigo == 403 || codigo == 404) -> Error.Tipo.VOZ
			codigo == 403 -> Error.Tipo.PERMISOS
			codigo == 429 || codigo == 503 || "busy" in clave -> Error.Tipo.OCUPADO
			else -> Error.Tipo.OTRO
		}
		return Error(tipo, "$codigo ${detalle?.optString("message").orEmpty()}".trim())
	}

	private fun leerJson(c: Context, ruta: String): String {
		val con = conexion(c, ruta)
		try {
			if (con.responseCode !in 200..299) throw error(con)
			return con.inputStream.bufferedReader().readText()
		} catch (e: Error) {
			throw e
		} catch (e: IOException) {
			throw Error(Error.Tipo.RED, e.message.orEmpty())
		} finally {
			con.disconnect()
		}
	}

	/** Las voces que puede usar esta cuenta (las suyas y las predeterminadas que tenga). */
	fun voces(c: Context): List<Voz> {
		val lista = mutableListOf<Voz>()
		var pagina = ""
		repeat(10) {
			val ruta = "/v2/voices?page_size=100&include_total_count=false" + if (pagina.isNotEmpty()) "&next_page_token=" + URLEncoder.encode(pagina, "UTF-8") else ""
			val o = JSONObject(leerJson(c, ruta))
			val voces = o.optJSONArray("voices") ?: JSONArray()
			for (i in 0 until voces.length()) {
				val v = voces.optJSONObject(i) ?: continue
				val etiquetas = v.optJSONObject("labels")
				val descripcion = listOfNotNull(
					etiquetas?.optString("gender")?.takeIf { it.isNotBlank() },
					etiquetas?.optString("accent")?.takeIf { it.isNotBlank() },
					etiquetas?.optString("description")?.takeIf { it.isNotBlank() } ?: etiquetas?.optString("descriptive")?.takeIf { it.isNotBlank() },
				).joinToString(", ")
				lista += Voz(v.optString("voice_id"), v.optString("name"), descripcion)
			}
			pagina = o.optString("next_page_token")
			if (!o.optBoolean("has_more") || pagina.isEmpty()) return lista.filter { it.id.isNotBlank() }
		}
		return lista.filter { it.id.isNotBlank() }
	}

	fun cuenta(c: Context): Cuenta {
		val o = JSONObject(leerJson(c, "/v1/user/subscription"))
		return Cuenta(o.optString("tier"), o.optLong("character_count"), o.optLong("character_limit"), o.optLong("next_character_count_reset_unix"))
	}

	/** La voz de ElevenLabs que se usa si no se ha elegido ninguna: la primera de la cuenta (se recuerda). */
	fun vozPorDefecto(c: Context): String {
		prefs(c).getString("el_voz_defecto", "")?.takeIf { it.isNotBlank() }?.let { return it }
		val id = voces(c).firstOrNull()?.id ?: throw Error(Error.Tipo.VOZ, "sin voces")
		prefs(c).edit().putString("el_voz_defecto", id).apply()
		return id
	}

	/** Un identificador de voz de ElevenLabs (las del móvil tienen guiones e idioma: «es-es-x-…»). */
	fun esVozSuya(voz: String) = voz.isNotBlank() && Regex("[A-Za-z0-9]{16,32}").matches(voz)

	// --- Caché ----------------------------------------------------------------------------

	private fun carpetaCache(c: Context) = File(c.cacheDir, "elevenlabs").apply { mkdirs() }

	fun tamanoCache(c: Context): Long = carpetaCache(c).listFiles()?.sumOf { it.length() } ?: 0L

	fun vaciarCache(c: Context) {
		carpetaCache(c).listFiles()?.forEach { it.delete() }
	}

	/** Como mucho unos 300 MB: se borran los más antiguos. */
	private fun recortarCache(c: Context) {
		val archivos = carpetaCache(c).listFiles()?.sortedBy { it.lastModified() } ?: return
		var total = archivos.sumOf { it.length() }
		for (f in archivos) {
			if (total < 300L * 1024 * 1024) break
			total -= f.length()
			f.delete()
		}
	}

	private fun huella(vararg partes: String): String {
		val md = MessageDigest.getInstance("SHA-1").digest(partes.joinToString("\u0001").toByteArray())
		return md.joinToString("") { "%02x".format(it) }
	}

	// --- Síntesis ---------------------------------------------------------------------------

	/** Una a una: el plan gratuito solo admite 2 a la vez y así queda margen. */
	private val cola = Executors.newSingleThreadExecutor { r -> Thread(r, "ElevenLabs").apply { isDaemon = true } }

	/**
	 * Genera el audio (MP3) de un trozo en [destino]. [vigente] se mira justo
	 * antes de pedirlo: si la lectura ya va por otro sitio, no se pide (no se
	 * gastan créditos). Llama a [alTerminar] (en el hilo de ElevenLabs) con
	 * null si fue bien o con el error.
	 */
	fun sintetizar(
		c: Context, texto: String, voz: String, idioma: String?, anterior: String, siguiente: String,
		destino: File, vigente: () -> Boolean, alTerminar: (Throwable?) -> Unit,
	) {
		val app = c.applicationContext
		cola.execute {
			if (!vigente()) return@execute alTerminar(Error(Error.Tipo.OTRO, "ya no hace falta"))
			alTerminar(runCatching { generar(app, texto, voz, idioma, anterior, siguiente, destino, vigente) }.exceptionOrNull())
		}
	}

	private fun generar(c: Context, texto: String, vozPedida: String, idioma: String?, anterior: String, siguiente: String, destino: File, vigente: () -> Boolean = { true }) {
		val voz = if (esVozSuya(vozPedida)) vozPedida else vozPorDefecto(c)
		val modelo = modelo(c)
		val guardado = File(carpetaCache(c), huella(modelo, voz, idioma.orEmpty(), texto) + ".mp3")
		if (guardado.length() > 0) {
			guardado.copyTo(destino, overwrite = true)
			guardado.setLastModified(System.currentTimeMillis())
			return
		}
		val cuerpo = JSONObject().apply {
			put("text", texto)
			put("model_id", modelo)
			// Para que la entonación siga de un trozo al siguiente
			if (anterior.isNotBlank()) put("previous_text", anterior.takeLast(300))
			if (siguiente.isNotBlank()) put("next_text", siguiente.take(300))
			// Flash admite el idioma (Multilingual v2 lo averigua solo)
			if (modelo == MODELO_RAPIDO && !idioma.isNullOrBlank()) put("language_code", idioma.substringBefore('-').lowercase())
		}
		var espera = 1500L
		for (intento in 1..4) {
			val con = conexion(c, "/v1/text-to-speech/$voz?output_format=mp3_44100_64", "POST")
			try {
				con.doOutput = true
				con.setRequestProperty("Content-Type", "application/json")
				con.outputStream.use { it.write(cuerpo.toString().toByteArray()) }
				if (con.responseCode in 200..299) {
					val temporal = File(guardado.path + ".parte")
					con.inputStream.use { e -> temporal.outputStream().use { e.copyTo(it) } }
					if (temporal.length() == 0L) throw Error(Error.Tipo.OTRO, "audio vacío")
					temporal.renameTo(guardado)
					guardado.copyTo(destino, overwrite = true)
					recortarCache(c)
					return
				}
				val e = error(con)
				// Ocupado (demasiadas a la vez o el servicio saturado): esperar y volver a pedir
				if (e.tipo != Error.Tipo.OCUPADO || intento == 4) throw e
			} catch (e: Error) {
				throw e
			} catch (e: IOException) {
				if (intento == 4) throw Error(Error.Tipo.RED, e.message.orEmpty())
			} finally {
				con.disconnect()
			}
			Thread.sleep(espera)
			espera *= 2
			if (!vigente()) throw Error(Error.Tipo.OTRO, "ya no hace falta")
		}
	}

	/** Prueba una voz: dice el texto con ElevenLabs (o el error, que devuelve). */
	fun probar(c: Context, texto: String, voz: String, alTerminar: (File?, Throwable?) -> Unit) {
		val destino = File(c.cacheDir, "prueba_elevenlabs.mp3")
		sintetizar(c, texto, voz, null, "", "", destino, { true }) { e -> alTerminar(if (e == null) destino else null, e) }
	}
}
