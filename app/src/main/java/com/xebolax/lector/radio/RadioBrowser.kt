package com.xebolax.lector.radio

import com.xebolax.lector.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * El directorio comunitario de emisoras radio-browser.info (libre, sin
 * cuenta). Tiene varios servidores iguales: se descubren por DNS
 * (all.api.radio-browser.info) y, si uno falla, se prueba el siguiente.
 * Pide que cada app se identifique con su nombre en el User-Agent.
 */
object RadioBrowser {

	private const val TODOS = "all.api.radio-browser.info"
	private val RESERVA = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info", "at1.api.radio-browser.info", "nl1.api.radio-browser.info")
	private const val CONECTAR_MS = 8_000
	private const val LEER_MS = 20_000
	/** Cuántas emisoras se piden de cada vez (con «Cargar más» al final). */
	const val PAGINA = 100

	@Volatile
	private var servidores: List<String>? = null

	/** No hay conexión con ningún servidor del directorio. */
	class SinServidor(causa: Throwable?) : Exception(causa?.message, causa)

	private fun agente() = "LectorUniversal/${BuildConfig.VERSION_NAME} (Android)"

	/** Servidores del directorio, en orden aleatorio para repartir la carga. */
	private fun servidores(): List<String> = servidores ?: run {
		val encontrados = runCatching {
			InetAddress.getAllByName(TODOS).mapNotNull { runCatching { it.canonicalHostName }.getOrNull() }
				.filter { it.endsWith(".radio-browser.info") && it != TODOS }
		}.getOrDefault(emptyList())
		(encontrados.distinct().shuffled() + RESERVA.shuffled()).distinct().also { servidores = it }
	}

