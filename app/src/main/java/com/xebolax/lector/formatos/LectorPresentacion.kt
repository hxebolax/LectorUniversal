package com.xebolax.lector.formatos

import android.util.Xml
import com.xebolax.lector.modelo.ConstructorDocumento
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.Enlace
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.Textos
import com.xebolax.lector.modelo.TipoBloque
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.MathContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor

/** Ayudas comunes a presentaciones y hojas de cálculo (OOXML y OpenDocument). */
private object AyudaPresentacion {

	class Relacion(val tipo: String, val destino: String, val externa: Boolean)

	fun parser(entrada: InputStream): XmlPullParser = Xml.newPullParser().apply {
		setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
		setInput(entrada, null)
	}

	/** Nombre sin prefijo ("p:sp" → "sp"). */
	fun local(nombre: String?): String = nombre?.substringAfter(':').orEmpty()

	fun atributo(p: XmlPullParser, local: String): String? {
		for (i in 0 until p.attributeCount) {
			if (p.getAttributeName(i).substringAfter(':') == local) return p.getAttributeValue(i)
		}
		return null
	}

	/** Nombre local en minúsculas de un elemento de Jsoup. */
	fun nombre(e: Element): String = e.normalName().substringAfter(':')

	/** Atributo r:id (relación) de un elemento. */
	fun idRel(e: Element): String? = e.attributes().firstOrNull { it.key.endsWith(":id") }?.value?.takeIf { it.isNotBlank() }

	fun xml(z: Contenedor, ruta: String): Element? = z.texto(ruta)?.let { Jsoup.parse(it, "", Parser.xmlParser()) }

	fun esZip(cabecera: ByteArray) = cabecera.size >= 2 && cabecera[0] == 'P'.code.toByte() && cabecera[1] == 'K'.code.toByte()

	fun relaciones(z: Contenedor, base: String): Map<String, Relacion> {
		val carpeta = base.substringBeforeLast('/', "")
		val ruta = (if (carpeta.isEmpty()) "" else "$carpeta/") + "_rels/" + base.substringAfterLast('/') + ".rels"
		val mapa = HashMap<String, Relacion>()
		val entrada = z.abrir(ruta) ?: return mapa
		runCatching {
			entrada.use {
				val p = parser(it)
				while (true) {
					val evento = p.next()
					if (evento == XmlPullParser.END_DOCUMENT) break
					if (evento != XmlPullParser.START_TAG || local(p.name) != "Relationship") continue
					val id = atributo(p, "Id") ?: continue
					val destino = atributo(p, "Target").orEmpty()
					val externa = atributo(p, "TargetMode").equals("External", ignoreCase = true)
					mapa[id] = Relacion(atributo(p, "Type").orEmpty(), if (externa) destino else Contenedor.resolver(base, destino), externa)
				}
			}
		}
		return mapa
	}

	fun metadatosCore(z: Contenedor, c: ConstructorDocumento) {
		val entrada = z.abrir("docProps/core.xml") ?: return
		runCatching {
			entrada.use {
				val p = parser(it)
				val texto = StringBuilder()
				while (true) {
					when (p.next()) {
						XmlPullParser.END_DOCUMENT -> break
						XmlPullParser.START_TAG -> texto.setLength(0)
						XmlPullParser.TEXT -> texto.append(p.text)
						XmlPullParser.END_TAG -> {
							val valor = Textos.unirEspacios(texto.toString()).trim()
							texto.setLength(0)
							if (valor.isEmpty()) continue
							when (local(p.name)) {
								"title" -> c.titulo = valor
								"creator" -> { c.autor = valor; c.metadatos["Autor"] = valor }
								"language" -> { c.idioma = valor; c.metadatos["Idioma"] = valor }
								"description" -> c.metadatos["Descripción"] = valor
								"subject" -> c.metadatos["Tema"] = valor
								"keywords" -> c.metadatos["Palabras clave"] = valor
								"created" -> c.metadatos["Fecha"] = valor.take(10)
							}
						}
					}
				}
			}
		}
	}

	/** Metadatos de meta.xml (o de la parte office:meta de un documento plano). */
	fun metadatosOdf(xml: String, c: ConstructorDocumento) {
		val inicio = xml.indexOf("<office:meta")
		if (inicio < 0) return
		val fin = xml.indexOf("</office:meta>", inicio).let { if (it < 0) minOf(xml.length, inicio + 65536) else it }
		val meta = xml.substring(inicio, fin)
		fun valor(etiqueta: String): String =
			Regex("<$etiqueta(?:\\s[^>]*)?>([^<]*)</$etiqueta>").find(meta)?.groupValues?.get(1)
				?.let { Textos.unirEspacios(Parser.unescapeEntities(it, false)).trim() }.orEmpty()
		val titulo = valor("dc:title")
		if (titulo.isNotEmpty()) c.titulo = titulo
		val autor = valor("meta:initial-creator").ifEmpty { valor("dc:creator") }
		if (autor.isNotEmpty()) {
			c.autor = autor
			c.metadatos["Autor"] = autor
		}
		val idioma = valor("dc:language")
		if (idioma.isNotEmpty()) {
			c.idioma = idioma
			c.metadatos["Idioma"] = idioma
		}
		c.metadatos["Descripción"] = valor("dc:description")
		c.metadatos["Tema"] = valor("dc:subject")
		c.metadatos["Fecha"] = valor("meta:creation-date").ifEmpty { valor("dc:date") }.take(10)
	}

	/** Filas de una tabla → bloques (resumen, encabezado y "Columna: valor; ..."). */
	fun emitirTabla(c: ConstructorDocumento, filas: List<List<String>>, encabezado: Boolean, opciones: OpcionesLectura) {
		val utiles = filas.map { f -> f.map { Textos.unirEspacios(it.replace('\n', ' ')).trim() } }
			.filter { f -> f.any { it.isNotEmpty() } }
		if (utiles.isEmpty()) return
		val columnas = utiles.maxOf { it.size }
		if (columnas <= 1) {
			for (f in utiles) c.parrafo(f.firstOrNull().orEmpty())
			return
		}
		val primero = c.tamano
		c.bloque(TipoBloque.PARRAFO, "Tabla de ${utiles.size} filas y $columnas columnas")
		escribirFilas(c, utiles, encabezado && utiles.size >= 2, opciones)
		if (c.tamano > primero) c.anadirMarca(primero, Marca.INICIO_TABLA)
	}

