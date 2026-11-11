package com.xebolax.lector.ui

import android.content.Context
import android.net.Uri
import com.xebolax.lector.BuildConfig
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.formatos.ContextoLectura
import com.xebolax.lector.formatos.Formato
import com.xebolax.lector.formatos.Fuente
import com.xebolax.lector.formatos.LectorHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarga una página web, guarda su contenido principal y la añade a la
 * biblioteca o, si compartido, a Compartidos (devuelve el id del libro).
 */
object PaginaWeb {

	private fun normal(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
		.replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()

	/** titulo: el titular esperado (noticias), para guardar solo el artículo. */
	/** Lo que se sabe de un artículo guardado (para el autor, el medio y el tiempo de escucha). */
	class Datos(val autor: String = "", val medio: String = "", val fecha: String = "", val palabras: Int = 0)

	/**
	 * Quita de una dirección lo que solo sirve para seguir de dónde vienes
	 * (utm_*, fbclid, gclid…) y el «#» final: así el mismo artículo compartido
	 * desde sitios distintos se reconoce como el mismo.
	 */
	fun limpiarUrl(url: String): String = runCatching {
		val u = Uri.parse(url.trim())
		val rastreo = Regex("^(utm_.*|fbclid|gclid|dclid|mc_cid|mc_eid|igshid|si|ref_src|ref_url|_ga|yclid|msclkid|twclid|wt_mc|cmpid|ocid|s_cid)$", RegexOption.IGNORE_CASE)
		val b = u.buildUpon().clearQuery().fragment(null)
		for (k in u.queryParameterNames) if (!rastreo.matches(k)) u.getQueryParameters(k).forEach { v -> b.appendQueryParameter(k, v) }
		b.build().toString()
	}.getOrDefault(url)

	/** forzar: descargarla otra vez aunque ya esté guardada (Volver a descargar). */
	suspend fun guardar(c: Context, direccion: String, titulo: String? = null, compartido: Boolean = false, forzar: Boolean = false): Long {
		val url = limpiarUrl(if (direccion.startsWith("http")) direccion else "https://$direccion")
		var datos = Datos()
		val (titulo, archivo) = withContext(Dispatchers.IO) {
			val conexion = URL(url).openConnection() as HttpURLConnection
			conexion.connectTimeout = 20_000
			conexion.readTimeout = 30_000
			conexion.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LectorUniversal/${BuildConfig.VERSION_NAME}")
			// Medio en el que estás suscrito y has entrado (Ajustes, Noticias): con tu sesión, el artículo completo
			com.xebolax.lector.rss.ArticuloCompleto.cookiesDe(c, url)?.let { conexion.setRequestProperty("Cookie", it) }
			var tipo = ""
			var disposicion: String? = null
			var final = url
			var destinoArchivo: File? = null
			val bytes = try {
				conexion.inputStream.use { entrada ->
					tipo = conexion.contentType.orEmpty()
					disposicion = conexion.getHeaderField("Content-Disposition")
					final = conexion.url.toString()
					// Lo primero dice qué es: una página o un documento (PDF, EPUB, audio, ZIP...)
					val cabecera = ByteArray(16)
					var leidos = 0
					while (leidos < cabecera.size) {
						val r = entrada.read(cabecera, leidos, cabecera.size - leidos)
						if (r < 0) break
						leidos += r
					}
					val porContenido = com.xebolax.lector.catalogo.NombreDescarga.extensionDeContenido(cabecera.copyOf(minOf(leidos, 8)))
					val noEsPagina = !tipo.contains("html") && !tipo.contains("xml") && (porContenido.isNotEmpty() ||
						com.xebolax.lector.catalogo.NombreDescarga.extensionDeTipo(tipo).let { it.isNotEmpty() && it != "html" && it != "txt" })
					if (noEsPagina) {
						// Un documento: se guarda tal cual con su nombre, a disco por bloques (puede ser muy grande)
						var nombre = com.xebolax.lector.catalogo.NombreDescarga.elegir(disposicion, final, tipo, null, null, "documento")
						if (!nombre.contains('.') && porContenido.isNotEmpty()) nombre += ".$porContenido"
						val carpeta = File(c.filesDir, "biblioteca/descargas").apply { mkdirs() }
						var destino = File(carpeta, nombre)
						var n = 2
						while (destino.exists()) destino = File(carpeta, nombre.substringBeforeLast('.') + " ($n)." + nombre.substringAfterLast('.', "")).also { n++ }
						val temporal = File(destino.path + ".parte")
						try {
							temporal.outputStream().use { salida ->
								salida.write(cabecera, 0, leidos)
								val bloque = ByteArray(256 * 1024)
								while (true) {
									ensureActive()
									val r = entrada.read(bloque)
									if (r < 0) break
									salida.write(bloque, 0, r)
								}
							}
							if (!temporal.renameTo(destino)) throw java.io.IOException(c.getString(com.xebolax.lector.R.string.error_guardar_archivo))
						} finally {
							temporal.delete()
						}
						destinoArchivo = destino
						ByteArray(0)
					} else {
						// Una página: como mucho 10 MB
						val salida = java.io.ByteArrayOutputStream()
						salida.write(cabecera, 0, leidos)
						val bloque = ByteArray(64 * 1024)
						while (salida.size() < 10 * 1024 * 1024) {
							ensureActive()
							val r = entrada.read(bloque)
							if (r < 0) break
							salida.write(bloque, 0, r)
						}
						salida.toByteArray()
					}
				}
			} finally {
				conexion.disconnect()
			}
			destinoArchivo?.let { return@withContext null to it }
			val html = com.xebolax.lector.modelo.Textos.decodificar(bytes, "utf-8")
			val doc = LectorHtml.convertir(html, ContextoLectura(c), url, url)
			val carpeta = File(c.filesDir, "web").apply { mkdirs() }
			val destino = File(carpeta, Fuente.hash(url).take(16) + ".html")
			val original = org.jsoup.Jsoup.parse(html, url)
			datos = datosDe(original)
			val pagina = original.clone().apply { select("script,style,noscript").remove() }
			// Una página que llega sin titular (Compartidos, Abrir una página web): el artículo, con el método de Noticias
			// (sin menús, «Lee también» ni avisos), y delante su titular y de quién es
			if (titulo.isNullOrBlank()) {
				val titular = doc.titulo.ifBlank { original.title() }
				val articulo = runCatching { com.xebolax.lector.rss.ArticuloCompleto.extraer(original, titular) }.getOrNull()
				if (articulo != null && articulo.text().length >= 200) {
					pagina.body().empty()
					pagina.body().appendElement("h1").text(titular)
					val linea = listOf(datos.autor, datos.medio, datos.fecha).filter { it.isNotBlank() }.joinToString(" · ")
					if (linea.isNotBlank()) pagina.body().appendElement("p").text(linea)
					pagina.body().appendChild(articulo.clone())
				}
			}
			datos = Datos(datos.autor, datos.medio, datos.fecha, pagina.body()?.text().orEmpty().split(Regex("\\s+")).count { it.length > 1 })
			// Noticia: si se encuentra el titular, solo el artículo (sin menús de la web)
			if (!titulo.isNullOrBlank()) {
				val clave = normal(titulo).take(40)
				val candidatos = pagina.select("h1, h2").filter { normal(it.text()).contains(clave) || clave.contains(normal(it.text()).take(40).ifBlank { "\u0000" }) }
				LectorHtml.bloqueDelTitulo(pagina, candidatos)?.let { bloque ->
					val copia = bloque.clone()
					pagina.body().empty().appendChild(copia)
				}
			}
			destino.writeText(pagina.outerHtml())
			doc.titulo to destino
		}
		val db = BaseDatos.obtener(c)
		db.libroPorUri(Uri.fromFile(archivo).toString())?.let { ya ->
			// Ya estaba (Volver a descargar): se actualizan su título y sus datos
			if (forzar && titulo != null) {
				db.renombrar(ya.id, titulo.ifBlank { ya.titulo }, datos.autor.ifBlank { ya.autor })
				guardarDatos(db, ya.id, datos, pendiente = false)
			}
			return ya.id
		}
		// Un documento descargado: se añade con su formato real y se leen su título y autor por detrás
		if (titulo == null) {
			val nuevo = com.xebolax.lector.biblioteca.Importador.anadirArchivos(c, listOf(Uri.fromFile(archivo)), null).anadidos.firstOrNull()
			val id = nuevo ?: db.libroPorUri(Uri.fromFile(archivo).toString())?.id ?: error(c.getString(com.xebolax.lector.R.string.error_guardar_archivo))
			if (compartido && nuevo != null) db.moverACompartidos(id, url)
			com.xebolax.lector.LectorApp.ambito.launch { com.xebolax.lector.biblioteca.Importador.completarDatos(c.applicationContext, listOf(id)) }
			return id
		}
		val id = if (compartido) db.anadirCompartido(Uri.fromFile(archivo).toString(), archivo.name, titulo.ifBlank { url }, Formato.HTML.name, false, archivo.length(), url)
		else db.anadirLibro(Uri.fromFile(archivo).toString(), archivo.name, titulo, "", Formato.HTML.name, false, archivo.length(), null)
		if (datos.autor.isNotBlank()) db.renombrar(id, titulo.ifBlank { url }, datos.autor)
		guardarDatos(db, id, datos, pendiente = false)
		return id
	}

	/** Palabras, medio y fecha en los ajustes propios del artículo (para el tiempo de escucha y la Información). */
	private fun guardarDatos(db: BaseDatos, id: Long, datos: Datos, pendiente: Boolean) {
		val o = runCatching { org.json.JSONObject(db.libro(id)?.ajustes.orEmpty()) }.getOrElse { org.json.JSONObject() }
		if (datos.palabras > 0) o.put("palabras", datos.palabras)
		if (datos.medio.isNotBlank()) o.put("medio", datos.medio)
		if (datos.fecha.isNotBlank()) o.put("fecha", datos.fecha)
		if (pendiente) o.put("pendiente", true) else o.remove("pendiente")
		db.guardarAjustesLibro(id, o)
	}

	/** Autor, medio y fecha de publicación de las etiquetas de la página (meta, Open Graph y JSON-LD). */
	private fun datosDe(d: org.jsoup.nodes.Document): Datos {
		fun meta(vararg claves: String): String = claves.firstNotNullOfOrNull { k ->
			d.selectFirst("meta[name=$k], meta[property=$k]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("http") }
		}.orEmpty()
		var autor = meta("author", "article:author", "parsely-author", "dc.creator", "byl").removePrefix("By ").removePrefix("Por ")
		var fecha = meta("article:published_time", "datePublished", "pubdate", "date", "dc.date")
		if (autor.isBlank() || fecha.isBlank()) for (s in d.select("script[type=application/ld+json]")) {
			val t = s.data()
			if (autor.isBlank()) Regex("\"author\"\\s*:\\s*\\[?\\s*\\{[^}]*?\"name\"\\s*:\\s*\"([^\"]+)\"").find(t)?.let { autor = it.groupValues[1] }
			if (fecha.isBlank()) Regex("\"datePublished\"\\s*:\\s*\"([^\"]+)\"").find(t)?.let { fecha = it.groupValues[1] }
		}
		if (fecha.isBlank()) fecha = d.selectFirst("time[datetime]")?.attr("datetime").orEmpty()
		// La fecha, legible: «2026-11-11T08:30:00+01:00» → «11/11/2026»
		val legible = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(fecha)?.let { m -> "${m.groupValues[3]}/${m.groupValues[2]}/${m.groupValues[1]}" } ?: ""
		return Datos(autor.take(120), meta("og:site_name", "application-name").take(80), legible)
	}

	/**
	 * No hay conexión: se guarda el enlace en Compartidos como «pendiente de
	 * descargar» (no se pierde) y se descarga solo cuando vuelva la red.
	 */
	suspend fun guardarPendiente(c: Context, direccion: String): Long = withContext(Dispatchers.IO) {
		val url = limpiarUrl(if (direccion.startsWith("http")) direccion else "https://$direccion")
		val carpeta = File(c.filesDir, "web").apply { mkdirs() }
		val destino = File(carpeta, Fuente.hash(url).take(16) + ".html")
		val sitio = Uri.parse(url).host?.removePrefix("www.").orEmpty()
		if (!destino.exists()) destino.writeText("<html><head><meta charset=\"utf-8\"><title>$sitio</title></head><body><h1>$sitio</h1><p>${c.getString(com.xebolax.lector.R.string.comp_pendiente_texto)}</p><p>$url</p></body></html>")
		val db = BaseDatos.obtener(c)
		val id = db.libroPorUri(Uri.fromFile(destino).toString())?.id
			?: db.anadirCompartido(Uri.fromFile(destino).toString(), destino.name, sitio.ifBlank { url }, Formato.HTML.name, false, destino.length(), url)
		guardarDatos(db, id, Datos(), pendiente = true)
		com.xebolax.lector.compartidos.DescargasPendientes.programar(c)
		id
	}
}
