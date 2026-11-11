package com.xebolax.lector.radio

import android.os.Build
import com.xebolax.lector.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cómo llegar al sonido de cualquier emisora, como los reproductores de
 * escritorio (BASS, VLC):
 * - agente de usuario propio (algunos servidores rechazan el de Android, «Dalvik»);
 * - redirecciones también de http a https y al revés (radiojar, mcvhost…);
 * - si el certificado de un https no vale (otro nombre, caducado), se prueba
 *   la misma dirección con http;
 * - el tipo se averigua por el contenido, no por la extensión: audio (MP3,
 *   AAC, Ogg, Opus, FLAC…), HLS aunque no acabe en .m3u8, listas M3U, PLS,
 *   ASX y XSPF (se sigue a lo que contienen) o una página web;
 * - y, si es una página web, se busca el sonido dentro (reproductores, enlaces
 *   y scripts de la página) y se comprueba cada candidato.
 */
object Flujos {

	/** Agente de usuario para la radio (reproductor, grabación y comprobaciones). Nunca uno de navegador: muchos Shoutcast devuelven su web. */
	val agente: String get() = "LectorUniversal/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"

	/** Una conexión abierta y si hubo que pasar de https a http. */
	class Abierta(val conexion: HttpURLConnection, val url: URL, val aHttp: Boolean)

	/** Error con un motivo que se puede explicar («http:500», «ssl», «red», «redirecciones»). */
	class ErrorFlujo(val motivo: String, causa: Throwable? = null) : IOException(motivo, causa)

	/**
	 * Abre la dirección siguiendo las redirecciones a mano (también entre
	 * http y https, como mucho 10) y, si el certificado de un https no vale,
	 * con http. Devuelve la conexión ya con su respuesta.
	 */
	fun abrir(url: String, icy: Boolean = false, conectar: Int = 8_000, leer: Int = 10_000): Abierta {
		var actual = URL(url.trim())
		var saltos = 0
		var aHttp = false
		while (true) {
			val c = actual.openConnection() as HttpURLConnection
			c.instanceFollowRedirects = false
			c.connectTimeout = conectar
			c.readTimeout = leer
			c.setRequestProperty("User-Agent", agente)
			c.setRequestProperty("Accept", "*/*")
			c.setRequestProperty("Icy-MetaData", if (icy) "1" else "0")
			val codigo = try {
				c.responseCode
			} catch (e: javax.net.ssl.SSLException) {
				c.disconnect()
				if (actual.protocol.equals("https", true) && !aHttp) {
					aHttp = true
					actual = URL("http", actual.host, actual.port, actual.file)
					continue
				}
				throw ErrorFlujo("ssl", e)
			} catch (e: IOException) {
				c.disconnect()
				// Servidores sin línea de estado HTTP (Shoutcast muy antiguos)
				if (e.message?.contains("status line", true) == true) throw ErrorFlujo("no_estandar", e)
				throw ErrorFlujo("red", e)
			}
			if (codigo in 300..399) {
				val destino = c.getHeaderField("Location")
				c.disconnect()
				if (destino.isNullOrBlank() || ++saltos > 10) throw ErrorFlujo("redirecciones")
				actual = URL(actual, destino)
				continue
			}
			if (codigo >= 400) {
				c.disconnect()
				throw ErrorFlujo("http:$codigo")
			}
			return Abierta(c, actual, aHttp)
		}
	}

	/** Lo que hay en una dirección. */
	data class Sonda(
		/** La dirección que hay que guardar (la original, o la de dentro de una lista, o la http si el https no valía). */
		val url: String,
		val hls: Boolean = false,
		/** Es una página web, no un sonido. */
		val web: Boolean = false,
		/** Nombre de la emisora que da el servidor (icy-name) y su calidad. */
		val nombre: String = "",
		val kbps: Int = 0,
		val formato: String = "",
		val generos: String = "",
		val webEmisora: String = "",
	)

	private fun lineasUtiles(texto: String) = texto.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

