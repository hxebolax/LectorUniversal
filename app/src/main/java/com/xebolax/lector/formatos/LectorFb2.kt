package com.xebolax.lector.formatos

import com.xebolax.lector.modelo.ConstructorDocumento
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.Textos
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * FictionBook 2 (.fb2, y .fb2.zip/.fbz o ZIP con un .fb2 dentro).
 *
 * El FB2 se transforma en HTML sobre el mismo árbol (section/title → hN
 * según la profundidad, poem → blockquote con una estrofa por bloque,
 * emphasis → em, a l:href → a href...) y lo convierte ConversorHtml. El
 * cuerpo de notas (body name="notes") va al final bajo "Notas", con cada
 * nota como bloque NOTA ("1. texto") y su ancla "#id".
 */
object LectorFb2 : LectorFormato {

	private val NOTAS = setOf("notes", "comments", "footnotes", "endnotes")
	private val TERMINA_EN_PUNTUACION = Regex("""[.!?:;…]["'»”)]*$""")

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val bytes = cargar(fuente)
		ctx.comprobar()
		val doc = Jsoup.parse(Textos.decodificar(bytes, "windows-1251"), "", Parser.xmlParser())
		// Imágenes (en base64 dentro del libro): se guardan las de tamaño razonable para poder describirlas
		val binarios = HashMap<String, String>()
		for (b in doc.getAllElements().filter { local(it) == "binary" }) {
			val datos = b.text().filterNot { it.isWhitespace() }
			val id = b.attr("id")
			if (id.isNotEmpty() && datos.length < 700_000) binarios[id] = "data:${b.attr("content-type").ifBlank { "image/jpeg" }};base64,$datos"
			b.remove()
		}
		ctx.progreso(30)
		val raiz = doc.getAllElements().firstOrNull { local(it) == "fictionbook" }
			?: throw ExcepcionFormato("El archivo no es un libro FictionBook válido.")
		val c = ctx.nuevoConstructor()
		hijo(raiz, "description")?.let { metadatos(it, c) }

		val cuerpos = raiz.children().filter { local(it) == "body" }
		if (cuerpos.isEmpty()) throw ExcepcionFormato("El libro FictionBook no tiene texto (falta <body>).")
		val principales = cuerpos.filter { it.attr("name").lowercase() !in NOTAS }.ifEmpty { listOf(cuerpos.first()) }
		val notas = cuerpos.filter { it !in principales }

