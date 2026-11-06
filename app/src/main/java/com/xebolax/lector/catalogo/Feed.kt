package com.xebolax.lector.catalogo

import android.util.Base64
import com.xebolax.lector.BuildConfig
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Catálogos de libros (OPDS) y noticias (RSS y Atom): se descargan y se
 * convierten en una lista de entradas. Una entrada puede llevar a otra lista
 * (navegación), a un libro para descargar o a un artículo para leer.
 */
object Feed {

	class Enlace(val url: String, val tipo: String, val rel: String, val titulo: String)

	class Entrada(
		val titulo: String,
		val autor: String,
		val resumen: String,
		val fecha: String,
		/** Otra lista (subsección del catálogo). */
		val navegacion: String?,
		/** Archivos del libro (EPUB, PDF...). */
		val descargas: List<Enlace>,
		/** Artículo (noticias) o página del libro. */
		val enlace: String?,
	)

	class Pagina(
		val titulo: String,
		val entradas: List<Entrada>,
		/** Página siguiente de resultados. */
		val siguiente: String?,
		/** Plantilla de búsqueda con {searchTerms}. */
		val busqueda: String?,
	)

	class Credenciales(val usuario: String, val clave: String)

	fun descargarTexto(url: String, credenciales: Credenciales? = null): String = abrir(url, credenciales).use { c ->
		c.inputStream.use { com.xebolax.lector.modelo.Textos.decodificar(it.readBytes(), "utf-8") }
	}

	/** Descarga un archivo (libro) a destino; devuelve el tipo que dice el servidor. */
	fun descargarArchivo(url: String, destino: File, credenciales: Credenciales?): String? = abrir(url, credenciales).use { c ->
		c.inputStream.use { e -> destino.outputStream().use { e.copyTo(it, 256 * 1024) } }
		c.contentType
	}

	private class Conexion(val c: HttpURLConnection) : AutoCloseable {
		val inputStream get() = c.inputStream
		val contentType: String? get() = c.contentType
		override fun close() = c.disconnect()
	}

	private fun abrir(url: String, credenciales: Credenciales?): Conexion {
		var actual = url
		repeat(6) {
			val c = URL(actual).openConnection() as HttpURLConnection
			c.connectTimeout = 20_000
			c.readTimeout = 60_000
			c.instanceFollowRedirects = false
			c.setRequestProperty("User-Agent", "LectorUniversal/${BuildConfig.VERSION_NAME} (Android)")
			if (credenciales != null && credenciales.usuario.isNotBlank()) {
				val token = Base64.encodeToString("${credenciales.usuario}:${credenciales.clave}".toByteArray(), Base64.NO_WRAP)
				c.setRequestProperty("Authorization", "Basic $token")
			}
			val codigo = c.responseCode
			if (codigo in 300..399) {
				// Redirecciones (también de http a https, que Java no sigue sola)
				val nueva = c.getHeaderField("Location") ?: error("HTTP $codigo")
				actual = URL(URL(actual), nueva).toString()
				c.disconnect()
				return@repeat
			}
			if (codigo == 401) {
				c.disconnect()
				error("Hace falta usuario y contraseña (401)")
			}
			if (codigo !in 200..299) {
				c.disconnect()
				error("HTTP $codigo")
			}
			return Conexion(c)
		}
		error("Demasiadas redirecciones")
	}

	/** Lee una lista OPDS, Atom o RSS. base: dirección de la que viene (para los enlaces relativos). */
	fun leer(xml: String, base: String): Pagina {
		// OPDS 2 (JSON): Internet Archive, Open Library...
		if (xml.trimStart().startsWith("{")) return leerOpds2(org.json.JSONObject(xml), base)
		val doc = Jsoup.parse(xml, base, Parser.xmlParser())
		val raiz = doc.children().firstOrNull() ?: error("Lista vacía")
		return when (nombre(raiz)) {
			"rss", "rdf" -> leerRss(raiz, base)
			"feed" -> leerAtom(raiz, base)
			else -> error("No es un catálogo OPDS ni una lista de noticias")
		}
	}

	private fun nombre(e: Element) = e.tagName().substringAfter(':').lowercase()

	private fun hijo(e: Element, n: String) = e.children().firstOrNull { nombre(it) == n }

