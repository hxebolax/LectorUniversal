package com.xebolax.lector.rss

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * El artículo completo a partir de su web, cuando el RSS solo trae un resumen
 * (como el lector de SocialRSS):
 *
 * 1. Si la cuenta de noticias es de Miniflux, se le pide al servidor.
 * 2. Se descarga la página como un navegador (con tu sesión si estás
 *    suscrito a ese medio y has entrado desde Ajustes).
 * 3. Varios candidatos: el bloque del titular, el de más párrafos (como
 *    Readability), el articleBody de los datos JSON-LD de la página y
 *    article / main. Gana el más completo.
 * 4. Se quitan las listas de «noticias relacionadas», el titular repetido y
 *    lo que no es texto; si la noticia sigue en otras páginas, se juntan.
 * 5. Si solo hay una entradilla con un aviso de «solo para suscriptores», se
 *    dice para que puedas entrar con tu cuenta o abrirlo en el navegador.
 */
object ArticuloCompleto {

	enum class Resultado {
		/** Texto completo conseguido. */
		COMPLETO,
		/** Solo para suscriptores (y no hay sesión en ese medio). */
		SUSCRIPTORES,
		/** La página no tiene más texto que el que ya trae el RSS. */
		SIN_MAS_TEXTO,
		/** No se pudo descargar la página. */
		ERROR,
	}

	class Extraido(val html: String, val resultado: Resultado, val paginas: Int = 1, val detalle: String = "")

	private const val MINIMO = 200
	private const val MAX_BYTES = 8 shl 20
	private const val MAX_PAGINAS = 6

	private val CABECERAS = mapOf(
		"User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36",
		"Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
		"Accept-Language" to "es-ES,es;q=0.9,en;q=0.6",
		"Upgrade-Insecure-Requests" to "1",
	)

	/** Frases de los avisos de «solo para suscriptores». */
	private val FRASES_MURO = listOf(
		"solo para suscriptores", "exclusivo para suscriptores", "exclusiva para suscriptores", "contenido exclusivo para",
		"hazte suscriptor", "suscríbete para seguir leyendo", "suscríbete para leer", "para seguir leyendo",
		"para continuar leyendo", "ya eres suscriptor", "ya soy suscriptor", "inicia sesión para seguir", "regístrate para seguir",
		"este contenido es exclusivo", "artículo exclusivo para", "noticia exclusiva para",
		"subscribe to continue", "subscribe to read", "subscribers only", "for subscribers", "to continue reading",
		"already a subscriber", "sign in to continue", "create a free account to continue",
	)

	/** Lo que no es el artículo. */
	private const val RUIDO = "script,style,noscript,template,nav,header,footer,aside,form,iframe,button,svg,canvas,select,input,dialog,[role=navigation],[role=banner],[role=contentinfo],[role=complementary],[aria-hidden=true],.a2a_kit,.addtoany_share_save_container"

	/** Clases e ids de bloques que no son el texto (compartir, comentarios, relacionadas...). */
	private val BLOQUES_RUIDO = Regex("(^|[-_ ])(share|sharing|social|comments?|related|relacionad|newsletter|promo|banner|cookies?|publicidad|advert|ads?|sidebar|breadcrumbs?|tags|labels|etiquetas|recomendad|outbrain|taboola|menu|footer|pager|paginacion|byline-share)([-_ ]|$)", RegexOption.IGNORE_CASE)

	// --- Lo principal -----------------------------------------------------------------------