	private fun codificar(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

	/** GET a la API; si un servidor falla, se pasa al siguiente (y ese queda el primero para la próxima). */
	private fun pedir(ruta: String, parametros: Map<String, Any?> = emptyMap()): String {
		val consulta = parametros.filterValues { it != null && it.toString().isNotEmpty() }
			.map { (k, v) -> "$k=${codificar(v.toString())}" }.joinToString("&")
		var ultimo: Throwable? = null
		val lista = servidores()
		for (servidor in lista) {
			try {
				val url = URL("https://$servidor/$ruta" + if (consulta.isNotEmpty()) "?$consulta" else "")
				val c = url.openConnection() as HttpURLConnection
				c.connectTimeout = CONECTAR_MS
				c.readTimeout = LEER_MS
				c.setRequestProperty("User-Agent", agente())
				c.setRequestProperty("Accept", "application/json")
				try {
					if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
					val texto = c.inputStream.bufferedReader().use { it.readText() }
					if (servidor != lista.first()) servidores = listOf(servidor) + lista.filter { it != servidor }
					return texto
				} finally {
					c.disconnect()
				}
			} catch (e: Exception) {
				ultimo = e
			}
		}
		throw SinServidor(ultimo)
	}

	private fun lista(texto: String): List<Emisora> {
		val a = JSONArray(texto)
		return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Emisora.deApi(it) } }
			.filter { it.uuid.isNotBlank() && it.url.isNotBlank() }
			.distinctBy { it.uuid }
	}

	/** Orden de una búsqueda o de una lista del directorio. */
	enum class Orden(val api: String, val inverso: Boolean) {
		RELEVANCIA("", false),
		CLICS("clickcount", true),
		VOTOS("votes", true),
		NOMBRE("name", false),
		CALIDAD("bitrate", true),
		TENDENCIA("clicktrend", true),
		SONANDO("clicktimestamp", true),
		CAMBIOS("changetimestamp", true),
	}

	/** Filtros de búsqueda (todos opcionales). */
	data class Filtro(
		val nombre: String = "",
		val codigoPais: String = "",
		val idioma: String = "",
		val genero: String = "",
		val codec: String = "",
		val bitrateMin: Int = 0,
		val orden: Orden = Orden.CLICS,
		val ocultarRotas: Boolean = true,
	)

	/** Busca emisoras (o una lista del directorio: más escuchadas, de un país, de un género…). */
	suspend fun buscar(f: Filtro, desde: Int = 0, cuantas: Int = PAGINA): List<Emisora> = withContext(Dispatchers.IO) {
		lista(pedir("json/stations/search", mapOf(
			"name" to f.nombre.trim(),
			"countrycode" to f.codigoPais,
			"language" to f.idioma.lowercase(Locale.ROOT),
			"languageExact" to if (f.idioma.isNotBlank()) "true" else null,
			"tag" to f.genero.lowercase(Locale.ROOT),
			"tagExact" to if (f.genero.isNotBlank()) "true" else null,
			"codec" to f.codec,
			"bitrateMin" to f.bitrateMin.takeIf { it > 0 },
			"order" to f.orden.api,
			"reverse" to if (f.orden.inverso) "true" else null,
			"hidebroken" to if (f.ocultarRotas) "true" else null,
			"offset" to desde.takeIf { it > 0 },
			"limit" to cuantas,
		)))
	}

	/** Las emisoras que se conocen por su identificador (para poner al día las favoritas). */
	suspend fun porUuid(uuids: List<String>): List<Emisora> = withContext(Dispatchers.IO) {
		if (uuids.isEmpty()) return@withContext emptyList()
		uuids.chunked(80).flatMap { trozo -> lista(pedir("json/stations/byuuid", mapOf("uuids" to trozo.joinToString(",")))) }
	}

	/** Géneros con más emisoras. */
	suspend fun generos(ocultarRotas: Boolean = true): List<Categoria> = withContext(Dispatchers.IO) {
		val a = JSONArray(pedir("json/tags", mapOf("order" to "stationcount", "reverse" to "true", "hidebroken" to if (ocultarRotas) "true" else null, "limit" to 600)))
		(0 until a.length()).mapNotNull { a.optJSONObject(it) }
			.map { Categoria(it.optString("name").trim(), it.optString("name").trim(), it.optInt("stationcount")) }
			.filter { it.clave.length in 2..40 && it.emisoras >= 5 }
	}

	/** Países, con su nombre en el idioma de la app, por orden alfabético. */
	suspend fun paises(ocultarRotas: Boolean = true): List<Categoria> = withContext(Dispatchers.IO) {
		val a = JSONArray(pedir("json/countries", mapOf("hidebroken" to if (ocultarRotas) "true" else null)))
		(0 until a.length()).mapNotNull { a.optJSONObject(it) }
			.mapNotNull { o ->
				val codigo = o.optString("iso_3166_1").uppercase()
				if (codigo.length != 2) return@mapNotNull null
				val nombre = Locale("", codigo).displayCountry.takeIf { it.isNotBlank() && it != codigo } ?: o.optString("name")
				Categoria(codigo, nombre, o.optInt("stationcount"))
			}
			.filter { it.emisoras > 0 }
			.groupBy { it.clave }.map { (_, l) -> l.first().copy(emisoras = l.sumOf { it.emisoras }) }
			.sortedWith(compareBy(java.text.Collator.getInstance()) { it.nombre })
	}

	/** Idiomas con su nombre en el idioma de la app (si radio-browser da su código). */
	suspend fun idiomas(ocultarRotas: Boolean = true): List<Categoria> = withContext(Dispatchers.IO) {
		val a = JSONArray(pedir("json/languages", mapOf("order" to "stationcount", "reverse" to "true", "hidebroken" to if (ocultarRotas) "true" else null)))
		(0 until a.length()).mapNotNull { a.optJSONObject(it) }
			.map { o ->
				val nombre = o.optString("name").trim()
				val codigo = o.optString("iso_639").trim()
				val legible = codigo.takeIf { it.length in 2..3 }?.let { Locale(it).displayLanguage }?.takeIf { it.isNotBlank() && !it.equals(codigo, true) }
					?.replaceFirstChar { it.titlecase() } ?: nombre.replaceFirstChar { it.titlecase() }
				Categoria(nombre, legible, o.optInt("stationcount"))
			}
			.filter { it.clave.isNotBlank() && it.emisoras >= 3 }
	}

	/** Avisa al directorio de que se escucha (cuenta para «Más escuchadas»). No importa si falla. */
	suspend fun contarClic(uuid: String) = withContext(Dispatchers.IO) {
		runCatching { pedir("json/url/${codificar(uuid)}") }
	}

	/** Vota una emisora. Devuelve null si salió bien o el mensaje del servidor (por ejemplo, si ya se votó hace poco). */
	suspend fun votar(uuid: String): String? = withContext(Dispatchers.IO) {
		val o = JSONObject(pedir("json/vote/${codificar(uuid)}"))
		if (o.optBoolean("ok")) null else o.optString("message")
	}
}
