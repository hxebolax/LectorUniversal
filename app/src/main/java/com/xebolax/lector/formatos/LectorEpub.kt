package com.xebolax.lector.formatos

import com.xebolax.lector.modelo.ConstructorDocumento
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.Textos
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * EPUB 2 y 3: container.xml → OPF (metadatos, manifiesto y orden de
 * lectura) → cada capítulo XHTML con ConversorHtml. Índice del nav de EPUB 3
 * o del NCX; páginas del page-list o del pageList del NCX. Si el libro no
 * tiene encabezados h1-h6, los títulos del índice se convierten en
 * encabezados para poder navegar por ellos.
 */
object LectorEpub : LectorFormato {

	/** Ofuscación de fuentes: no es DRM. */
	private val OFUSCACION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		fuente.contenedor().use { z ->
			// Un .epub dentro de un ZIP
			if (z.buscar("META-INF/container.xml") == null && z.entradas().none { it.lowercase().endsWith(".opf") }) {
				throw ExcepcionFormato("No es un EPUB válido: falta el archivo del paquete (OPF).")
			}
			comprobarDrm(z)
			return Contenido.Texto(leerContenedor(z, ctx).construir())
		}
	}

	private fun comprobarDrm(z: Contenedor) {
		val ruta = z.buscar("META-INF/encryption.xml") ?: return
		val xml = Jsoup.parse(z.texto(ruta).orEmpty(), "", Parser.xmlParser())
		val algoritmos = xml.select("EncryptionMethod, enc|EncryptionMethod").map { it.attr("Algorithm") }
		val cifrados = xml.select("EncryptedData, enc|EncryptedData").size
		if (cifrados > 0 && algoritmos.any { it !in OFUSCACION }) {
			// Solo fuentes ofuscadas no impide leer; contenido cifrado sí
			val referencias = xml.select("CipherReference, enc|CipherReference").map { it.attr("URI").lowercase() }
			if (referencias.isEmpty() || referencias.any { it.endsWith("html") || it.endsWith("htm") || it.endsWith("xml") }) {
				throw ExcepcionDrm("Este libro tiene DRM (protección anticopia) y no se puede abrir. Usa la aplicación de la tienda donde lo compraste.")
			}
		}
	}

	private fun xml(z: Contenedor, ruta: String): Document =
		Jsoup.parse(z.texto(ruta) ?: throw ExcepcionFormato("Falta $ruta en el EPUB."), "", Parser.xmlParser())

	private fun local(e: Element) = e.normalName().substringAfter(':')

	private fun hijosLocales(e: Element, nombre: String) = e.children().filter { local(it) == nombre }

	private fun buscarTodos(e: Element, nombre: String): List<Element> = e.getAllElements().filter { local(it) == nombre }

	fun leerContenedor(z: Contenedor, ctx: ContextoLectura): ConstructorDocumento {
		val c = ctx.nuevoConstructor()
		val rutaOpf = z.buscar("META-INF/container.xml")?.let { cont ->
			buscarTodos(xml(z, cont), "rootfile").firstOrNull { it.attr("media-type").contains("oebps") || it.hasAttr("full-path") }
				?.attr("full-path")
		}?.let { z.buscar(it) } ?: z.entradas().firstOrNull { it.lowercase().endsWith(".opf") }
		?: throw ExcepcionFormato("No es un EPUB válido: falta el archivo del paquete (OPF).")
		val opf = xml(z, rutaOpf)

		// --- Metadatos
		val metadatos = buscarTodos(opf, "metadata").firstOrNull()
		fun dc(nombre: String) = metadatos?.let { buscarTodos(it, nombre).map { e -> e.text().trim() }.filter { t -> t.isNotEmpty() } }.orEmpty()
		c.titulo = dc("title").firstOrNull().orEmpty()
		c.autor = dc("creator").joinToString(", ")
		c.idioma = dc("language").firstOrNull().orEmpty()
		c.metadatos["Autor"] = c.autor
		c.metadatos["Editorial"] = dc("publisher").joinToString(", ")
		c.metadatos["Fecha"] = dc("date").firstOrNull().orEmpty()
		c.metadatos["Idioma"] = c.idioma
		c.metadatos["Descripción"] = dc("description").firstOrNull()?.let { Jsoup.parse(it).text() }.orEmpty()
		c.metadatos["Temas"] = dc("subject").joinToString(", ")
		c.metadatos["Identificador"] = dc("identifier").firstOrNull().orEmpty()
		c.metadatos["Derechos"] = dc("rights").firstOrNull().orEmpty()
		// Serie: <meta name="calibre:series"> (con calibre:series_index) o, en EPUB 3, belongs-to-collection con group-position
		metadatos?.let { m ->
			val metas = buscarTodos(m, "meta")
			val calibre = metas.firstOrNull { it.attr("name") == "calibre:series" }?.attr("content")?.trim().orEmpty()
			val indice = metas.firstOrNull { it.attr("name") == "calibre:series_index" }?.attr("content")?.trim().orEmpty()
			val coleccion = metas.firstOrNull { it.attr("property") == "belongs-to-collection" }
			val nombre = calibre.ifBlank { coleccion?.text()?.trim().orEmpty() }
			val numero = indice.ifBlank {
				coleccion?.attr("id")?.takeIf { it.isNotBlank() }?.let { id -> metas.firstOrNull { it.attr("refines") == "#$id" && it.attr("property") == "group-position" }?.text()?.trim() }.orEmpty()
			}
			if (nombre.isNotEmpty()) c.metadatos["Serie"] = if (numero.isEmpty() || numero == "0" || numero == "0.0") nombre else "$nombre, ${numero.removeSuffix(".0")}"
		}
		metadatos?.let { m ->
			val propiedades = buscarTodos(m, "meta").associate { it.attr("property").ifBlank { it.attr("name") } to it.text().ifBlank { it.attr("content") } }
			propiedades["schema:accessibilitySummary"]?.let { c.metadatos["Accesibilidad"] = it }
			val riesgos = buscarTodos(m, "meta").filter { it.attr("property") == "schema:accessibilityHazard" }.map { it.text() }
			if (riesgos.any { it == "flashing" }) c.avisos += "Este libro avisa de contenido con destellos."
			if (riesgos.any { it == "motionSimulation" }) c.avisos += "Este libro avisa de simulación de movimiento."
			if (riesgos.any { it == "sound" }) c.avisos += "Este libro avisa de contenido sonoro."
			propiedades["dcterms:modified"]?.let { c.metadatos["Modificado"] = it }
		}

		// --- Manifiesto y orden de lectura
		class Elemento(val ruta: String, val tipo: String, val propiedades: String)
		val manifiesto = HashMap<String, Elemento>()
		buscarTodos(opf, "item").forEach { item ->
			manifiesto[item.attr("id")] = Elemento(
				Contenedor.resolver(rutaOpf, item.attr("href")),
				item.attr("media-type"),
				item.attr("properties"),
			)
		}
		val spine = buscarTodos(opf, "spine").firstOrNull()
		val orden = spine?.let { s -> hijosLocales(s, "itemref").mapNotNull { manifiesto[it.attr("idref")] } }.orEmpty()
			.filter { it.tipo.contains("html") || it.tipo.contains("xml") || it.ruta.lowercase().endsWith("htm") || it.ruta.lowercase().endsWith("html") }
		if (orden.isEmpty()) throw ExcepcionFormato("El EPUB no tiene capítulos que leer.")

		// --- Capítulos
		for ((i, elemento) in orden.withIndex()) {
			ctx.comprobar()
			ctx.progreso(i * 90 / orden.size)
			val ruta = z.buscar(elemento.ruta) ?: continue
			val bytes = z.bytes(ruta) ?: continue
			val texto = Textos.decodificar(bytes, "utf-8")
			val documento = if (elemento.tipo.contains("xhtml") || elemento.tipo.contains("xml") || ruta.lowercase().endsWith("xhtml")) {
				runCatching { Jsoup.parse(texto, "", Parser.xmlParser()) }.getOrElse { Jsoup.parse(texto) }
			} else Jsoup.parse(texto)
			val cuerpo = documento.getAllElements().firstOrNull { local(it) == "body" } ?: documento
			c.marcarSiguiente(Marca.INICIO_SECCION)
			c.ancla(elemento.ruta)
			c.ancla(ruta)
			val idioma = documento.getAllElements().firstOrNull { local(it) == "html" }?.let { it.attr("xml:lang").ifBlank { it.attr("lang") } }
			ConversorHtml(c, elemento.ruta, ctx.opciones, c.idioma.ifBlank { idioma.orEmpty() }).convertir(cuerpo)
		}

		// --- Índice y páginas
		val entradas = mutableListOf<Triple<String, Int, String>>()
		val nav = manifiesto.values.firstOrNull { it.propiedades.split(' ').contains("nav") }
		var conIndice = false
		if (nav != null) z.buscar(nav.ruta)?.let { rutaNav ->
			val doc = runCatching { Jsoup.parse(z.texto(rutaNav).orEmpty(), "", Parser.xmlParser()) }.getOrNull() ?: return@let
			val navs = buscarTodos(doc, "nav")
			navs.firstOrNull { tipoNav(it).contains("toc") }?.let { toc ->
				hijosLocales(toc, "ol").firstOrNull()?.let { indiceNav(c, it, nav.ruta, 1, entradas) }
				conIndice = true
			}
			navs.firstOrNull { tipoNav(it).contains("page-list") || tipoNav(it).contains("pagelist") }?.let { lista ->
				buscarTodos(lista, "a").forEach { a ->
					c.paginaAncla(a.text(), destino(nav.ruta, a.attr("href")))
				}
			}
		}
		val ncx = manifiesto.values.firstOrNull { it.tipo == "application/x-dtbncx+xml" }
			?: spine?.attr("toc")?.let { manifiesto[it] }
		if (ncx != null) z.buscar(ncx.ruta)?.let { rutaNcx ->
			val doc = runCatching { xml(z, rutaNcx) }.getOrNull() ?: return@let
			if (!conIndice) {
				buscarTodos(doc, "navMap").firstOrNull()?.let { mapa ->
					hijosLocales(mapa, "navPoint").forEach { indiceNcx(c, it, ncx.ruta, 1, entradas) }
				}
			}
			buscarTodos(doc, "pageTarget").forEach { p ->
				val etiqueta = buscarTodos(p, "text").firstOrNull()?.text().orEmpty()
				val src = buscarTodos(p, "content").firstOrNull()?.attr("src").orEmpty()
				if (etiqueta.isNotBlank() && src.isNotBlank()) c.paginaAncla(etiqueta, destino(ncx.ruta, src))
			}
		}
		if (!c.tieneEncabezados()) encabezadosDesdeIndice(c, entradas)
		if (c.titulo.isBlank()) c.titulo = rutaOpf.substringAfterLast('/').substringBeforeLast('.')
		ctx.progreso(100)
		return c
	}

	private fun tipoNav(nav: Element) = (nav.attr("epub:type") + " " + nav.attr("role") + " " + nav.attr("type")).lowercase()

	/** Destino de un enlace del índice: "ruta" o "ruta#id", como las anclas del conversor. */
	private fun destino(base: String, href: String): String {
		val archivo = Contenedor.resolver(base, href)
		val fragmento = href.substringAfter('#', "")
		return if (fragmento.isEmpty()) archivo else "$archivo#$fragmento"
	}

	private fun indiceNav(c: ConstructorDocumento, ol: Element, base: String, nivel: Int, entradas: MutableList<Triple<String, Int, String>>) {
		for (li in hijosLocales(ol, "li")) {
			val enlace = li.children().firstOrNull { local(it) == "a" || local(it) == "span" }
			val titulo = enlace?.text().orEmpty().trim()
			val href = enlace?.attr("href").orEmpty()
			if (titulo.isNotEmpty() && href.isNotEmpty()) {
				c.indiceAncla(titulo, nivel, destino(base, href))
				entradas += Triple(titulo, nivel, destino(base, href))
			}
			hijosLocales(li, "ol").firstOrNull()?.let { indiceNav(c, it, base, nivel + 1, entradas) }
		}
	}

	private fun indiceNcx(c: ConstructorDocumento, punto: Element, base: String, nivel: Int, entradas: MutableList<Triple<String, Int, String>>) {
		val etiqueta = punto.children().firstOrNull { local(it) == "navLabel" }?.let { buscarTodos(it, "text").firstOrNull()?.text() }.orEmpty().trim()
		val src = punto.children().firstOrNull { local(it) == "content" }?.attr("src").orEmpty()
		if (etiqueta.isNotEmpty() && src.isNotEmpty()) {
			c.indiceAncla(etiqueta, nivel, destino(base, src))
			entradas += Triple(etiqueta, nivel, destino(base, src))
		}
		hijosLocales(punto, "navPoint").forEach { indiceNcx(c, it, base, nivel + 1, entradas) }
	}

	/** Libro sin h1-h6: el bloque al que apunta cada entrada del índice pasa a ser encabezado si su texto coincide. */
	private fun encabezadosDesdeIndice(c: ConstructorDocumento, entradas: List<Triple<String, Int, String>>) {
		for ((titulo, nivel, destino) in entradas) {
			val bloque = c.resolver(destino) ?: continue
			val texto = c.textoDe(bloque) ?: continue
			val a = normalizar(texto)
			val b = normalizar(titulo)
			if (a.isNotEmpty() && b.isNotEmpty() && (a == b || a.contains(b) || b.contains(a))) c.cambiarAEncabezado(bloque, nivel)
		}
	}

	private fun normalizar(t: String) = t.lowercase().filter { it.isLetterOrDigit() }
}
