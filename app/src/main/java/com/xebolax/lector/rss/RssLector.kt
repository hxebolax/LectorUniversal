package com.xebolax.lector.rss

import com.xebolax.lector.BuildConfig
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Descarga y lee fuentes RSS 2.0, Atom, RDF (RSS 1.0) y JSON Feed. Pide al
 * servidor solo lo nuevo (ETag y Last-Modified) y descubre la fuente de una
 * página web (enlaces "alternate" o rutas habituales como /feed).
 */
object RssLector {

	class Elemento(
		val guid: String,
		val titulo: String,
		val enlace: String,
		val autor: String,
		val fecha: Long,
		val resumen: String,
		val contenido: String,
		val adjunto: String,
		val tipoAdjunto: String,
		val duracion: String,
	)

	/** idioma: el que declara la fuente (para que la voz lea en ese idioma); vacío si no lo dice. */
	class Canal(val titulo: String, val sitio: String, val elementos: List<Elemento>, val idioma: String = "")

	/** Respuesta: el canal (null si no ha cambiado) y las cabeceras para la próxima vez. */
	class Respuesta(val canal: Canal?, val etag: String?, val modificado: String?)

	private const val AGENTE = "Mozilla/5.0 (Android) LectorUniversal/" + BuildConfig.VERSION_NAME
	/** Algunas webs rechazan (403) lo que no parece un navegador: se prueba una vez más como si lo fuera. */
	private const val NAVEGADOR = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
	/** Para YouTube: como navegador de escritorio, la página trae el id del canal sin escapar. */
	private const val ESCRITORIO = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

