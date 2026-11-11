package com.xebolax.lector.formatos

import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.Textos
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Libros en braille: BRA y BAN de la ONCE (Biblioteca Digital, EBrai,
 * QuickBraille), BRF (Braille Ready Format: Bookshare, NLS) y PEF (DAISY).
 * Se quitan los números de página de cada página (quedan como páginas para
 * navegar), se unen las líneas en párrafos y se pasa a texto el braille
 * español de grado 1. Si se elige en Ajustes, se muestra el braille tal
 * cual (puntos Unicode). El braille inglés contraído (grado 2) no se pasa a
 * texto: se muestra con sus puntos.
 */
object LectorBraille : LectorFormato {

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val bytes = fuente.bytes(64L * 1024 * 1024)
		val c = ctx.nuevoConstructor()
		c.titulo = fuente.nombre.substringBeforeLast('.')
		val inicio = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
		val paginas: List<List<IntArray>> = if (fuente.extension == "pef" || inicio.contains("<pef", ignoreCase = true)) {
			pef(Textos.decodificar(bytes, "utf-8"), c)
		} else {
			// BRF en braille ASCII norteamericano; BRA y BAN, en el de la ONCE. Windows-1252 si no es UTF-8.
			val texto = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrElse { String(bytes, charset("windows-1252")) }
			val tabla = if (fuente.extension == "brf") Braille.Tabla.BRF else Braille.Tabla.ONCE
			val lineas = texto.replace("\r\n", "\n").replace('\r', '\n').split('\n')
			if (texto.contains('\u000C')) {
				// Con saltos de página
				texto.replace("\r\n", "\n").replace('\r', '\n').split('\u000C').map { p -> p.split('\n').map { Braille.celdas(it, tabla) } }
			} else {
				val todas = lineas.map { Braille.celdas(it, tabla) }
				val porPagina = Braille.lineasPorPagina(todas, if (tabla == Braille.Tabla.BRF) 25 else 29)
				if (porPagina > 0) todas.chunked(porPagina) else listOf(todas)
			}
		}
		ctx.comprobar()
		// El BRF suele ser braille inglés contraído (grado 2), que no se pasa a texto con las reglas del español
		val comoTexto = ctx.opciones.brailleComoTexto && !(fuente.extension == "brf" && !pareceEspanol(paginas))
		if (comoTexto) c.idioma = c.idioma.ifBlank { "es" }
		var primero = true
		for (t in Braille.maquetar(paginas)) {
			t.pagina?.let { c.pagina(it) }
			val texto = if (comoTexto) Braille.aTexto(t.celdas) else Braille.unicode(t.celdas)
			if (texto.isBlank()) continue
			when (t.tipo) {
				Braille.Trozo.Tipo.SEPARADOR -> c.separador()
				Braille.Trozo.Tipo.TITULO -> {
					// El primer título (la portada) es de nivel 1; los demás, de nivel 2
					c.marcarSiguiente(Marca.INICIO_SECCION)
					c.encabezado(texto, if (primero) 1 else 2)
					c.indice(texto, if (primero) 1 else 2, c.tamano - 1)
					primero = false
				}
				Braille.Trozo.Tipo.PARRAFO -> c.parrafo(texto)
			}
		}
		if (c.estaVacio()) throw ExcepcionFormato("El archivo braille está vacío.")
		return Contenido.Texto(c.construir())
	}

	/** ¿Parece braille español? (tiene letras con acento o ñ, que el inglés no usa: á é í ó ú ñ) */
	private fun pareceEspanol(paginas: List<List<IntArray>>): Boolean {
		val propias = setOf(0x37, 0x2E, 0x0C, 0x2C, 0x3E, 0x3B)
		var n = 0
		for (p in paginas) for (l in p) for (x in l) if (x in propias) n++
		return n >= 5
	}

	/** PEF: volúmenes, secciones, páginas y filas de celdas Unicode. */
	private fun pef(xml: String, c: com.xebolax.lector.modelo.ConstructorDocumento): List<List<IntArray>> {
		val doc = Jsoup.parse(xml, "", Parser.xmlParser())
		doc.getElementsByTag("dc:title").firstOrNull()?.text()?.takeIf { it.isNotBlank() }?.let { c.titulo = it }
		doc.getElementsByTag("dc:creator").firstOrNull()?.text()?.takeIf { it.isNotBlank() }?.let { c.autor = it }
		doc.getElementsByTag("dc:language").firstOrNull()?.text()?.takeIf { it.isNotBlank() }?.let { c.idioma = it }
		return doc.getElementsByTag("page").map { pagina ->
			pagina.getElementsByTag("row").map { fila ->
				val t = fila.text().replace(Regex("[\\n\\r\\t]"), "")
				IntArray(t.length) { i -> t[i].let { ch -> if (ch in '⠀'..'⣿') (ch.code - 0x2800) and 0x3F else 0 } }
			}
		}
	}
}