	/** Escribe las filas: la de encabezado con " | " y el resto con pares o barras. */
	fun escribirFilas(c: ConstructorDocumento, filas: List<List<String>>, conEncabezado: Boolean, opciones: OpcionesLectura) {
		val cabeceras = if (conEncabezado) filas[0] else emptyList()
		for ((i, fila) in filas.withIndex()) {
			val linea = when {
				i == 0 && conEncabezado -> fila.filter { it.isNotBlank() }.joinToString(" | ")
				opciones.tablasConEncabezados && cabeceras.isNotEmpty() -> fila.mapIndexed { j, valor ->
					val cabecera = cabeceras.getOrNull(j).orEmpty()
					if (cabecera.isBlank() || valor.isBlank()) valor else "$cabecera: $valor"
				}.filter { it.isNotBlank() }.joinToString("; ")
				else -> fila.joinToString(" | ")
			}
			c.bloque(TipoBloque.FILA_TABLA, linea.ifBlank { "(fila vacía)" }, marcas = if (i == 0 && conEncabezado) Marca.ENCABEZADO_TABLA else 0)
		}
	}

	private val ESPACIOS = Regex("[ \t\r\n]+")

	/** Texto en línea de OpenDocument (text:span, text:s, text:a...). */
	fun lineaOdf(nodo: Node, sb: StringBuilder, enlaces: MutableList<Enlace>) {
		if (nodo is TextNode) {
			val s = ESPACIOS.replace(nodo.wholeText, " ")
			if (s.startsWith(" ") && (sb.isEmpty() || sb.last().isWhitespace())) sb.append(s.trimStart()) else sb.append(s)
			return
		}
		if (nodo !is Element) return
		when (nodo.normalName()) {
			"text:s" -> sb.append(" ".repeat((nodo.attr("text:c").toIntOrNull() ?: 1).coerceIn(1, 20)))
			"text:tab" -> if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
			"text:line-break" -> sb.append('\n')
			"text:a" -> {
				val inicio = sb.length
				for (n in nodo.childNodes()) lineaOdf(n, sb, enlaces)
				val href = nodo.attr("xlink:href").trim()
				if (sb.length > inicio && (href.contains("://") || href.startsWith("mailto:") || href.startsWith("#"))) {
					enlaces += Enlace(inicio, sb.length, href)
				}
			}
			"office:annotation", "office:annotation-end", "text:note", "svg:title", "svg:desc", "text:soft-page-break",
			"text:bookmark", "text:bookmark-start", "text:bookmark-end" -> Unit
			else -> for (n in nodo.childNodes()) lineaOdf(n, sb, enlaces)
		}
	}

	/** Texto plano de todos los párrafos de un elemento de OpenDocument. */
	fun textoOdf(e: Element): String {
		val partes = ArrayList<String>()
		for (p in e.getAllElements()) {
			val n = p.normalName()
			if (n != "text:p" && n != "text:h") continue
			val sb = StringBuilder()
			for (hijo in p.childNodes()) lineaOdf(hijo, sb, ArrayList())
			val t = Textos.unirEspacios(sb.toString().replace('\n', ' ')).trim()
			if (t.isNotEmpty()) partes += t
		}
		return partes.joinToString(" ")
	}
}

/**
 * Presentaciones: PowerPoint (PPTX, PPTM) y OpenDocument (ODP, FODP).
 *
 * Cada diapositiva empieza con el encabezado "Diapositiva N: título"; luego
 * el texto de cada forma (listas con su nivel), las tablas, las imágenes con
 * descripción y, al final, las notas del orador.
 */
object LectorPresentacion : LectorFormato {

	private val DIAPOSITIVA = Regex("""ppt/slides/slide(\d+)\.xml""")
	private val NOMBRE_GENERICO = Regex("""(?i)^(page|slide|diapositiva|página|pagina|folie|diapo)\s*\d+$""")
	private val IGNORAR_PH = setOf("sldnum", "dt", "ftr", "hdr")

	/** Añade bloques recordando el último tipo (para marcar el inicio de las listas). */
	private class Emisor(val c: ConstructorDocumento) {
		var ultimo: TipoBloque? = null

