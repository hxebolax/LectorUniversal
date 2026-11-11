package com.xebolax.lector.formatos

import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.Textos
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import android.util.Base64

/**
 * Páginas HTML/XHTML guardadas y archivos MHT. En páginas web con
 * <article> o <main> se lee solo el contenido principal (sin menús).
 */
object LectorHtml : LectorFormato {

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val bytes = fuente.bytes(128L * 1024 * 1024)
		val html = if (fuente.extension == "mht" || fuente.extension == "mhtml") desdeMht(bytes) else Textos.decodificar(bytes, "utf-8")
		return Contenido.Texto(convertir(html, ctx, fuente.nombre.substringBeforeLast('.')))
	}

	/** Convierte un HTML completo en documento (también lo usan "Abrir página web" y el portapapeles). */
	fun convertir(html: String, ctx: ContextoLectura, tituloAlternativo: String, urlBase: String = "") =
		ctx.nuevoConstructor().let { c ->
			val doc = Jsoup.parse(html, urlBase)
			c.titulo = doc.title().ifBlank { doc.selectFirst("h1")?.text().orEmpty() }.ifBlank { tituloAlternativo }
			c.idioma = doc.selectFirst("html")?.attr("lang").orEmpty()
			doc.selectFirst("meta[name=author]")?.attr("content")?.let { c.autor = it; c.metadatos["Autor"] = it }
			doc.selectFirst("meta[name=description]")?.attr("content")?.let { c.metadatos["Descripción"] = it }
			if (urlBase.isNotEmpty()) c.metadatos["Dirección"] = urlBase
			// Enlaces de la web: absolutos para poder abrirlos
			if (urlBase.isNotEmpty()) doc.select("a[href]").forEach { a ->
				val href = a.attr("href")
				if (!href.startsWith("#")) a.attr("href", a.absUrl("href").ifBlank { href })
			}
			ConversorHtml(c, "", ctx.opciones).convertir(principal(doc, web = urlBase.isNotEmpty()))
			c.construir()
		}

	/**
	 * Contenido principal: el <article> o <main> con más texto si tiene una
	 * parte importante de la página. Sin ellos, el bloque del título solo en
	 * páginas web: un HTML local (un libro con varios h1) se lee entero.
	 */
	private fun principal(doc: Document, web: Boolean): Element {
		val cuerpo = doc.body() ?: return doc
		val total = cuerpo.text().length
		val candidato = (doc.select("article") + doc.select("main") + doc.select("[role=main]"))
			.maxByOrNull { it.text().length }
		if (candidato != null && total > 0 && candidato.text().length > total * 0.4) return candidato
		// Sin <article> ni <main>: el bloque que rodea al título principal, si tiene párrafos de verdad
		if (web) bloqueDelTitulo(doc, doc.select("h1"))?.let { return it }
		return cuerpo
	}

	/**
	 * El elemento que contiene a uno de estos títulos y al texto que le sigue
	 * (sin el menú ni el pie de la web). null si no parece un artículo.
	 */
	fun bloqueDelTitulo(doc: Document, titulos: List<Element>): Element? {
		val cuerpo = doc.body() ?: return null
		for (t in titulos) {
			var e: Element = t
			while (e.parent() != null && e.parent() !== cuerpo && (e.text().length < 1200 || e.select("p").size < 3)) e = e.parent()!!
			if (e !== cuerpo && e.select("p").size >= 3 && e.text().length >= 600) return e
		}
		return null
	}

	/** Primera parte text/html de un archivo MHT (MIME), decodificada. */
	private fun desdeMht(bytes: ByteArray): String {
		val texto = String(bytes, Charsets.ISO_8859_1)
		val limite = Regex("""boundary="?([^";\r\n]+)"?""", RegexOption.IGNORE_CASE).find(texto)?.groupValues?.get(1)
			?: return Textos.decodificar(bytes)
		for (parte in texto.split("--$limite")) {
			val corte = parte.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: parte.indexOf("\n\n").takeIf { it >= 0 } ?: continue
			val cabeceras = parte.substring(0, corte).lowercase()
			if (!cabeceras.contains("text/html")) continue
			val cuerpo = parte.substring(corte).trim()
			val charset = Regex("""charset="?([\w-]+)""").find(cabeceras)?.groupValues?.get(1) ?: "utf-8"
			val crudo = when {
				cabeceras.contains("quoted-printable") -> quotedPrintable(cuerpo)
				cabeceras.contains("base64") -> runCatching { Base64.decode(cuerpo, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
				else -> cuerpo.toByteArray(Charsets.ISO_8859_1)
			}
			return runCatching { String(crudo, charset(charset)) }.getOrElse { Textos.decodificar(crudo) }
		}
		return Textos.decodificar(bytes)
	}

	private fun quotedPrintable(texto: String): ByteArray {
		val salida = ByteArrayOutputStream(texto.length)
		var i = 0
		while (i < texto.length) {
			val c = texto[i]
			if (c == '=' && i + 1 < texto.length) {
				if (texto[i + 1] == '\r' || texto[i + 1] == '\n') {
					i += if (texto.startsWith("=\r\n", i)) 3 else 2
					continue
				}
				val hex = texto.substring(i + 1, minOf(i + 3, texto.length)).toIntOrNull(16)
				if (hex != null) {
					salida.write(hex)
					i += 3
					continue
				}
			}
			salida.write(c.code and 0xFF)
			i++
		}
		return salida.toByteArray()
	}
}