	private fun hijos(e: Element, n: String) = e.children().filter { nombre(it) == n }

	private fun absoluta(base: String, href: String): String = runCatching { URL(URL(base), href).toString() }.getOrDefault(href)

	private fun limpiarHtml(s: String): String = Jsoup.parse(s).text().trim()

	private fun leerAtom(raiz: Element, base: String): Pagina {
		val enlaces = hijos(raiz, "link")
		val siguiente = enlaces.firstOrNull { it.attr("rel") == "next" }?.attr("href")?.let { absoluta(base, it) }
		val busqueda = enlaces.firstOrNull { it.attr("rel") == "search" && it.attr("type").contains("atom") && it.attr("href").contains("{searchTerms}") }
			?.attr("href")?.let { absoluta(base, it).replace("%7BsearchTerms%7D", "{searchTerms}") }
		// El buscador OpenSearch se resuelve al usarlo
			?: enlaces.firstOrNull { it.attr("rel") == "search" }?.attr("href")?.let { "opensearch:" + absoluta(base, it) }
		val entradas = hijos(raiz, "entry").map { e ->
			val links = hijos(e, "link").map { Enlace(absoluta(base, it.attr("href")), it.attr("type"), it.attr("rel"), it.attr("title")) }
			val navegacion = links.firstOrNull {
				it.tipo.contains("profile=opds-catalog") || it.rel == "subsection" || (it.tipo.contains("atom+xml") && !it.rel.contains("acquisition"))
			}?.url
			val descargas = links.filter { it.rel.contains("opds-spec.org/acquisition") && !it.rel.contains("buy") && !it.rel.contains("subscribe") }
			val autor = hijos(e, "author").mapNotNull { hijo(it, "name")?.text()?.trim() }.joinToString(", ")
			val resumen = (hijo(e, "summary") ?: hijo(e, "content"))?.let { limpiarHtml(it.text().ifBlank { it.html() }) }.orEmpty()
			Entrada(
				titulo = hijo(e, "title")?.text()?.trim().orEmpty(),
				autor = autor,
				resumen = resumen,
				fecha = hijo(e, "published")?.text()?.take(10).orEmpty(),
				navegacion = if (descargas.isEmpty()) navegacion else null,
				descargas = descargas,
				enlace = links.firstOrNull { it.rel == "alternate" || it.rel.isEmpty() }?.url,
			)
		}
		return Pagina(hijo(raiz, "title")?.text()?.trim().orEmpty(), entradas, siguiente, busqueda)
	}

