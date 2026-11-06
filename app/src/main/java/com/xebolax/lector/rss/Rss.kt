package com.xebolax.lector.rss

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import com.xebolax.lector.formatos.LectorHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date

/**
 * La sección de noticias: actualizar las fuentes, buscar y añadir fuentes,
 * OPML, descargar el artículo completo y preparar el artículo para leerlo.
 */
object Rss {

	/** Fuentes de ejemplo la primera vez (en español). */
	val PREDETERMINADAS = listOf(
		Triple("RTVE Noticias", "https://api2.rtve.es/rss/temas_noticias.xml", "Noticias"),
		Triple("El País", "https://feeds.elpais.com/mrss-s/pages/ep/site/elpais.com/portada", "Noticias"),
		Triple("elDiario.es", "https://www.eldiario.es/rss/", "Noticias"),
		Triple("Europa Press", "https://www.europapress.es/rss/rss.aspx", "Noticias"),
		Triple("Xataka", "https://www.xataka.com/feedburner.xml", "Tecnología"),
		Triple("Genbeta", "https://www.genbeta.com/feedburner.xml", "Tecnología"),
		Triple("ONCE", "https://www.once.es/rss.xml", "Accesibilidad"),
		Triple("Discapnet", "https://www.discapnet.es/rss/noticias-eventos.xml", "Accesibilidad"),
		Triple("Accesibilidad en la Web", "https://accesibilidadenlaweb.blogspot.com/feeds/posts/default", "Accesibilidad"),
		Triple("Usable y accesible", "https://olgacarreras.blogspot.com/feeds/posts/default", "Accesibilidad"),
	)

	class Resultado(val nuevos: Int, val errores: Int, val avisos: List<Pair<Fuente, RssLector.Elemento>>)

	fun prefs(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)

	/** La primera vez: las fuentes que había en la versión anterior (o las de ejemplo). */
	fun preparar(c: Context) {
		val p = prefs(c)
		if (p.getBoolean("rss_preparado", false)) return
		val db = RssBaseDatos.obtener(c)
		val antiguas = p.getString("fuentes_rss", null)
		if (antiguas != null) {
			runCatching {
				val a = org.json.JSONArray(antiguas)
				for (i in 0 until a.length()) {
					val o = a.getJSONObject(i)
					db.anadirFuente(o.optString("url"), o.optString("nombre"), categoria = "Noticias")
				}
			}
		} else {
			for ((nombre, url, categoria) in PREDETERMINADAS) db.anadirFuente(url, nombre, categoria = categoria)
		}
		p.edit().putBoolean("rss_preparado", true).apply()
	}

	/** Actualiza una fuente. Devuelve los artículos nuevos. */
	suspend fun actualizar(c: Context, f: Fuente): List<RssLector.Elemento> = withContext(Dispatchers.IO) {
		val db = RssBaseDatos.obtener(c)
		try {
			val r = RssLector.descargar(f.url, f.etag, f.modificado)
			val canal = r.canal
			if (canal == null) {
				db.guardarActualizacion(f.id, r.etag, r.modificado, null, null, "")
				return@withContext emptyList()
			}
			// Palabras silenciadas: esos artículos no se guardan
			val silenciadas = f.silenciar.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
			// «Solo con estas palabras»: si la fuente lo tiene, el resto no se guarda
			val soloCon = f.soloCon.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
			// Sin duplicados: lo que ya llegó por otra fuente (mismo enlace o mismo titular) no se guarda
			val (titulosOtras, enlacesOtras) = if (f.sinDuplicados) db.recibidosDeOtras(f.id) else emptyList<String>() to emptySet()
			val titularesOtras = titulosOtras.map { normal(it) }.filter { it.length > 15 }.toSet()
			val validos = canal.elementos.filter { e ->
				val texto = e.titulo.lowercase() + " " + e.resumen.lowercase()
				silenciadas.none { s -> texto.contains(s) } &&
					(soloCon.isEmpty() || soloCon.any { s -> texto.contains(s) }) &&
					(!f.sinDuplicados || e.enlace !in enlacesOtras && normal(e.titulo) !in titularesOtras)
			}
			// La primera vez solo los 20 más recientes quedan sin leer
			val primeraVez = f.ultima == 0L
			val nuevos = if (primeraVez && validos.size > 20) {
				val ordenados = validos.sortedByDescending { it.fecha }
				db.anadirArticulos(f.id, ordenados.drop(20), leidos = true)
				db.anadirArticulos(f.id, ordenados.take(20))
			} else db.anadirArticulos(f.id, validos)
			db.guardarActualizacion(f.id, r.etag, r.modificado, canal.titulo.takeIf { f.titulo.isBlank() || f.titulo == f.url }, canal.sitio, "", canal.idioma)
			nuevos
		} catch (e: Exception) {
			db.guardarActualizacion(f.id, null, null, null, null, e.message ?: e.javaClass.simpleName)
			throw e
		}
	}