	/**
	 * Mira qué hay en la dirección: sonido (y su tipo), HLS, una lista (y se
	 * sigue a la primera emisora de dentro) o una página web.
	 */
	suspend fun sondear(url: String, profundidad: Int = 0): Sonda = withContext(Dispatchers.IO) {
		val a = abrir(url, icy = true)
		val c = a.conexion
		try {
			val tipo = c.contentType.orEmpty().lowercase()
			val bytes = c.inputStream.use { e ->
				val b = ByteArray(16 * 1024)
				var n = 0
				while (n < b.size) {
					val r = e.read(b, n, b.size - n)
					if (r < 0) break
					n += r
					// Para saber si es sonido bastan los primeros bytes
					if (n >= 4096 && (tipo.startsWith("audio/") && !tipo.contains("mpegurl") && !tipo.contains("scpls"))) break
				}
				b.copyOf(n)
			}
			val texto = String(bytes, Charsets.UTF_8)
			val inicio = texto.trimStart('﻿', ' ', '\r', '\n', '\t').take(512).lowercase()
			// La dirección a guardar: la original (los tokens de radiojar caducan) salvo si el https no valía
			val guardar = if (a.aHttp) URL("http", URL(url).host, URL(url).port, URL(url).file).toString() else url.trim()
			val nombre = c.getHeaderField("icy-name").orEmpty().trim().takeIf { it.isNotBlank() && !it.equals("no name", true) && it != "-" }.orEmpty()
			val kbps = c.getHeaderField("icy-br")?.substringBefore(',')?.trim()?.toIntOrNull() ?: 0
			val generos = c.getHeaderField("icy-genre").orEmpty().trim()
			val webIcy = c.getHeaderField("icy-url").orEmpty().trim()
			suspend fun seguir(dentro: String): Sonda {
				if (profundidad >= 3) throw ErrorFlujo("lista")
				return sondear(URL(a.url, dentro.trim()).toString(), profundidad + 1)
			}
			when {
				// HLS (aunque no acabe en .m3u8)
				inicio.startsWith("#extm3u") && texto.contains("#EXT-X-", ignoreCase = true) -> Sonda(guardar, hls = true, nombre = nombre, kbps = kbps, formato = "HLS")
				// Listas: se sigue a la primera emisora de dentro
				inicio.startsWith("[playlist]") || tipo.contains("scpls") ->
					Regex("""(?im)^\s*File\d+\s*=\s*(\S+)""").find(texto)?.groupValues?.get(1)?.let { seguir(it) } ?: throw ErrorFlujo("lista")
				inicio.startsWith("#extm3u") || tipo.contains("mpegurl") || (tipo.startsWith("text/plain") && lineasUtiles(texto).firstOrNull()?.matches(Regex("""(?i)(https?|mms|icy)://\S+""")) == true) ->
					lineasUtiles(texto).firstOrNull()?.let { seguir(it.replaceFirst(Regex("(?i)^(mms|icy)://"), "http://")) } ?: throw ErrorFlujo("lista")
				inicio.contains("<asx") || tipo.contains("x-ms-asf") || tipo.contains("x-ms-wax") ->
					Regex("""(?i)<ref\s+href\s*=\s*"([^"]+)"""").find(texto)?.groupValues?.get(1)?.let { seguir(it.replaceFirst(Regex("(?i)^mms://"), "http://")) } ?: throw ErrorFlujo("lista")
				tipo.contains("xspf") || (inicio.contains("<playlist") && inicio.contains("xspf")) ->
					Regex("""(?is)<location>\s*([^<]+?)\s*</location>""").find(texto)?.groupValues?.get(1)?.let { seguir(it) } ?: throw ErrorFlujo("lista")
				// Una página web: hay que buscar el sonido dentro
				tipo.contains("html") || inicio.startsWith("<!doctype") || inicio.startsWith("<html") -> Sonda(url.trim(), web = true)
				else -> {
					val formato = formatoDe(tipo, bytes) ?: throw ErrorFlujo("formato")
					Sonda(guardar, nombre = nombre, kbps = kbps, formato = formato, generos = generos, webEmisora = webIcy)
				}
			}
		} finally {
			c.disconnect()
		}
	}

	/** Qué sonido es, por el tipo que dice el servidor o por los primeros bytes. null si no parece sonido. */
	private fun formatoDe(tipo: String, b: ByteArray): String? {
		fun empieza(s: String, desde: Int = 0) = b.size >= desde + s.length && String(b, desde, s.length, Charsets.ISO_8859_1) == s
		fun u(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
		// Sincronía MPEG (MP3, MP2) o ADTS (AAC) en los primeros bytes (el flujo puede empezar a mitad de trama)
		val sincro = (0 until minOf(b.size - 1, 8192)).firstOrNull { i -> u(i) == 0xFF && (u(i + 1) and 0xE0) == 0xE0 }
		return when {
			empieza("OggS") -> if (String(b, 0, minOf(b.size, 200), Charsets.ISO_8859_1).contains("OpusHead")) "Opus" else "Ogg"
			empieza("fLaC") -> "FLAC"
			empieza("ID3") -> "MP3"
			u(0) == 0x47 && u(188) == 0x47 -> "MPEG-TS"
			tipo.contains("aac") || tipo.contains("aacp") -> "AAC"
			tipo.contains("mpeg") || tipo.contains("mp3") -> "MP3"
			tipo.contains("ogg") -> "Ogg"
			tipo.contains("opus") -> "Opus"
			tipo.contains("flac") -> "FLAC"
			tipo.startsWith("audio/") -> tipo.removePrefix("audio/").substringBefore(';').uppercase()
			sincro != null -> if ((u(sincro + 1) and 0x06) == 0) "AAC" else "MP3"
			tipo.contains("octet-stream") || tipo.isBlank() -> "?"
			else -> null
		}
	}

	/** Un sonido encontrado en una página, ya comprobado. */
	data class Candidato(val sonda: Sonda, val etiqueta: String)

	private val EXTENSIONES = Regex("""(?i)\.(m3u8|m3u|pls|asx|xspf|mp3|aac|aacp|ogg|oga|opus|flac)(\?|$)""")
	private val RUTAS = Regex("""(?i)(/;|/stream/?|/live/?|/listen/?|/radio\.mp3|/autodj|/;stream\.mp3|/\d+/stream)(\?|$)""")
	private val SERVICIOS = Regex("""(?i)(radiojar\.com|stream\.zeno\.fm|streamtheworld\.com|radio\.co/|shoutcast|icecast|listen2myradio|streaming|radiostream|stream\.|zenofm|onlineradiobox|cdnstream|ice\d*\.|live\d*\.|:\d{4,5}/)""")
	private val DIRECCION = Regex("""https?:\\?/\\?/[^\s"'<>()\\]+(?:\\/[^\s"'<>()\\]*)*""")

	/** ¿Parece la dirección de un sonido (por su extensión, su ruta o su servicio)? */
	private fun pareceSonido(u: String): Boolean {
		val l = u.lowercase()
		if (Regex("""(?i)\.(png|jpe?g|gif|svg|webp|ico|css|js|woff2?|ttf|json|xml|html?|php)(\?|$)""").containsMatchIn(l.substringBefore('#'))) return false
		if (l.contains("facebook.") || l.contains("twitter.") || l.contains("instagram.") || l.contains("google") || l.contains("youtube.") || l.contains("apple.com/app") || l.contains("play.google")) return false
		return EXTENSIONES.containsMatchIn(l) || RUTAS.containsMatchIn(l) || SERVICIOS.containsMatchIn(l)
	}

	/** Descarga una página (como mucho 2 MB) siguiendo redirecciones. */
	private fun pagina(url: String): Pair<String, URL>? = runCatching {
		val a = abrir(url, conectar = 10_000, leer = 15_000)
		val c = a.conexion
		try {
			val tipo = c.contentType.orEmpty().lowercase()
			if (tipo.startsWith("audio/") || tipo.startsWith("video/")) return@runCatching null
			val texto = c.inputStream.use { e ->
				val salida = java.io.ByteArrayOutputStream()
				val b = ByteArray(16 * 1024)
				while (salida.size() < 2_000_000) {
					val r = e.read(b)
					if (r < 0) break
					salida.write(b, 0, r)
				}
				salida.toString("UTF-8")
			}
			texto to a.url
		} finally {
			c.disconnect()
		}
	}.getOrNull()

	/** Direcciones que podrían ser el sonido, en el HTML o en un script. */
	private fun candidatosEn(texto: String, base: URL): List<String> {
		val r = LinkedHashSet<String>()
		val doc = org.jsoup.Jsoup.parse(texto, base.toString())
		// Reproductores y enlaces de la página
		doc.select("audio[src], source[src], audio source[src]").forEach { r += it.absUrl("src") }
		doc.select("meta[property=og:audio], meta[property=og:audio:url], meta[property=og:audio:secure_url]").forEach { r += it.attr("content") }
		doc.select("a[href], [data-src], [data-url], [data-stream], [data-audio], [data-file]").forEach { e ->
			for (atributo in listOf("href", "data-src", "data-url", "data-stream", "data-audio", "data-file")) {
				val v = e.absUrl(atributo)
				if (v.isNotBlank() && pareceSonido(v)) r += v
			}
		}
		// Cualquier dirección en el texto (también dentro de los scripts, con «\/»)
		DIRECCION.findAll(texto).map { it.value.replace("\\/", "/").trimEnd('.', ',', ';', '\'', '"').let { u -> if (it.value.endsWith("/;")) "$u;" else u } }
			.filter { pareceSonido(it) }.forEach { r += it }
		return r.filter { it.startsWith("http") }
	}

	/**
	 * Busca el sonido de una emisora en su página web: en la página, en sus
	 * iframes (un nivel) y en sus scripts del mismo sitio (como mucho 5).
	 * Comprueba cada candidato (4 a la vez, 6 segundos cada uno) y devuelve
	 * los que suenan, el mejor primero.
	 */
	suspend fun buscarEnWeb(url: String): List<Candidato> = withContext(Dispatchers.IO) {
		val (html, base) = pagina(url) ?: return@withContext emptyList()
		val candidatos = LinkedHashSet<String>()
		candidatos += candidatosEn(html, base)
		val doc = org.jsoup.Jsoup.parse(html, base.toString())
		// Reproductores incrustados y scripts propios del sitio (ahí suele estar la dirección)
		val extras = (doc.select("iframe[src]").map { it.absUrl("src") }.take(4) +
			doc.select("script[src]").map { it.absUrl("src") }.filter { runCatching { URL(it).host.endsWith(base.host.removePrefix("www.")) }.getOrDefault(false) }.take(5))
			.filter { it.startsWith("http") }
		for (e in extras) pagina(e)?.let { (t, b) -> candidatos += candidatosEn(t, b) }
		// StreamTheWorld: el «mount» lleva a su servidor
		Regex("""(?i)(?:mount|station)["'\s:=]+([A-Z0-9_]{3,40}(?:AAC|MP3)?)""").findAll(html).map { it.groupValues[1] }
			.filter { html.contains("streamtheworld", true) }.distinct().take(2)
			.forEach { candidatos += "https://playerservices.streamtheworld.com/api/livestream-redirect/$it.mp3" }
		val lista = candidatos.distinct().take(25)
		val turno = Semaphore(4)
		val buenos = coroutineScope {
			lista.map { u ->
				async {
					turno.withPermit {
						withTimeoutOrNull(6_000) { runCatching { sondear(u) }.getOrNull() }?.takeIf { !it.web }
					}
				}
			}.awaitAll().filterNotNull()
		}.distinctBy { it.url }
		buenos.sortedByDescending { it.kbps }.map { s ->
			Candidato(s, listOf(s.nombre.ifBlank { runCatching { URL(s.url).host }.getOrDefault(s.url) }, s.formato.takeIf { it.isNotBlank() && it != "?" },
				s.kbps.takeIf { it > 0 }?.let { "$it kbps" }).filterNotNull().joinToString(" · "))
		}.distinctBy { it.etiqueta }
	}
}