	/**
	 * OPDS 2: navegación, grupos, filtros (por idioma, por tema) y libros.
	 * Solo cuentan como descargables los de acceso libre (open-access o una
	 * adquisición directa a un archivo); los de préstamo con DRM (borrow, LCP)
	 * y los de compra no se ofrecen, porque no se podrían leer.
	 */
	private fun leerOpds2(o: org.json.JSONObject, base: String): Pagina {
		fun lista(a: org.json.JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
		fun enlaces(x: org.json.JSONObject) = lista(x.optJSONArray("links"))
		val propios = enlaces(o)
		val siguiente = propios.firstOrNull { it.optString("rel") == "next" }?.optString("href")?.let { absoluta(base, it) }
		// Plantilla de búsqueda: «…{?query}…» pasa a «…?query={searchTerms}…»
		val busqueda = propios.firstOrNull { it.optString("rel") == "search" }?.optString("href")?.let { h ->
			absoluta(base, h).replace("%7B", "{").replace("%7D", "}").replace(Regex("""\{\?([a-zA-Z]+)\}""")) { "?${it.groupValues[1]}={searchTerms}" }
				.replace(Regex("""\{&([a-zA-Z]+)\}""")) { "&${it.groupValues[1]}={searchTerms}" }
		}
		val entradas = mutableListOf<Entrada>()
		fun navegacion(titulo: String, href: String, detalle: String = "") {
			if (href.isNotBlank()) entradas += Entrada(titulo, "", detalle, "", absoluta(base, href), emptyList(), null)
		}
		fun publicacion(p: org.json.JSONObject) {
			val m = p.optJSONObject("metadata") ?: return
			val autores = m.opt("author").let { a ->
				when (a) {
					is org.json.JSONArray -> (0 until a.length()).mapNotNull { i -> (a.opt(i) as? org.json.JSONObject)?.optString("name") ?: a.optString(i) }
					is org.json.JSONObject -> listOf(a.optString("name"))
					is String -> listOf(a)
					else -> emptyList()
				}
			}.filter { it.isNotBlank() }
			val links = enlaces(p)
			val descargas = links.filter { l ->
				val rel = l.optString("rel")
				val tipo = l.optString("type")
				(rel.endsWith("acquisition/open-access") || rel.endsWith("opds-spec.org/acquisition")) && !tipo.contains("opds") && !tipo.contains("lcp")
			}.map { Enlace(absoluta(base, it.optString("href")), it.optString("type"), it.optString("rel"), it.optString("title")) }
			if (descargas.isEmpty()) return
			entradas += Entrada(
				titulo = m.optString("title").trim(),
				autor = autores.joinToString(", "),
				resumen = limpiarHtml(m.optString("description")),
				fecha = m.optString("published").take(10),
				navegacion = null,
				descargas = descargas,
				enlace = m.optString("identifier").takeIf { it.startsWith("http") },
			)
		}
		lista(o.optJSONArray("navigation")).forEach { navegacion(it.optString("title"), it.optString("href")) }
		// Filtros: «Idioma: Spanish», «Tema: Romance»...
		lista(o.optJSONArray("facets")).forEach { f ->
			val nombre = f.optJSONObject("metadata")?.optString("title").orEmpty().removePrefix("Browse by ").trim()
			enlaces(f).forEach { l -> navegacion("$nombre: ${l.optString("title")}", l.optString("href")) }
		}
		// Grupos («Open Access & Public Domain»...): se entra en cada uno
		lista(o.optJSONArray("groups")).forEach { g ->
			val gm = g.optJSONObject("metadata")
			val propio = enlaces(g).firstOrNull { it.optString("rel") == "self" }?.optString("href")
			val n = gm?.optInt("numberOfItems", 0) ?: 0
			if (propio != null) navegacion(gm?.optString("title").orEmpty(), propio, if (n > 0) n.toString() else "")
			else lista(g.optJSONArray("publications")).forEach { publicacion(it) }
		}
		lista(o.optJSONArray("publications")).forEach { publicacion(it) }
		return Pagina(o.optJSONObject("metadata")?.optString("title").orEmpty(), entradas, siguiente, busqueda)
	}

	private fun leerRss(raiz: Element, base: String): Pagina {
		val canal = hijo(raiz, "channel") ?: raiz
		val elementos = hijos(canal, "item").ifEmpty { hijos(raiz, "item") }
		val entradas = elementos.map { e ->
			val enlace = hijo(e, "link")?.text()?.trim()?.takeIf { it.isNotEmpty() }
				?: hijo(e, "guid")?.text()?.trim()?.takeIf { it.startsWith("http") }
			val adjunto = hijo(e, "enclosure")?.let { Enlace(absoluta(base, it.attr("url")), it.attr("type"), "enclosure", "") }
			Entrada(
				titulo = hijo(e, "title")?.text()?.let { limpiarHtml(it) }.orEmpty(),
				autor = (hijo(e, "creator") ?: hijo(e, "author"))?.text()?.trim().orEmpty(),
				resumen = hijo(e, "description")?.text()?.let { limpiarHtml(it) }.orEmpty(),
				fecha = fechaLegible((hijo(e, "pubdate") ?: hijo(e, "date"))?.text()?.trim().orEmpty()),
				navegacion = null,
				descargas = listOfNotNull(adjunto?.takeIf { it.tipo.startsWith("audio") || it.tipo.contains("epub") || it.tipo.contains("pdf") }),
				enlace = enlace?.let { absoluta(base, it) },
			)
		}
		return Pagina(hijo(canal, "title")?.text()?.trim().orEmpty(), entradas, null, null)
	}

	/** "Fri, 23 Oct 2026 10:48:30 GMT" → fecha y hora del móvil. */
	private fun fechaLegible(s: String): String {
		if (s.isBlank()) return ""
		val formatos = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd'T'HH:mm:ssXXX")
		for (f in formatos) {
			val fecha = runCatching { java.text.SimpleDateFormat(f, java.util.Locale.US).parse(s) }.getOrNull() ?: continue
			return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(fecha)
		}
		return s
	}

	/** Dirección de búsqueda a partir de la plantilla (o de la descripción OpenSearch). */
	fun direccionBusqueda(plantilla: String, texto: String, credenciales: Credenciales?): String {
		var p = plantilla
		if (p.startsWith("opensearch:")) {
			val desc = p.removePrefix("opensearch:")
			val xml = descargarTexto(desc, credenciales)
			val d = Jsoup.parse(xml, desc, Parser.xmlParser())
			val url = d.getAllElements().filter { nombre(it) == "url" }
				.sortedByDescending { it.attr("type").contains("atom") }
				.firstOrNull()?.attr("template") ?: error("El catálogo no tiene buscador")
			p = absoluta(desc, url)
		}
		return p.replace("{searchTerms}", URLEncoder.encode(texto, "UTF-8"))
			.replace(Regex("\\{[a-zA-Z:]+\\?\\}"), "")
	}

	/**
	 * Internet Archive: su enlace de descarga del catálogo (…/opds/book/<id>?glob_pattern=…) no siempre
	 * lleva a un archivo; se mira la lista de archivos del libro y se elige el mejor para leer:
	 * EPUB, PDF con texto, el texto reconocido (_djvu.txt) o el PDF de imágenes. null si no es de Internet Archive.
	 */
	fun archivoInternetArchive(d: Enlace): Enlace? {
		val id = Regex("""archive\.org/services/opds/book/([^?/]+)""").find(d.url)?.groupValues?.get(1) ?: return null
		val json = org.json.JSONObject(descargarTexto("https://archive.org/metadata/$id/files"))
		val archivos = json.optJSONArray("result") ?: error("Internet Archive no da los archivos de este libro")
		val lista = (0 until archivos.length()).mapNotNull { archivos.optJSONObject(it) }
		fun primero(prueba: (String, String) -> Boolean) = lista.firstOrNull { prueba(it.optString("name").lowercase(), it.optString("format")) }
		val elegido = primero { n, _ -> n.endsWith(".epub") }
			?: primero { n, f -> n.endsWith(".pdf") && f.contains("Text PDF") }
			?: primero { n, _ -> n.endsWith("_djvu.txt") }
			?: primero { n, _ -> n.endsWith(".pdf") }
			?: error("Este libro de Internet Archive no tiene archivos que se puedan descargar")
		val nombre = elegido.optString("name")
		val tipo = when {
			nombre.endsWith(".epub", true) -> "application/epub+zip"
			nombre.endsWith(".pdf", true) -> "application/pdf"
			else -> "text/plain"
		}
		return Enlace("https://archive.org/download/$id/" + java.net.URLEncoder.encode(nombre, "UTF-8").replace("+", "%20"), tipo, d.rel, d.titulo)
	}

	/** El mejor archivo para leer: EPUB, luego PDF, luego otros. */
	fun mejorDescarga(descargas: List<Enlace>): Enlace? = descargas.sortedBy {
		when {
			it.tipo.contains("epub") && !it.url.contains("noimages") -> 0
			it.tipo.contains("epub") -> 1
			it.tipo.contains("fb2") -> 2
			it.tipo.contains("pdf") -> 3
			it.tipo.contains("mobi") || it.tipo.contains("kindle") -> 4
			it.tipo.startsWith("audio") -> 5
			it.tipo.contains("text/plain") -> 6
			else -> 9
		}
	}.firstOrNull()

	fun extension(tipo: String, url: String): String = when {
		tipo.contains("epub") -> "epub"
		tipo.contains("pdf") -> "pdf"
		tipo.contains("fb2") -> "fb2"
		tipo.contains("mobi") || tipo.contains("kindle") -> "mobi"
		tipo.contains("mpeg") -> "mp3"
		tipo.contains("mp4") || tipo.contains("m4b") -> "m4b"
		tipo.contains("text/plain") -> "txt"
		tipo.contains("html") -> "html"
		else -> url.substringBefore('?').substringAfterLast('.', "epub").take(5)
	}
}
