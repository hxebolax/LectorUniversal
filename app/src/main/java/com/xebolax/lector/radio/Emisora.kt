package com.xebolax.lector.radio

import org.json.JSONObject
import java.util.Locale

/**
 * Una emisora de radio. Las del directorio comunitario radio-browser.info
 * llevan su identificador (stationuuid); las añadidas a mano, «propia:…».
 */
data class Emisora(
	val uuid: String,
	val nombre: String,
	/** Dirección del sonido (la ya resuelta de radio-browser, sin listas .pls ni .m3u). */
	val url: String,
	val web: String = "",
	val pais: String = "",
	val codigoPais: String = "",
	val region: String = "",
	val idioma: String = "",
	val etiquetas: String = "",
	val codec: String = "",
	/** kbps (0 si no se sabe). */
	val bitrate: Int = 0,
	val hls: Boolean = false,
	val votos: Int = 0,
	val clics: Int = 0,
	/** Cómo han cambiado los clics en el último día (positivo: sube). */
	val tendencia: Int = 0,
	/** La última comprobación de radio-browser la encontró funcionando. */
	val funciona: Boolean = true,
	val logo: String = "",
) {
	val propia get() = uuid.startsWith(PROPIA)

	/** Id del audio para el reproductor (negativo y único, no choca con libros ni episodios). */
	val idAudio: Long get() = -5_000_000_000L - (uuid.hashCode().toLong() and 0x7fffffff)

	/** El país en el idioma de la app (radio-browser lo da en inglés). */
	fun paisLegible(): String =
		codigoPais.takeIf { it.length == 2 }?.let { Locale("", it).displayCountry }?.takeIf { it.isNotBlank() && !it.equals(codigoPais, true) } ?: pais

	/** Los primeros géneros (radio-browser los separa con comas). */
	fun generos(max: Int = 3): String = etiquetas.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct().take(max).joinToString(", ")

	/** «MP3, 128 kbps». */
	fun calidad(): String = listOfNotNull(codec.takeIf { it.isNotBlank() && !it.equals("unknown", true) }?.uppercase(), bitrate.takeIf { it > 0 }?.let { "$it kbps" })
		.joinToString(", ")

	fun json(): JSONObject = JSONObject()
		.put("uuid", uuid).put("nombre", nombre).put("url", url).put("web", web).put("pais", pais).put("codigoPais", codigoPais)
		.put("region", region).put("idioma", idioma).put("etiquetas", etiquetas).put("codec", codec).put("bitrate", bitrate).put("hls", hls)
		.put("votos", votos).put("clics", clics).put("tendencia", tendencia).put("funciona", funciona).put("logo", logo)

	companion object {
		const val PROPIA = "propia:"

		/** De nuestro formato (base de datos, copias de seguridad). */
		fun deJson(o: JSONObject) = Emisora(
			uuid = o.optString("uuid"), nombre = o.optString("nombre"), url = o.optString("url"), web = o.optString("web"),
			pais = o.optString("pais"), codigoPais = o.optString("codigoPais"), region = o.optString("region"), idioma = o.optString("idioma"),
			etiquetas = o.optString("etiquetas"), codec = o.optString("codec"), bitrate = o.optInt("bitrate"), hls = o.optBoolean("hls"),
			votos = o.optInt("votos"), clics = o.optInt("clics"), tendencia = o.optInt("tendencia"), funciona = o.optBoolean("funciona", true),
			logo = o.optString("logo"),
		)

		fun deJson(texto: String): Emisora? = runCatching { deJson(JSONObject(texto)) }.getOrNull()?.takeIf { it.uuid.isNotBlank() && it.url.isNotBlank() }

		/** De la API de radio-browser.info. */
		fun deApi(o: JSONObject) = Emisora(
			uuid = o.optString("stationuuid"),
			nombre = o.optString("name").trim().ifBlank { o.optString("url") },
			url = o.optString("url_resolved").ifBlank { o.optString("url") },
			web = o.optString("homepage"),
			pais = o.optString("country"),
			codigoPais = o.optString("countrycode").uppercase(),
			region = o.optString("state"),
			idioma = o.optString("language"),
			etiquetas = o.optString("tags"),
			codec = o.optString("codec"),
			bitrate = o.optInt("bitrate"),
			hls = o.optInt("hls") == 1,
			votos = o.optInt("votes"),
			clics = o.optInt("clickcount"),
			tendencia = o.optInt("clicktrend"),
			funciona = o.optInt("lastcheckok", 1) == 1,
			logo = o.optString("favicon"),
		)

		/** Una emisora añadida a mano (su id sale de la dirección, así no se repite). */
		/** hls: si se sabe (por su contenido); si no, por la extensión .m3u8. */
		fun propia(nombre: String, url: String, genero: String = "", web: String = "", hls: Boolean? = null) = Emisora(
			uuid = PROPIA + Integer.toHexString(url.trim().lowercase().hashCode()),
			nombre = nombre.trim().ifBlank { url.trim() },
			url = url.trim(),
			etiquetas = genero.trim(),
			web = web.trim(),
			hls = hls ?: url.substringBefore('?').endsWith(".m3u8", ignoreCase = true),
		)
	}
}

/** Un género, país o idioma del directorio, con cuántas emisoras tiene. */
data class Categoria(
	/** Lo que se manda a la API (nombre del género o del idioma, código del país). */
	val clave: String,
	/** Lo que se dice (país e idioma, en el idioma de la app). */
	val nombre: String,
	val emisoras: Int,
)