		for ((i, cuerpo) in principales.withIndex()) {
			ctx.comprobar()
			preparar(cuerpo, ctx.opciones, binarios)
			// Cada sección de primer nivel empieza una sección del libro
			for (hijo in cuerpo.children()) {
				if (local(hijo) == "section") c.marcarSiguiente(Marca.INICIO_SECCION)
				ConversorHtml(c, "", ctx.opciones).convertir(hijo)
			}
			ctx.progreso(30 + (i + 1) * 50 / principales.size)
		}
		for (cuerpo in notas) {
			ctx.comprobar()
			val tituloElemento = hijo(cuerpo, "title")
			val titulo = tituloElemento?.let { textoTitulo(it) }.orEmpty().ifBlank { "Notas" }
			tituloElemento?.remove()
			prepararNotas(cuerpo)
			preparar(cuerpo, ctx.opciones, binarios)
			cuerpo.tagName("section")
			cuerpo.attr("class", "notas")
			c.marcarSiguiente(Marca.INICIO_SECCION)
			c.encabezado(titulo, 1)
			ConversorHtml(c, "", ctx.opciones).convertir(cuerpo)
		}
		if (c.titulo.isBlank()) c.titulo = fuente.nombre.substringBeforeLast('.').removeSuffix(".fb2")
		return Contenido.Texto(c.construir())
	}

	private fun cargar(fuente: Fuente): ByteArray {
		val cabecera = runCatching { fuente.cabecera(4) }.getOrDefault(ByteArray(0))
		val esZip = cabecera.size >= 2 && cabecera[0] == 'P'.code.toByte() && cabecera[1] == 'K'.code.toByte()
		if (!esZip) return fuente.bytes(128L * 1024 * 1024)
		return fuente.contenedor().use { z ->
			val entrada = z.entradas().filter { it.lowercase().endsWith(".fb2") }.minByOrNull { it.count { ch -> ch == '/' } }
				?: throw ExcepcionFormato("El ZIP no contiene ningún libro FB2.")
			z.bytes(entrada) ?: throw ExcepcionFormato("No se puede leer el libro FB2 del ZIP.")
		}
	}

	private fun local(e: Element): String = e.normalName().substringAfter(':')

	private fun hijo(e: Element, nombre: String): Element? = e.children().firstOrNull { local(it) == nombre }

	private fun hijos(e: Element, nombre: String): List<Element> = e.children().filter { local(it) == nombre }

	private fun texto(e: Element?): String = e?.text()?.let { Textos.unirEspacios(it).trim() }.orEmpty()

	private fun metadatos(descripcion: Element, c: ConstructorDocumento) {
		val info = hijo(descripcion, "title-info")
		if (info != null) {
			val titulo = texto(hijo(info, "book-title"))
			if (titulo.isNotEmpty()) c.titulo = titulo
			val autores = hijos(info, "author").map { a ->
				listOf("first-name", "middle-name", "last-name").map { texto(hijo(a, it)) }.filter { it.isNotEmpty() }
					.joinToString(" ").ifEmpty { texto(hijo(a, "nickname")) }
			}.filter { it.isNotEmpty() }
			if (autores.isNotEmpty()) {
				c.autor = autores.joinToString(", ")
				c.metadatos["Autor"] = c.autor
			}
			val idioma = texto(hijo(info, "lang"))
			if (idioma.isNotEmpty()) {
				c.idioma = idioma
				c.metadatos["Idioma"] = idioma
			}
			hijo(info, "annotation")?.let { anotacion ->
				val partes = anotacion.children().map { texto(it) }.filter { it.isNotEmpty() }
				c.metadatos["Descripción"] = if (partes.isEmpty()) texto(anotacion) else partes.joinToString("\n")
			}
			c.metadatos["Género"] = hijos(info, "genre").map { texto(it) }.filter { it.isNotEmpty() }.joinToString(", ")
			hijo(info, "date")?.let { c.metadatos["Fecha"] = texto(it).ifEmpty { it.attr("value") } }
			c.metadatos["Palabras clave"] = texto(hijo(info, "keywords"))
			val serie = hijo(info, "sequence")
			val nombreSerie = serie?.attr("name")?.trim().orEmpty()
			if (serie != null && nombreSerie.isNotEmpty()) {
				val numero = serie.attr("number").trim()
				c.metadatos["Serie"] = if (numero.isEmpty()) nombreSerie else "$nombreSerie, $numero"
			}
			val traductores = hijos(info, "translator").map { a ->
				listOf("first-name", "middle-name", "last-name").map { texto(hijo(a, it)) }.filter { it.isNotEmpty() }.joinToString(" ")
			}.filter { it.isNotEmpty() }
			c.metadatos["Traductor"] = traductores.joinToString(", ")
		}
		hijo(descripcion, "publish-info")?.let { p ->
			c.metadatos["Editorial"] = texto(hijo(p, "publisher"))
			c.metadatos["Ciudad"] = texto(hijo(p, "city"))
			c.metadatos["Año"] = texto(hijo(p, "year"))
			c.metadatos["ISBN"] = texto(hijo(p, "isbn"))
		}
	}

	/** Texto de un title (varios p unidos con ". "). */
	private fun textoTitulo(titulo: Element): String {
		val partes = hijos(titulo, "p").map { texto(it) }.filter { it.isNotEmpty() }
		if (partes.isEmpty()) return texto(titulo)
		val sb = StringBuilder()
		for (parte in partes) {
			if (sb.isNotEmpty()) sb.append(if (TERMINA_EN_PUNTUACION.containsMatchIn(sb)) " " else ". ")
			sb.append(parte)
		}
		return sb.toString()
	}

	/** Cada sección de notas: su título ("1") va delante del primer párrafo ("1. texto"). */
	private fun prepararNotas(cuerpo: Element) {
		for (seccion in cuerpo.getAllElements().filter { local(it) == "section" }) {
			val titulo = hijo(seccion, "title") ?: continue
			val etiqueta = textoTitulo(titulo).trim()
			titulo.remove()
			if (etiqueta.isEmpty()) continue
			val primero = hijo(seccion, "p")
			val prefijo = if (TERMINA_EN_PUNTUACION.containsMatchIn(etiqueta)) "$etiqueta " else "$etiqueta. "
			if (primero != null) primero.prependText(prefijo) else seccion.prependElement("p").text(etiqueta)
		}
	}

	/** Cambia las etiquetas FB2 por las HTML equivalentes que entiende ConversorHtml. */
	/** binarios: id de imagen → "data:" con su contenido. */
	private fun preparar(cuerpo: Element, opciones: OpcionesLectura, binarios: Map<String, String> = emptyMap()) {
		for (e in cuerpo.getAllElements().toList()) {
			if (e === cuerpo) continue
			when (local(e)) {
				"title" -> {
					val padre = e.parent()?.let { local(it) }
					if (padre == "section" || padre == "body") {
						val nivel = e.parents().count { local(it) == "section" }.coerceIn(1, 6)
						val contenido = textoTitulo(e)
						e.empty()
						e.appendText(contenido)
						e.tagName("h$nivel")
					} else {
						e.tagName("div")
					}
				}
				"subtitle" -> {
					val t = texto(e)
					val nivel = e.parents().count { local(it) == "section" }
					if (t.length in 1..150 && t.any { it.isLetterOrDigit() } && e.parent()?.let { local(it) } == "section") {
						e.tagName("h${(nivel + 1).coerceIn(2, 6)}")
					} else {
						e.tagName("p")
					}
				}
				"empty-line" -> e.remove()
				"poem", "epigraph", "cite" -> e.tagName("blockquote")
				"stanza" -> {
					val versos = hijos(e, "v")
					for ((i, v) in versos.withIndex()) {
						v.tagName("span")
						if (i < versos.size - 1) v.after(Element("br"))
					}
					e.tagName("p")
				}
				"v", "text-author" -> e.tagName("p")
				"emphasis" -> e.tagName("em")
				"strikethrough" -> e.tagName("s")
				"style" -> e.tagName("span")
				"annotation" -> e.tagName("div")
				"a" -> {
					val href = e.attributes().firstOrNull { it.key.endsWith("href", ignoreCase = true) }?.value.orEmpty()
					if (href.isNotEmpty()) e.attr("href", href)
					if (e.attr("type").equals("note", ignoreCase = true)) e.addClass("noteref")
				}
				"image" -> {
					val alt = e.attr("alt").ifBlank { e.attr("title") }
					val id = e.attributes().firstOrNull { it.key.endsWith("href", ignoreCase = true) }?.value.orEmpty().removePrefix("#")
					val datos = binarios[id]
					when {
						!opciones.leerImagenes -> e.remove()
						datos != null -> {
							e.tagName("img")
							e.attr("src", datos)
							if (alt.isNotBlank()) e.attr("alt", alt)
						}
						alt.isBlank() -> e.remove()
					}
				}
			}
		}
	}
}
