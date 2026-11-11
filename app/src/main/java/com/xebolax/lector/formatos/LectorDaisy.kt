package com.xebolax.lector.formatos

import com.xebolax.lector.R
import com.xebolax.lector.modelo.ConstructorDocumento
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.FraseAudio
import com.xebolax.lector.modelo.LibroAudio
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.MarcaAudio
import com.xebolax.lector.modelo.Pista
import com.xebolax.lector.modelo.Textos
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import kotlin.math.roundToLong

/**
 * Libros DAISY 2.02 (ncc.html + SMIL 1.0) y DAISY 3 (OPF + NCX + SMIL 2.0
 * + DTBook), de cualquier tipo: solo audio, audio con índice, audio y texto
 * sincronizados o solo texto.
 *
 * - Las frases son los <par> del SMIL en orden de lectura (y los <audio>
 *   sueltos): un trozo de audio y, si lo hay, el texto que le corresponde.
 * - El índice (encabezados del NCC o navMap del NCX) y las páginas apuntan
 *   a frases y de ahí a (pista, ms) y al bloque del texto.
 * - El texto se convierte archivo a archivo con ConversorHtml (el DTBook
 *   se traduce antes a etiquetas de HTML) y cada frase se ata a su bloque
 *   por el ancla "archivo#id".
 */
