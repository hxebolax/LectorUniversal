package com.xebolax.lector.formatos

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.xebolax.lector.modelo.ConstructorDocumento
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.Documento
import com.xebolax.lector.modelo.Textos
import java.io.File
import java.io.IOException
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PDF con pdfbox-android. El texto se saca línea a línea con su tamaño de
 * letra y su posición; después se quitan cabeceras y pies repetidos, se
 * unen las líneas en párrafos (si la opción está activa), se detectan los
 * títulos por el tamaño de letra y el índice sale de los marcadores.
 */
object LectorPdf : LectorFormato {

	@Volatile
	private var iniciado = false

	private class Linea(val texto: String, val tamano: Float, val negrita: Boolean, val y: Float, val x0: Float, val x1: Float)

	private class Parrafo(val texto: StringBuilder, val tamano: Float, val negrita: Boolean, var ultima: Linea?) {
		var lineas = 1
		/** 0: párrafo; 1-6: encabezado. */
		var nivel = 0
	}

	private val NUMERO_PAGINA = Regex(
		"""^(?:p[áa]g(?:ina)?\.?|page|p\.)?\s*[-–—]?\s*(?:\d{1,4}|(?=[ivxlcdm])m{0,3}(?:cm|cd|d?c{0,3})(?:xc|xl|l?x{0,3})(?:ix|iv|v?i{0,3}))\s*[-–—]?(?:\s*(?:/|de|of)\s*\d{1,4})?$""",
		RegexOption.IGNORE_CASE,
	)
	private val LISTA = Regex("""^\s*(?:[•●○◦▪▫■□‣⁃∙·*–—-]\s|\(?\d{1,3}[.)]\s|\(?[a-z]\)\s)""")
	private val ESPACIOS = Regex("\\s+")
	private val DIGITOS = Regex("\\d")
	private val PREFIJO_TITULO = Regex("""^Microsoft (?:Word|PowerPoint|Excel) - """)

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		if (!iniciado) {
			synchronized(this) {
				if (!iniciado) {
					PDFBoxResourceLoader.init(ctx.app.applicationContext)
					iniciado = true
				}
			}
		}
		val archivo = fuente.archivoLocal()
		val temporal = File(ctx.app.cacheDir, "pdfbox").apply { mkdirs() }
		val memoria = MemoryUsageSetting.setupMixed(48L * 1024 * 1024).setTempDir(temporal)
		val doc = try {
			PDDocument.load(archivo, ctx.opciones.clave ?: "", memoria)
		} catch (e: InvalidPasswordException) {
			throw ExcepcionClave(incorrecta = ctx.opciones.clave != null)
		} catch (e: IOException) {
			throw ExcepcionFormato("El PDF está dañado o no se puede leer.", e)
		}
		try {
			return Contenido.Texto(convertir(doc, fuente, ctx))
		} finally {
			runCatching { doc.close() }
		}
	}

	private fun convertir(doc: PDDocument, fuente: Fuente, ctx: ContextoLectura): Documento {
		val total = doc.numberOfPages
		if (total <= 0) throw ExcepcionFormato("El PDF no tiene páginas.")
		val c = ctx.nuevoConstructor()
		metadatos(doc, c)
		if (c.titulo.isBlank()) c.titulo = fuente.nombre.substringBeforeLast('.')

		// 1. Líneas de cada página (80 % del progreso)
		val extractor = Extractor { pagina ->
			ctx.comprobar()
			ctx.progreso((pagina - 1) * 80 / total)
		}
		extractor.writeText(doc, NADA)
		val paginas = List(total) { extractor.paginas.getOrNull(it) ?: emptyList() }

		val etiquetas: Array<String>? = runCatching { doc.documentCatalog.pageLabels?.labelsByPageIndices }.getOrNull()
		fun etiqueta(i: Int) = etiquetas?.getOrNull(i)?.takeIf { it.isNotBlank() } ?: (i + 1).toString()

		// 2. Cabeceras y pies repetidos, y números de página sueltos (sin quitar títulos como «I» o «12», de letra más grande)
		val limpias = quitarCabecerasYPies(paginas, tamanoNormal(paginas))
		val conTexto = paginas.count { it.isNotEmpty() }
		val escaneado = conTexto * 10 < total
		if (escaneado) {
			c.avisos += "Este PDF no tiene texto: parece escaneado (imágenes de páginas). Hace falta reconocimiento de texto (OCR)."
		}

		// 3. Tamaño de letra del texto normal (el más usado, por caracteres)
		val cuerpo = tamanoNormal(limpias)

		// 4. Párrafos
		val unir = ctx.opciones.pdfUnirLineas
		val parrafos = ArrayList<Parrafo>()
		val primerParrafo = IntArray(total)
		var actual: Parrafo? = null
		fun cerrar() {
			actual?.let { parrafos += it }
			actual = null
		}
		fun nuevo(l: Linea) {
			actual = Parrafo(StringBuilder(l.texto), l.tamano, l.negrita, l)
		}
		for (p in 0 until total) {
			val lineas = limpias[p]
			if (lineas.isEmpty()) {
				cerrar()
				primerParrafo[p] = parrafos.size
				if (paginas[p].isEmpty()) {
					val aviso = if (escaneado) "Página ${etiqueta(p)} sin texto." else "Página ${etiqueta(p)}: sin texto (imagen)."
					parrafos += Parrafo(StringBuilder(aviso), 0f, false, null)
				}
				continue
			}
			val primera = lineas[0]
			val sigue = actual?.let { unir && continuaEnPagina(it, primera, cuerpo) } ?: false
			if (!sigue) cerrar()
			primerParrafo[p] = parrafos.size
			val espaciado = espaciadoTipico(lineas)
			val ancho = anchoTipico(lineas)
			for ((k, l) in lineas.withIndex()) {
				val a = actual
				if (a == null) {
					nuevo(l)
				} else if (k == 0) {
					unirLinea(a, l)
				} else if (!unir || corta(lineas[k - 1], l, espaciado, ancho)) {
					cerrar()
					nuevo(l)
				} else {
					unirLinea(a, l)
				}
			}
		}
		cerrar()

		// 5. Encabezados: letra más grande que el texto normal, o negrita sola y corta
		if (cuerpo > 0f) {
			val candidatos = ArrayList<Parrafo>()
			for (p in parrafos) {
				if (p.ultima == null) continue
				val t = p.texto.trim()
				if (t.length >= 150 || t.endsWith('.') || t.endsWith(',') || t.endsWith(';') || t.none { it.isLetterOrDigit() }) continue
				val grande = p.tamano >= cuerpo * 1.15f
				val negritaSola = p.negrita && p.tamano >= cuerpo * 1.05f && t.length < 80 && p.lineas == 1
				if (grande || negritaSola) candidatos += p
			}
			val textuales = parrafos.count { it.ultima != null }
			// Demasiados "títulos": el tamaño normal no se ha detectado bien
			if (candidatos.isNotEmpty() && (textuales < 10 || candidatos.size * 10 <= textuales * 4)) {
				val tamanos = candidatos.map { it.tamano }.distinct().sortedDescending()
				for (p in candidatos) p.nivel = (tamanos.indexOf(p.tamano) + 1).coerceIn(1, 6)
			}
		}

		// 6. Bloques y páginas
		val primerBloque = IntArray(total)
		var pagina = 0
		fun abrirPaginasHasta(k: Int) {
			while (pagina < total && primerParrafo[pagina] <= k) {
				ctx.comprobar()
				primerBloque[pagina] = c.tamano
				c.pagina(etiqueta(pagina), c.tamano)
				pagina++
				ctx.progreso(80 + pagina * 20 / total)
			}
		}
		for ((k, p) in parrafos.withIndex()) {
			abrirPaginasHasta(k)
			val texto = Textos.unirEspacios(p.texto.toString()).trim()
			if (p.nivel > 0) c.encabezado(texto, p.nivel) else c.parrafo(texto)
		}
		abrirPaginasHasta(Int.MAX_VALUE)

		// 7. Índice desde los marcadores
		runCatching { marcadores(doc, c, primerBloque) }
		return c.construir()
	}

	// --- Metadatos ---------------------------------------------------------------------

	private fun metadatos(doc: PDDocument, c: ConstructorDocumento) {
		val info = runCatching { doc.documentInformation }.getOrNull() ?: return
		info.title?.let { PREFIJO_TITULO.replace(Textos.limpiar(it), "") }?.takeIf { it.isNotBlank() }?.let { c.titulo = it }
		info.author?.let { Textos.limpiar(it) }?.takeIf { it.isNotBlank() }?.let {
			c.autor = it
			c.metadatos["Autor"] = it
		}
		info.subject?.let { c.metadatos["Tema"] = Textos.limpiar(it) }
		info.keywords?.let { c.metadatos["Palabras clave"] = Textos.limpiar(it) }
		info.creator?.let { c.metadatos["Creador"] = Textos.limpiar(it) }
		runCatching { info.creationDate }.getOrNull()?.let {
			c.metadatos["Fecha"] = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(it.time)
		}
		runCatching { doc.documentCatalog.language }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
			c.idioma = it.trim()
			c.metadatos["Idioma"] = c.idioma
		}
	}

	/** Recorre los marcadores (outline) y crea el índice apuntando al bloque de la página. */
	private fun marcadores(doc: PDDocument, c: ConstructorDocumento, primerBloque: IntArray) {
		val raiz = doc.documentCatalog.documentOutline ?: return
		val total = primerBloque.size
		val indicePagina = IdentityHashMap<COSDictionary, Int>()
		doc.pages.forEachIndexed { i, pg -> indicePagina[pg.cosObject] = i }
		val hayEncabezados = c.tieneEncabezados()
		val vistos = IdentityHashMap<COSDictionary, Boolean>()
		var cuenta = 0
		fun recorrer(nodo: PDOutlineNode, nivel: Int) {
			var siguiente: PDOutlineItem? = nodo.firstChild
			while (cuenta < 10_000) {
				val item = siguiente ?: break
				if (vistos.put(item.cosObject, true) != null) break
				cuenta++
				val titulo = Textos.limpiar(item.title.orEmpty())
				val destino = runCatching { item.findDestinationPage(doc) }.getOrNull()
				val idx = destino?.let { indicePagina[it.cosObject] }
				if (titulo.isNotEmpty() && idx != null) {
					val inicio = primerBloque[idx]
					val fin = minOf(if (idx + 1 < total) maxOf(primerBloque[idx + 1], inicio + 1) else c.tamano, inicio + 12, c.tamano)
					var bloque = inicio
					for (b in inicio until fin) {
						if (AyudaBinarios.parecidos(c.textoDe(b).orEmpty(), titulo)) {
							bloque = b
							if (!hayEncabezados) c.cambiarAEncabezado(b, nivel)
							break
						}
					}
					c.indice(titulo, nivel, bloque)
				}
				if (nivel < 12) recorrer(item, nivel + 1)
				siguiente = item.nextSibling
			}
		}
		recorrer(raiz, 1)
	}

	// --- Líneas y párrafos -------------------------------------------------------------

	/** Las dos líneas de arriba y las dos de abajo de una página (por posición). */
	private fun extremos(lineas: List<Linea>): Set<Linea> {
		if (lineas.size <= 4) return lineas.toSet()
		val orden = lineas.sortedBy { it.y }
		return setOf(orden[0], orden[1], orden[orden.size - 2], orden[orden.size - 1])
	}

	private fun clave(texto: String): String? {
		val k = ESPACIOS.replace(DIGITOS.replace(texto.lowercase(), "#"), " ").trim()
		return k.takeIf { it.isNotEmpty() && it.length <= 200 }
	}

	/** El tamaño de letra más usado (por caracteres): el del texto normal. 0 si no hay texto. */
	private fun tamanoNormal(paginas: List<List<Linea>>): Float {
		val histograma = HashMap<Float, Int>()
		for (lineas in paginas) for (l in lineas) histograma[l.tamano] = (histograma[l.tamano] ?: 0) + l.texto.length
		return histograma.maxByOrNull { it.value }?.key ?: 0f
	}

	private fun quitarCabecerasYPies(paginas: List<List<Linea>>, cuerpo: Float): List<List<Linea>> {
		val total = paginas.size
		val repetidas = HashSet<String>()
		if (total >= 3) {
			val cuenta = HashMap<String, Int>()
			for (lineas in paginas) {
				val claves = HashSet<String>()
				for (l in extremos(lineas)) clave(l.texto)?.let { claves += it }
				for (k in claves) cuenta[k] = (cuenta[k] ?: 0) + 1
			}
			val umbral = maxOf(3, (total + 2) / 3)
			for ((k, n) in cuenta) if (n >= umbral) repetidas += k
		}
		return paginas.map { lineas ->
			val ext = extremos(lineas)
			lineas.filter { l ->
				val numero = NUMERO_PAGINA.matches(l.texto.trim())
				// Un número suelto de letra más grande que el texto es un título («I», «IV», «12»), no el número de página
				val titulo = numero && cuerpo > 0f && l.tamano >= cuerpo * 1.15f
				!(l in ext && !titulo && (numero || clave(l.texto)?.let { it in repetidas } == true))
			}
		}
	}

	private fun mediana(valores: MutableList<Float>): Float? {
		if (valores.isEmpty()) return null
		valores.sort()
		return valores[valores.size / 2]
	}

	private fun espaciadoTipico(lineas: List<Linea>): Float {
		val deltas = ArrayList<Float>()
		for (i in 1 until lineas.size) {
			val d = lineas[i].y - lineas[i - 1].y
			val t = maxOf(lineas[i].tamano, 1f)
			if (d > t * 0.3f && d < t * 3f) deltas += d
		}
		return mediana(deltas) ?: ((lineas.maxOfOrNull { it.tamano } ?: 10f) * 1.25f)
	}

	private fun anchoTipico(lineas: List<Linea>): Float {
		val anchos = lineas.filter { it.texto.length >= 25 }.map { it.x1 - it.x0 }.toMutableList()
		return mediana(anchos) ?: (lineas.maxOfOrNull { it.x1 - it.x0 } ?: 0f)
	}

	private fun terminaFrase(t: CharSequence): Boolean {
		var i = t.length - 1
		while (i >= 0 && t[i] in "\"”’'»)] ") i--
		return i >= 0 && t[i] in ".!?…:"
	}

	/** ¿Empieza un párrafo nuevo en la línea l (después de la línea p)? */
	private fun corta(p: Linea, l: Linea, espaciado: Float, ancho: Float): Boolean {
		val ref = maxOf(p.tamano, l.tamano, 1f)
		if (abs(l.tamano - p.tamano) > maxOf(1f, 0.15f * minOf(l.tamano, p.tamano))) return true
		val d = l.y - p.y
		if (d > espaciado * 1.4f + 0.5f && d > ref * 0.5f) return true
		// Sube en la página (otra columna): solo si la anterior acaba una frase
		if (d < -ref * 0.5f) return terminaFrase(p.texto)
		if (l.negrita != p.negrita) return true
		if (LISTA.containsMatchIn(l.texto)) return true
		if (terminaFrase(p.texto)) {
			if (ancho > 0f && p.x1 - p.x0 < ancho * 0.85f) return true
			if (l.x0 > p.x0 + ref * 1.5f) return true
		}
		return false
	}

	/** ¿El párrafo de la página anterior sigue en la primera línea de esta? */
	private fun continuaEnPagina(p: Parrafo, l: Linea, cuerpo: Float): Boolean {
		val ultima = p.ultima ?: return false
		if (p.nivel > 0 || (cuerpo > 0f && p.tamano > cuerpo * 1.1f)) return false
		if (terminaFrase(ultima.texto) || abs(l.tamano - p.tamano) > 1f || l.negrita != p.negrita) return false
		if (LISTA.containsMatchIn(l.texto)) return false
		val primero = l.texto.firstOrNull() ?: return false
		return primero.isLowerCase() || ultima.texto.endsWith("-")
	}

	/** Une una línea al párrafo quitando los guiones de final de línea ("pala-" + "bra"). */
	private fun unirLinea(p: Parrafo, l: Linea) {
		val sb = p.texto
		val siguiente = l.texto
		val n = sb.length
		if (n >= 2 && sb[n - 1] == '-' && sb[n - 2].isLetter()) {
			val primero = siguiente.firstOrNull()
			if (primero != null && primero.isLowerCase()) sb.setLength(n - 1)
			sb.append(siguiente)
		} else {
			if (n > 0) sb.append(' ')
			sb.append(siguiente)
		}
		p.lineas++
		p.ultima = l
	}

	private val NADA = object : Writer() {
		override fun write(cbuf: CharArray, off: Int, len: Int) {}
		override fun flush() {}
		override fun close() {}
	}

	/**
	 * PDFTextStripper que guarda las líneas de cada página con su tamaño de
	 * letra dominante, si son negrita y su posición.
	 */
	private class Extractor(private val alEmpezarPagina: (Int) -> Unit) : PDFTextStripper() {
		val paginas = ArrayList<List<Linea>>()
		private var actual = ArrayList<Linea>()
		private val texto = StringBuilder()
		private val tamanos = HashMap<Float, Int>()
		private var caracteres = 0
		private var negritas = 0
		private var y = 0f
		private var hayY = false
		private var x0 = Float.MAX_VALUE
		private var x1 = -Float.MAX_VALUE
		private val fuentesNegrita = IdentityHashMap<PDFont, Boolean>()

		init {
			sortByPosition = false
		}

		override fun startPage(page: PDPage?) {
			alEmpezarPagina(currentPageNo)
			actual = ArrayList()
			reiniciarLinea()
			super.startPage(page)
		}

		override fun endPage(page: PDPage?) {
			cerrarLinea()
			// Las páginas sin contenido no pasan por aquí: se rellenan vacías para que la numeración no se mueva
			while (paginas.size < currentPageNo - 1) paginas += emptyList<Linea>()
			paginas += actual
			super.endPage(page)
		}

		override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
			if (text != null) texto.append(text)
			if (textPositions == null) return
			for (tp in textPositions) {
				val u = tp.unicode
				if (u.isNullOrBlank()) continue
				var tam = abs(tp.yScale)
				if (tam < 0.5f || tam > 500f) tam = tp.fontSizeInPt
				val clave = (tam * 2f).roundToInt() / 2f
				tamanos[clave] = (tamanos[clave] ?: 0) + u.length
				caracteres += u.length
				if (esNegrita(tp.font)) negritas += u.length
				if (!hayY) {
					y = tp.yDirAdj
					hayY = true
				}
				x0 = minOf(x0, tp.xDirAdj)
				x1 = maxOf(x1, tp.xDirAdj + tp.widthDirAdj)
			}
		}

		override fun writeWordSeparator() {
			texto.append(' ')
		}

		override fun writeLineSeparator() {
			cerrarLinea()
		}

		private fun esNegrita(f: PDFont?): Boolean {
			if (f == null) return false
			return fuentesNegrita.getOrPut(f) {
				val nombre = f.name.orEmpty().lowercase()
				val d = runCatching { f.fontDescriptor }.getOrNull()
				nombre.contains("bold") || nombre.contains("black") || nombre.contains("heavy") ||
					nombre.contains("semibold") || nombre.contains("demi") ||
					(d != null && (d.isForceBold || d.fontWeight >= 700f))
			}
		}

		private fun cerrarLinea() {
			var crudo = texto.toString().trimEnd()
			// Guion blando al final: se trata como guion de partición
			if (crudo.endsWith('­')) crudo = crudo.dropLast(1) + "-"
			val t = Textos.unirEspacios(Textos.limpiar(crudo)).trim()
			if (t.isNotEmpty() && caracteres > 0) {
				var mejor = 0f
				var max = -1
				for ((k, v) in tamanos) if (v > max) {
					max = v
					mejor = k
				}
				actual += Linea(t, mejor, negritas * 2 > caracteres, y, x0, x1)
			}
			reiniciarLinea()
		}

		private fun reiniciarLinea() {
			texto.setLength(0)
			tamanos.clear()
			caracteres = 0
			negritas = 0
			hayY = false
			y = 0f
			x0 = Float.MAX_VALUE
			x1 = -Float.MAX_VALUE
		}
	}
}