	/**
	 * Busca el artículo completo. actual: lo que ya trae el RSS (si lo nuevo no
	 * mejora en al menos un 10 %, se queda como está).
	 */
	suspend fun obtener(c: Context, a: Articulo): Extraido = withContext(Dispatchers.IO) {
		val actual = Jsoup.parse(a.contenido).text().length
		// 1. Miniflux lo saca él mismo
		RssSincronizacion.textoDelServidor(c, a)?.let { html ->
			if (Jsoup.parse(html).text().length > actual * 1.1 && Jsoup.parse(html).text().length >= MINIMO) return@withContext Extraido(html, Resultado.COMPLETO, detalle = "Miniflux")
		}
		val sesion = conSesion(c, a.enlace)
		val doc = try {
			descargar(a.enlace, sesion)
		} catch (e: Exception) {
			return@withContext Extraido("", Resultado.ERROR, detalle = e.message ?: e.javaClass.simpleName)
		}
		var cuerpo = extraer(doc, a.titulo)
		var paginas = 1
		// 4. Noticias partidas en varias páginas
		if (cuerpo != null) {
			var siguiente = paginaSiguiente(doc, a.enlace, 1)
			val vistos = HashSet<String>().apply { cuerpo!!.select("p").forEach { add(it.text().trim()) } }
			while (siguiente != null && paginas < MAX_PAGINAS) {
				val otra = runCatching { descargar(siguiente, sesion) }.getOrNull() ?: break
				val trozo = extraer(otra, a.titulo) ?: break
				paginas++
				trozo.children().forEach { hijo -> if (hijo.text().isNotBlank() && vistos.add(hijo.text().trim())) cuerpo!!.appendChild(hijo.clone()) }
				siguiente = paginaSiguiente(otra, siguiente, paginas)
			}
		}
		val texto = cuerpo?.text().orEmpty()
		when {
			cuerpo == null || texto.length < MINIMO -> Extraido("", if (esMuro(doc.text(), texto) && !sesion) Resultado.SUSCRIPTORES else Resultado.SIN_MAS_TEXTO)
			esMuro(texto, texto) && !sesion -> Extraido(cuerpo.html(), Resultado.SUSCRIPTORES)
			texto.length <= actual * 1.1 -> Extraido("", Resultado.SIN_MAS_TEXTO)
			else -> Extraido(cuerpo.html(), Resultado.COMPLETO, paginas)
		}
	}

	// --- Descarga ----------------------------------------------------------------------------

	/** Medios en los que estás suscrito (Ajustes, Noticias): se descarga con tu sesión. */
	fun suscripciones(c: Context): List<String> =
		Rss.prefs(c).getString("rss_suscripciones", "").orEmpty().lines().map { dominio(it) }.filter { it.isNotBlank() }.distinct()

	fun guardarSuscripciones(c: Context, lista: List<String>) {
		Rss.prefs(c).edit().putString("rss_suscripciones", lista.map { dominio(it) }.filter { it.isNotBlank() }.distinct().joinToString("\n")).apply()
	}

	/** «https://www.elpais.com/algo» → «elpais.com». */
	fun dominio(s: String): String {
		val t = s.trim().lowercase()
		val host = if (t.contains("://")) Uri.parse(t).host.orEmpty() else t.substringBefore('/')
		return host.substringBefore(':').removePrefix("www.").removePrefix("amp.")
	}

	private fun estaSuscrito(c: Context, url: String): Boolean {
		val d = dominio(url)
		return suscripciones(c).any { d == it || d.endsWith(".$it") }
	}

	/** ¿Se usa la sesión de ese medio? (estás suscrito y hay cookies de haber entrado) */
	private fun conSesion(c: Context, url: String): Boolean =
		estaSuscrito(c, url) && runCatching { !CookieManager.getInstance().getCookie(url).isNullOrBlank() }.getOrDefault(false)

	private fun descargar(url: String, conSesion: Boolean): Document {
		var actual = url
		repeat(6) {
			val c = URL(actual).openConnection() as HttpURLConnection
			try {
				c.instanceFollowRedirects = false
				c.connectTimeout = 10_000
				c.readTimeout = 25_000
				CABECERAS.forEach { (k, v) -> c.setRequestProperty(k, v) }
				// Solo tu sesión del medio en el que estás suscrito; si no, sin cookies
				if (conSesion) CookieManager.getInstance().getCookie(actual)?.let { c.setRequestProperty("Cookie", it) }
				val codigo = c.responseCode
				if (codigo in 300..399) {
					actual = URL(URL(actual), c.getHeaderField("Location") ?: error("HTTP $codigo")).toString()
					return@repeat
				}
				if (codigo !in 200..299) error("HTTP $codigo")
				val tipo = c.contentType.orEmpty()
				if (tipo.isNotBlank() && !tipo.contains("html") && !tipo.contains("xml")) error(tipo)
				val bytes = c.inputStream.use { leerHasta(it) }
				return Jsoup.parse(decodificar(bytes, tipo), actual)
			} finally {
				c.disconnect()
			}
		}
		error("Demasiadas redirecciones")
	}

	private fun leerHasta(entrada: java.io.InputStream): ByteArray {
		val salida = ByteArrayOutputStream()
		val buf = ByteArray(16384)
		while (salida.size() < MAX_BYTES) {
			val n = entrada.read(buf)
			if (n < 0) break
			salida.write(buf, 0, n)
		}
		return salida.toByteArray()
	}

