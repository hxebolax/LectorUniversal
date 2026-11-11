package com.xebolax.lector.formatos

import com.xebolax.lector.modelo.ConstructorDocumento
import com.xebolax.lector.modelo.Enlace
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.TipoBloque
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Convierte HTML/XHTML (capítulos de EPUB, páginas web, MOBI, Markdown,
 * DTBook de DAISY) en bloques del documento:
 *
 * - h1-h6 → encabezados; p, div, li, blockquote, pre... → un bloque cada uno.
 * - Listas: cada elemento es un bloque (los numerados llevan su número).
 * - Tablas: una fila por bloque; con encabezados, "Columna: valor; ...".
 *   Las tablas de una sola columna (maquetación) se leen como texto normal.
 * - Imágenes: "Imagen: texto alternativo"; fórmulas: su texto alternativo.
 * - Saltos de página (epub:type="pagebreak") → páginas del documento.
 * - Notas al pie: las llamadas se marcan y las notas son bloques NOTA.
 * - Los id de los elementos se registran como anclas "ruta#id" para resolver enlaces.
 *
 * ruta: archivo del que viene el HTML dentro del libro ("" si es uno solo).
 */
class ConversorHtml(
	private val c: ConstructorDocumento,
	private val ruta: String,
	private val opciones: OpcionesLectura,
	/** Idioma del documento: los bloques en otro idioma lo indican (para la voz). */
	private val idiomaDocumento: String = c.idioma,
	/**
	 * ¿Es de adorno una imagen sin descripción? (la mide y cuenta quien tiene
	 * el libro: pequeñas o repetidas, como separadores y viñetas, no se leen).
	 */
	private val imagenDeAdorno: (String) -> Boolean = { false },
) {

	private class Lista(val ordenada: Boolean, var contador: Int, var primero: Boolean = true)

	private val texto = StringBuilder()
	private val enlaces = mutableListOf<Enlace>()
	private var marcas = 0
	private var nivelEncabezado = 0
	private var enPre = 0
	private var enCita = 0
	private var enNota = 0
	private var enFigura = 0
	private var enElementoLista = 0
	private var soloImagen = false
	private var hayTextoNoImagen = false
	/** Primera imagen del bloque que se está montando (para describirla). */
	private var imagenBloque: String? = null
	/** Dónde empieza "Imagen sin descripción" en el texto (se quita si el bloque tiene más texto). */
	private var sinDescripcion = -1
	private val listas = ArrayDeque<Lista>()
	private val idiomas = ArrayDeque<String>()
	private var prefijoLista = ""
	private var marcaListaPendiente = false
	/** Destino de un enlace que envuelve bloques enteros (<a><p>…</p></a>). */
	private var enlaceEnvolvente: String? = null

	fun convertir(raiz: Element) {
		recorrer(raiz)
		volcar()
	}

	// --- Recorrido -----------------------------------------------------------

	private fun recorrer(nodo: Node) {
		when (nodo) {
			is TextNode -> anadirTexto(nodo.wholeText)
			is Element -> elemento(nodo)
		}
	}

	private fun hijos(e: Element) {
		for (hijo in e.childNodes()) recorrer(hijo)
	}

	private fun nombre(e: Element): String = e.normalName().substringAfter(':')

	private fun tipoEpub(e: Element): String =
		(e.attr("epub:type") + " " + e.attr("role") + " " + e.attr("type")).lowercase()

	private fun elemento(e: Element) {
		val nombre = nombre(e)
		if (nombre in IGNORAR) return
		val tipo = tipoEpub(e)

		// Saltos de página: no se leen, marcan la página
		if (tipo.contains("pagebreak") || tipo.contains("page-break")) {
			val etiqueta = e.attr("title").ifBlank { e.attr("aria-label") }.ifBlank { e.text() }.trim()
			registrarAnclas(e)
			if (etiqueta.isNotEmpty()) c.pagina(etiqueta, c.tamano)
			return
		}

		val idioma = e.attr("xml:lang").ifBlank { e.attr("lang") }
		if (idioma.isNotBlank()) idiomas.addLast(idioma)
		try {
			procesar(e, nombre, tipo)
		} finally {
			if (idioma.isNotBlank()) idiomas.removeLast()
		}
	}

	private fun procesar(e: Element, nombre: String, tipo: String) {
		// Encabezado hecho con ARIA (role="heading" y aria-level): como un h1-h6
		if (e.attr("role").trim().equals("heading", ignoreCase = true) && nombre !in ENCABEZADOS) {
			volcar()
			registrarAnclas(e)
			nivelEncabezado = e.attr("aria-level").trim().toIntOrNull()?.coerceIn(1, 6) ?: 2
			hijos(e)
			volcar()
			nivelEncabezado = 0
			return
		}
		when (nombre) {
			// En XHTML mal cerrado (<br> sin «/», leído como XML) el texto que sigue queda dentro: se recorre también
			"br" -> {
				registrarAnclas(e)
				if (texto.isNotEmpty() && texto.last() != '\n') texto.append('\n')
				hijos(e)
			}
			"hr" -> {
				registrarAnclas(e)
				volcar()
				c.separador()
				hijos(e)
			}
			"h1", "h2", "h3", "h4", "h5", "h6" -> {
				volcar()
				registrarAnclas(e)
				nivelEncabezado = nombre[1] - '0'
				hijos(e)
				volcar()
				nivelEncabezado = 0
			}
			"img", "image" -> {
				imagen(e)
				hijos(e)
			}
			"svg" -> {
				registrarAnclas(e)
				val titulo = e.selectFirst("title")?.text().orEmpty().ifBlank { e.attr("aria-label") }
					.ifBlank { e.selectFirst("desc")?.text().orEmpty() }.trim()
				// Una imagen dentro del SVG (las portadas de muchos EPUB)
				val dentro = e.getAllElements().firstOrNull { nombre(it) == "image" }?.let { recursoImagen(it) }
				when {
					titulo.isNotBlank() -> anadirImagen(titulo, dentro)
					dentro != null && opciones.leerImagenes && texto.isBlank() && !esAdorno(e) && !imagenDeAdorno(dentro) -> anadirImagen(null, dentro)
					// Un dibujo con texto (un esquema, un título dibujado): se lee su texto
					else -> e.getAllElements().filter { nombre(it) == "text" }.joinToString(" ") { it.text() }.trim()
						.takeIf { it.isNotEmpty() }?.let { anadirTexto(" $it ") }
				}
			}
			"math" -> {
				registrarAnclas(e)
				// Lo que puso el autor; si no, la fórmula dicha con palabras; si no, su TeX o su texto
				val alt = e.attr("alttext").ifBlank { runCatching { Matematicas.hablar(e, idiomaActual()) }.getOrDefault("") }.ifBlank {
					e.selectFirst("annotation[encoding*=TeX], annotation")?.text().orEmpty()
				}.ifBlank { e.text() }
				if (alt.isNotBlank()) {
					separarSiHaceFalta()
					texto.append("[Fórmula: ").append(alt.trim()).append("]")
					hayTextoNoImagen = true
					marcas = marcas or Marca.FORMULA
				}
			}
			"a" -> enlace(e, tipo)
			"table" -> tabla(e)
			"ul", "ol", "menu", "dir" -> {
				volcar()
				registrarAnclas(e)
				val inicio = e.attr("start").toIntOrNull() ?: 1
				listas.addLast(Lista(nombre == "ol", inicio))
				hijos(e)
				volcar()
				listas.removeLast()
			}
			"li" -> {
				volcar()
				registrarAnclas(e)
				val lista = listas.lastOrNull()
				prefijoLista = if (lista?.ordenada == true) "${e.attr("value").toIntOrNull() ?: lista.contador}. " else ""
				lista?.let {
					it.contador = (e.attr("value").toIntOrNull() ?: it.contador) + 1
					if (it.primero) {
						marcaListaPendiente = true
						it.primero = false
					}
				}
				enElementoLista++
				hijos(e)
				volcar()
				enElementoLista--
				prefijoLista = ""
			}
			"pre" -> {
				volcar()
				registrarAnclas(e)
				enPre++
				hijos(e)
				volcar()
				enPre--
			}
			"blockquote" -> {
				volcar()
				registrarAnclas(e)
				val primero = c.tamano
				enCita++
				hijos(e)
				volcar()
				enCita--
				if (c.tamano > primero) c.anadirMarca(primero, Marca.INICIO_CITA)
			}
			"figure" -> {
				volcar()
				registrarAnclas(e)
				val primero = c.tamano
				enFigura++
				hijos(e)
				volcar()
				enFigura--
				if (c.tamano > primero) c.anadirMarca(primero, Marca.FIGURA)
			}
			"aside", "section", "div", "footer", "ol-notes" -> {
				val esNota = NOTA.containsMatchIn(tipo) || NOTA_CLASE.containsMatchIn(e.className())
				volcar()
				registrarAnclas(e)
				if (esNota) enNota++
				hijos(e)
				volcar()
				if (esNota) enNota--
			}
			"rt", "rp" -> Unit
			else -> {
				if (nombre in BLOQUES) {
					volcar()
					registrarAnclas(e)
					hijos(e)
					volcar()
				} else {
					// En línea: span, em, strong, sup...
					registrarAnclas(e)
					hijos(e)
				}
			}
		}
	}

	private fun registrarAnclas(e: Element) {
		val id = e.id().ifBlank { if (nombre(e) == "a") e.attr("name") else "" }
		if (id.isNotBlank()) {
			c.ancla("$ruta#$id")
			c.ancla("#$id")
		}
	}

	/** Idioma de lo que se está leyendo (el del elemento o el del libro; vacío si no se sabe). */
	private fun idiomaActual(): String = idiomas.lastOrNull()?.takeIf { it.isNotBlank() } ?: idiomaDocumento

	// --- Texto en línea --------------------------------------------------------

	private fun anadirTexto(crudo: String) {
		if (crudo.isEmpty()) return
		if (enPre > 0) {
			if (texto.isEmpty() && prefijoLista.isNotEmpty()) texto.append(prefijoLista)
			texto.append(crudo)
			if (crudo.isNotBlank()) hayTextoNoImagen = true
			return
		}
		val colapsado = ESPACIOS.replace(crudo, " ")
		if (colapsado == " ") {
			if (texto.isNotEmpty() && !texto.last().isWhitespace()) texto.append(' ')
			return
		}
		if (texto.isEmpty() && prefijoLista.isNotEmpty()) texto.append(prefijoLista)
		val sinInicio = if (texto.isEmpty() || texto.last().isWhitespace()) colapsado.trimStart() else colapsado
		texto.append(sinInicio)
		hayTextoNoImagen = true
	}

	private fun separarSiHaceFalta() {
		if (texto.isEmpty() && prefijoLista.isNotEmpty()) texto.append(prefijoLista)
		if (texto.isNotEmpty() && !texto.last().isWhitespace()) texto.append(' ')
	}

	private fun imagen(e: Element) {
		registrarAnclas(e)
		if (!opciones.leerImagenes) return
		val alt = e.attr("alt").ifBlank { e.attr("title") }.ifBlank { e.attr("aria-label") }.trim()
		val recurso = recursoImagen(e)
		if (alt.isEmpty()) {
			// Sin descripción: solo las imágenes sueltas (ilustraciones), no adornos dentro del texto
			if (recurso == null || texto.isNotBlank() || esAdorno(e) || imagenDeAdorno(recurso)) return
			anadirImagen(null, recurso)
			return
		}
		anadirImagen(alt, recurso)
	}

	/** Dónde está la imagen: ruta dentro del libro, dirección web o "data:" (si no es enorme). */
	private fun recursoImagen(e: Element): String? {
		val atributo = listOf("src", "xlink:href", "href").firstOrNull { e.attr(it).isNotBlank() }
		val src = atributo?.let { e.attr(it) }.orEmpty().trim()
		// Página web: la dirección completa (la relativa no se encontraría luego)
		val absoluta = atributo?.let { runCatching { e.absUrl(it) }.getOrNull() }.orEmpty()
		return when {
			// MOBI 6: la imagen es un registro del libro (recindex); KF8: «kindle:embed:XXXX» (número en base 32)
			e.hasAttr("recindex") -> e.attr("recindex").trim().toIntOrNull()?.let { "${PREFIJO_MOBI}6:$it" }
			src.startsWith("kindle:embed:", ignoreCase = true) -> src.substring(13).substringBefore('?').toIntOrNull(32)?.let { "${PREFIJO_MOBI}8:$it" }
			src.isEmpty() -> null
			ruta.isEmpty() && (absoluta.startsWith("http://") || absoluta.startsWith("https://")) -> absoluta
			src.startsWith("data:") -> src.takeIf { it.length < 700_000 }
			src.contains("://") -> src
			src.lowercase().substringBefore('?').endsWith(".svg") -> null
			src.startsWith("#") -> null
			else -> Contenedor.resolver(ruta, src).ifBlank { null }
		}
	}

	/** Imágenes pequeñas (iconos, viñetas, adornos) o marcadas como decorativas. */
	private fun esAdorno(e: Element): Boolean {
		if (e.attr("role") == "presentation" || e.attr("aria-hidden") == "true") return true
		val ancho = e.attr("width").filter { it.isDigit() }.toIntOrNull()
		val alto = e.attr("height").filter { it.isDigit() }.toIntOrNull()
		return (ancho != null && ancho < 48) || (alto != null && alto < 48)
	}

	private fun anadirImagen(descripcion: String?, recurso: String? = null) {
		val vacio = texto.isBlank()
		separarSiHaceFalta()
		if (descripcion == null) sinDescripcion = texto.length
		texto.append(
			when {
				descripcion == null -> SIN_DESCRIPCION
				vacio -> "Imagen: $descripcion"
				else -> "[Imagen: $descripcion]"
			}
		)
		if (vacio) soloImagen = true
		if (imagenBloque == null && recurso != null) imagenBloque = recurso
	}

	private fun enlace(e: Element, tipo: String) {
		registrarAnclas(e)
		val href = e.attr("href").trim()
		if (href.isEmpty() || href.startsWith("javascript:")) {
			hijos(e)
			return
		}
		val destino = destinoDe(href)
		// Enlace que envuelve párrafos o encabezados (una tarjeta, una entrada del índice): cada bloque de dentro lo lleva entero
		if (e.getAllElements().any { it !== e && nombre(it) in BLOQUES_DENTRO_DE_ENLACE }) {
			val antes = enlaceEnvolvente
			volcar()
			enlaceEnvolvente = destino
			hijos(e)
			volcar()
			enlaceEnvolvente = antes
			return
		}
		separarSiHaceFalta()
		val inicio = texto.length
		hijos(e)
		val fin = texto.length
		if (fin <= inicio) return
		enlaces += Enlace(inicio, fin, destino)
		if (esLlamadaNota(e, tipo, texto.substring(inicio, fin))) marcas = marcas or Marca.LLAMADA_NOTA
	}

	/** Destino de un enlace: tal cual si es externo; si no, la ruta dentro del libro con su fragmento. */
	private fun destinoDe(href: String): String = when {
		href.contains("://") || href.startsWith("mailto:") || href.startsWith("tel:") -> href
		href.startsWith("#") -> "$ruta$href"
		else -> {
			val archivo = Contenedor.resolver(ruta, href)
			val fragmento = href.substringAfter('#', "")
			if (fragmento.isEmpty()) archivo else "$archivo#$fragmento"
		}
	}

	/** Llamada a nota: declarada (noteref) o por su forma ("[12]", "³", "*" en superíndice). */
	private fun esLlamadaNota(e: Element, tipo: String, contenido: String): Boolean {
		if (tipo.contains("noteref") || NOTA_CLASE.containsMatchIn(e.className())) return true
		val href = e.attr("href")
		if (!href.contains('#') || href.contains("://")) return false
		if (!FORMA_NOTA.matches(contenido.trim())) return false
		val enSuperindice = e.parents().take(2).any { nombre(it) == "sup" } || e.selectFirst("sup") != null
		val entreCorchetes = contenido.trim().startsWith("[")
		return enSuperindice || entreCorchetes
	}

	// --- Tablas ------------------------------------------------------------------

	private fun tabla(t: Element) {
		volcar()
		registrarAnclas(t)
		val filas = t.select("tr").filter { fila -> fila.parents().firstOrNull { nombre(it) == "table" } == t }
		val columnas = filas.maxOfOrNull { fila -> fila.children().count { nombre(it) == "td" || nombre(it) == "th" } } ?: 0
		// Tabla de maquetación (una columna, o con títulos dentro de las celdas): se lee como texto normal,
		// para no perder sus encabezados al navegar
		val conTitulos = filas.any { fila -> fila.select("h1, h2, h3, h4, h5, h6, [role=heading]").any { h -> h.parents().firstOrNull { nombre(it) == "table" } == t } }
		if (columnas <= 1 || conTitulos) {
			hijos(t)
			volcar()
			return
		}
		val primero = c.tamano
		t.children().firstOrNull { nombre(it) == "caption" }?.let { titulo ->
			c.bloque(TipoBloque.PARRAFO, "Tabla: " + titulo.text())
		}
		val celdasDe = { fila: Element -> fila.children().filter { nombre(it) == "td" || nombre(it) == "th" } }
		val primeraFila = filas.firstOrNull()
		val conEncabezado = primeraFila != null && (celdasDe(primeraFila).all { nombre(it) == "th" } ||
			primeraFila.parents().any { nombre(it) == "thead" })
		val encabezados = if (conEncabezado) celdasDe(primeraFila!!).map { celda(it).first } else emptyList()
		if (c.tamano == primero) {
			c.bloque(TipoBloque.PARRAFO, "Tabla de ${filas.size} filas y $columnas columnas")
		}
		for ((i, fila) in filas.withIndex()) {
			registrarAnclas(fila)
			val celdas = celdasDe(fila)
			celdas.forEach { registrarAnclas(it) }
			val cabeceraFila = i == 0 && conEncabezado
			val conNombres = !cabeceraFila && opciones.tablasConEncabezados && encabezados.isNotEmpty()
			// (prefijo «Columna: », texto de la celda, sus enlaces)
			var partes = celdas.mapIndexed { j, cel ->
				val (valor, enlacesCelda) = celda(cel)
				val cabecera = if (conNombres) encabezados.getOrNull(j).orEmpty() else ""
				Triple(if (cabecera.isBlank() || valor.isBlank()) "" else "$cabecera: ", valor, enlacesCelda)
			}
			if (conNombres) partes = partes.filter { it.second.isNotBlank() }
			val separador = if (conNombres) "; " else " | "
			val linea = StringBuilder()
			val enlacesFila = ArrayList<Enlace>()
			for ((k, p) in partes.withIndex()) {
				if (k > 0) linea.append(separador)
				linea.append(p.first)
				val base = linea.length
				linea.append(p.second)
				for (en in p.third) enlacesFila += Enlace(base + en.inicio, base + en.fin, en.destino)
			}
			val vacia = linea.isBlank()
			c.bloque(
				TipoBloque.FILA_TABLA, if (vacia) "(fila vacía)" else linea.toString(),
				enlaces = if (vacia) emptyList() else enlacesFila,
				marcas = if (cabeceraFila) Marca.ENCABEZADO_TABLA else 0,
			)
		}
		if (c.tamano > primero) c.anadirMarca(primero, Marca.INICIO_TABLA)
	}

	private fun textoCelda(celda: Element): String {
		val partes = StringBuilder()
		for (img in celda.select("img")) {
			val alt = img.attr("alt")
			if (alt.isNotBlank()) img.after(TextNode(" [Imagen: $alt] "))
		}
		// Fórmulas dentro de la celda: dichas con palabras, como fuera de las tablas (no sus símbolos sueltos)
		for (m in celda.select("math")) {
			val alt = m.attr("alttext").ifBlank { runCatching { Matematicas.hablar(m, idiomaActual()) }.getOrDefault("") }.ifBlank {
				m.selectFirst("annotation[encoding*=TeX], annotation")?.text().orEmpty()
			}.ifBlank { m.text() }
			m.replaceWith(TextNode(if (alt.isBlank()) " " else " [Fórmula: ${alt.trim()}] "))
		}
		partes.append(celda.text())
		return ESPACIOS.replace(partes.toString(), " ").trim()
	}

	/** Texto de una celda y sus enlaces (posiciones dentro de ese texto). */
	private fun celda(e: Element): Pair<String, List<Enlace>> {
		val texto = textoCelda(e)
		val propios = ArrayList<Enlace>()
		var desde = 0
		for (a in e.select("a[href]")) {
			val href = a.attr("href").trim()
			if (href.isEmpty() || href.startsWith("javascript:")) continue
			val t = ESPACIOS.replace(a.text(), " ").trim()
			if (t.isEmpty()) continue
			val i = texto.indexOf(t, desde)
			if (i < 0) continue
			propios += Enlace(i, i + t.length, destinoDe(href))
			desde = i + t.length
		}
		return texto to propios
	}

	// --- Volcado de bloques --------------------------------------------------------

	private fun volcar() {
		// Imagen sin descripción con texto detrás (una letra capital, un adorno): solo el texto
		if (sinDescripcion >= 0 && hayTextoNoImagen && enlaces.none { it.inicio < sinDescripcion + SIN_DESCRIPCION.length }) {
			val fin = (sinDescripcion + SIN_DESCRIPCION.length).coerceAtMost(texto.length)
			var hasta = fin
			while (hasta < texto.length && texto[hasta] == ' ') hasta++
			texto.delete(sinDescripcion, hasta)
			val quitados = hasta - sinDescripcion
			for (i in enlaces.indices) enlaces[i] = Enlace(enlaces[i].inicio - quitados, enlaces[i].fin - quitados, enlaces[i].destino)
			soloImagen = false
		}
		sinDescripcion = -1
		if (texto.isBlank()) {
			reiniciar()
			return
		}
		val tipo = when {
			nivelEncabezado > 0 -> TipoBloque.ENCABEZADO
			enPre > 0 -> TipoBloque.CODIGO
			soloImagen && !hayTextoNoImagen -> TipoBloque.IMAGEN
			enNota > 0 -> TipoBloque.NOTA
			enElementoLista > 0 && listas.isNotEmpty() -> TipoBloque.ELEMENTO_LISTA
			enCita > 0 -> TipoBloque.CITA
			enFigura > 0 && soloImagen -> TipoBloque.IMAGEN
			else -> TipoBloque.PARRAFO
		}
		val nivel = when (tipo) {
			TipoBloque.ENCABEZADO -> nivelEncabezado
			TipoBloque.ELEMENTO_LISTA -> listas.size
			else -> 0
		}
		var m = marcas
		if (tipo == TipoBloque.ELEMENTO_LISTA && marcaListaPendiente) {
			m = m or Marca.INICIO_LISTA
			marcaListaPendiente = false
		}
		val idioma = idiomas.lastOrNull()?.takeIf {
			idiomaDocumento.isNotBlank() && !it.substringBefore('-').equals(idiomaDocumento.substringBefore('-'), ignoreCase = true)
		}
		val contenido = if (enPre > 0) texto.toString().trimEnd() else texto.toString()
		// Dentro de un enlace que envuelve bloques: el bloque entero es el enlace (si no tiene otros propios)
		enlaceEnvolvente?.let { if (enlaces.isEmpty()) enlaces += Enlace(0, contenido.length, it) }
		val n = c.bloque(tipo, contenido, nivel, enlaces.toList(), m, idioma)
		imagenBloque?.let { if (n >= 0) c.imagen(n, it) }
		// Un elemento de lista partido en varios bloques: solo el primero lleva el número
		prefijoLista = ""
		reiniciar()
	}

	private fun reiniciar() {
		texto.setLength(0)
		enlaces.clear()
		marcas = 0
		soloImagen = false
		hayTextoNoImagen = false
		imagenBloque = null
	}

	companion object {
		/** Imágenes de MOBI: «mobi:6:n» (recindex) o «mobi:8:n» (kindle:embed); las saca LectorMobi.imagen. */
		const val PREFIJO_MOBI = "mobi:"
		private val ESPACIOS = Regex("[ \t\r\n\u000C]+")
		private val IGNORAR = setOf(
			// «object» no: lo de dentro es el texto alternativo (si no se puede mostrar el objeto)
			"head", "script", "style", "noscript", "template", "title", "meta", "link", "embed", "param",
			"iframe", "audio", "video", "source", "track", "canvas", "map", "input", "select", "textarea", "button",
		)
		private val BLOQUES = setOf(
			"p", "div", "article", "main", "header", "nav", "address", "center", "details", "summary",
			"figcaption", "caption", "dl", "dt", "dd", "body", "html", "hgroup", "form", "fieldset", "legend",
			"tr", "td", "th", "thead", "tbody", "tfoot", "option", "dialog",
			// DTBook (DAISY 3) y FictionBook
			"level", "level1", "level2", "level3", "level4", "level5", "level6", "frontmatter", "bodymatter",
			"rearmatter", "doctitle", "docauthor", "sidebar", "prodnote", "poem", "linegroup", "line", "stanza",
			"epigraph", "v", "subtitle", "text-author", "annotation", "cite",
		)
		private val ENCABEZADOS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
		/** Lo que, dentro de un enlace, hace que el enlace envuelva bloques y no texto. */
		private val BLOQUES_DENTRO_DE_ENLACE = BLOQUES + ENCABEZADOS + setOf(
			"ul", "ol", "li", "pre", "blockquote", "figure", "aside", "section", "footer", "table", "hr",
		)
		private val NOTA = Regex("footnote|endnote|rearnote|doc-note|doc-footnote|doc-endnote|\\bnote\\b")
		private val NOTA_CLASE = Regex("(?i)\\b(footnote|endnote|nota|notas|note|noteref|fn)\\b")
		private val FORMA_NOTA = Regex("""^[\[\(\{]?\s*(?:\d{1,4}|[a-z]|[*†‡§¶]{1,3})\s*[\]\)\}]?$""")
	}
}