	/** Actualiza todas las fuentes (4 a la vez). progreso(hechas, total). */
	suspend fun actualizarTodas(c: Context, progreso: (Int, Int) -> Unit = { _, _ -> }): Resultado = coroutineScope {
		val db = RssBaseDatos.obtener(c)
		val inicio = System.currentTimeMillis()
		var errores = 0
		var nuevos = 0
		// Con cuenta en un servidor, las fuentes de la cuenta se traen de allí
		var sincronizadas = emptySet<Long>()
		if (RssSincronizacion.configurado(c)) {
			runCatching { RssSincronizacion.sincronizar(c) }
				.onSuccess { nuevos += it.nuevos; prefs(c).edit().putString("rss_sinc_error", "").apply() }
				.onFailure { errores++; prefs(c).edit().putString("rss_sinc_error", it.message ?: it.javaClass.simpleName).apply() }
			sincronizadas = db.remotosDeFuentes().keys
		}
		val fuentes = db.fuentes().filter { !it.pausada && it.id !in sincronizadas }
		val limite = Semaphore(4)
		var hechas = 0
		val avisos = ArrayList<Pair<Fuente, RssLector.Elemento>>()
		fuentes.map { f ->
			async(Dispatchers.IO) {
				limite.withPermit {
					val r = runCatching { actualizar(c, f) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
					val lista = r.getOrDefault(emptyList())
					// Los contadores se tocan desde varias fuentes a la vez: dentro del bloqueo
					synchronized(avisos) {
						if (r.isFailure) errores++
						nuevos += lista.size
						if (f.avisar) lista.forEach { avisos += f to it }
						hechas++
						progreso(hechas, fuentes.size)
					}
				}
			}
		}.awaitAll()
		// Limpiar y aplicar reglas toca la base de datos: fuera del hilo principal
		withContext(Dispatchers.IO) {
			db.limpiar(prefs(c).getString("rss_dias", "30")?.toIntOrNull() ?: 30)
			// Reglas y alertas con los artículos que acaban de llegar
			runCatching { Reglas.aplicar(c, db.recibidosDesde(inicio)) }
		}
		prefs(c).edit().putLong("rss_ultima", System.currentTimeMillis()).apply()
		Resultado(nuevos, errores, avisos)
	}

	// --- Buscar y añadir fuentes ------------------------------------------------------------

	class Sugerencia(val titulo: String, val url: String, val descripcion: String, val suscriptores: Long)

	/** Busca fuentes por tema o nombre en el buscador público de Feedly (no hace falta cuenta). */
	suspend fun buscarFuentes(texto: String): List<Sugerencia> = withContext(Dispatchers.IO) {
		val url = "https://cloud.feedly.com/v3/search/feeds?count=40&locale=es&query=" + URLEncoder.encode(texto, "UTF-8")
		val json = JSONObject(RssLector.descargarTexto(url))
		val r = json.optJSONArray("results") ?: return@withContext emptyList()
		(0 until r.length()).mapNotNull { i ->
			val o = r.optJSONObject(i) ?: return@mapNotNull null
			val fuente = o.optString("feedId").removePrefix("feed/").ifBlank { return@mapNotNull null }
			Sugerencia(o.optString("title").ifBlank { fuente }, fuente, Jsoup.parse(o.optString("description")).text(), o.optLong("subscribers"))
		}
	}

	/** Añade una fuente a partir de una dirección (de la fuente o de su web). Devuelve las encontradas si hay varias. */
	suspend fun descubrir(direccion: String): List<Pair<String, String>> = withContext(Dispatchers.IO) { RssLector.descubrir(direccion) }

	// --- OPML -------------------------------------------------------------------------------

	fun exportarOpml(c: Context): String {
		val db = RssBaseDatos.obtener(c)
		val fuentes = db.fuentes()
		fun e(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
		fun fuente(f: Fuente) = "<outline type=\"rss\" text=\"${e(f.titulo)}\" title=\"${e(f.titulo)}\" xmlUrl=\"${e(f.url)}\" htmlUrl=\"${e(f.sitio)}\"/>"
		return buildString {
			appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
			appendLine("<opml version=\"2.0\"><head><title>Lector Universal</title></head><body>")
			// Las carpetas en su orden (también las vacías), con sus fuentes en su orden; después las que no tienen carpeta
			for (carpeta in db.categorias()) {
				appendLine("<outline text=\"${e(carpeta)}\" title=\"${e(carpeta)}\">")
				fuentes.filter { it.categoria.equals(carpeta, ignoreCase = true) }.forEach { appendLine(fuente(it)) }
				appendLine("</outline>")
			}
			fuentes.filter { f -> f.categoria.isBlank() || db.categorias().none { it.equals(f.categoria, ignoreCase = true) } }.forEach { appendLine(fuente(it)) }
			appendLine("</body></opml>")
		}
	}

	/** Importa un OPML (de Feedly, Inoreader, otra app...). Devuelve cuántas fuentes nuevas. */
	fun importarOpml(c: Context, texto: String): Int {
		val db = RssBaseDatos.obtener(c)
		val antes = db.fuentes().size
		val doc = Jsoup.parse(texto, "", Parser.xmlParser())
		// Las carpetas primero, en su orden (también las vacías)
		doc.select("body > outline:not([xmlUrl])").forEach { o -> db.crearCarpeta(o.attr("title").ifBlank { o.attr("text") }) }
		for (o in doc.select("outline[xmlUrl]")) {
			val url = o.attr("xmlUrl").trim()
			if (url.isEmpty()) continue
			val padre = o.parent()?.takeIf { it.tagName() == "outline" && !it.hasAttr("xmlUrl") }
			val categoria = padre?.attr("title")?.ifBlank { padre.attr("text") }.orEmpty()
			db.anadirFuente(url, o.attr("title").ifBlank { o.attr("text") }.ifBlank { url }, o.attr("htmlUrl"), categoria)
		}
		return db.fuentes().size - antes
	}

	// --- Leer ------------------------------------------------------------------------------

	/** ¿Hay que descargar el artículo completo? (el RSS solo trae un resumen corto) */
	fun hayQueCompletar(c: Context, a: Articulo, f: Fuente?): Boolean {
		if (a.completo || a.enlace.isBlank() || a.esPodcast && a.contenido.length > 100) return false
		val siempre = f?.textoCompleto == true || prefs(c).getBoolean("rss_texto_completo", false)
		return siempre || Jsoup.parse(a.contenido).text().length < 500
	}

	/**
	 * Busca el artículo completo en su web (ArticuloCompleto) y lo guarda si
	 * mejora lo que trae el RSS. Si la web no tiene más, se apunta para no
	 * volver a intentarlo al abrirlo (con «Descargar el artículo completo» se
	 * puede pedir otra vez).
	 */
	suspend fun completar(c: Context, a: Articulo): ArticuloCompleto.Extraido {
		val r = ArticuloCompleto.obtener(c, a)
		val db = RssBaseDatos.obtener(c)
		when (r.resultado) {
			ArticuloCompleto.Resultado.COMPLETO -> db.guardarContenidoCompleto(a.id, r.html)
			ArticuloCompleto.Resultado.SIN_MAS_TEXTO -> db.guardarContenidoCompleto(a.id, a.contenido)
			else -> Unit
		}
		return r
	}

	/** Qué decir del resultado. */
	fun motivo(c: Context, r: ArticuloCompleto.Extraido): String = when (r.resultado) {
		ArticuloCompleto.Resultado.COMPLETO -> if (r.paginas > 1) c.getString(com.xebolax.lector.R.string.rss_completo_paginas, r.paginas) else c.getString(com.xebolax.lector.R.string.rss_completo_descargado)
		ArticuloCompleto.Resultado.SUSCRIPTORES -> c.getString(com.xebolax.lector.R.string.rss_solo_suscriptores)
		ArticuloCompleto.Resultado.SIN_MAS_TEXTO -> c.getString(com.xebolax.lector.R.string.rss_sin_mas_texto)
		ArticuloCompleto.Resultado.ERROR -> c.getString(com.xebolax.lector.R.string.rss_completo_error, r.detalle)
	}

	/**
	 * Al actualizar, descarga ya el artículo completo de los nuevos que solo
	 * traen un resumen (para leerlos sin conexión): de las fuentes con
	 * «Descargar siempre el artículo completo» o de todas si está en Ajustes.
	 * Como mucho 15 cada vez, para no tardar ni gastar datos de más.
	 */
	suspend fun completarPendientes(c: Context) = withContext(Dispatchers.IO) {
		val todas = prefs(c).getBoolean("rss_completo_al_actualizar", false)
		if (prefs(c).getBoolean("rss_solo_wifi", false) && (c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered) return@withContext
		val db = RssBaseDatos.obtener(c)
		val fuentes = db.fuentes().associateBy { it.id }
		db.sinLeer().asSequence()
			.filter { !it.completo && it.enlace.isNotBlank() && !it.esPodcast && (todas || fuentes[it.fuente]?.textoCompleto == true) }
			.filter { Jsoup.parse(it.contenido).text().length < 600 }
			.take(15)
			.forEach { runCatching { completar(c, it) } }
	}

	private fun normal(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
		.replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()

	fun fechaLegible(ms: Long): String = if (ms <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

	/** Atributo lang (vacío si la fuente no dice su idioma). */
	private fun lang(idioma: String) = if (idioma.isBlank()) "" else " lang=\"" + escapar(idioma.trim()).replace("\"", "") + "\""

	private fun escapar(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

	/** Quita las imágenes cuya descripción es el propio titular (se leería dos veces). */
	private fun limpiarContenido(contenido: String, titulo: String): String {
		if (!contenido.contains("<img", ignoreCase = true)) return contenido
		val doc = Jsoup.parseBodyFragment(contenido)
		val t = normal(titulo)
		doc.select("img").forEach { img -> if (t.isNotEmpty() && normal(img.attr("alt")) == t) img.remove() }
		return doc.body().html()
	}

	/** HTML de un artículo para el lector: título, fuente y fecha, el texto y el enlace original. */
	fun html(c: Context, a: Articulo, contenidoOriginal: String = a.contenido): String = buildString {
		val contenido = limpiarContenido(contenidoOriginal, a.titulo)
		append("<!DOCTYPE html><html").append(lang(a.idiomaFuente)).append("><head><meta charset=\"utf-8\"><title>").append(escapar(a.titulo)).append("</title></head><body>")
		append("<h1>").append(escapar(a.titulo)).append("</h1>")
		val datos = listOf(a.nombreFuente, a.autor, fechaLegible(a.fecha)).filter { it.isNotBlank() }.joinToString(" · ")
		if (datos.isNotBlank()) append("<p>").append(escapar(datos)).append("</p>")
		append(contenido.ifBlank { "<p>" + escapar(a.resumen) + "</p>" })
		if (a.enlace.isNotBlank()) {
			append("<p><a href=\"").append(escapar(a.enlace)).append("\">")
				.append(escapar(c.getString(com.xebolax.lector.R.string.rss_enlace_original))).append("</a></p>")
		}
		append("</body></html>")
	}

	/** Varios artículos en un solo documento (para leerlos seguidos en voz alta). */
	fun htmlVarios(c: Context, titulo: String, lista: List<Articulo>): String = buildString {
		append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(escapar(titulo)).append("</title></head><body>")
		for (a in lista) {
			// Cada artículo en el idioma de su fuente (la voz cambia de idioma si hace falta)
			append("<div").append(lang(a.idiomaFuente)).append(">")
			append("<h1>").append(escapar(a.titulo)).append("</h1>")
			val datos = listOf(a.nombreFuente, fechaLegible(a.fecha)).filter { it.isNotBlank() }.joinToString(" · ")
			if (datos.isNotBlank()) append("<p>").append(escapar(datos)).append("</p>")
			append(a.contenido.ifBlank { "<p>" + escapar(a.resumen) + "</p>" })
			append("</div>")
		}
		append("</body></html>")
	}

	/** Solo titulares: cada uno es un encabezado con enlace a su artículo, y debajo su fuente y fecha. */
	fun htmlTitulares(c: Context, titulo: String, lista: List<Articulo>): String = buildString {
		append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(escapar(titulo)).append("</title></head><body>")
		append("<h1>").append(escapar(titulo)).append("</h1>")
		for (a in lista) {
			append("<h2").append(lang(a.idiomaFuente)).append("><a href=\"").append(RssAcciones.ESQUEMA_ARTICULO).append(a.id).append("\">").append(escapar(a.titulo)).append("</a></h2>")
			val datos = listOf(a.nombreFuente, fechaLegible(a.fecha)).filter { it.isNotBlank() }.joinToString(" · ")
			if (datos.isNotBlank()) append("<p>").append(escapar(datos)).append("</p>")
		}
		append("</body></html>")
	}

	/** Escribe el HTML en la caché (fuera de la biblioteca) y devuelve su dirección. */
	fun archivo(c: Context, nombre: String, html: String): Uri {
		val carpeta = File(c.cacheDir, "rss").apply { mkdirs() }
		val f = File(carpeta, "$nombre.html")
		f.writeText(html)
		return Uri.fromFile(f)
	}

	/** Guarda el artículo en la biblioteca principal (solo si se pide). Devuelve el id del libro. */
	fun guardarEnBiblioteca(c: Context, a: Articulo, contenido: String): Long {
		val carpeta = File(c.filesDir, "biblioteca/noticias").apply { mkdirs() }
		val nombre = a.titulo.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "articulo" } + " " + a.id + ".html"
		val f = File(carpeta, nombre)
		f.writeText(html(c, a, contenido))
		val db = com.xebolax.lector.datos.BaseDatos.obtener(c)
		val uri = Uri.fromFile(f).toString()
		val id = db.libroPorUri(uri)?.id ?: db.anadirLibro(uri, f.name, a.titulo, a.autor.ifBlank { a.nombreFuente },
			com.xebolax.lector.formatos.Formato.HTML.name, false, f.length(), null)
		RssBaseDatos.obtener(c).marcarEnBiblioteca(a.id, id)
		return id
	}
}