	/** Juego de caracteres: el de la cabecera, el del <meta> o UTF-8 (y si no cuadra, Windows-1252). */
	private fun decodificar(bytes: ByteArray, tipo: String): String {
		fun charset(nombre: String?) = nombre?.trim('"', '\'', ' ')?.takeIf { it.isNotBlank() }?.let { runCatching { Charset.forName(it) }.getOrNull() }
		charset(Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(tipo)?.groupValues?.get(1))?.let { return String(bytes, it) }
		val cabeza = String(bytes, 0, minOf(bytes.size, 8192), Charsets.ISO_8859_1)
		charset(Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(cabeza)?.groupValues?.get(1))?.let { return String(bytes, it) }
		val utf8 = String(bytes, Charsets.UTF_8)
		return if (utf8.contains('�')) String(bytes, Charset.forName("windows-1252")) else utf8
	}

	// --- Extracción --------------------------------------------------------------------------

	/** El cuerpo del artículo, limpio, o null si no hay suficiente texto. */
	fun extraer(original: Document, titulo: String): Element? {
		val jsonld = desdeJsonLd(original)
		val doc = original.clone()
		doc.select(RUIDO).remove()
		doc.select("[style~=(?i)display:\\s*none]").remove()
		val clave = normal(titulo).take(40)
		val titulares = doc.select("h1, h2").filter { clave.isNotEmpty() && normal(it.text()).contains(clave) }
		val delTitular = com.xebolax.lector.formatos.LectorHtml.bloqueDelTitulo(doc, titulares)
		val porParrafos = porParrafos(doc)
		val contenedor = (doc.select("article") + doc.select("main") + doc.select("[role=main]")).maxByOrNull { it.text().length }
		// Por orden de preferencia; otro gana si trae bastante más texto
		var elegido = listOfNotNull(delTitular, porParrafos, contenedor).map { limpiar(it.clone(), titulo) }.firstOrNull { it.text().length >= MINIMO }
		for (otro in listOfNotNull(porParrafos, contenedor).map { limpiar(it.clone(), titulo) }) {
			if (elegido == null || otro.text().length > elegido.text().length * 1.25) elegido = otro.takeIf { it.text().length >= MINIMO } ?: elegido
		}
		if (jsonld != null && jsonld.text().length >= MINIMO && (elegido == null || jsonld.text().length > elegido.text().length * 1.3)) elegido = jsonld
		return elegido?.takeIf { it.text().length >= MINIMO }
	}

	/** El contenedor con más texto en párrafos (la idea de Readability). */
	private fun porParrafos(doc: Document): Element? {
		val puntos = HashMap<Element, Double>()
		for (p in doc.select("p, pre, blockquote")) {
			val largo = p.text().length
			if (largo < 25) continue
			val enlaces = p.select("a").sumOf { it.text().length }
			if (enlaces > largo * 0.5) continue
			val valor = 1.0 + minOf(largo / 100.0, 3.0) + p.text().count { it == ',' }
			p.parent()?.let { puntos[it] = (puntos[it] ?: 0.0) + valor }
			p.parent()?.parent()?.let { puntos[it] = (puntos[it] ?: 0.0) + valor / 2 }
		}
		return puntos.entries.filter { it.key !== doc.body() }.maxByOrNull { it.value }?.key
	}

	/** El articleBody más largo de los datos estructurados (JSON-LD) de la página. */
	private fun desdeJsonLd(doc: Document): Element? {
		var mejor = ""
		fun recorrer(v: Any?) {
			when (v) {
				is JSONArray -> for (i in 0 until v.length()) recorrer(v.opt(i))
				is JSONObject -> {
					val cuerpo = v.optString("articleBody")
					if (cuerpo.length > mejor.length) mejor = cuerpo
					recorrer(v.opt("@graph"))
					recorrer(v.opt("mainEntity"))
				}
			}
		}
		for (s in doc.select("script[type=application/ld+json]")) {
			val datos = s.data().trim().ifBlank { continue }
			recorrer(runCatching { if (datos.startsWith("[")) JSONArray(datos) else JSONObject(datos) }.getOrNull())
		}
		if (mejor.length < MINIMO) return null
		val html = if (Regex("<(p|br|div)[\\s>/]", RegexOption.IGNORE_CASE).containsMatchIn(mejor)) mejor
		else mejor.split(Regex("\\n\\s*\\n|\\n")).filter { it.isNotBlank() }.joinToString("") { "<p>" + escapar(it.trim()) + "</p>" }
		return Jsoup.parseBodyFragment(html).body().apply { tagName("div") }
	}

