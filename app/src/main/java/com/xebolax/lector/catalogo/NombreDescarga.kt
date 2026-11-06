package com.xebolax.lector.catalogo

import java.net.URLDecoder

/**
 * El nombre de un archivo descargado de la web de una biblioteca, para que
 * en la biblioteca se sepa qué es sin abrirlo. Por orden:
 * 1. El que manda el servidor (Content-Disposition), entendiendo también
 *    «filename*=UTF-8''…» con tildes y la mezcla de los dos (lo que el
 *    navegador de Android no entiende y por eso daba «downloadfile» o similar).
 * 2. El de la dirección, si acaba en un nombre con extensión.
 * 3. El título del libro de la página y el texto del enlace pulsado (que
 *    distingue las varias clases de descarga: «DAISY», «Texto»...).
 * La extensión, si falta, sale del tipo del archivo o de su contenido.
 */
object NombreDescarga {

	/** El nombre de la cabecera Content-Disposition (null si no trae). */
	fun deCabecera(cabecera: String?): String? {
		if (cabecera.isNullOrBlank()) return null
		// filename*=UTF-8''El%20Quijote.zip (RFC 5987), con o sin comillas: manda sobre filename=
		Regex("""filename\*\s*=\s*"?([^';"]*)'[^']*'([^;"]+)"?""", RegexOption.IGNORE_CASE).find(cabecera)?.let { m ->
			val juego = m.groupValues[1].ifBlank { "UTF-8" }
			runCatching { URLDecoder.decode(m.groupValues[2].replace("+", "%2B"), juego) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
		}
		val m = Regex("""filename\s*=\s*(?:"((?:\\.|[^"\\])*)"|([^;]+))""", RegexOption.IGNORE_CASE).find(cabecera) ?: return null
		val crudo = (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace("\\\"", "\"").trim()
		if (crudo.isEmpty()) return null
		// Algunos servidores mandan %-códigos o UTF-8 leído como Latin-1 («GarcÃ­a»): se arregla
		val decodificado = if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(crudo)) runCatching { URLDecoder.decode(crudo.replace("+", "%2B"), "UTF-8") }.getOrDefault(crudo) else crudo
		return arreglarLatin1(decodificado)
	}

	/** «GarcÃ­a» → «García» (UTF-8 que se leyó como Latin-1). */
	fun arreglarLatin1(t: String): String {
		if (!Regex("[ÃÂ][\\u0080-\\u00BF]").containsMatchIn(t)) return t
		return runCatching { String(t.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) }.getOrDefault(t).takeIf { !it.contains('�') } ?: t
	}

	/** El nombre que trae la dirección, solo si parece un nombre de archivo (con extensión). */
	fun deUrl(url: String): String? {
		val ultimo = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
		val nombre = runCatching { URLDecoder.decode(ultimo.replace("+", "%2B"), "UTF-8") }.getOrDefault(ultimo)
		return nombre.takeIf { Regex("""\.[A-Za-z0-9]{2,5}$""").containsMatchIn(it) && !generico(it) && !it.endsWith(".aspx") && !it.endsWith(".php") }
	}

	/** Nombres que no dicen nada: «download0», «downloadfile.bin», «file», «12345», «Descargar»... */
	fun generico(nombre: String): Boolean {
		val base = nombre.substringBeforeLast('.').trim().lowercase()
		return base.isEmpty() || base.all { it.isDigit() || it == '-' || it == '_' } ||
			Regex("""^(download|downloadfile|descarga|descargar|file|archivo|fichero|getfile|get|index|default|attachment|obra|libro|book)[\s_\-]?\d*$""").matches(base)
	}

	/** La extensión que corresponde a un tipo MIME (vacía si no se sabe). */
	fun extensionDeTipo(tipo: String?): String {
		val t = tipo.orEmpty().lowercase().substringBefore(';').trim()
		return when {
			t.contains("epub") -> "epub"
			t.contains("zip") -> "zip"
			t.contains("pdf") -> "pdf"
			t.contains("wordprocessingml") -> "docx"
			t.contains("msword") -> "doc"
			t.contains("rtf") -> "rtf"
			t.contains("mpeg") || t.contains("mp3") -> "mp3"
			t.contains("mp4") || t.contains("m4a") || t.contains("m4b") -> "m4b"
			t.contains("rar") -> "rar"
			t == "text/plain" -> "txt"
			t.contains("html") -> "html"
			else -> ""
		}
	}

	/** La extensión por el contenido del archivo (sus primeros bytes); vacía si no se sabe. */
	fun extensionDeContenido(inicio: ByteArray): String {
		fun empieza(vararg b: Int) = inicio.size >= b.size && b.indices.all { inicio[it].toInt() and 0xFF == b[it] }
		return when {
			empieza(0x50, 0x4B, 0x03, 0x04) -> "zip"
			empieza(0x25, 0x50, 0x44, 0x46) -> "pdf"
			empieza(0x49, 0x44, 0x33) || empieza(0xFF, 0xFB) || empieza(0xFF, 0xF3) -> "mp3"
			empieza(0x52, 0x61, 0x72, 0x21) -> "rar"
			empieza(0x7B, 0x5C, 0x72, 0x74, 0x66) -> "rtf"
			empieza(0xD0, 0xCF, 0x11, 0xE0) -> "doc"
			else -> ""
		}
	}

	/** Quita lo que no puede ir en un nombre de archivo y lo acorta. */
	fun limpiar(nombre: String): String =
		nombre.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ").replace(Regex("\\s+"), " ").trim().trim('.').take(120)

	/** Un título de página sin el nombre del sitio («Ficha - Tiflolibros» → «Ficha»). */
	fun sinSitio(titulo: String, sitio: String): String {
		val partes = titulo.split(" - ", " | ", " – ", " — ").map { it.trim() }.filter { it.isNotEmpty() }
		val utiles = partes.filterNot { p -> sitio.isNotBlank() && (p.contains(sitio, true) || sitio.contains(p, true)) }
		return (utiles.ifEmpty { partes }).joinToString(" - ")
	}

	/**
	 * El nombre final. tituloLibro: el título del libro en la página desde la que se descargó;
	 * enlace: el texto del botón o enlace pulsado; sitio: el nombre de la biblioteca.
	 */
	fun elegir(cabecera: String?, url: String, tipo: String?, tituloLibro: String?, enlace: String?, sitio: String): String {
		val delServidor = deCabecera(cabecera)?.let { limpiar(it) }?.takeIf { it.isNotEmpty() }
		val candidato = delServidor?.takeIf { !generico(it) } ?: deUrl(url)?.let { limpiar(it) }
		val extension = (delServidor ?: candidato)?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
			?: extensionDeTipo(tipo)
		if (candidato != null) return if (candidato.contains('.') || extension.isEmpty()) candidato else "$candidato.$extension"
		// Sin nombre útil: el título del libro y la clase de descarga («El Quijote - DAISY»)
		val titulo = limpiar(sinSitio(tituloLibro.orEmpty(), sitio))
		val clase = limpiar(enlace.orEmpty()).takeIf { it.isNotEmpty() && it.length <= 60 && !it.equals(titulo, true) && !generico(it) }
		val base = listOf(titulo, clase.orEmpty()).filter { it.isNotEmpty() }.joinToString(" - ").ifEmpty { limpiar(sitio).ifEmpty { "libro" } }
		return if (extension.isEmpty()) base else "$base.$extension"
	}
}