		fun bloque(tipo: TipoBloque, texto: String, nivel: Int = 0, enlaces: List<Enlace> = emptyList(), imagen: String? = null) {
			val marcas = if (tipo == TipoBloque.ELEMENTO_LISTA && ultimo != TipoBloque.ELEMENTO_LISTA) Marca.INICIO_LISTA else 0
			val n = c.bloque(tipo, texto, nivel, enlaces, marcas)
			if (n >= 0) {
				ultimo = tipo
				imagen?.let { c.imagen(n, it) }
			}
		}
	}

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val c = ctx.nuevoConstructor()
		when (fuente.extension) {
			"odp", "fodp", "otp" -> leerOdp(fuente, ctx, c)
			else -> leerPptx(fuente, ctx, c)
		}
		if (c.titulo.isBlank()) c.titulo = fuente.nombre.substringBeforeLast('.')
		return Contenido.Texto(c.construir())
	}

	// --- PowerPoint ------------------------------------------------------------------

	private fun leerPptx(fuente: Fuente, ctx: ContextoLectura, c: ConstructorDocumento) {
		val cabecera = runCatching { fuente.cabecera(8) }.getOrDefault(ByteArray(0))
		if (cabecera.size >= 4 && cabecera[0] == 0xD0.toByte() && cabecera[1] == 0xCF.toByte()) {
			throw ExcepcionFormato("La presentación está cifrada con contraseña; ábrela en PowerPoint y guárdala sin contraseña.")
		}
		if (!AyudaPresentacion.esZip(cabecera)) {
			// Puede ser un ODP con otra extensión
			throw ExcepcionFormato("La presentación está dañada o no es un archivo PowerPoint válido.")
		}
		fuente.contenedor().use { z ->
			if (z.buscar("content.xml") != null && z.buscar("ppt/presentation.xml") == null) {
				leerOdpZip(z, ctx, c)
				return
			}
			val raiz = AyudaPresentacion.relaciones(z, "")
			val ruta = raiz.values.firstOrNull { it.tipo.endsWith("/officeDocument") }?.let { z.buscar(it.destino) }
				?: z.buscar("ppt/presentation.xml")
				?: throw ExcepcionFormato("La presentación está dañada (falta ppt/presentation.xml).")
			AyudaPresentacion.metadatosCore(z, c)
			val rels = AyudaPresentacion.relaciones(z, ruta)
			val presentacion = AyudaPresentacion.xml(z, ruta)
			var diapositivas = presentacion?.getAllElements()
				?.filter { AyudaPresentacion.nombre(it) == "sldid" }
				?.mapNotNull { AyudaPresentacion.idRel(it)?.let { id -> rels[id]?.destino } }
				?.mapNotNull { z.buscar(it) }
				.orEmpty()
			if (diapositivas.isEmpty()) {
				diapositivas = z.entradas().filter { DIAPOSITIVA.matches(it.lowercase()) }
					.sortedBy { DIAPOSITIVA.find(it.lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
			}
			if (diapositivas.isEmpty()) throw ExcepcionFormato("La presentación no tiene diapositivas.")
			for ((i, rutaDiapositiva) in diapositivas.withIndex()) {
				ctx.comprobar()
				ctx.progreso(i * 100 / diapositivas.size)
				diapositiva(z, rutaDiapositiva, i + 1, c, ctx)
			}
		}
	}

	private fun tipoPh(forma: Element): String? {
		val nv = forma.children().firstOrNull { AyudaPresentacion.nombre(it).startsWith("nv") } ?: return null
		val ph = nv.getAllElements().firstOrNull { AyudaPresentacion.nombre(it) == "ph" } ?: return null
		return ph.attr("type").lowercase().ifEmpty { "obj" }
	}

	/** Formas de un árbol (los grupos se recorren y de las alternativas se usa la primera). */
	private fun recogerFormas(e: Element, lista: MutableList<Element>) {
		for (h in e.children()) {
			when (AyudaPresentacion.nombre(h)) {
				"sp", "pic", "graphicframe" -> lista += h
				"grpsp" -> recogerFormas(h, lista)
				"alternatecontent" -> h.children().firstOrNull { AyudaPresentacion.nombre(it) == "choice" }?.let { recogerFormas(it, lista) }
			}
		}
	}

	private fun textoForma(e: Element): String = e.getAllElements()
		.filter { AyudaPresentacion.nombre(it) == "p" }
		.joinToString(" ") { p ->
			p.getAllElements().filter { AyudaPresentacion.nombre(it) == "t" }.joinToString("") { it.wholeText() }
		}
		.let { Textos.unirEspacios(it).trim() }

	private fun diapositiva(z: Contenedor, ruta: String, numero: Int, c: ConstructorDocumento, ctx: ContextoLectura) {
		val doc = AyudaPresentacion.xml(z, ruta) ?: return
		val enlaces = HashMap<String, String>()
		var rutaNotas: String? = null
		for ((id, r) in AyudaPresentacion.relaciones(z, ruta)) {
			if (r.tipo.endsWith("/notesSlide")) rutaNotas = z.buscar(r.destino)
			if (!r.destino.startsWith("ppaction:")) enlaces[id] = r.destino
		}
		val arbol = doc.getAllElements().firstOrNull { AyudaPresentacion.nombre(it) == "sptree" }
		val formas = ArrayList<Element>()
		if (arbol != null) recogerFormas(arbol, formas)
		val formaTitulo = formas.firstOrNull { tipoPh(it) == "title" || tipoPh(it) == "ctrtitle" }
		val titulo = formaTitulo?.let { textoForma(it) }.orEmpty()
		val oculta = doc.children().firstOrNull()?.attr("show") == "0"

		c.ancla(ruta)
		c.marcarSiguiente(Marca.INICIO_SECCION)
		val encabezado = "Diapositiva $numero" + (if (titulo.isNotEmpty()) ": $titulo" else "") + (if (oculta) " (oculta)" else "")
		c.encabezado(encabezado, 1)
		val em = Emisor(c)
		em.ultimo = TipoBloque.ENCABEZADO

		for (forma in formas) {
			if (forma === formaTitulo) continue
			when (AyudaPresentacion.nombre(forma)) {
				"sp" -> {
					val ph = tipoPh(forma)
					if (ph in IGNORAR_PH) continue
					val cuerpo = forma.children().firstOrNull { AyudaPresentacion.nombre(it) == "txbody" } ?: continue
					parrafosForma(cuerpo, ph == "body" || ph == "obj", enlaces, em)
				}
				"graphicframe" -> {
					val tabla = forma.getAllElements().firstOrNull { AyudaPresentacion.nombre(it) == "tbl" }
					if (tabla != null) {
						tablaPptx(tabla, c, ctx)
						em.ultimo = TipoBloque.FILA_TABLA
					} else {
						imagen(forma, em, ctx, enlaces)
					}
				}
				"pic" -> imagen(forma, em, ctx, enlaces)
			}
		}

		// Notas del orador
		val notas = rutaNotas?.let { AyudaPresentacion.xml(z, it) } ?: return
		val arbolNotas = notas.getAllElements().firstOrNull { AyudaPresentacion.nombre(it) == "sptree" } ?: return
		val formasNotas = ArrayList<Element>()
		recogerFormas(arbolNotas, formasNotas)
		val parrafos = formasNotas.filter { tipoPh(it) == "body" }.flatMap { f ->
			f.getAllElements().filter { AyudaPresentacion.nombre(it) == "p" }.map { p ->
				Textos.unirEspacios(p.getAllElements().filter { AyudaPresentacion.nombre(it) == "t" }.joinToString("") { it.wholeText() }).trim()
			}
		}.filter { it.isNotEmpty() }
		if (parrafos.isNotEmpty()) {
			c.encabezado("Notas del orador", 2)
			for (p in parrafos) c.parrafo(p)
		}
	}

	private fun parrafosForma(cuerpo: Element, esCuerpo: Boolean, enlaces: Map<String, String>, em: Emisor) {
		val contadores = IntArray(10)
		for (p in cuerpo.children()) {
			if (AyudaPresentacion.nombre(p) != "p") continue
			val sb = StringBuilder()
			val propios = ArrayList<Enlace>()
			var nivel = 0
			var sinVineta = false
			var vineta = false
			var automatica = false
			var inicioAutomatico = 1
			for (h in p.children()) {
				when (AyudaPresentacion.nombre(h)) {
					"ppr" -> {
						nivel = (h.attr("lvl").toIntOrNull() ?: 0).coerceIn(0, 8)
						for (b in h.children()) {
							when (AyudaPresentacion.nombre(b)) {
								"bunone" -> sinVineta = true
								"buchar", "bublip" -> vineta = true
								"buautonum" -> {
									automatica = true
									inicioAutomatico = b.attr("startAt").toIntOrNull() ?: 1
								}
							}
						}
					}
					"r", "fld" -> {
						val t = h.children().firstOrNull { AyudaPresentacion.nombre(it) == "t" }?.wholeText().orEmpty()
						val inicio = sb.length
						sb.append(t)
						val id = h.children().firstOrNull { AyudaPresentacion.nombre(it) == "rpr" }
							?.children()?.firstOrNull { AyudaPresentacion.nombre(it) == "hlinkclick" }
							?.let { AyudaPresentacion.idRel(it) }
						val destino = id?.let { enlaces[it] }
						if (destino != null && sb.length > inicio) propios += Enlace(inicio, sb.length, destino)
					}
					"br" -> sb.append('\n')
				}
			}
			if (sb.isBlank()) continue
			val lista = !sinVineta && (esCuerpo || vineta || automatica)
			var prefijo = ""
			if (lista && automatica) {
				contadores[nivel] = if (contadores[nivel] == 0) inicioAutomatico else contadores[nivel] + 1
				for (k in nivel + 1 until contadores.size) contadores[k] = 0
				prefijo = "${contadores[nivel]}. "
			}
			val desplazados = if (prefijo.isEmpty()) propios else propios.map { Enlace(it.inicio + prefijo.length, it.fin + prefijo.length, it.destino) }
			if (lista) em.bloque(TipoBloque.ELEMENTO_LISTA, prefijo + sb, nivel + 1, desplazados)
			else em.bloque(TipoBloque.PARRAFO, sb.toString(), 0, desplazados)
		}
	}

	/** relaciones: id → ruta de la diapositiva (las imágenes están entre ellas). */
	private fun imagen(forma: Element, em: Emisor, ctx: ContextoLectura, relaciones: Map<String, String>) {
		if (!ctx.opciones.leerImagenes) return
		val cnv = forma.getAllElements().firstOrNull { AyudaPresentacion.nombre(it) == "cnvpr" } ?: return
		val descripcion = cnv.attr("descr").ifBlank { cnv.attr("title") }.replace('\n', ' ').trim()
		val ruta = forma.getAllElements().firstOrNull { AyudaPresentacion.nombre(it) == "blip" }
			?.let { AyudaPresentacion.idRel(it) }?.let { relaciones[it] }
			?.takeIf { !it.contains("://") }
		when {
			descripcion.isNotEmpty() -> em.bloque(TipoBloque.IMAGEN, "Imagen: $descripcion", imagen = ruta)
			ruta != null -> em.bloque(TipoBloque.IMAGEN, SIN_DESCRIPCION, imagen = ruta)
		}
	}

	private fun tablaPptx(tabla: Element, c: ConstructorDocumento, ctx: ContextoLectura) {
		val filas = tabla.children().filter { AyudaPresentacion.nombre(it) == "tr" }.map { tr ->
			tr.children().filter { AyudaPresentacion.nombre(it) == "tc" }.map { tc ->
				if (tc.attr("hMerge") == "1" || tc.attr("vMerge") == "1") "" else textoForma(tc)
			}
		}
		val primeraFila = tabla.children().firstOrNull { AyudaPresentacion.nombre(it) == "tblpr" }?.attr("firstRow") == "1"
		AyudaPresentacion.emitirTabla(c, filas, primeraFila || (filas.size >= 2 && ctx.opciones.tablasConEncabezados), ctx.opciones)
	}

	// --- OpenDocument -------------------------------------------------------------------

	private fun leerOdp(fuente: Fuente, ctx: ContextoLectura, c: ConstructorDocumento) {
		val cabecera = runCatching { fuente.cabecera(8) }.getOrDefault(ByteArray(0))
		if (fuente.extension == "fodp" || !AyudaPresentacion.esZip(cabecera)) {
			val texto = Textos.decodificar(fuente.bytes())
			AyudaPresentacion.metadatosOdf(texto, c)
			paginasOdp(Jsoup.parse(texto, "", Parser.xmlParser()), ctx, c)
		} else {
			fuente.contenedor().use { z -> leerOdpZip(z, ctx, c) }
		}
	}

	private fun leerOdpZip(z: Contenedor, ctx: ContextoLectura, c: ConstructorDocumento) {
		if (z.texto("META-INF/manifest.xml").orEmpty().contains("encryption-data")) {
			throw ExcepcionFormato("La presentación está cifrada con contraseña; ábrela y guárdala sin contraseña.")
		}
		z.texto("meta.xml")?.let { AyudaPresentacion.metadatosOdf(it, c) }
		val contenido = z.texto("content.xml") ?: throw ExcepcionFormato("La presentación está dañada (falta content.xml).")
		paginasOdp(Jsoup.parse(contenido, "", Parser.xmlParser()), ctx, c)
	}

	private fun paginasOdp(doc: Element, ctx: ContextoLectura, c: ConstructorDocumento) {
		val presentacion = doc.getElementsByTag("office:presentation").firstOrNull()
			?: throw ExcepcionFormato("No es una presentación OpenDocument válida.")
		val paginas = presentacion.children().filter { it.normalName() == "draw:page" }
		if (paginas.isEmpty()) throw ExcepcionFormato("La presentación no tiene diapositivas.")
		for ((i, pagina) in paginas.withIndex()) {
			ctx.comprobar()
			ctx.progreso(i * 100 / paginas.size)
			val nombre = pagina.attr("draw:name").trim()
			// Sin marco de título declarado, el primer cuadro con el mismo texto que el nombre de la diapositiva lo es
			val formaTitulo = pagina.children().firstOrNull { it.attr("presentation:class") == "title" }
				?: pagina.children().firstOrNull { it.normalName() == "draw:frame" }?.takeIf {
					nombre.isNotEmpty() && AyudaPresentacion.textoOdf(it).trim() == nombre
				}
			val titulo = formaTitulo?.let { AyudaPresentacion.textoOdf(it) }.orEmpty()
				.ifEmpty { nombre.takeUnless { NOMBRE_GENERICO.matches(it) }.orEmpty() }
			if (nombre.isNotEmpty()) c.ancla("#$nombre")
			c.marcarSiguiente(Marca.INICIO_SECCION)
			c.encabezado("Diapositiva ${i + 1}" + (if (titulo.isNotEmpty()) ": $titulo" else ""), 1)
			val em = Emisor(c)
			em.ultimo = TipoBloque.ENCABEZADO
			for (h in pagina.children()) {
				if (h === formaTitulo) continue
				elementoOdp(h, em, ctx)
			}
			val notas = pagina.children().firstOrNull { it.normalName() == "presentation:notes" } ?: continue
			val textos = notas.children()
				.filter { it.normalName() == "draw:frame" && it.attr("presentation:class") != "page-thumbnail" }
				.flatMap { marco ->
					marco.getAllElements().filter { it.normalName() == "text:p" || it.normalName() == "text:h" }.map { p ->
						val sb = StringBuilder()
						for (n in p.childNodes()) AyudaPresentacion.lineaOdf(n, sb, ArrayList())
						Textos.unirEspacios(sb.toString()).trim()
					}
				}.filter { it.isNotEmpty() }
			if (textos.isNotEmpty()) {
				c.encabezado("Notas del orador", 2)
				for (t in textos) c.parrafo(t)
			}
		}
	}

	private fun elementoOdp(e: Element, em: Emisor, ctx: ContextoLectura) {
		when (e.normalName()) {
			"presentation:notes", "office:forms", "svg:title", "svg:desc", "anim:par", "anim:seq", "office:annotation" -> Unit
			"draw:g" -> for (h in e.children()) elementoOdp(h, em, ctx)
			"draw:frame" -> {
				if (e.attr("presentation:class") in setOf("page-number", "date-time", "footer", "header")) return
				var esImagen = false
				for (h in e.children()) {
					when (h.normalName()) {
						"draw:text-box" -> bloquesOdf(h, 0, em, ctx)
						"table:table" -> tablaOdf(h, em, ctx)
						"draw:image", "draw:object", "draw:object-ole", "draw:plugin" -> esImagen = true
					}
				}
				if (esImagen && ctx.opciones.leerImagenes) {
					val descripcion = e.children().firstOrNull { it.normalName() == "svg:title" }?.text()?.trim().orEmpty()
						.ifEmpty { e.children().firstOrNull { it.normalName() == "svg:desc" }?.text()?.trim().orEmpty() }
					val ruta = e.children().firstOrNull { it.normalName() == "draw:image" }?.attr("xlink:href")?.trim()
						?.takeIf { it.isNotEmpty() && !it.contains("://") && !it.startsWith("#") }?.let { Contenedor.resolver("", it) }
					when {
						descripcion.isNotEmpty() -> em.bloque(TipoBloque.IMAGEN, "Imagen: $descripcion", imagen = ruta)
						ruta != null -> em.bloque(TipoBloque.IMAGEN, SIN_DESCRIPCION, imagen = ruta)
					}
				}
			}
			else -> bloquesOdf(e, 0, em, ctx)
		}
	}

	private fun bloquesOdf(e: Element, nivel: Int, em: Emisor, ctx: ContextoLectura) {
		for (h in e.children()) {
			when (h.normalName()) {
				"text:p", "text:h" -> {
					val sb = StringBuilder()
					val enlaces = ArrayList<Enlace>()
					for (n in h.childNodes()) AyudaPresentacion.lineaOdf(n, sb, enlaces)
					if (nivel > 0) em.bloque(TipoBloque.ELEMENTO_LISTA, sb.toString(), nivel.coerceAtMost(9), enlaces)
					else em.bloque(TipoBloque.PARRAFO, sb.toString(), 0, enlaces)
				}
				"text:list" -> for (item in h.children()) {
					if (item.normalName() == "text:list-item" || item.normalName() == "text:list-header") bloquesOdf(item, nivel + 1, em, ctx)
				}
				"table:table" -> tablaOdf(h, em, ctx)
				"draw:frame", "draw:g" -> elementoOdp(h, em, ctx)
				"svg:title", "svg:desc", "office:annotation", "text:soft-page-break" -> Unit
				else -> bloquesOdf(h, nivel, em, ctx)
			}
		}
	}

	private fun tablaOdf(t: Element, em: Emisor, ctx: ContextoLectura) {
		val filas = ArrayList<List<String>>()
		var encabezado = false
		for (fila in t.getElementsByTag("table:table-row")) {
			if (fila.parents().firstOrNull { it.normalName() == "table:table" } !== t) continue
			if (filas.isEmpty() && fila.parents().any { it.normalName() == "table:table-header-rows" }) encabezado = true
			val celdas = ArrayList<String>()
			for (celda in fila.children()) {
				val n = celda.normalName()
				if (n != "table:table-cell" && n != "table:covered-table-cell") continue
				val texto = if (n == "table:covered-table-cell") "" else AyudaPresentacion.textoOdf(celda)
				repeat((celda.attr("table:number-columns-repeated").toIntOrNull() ?: 1).coerceIn(1, 100)) { celdas += texto }
			}
			while (celdas.isNotEmpty() && celdas.last().isBlank()) celdas.removeAt(celdas.size - 1)
			filas.add(celdas)
		}
		AyudaPresentacion.emitirTabla(em.c, filas, encabezado || (filas.size >= 2 && ctx.opciones.tablasConEncabezados), ctx.opciones)
		em.ultimo = TipoBloque.FILA_TABLA
	}
}

/**
 * Hojas de cálculo: Excel (XLSX, XLSM) y OpenDocument (ODS, FODS).
 *
 * Cada hoja es un encabezado de nivel 1 con su nombre y una fila por bloque.
 * Si la primera fila parece de encabezados (todo texto), las demás se leen
 * como "Columna: valor; ..."; si no, "valor | valor". Las filas vacías se
 * saltan y cada hoja se limita a 20 000 filas.
 */
object LectorHoja : LectorFormato {

	private const val LIMITE_FILAS = 20000
	private const val LIMITE_COLUMNAS = 1000

	private class Hoja(val nombre: String) {
		val filas = ArrayList<List<String>>()
		var primeraEsTexto = false
		var encabezadoDeclarado = false
		var truncada = false
	}

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val c = ctx.nuevoConstructor()
		val cabecera = runCatching { fuente.cabecera(8) }.getOrDefault(ByteArray(0))
		if (cabecera.size >= 4 && cabecera[0] == 0xD0.toByte() && cabecera[1] == 0xCF.toByte()) {
			throw ExcepcionFormato("La hoja de cálculo está cifrada con contraseña o es un .xls antiguo; ábrela y guárdala como .xlsx sin contraseña.")
		}
		val plana = fuente.extension == "fods" || !AyudaPresentacion.esZip(cabecera)
		if (plana) {
			val bytes = fuente.bytes()
			AyudaPresentacion.metadatosOdf(String(bytes, 0, minOf(bytes.size, 262144), Charsets.UTF_8), c)
			leerOds(ByteArrayInputStream(bytes), c, ctx)
		} else {
			fuente.contenedor().use { z ->
				if (z.buscar("content.xml") != null && z.buscar("xl/workbook.xml") == null) {
					if (z.texto("META-INF/manifest.xml").orEmpty().contains("encryption-data")) {
						throw ExcepcionFormato("La hoja de cálculo está cifrada con contraseña; ábrela y guárdala sin contraseña.")
					}
					z.texto("meta.xml")?.let { AyudaPresentacion.metadatosOdf(it, c) }
					val entrada = z.abrir("content.xml") ?: throw ExcepcionFormato("La hoja de cálculo está dañada (falta content.xml).")
					entrada.use { leerOds(it, c, ctx) }
				} else {
					leerXlsx(z, c, ctx)
				}
			}
		}
		if (c.estaVacio()) c.parrafo("La hoja de cálculo está vacía.")
		if (c.titulo.isBlank()) c.titulo = fuente.nombre.substringBeforeLast('.')
		return Contenido.Texto(c.construir())
	}

	/** Escribe una hoja: encabezado con su nombre y las filas. */
	private fun emitirHoja(c: ConstructorDocumento, hoja: Hoja, numero: Int, opciones: OpcionesLectura) {
		c.marcarSiguiente(Marca.INICIO_SECCION)
		c.encabezado(hoja.nombre.ifBlank { "Hoja $numero" }, 1)
		if (hoja.filas.isEmpty()) {
			c.parrafo("(Hoja vacía)")
			return
		}
		// Sin las columnas vacías del principio ni del final
		val desde = hoja.filas.minOf { f -> f.indexOfFirst { it.isNotBlank() }.let { if (it < 0) Int.MAX_VALUE else it } }
			.let { if (it == Int.MAX_VALUE) 0 else it }
		val filas = hoja.filas.map { f ->
			val recortada = if (desde > 0) f.drop(desde) else f
			recortada.dropLastWhile { it.isBlank() }.map { Textos.unirEspacios(it.replace('\n', ' ')).trim() }
		}
		val conEncabezado = filas.size >= 2 && (hoja.encabezadoDeclarado || hoja.primeraEsTexto) && filas[0].count { it.isNotBlank() } >= 2
		val primero = c.tamano
		AyudaPresentacion.escribirFilas(c, filas, conEncabezado, opciones)
		if (c.tamano > primero) c.anadirMarca(primero, Marca.INICIO_TABLA)
		if (hoja.truncada) {
			val aviso = "La hoja «${hoja.nombre}» tiene más de $LIMITE_FILAS filas: solo se muestran las primeras $LIMITE_FILAS."
			c.parrafo(aviso)
			c.avisos += aviso
		}
	}

	// --- Excel ---------------------------------------------------------------------------

	private fun leerXlsx(z: Contenedor, c: ConstructorDocumento, ctx: ContextoLectura) {
		val raiz = AyudaPresentacion.relaciones(z, "")
		val libro = raiz.values.firstOrNull { it.tipo.endsWith("/officeDocument") }?.let { z.buscar(it.destino) }
			?: z.buscar("xl/workbook.xml")
			?: throw ExcepcionFormato("La hoja de cálculo está dañada (falta xl/workbook.xml).")
		AyudaPresentacion.metadatosCore(z, c)
		val rels = AyudaPresentacion.relaciones(z, libro)
		fun rutaDe(tipo: String, defecto: String): String? =
			rels.values.firstOrNull { it.tipo.endsWith("/$tipo") }?.let { z.buscar(it.destino) } ?: z.buscar(defecto)

		// Hojas del libro (nombre, id de relación, oculta) y sistema de fechas
		val hojas = ArrayList<Triple<String, String, Boolean>>()
		var en1904 = false
		z.abrir(libro)?.use {
			val p = AyudaPresentacion.parser(it)
			while (true) {
				val evento = p.next()
				if (evento == XmlPullParser.END_DOCUMENT) break
				if (evento != XmlPullParser.START_TAG) continue
				when (AyudaPresentacion.local(p.name)) {
					"sheet" -> {
						val nombre = AyudaPresentacion.atributo(p, "name").orEmpty()
						val id = AyudaPresentacion.atributo(p, "id").orEmpty()
						val estado = AyudaPresentacion.atributo(p, "state")
						hojas += Triple(nombre, id, estado == "hidden" || estado == "veryHidden")
					}
					"workbookPr" -> en1904 = AyudaPresentacion.atributo(p, "date1904") in setOf("1", "true")
				}
			}
		}
		val compartidas = rutaDe("sharedStrings", "xl/sharedStrings.xml")?.let { leerCompartidas(z, it) }.orEmpty()
		val fechas = rutaDe("styles", "xl/styles.xml")?.let { leerEstilosFecha(z, it) }.orEmpty()
		ctx.progreso(10)

		var numero = 0
		for ((i, datos) in hojas.withIndex()) {
			ctx.comprobar()
			val (nombre, id, oculta) = datos
			val relacion = rels[id] ?: continue
			if (relacion.tipo.contains("chartsheet") || relacion.tipo.contains("dialogsheet")) continue
			val ruta = z.buscar(relacion.destino) ?: continue
			val hoja = Hoja(if (oculta) "$nombre (oculta)" else nombre)
			z.abrir(ruta)?.use { leerHojaXlsx(it, hoja, compartidas, fechas, en1904, ctx) }
			emitirHoja(c, hoja, ++numero, ctx.opciones)
			ctx.progreso(10 + (i + 1) * 90 / hojas.size)
		}
	}

	private fun leerCompartidas(z: Contenedor, ruta: String): List<String> {
		val lista = ArrayList<String>()
		z.abrir(ruta)?.use {
			val p = AyudaPresentacion.parser(it)
			val sb = StringBuilder()
			var enT = false
			var fonetica = 0
			while (true) {
				when (p.next()) {
					XmlPullParser.END_DOCUMENT -> break
					XmlPullParser.START_TAG -> when (AyudaPresentacion.local(p.name)) {
						"si" -> sb.setLength(0)
						"t" -> if (fonetica == 0) enT = true
						"rPh" -> fonetica++
					}
					XmlPullParser.TEXT -> if (enT) sb.append(p.text)
					XmlPullParser.END_TAG -> when (AyudaPresentacion.local(p.name)) {
						"t" -> enT = false
						"rPh" -> fonetica--
						"si" -> lista += sb.toString()
					}
				}
			}
		}
		return lista
	}

	/** Índices de estilo de celda (cellXfs) cuyo formato es de fecha u hora. */
	private fun leerEstilosFecha(z: Contenedor, ruta: String): Set<Int> {
		val propios = HashMap<Int, String>()
		val formatos = ArrayList<Int>()
		z.abrir(ruta)?.use {
			val p = AyudaPresentacion.parser(it)
			var enXfs = false
			while (true) {
				when (p.next()) {
					XmlPullParser.END_DOCUMENT -> break
					XmlPullParser.START_TAG -> when (AyudaPresentacion.local(p.name)) {
						"numFmt" -> {
							val id = AyudaPresentacion.atributo(p, "numFmtId")?.toIntOrNull()
							if (id != null) propios[id] = AyudaPresentacion.atributo(p, "formatCode").orEmpty()
						}
						"cellXfs" -> enXfs = true
						"xf" -> if (enXfs) formatos += AyudaPresentacion.atributo(p, "numFmtId")?.toIntOrNull() ?: 0
					}
					XmlPullParser.END_TAG -> if (AyudaPresentacion.local(p.name) == "cellXfs") enXfs = false
				}
			}
		}
		return formatos.indices.filter { esFecha(formatos[it], propios) }.toSet()
	}

	/** Partes de un código de formato que no son de fecha: "texto", [color], \x, _x, *x. */
	private val LITERALES_FORMATO = Regex("\"[^\"]*\"|\\[[^\\]]*]|\\\\.|_.|\\*.")

	private fun esFecha(id: Int, propios: Map<Int, String>): Boolean {
		if (id in 14..22 || id in 27..36 || id in 45..47 || id in 50..58) return true
		val codigo = propios[id] ?: return false
		val limpio = LITERALES_FORMATO.replace(codigo, "").lowercase()
		if (limpio.contains("general")) return false
		return limpio.any { it == 'd' || it == 'm' || it == 'y' || it == 'h' || it == 's' }
	}

	private fun columna(referencia: String): Int {
		var n = 0
		var letras = 0
		for (ch in referencia) {
			if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1)
			else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1)
			else break
			letras++
		}
		return if (letras == 0) -1 else n - 1
	}

	private fun leerHojaXlsx(
		entrada: InputStream, hoja: Hoja, compartidas: List<String>, fechas: Set<Int>, en1904: Boolean, ctx: ContextoLectura,
	) {
		val p = AyudaPresentacion.parser(entrada)
		var fila: ArrayList<String>? = null
		var filaTexto = true
		var referencia = ""
		var tipo = ""
		var estilo = -1
		val valor = StringBuilder()
		var enV = false
		var enT = false
		var fonetica = 0
		var siguiente = 0
		while (true) {
			when (p.next()) {
				XmlPullParser.END_DOCUMENT -> break
				XmlPullParser.START_TAG -> when (AyudaPresentacion.local(p.name)) {
					"row" -> {
						fila = ArrayList()
						filaTexto = true
						siguiente = 0
					}
					"c" -> {
						referencia = AyudaPresentacion.atributo(p, "r").orEmpty()
						tipo = AyudaPresentacion.atributo(p, "t").orEmpty()
						estilo = AyudaPresentacion.atributo(p, "s")?.toIntOrNull() ?: -1
						valor.setLength(0)
					}
					"v" -> enV = true
					"t" -> if (fonetica == 0) enT = true
					"rPh" -> fonetica++
				}
				XmlPullParser.TEXT -> if (enV || enT) valor.append(p.text)
				XmlPullParser.END_TAG -> when (AyudaPresentacion.local(p.name)) {
					"v" -> enV = false
					"t" -> enT = false
					"rPh" -> fonetica--
					"c" -> {
						val f = fila ?: continue
						val texto = valorCelda(tipo, valor.toString(), estilo, compartidas, fechas, en1904)
						val col = columna(referencia).takeIf { it >= 0 } ?: siguiente
						siguiente = col + 1
						if (texto.isEmpty() || col >= LIMITE_COLUMNAS) continue
						while (f.size < col) f.add("")
						if (col < f.size) f[col] = texto else f.add(texto)
						if (tipo != "s" && tipo != "inlineStr" && tipo != "str") filaTexto = false
					}
					"row" -> {
						val f = fila
						fila = null
						if (f != null && f.any { it.isNotBlank() }) {
							if (hoja.filas.size >= LIMITE_FILAS) {
								hoja.truncada = true
								return
							}
							if (hoja.filas.isEmpty()) hoja.primeraEsTexto = filaTexto
							hoja.filas.add(f)
							if (hoja.filas.size % 500 == 0) ctx.comprobar()
						}
					}
				}
			}
		}
	}

	private fun valorCelda(tipo: String, crudo: String, estilo: Int, compartidas: List<String>, fechas: Set<Int>, en1904: Boolean): String =
		when (tipo) {
			"s" -> crudo.trim().toIntOrNull()?.let { compartidas.getOrNull(it) }.orEmpty()
			"inlineStr", "str", "e" -> crudo
			"b" -> if (crudo.trim() == "1" || crudo.trim().equals("true", true)) "VERDADERO" else "FALSO"
			"d" -> crudo.replace('T', ' ')
			else -> when {
				crudo.isBlank() -> ""
				estilo in fechas -> crudo.trim().toDoubleOrNull()?.let { fecha(it, en1904) } ?: formatoNumero(crudo)
				else -> formatoNumero(crudo)
			}
		}

	private fun formatoNumero(crudo: String): String {
		val d = crudo.trim().toDoubleOrNull() ?: return crudo.trim()
		if (d == floor(d) && abs(d) < 1e15) return d.toLong().toString()
		return runCatching { BigDecimal(d).round(MathContext(15)).stripTrailingZeros().toPlainString() }.getOrDefault(crudo.trim())
	}

	/** Número de serie de Excel → fecha ("dd/MM/yyyy", con hora si la tiene). */
	private fun fecha(serie: Double, en1904: Boolean): String {
		if (serie < 0 || serie > 2958465) return formatoNumero(serie.toString())
		var dias = floor(serie).toInt()
		val segundos = Math.round((serie - floor(serie)) * 86400).toInt()
		val utc = TimeZone.getTimeZone("UTC")
		val cal = GregorianCalendar(utc)
		cal.clear()
		if (en1904) {
			cal.set(1904, Calendar.JANUARY, 1)
		} else {
			// Excel cuenta el 29/02/1900, que no existió
			if (dias in 1..59) dias += 1
			cal.set(1899, Calendar.DECEMBER, 30)
		}
		cal.add(Calendar.DATE, dias)
		cal.add(Calendar.SECOND, segundos)
		val patron = when {
			dias == 0 && !en1904 -> "HH:mm"
			segundos > 0 -> "dd/MM/yyyy HH:mm"
			else -> "dd/MM/yyyy"
		}
		return SimpleDateFormat(patron, Locale.ROOT).apply { timeZone = utc }.format(cal.time)
	}

	// --- OpenDocument --------------------------------------------------------------------

	private fun leerOds(entrada: InputStream, c: ConstructorDocumento, ctx: ContextoLectura) {
		val p = AyudaPresentacion.parser(entrada)
		var profTabla = 0
		var hoja: Hoja? = null
		var numeroHoja = 0
		var enCabecera = false
		var fila: ArrayList<String>? = null
		var repetirFila = 1
		var filaTexto = true
		var vacias = 0
		var celda: StringBuilder? = null
		var repetirCelda = 1
		var tipoValor = ""
		var valorAtributo = ""
		var enP = 0
		var saltarHasta = -1
		while (true) {
			val evento = p.next()
			if (evento == XmlPullParser.END_DOCUMENT) break
			if (saltarHasta >= 0) {
				if (evento == XmlPullParser.END_TAG && p.depth == saltarHasta) saltarHasta = -1
				continue
			}
			when (evento) {
				XmlPullParser.START_TAG -> {
					fun atr(nombre: String) = p.getAttributeValue(null, nombre)
					when (p.name) {
						"table:table" -> {
							profTabla++
							if (profTabla == 1) hoja = Hoja(atr("table:name").orEmpty())
						}
						"table:table-header-rows" -> if (profTabla == 1) enCabecera = true
						"table:table-row" -> if (profTabla == 1) {
							fila = ArrayList()
							repetirFila = (atr("table:number-rows-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1)
							filaTexto = true
							vacias = 0
						}
						"table:table-cell", "table:covered-table-cell" -> if (profTabla == 1 && fila != null) {
							celda = StringBuilder()
							repetirCelda = (atr("table:number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1)
							tipoValor = atr("office:value-type").orEmpty()
							valorAtributo = when (tipoValor) {
								"boolean" -> if (atr("office:boolean-value") == "true") "VERDADERO" else "FALSO"
								"date" -> atr("office:date-value").orEmpty().replace('T', ' ')
								"time" -> atr("office:time-value").orEmpty()
								"string" -> atr("office:string-value").orEmpty()
								"" -> ""
								else -> atr("office:value")?.let { formatoNumero(it) }.orEmpty()
							}
						}
						"text:p", "text:h" -> celda?.let {
							if (it.isNotEmpty()) it.append(' ')
							enP++
						}
						"text:s" -> if (enP > 0) celda?.append(' ')
						"text:tab", "text:line-break" -> if (enP > 0) celda?.append(' ')
						"office:annotation" -> saltarHasta = p.depth
					}
				}
				XmlPullParser.TEXT -> if (enP > 0) celda?.append(p.text)
				XmlPullParser.END_TAG -> when (p.name) {
					"text:p", "text:h" -> if (enP > 0) enP--
					"table:table-cell", "table:covered-table-cell" -> {
						val f = fila
						val contenido = celda
						if (profTabla == 1 && f != null && contenido != null) {
							val texto = Textos.unirEspacios(contenido.toString()).trim().ifEmpty { valorAtributo }
							if (texto.isEmpty()) {
								vacias += repetirCelda
							} else {
								repeat(minOf(vacias, LIMITE_COLUMNAS - f.size).coerceAtLeast(0)) { f.add("") }
								vacias = 0
								repeat(minOf(repetirCelda, 100)) { if (f.size < LIMITE_COLUMNAS) f.add(texto) }
								if (tipoValor.isNotEmpty() && tipoValor != "string") filaTexto = false
							}
							celda = null
						}
					}
					"table:table-row" -> if (profTabla == 1) {
						val f = fila
						fila = null
						val h = hoja
						if (h != null && f != null && f.any { it.isNotBlank() }) {
							if (h.filas.isEmpty()) {
								h.primeraEsTexto = filaTexto
								h.encabezadoDeclarado = enCabecera
							}
							for (k in 0 until minOf(repetirFila, 100)) {
								if (h.filas.size >= LIMITE_FILAS) {
									h.truncada = true
									break
								}
								h.filas.add(f)
							}
							if (h.filas.size % 500 == 0) ctx.comprobar()
						}
					}
					"table:table-header-rows" -> if (profTabla == 1) enCabecera = false
					"table:table" -> {
						if (profTabla == 1) {
							hoja?.let { emitirHoja(c, it, ++numeroHoja, ctx.opciones) }
							hoja = null
						}
						profTabla--
					}
				}
			}
		}
	}
}