	fun abrir(url: String, etag: String = "", modificado: String = "", usuario: String = "", clave: String = ""): HttpURLConnection {
		var actual = url
		var agente = AGENTE
		repeat(7) {
			val c = URL(actual).openConnection() as HttpURLConnection
			c.connectTimeout = 15_000
			c.readTimeout = 30_000
			c.instanceFollowRedirects = false
			c.setRequestProperty("User-Agent", agente)
			c.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/feed+json, application/xml, text/xml, */*")
			if (etag.isNotBlank()) c.setRequestProperty("If-None-Match", etag)
			if (modificado.isNotBlank()) c.setRequestProperty("If-Modified-Since", modificado)
			// Feeds privados (podcasts de mecenazgo): usuario y contraseña
			if (usuario.isNotBlank()) c.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString("$usuario:$clave".toByteArray(), android.util.Base64.NO_WRAP))
			val codigo = c.responseCode
			if (codigo == 403 && agente == AGENTE) {
				agente = NAVEGADOR
				c.disconnect()
				return@repeat
			}
			if (codigo in 300..399 && codigo != 304) {
				val nueva = c.getHeaderField("Location") ?: error("HTTP $codigo")
				actual = URL(URL(actual), nueva).toString()
				c.disconnect()
				return@repeat
			}
			return c
		}
		error("Demasiadas redirecciones")
	}

	/** Descarga una fuente. Si no ha cambiado desde la última vez (304), el canal es null. */
	fun descargar(url: String, etag: String = "", modificado: String = ""): Respuesta {
		val c = abrir(url, etag, modificado)
		try {
			if (c.responseCode == 304) return Respuesta(null, etag, modificado)
			if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
			val bytes = c.inputStream.use { it.readBytes() }
			val texto = com.xebolax.lector.modelo.Textos.decodificar(bytes, "utf-8")
			return Respuesta(leer(texto, url), c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"))
		} finally {
			c.disconnect()
		}
	}

	fun descargarTexto(url: String): String {
		val c = abrir(url)
		try {
			if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
			return c.inputStream.use { com.xebolax.lector.modelo.Textos.decodificar(it.readBytes(), "utf-8") }
		} finally {
			c.disconnect()
		}
	}

	/** Lee el texto de una fuente (XML o JSON). base: su dirección, para los enlaces relativos. */
	fun leer(texto: String, base: String): Canal {
		val t = texto.trimStart('﻿', ' ', '\n', '\r', '\t')
		if (t.startsWith("{")) return leerJson(JSONObject(t), base)
		val doc = Jsoup.parse(t, base, Parser.xmlParser())
		val raiz = doc.children().firstOrNull { nombre(it) in setOf("rss", "feed", "rdf") } ?: error("No es una fuente RSS, Atom ni JSON")
		return if (nombre(raiz) == "feed") leerAtom(raiz, base) else leerRss(raiz, base)
	}

	/** Es una fuente si se puede leer como tal. */
	fun esFuente(texto: String): Boolean = runCatching { leer(texto, "https://x/") }.isSuccess

	/**
	 * Fuentes de una dirección: si ya es una fuente, ella; si es una web, las
	 * que anuncia, o las rutas habituales (/feed, /rss...). (título, url)
	 */
	/**
	 * La fuente de servicios que no la anuncian bien: un canal o una lista de
	 * YouTube, un subreddit o usuario de Reddit, una cuenta de Mastodon
	 * (también escrita como @usuario@servidor) o de Bluesky. null = no es de esos.
	 */
	/** Página de YouTube pedida como un navegador de escritorio y sin la pantalla de cookies (trae el id del canal). */
	private fun paginaYoutube(url: String): String? = runCatching {
		val c = URL(url).openConnection() as HttpURLConnection
		c.connectTimeout = 15_000
		c.readTimeout = 30_000
		c.setRequestProperty("User-Agent", ESCRITORIO)
		c.setRequestProperty("Accept-Language", "es,en;q=0.8")
		c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI")
		try {
			if (c.responseCode !in 200..299) null
			else c.inputStream.use { com.xebolax.lector.modelo.Textos.decodificar(it.readBytes(), "utf-8") }
		} finally {
			c.disconnect()
		}
	}.getOrNull()

	/** El id del canal (UC…) en una página de YouTube, venga como venga escrito. */
	private fun idCanal(texto: String): String? = listOf(
		Regex("""channel_id=(UC[\w-]{22})"""),
		Regex(""""(?:externalId|channelId|browseId)"\s*:\s*"(UC[\w-]{22})""""),
		Regex("""\\x22(?:externalId|channelId|browseId)\\x22:\\x22(UC[\w-]{22})"""),
		Regex("""youtube\.com/channel/(UC[\w-]{22})"""),
	).firstNotNullOfOrNull { it.find(texto)?.groupValues?.get(1) }

	/** Id de un vídeo de YouTube en su enlace (youtu.be/ID, watch?v=ID, /shorts/ID, /live/ID). */
	private fun idVideo(url: URL): String? {
		val host = url.host.lowercase().removePrefix("www.").removePrefix("m.")
		val ruta = url.path.trimEnd('/')
		return when {
			host == "youtu.be" -> ruta.removePrefix("/").substringBefore('/').takeIf { it.isNotBlank() }
			host.endsWith("youtube.com") && ruta == "/watch" -> url.query?.split('&')?.firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
			host.endsWith("youtube.com") && (ruta.startsWith("/shorts/") || ruta.startsWith("/live/")) -> ruta.split('/').getOrNull(2)
			else -> null
		}
	}

	/**
	 * La fuente del canal de un vídeo de YouTube (lo que comparte la app de
	 * YouTube es casi siempre un vídeo): oEmbed da la página del canal, y de
	 * ella se saca su id.
	 */
	private fun canalDeVideo(id: String): String? {
		val video = "https://www.youtube.com/watch?v=$id"
		val oembed = runCatching { descargarTexto("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(video, "UTF-8")) }.getOrNull()
		val canal = oembed?.let { runCatching { JSONObject(it).optString("author_url") }.getOrNull() }?.takeIf { it.isNotBlank() }
		val texto = (canal?.let { paginaYoutube(it) }) ?: paginaYoutube(video) ?: return null
		return idCanal(texto)
	}

	fun atajo(direccion: String): String? {
		val d = direccion.trim()
		Regex("""^@?([\w.]+)@([\w.-]+\.[a-z]{2,})$""", RegexOption.IGNORE_CASE).matchEntire(d)?.let { m ->
			return "https://${m.groupValues[2]}/@${m.groupValues[1]}.rss"
		}
		val url = runCatching { URL(if (d.startsWith("http")) d else "https://$d") }.getOrNull() ?: return null
		val host = url.host.lowercase().removePrefix("www.").removePrefix("m.")
		val ruta = url.path.trimEnd('/')
		return when {
			host.endsWith("youtube.com") && ruta.startsWith("/channel/") -> "https://www.youtube.com/feeds/videos.xml?channel_id=" + ruta.removePrefix("/channel/").substringBefore('/')
			host.endsWith("youtube.com") && ruta == "/playlist" -> url.query?.split('&')?.firstOrNull { it.startsWith("list=") }?.let { "https://www.youtube.com/feeds/videos.xml?playlist_id=" + it.removePrefix("list=") }
			// Un vídeo (lo que comparte la app de YouTube): la fuente de su canal
			idVideo(url) != null -> canalDeVideo(idVideo(url)!!)?.let { "https://www.youtube.com/feeds/videos.xml?channel_id=$it" }
			host.endsWith("youtube.com") && (ruta.startsWith("/@") || ruta.startsWith("/c/") || ruta.startsWith("/user/")) -> {
				// La página del canal (pedida como un navegador de escritorio) trae su identificador
				val texto = paginaYoutube("https://www.youtube.com" + ruta.split('/').take(2).joinToString("/")) ?: return null
				idCanal(texto)?.let { "https://www.youtube.com/feeds/videos.xml?channel_id=$it" }
			}
			host == "reddit.com" && (ruta.startsWith("/r/") || ruta.startsWith("/user/") || ruta.startsWith("/u/")) -> "https://www.reddit.com$ruta/.rss"
			host == "bsky.app" && ruta.startsWith("/profile/") -> "https://bsky.app${ruta.substringBefore("/post")}/rss"
			ruta.matches(Regex("""/@[\w.]+""")) && !host.endsWith("youtube.com") && !host.endsWith("medium.com") -> "https://$host$ruta.rss"
			else -> null
		}
	}

	fun descubrir(direccion: String): List<Pair<String, String>> {
		atajo(direccion)?.let { directa ->
			val texto = runCatching { descargarTexto(directa) }.getOrNull()
			if (texto != null && esFuente(texto)) return listOf(leer(texto, directa).titulo.ifBlank { directa } to directa)
		}
		val url = if (direccion.startsWith("http")) direccion.trim() else "https://" + direccion.trim()
		val texto = descargarTexto(url)
		if (esFuente(texto)) return listOf(leer(texto, url).titulo.ifBlank { url } to url)
		val doc = Jsoup.parse(texto, url)
		val anunciadas = anunciadasEn(doc, url)
		if (anunciadas.isNotEmpty()) return anunciadas
		// La dirección de un artículo: las de la portada del sitio
		val raiz = URL(URL(url), "/").toString().trimEnd('/')
		if (raiz.trimEnd('/') != url.trimEnd('/')) {
			runCatching { descargarTexto(raiz) }.getOrNull()?.let { t -> anunciadasEn(Jsoup.parse(t, raiz), raiz).takeIf { it.isNotEmpty() }?.let { return it } }
		}
		for (ruta in listOf("/feed", "/rss", "/feed.xml", "/rss.xml", "/atom.xml", "/index.xml", "/index.rss", "/feeds/posts/default", "/?feed=rss2", "/feed/", "/rss/")) {
			val prueba = raiz + ruta
			val ok = runCatching { esFuente(descargarTexto(prueba)) }.getOrDefault(false)
			if (ok) return listOf(doc.title().ifBlank { prueba } to prueba)
		}
		return emptyList()
	}

	/**
	 * Las fuentes que anuncia una página: solo RSS, Atom, RDF y JSON Feed (no
	 * el oEmbed ni la API JSON de WordPress, que no son fuentes), las de
	 * comentarios al final, y comprobando que de verdad se leen como fuente.
	 */
	private fun anunciadasEn(doc: org.jsoup.nodes.Document, url: String): List<Pair<String, String>> {
		val todas = doc.select("link[rel~=(?i)alternate][type]").filter { l ->
			val tipo = l.attr("type").lowercase()
			(tipo.contains("rss") || tipo.contains("atom") || tipo.contains("rdf") || tipo.contains("feed+json")) && !tipo.contains("oembed")
		}.map { l -> l.attr("title").ifBlank { doc.title() }.ifBlank { url } to l.absUrl("href") }
			.filter { it.second.isNotBlank() }.distinctBy { it.second }
			.sortedBy { (titulo, enlace) -> titulo.contains("coment", true) || titulo.contains("comment", true) || enlace.contains("/comments/") }
		// Se comprueban (como mucho 5): algunas webs anuncian fuentes que ya no existen
		val buenas = todas.take(5).filter { (_, enlace) -> runCatching { esFuente(descargarTexto(enlace)) }.getOrDefault(false) }
		return buenas.ifEmpty { if (todas.size > 5) todas.drop(5) else emptyList() }
	}

	// --- Formatos -------------------------------------------------------------------------

	private fun nombre(e: Element) = e.tagName().substringAfter(':').lowercase()
	private fun prefijo(e: Element) = e.tagName().substringBefore(':', "").lowercase()
	private fun hijo(e: Element, n: String) = e.children().firstOrNull { nombre(it) == n }
	private fun hijos(e: Element, n: String) = e.children().filter { nombre(it) == n }
	private fun absoluta(base: String, href: String): String = if (href.isBlank()) "" else runCatching { URL(URL(base), href.trim()).toString() }.getOrDefault(href.trim())

	/** Texto de un elemento que puede traer HTML escapado o CDATA. */
	private fun html(e: Element?): String {
		e ?: return ""
		// Atom type="xhtml" trae elementos; lo demás es texto (HTML escapado o en CDATA)
		return if (e.children().isNotEmpty()) e.html().trim() else e.wholeText().trim()
	}

	/** Texto con saltos de línea (la descripción de un vídeo) como párrafos HTML. */
	private fun textoAHtml(t: String): String = if (t.isBlank()) "" else t.split(Regex("\n\\s*\n|\n")).map { it.trim() }.filter { it.isNotEmpty() }
		.joinToString("") { "<p>" + org.jsoup.nodes.Entities.escape(it) + "</p>" }

	private fun textoPlano(s: String): String = if (s.contains('<')) Jsoup.parse(s).text().trim() else Parser.unescapeEntities(s, false).trim()

	private fun leerRss(raiz: Element, base: String): Canal {
		val canal = hijo(raiz, "channel") ?: raiz
		val items = hijos(canal, "item").ifEmpty { hijos(raiz, "item") }
		val elementos = items.map { e ->
			val enlace = absoluta(base, hijo(e, "link")?.text().orEmpty().ifBlank { hijo(e, "link")?.attr("href").orEmpty() })
			val guid = hijo(e, "guid")?.text()?.trim().orEmpty().ifBlank { enlace }.ifBlank { hijo(e, "title")?.text().orEmpty() }
			// content:encoded trae el artículo; description, el resumen (o todo)
			val encoded = e.children().firstOrNull { nombre(it) == "encoded" && prefijo(it) == "content" }
			val descripcion = html(hijo(e, "description"))
			val contenido = html(encoded).ifBlank { descripcion }
			val adjunto = hijo(e, "enclosure")
				?: e.children().firstOrNull { nombre(it) == "content" && prefijo(it) == "media" && it.attr("type").let { t -> t.startsWith("audio") || t.startsWith("video") } }
			val autor = e.children().firstOrNull { nombre(it) == "creator" }?.text()
				?: hijo(e, "author")?.text()
			Elemento(
				guid = guid,
				titulo = textoPlano(hijo(e, "title")?.text().orEmpty()).ifBlank { textoPlano(descripcion).take(80) },
				enlace = enlace,
				autor = autor?.trim().orEmpty(),
				fecha = fecha((hijo(e, "pubdate") ?: e.children().firstOrNull { nombre(it) == "date" })?.text().orEmpty()),
				resumen = textoPlano(descripcion).take(600),
				contenido = contenido,
				adjunto = adjunto?.attr("url")?.let { absoluta(base, it) }.orEmpty(),
				tipoAdjunto = adjunto?.attr("type").orEmpty(),
				duracion = e.children().firstOrNull { nombre(it) == "duration" }?.text()?.trim().orEmpty(),
			)
		}
		val sitio = hijo(canal, "link")?.text()?.trim().orEmpty()
		return Canal(textoPlano(hijo(canal, "title")?.text().orEmpty()), absoluta(base, sitio), elementos, hijo(canal, "language")?.text()?.trim().orEmpty())
	}

	private fun leerAtom(raiz: Element, base: String): Canal {
		val elementos = hijos(raiz, "entry").map { e ->
			val enlaces = hijos(e, "link")
			val enlace = (enlaces.firstOrNull { it.attr("rel").isEmpty() || it.attr("rel") == "alternate" } ?: enlaces.firstOrNull())
				?.attr("href")?.let { absoluta(base, it) }.orEmpty()
			val adjunto = enlaces.firstOrNull { it.attr("rel") == "enclosure" }
			// YouTube (y otros) ponen la descripción en media:group/media:description: es el texto del vídeo
			val media = hijo(e, "group")?.let { g -> hijo(g, "description") }?.wholeText()?.trim().orEmpty()
			val contenido = html(hijo(e, "content")).ifBlank { textoAHtml(media) }
			val resumen = html(hijo(e, "summary")).ifBlank { media.take(600) }
			Elemento(
				guid = hijo(e, "id")?.text()?.trim().orEmpty().ifBlank { enlace },
				titulo = textoPlano(html(hijo(e, "title"))),
				enlace = enlace,
				autor = hijos(e, "author").mapNotNull { hijo(it, "name")?.text()?.trim() }.joinToString(", "),
				fecha = fecha((hijo(e, "published") ?: hijo(e, "updated"))?.text().orEmpty()),
				resumen = textoPlano(resumen.ifBlank { contenido }).take(600),
				contenido = contenido.ifBlank { resumen },
				adjunto = adjunto?.attr("href")?.let { absoluta(base, it) }.orEmpty(),
				tipoAdjunto = adjunto?.attr("type").orEmpty(),
				duracion = "",
			)
		}
		val sitio = hijos(raiz, "link").firstOrNull { it.attr("rel").isEmpty() || it.attr("rel") == "alternate" }?.attr("href").orEmpty()
		return Canal(textoPlano(html(hijo(raiz, "title"))), absoluta(base, sitio), elementos, raiz.attr("xml:lang").trim())
	}

	private fun leerJson(o: JSONObject, base: String): Canal {
		val items = o.optJSONArray("items")
		val elementos = (0 until (items?.length() ?: 0)).mapNotNull { i ->
			val e = items?.optJSONObject(i) ?: return@mapNotNull null
			val enlace = absoluta(base, e.optString("url"))
			val html = e.optString("content_html").ifBlank { e.optString("content_text").replace("\n", "<br>") }
			val adjunto = e.optJSONArray("attachments")?.optJSONObject(0)
			Elemento(
				guid = e.optString("id").ifBlank { enlace },
				titulo = e.optString("title").ifBlank { textoPlano(html).take(80) },
				enlace = enlace,
				autor = e.optJSONObject("author")?.optString("name").orEmpty()
					.ifBlank { e.optJSONArray("authors")?.optJSONObject(0)?.optString("name").orEmpty() },
				fecha = fecha(e.optString("date_published")),
				resumen = e.optString("summary").ifBlank { textoPlano(html) }.take(600),
				contenido = html,
				adjunto = adjunto?.optString("url")?.let { absoluta(base, it) }.orEmpty(),
				tipoAdjunto = adjunto?.optString("mime_type").orEmpty(),
				duracion = adjunto?.optLong("duration_in_seconds", 0)?.takeIf { it > 0 }?.let { "%d:%02d:%02d".format(it / 3600, it % 3600 / 60, it % 60) }.orEmpty(),
			)
		}
		return Canal(o.optString("title"), absoluta(base, o.optString("home_page_url")), elementos, o.optString("language"))
	}

	private val FORMATOS = listOf(
		"EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss Z", "dd MMM yyyy HH:mm:ss Z",
		"EEE, dd MMM yyyy HH:mm Z", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'",
		"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mmXXX", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd",
	)

	/** Fecha en cualquiera de los formatos habituales de RSS y Atom (0 si no se entiende). */
	fun fecha(s: String): Long {
		val t = s.trim().replace(Regex("\\s+"), " ")
		if (t.isEmpty()) return 0
		for (f in FORMATOS) {
			val formato = SimpleDateFormat(f, Locale.US).apply {
				isLenient = false
				if (f.endsWith("'Z'") || !f.contains('Z') && !f.contains('X') && !f.contains('z')) timeZone = TimeZone.getTimeZone("UTC")
			}
			runCatching { formato.parse(t) }.getOrNull()?.let { return it.time }
		}
		return 0
	}
}