object LectorDaisy : LectorFormato {

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		if (fuente.esCarpeta) return fuente.contenedor().use { leerContenedor(it, ctx, fuente.nombre) }
		val cabecera = runCatching { fuente.cabecera(4096) }.getOrDefault(ByteArray(0))
		val nombre = fuente.nombre.substringBeforeLast('.').ifBlank { fuente.nombre }
		if (cabecera.size >= 2 && cabecera[0] == 'P'.code.toByte() && cabecera[1] == 'K'.code.toByte()) {
			val carpeta = extraerZip(fuente, ctx, "daisy")
			return ContenedorDirectorio(carpeta).use { leerContenedor(it, ctx, nombre) }
		}
		// DTBook suelto: se puede leer sin sus hermanos (es solo texto)
		if (String(cabecera, Charsets.ISO_8859_1).contains("<dtbook", ignoreCase = true)) {
			return dtbookSuelto(fuente, ctx)
		}
		if (fuente.uri.scheme == "file") {
			val padre = File(fuente.uri.path!!).parentFile
				?: throw ExcepcionFormato(R.string.fmt_daisy_carpeta)
			return ContenedorDirectorio(padre).use { leerContenedor(it, ctx, padre.name) }
		}
		throw ExcepcionFormato(R.string.fmt_daisy_carpeta)
	}

	/**
	 * Extrae un ZIP a la carpeta de la app (el audio tiene que estar en
	 * archivos sueltos para reproducirlo). Si ya se extrajo, no se repite.
	 */
	fun extraerZip(fuente: Fuente, ctx: ContextoLectura, tipo: String = "daisy"): File {
		val destino = File(ctx.app.filesDir, tipo + "/" + Fuente.hash(fuente.uri.toString() + fuente.tamano))
		val marca = File(destino, ".completo")
		// La fecha de la marca dice cuándo se usó por última vez (para borrar primero lo que no se usa)
		if (marca.exists()) return destino.also { marca.setLastModified(System.currentTimeMillis()) }
		// Un hilo cada vez por carpeta: si dos abren el mismo libro a la vez, uno no borra lo que extrae el otro
		synchronized(cerrojos.getOrPut(destino.path) { Any() }) {
			if (marca.exists()) return destino
			destino.deleteRecursively()
			ctx.progreso(1)
			ContenedorZip(fuente.archivoLocal()).use { it.extraer(destino) }
			marca.writeText("1")
		}
		runCatching { limpiarExtracciones(destino.parentFile, destino) }
		return destino
	}

	private val cerrojos = java.util.concurrent.ConcurrentHashMap<String, Any>()

	/**
	 * Las extracciones no crecen sin límite: pasando de 3 GB por tipo, se
	 * borran las que llevan más de un mes sin abrirse (las más antiguas
	 * primero). Si se vuelve a abrir el libro, se extrae otra vez de su ZIP
	 * (que hay que leer igualmente para abrirlo).
	 */
	private fun limpiarExtracciones(base: File?, actual: File) {
		// Nunca la del audiolibro cargado en el reproductor (se puede estar escuchando sin volver a abrirlo)
		val cargado = com.xebolax.lector.audio.ControlAudio.libro?.pistas?.firstOrNull()?.uri
			?.takeIf { it.startsWith("file:") }?.let { android.net.Uri.parse(it).path }
		val carpetas = base?.listFiles()?.filter { c ->
			c.isDirectory && c != actual && (cargado == null || !cargado.startsWith(c.path + File.separator))
		} ?: return
		fun usada(f: File) = File(f, ".completo").takeIf { it.exists() }?.lastModified() ?: f.lastModified()
		val tamanos = carpetas.associateWith { c -> c.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
		var total = tamanos.values.sum() + actual.walkTopDown().filter { it.isFile }.sumOf { it.length() }
		val haceUnMes = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
		for (c in carpetas.sortedBy { usada(it) }) {
			if (total <= 3L * 1024 * 1024 * 1024) break
			if (usada(c) > haceUnMes) break
			synchronized(cerrojos.getOrPut(c.path) { Any() }) { c.deleteRecursively() }
			total -= tamanos[c] ?: 0L
		}
	}

	/** Lee un libro DAISY de un contenedor (carpeta). nombre: título si el libro no lo declara. */
	fun leerContenedor(contenedor: Contenedor, ctx: ContextoLectura, nombre: String): Contenido {
		val entradas = contenedor.entradas().filterNot { it.startsWith("__MACOSX") }
		fun profundidad(ruta: String) = ruta.count { it == '/' }
		val ncc = entradas.filter {
			val n = it.substringAfterLast('/').lowercase()
			n == "ncc.html" || n == "ncc.htm"
		}.minByOrNull { profundidad(it) }
		if (ncc != null) return Montaje(contenedor, ctx, nombre, entradas).daisy2(ncc)
		val opfs = entradas.filter { it.lowercase().endsWith(".opf") }.sortedBy { profundidad(it) }
		if (opfs.isNotEmpty()) return Montaje(contenedor, ctx, nombre, entradas).daisy3(opfs.first())
		val dtbooks = entradas.filter { it.lowercase().endsWith(".xml") }.sortedWith(OrdenNatural).filter { ruta ->
			runCatching {
				contenedor.abrir(ruta)?.use { e ->
					val b = ByteArray(4000)
					var n = 0
					while (n < b.size) {
						val r = e.read(b, n, b.size - n)
						if (r < 0) break
						n += r
					}
					String(b, 0, n, Charsets.ISO_8859_1)
				}
			}.getOrNull().orEmpty().contains("<dtbook", ignoreCase = true)
		}
		if (dtbooks.isNotEmpty()) return Montaje(contenedor, ctx, nombre, entradas).soloTexto(dtbooks)
		throw ExcepcionFormato(R.string.fmt_daisy_no_encontrado)
	}

	/**
	 * EPUB 3 con narración sincronizada (Media Overlays): texto y audio
	 * humano a la vez, como un DAISY de texto y audio (los dos usan SMIL). El
	 * contenedor es el EPUB ya extraído (el audio tiene que estar en archivos).
	 */
	fun epubNarrado(contenedor: Contenedor, ctx: ContextoLectura, nombre: String, rutaOpf: String): Contenido {
		val entradas = contenedor.entradas().filterNot { it.startsWith("__MACOSX") }
		return Montaje(contenedor, ctx, nombre, entradas).epub3(rutaOpf)
	}

	// --- Tiempos -----------------------------------------------------------------

	private val MEDIDA = Regex("""^(\d+(?:\.\d*)?|\.\d+)\s*(ms|s|min|h)?$""", RegexOption.IGNORE_CASE)

	/**
	 * Reloj de SMIL a milisegundos: "npt=1.709s", "0:00:01.709", "00:01.709",
	 * "1.709s", "123ms", "2.5min", "1h" o segundos a secas. Los grupos con dos
	 * puntos se cuentan de derecha a izquierda ("01:30" es minuto y medio).
	 */
	fun tiempo(valor: String?): Long? {
		var s = valor?.trim() ?: return null
		if (s.startsWith("npt=", ignoreCase = true)) s = s.substring(4).trim()
		if (s.isEmpty()) return null
		if (':' in s) {
			val partes = s.split(':')
			if (partes.size > 3) return null
			var total = 0.0
			var escala = 1.0
			for (p in partes.asReversed()) {
				val v = p.trim().toDoubleOrNull() ?: return null
				if (v < 0) return null
				total += v * escala
				escala *= 60.0
			}
			return (total * 1000).roundToLong()
		}
		val m = MEDIDA.matchEntire(s) ?: return null
		val n = m.groupValues[1].toDoubleOrNull() ?: return null
		val factor = when (m.groupValues[2].lowercase()) {
			"ms" -> 1.0
			"min" -> 60_000.0
			"h" -> 3_600_000.0
			else -> 1000.0
		}
		return (n * factor).roundToLong()
	}

	// --- Utilidades XML ----------------------------------------------------------

	private val PROLOGO = Regex("^\\s*<\\?xml[^>]*\\?>")

	private fun sinPrologo(texto: String) = PROLOGO.replaceFirst(texto, "").trimStart()

	private fun local(e: Element) = e.normalName().substringAfter(':')

	private fun xml(z: Contenedor, ruta: String): Document? {
		val bytes = runCatching { z.bytes(ruta) }.getOrNull() ?: return null
		return runCatching { Jsoup.parse(sinPrologo(Textos.decodificar(bytes)), "", Parser.xmlParser()) }.getOrNull()
	}

	private fun html(z: Contenedor, ruta: String): Document? {
		val bytes = runCatching { z.bytes(ruta) }.getOrNull() ?: return null
		return runCatching { Jsoup.parse(sinPrologo(Textos.decodificar(bytes))) }.getOrNull()
	}

	private val CLASES_PAGINA = setOf("page-normal", "page-front", "page-special")

	private fun esPagina(e: Element) = e.classNames().any { it.lowercase() in CLASES_PAGINA }

	/** Las páginas de DAISY 2.02 (class="page-*") como saltos de página que entiende ConversorHtml. */
	private fun marcarPaginasHtml(raiz: Element): Boolean {
		var hay = false
		for (e in raiz.getAllElements()) {
			if (esPagina(e)) {
				e.attr("title", e.text().trim())
				e.attr("epub:type", "pagebreak")
				hay = true
			}
		}
		return hay
	}

	/**
	 * Traduce DTBook a las etiquetas de HTML que entiende ConversorHtml:
	 * level → section, hd/levelhd → hN por profundidad, pagenum → salto de
	 * página, list → ul/ol, noteref → enlace, note → nota, imggroup → figure,
	 * prodnote/sidebar → aside, sent/w → span. Devuelve si hay páginas.
	 */
	private fun dtbookAHtml(raiz: Element): Boolean {
		var paginas = false
		// Al revés (hijos antes que padres): los niveles aún tienen su nombre al calcular la profundidad
		for (e in raiz.getAllElements().asReversed()) {
			when (val n = local(e)) {
				"hd", "levelhd", "bridgehead" -> {
					var nivel = 0
					for (p in e.parents()) {
						val pn = local(p)
						if (pn == "level") nivel++
						else if (pn.length == 6 && pn.startsWith("level") && pn[5].isDigit()) {
							nivel += pn[5] - '0'
							break
						}
					}
					if (n == "bridgehead") nivel++
					e.tagName("h" + nivel.coerceIn(1, 6))
				}
				"level", "level1", "level2", "level3", "level4", "level5", "level6" -> e.tagName("section")
				"doctitle" -> e.tagName("h1")
				"docauthor", "covertitle", "author", "byline", "dateline" -> e.tagName("p")
				"pagenum" -> {
					val texto = e.text().trim()
					e.tagName("span")
					e.attr("title", texto)
					e.attr("epub:type", "pagebreak")
					paginas = true
				}
				"list" -> {
					val ordenada = e.attr("type").equals("ol", ignoreCase = true)
					e.removeAttr("type")
					e.tagName(if (ordenada) "ol" else "ul")
				}
				"lic", "sent", "w", "linenum", "q" -> e.tagName("span")
				"noteref", "annoref" -> {
					val ref = e.attr("idref").ifBlank { e.attr("href") }
					e.tagName("a")
					if (ref.isNotBlank()) e.attr("href", if (ref.contains('#')) ref else "#$ref")
					e.attr("epub:type", "noteref")
				}
				"note", "annotation" -> {
					e.tagName("aside")
					e.attr("epub:type", "footnote")
				}
				"imggroup" -> e.tagName("figure")
				"caption" -> if (e.parent()?.let { local(it) } == "imggroup") e.tagName("figcaption")
				"prodnote", "sidebar" -> e.tagName("aside")
			}
		}
		return paginas
	}

	/** Un DTBook elegido solo (sin carpeta): libro de solo texto. */
	private fun dtbookSuelto(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val texto = sinPrologo(Textos.decodificar(fuente.bytes()))
		val doc = Jsoup.parse(texto, "", Parser.xmlParser())
		val c = ctx.nuevoConstructor()
		metadatosDtbook(doc, c)
		if (c.titulo.isBlank()) c.titulo = fuente.nombre.substringBeforeLast('.')
		val libro = doc.getAllElements().firstOrNull { local(it) == "book" } ?: doc
		dtbookAHtml(libro)
		ConversorHtml(c, "", ctx.opciones).convertir(libro)
		if (c.estaVacio()) throw ExcepcionFormato(R.string.fmt_vacio)
		c.metadatos["Tipo DAISY"] = "DAISY 3, solo texto"
		return Contenido.Texto(c.construir())
	}

	private fun metadatosDtbook(doc: Document, c: ConstructorDocumento) {
		for (m in doc.getAllElements().filter { local(it) == "meta" }) {
			val valor = m.attr("content").trim()
			if (valor.isEmpty()) continue
			when (m.attr("name").lowercase()) {
				"dc:title" -> if (c.titulo.isBlank()) c.titulo = valor
				"dc:creator" -> if (c.autor.isBlank()) c.autor = valor
				"dc:language" -> if (c.idioma.isBlank()) c.idioma = valor
				"dc:publisher" -> c.metadatos.putIfAbsent("Editorial", valor)
				"dc:date" -> c.metadatos.putIfAbsent("Fecha", valor)
			}
		}
		if (c.titulo.isBlank()) doc.getAllElements().firstOrNull { local(it) == "doctitle" }?.let { c.titulo = it.text().trim() }
		if (c.autor.isBlank()) doc.getAllElements().firstOrNull { local(it) == "docauthor" }?.let { c.autor = it.text().trim() }
		if (c.idioma.isBlank()) c.idioma = doc.getAllElements().firstOrNull { local(it) == "dtbook" }?.attr("xml:lang").orEmpty()
	}

	// --- Montaje -----------------------------------------------------------------

	/** Trozo de audio: archivo (ruta en el contenedor), inicio y fin en ms (-1 = hasta el final del archivo). */
	private class Segmento(val audio: String, val inicio: Long, var fin: Long)

	/** Un <par> del SMIL. texto: "archivo#id" del texto ("" si no tiene). */
	private class Frase(val smil: String, val texto: String, val segmentos: List<Segmento>, val anclas: List<String>)

	/** Entrada de navegación: nivel 1-6, o 0 para las páginas. destino: "archivo#id". */
	private class Entrada(val titulo: String, val nivel: Int, val destino: String)

	private class Montaje(val z: Contenedor, val ctx: ContextoLectura, val nombre: String, val entradas: List<String>) {
		var version = ""
		var titulo = ""
		var autor = ""
		var narrador = ""
		var editorial = ""
		var fecha = ""
		var idioma = ""
		var tipo = ""
		var duracionDeclarada = 0L
		var ncc: String? = null

		val frases = ArrayList<Frase>()
		/** "smil#id" (par, text, seq...) y "texto#id" → índice de frase. */
		val anclas = HashMap<String, Int>()
		val inicioSmil = HashMap<String, Int>()
		private val rutas = HashMap<String, String>()

		/** Ruta real de un enlace relativo a un archivo, con su fragmento. */
		fun destino(base: String, href: String): String {
			val h = href.trim()
			val archivo = if (h.substringBefore('#').isEmpty()) base else rutas.getOrPut(base + "\u0000" + h.substringBefore('#')) {
				val r = Contenedor.resolver(base, h)
				z.buscar(r) ?: r
			}
			val fragmento = h.substringAfter('#', "")
			return if (fragmento.isEmpty()) archivo else "$archivo#$fragmento"
		}

		private fun carpeta(ruta: String) = ruta.substringBeforeLast('/', "")

		// --- DAISY 2.02 ---

		fun daisy2(rutaNcc: String): Contenido {
			version = "2.02"
			ncc = rutaNcc
			ctx.progreso(2)
			val doc = html(z, rutaNcc) ?: throw ExcepcionFormato(R.string.fmt_falta_parte)
			for (m in doc.select("meta")) {
				val valor = m.attr("content").trim()
				if (valor.isEmpty()) continue
				when (m.attr("name").trim().lowercase()) {
					"dc:title" -> if (titulo.isEmpty()) titulo = valor
					"dc:creator" -> autor = if (autor.isEmpty()) valor else "$autor, $valor"
					"ncc:narrator" -> if (narrador.isEmpty()) narrador = valor
					"ncc:multimediatype" -> tipo = valor
					"ncc:totaltime" -> duracionDeclarada = tiempo(valor) ?: 0L
					"dc:language" -> if (idioma.isEmpty()) idioma = valor
					"dc:publisher" -> if (editorial.isEmpty()) editorial = valor
					"dc:date" -> if (fecha.isEmpty()) fecha = valor
				}
			}
			if (titulo.isEmpty()) titulo = doc.title().trim()

			val navegacion = ArrayList<Entrada>()
			val ordenSmil = LinkedHashSet<String>()
			val otros = LinkedHashSet<String>()
			for (e in doc.body().getAllElements()) {
				val n = local(e)
				val nivel = when {
					n.length == 2 && n[0] == 'h' && n[1] in '1'..'6' -> n[1] - '0'
					esPagina(e) -> 0
					else -> continue
				}
				val href = (if (n == "a") e else e.selectFirst("a[href]"))?.attr("href")?.trim().orEmpty()
				if (href.isEmpty()) continue
				val d = destino(rutaNcc, href)
				val archivo = d.substringBefore('#')
				if (archivo.lowercase().endsWith(".smil")) ordenSmil += archivo else otros += archivo
				navegacion += Entrada(Textos.unirEspacios(e.text()).trim().ifEmpty { "(sin título)" }, nivel, d)
			}
			// master.smil como alternativa
			if (ordenSmil.isEmpty()) {
				val master = z.buscar(if (carpeta(rutaNcc).isEmpty()) "master.smil" else carpeta(rutaNcc) + "/master.smil")
				val m = master?.let { xml(z, it) }
				if (master != null && m != null) {
					for (r in m.getAllElements().filter { local(it) == "ref" }) {
						val src = r.attr("src")
						if (src.isNotBlank()) ordenSmil += destino(master, src).substringBefore('#')
					}
				}
			}
			return montar(ordenSmil.toList(), navegacion, otros.filter { !esNcc(it) })
		}

		// --- DAISY 3 ---

		fun daisy3(rutaOpf: String): Contenido {
			version = "3"
			ctx.progreso(2)
			val opf = xml(z, rutaOpf) ?: throw ExcepcionFormato(R.string.fmt_falta_parte)
			val todos = opf.getAllElements()
			for (e in todos) {
				when (local(e)) {
					"title" -> if (titulo.isEmpty()) titulo = e.text().trim()
					"creator" -> e.text().trim().takeIf { it.isNotEmpty() }?.let { autor = if (autor.isEmpty()) it else "$autor, $it" }
					"publisher" -> if (editorial.isEmpty()) editorial = e.text().trim()
					"date" -> if (fecha.isEmpty()) fecha = e.text().trim()
					"language" -> if (idioma.isEmpty()) idioma = e.text().trim()
					"meta" -> {
						val valor = e.attr("content").trim()
						when (e.attr("name").trim().lowercase()) {
							"dtb:narrator" -> if (narrador.isEmpty()) narrador = valor
							"dtb:multimediatype" -> tipo = valor
							"dtb:totaltime" -> duracionDeclarada = tiempo(valor) ?: 0L
							"dc:title" -> if (titulo.isEmpty()) titulo = valor
							"dc:creator" -> if (autor.isEmpty()) autor = valor
						}
					}
				}
			}
			// Manifiesto y orden de lectura
			class Item(val ruta: String, val tipo: String)
			val items = LinkedHashMap<String, Item>()
			for (e in todos.filter { local(it) == "item" }) {
				val href = e.attr("href")
				if (href.isBlank()) continue
				items[e.attr("id")] = Item(destino(rutaOpf, href), e.attr("media-type").lowercase())
			}
			val ordenSmil = ArrayList<String>()
			for (e in todos.filter { local(it) == "itemref" }) {
				val item = items[e.attr("idref")] ?: continue
				if (item.tipo.contains("smil") || item.ruta.lowercase().endsWith(".smil")) ordenSmil += item.ruta
			}
			val dtbooks = items.values.filter {
				it.tipo.contains("dtbook+xml") || (it.ruta.lowercase().endsWith(".xml") && !it.tipo.contains("ncx") && !it.tipo.contains("res"))
			}.map { it.ruta }
			val rutaNcx = items.values.firstOrNull { it.tipo.contains("ncx") || it.ruta.lowercase().endsWith(".ncx") }?.ruta
				?.let { z.buscar(it) }
				?: entradas.filter { it.lowercase().endsWith(".ncx") }.minByOrNull { it.count { c -> c == '/' } }
			val navegacion = ArrayList<Entrada>()
			if (rutaNcx != null) xml(z, rutaNcx)?.let { ncx -> leerNcx(ncx, rutaNcx, navegacion) }
			return montar(ordenSmil, navegacion, dtbooks)
		}

		/** EPUB 3: el orden de lectura (spine) con el SMIL de cada capítulo (media-overlay) y su índice (nav o NCX). */
		fun epub3(rutaOpf: String): Contenido {
			version = "EPUB 3"
			ctx.progreso(2)
			val opf = xml(z, rutaOpf) ?: throw ExcepcionFormato(R.string.fmt_falta_parte)
			val todos = opf.getAllElements()
			for (e in todos) {
				when (local(e)) {
					"title" -> if (titulo.isEmpty()) titulo = e.text().trim()
					"creator" -> e.text().trim().takeIf { it.isNotEmpty() }?.let { autor = if (autor.isEmpty()) it else "$autor, $it" }
					"publisher" -> if (editorial.isEmpty()) editorial = e.text().trim()
					"date" -> if (fecha.isEmpty()) fecha = e.text().trim()
					"language" -> if (idioma.isEmpty()) idioma = e.text().trim()
					"meta" -> when (e.attr("property").trim()) {
						"media:narrator" -> if (narrador.isEmpty()) narrador = e.text().trim()
						"media:duration" -> if (!e.hasAttr("refines")) duracionDeclarada = tiempo(e.text()) ?: 0L
					}
				}
			}
			class Item(val ruta: String, val tipo: String, val narracion: String, val propiedades: String)
			val items = LinkedHashMap<String, Item>()
			for (e in todos.filter { local(it) == "item" }) {
				val href = e.attr("href")
				if (href.isBlank()) continue
				items[e.attr("id")] = Item(destino(rutaOpf, href), e.attr("media-type").lowercase(), e.attr("media-overlay"), e.attr("properties"))
			}
			val ordenSmil = ArrayList<String>()
			val textos = ArrayList<String>()
			for (e in todos.filter { local(it) == "itemref" }) {
				val item = items[e.attr("idref")] ?: continue
				textos += item.ruta
				items[item.narracion]?.let { ordenSmil += it.ruta }
			}
			val navegacion = ArrayList<Entrada>()
			val nav = items.values.firstOrNull { it.propiedades.split(' ').contains("nav") }
			if (nav != null) html(z, nav.ruta)?.let { leerNav(it, nav.ruta, navegacion) }
			if (navegacion.isEmpty()) {
				items.values.firstOrNull { it.tipo.contains("ncx") }?.let { n -> xml(z, n.ruta)?.let { leerNcx(it, n.ruta, navegacion) } }
			}
			return montar(ordenSmil, navegacion, textos)
		}

		/** Índice de un EPUB 3 (nav toc: listas anidadas) y su lista de páginas (nav page-list). */
		private fun leerNav(doc: Document, rutaNav: String, navegacion: MutableList<Entrada>) {
			fun tipo(e: Element) = e.attr("epub:type").ifEmpty { e.attr("type") }.ifEmpty { e.attr("role").substringAfter("doc-") }
			fun recorrer(lista: Element, nivel: Int) {
				for (li in lista.children().filter { it.normalName() == "li" }) {
					val a = li.children().firstOrNull { it.normalName() == "a" || it.normalName() == "span" }
					val href = a?.attr("href").orEmpty()
					if (href.isNotBlank()) navegacion += Entrada(Textos.unirEspacios(a!!.text()).trim().ifEmpty { "(sin título)" }, nivel.coerceIn(1, 6), destino(rutaNav, href))
					li.children().firstOrNull { it.normalName() == "ol" || it.normalName() == "ul" }?.let { recorrer(it, nivel + 1) }
				}
			}
			for (n in doc.select("nav")) {
				val t = tipo(n)
				val lista = n.children().firstOrNull { it.normalName() == "ol" || it.normalName() == "ul" } ?: continue
				when {
					t.contains("toc") -> recorrer(lista, 1)
					t.contains("page-list") -> for (a in lista.select("a[href]")) navegacion += Entrada(Textos.unirEspacios(a.text()).trim(), 0, destino(rutaNav, a.attr("href")))
				}
			}
		}

		private fun leerNcx(ncx: Document, rutaNcx: String, navegacion: MutableList<Entrada>) {
			fun etiqueta(e: Element) = e.children().firstOrNull { local(it) == "navlabel" }?.let { Textos.unirEspacios(it.text()).trim() }.orEmpty()
			fun src(e: Element) = e.children().firstOrNull { local(it) == "content" }?.attr("src")?.trim().orEmpty()
			fun recorrer(padre: Element, nivel: Int) {
				for (h in padre.children()) {
					if (local(h) != "navpoint") continue
					val s = src(h)
					if (s.isNotEmpty()) navegacion += Entrada(etiqueta(h).ifEmpty { "(sin título)" }, nivel.coerceIn(1, 6), destino(rutaNcx, s))
					recorrer(h, nivel + 1)
				}
			}
			val todos = ncx.getAllElements()
			todos.firstOrNull { local(it) == "navmap" }?.let { recorrer(it, 1) }
			for (p in todos.filter { local(it) == "pagetarget" }) {
				val s = src(p)
				if (s.isNotEmpty()) navegacion += Entrada(etiqueta(p).ifEmpty { p.attr("value") }, 0, destino(rutaNcx, s))
			}
			if (titulo.isEmpty()) todos.firstOrNull { local(it) == "doctitle" }?.let { titulo = Textos.unirEspacios(it.text()).trim() }
			if (autor.isEmpty()) todos.firstOrNull { local(it) == "docauthor" }?.let { autor = Textos.unirEspacios(it.text()).trim() }
		}

		/** Solo DTBook, sin OPF ni NCC. */
		fun soloTexto(dtbooks: List<String>): Contenido {
			version = "3"
			return montar(emptyList(), emptyList(), dtbooks)
		}

		// --- SMIL ---

		private fun leerSmil(ruta: String): List<Frase> {
			val doc = xml(z, ruta) ?: return emptyList()
			val r = ArrayList<Frase>()
			var ultimoTexto = ""
			fun parDe(e: Element): Element? = e.parents().firstOrNull { local(it) == "par" }
			fun idsAntecesores(e: Element) = e.parents().mapNotNull { p -> p.id().takeIf { it.isNotEmpty() } }
			fun segmentos(audios: List<Element>): List<Segmento> {
				val s = ArrayList<Segmento>()
				for (a in audios) {
					val src = a.attr("src").trim()
					if (src.isEmpty()) continue
					val archivo = destino(ruta, src).substringBefore('#')
					val ini = tiempo(a.attr("clip-begin").ifEmpty { a.attr("clipBegin") }) ?: 0L
					val fin = tiempo(a.attr("clip-end").ifEmpty { a.attr("clipEnd") }) ?: -1L
					val anterior = s.lastOrNull()
					// Recortes seguidos del mismo archivo: un solo segmento (una sección de solo audio es un par con muchos)
					if (anterior != null && anterior.audio == archivo && anterior.fin >= 0 && ini >= anterior.inicio && ini - anterior.fin < 1500) {
						anterior.fin = if (fin >= 0) maxOf(fin, anterior.fin) else -1L
					} else {
						s += Segmento(archivo, ini, if (fin >= 0 && fin < ini) -1L else fin)
					}
				}
				return s
			}
			for (e in doc.getAllElements()) {
				when (local(e)) {
					"par" -> {
						val propios = e.getAllElements().filter { it !== e && parDe(it) === e }
						val textoEl = propios.firstOrNull { local(it) == "text" }
						val audios = propios.filter { local(it) == "audio" }
						var texto = ""
						textoEl?.attr("src")?.trim()?.takeIf { it.isNotEmpty() }?.let { src ->
							texto = if (src.startsWith("#")) {
								if (ultimoTexto.isNotEmpty()) ultimoTexto + src else ""
							} else destino(ruta, src)
							if (!src.startsWith("#")) ultimoTexto = texto.substringBefore('#')
						}
						val segs = segmentos(audios)
						if (segs.isEmpty() && texto.isEmpty()) continue
						val ids = ArrayList<String>()
						e.id().takeIf { it.isNotEmpty() }?.let { ids += it }
						textoEl?.id()?.takeIf { it.isNotEmpty() }?.let { ids += it }
						texto.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let { ids += it }
						ids += idsAntecesores(e)
						r += Frase(ruta, texto, segs, ids)
					}
					"audio" -> if (parDe(e) == null) {
						val segs = segmentos(listOf(e))
						if (segs.isEmpty()) continue
						val ids = ArrayList<String>()
						e.id().takeIf { it.isNotEmpty() }?.let { ids += it }
						ids += idsAntecesores(e)
						r += Frase(ruta, "", segs, ids)
					}
				}
			}
			return r
		}

		/** Frase a la que apunta un destino de la navegación (-1 si ninguna). */
		fun fraseDe(destino: String): Int {
			anclas[destino]?.let { return it }
			return inicioSmil[destino.substringBefore('#')] ?: -1
		}

		private fun esNcc(ruta: String): Boolean {
			val n = ruta.substringAfterLast('/').lowercase()
			return n == "ncc.html" || n == "ncc.htm"
		}

		private fun esArchivoTexto(ruta: String): Boolean {
			val r = ruta.lowercase()
			return !esNcc(ruta) && !r.endsWith(".smil") && !r.endsWith(".ncx") && !r.endsWith(".opf") &&
				(r.endsWith(".html") || r.endsWith(".htm") || r.endsWith(".xhtml") || r.endsWith(".xml"))
		}

		// --- Montaje común ---

		fun montar(ordenSmil: List<String>, navegacion: List<Entrada>, textoAlternativo: List<String>): Contenido {
			// SMIL no declarados al final: mejor fuera de sitio que perdidos
			val raiz = carpeta(ncc ?: ordenSmil.firstOrNull() ?: "")
			val declarados = ordenSmil.filter { z.buscar(it) != null }.distinct()
			val sueltos = entradas.filter {
				it.lowercase().endsWith(".smil") && it !in declarados && it.substringAfterLast('/').lowercase() != "master.smil" &&
					(raiz.isEmpty() || it.startsWith("$raiz/"))
			}.sortedWith(OrdenNatural)
			val smils = if (declarados.isEmpty() && version == "3" && navegacion.isEmpty() && ordenSmil.isEmpty()) emptyList() else declarados + sueltos

			for ((i, smil) in smils.withIndex()) {
				ctx.comprobar()
				ctx.progreso(5 + 65 * i / smils.size)
				val propias = leerSmil(smil)
				if (propias.isEmpty()) continue
				inicioSmil[smil] = frases.size
				for (f in propias) {
					val pos = frases.size
					for (id in f.anclas) anclas.putIfAbsent("$smil#$id", pos)
					if (f.texto.isNotEmpty()) anclas.putIfAbsent(f.texto, pos)
					frases += f
				}
			}
			cerrarFinales()
			val hayAudio = frases.any { it.segmentos.isNotEmpty() }

			// Texto del libro: los archivos a los que apuntan las frases (sin el NCC)
			val archivosTexto = LinkedHashSet<String>()
			// EPUB 3: todo el orden de lectura, también los capítulos sin narración (portada, notas...), cada uno en su sitio
			if (version.startsWith("EPUB")) for (a in textoAlternativo) if (esArchivoTexto(a)) z.buscar(a)?.let { archivosTexto += it }
			for (f in frases) {
				val a = f.texto.substringBefore('#')
				if (a.isNotEmpty() && esArchivoTexto(a) && z.buscar(a) != null) archivosTexto += a
			}
			if (!hayAudio && archivosTexto.isEmpty()) {
				for (a in textoAlternativo) if (esArchivoTexto(a) && z.buscar(a) != null) archivosTexto += z.buscar(a)!!
			}
			val c = ctx.nuevoConstructor()
			c.titulo = titulo.ifBlank { nombre }
			c.autor = autor
			c.idioma = idioma
			var paginasEnTexto = false
			for ((i, a) in archivosTexto.withIndex()) {
				ctx.comprobar()
				ctx.progreso(70 + 20 * i / archivosTexto.size)
				if (convertirTexto(a, c)) paginasEnTexto = true
			}
			// Sin audio ni texto: al menos el NCC (sus encabezados)
			if (!hayAudio && c.estaVacio()) {
				ncc?.let { n -> html(z, n)?.let { ConversorHtml(c, n, ctx.opciones).convertir(it.body()) } }
				if (c.estaVacio()) throw ExcepcionFormato(R.string.fmt_vacio)
			}
			val hayTexto = !c.estaVacio()
			if (hayTexto && archivosTexto.isNotEmpty()) {
				for (e in navegacion) {
					val b = bloqueDe(e.destino, c) ?: continue
					if (e.nivel == 0) {
						if (!paginasEnTexto) c.pagina(e.titulo, b)
					} else c.indice(e.titulo, e.nivel, b)
				}
			}
			val tipoLegible = when {
				hayAudio && hayTexto -> "audio y texto"
				hayAudio -> "solo audio"
				else -> "solo texto"
			}
			val metadatos = linkedMapOf(
				"Autor" to autor,
				"Narrador" to narrador,
				"Editorial" to editorial,
				"Fecha" to fecha,
				"Idioma" to idioma,
				"Tipo DAISY" to (if (version.startsWith("EPUB")) "$version con narración, $tipoLegible" else "DAISY $version, $tipoLegible") + (if (tipo.isNotBlank()) " ($tipo)" else ""),
			)
			ctx.progreso(92)

			if (!hayAudio) {
				for ((k, v) in metadatos) if (v.isNotBlank() && k != "Autor" && k != "Idioma") c.metadatos[k] = v
				return Contenido.Texto(c.construir())
			}

			// --- Audio ---
			val documento = if (hayTexto) {
				for ((k, v) in metadatos) if (v.isNotBlank() && k != "Autor" && k != "Idioma") c.metadatos[k] = v
				c.construir()
			} else null
			val maxBloque = (documento?.bloques?.size ?: 0) - 1
			val pistaDe = LinkedHashMap<String, Int>()
			val primeraFrase = ArrayList<Int>()
			for ((i, f) in frases.withIndex()) for (s in f.segmentos) {
				if (s.audio !in pistaDe) {
					pistaDe[s.audio] = pistaDe.size
					primeraFrase += i
				}
			}
			val frasesAudio = ArrayList<FraseAudio>()
			for (f in frases) {
				if (f.segmentos.isEmpty()) continue
				val bloque = if (documento != null && f.texto.isNotEmpty()) {
					c.resolver(f.texto)?.coerceIn(0, maxBloque.coerceAtLeast(0)) ?: -1
				} else -1
				for (s in f.segmentos) frasesAudio += FraseAudio(pistaDe[s.audio]!!, s.inicio, s.fin, bloque)
			}
			// Capítulos y páginas: (frase con audio, marca)
			val capitulos = ArrayList<Pair<Int, MarcaAudio>>()
			val paginas = ArrayList<MarcaAudio>()
			for (e in navegacion) {
				var f = fraseDe(e.destino)
				if (f < 0) continue
				while (f < frases.size && frases[f].segmentos.isEmpty()) f++
				if (f >= frases.size) continue
				val s = frases[f].segmentos[0]
				val marca = MarcaAudio(e.titulo, e.nivel, pistaDe[s.audio]!!, s.inicio)
				if (e.nivel == 0) paginas += marca else capitulos += f to marca
			}
			val orden = compareBy<MarcaAudio>({ it.pista }, { it.ms })
			val capitulosOrdenados = capitulos.sortedBy { it.first }
			val pistas = pistaDe.keys.mapIndexed { i, audio ->
				val cubre = capitulosOrdenados.lastOrNull { it.first <= primeraFrase[i] }?.second?.titulo
				val titulo = cubre ?: audio.substringAfterLast('/').substringBeforeLast('.')
				Pista(z.uriReproducible(audio) ?: audio, titulo, 0L)
			}
			val faltan = pistaDe.keys.count { z.buscar(it) == null }
			val avisos = if (faltan > 0) listOf("Faltan $faltan archivos de audio del libro: algunas partes no sonarán.") else emptyList()
			val duracion = if (duracionDeclarada > 0) duracionDeclarada else {
				val conFin = frasesAudio.filter { it.finMs > it.inicioMs }
				if (conFin.size == frasesAudio.size) conFin.sumOf { it.finMs - it.inicioMs } else 0L
			}
			if (duracion > 0) metadatos["Duración total"] = CapitulosAudio.duracionLegible(duracion)
			ctx.progreso(100)
			return Contenido.Audio(
				LibroAudio(
					titulo = titulo.ifBlank { nombre },
					autor = autor,
					narrador = narrador,
					pistas = pistas,
					capitulos = capitulos.map { it.second }.sortedWith(orden),
					paginas = paginas.sortedWith(orden),
					frases = frasesAudio.sortedWith(compareBy({ it.pista }, { it.inicioMs })),
					texto = documento,
					metadatos = metadatos.filterValues { it.isNotBlank() },
					avisos = avisos,
				),
			)
		}

		/** Finales que faltan: el inicio del siguiente recorte del mismo archivo, o -1 (hasta el final). */
		private fun cerrarFinales() {
			for ((i, f) in frases.withIndex()) {
				for ((j, s) in f.segmentos.withIndex()) {
					if (s.fin >= 0) continue
					val siguiente = f.segmentos.getOrNull(j + 1)
						?: frases.subList(i + 1, minOf(frases.size, i + 50)).firstOrNull { it.segmentos.isNotEmpty() }?.segmentos?.first()
					if (siguiente != null && siguiente.audio == s.audio && siguiente.inicio > s.inicio) s.fin = siguiente.inicio
				}
			}
		}

		/** Bloque del texto al que apunta un destino de la navegación. */
		private fun bloqueDe(destino: String, c: ConstructorDocumento): Int? {
			val f = fraseDe(destino)
			if (f >= 0) {
				for (k in f until minOf(frases.size, f + 50)) {
					val t = frases[k].texto
					if (t.isNotEmpty()) c.resolver(t)?.let { return it }
				}
				return null
			}
			return if (destino.substringBefore('#').lowercase().endsWith(".smil")) null else c.resolver(destino)
		}

		/** Convierte un archivo de texto (XHTML de DAISY 2.02 o DTBook). Devuelve si tenía páginas. */
		private fun convertirTexto(ruta: String, c: ConstructorDocumento): Boolean {
			val bytes = runCatching { z.bytes(ruta) }.getOrNull() ?: return false
			val texto = sinPrologo(Textos.decodificar(bytes))
			val esDtbook = ruta.lowercase().endsWith(".xml") || texto.take(3000).contains("<dtbook", ignoreCase = true)
			c.marcarSiguiente(Marca.INICIO_SECCION)
			c.ancla(ruta)
			return if (esDtbook) {
				val doc = runCatching { Jsoup.parse(texto, "", Parser.xmlParser()) }.getOrNull() ?: return false
				if (c.idioma.isBlank()) c.idioma = doc.getAllElements().firstOrNull { local(it) == "dtbook" }?.attr("xml:lang").orEmpty()
				val libro = doc.getAllElements().firstOrNull { local(it) == "book" } ?: doc
				val paginas = dtbookAHtml(libro)
				ConversorHtml(c, ruta, ctx.opciones).convertir(libro)
				paginas
			} else {
				val doc = runCatching { Jsoup.parse(texto) }.getOrNull() ?: return false
				val paginas = marcarPaginasHtml(doc.body())
				ConversorHtml(c, ruta, ctx.opciones).convertir(doc.body())
				paginas
			}
		}
	}
}
