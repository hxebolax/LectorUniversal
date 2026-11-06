package com.xebolax.lector.formatos

import com.xebolax.lector.modelo.Contenido
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Cómic (.cbz, o un ZIP que solo trae imágenes): una página por imagen, en
 * orden natural. Cada página es un encabezado con su imagen, para describirla
 * con la IA o leer sus bocadillos con el reconocimiento de texto (en las
 * acciones del párrafo); con «Reconocer el texto de todas las páginas» se
 * saca un documento con el texto. Si trae ComicInfo.xml, se usan su título,
 * serie, número, guionista y resumen.
 */
object LectorComic : LectorFormato {

	val IMAGENES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

	fun esImagen(ruta: String) = Fuente.extensionDe(ruta).lowercase() in IMAGENES

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val zip = fuente.contenedor() as? ContenedorZip ?: throw ExcepcionFormato("No es un cómic válido.")
		zip.use { z ->
			val entradas = z.entradas().filterNot { it.startsWith("__MACOSX") || it.substringAfterLast('/').startsWith(".") }
			val paginas = entradas.filter { esImagen(it) }.sortedWith(OrdenNatural)
			if (paginas.isEmpty()) throw ExcepcionFormato("El cómic no tiene páginas.")
			val c = ctx.nuevoConstructor()
			c.titulo = fuente.nombre.substringBeforeLast('.')
			// ComicInfo.xml (el estándar de ComicRack): título, serie, número, autores y resumen
			entradas.firstOrNull { it.substringAfterLast('/').equals("ComicInfo.xml", ignoreCase = true) }?.let { ruta ->
				runCatching {
					val info = Jsoup.parse(z.texto(ruta).orEmpty(), "", Parser.xmlParser())
					fun campo(n: String) = info.selectFirst(n)?.text()?.trim().orEmpty()
					val serie = listOf(campo("Series"), campo("Number").takeIf { it.isNotEmpty() }?.let { "nº $it" }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" ")
					c.titulo = campo("Title").ifEmpty { serie }.ifEmpty { c.titulo }
					val autor = listOf(campo("Writer"), campo("Penciller")).filter { it.isNotEmpty() }.distinct().joinToString(", ")
					if (autor.isNotEmpty()) { c.autor = autor; c.metadatos["Autor"] = autor }
					if (serie.isNotEmpty()) c.metadatos["Serie"] = serie
					campo("LanguageISO").takeIf { it.isNotEmpty() }?.let { c.idioma = it }
					campo("Summary").takeIf { it.isNotEmpty() }?.let { c.metadatos["Descripción"] = it }
				}
			}
			c.encabezado(c.titulo, 1)
			c.metadatos["Descripción"]?.let { c.parrafo(it) }
			c.parrafo("Cómic de ${paginas.size} páginas. Cada página es una imagen: en sus acciones, «Describir imagen» la describe con la IA o lee el texto de sus bocadillos.")
			for ((i, ruta) in paginas.withIndex()) {
				ctx.comprobar()
				val n = (i + 1).toString()
				c.pagina(n)
				val bloque = c.encabezado("Página $n", 2)
				c.imagen(bloque, ruta)
			}
			return Contenido.Texto(c.construir())
		}
	}
}