	/** Quita del bloque lo que no es texto del artículo. */
	private fun limpiar(bloque: Element, titulo: String): Element {
		bloque.select("*").filter { e ->
			val nombre = e.className() + " " + e.id()
			e.tagName() in setOf("div", "section", "ul", "ol", "span", "p") && BLOQUES_RUIDO.containsMatchIn(nombre) && e.select("p").size < 3
		}.forEach { it.remove() }
		// Listas de enlaces («noticias relacionadas»): casi todo su texto son enlaces
		bloque.select("ul, ol").filter { l ->
			val items = l.select("> li")
			items.size >= 2 && items.all { li -> li.text().isNotBlank() && li.select("a").sumOf { it.text().length } >= li.text().length * 0.6 }
		}.forEach { it.remove() }
		// Párrafos que solo son un enlace a la portada o al principio de la página («Página de inicio», «Subir»)
		bloque.select("p").filter { p ->
			val a = p.select("a")
			a.size == 1 && a.text().trim() == p.text().trim() && a.attr("href").let { it.startsWith("#") || it == "/" || it.isBlank() }
		}.forEach { it.remove() }
		// El titular ya va arriba
		val t = normal(titulo)
		bloque.select("h1, h2, h3, p").firstOrNull()?.let { if (t.isNotEmpty() && normal(it.text()) == t) it.remove() }
		bloque.select("a[href]").forEach { it.attr("href", it.absUrl("href")) }
		bloque.select("img[src]").forEach { it.attr("src", it.absUrl("src")) }
		bloque.select("img[data-src]").forEach { if (!it.hasAttr("src") || it.attr("src").startsWith("data:")) it.attr("src", it.absUrl("data-src")) }
		// Vacíos que quedan
		bloque.select("div, section, span").filter { it.text().isBlank() && it.select("img, figure, table").isEmpty() }.forEach { it.remove() }
		// Rótulos que quedan colgando al final sin nada detrás («Compártelo en:», «Etiquetas:»...)
		for (e in bloque.select("p, h2, h3, h4, h5, h6, strong").reversed()) {
			val t = e.text().trim()
			if (t.isEmpty()) continue
			if (t.endsWith(":") && t.length < 60) e.remove() else break
		}
		return bloque
	}

	/** ¿Es solo una entradilla con el aviso de suscriptores? */
	private fun esMuro(pagina: String, texto: String): Boolean {
		val bajo = pagina.lowercase()
		val frase = FRASES_MURO.firstOrNull { bajo.contains(it) } ?: return false
		if (texto.length < 1200) return true
		// La frase al final del texto: el artículo se corta ahí
		val pos = texto.lowercase().lastIndexOf(frase)
		return pos >= 0 && pos > texto.length * 0.7
	}

	/** La página siguiente de la misma noticia (rel=next o «Siguiente» / el número siguiente). */
	private fun paginaSiguiente(doc: Document, url: String, actual: Int): String? {
		fun misma(otra: String): Boolean {
			if (otra.isBlank() || otra == url) return false
			val a = Uri.parse(url)
			val b = Uri.parse(otra)
			if (a.host != b.host) return false
			// Misma ruta salvo el número de página
			val raiz = { s: String -> s.replace(Regex("[/_-]?(page|pagina|p)?[/=_-]?\\d+(\\.html?)?/?$", RegexOption.IGNORE_CASE), "").trimEnd('/') }
			return raiz(a.path.orEmpty()) == raiz(b.path.orEmpty()) && (otra.contains((actual + 1).toString()))
		}
		doc.select("link[rel=next], a[rel=next]").map { it.absUrl("href") }.firstOrNull { misma(it) }?.let { return it }
		return doc.select("a[href]").firstOrNull { e ->
			val t = e.text().trim().lowercase()
			(t == "siguiente" || t == "siguiente página" || t == "next" || t == "next page" || t == (actual + 1).toString() || t == "»") && misma(e.absUrl("href"))
		}?.absUrl("href")
	}

	private fun normal(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
		.replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()

	private fun escapar(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
