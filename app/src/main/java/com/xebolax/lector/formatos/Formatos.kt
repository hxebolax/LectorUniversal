package com.xebolax.lector.formatos

import com.xebolax.lector.modelo.Contenido

/** Un lector de formato: convierte una fuente en un documento de texto o un libro de audio. */
interface LectorFormato {
	fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido
}

/** Formatos que la app sabe abrir. nombre: el que se muestra en la biblioteca. */
enum class Formato(val nombre: String, val extensiones: List<String>, val audio: Boolean = false) {
	EPUB("EPUB", listOf("epub", "epub3", "kepub")),
	PDF("PDF", listOf("pdf")),
	DOCX("Word", listOf("docx", "docm", "dotx")),
	/** Word 97-2003 (contenedor OLE). */
	DOC("Word 97-2003", listOf("doc", "dot")),
	ODT("OpenDocument", listOf("odt", "fodt", "ott")),
	RTF("RTF", listOf("rtf", "wri")),
	TXT("Texto", listOf("txt", "text", "log", "nfo", "csv", "tsv", "ini", "srt", "vtt", "json", "xml")),
	HTML("HTML", listOf("html", "htm", "xhtml", "xht", "mht", "mhtml", "shtml")),
	MARKDOWN("Markdown", listOf("md", "markdown", "mdown", "mkd", "rst")),
	FB2("FictionBook", listOf("fb2", "fb2.zip", "fbz")),
	MOBI("Kindle", listOf("mobi", "azw", "azw3", "prc", "pdb")),
	PPTX("PowerPoint", listOf("pptx", "pptm", "odp", "fodp")),
	XLSX("Hoja de cálculo", listOf("xlsx", "xlsm", "ods", "fods")),
	CHM("Ayuda CHM", listOf("chm")),
	/** Libros en braille: BRA y BAN de la ONCE, BRF y PEF. */
	BRAILLE("Braille", listOf("bra", "ban", "brf", "pef")),
	DAISY("DAISY", listOf("opf", "ncc", "daisy")),
	AUDIO("Audio", listOf("m4b", "m4a", "mp3", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "mka", "mp4", "3gp", "amr", "awb"), audio = true),
	LISTA("Lista de reproducción", listOf("m3u", "m3u8", "pls", "cue"), audio = true),
	/** Carpeta: DAISY o audiolibro en varios archivos. */
	CARPETA("Carpeta", emptyList()),
	/** Cómic: ZIP de imágenes (una por página). */
	COMIC("Cómic", listOf("cbz")),
	ZIP("ZIP", listOf("zip")),
	DESCONOCIDO("Desconocido", emptyList());

