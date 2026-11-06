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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Descarga una página web, guarda su contenido principal y la añade a la biblioteca (devuelve el id del libro). */
object PaginaWeb {

	private fun normal(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
		.replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()

	/** titulo: el titular esperado (noticias), para guardar solo el artículo. */
	suspend fun guardar(c: Context, direccion: String, titulo: String? = null): Long {
		val url = if (direccion.startsWith("http")) direccion else "https://$direccion"
		val (titulo, archivo) = withContext(Dispatchers.IO) {
			val conexion = URL(url).openConnection() as HttpURLConnection
			conexion.connectTimeout = 20_000
			conexion.readTimeout = 30_000
			conexion.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LectorUniversal/${BuildConfig.VERSION_NAME}")
			var tipo = ""
			var disposicion: String? = null
			var final = url
			val bytes = try {
				conexion.inputStream.use { it.readBytes() }.also {
					tipo = conexion.contentType.orEmpty()
					disposicion = conexion.getHeaderField("Content-Disposition")
					final = conexion.url.toString()
				}
			} finally {
				conexion.disconnect()
			}
			// El enlace es un libro o un documento (PDF, EPUB, DOCX, ZIP...), no una página: se guarda tal cual con su nombre
			val porContenido = com.xebolax.lector.catalogo.NombreDescarga.extensionDeContenido(bytes.copyOf(minOf(bytes.size, 8)))
			val noEsPagina = !tipo.contains("html") && !tipo.contains("xml") && (porContenido.isNotEmpty() ||
				com.xebolax.lector.catalogo.NombreDescarga.extensionDeTipo(tipo).let { it.isNotEmpty() && it != "html" && it != "txt" })
			if (noEsPagina) {
				var nombre = com.xebolax.lector.catalogo.NombreDescarga.elegir(disposicion, final, tipo, null, null, "documento")
				if (!nombre.contains('.') && porContenido.isNotEmpty()) nombre += ".$porContenido"
				val carpeta = File(c.filesDir, "biblioteca/descargas").apply { mkdirs() }
				var destino = File(carpeta, nombre)
				var n = 2
				while (destino.exists()) destino = File(carpeta, nombre.substringBeforeLast('.') + " ($n)." + nombre.substringAfterLast('.', "")).also { n++ }
				destino.writeBytes(bytes)
				return@withContext null to destino
			}
			val html = com.xebolax.lector.modelo.Textos.decodificar(bytes, "utf-8")
			val doc = LectorHtml.convertir(html, ContextoLectura(c), url, url)
			val carpeta = File(c.filesDir, "web").apply { mkdirs() }
			val destino = File(carpeta, Fuente.hash(url).take(16) + ".html")
			val pagina = org.jsoup.Jsoup.parse(html, url).apply { select("script,style,noscript").remove() }
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
		db.libroPorUri(Uri.fromFile(archivo).toString())?.let { return it.id }
		// Un documento descargado: se añade con su formato real y se leen su título y autor por detrás
		if (titulo == null) {
			val id = com.xebolax.lector.biblioteca.Importador.anadirArchivos(c, listOf(Uri.fromFile(archivo)), null).anadidos.firstOrNull()
				?: db.libroPorUri(Uri.fromFile(archivo).toString())?.id ?: error("No se pudo añadir")
			com.xebolax.lector.LectorApp.ambito.launch { com.xebolax.lector.biblioteca.Importador.completarDatos(c.applicationContext, listOf(id)) }
			return id
		}
		return db.anadirLibro(Uri.fromFile(archivo).toString(), archivo.name, titulo, "", Formato.HTML.name, false, archivo.length(), null)
	}
}
