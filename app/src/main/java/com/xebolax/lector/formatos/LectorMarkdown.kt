package com.xebolax.lector.formatos

import com.xebolax.lector.modelo.ConstructorDocumento
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.Enlace
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.Textos
import com.xebolax.lector.modelo.TipoBloque
import org.commonmark.Extension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup

/**
 * Markdown (CommonMark con las tablas de GitHub) y reStructuredText.
 *
 * - Markdown → HTML → ConversorHtml. Los encabezados reciben un id como en
 *   GitHub ("#mi-seccion") para que funcionen los enlaces internos. La
 *   cabecera YAML (--- title: ... ---) da el título y el autor.
 * - reStructuredText: los títulos subrayados (====, ----, ~~~~...) son
 *   encabezados con el nivel según el orden de aparición de cada estilo;
 *   el resto, párrafos, listas, citas y bloques de código.
 */
object LectorMarkdown : LectorFormato {

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val texto = Textos.decodificar(fuente.bytes(64L * 1024 * 1024)).replace("\r\n", "\n").replace('\r', '\n')
		val c = ctx.nuevoConstructor()
		if (fuente.extension == "rst") leerRst(texto, c, ctx) else leerMarkdown(texto, c, ctx)
		if (c.titulo.isBlank()) c.titulo = fuente.nombre.substringBeforeLast('.')
		return Contenido.Texto(c.construir())
	}

	// --- Markdown ------------------------------------------------------------------

	private val CABECERA_YAML = Regex("""\A---[ \t]*\n(.*?)\n(?:---|\.\.\.)[ \t]*(?:\n|\z)""", RegexOption.DOT_MATCHES_ALL)

	private fun leerMarkdown(original: String, c: ConstructorDocumento, ctx: ContextoLectura) {
		var texto = original.removePrefix("﻿")
		CABECERA_YAML.find(texto)?.let { m ->
			for (linea in m.groupValues[1].lines()) {
				val clave = linea.substringBefore(':', "").trim().lowercase()
				val valor = linea.substringAfter(':', "").trim().trim('"', '\'')
				if (valor.isEmpty()) continue
				when (clave) {
					"title", "titulo", "título" -> c.titulo = valor
					"author", "autor" -> { c.autor = valor; c.metadatos["Autor"] = valor }
					"date", "fecha" -> c.metadatos["Fecha"] = valor
					"lang", "language", "idioma" -> { c.idioma = valor; c.metadatos["Idioma"] = valor }
					"description", "descripcion", "descripción", "summary" -> c.metadatos["Descripción"] = valor
				}
			}
			texto = texto.substring(m.range.last + 1)
		}
		ctx.comprobar()
		val extensiones: List<Extension> = listOf(TablesExtension.create())
		val parser = Parser.builder().extensions(extensiones).build()
		val html = HtmlRenderer.builder().extensions(extensiones).build().render(parser.parse(texto))
		ctx.progreso(50)
		val doc = Jsoup.parse(html)
		// Ids de los encabezados al estilo de GitHub
		val usados = HashSet<String>()
		for (h in doc.select("h1, h2, h3, h4, h5, h6")) {
			if (h.id().isNotBlank()) { usados += h.id(); continue }
			val base = slug(h.text()).ifEmpty { "seccion" }
			var id = base
			var n = 1
			while (!usados.add(id)) id = "$base-${n++}"
			h.attr("id", id)
		}
		if (c.titulo.isBlank()) {
			c.titulo = (doc.selectFirst("h1") ?: doc.selectFirst("h2, h3, h4, h5, h6"))?.text()?.trim().orEmpty()
		}
		ctx.comprobar()
		ConversorHtml(c, "", ctx.opciones).convertir(doc.body())
	}

	/** Id de un encabezado como en GitHub: minúsculas, sin puntuación y espacios → guiones. */
	private fun slug(texto: String): String =
		texto.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}\\s_-]"), "").replace(Regex("\\s"), "-")

	// --- reStructuredText ----------------------------------------------------------

	private const val ADORNOS = "=-`:'\"~^_*+#<>."
	private val CAMPO_RST = Regex("""^:([^:]+):\s*(.*)$""")
	private val DIRECTIVA = Regex("""^\.\.\s+([\w:-]+)::\s*(.*)$""")
	private val NOTA_RST = Regex("""^\.\.\s+\[([^\]]+)]\s*(.*)$""")
	private val ITEM_RST = Regex("""^(\s*)([-*+•]|#\.|\d+[.)]|\(\d+\))\s+(.*)$""")
	private val EN_LINEA_RST = Regex(
		"""`([^`<]+?)\s*<([^>]+)>`_{1,2}|:[\w:+-]+:`([^`]+)`|``(.+?)``|\*\*(.+?)\*\*|\*([^*\s][^*]*?)\*|`([^`]+)`_{0,2}|\|([^|\s][^|]*)\|_{0,2}|\[(\d+|#[\w-]*|\*)]_"""
	)

	private fun esAdorno(linea: String?, minimo: Int = 3): Boolean {
		val t = linea?.trim() ?: return false
		return t.length >= minimo && t[0] in ADORNOS && t.all { it == t[0] }
	}

	private fun sangrada(linea: String) = linea.isNotEmpty() && (linea[0] == ' ' || linea[0] == '\t')

	/** Marcas en línea de RST → texto plano (con los enlaces externos). */
	private fun enLineaRst(s: String): Pair<String, List<Enlace>> {
		val sb = StringBuilder()
		val enlaces = ArrayList<Enlace>()
		var desde = 0
		for (m in EN_LINEA_RST.findAll(s)) {
			sb.append(s, desde, m.range.first)
			val g = m.groupValues
			when {
				g[1].isNotEmpty() -> {
					val inicio = sb.length
					sb.append(g[1].trim())
					val url = g[2].trim()
					if (url.contains("://") || url.startsWith("mailto:")) enlaces += Enlace(inicio, sb.length, url)
				}
				g[3].isNotEmpty() -> sb.append(g[3].substringBefore(" <").trim())
				g[4].isNotEmpty() -> sb.append(g[4])
				g[5].isNotEmpty() -> sb.append(g[5])
				g[6].isNotEmpty() -> sb.append(g[6])
				g[7].isNotEmpty() -> sb.append(g[7])
				g[8].isNotEmpty() -> sb.append(g[8])
				g[9].isNotEmpty() -> sb.append('[').append(g[9]).append(']')
			}
			desde = m.range.last + 1
		}
		sb.append(s, desde, s.length)
		return sb.toString() to enlaces
	}

	private fun leerRst(texto: String, c: ConstructorDocumento, ctx: ContextoLectura) {
		val lineas = texto.removePrefix("﻿").lines()
		val estilos = ArrayList<String>()
		val bufer = StringBuilder()
		var tipoBufer = TipoBloque.PARRAFO
		var nivelBufer = 0
		var prefijo = ""
		var literalPendiente = false
		var soloTitulos = true
		var ultimoTipo: TipoBloque? = null

		fun anadir(tipo: TipoBloque, contenido: String, nivel: Int = 0, enlaces: List<Enlace> = emptyList()) {
			var marcas = 0
			if (tipo == TipoBloque.ELEMENTO_LISTA && ultimoTipo != TipoBloque.ELEMENTO_LISTA) marcas = Marca.INICIO_LISTA
			if (c.bloque(tipo, contenido, nivel, enlaces, marcas) >= 0) {
				ultimoTipo = tipo
				if (tipo != TipoBloque.ENCABEZADO) soloTitulos = false
			}
		}

		fun volcar() {
			if (bufer.isBlank()) {
				bufer.setLength(0)
				tipoBufer = TipoBloque.PARRAFO
				prefijo = ""
				return
			}
			var contenido = bufer.toString().trim()
			if (contenido.endsWith("::")) {
				literalPendiente = true
				contenido = contenido.removeSuffix("::")
				contenido = if (contenido.isEmpty() || contenido.last().isWhitespace()) contenido.trimEnd() else "$contenido:"
			}
			if (contenido.isNotEmpty()) {
				val (t, enlaces) = enLineaRst(contenido)
				val desplazados = if (prefijo.isEmpty()) enlaces else enlaces.map { Enlace(it.inicio + prefijo.length, it.fin + prefijo.length, it.destino) }
				anadir(tipoBufer, prefijo + t, nivelBufer, desplazados)
			}
			bufer.setLength(0)
			tipoBufer = TipoBloque.PARRAFO
			nivelBufer = 0
			prefijo = ""
		}

		fun titulo(texto: String, estilo: String) {
			var indice = estilos.indexOf(estilo)
			if (indice < 0) {
				estilos += estilo
				indice = estilos.size - 1
			}
			val (t, enlaces) = enLineaRst(texto)
			anadir(TipoBloque.ENCABEZADO, t, (indice + 1).coerceAtMost(6), enlaces)
			if (c.titulo.isBlank()) c.titulo = t.trim()
		}

		var i = 0
		while (i < lineas.size) {
			if (i % 1000 == 0) {
				ctx.comprobar()
				ctx.progreso(i * 100 / lineas.size)
			}
			val linea = lineas[i].trimEnd()
			val siguiente = lineas.getOrNull(i + 1)?.trimEnd()

			// Bloque literal tras "::": las líneas sangradas son código
			if (literalPendiente) {
				if (linea.isBlank() || sangrada(linea)) {
					val sb = StringBuilder()
					var j = i
					while (j < lineas.size && (lineas[j].isBlank() || sangrada(lineas[j]))) {
						sb.append(lineas[j].trimEnd()).append('\n')
						j++
					}
					literalPendiente = false
					val codigo = sb.toString().trimIndent().trim('\n')
					if (codigo.isNotBlank()) anadir(TipoBloque.CODIGO, codigo)
					i = j
					continue
				}
				literalPendiente = false
			}

			// Título con línea encima y debajo
			if (esAdorno(linea) && siguiente != null && siguiente.isNotBlank() && !esAdorno(siguiente)) {
				val debajo = lineas.getOrNull(i + 2)?.trimEnd()
				if (debajo != null && esAdorno(debajo) && debajo.trim()[0] == linea.trim()[0]) {
					volcar()
					titulo(siguiente.trim(), "o" + linea.trim()[0])
					i += 3
					continue
				}
			}
			// Título subrayado
			if (linea.isNotBlank() && !sangrada(linea) && !esAdorno(linea) && siguiente != null &&
				esAdorno(siguiente, minOf(linea.trim().length, 3)) && siguiente.trim().length >= minOf(linea.trim().length, 3) &&
				!linea.startsWith("..")
			) {
				volcar()
				titulo(linea.trim(), "u" + siguiente.trim()[0])
				i += 2
				continue
			}
			// Transición (línea de adorno suelta)
			if (esAdorno(linea, 4) && (siguiente == null || siguiente.isBlank())) {
				volcar()
				c.separador()
				i++
				continue
			}
			if (linea.isBlank()) {
				volcar()
				i++
				continue
			}
			// Campos del principio (:Author: ...)
			val campo = CAMPO_RST.matchEntire(linea)
			if (campo != null && soloTitulos && bufer.isEmpty()) {
				val valor = campo.groupValues[2].trim()
				when (campo.groupValues[1].trim().lowercase()) {
					"author", "authors", "autor", "autores" -> { c.autor = valor; c.metadatos["Autor"] = valor }
					"date", "fecha" -> c.metadatos["Fecha"] = valor
					"version", "versión" -> c.metadatos["Versión"] = valor
					"organization", "organización" -> c.metadatos["Organización"] = valor
					"abstract", "resumen" -> c.metadatos["Descripción"] = valor
					else -> anadir(TipoBloque.PARRAFO, "${campo.groupValues[1].trim()}: $valor")
				}
				i++
				continue
			}
			// Directivas, notas y comentarios
			if (linea.startsWith("..")) {
				volcar()
				var j = i + 1
				val directiva = DIRECTIVA.find(linea)
				if (directiva != null) {
					val tipo = directiva.groupValues[1].lowercase()
					val argumento = directiva.groupValues[2].trim()
					var alt = ""
					while (j < lineas.size && sangrada(lineas[j]) && lineas[j].trim().startsWith(":")) {
						val opcion = lineas[j].trim()
						if (opcion.startsWith(":alt:")) alt = opcion.removePrefix(":alt:").trim()
						j++
					}
					when (tipo) {
						"image", "figure" -> if (ctx.opciones.leerImagenes && alt.isNotBlank()) anadir(TipoBloque.IMAGEN, "Imagen: $alt")
						"code", "code-block", "sourcecode", "highlight" -> literalPendiente = true
						"note" -> anadir(TipoBloque.PARRAFO, "Nota: $argumento")
						"warning", "caution", "danger", "attention" -> anadir(TipoBloque.PARRAFO, "Advertencia: $argumento")
						"tip", "hint" -> anadir(TipoBloque.PARRAFO, "Consejo: $argumento")
						"important" -> anadir(TipoBloque.PARRAFO, "Importante: $argumento")
						"admonition", "topic", "sidebar", "rubric" -> if (argumento.isNotBlank()) anadir(TipoBloque.PARRAFO, argumento)
						"title" -> if (argumento.isNotBlank()) c.titulo = argumento
						"contents", "toctree", "include", "raw", "meta", "only", "index" -> {
							// Contenido sin texto para leer: se salta con sus líneas sangradas
							while (j < lineas.size && (sangrada(lineas[j]) || (lineas[j].isBlank() && lineas.getOrNull(j + 1)?.let { sangrada(it) } == true))) j++
						}
					}
				} else {
					val nota = NOTA_RST.find(linea)
					val sb = StringBuilder(nota?.groupValues?.get(2).orEmpty())
					while (j < lineas.size && sangrada(lineas[j])) {
						sb.append(' ').append(lineas[j].trim())
						j++
					}
					if (nota != null) {
						val etiqueta = nota.groupValues[1]
						c.ancla("#nota-$etiqueta")
						val (t, enlaces) = enLineaRst(sb.toString().trim())
						val prefijoNota = "[$etiqueta] "
						anadir(TipoBloque.NOTA, prefijoNota + t, 0, enlaces.map { Enlace(it.inicio + prefijoNota.length, it.fin + prefijoNota.length, it.destino) })
					}
				}
				i = j
				continue
			}
			// Elemento de lista
			val item = ITEM_RST.matchEntire(linea)
			if (item != null && (bufer.isEmpty() || tipoBufer == TipoBloque.ELEMENTO_LISTA)) {
				volcar()
				tipoBufer = TipoBloque.ELEMENTO_LISTA
				nivelBufer = (item.groupValues[1].replace("\t", "    ").length / 2 + 1).coerceIn(1, 6)
				val marca = item.groupValues[2]
				prefijo = if (marca[0].isDigit() || marca[0] == '(') "$marca " else ""
				bufer.append(item.groupValues[3].trim())
				i++
				continue
			}
			// Continuación o párrafo nuevo (sangrado = cita)
			if (bufer.isNotEmpty()) {
				bufer.append(' ').append(linea.trim())
			} else {
				tipoBufer = if (sangrada(linea)) TipoBloque.CITA else TipoBloque.PARRAFO
				bufer.append(linea.trim())
			}
			i++
		}
		volcar()
	}
}