	companion object {
		private val porExtension: Map<String, Formato> by lazy {
			entries.flatMap { f -> f.extensiones.map { it to f } }.toMap()
		}

		fun deExtension(extension: String): Formato = porExtension[extension.lowercase()] ?: DESCONOCIDO

		/** Todas las extensiones (para filtrar al buscar en carpetas). */
		val extensionesConocidas: Set<String> by lazy { porExtension.keys + "html" }

		/** Tipos MIME para el selector de archivos del sistema. */
		val TIPOS_MIME = arrayOf("*/*")

		/**
		 * Reconoce el formato por la extensión y, si no basta, por el
		 * contenido (los ZIP se miran por dentro: EPUB, DOCX, DAISY, FB2...).
		 */
		fun reconocer(fuente: Fuente): Formato {
			if (fuente.esCarpeta) return CARPETA
			val porNombre = deExtension(fuente.extension)
			if (porNombre != DESCONOCIDO && porNombre != ZIP && porNombre != TXT && porNombre != HTML) return porNombre
			if (fuente.nombre.equals("ncc.html", ignoreCase = true)) return DAISY
			val cabecera = runCatching { fuente.cabecera(4096) }.getOrDefault(ByteArray(0))
			return porContenido(cabecera, fuente) ?: porNombre.takeIf { it != ZIP } ?: TXT
		}

		private fun porContenido(c: ByteArray, fuente: Fuente): Formato? {
			fun empieza(texto: String, desde: Int = 0) =
				c.size >= desde + texto.length && String(c, desde, texto.length, Charsets.ISO_8859_1) == texto
			val inicio = String(c, 0, minOf(c.size, 1024), Charsets.ISO_8859_1).trimStart('﻿', ' ', '\r', '\n', '\t', 'ï', '»', '¿')
			return when {
				empieza("%PDF") -> PDF
				// Office antiguo (OLE): Word .doc (el lector dice si es otra cosa, como .ppt o .xls)
				c.size >= 4 && (c[0].toInt() and 0xFF) == 0xD0 && (c[1].toInt() and 0xFF) == 0xCF && (c[2].toInt() and 0xFF) == 0x11 && (c[3].toInt() and 0xFF) == 0xE0 -> DOC
				empieza("{\\rtf") -> RTF
				empieza("BOOKMOBI", 60) || empieza("TEXtREAd", 60) -> MOBI
				empieza("ITSF") -> CHM
				empieza("ID3") || empieza("ftyp", 4) || empieza("OggS") || empieza("fLaC") || empieza("RIFF") -> AUDIO
				c.size > 2 && (c[0].toInt() and 0xFF) == 0xFF && (c[1].toInt() and 0xE0) == 0xE0 -> AUDIO
				empieza("PK") -> mirarZip(fuente)
				inicio.startsWith("#EXTM3U") -> LISTA
				inicio.contains("<FictionBook", ignoreCase = true) -> FB2
				inicio.contains("<pef", ignoreCase = true) && inicio.contains("daisy.org/ns/2008/pef") -> BRAILLE
				inicio.contains("<html", ignoreCase = true) || inicio.contains("<!DOCTYPE html", ignoreCase = true) -> HTML
				inicio.contains("<dtbook", ignoreCase = true) -> DAISY
				else -> null
			}
		}

		/** Qué hay dentro de un ZIP. */
		fun mirarZip(fuente: Fuente): Formato = runCatching {
			fuente.contenedor().use { z ->
				val entradas = z.entradas().map { it.lowercase() }
				val mimetype = z.buscar("mimetype")?.let { z.texto(it)?.trim() }.orEmpty()
				when {
					mimetype == "application/epub+zip" -> EPUB
					entradas.any { it.endsWith("ncc.html") } -> DAISY
					// DAISY 3: OPF con SMIL o NCX y sin el container.xml de los EPUB
					entradas.any { it.endsWith(".opf") } && entradas.any { it.endsWith(".smil") } &&
						entradas.none { it.endsWith("meta-inf/container.xml") } -> DAISY
					entradas.any { it.endsWith("meta-inf/container.xml") } -> EPUB
					mimetype.contains("opendocument.text") -> ODT
					mimetype.contains("opendocument.presentation") -> PPTX
					mimetype.contains("opendocument.spreadsheet") -> XLSX
					entradas.contains("word/document.xml") -> DOCX
					entradas.any { it.startsWith("ppt/slides/") } -> PPTX
					entradas.contains("xl/workbook.xml") -> XLSX
					entradas.any { it.endsWith("ncc.html") } -> DAISY
					entradas.any { it.endsWith(".opf") } && entradas.any { it.endsWith(".smil") || it.endsWith(".ncx") } -> DAISY
					entradas.any { it.endsWith(".fb2") } -> FB2
					entradas.any { it.endsWith(".epub") } -> EPUB
					entradas.any { e -> AUDIO.extensiones.any { e.endsWith(".$it") } } -> AUDIO
					entradas.any { it.endsWith(".html") || it.endsWith(".htm") || it.endsWith(".xhtml") } -> HTML
					entradas.any { it.endsWith(".txt") } -> TXT
					// Solo imágenes (y quizá su ComicInfo.xml): un cómic
					entradas.count { LectorComic.esImagen(it) } >= 2 && entradas.all { LectorComic.esImagen(it) || it.endsWith("/") || it.endsWith(".xml") || it.substringAfterLast('/').startsWith(".") || it.startsWith("__macosx") } -> COMIC
					else -> ZIP
				}
			}
		}.getOrDefault(ZIP)
	}
}
