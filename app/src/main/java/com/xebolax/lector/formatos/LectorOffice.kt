package com.xebolax.lector.formatos

import android.util.Xml
import com.xebolax.lector.R
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
import org.xmlpull.v1.XmlPullParserException
import java.io.InputStream

/** Ayudas comunes a DOCX y ODT. */
/** Texto de las imágenes sin descripción (igual en todos los formatos). */
const val SIN_DESCRIPCION = "Imagen sin descripción"

/**
 * Los XML de Office (DOCX, XLSX, PPTX, ODT…) nunca declaran entidades. Si
 * uno trae «<!ENTITY» antes del elemento raíz, es un archivo manipulado
 * (entidades que se expanden hasta llenar la memoria, «billion laughs») y no
 * se lee. Solo se mira hasta que empieza el elemento raíz.
 */
internal class SinEntidadesXml(entrada: InputStream) : java.io.FilterInputStream(entrada) {
	private var comprobando = true
	private val ventana = StringBuilder()

	private fun ver(b: Int) {
		if (!comprobando || b <= 0) return
		val c = b.toChar()
		ventana.append(c)
		if (ventana.length > 16) ventana.deleteCharAt(0)
		if (ventana.endsWith("<!ENTITY", ignoreCase = true)) throw ExcepcionFormato(R.string.fmt_manipulado)
		// Empieza el elemento raíz («<w:document…»): ya no puede haber declaraciones
		if (ventana.length >= 2 && ventana[ventana.length - 2] == '<' && c.isLetter()) comprobando = false
	}

	override fun read(): Int = super.read().also { ver(it) }

	override fun read(b: ByteArray, off: Int, len: Int): Int {
		val n = super.read(b, off, len)
		if (comprobando) for (i in off until off + maxOf(n, 0)) ver(b[i].toInt() and 0xFF)
		return n
	}
}

private object AyudaOffice {

	class Relacion(val tipo: String, val destino: String, val externa: Boolean)

	fun parser(entrada: InputStream): XmlPullParser = Xml.newPullParser().apply {
		setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
		setInput(SinEntidadesXml(entrada), null)
	}

	/** Nombre sin prefijo ("w:p" → "p"). */
	fun local(nombre: String?): String = nombre?.substringAfter(':').orEmpty()

	/** Atributo por su nombre sin prefijo. */
	fun atributo(p: XmlPullParser, local: String): String? {
		for (i in 0 until p.attributeCount) {
			if (p.getAttributeName(i).substringAfter(':') == local) return p.getAttributeValue(i)
		}
		return null
	}

	/** Relaciones de un archivo del paquete (id → tipo y destino resuelto respecto al archivo). */
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

	/** Título, autor, idioma... de docProps/core.xml. */
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

	/**
	 * Filas de una tabla → bloques, como ConversorHtml: resumen, fila de
	 * encabezado y filas "Columna: valor; ...". Una sola columna: párrafos.
	 */
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
		val conEncabezado = encabezado && utiles.size >= 2
		val cabeceras = if (conEncabezado) utiles[0] else emptyList()
		for ((i, fila) in utiles.withIndex()) {
			val linea = when {
				i == 0 && conEncabezado -> fila.joinToString(" | ")
				opciones.tablasConEncabezados && cabeceras.isNotEmpty() -> fila.mapIndexed { j, valor ->
					val cabecera = cabeceras.getOrNull(j).orEmpty()
					if (cabecera.isBlank() || valor.isBlank()) valor else "$cabecera: $valor"
				}.filter { it.isNotBlank() }.joinToString("; ")
				else -> fila.joinToString(" | ")
			}
			c.bloque(TipoBloque.FILA_TABLA, linea.ifBlank { "(fila vacía)" }, marcas = if (i == 0 && conEncabezado) Marca.ENCABEZADO_TABLA else 0)
		}
		if (c.tamano > primero) c.anadirMarca(primero, Marca.INICIO_TABLA)
	}

	private val HIPERVINCULO = Regex("""HYPERLINK\s+"([^"]*)"""", RegexOption.IGNORE_CASE)
	private val HIPERVINCULO_SIN_COMILLAS = Regex("""HYPERLINK\s+([^\s"\\]+)""", RegexOption.IGNORE_CASE)
	private val LOCAL = Regex("""\\l\s+"([^"]+)"""")
	private val REFERENCIA = Regex("""^\s*(?:REF|PAGEREF|NOTEREF)\s+([^\s\\]+)""", RegexOption.IGNORE_CASE)

	/** Destino de un campo de Word: HYPERLINK "url", HYPERLINK \l "marcador", REF marcador. */
	fun destinoCampo(instruccion: String): String? {
		val t = instruccion.trim()
		if (t.startsWith("HYPERLINK", ignoreCase = true)) {
			val local = LOCAL.find(t)?.groupValues?.get(1)
			val url = HIPERVINCULO.find(t)?.groupValues?.get(1)?.takeIf { it.isNotBlank() && !t.contains("\\l \"$it\"") }
				?: HIPERVINCULO_SIN_COMILLAS.find(t)?.groupValues?.get(1)
			return when {
				url != null && local != null -> "${normalizarUrl(url)}#$local"
				url != null -> normalizarUrl(url)
				local != null -> "#$local"
				else -> null
			}
		}
		return REFERENCIA.find(t)?.groupValues?.get(1)?.let { "#$it" }
	}

	fun normalizarUrl(url: String): String = if (url.startsWith("www.", ignoreCase = true)) "http://$url" else url

	fun romano(numero: Int): String {
		if (numero <= 0 || numero >= 4000) return numero.toString()
		val valores = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
		val simbolos = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
		val sb = StringBuilder()
		var n = numero
		for (i in valores.indices) while (n >= valores[i]) {
			sb.append(simbolos[i])
			n -= valores[i]
		}
		return sb.toString()
	}

	/** 1 → a, 26 → z, 27 → aa (como Word). */
	fun letras(numero: Int): String {
		if (numero <= 0) return numero.toString()
		val letra = 'a' + (numero - 1) % 26
		return letra.toString().repeat((numero - 1) / 26 + 1)
	}

	fun esOle(cabecera: ByteArray) = cabecera.size >= 4 && cabecera[0] == 0xD0.toByte() && cabecera[1] == 0xCF.toByte() &&
		cabecera[2] == 0x11.toByte() && cabecera[3] == 0xE0.toByte()

	fun esZip(cabecera: ByteArray) = cabecera.size >= 2 && cabecera[0] == 'P'.code.toByte() && cabecera[1] == 'K'.code.toByte()
}

/**
 * Word (DOCX, DOCM, DOTX): se lee word/document.xml en flujo (puede tener
 * decenas de MB) con XmlPullParser.
 *
 * - Encabezados por el nombre del estilo ("heading 1", "Título 1"...), por
 *   su nivel de esquema (w:outlineLvl) o por el estilo "Title".
 * - Listas con numbering.xml: viñetas sin prefijo y números "1. ", "a) "...
 * - Tablas: una fila por bloque; las anidadas se aplanan en su celda.
 * - Notas al pie y al final: llamada "[N]" enlazada y las notas al final
 *   bajo el encabezado "Notas".
 * - Hipervínculos externos, marcadores internos y campos HYPERLINK/REF.
 */
object LectorDocx : LectorFormato {

	private class Estilo(val nombre: String, val esquema: Int?, val basadoEn: String?, val numId: String?, val ilvl: Int?)

	private class Nivel(val formato: String, val inicio: Int, val texto: String)

	private val PORCENTAJE = Regex("%([1-9])")

	/** Numeración de las listas (numbering.xml) y sus contadores. */
	private class Numeracion {
		val abstractos = HashMap<String, HashMap<Int, Nivel>>()
		val numeros = HashMap<String, String>()
		val inicios = HashMap<String, HashMap<Int, Int>>()
		private val contadores = HashMap<String, IntArray>()

		fun nivel(numId: String, ilvl: Int): Nivel? = numeros[numId]?.let { abstractos[it] }?.get(ilvl)

		private fun inicio(numId: String, ilvl: Int): Int = inicios[numId]?.get(ilvl) ?: nivel(numId, ilvl)?.inicio ?: 1

		/** Prefijo del elemento ("1. ", "a) "...) o "" para las viñetas. Avanza los contadores. */
		fun prefijo(numId: String, ilvl: Int): String {
			val l = ilvl.coerceIn(0, 8)
			val cont = contadores.getOrPut(numId) { IntArray(9) { Int.MIN_VALUE } }
			cont[l] = if (cont[l] == Int.MIN_VALUE) inicio(numId, l) else cont[l] + 1
			for (k in l + 1 until 9) cont[k] = Int.MIN_VALUE
			val actual = nivel(numId, l) ?: return ""
			if (actual.formato == "bullet" || actual.formato == "none" || actual.texto.isBlank()) return ""
			val r = PORCENTAJE.replace(actual.texto) { m ->
				val k = m.groupValues[1].toInt() - 1
				val v = if (cont[k] == Int.MIN_VALUE) inicio(numId, k) else cont[k]
				formatear(v, nivel(numId, k)?.formato ?: "decimal")
			}.trim()
			return if (r.isEmpty()) "" else "$r "
		}

		private fun formatear(n: Int, formato: String): String = when (formato) {
			"decimalZero" -> if (n in 0..9) "0$n" else n.toString()
			"lowerLetter" -> AyudaOffice.letras(n)
			"upperLetter" -> AyudaOffice.letras(n).uppercase()
			"lowerRoman" -> AyudaOffice.romano(n).lowercase()
			"upperRoman" -> AyudaOffice.romano(n)
			else -> n.toString()
		}
	}

	private class Llamada(val ancla: String, val etiqueta: String, val id: String, val pie: Boolean)

	private class TablaDocx {
		val filas = ArrayList<List<String>>()
		var fila = ArrayList<String>()
		val celda = StringBuilder()
		var encabezado = false
	}

	private class Campo(val instruccion: StringBuilder = StringBuilder(), var inicio: Int = -1, var parrafo: Int = -1)

	private val TITULO_NOMBRE = Regex(
		"""^(heading|título|titulo|titre|überschrift|titolo|kop|nagłówek|rubrik|otsikko|overskrift|cabeçalho|заголовок)\s*(\d)$""",
		RegexOption.IGNORE_CASE,
	)
	private val TITULO_ID = Regex("""^(heading|ttulo|titulo|título|titre|berschrift|überschrift|titolo|kop)(\d)$""", RegexOption.IGNORE_CASE)
	private val CITA = Regex("""(?i)quote|cita|citation|zitat""")

	/** Elementos que se saltan enteros (propiedades, texto borrado, alternativas repetidas...). */
	private val SALTAR = setOf(
		"del", "moveFrom", "Fallback", "sdtPr", "sdtEndPr", "rPr", "sectPr", "tblPr", "tblGrid", "tcPr", "pPrChange",
		"trPrChange", "tcPrChange", "tblPrChange", "tblGridChange", "sectPrChange", "numberingChange", "rt",
		"customXmlPr", "smartTagPr", "footnotePr", "endnotePr",
	)

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val cabecera = runCatching { fuente.cabecera(8) }.getOrDefault(ByteArray(0))
		// Un .docx que en realidad es un Word antiguo (OLE): se lee como tal (si está cifrado, lo dice)
		if (AyudaOffice.esOle(cabecera)) return LectorDocAntiguo.leer(fuente, ctx)
		val c = ctx.nuevoConstructor()
		fuente.contenedor().use { z ->
			val raiz = AyudaOffice.relaciones(z, "")
			val principal = raiz.values.firstOrNull { it.tipo.endsWith("/officeDocument") }?.let { z.buscar(it.destino) }
				?: z.buscar("word/document.xml")
				?: throw ExcepcionFormato(R.string.fmt_falta_parte)
			val rels = AyudaOffice.relaciones(z, principal)
			fun rutaDe(tipo: String, defecto: String): String? =
				rels.values.firstOrNull { it.tipo.endsWith("/$tipo") }?.let { z.buscar(it.destino) } ?: z.buscar(defecto)

			AyudaOffice.metadatosCore(z, c)
			val estilos = leerEstilos(z, rutaDe("styles", "word/styles.xml"), c)
			val numeracion = leerNumeracion(z, rutaDe("numbering", "word/numbering.xml"))
			val notasPie = leerNotas(z, rutaDe("footnotes", "word/footnotes.xml"))
			val notasFin = leerNotas(z, rutaDe("endnotes", "word/endnotes.xml"))
			val externos = HashMap<String, String>()
			for ((id, r) in rels) if (r.externa) externos[id] = AyudaOffice.normalizarUrl(r.destino)
			// Imágenes del documento: id de relación → ruta dentro del .docx (para describirlas)
			val imagenes = rels.filter { !it.value.externa && it.value.tipo.endsWith("/image") }.mapValues { it.value.destino }
			ctx.progreso(10)

			val cuerpo = Cuerpo(c, ctx, estilos, numeracion, externos, notasPie.isNotEmpty(), imagenes)
			try {
				z.abrir(principal)?.use { cuerpo.leer(it) }
					?: throw ExcepcionFormato(R.string.fmt_falta_parte)
			} catch (e: XmlPullParserException) {
				if (c.estaVacio()) throw ExcepcionFormato(R.string.fmt_danado, e)
				c.avisos += "El documento está dañado: puede faltar texto al final."
			}
			ctx.progreso(90)

			if (cuerpo.llamadas.isNotEmpty()) {
				c.encabezado("Notas", 1)
				for (ll in cuerpo.llamadas.values) {
					val texto = (if (ll.pie) notasPie else notasFin)[ll.id] ?: continue
					c.ancla(ll.ancla)
					c.bloque(TipoBloque.NOTA, "${ll.etiqueta}. $texto")
				}
			}
		}
		if (c.titulo.isBlank()) c.titulo = fuente.nombre.substringBeforeLast('.')
		return Contenido.Texto(c.construir())
	}

	// --- Estilos, numeración y notas ---------------------------------------------------

	private fun leerEstilos(z: Contenedor, ruta: String?, c: ConstructorDocumento): Map<String, Estilo> {
		val mapa = HashMap<String, Estilo>()
		val entrada = ruta?.let { z.abrir(it) } ?: return mapa
		runCatching {
			entrada.use {
				val p = AyudaOffice.parser(it)
				var id: String? = null
				var nombre = ""
				var esquema: Int? = null
				var basado: String? = null
				var numId: String? = null
				var ilvl: Int? = null
				var enDefecto = false
				while (true) {
					when (p.next()) {
						XmlPullParser.END_DOCUMENT -> break
						XmlPullParser.START_TAG -> {
							val valor = AyudaOffice.atributo(p, "val")
							when (AyudaOffice.local(p.name)) {
								"docDefaults" -> enDefecto = true
								"lang" -> if (enDefecto && c.idioma.isBlank() && !valor.isNullOrBlank()) c.idioma = valor
								"style" -> {
									id = if (AyudaOffice.atributo(p, "type") == "paragraph") AyudaOffice.atributo(p, "styleId") else null
									nombre = ""
									esquema = null
									basado = null
									numId = null
									ilvl = null
								}
								"name" -> if (id != null) nombre = valor.orEmpty()
								"basedOn" -> if (id != null) basado = valor
								"outlineLvl" -> if (id != null) esquema = valor?.toIntOrNull()
								"numId" -> if (id != null) numId = valor
								"ilvl" -> if (id != null) ilvl = valor?.toIntOrNull()
							}
						}
						XmlPullParser.END_TAG -> when (AyudaOffice.local(p.name)) {
							"docDefaults" -> enDefecto = false
							"style" -> {
								id?.let { k -> mapa[k] = Estilo(nombre, esquema, basado, numId, ilvl) }
								id = null
							}
						}
					}
				}
			}
		}
		return mapa
	}

	private fun leerNumeracion(z: Contenedor, ruta: String?): Numeracion {
		val n = Numeracion()
		val entrada = ruta?.let { z.abrir(it) } ?: return n
		runCatching {
			entrada.use {
				val p = AyudaOffice.parser(it)
				var abstracto: String? = null
				var niveles: HashMap<Int, Nivel>? = null
				var ilvl = -1
				var formato = "decimal"
				var inicio = 1
				var texto = ""
				var numId: String? = null
				var sustituido = -1
				while (true) {
					when (p.next()) {
						XmlPullParser.END_DOCUMENT -> break
						XmlPullParser.START_TAG -> {
							val valor = AyudaOffice.atributo(p, "val")
							when (AyudaOffice.local(p.name)) {
								"abstractNum" -> {
									abstracto = AyudaOffice.atributo(p, "abstractNumId")
									niveles = HashMap()
									abstracto?.let { a -> n.abstractos[a] = niveles!! }
								}
								"lvl" -> if (abstracto != null) {
									ilvl = AyudaOffice.atributo(p, "ilvl")?.toIntOrNull() ?: 0
									formato = "decimal"
									inicio = 1
									texto = ""
								}
								"start" -> if (abstracto != null) inicio = valor?.toIntOrNull() ?: 1
								"numFmt" -> if (abstracto != null) formato = valor ?: "decimal"
								"lvlText" -> if (abstracto != null) texto = valor.orEmpty()
								"num" -> numId = AyudaOffice.atributo(p, "numId")
								"abstractNumId" -> {
									val id = numId
									if (id != null && valor != null) n.numeros[id] = valor
								}
								"lvlOverride" -> sustituido = AyudaOffice.atributo(p, "ilvl")?.toIntOrNull() ?: -1
								"startOverride" -> {
									val id = numId
									val v = valor?.toIntOrNull()
									if (id != null && v != null && sustituido >= 0) n.inicios.getOrPut(id) { HashMap() }[sustituido] = v
								}
							}
						}
						XmlPullParser.END_TAG -> when (AyudaOffice.local(p.name)) {
							"lvl" -> if (abstracto != null && ilvl >= 0) niveles?.set(ilvl, Nivel(formato, inicio, texto))
							"abstractNum" -> {
								abstracto = null
								niveles = null
							}
							"num" -> numId = null
						}
					}
				}
			}
		}
		return n
	}

	/** Texto de cada nota (id → texto) de footnotes.xml o endnotes.xml. */
	private fun leerNotas(z: Contenedor, ruta: String?): Map<String, String> {
		val mapa = HashMap<String, String>()
		val entrada = ruta?.let { z.abrir(it) } ?: return mapa
		runCatching {
			entrada.use {
				val p = AyudaOffice.parser(it)
				var id: String? = null
				val sb = StringBuilder()
				var enT = false
				var enPPr = false
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
							val nombre = AyudaOffice.local(p.name)
							when (nombre) {
								"footnote", "endnote" -> {
									val tipo = AyudaOffice.atributo(p, "type")
									id = if (tipo == null || tipo == "normal") AyudaOffice.atributo(p, "id") else null
									sb.setLength(0)
								}
								"p" -> if (sb.isNotEmpty()) sb.append(' ')
								"pPr" -> enPPr = true
								"t" -> enT = true
								"tab" -> if (!enPPr) sb.append(' ')
								"br", "cr" -> sb.append(' ')
								"noBreakHyphen" -> sb.append('-')
								else -> if (nombre in SALTAR) saltarHasta = p.depth
							}
						}
						XmlPullParser.TEXT -> if (enT) sb.append(p.text)
						XmlPullParser.END_TAG -> when (AyudaOffice.local(p.name)) {
							"t" -> enT = false
							"pPr" -> enPPr = false
							"footnote", "endnote" -> {
								id?.let { k -> mapa[k] = Textos.unirEspacios(sb.toString()).trim() }
								id = null
							}
						}
					}
				}
			}
		}
		return mapa
	}

	// --- Cuerpo del documento ------------------------------------------------------------

	private class Cuerpo(
		val c: ConstructorDocumento,
		val ctx: ContextoLectura,
		val estilos: Map<String, Estilo>,
		val numeracion: Numeracion,
		/** id de relación → dirección externa. */
		val externos: Map<String, String>,
		val hayNotasPie: Boolean,
		/** id de relación → ruta de la imagen dentro del .docx. */
		val imagenes: Map<String, String> = emptyMap(),
	) {
		val llamadas = LinkedHashMap<String, Llamada>()
		private var contadorPie = 0
		private var contadorFin = 0

		private val texto = StringBuilder()
		private val enlaces = ArrayList<Enlace>()
		private var marcas = 0
		private var soloImagen = false
		private var hayTexto = false
		private var trasImagen = false
		/** Imagen del párrafo (ruta dentro del .docx) y dónde empieza "Imagen sin descripción". */
		private var imagenParrafo: String? = null
		private var sinDescripcion = -1
		/** Hay una imagen (docPr) esperando su archivo (blip); conDescripcion: ya se escribió su descripción. */
		private var esperandoBlip = false
		private var conDescripcion = false
		private var estiloP: String? = null
		private var esquemaP: Int? = null
		private var numIdP: String? = null
		private var ilvlP: Int? = null

		private var profParrafo = 0
		private var parrafos = 0
		private var tabla: TablaDocx? = null
		private var tablasAnidadas = 0
		private val vinculos = ArrayDeque<Pair<Int, String?>>()
		private val campos = ArrayDeque<Campo>()
		private var enT = false
		private var enInstr = false
		private var enPPr = false
		private var ultimoTipo: TipoBloque? = null
		private val cacheNivel = HashMap<String, Int>()

		fun leer(entrada: InputStream) {
			val p = AyudaOffice.parser(entrada)
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
						val nombre = AyudaOffice.local(p.name)
						if (nombre in SALTAR) saltarHasta = p.depth else inicio(nombre, p)
					}
					XmlPullParser.TEXT -> {
						if (enT) anadirTexto(p.text)
						else if (enInstr) campos.lastOrNull()?.instruccion?.append(p.text)
					}
					XmlPullParser.END_TAG -> fin(AyudaOffice.local(p.name))
				}
			}
			tabla?.let {
				tabla = null
				emitirTabla(it)
			}
		}

		private fun inicio(nombre: String, p: XmlPullParser) {
			fun atr(local: String) = AyudaOffice.atributo(p, local)
			when (nombre) {
				"p" -> {
					profParrafo++
					if (profParrafo == 1) nuevoParrafo() else separar()
				}
				"pPr" -> enPPr = true
				"pStyle" -> if (enPPr && profParrafo == 1) estiloP = atr("val")
				"outlineLvl" -> if (enPPr && profParrafo == 1) esquemaP = atr("val")?.toIntOrNull()
				"numId" -> if (enPPr && profParrafo == 1) numIdP = atr("val")
				"ilvl" -> if (enPPr && profParrafo == 1) ilvlP = atr("val")?.toIntOrNull()
				"t" -> enT = true
				"instrText" -> enInstr = true
				"tab" -> if (!enPPr) anadirTexto(" ")
				"ptab" -> anadirTexto(" ")
				"br" -> {
					val tipo = atr("type")
					if (tipo == null || tipo == "textWrapping") anadirTexto("\n")
				}
				"cr" -> anadirTexto("\n")
				"noBreakHyphen" -> anadirTexto("-")
				"sym" -> {
					val codigo = atr("char")?.toIntOrNull(16)
					if (codigo != null && codigo >= 0x20 && codigo !in 0xF000..0xF0FF) anadirTexto(codigo.toChar().toString())
				}
				"hyperlink" -> {
					val id = atr("id")
					val ancla = atr("anchor")?.takeIf { it.isNotBlank() }
					val externo = id?.let { externos[it] }
					val destino = when {
						externo != null -> if (ancla != null) "$externo#$ancla" else externo
						ancla != null -> "#$ancla"
						else -> null
					}
					vinculos.addLast(texto.length to destino)
				}
				"bookmarkStart" -> atr("name")?.takeIf { it.isNotBlank() && it != "_GoBack" }?.let { c.ancla("#$it") }
				"fldChar" -> when (atr("fldCharType")) {
					"begin" -> campos.addLast(Campo())
					"separate" -> campos.lastOrNull()?.let {
						it.inicio = texto.length
						it.parrafo = parrafos
					}
					"end" -> campos.removeLastOrNull()?.let { cerrarCampo(it) }
				}
				"fldSimple" -> campos.addLast(Campo(StringBuilder(atr("instr").orEmpty()), texto.length, parrafos))
				"footnoteReference" -> llamada(atr("id"), true)
				"endnoteReference" -> llamada(atr("id"), false)
				"docPr" -> {
					val descripcion = atr("descr")?.takeIf { it.isNotBlank() } ?: atr("title")
					esperandoBlip = true
					conDescripcion = !descripcion.isNullOrBlank()
					if (conDescripcion) imagen(descripcion)
				}
				"blip" -> if (esperandoBlip) {
					esperandoBlip = false
					val ruta = atr("embed")?.let { imagenes[it] }
					if (ruta != null) {
						if (!conDescripcion) imagenSinDescripcion()
						if (imagenParrafo == null) imagenParrafo = ruta
					}
				}
				"shape" -> imagen(atr("alt"))
				"tbl" -> if (tabla == null && profParrafo == 0) tabla = TablaDocx() else tablasAnidadas++
				"tr" -> if (tablasAnidadas == 0) tabla?.let { it.fila = ArrayList() } else separar()
				"tc" -> if (tablasAnidadas == 0) tabla?.celda?.setLength(0) else separar()
				"tblHeader" -> {
					val t = tabla
					val valor = atr("val").orEmpty()
					if (tablasAnidadas == 0 && t != null && t.filas.isEmpty() && valor != "0" && valor != "false" && valor != "off") t.encabezado = true
				}
			}
		}

		private fun fin(nombre: String) {
			when (nombre) {
				"t" -> enT = false
				"instrText" -> enInstr = false
				"pPr" -> enPPr = false
				"hyperlink" -> {
					val vinculo = vinculos.removeLastOrNull()
					val destino = vinculo?.second
					if (vinculo != null && destino != null && texto.length > vinculo.first) enlaces.add(Enlace(vinculo.first, texto.length, destino))
				}
				"fldSimple" -> campos.removeLastOrNull()?.let { cerrarCampo(it) }
				"p" -> {
					profParrafo--
					if (profParrafo <= 0) {
						profParrafo = 0
						terminarParrafo()
					}
				}
				"tc" -> if (tablasAnidadas == 0) tabla?.let {
					it.fila.add(it.celda.toString().trim())
					it.celda.setLength(0)
				}
				"tr" -> if (tablasAnidadas == 0) tabla?.let {
					it.filas.add(it.fila)
					it.fila = ArrayList()
				}
				"tbl" -> if (tablasAnidadas > 0) tablasAnidadas-- else tabla?.let {
					tabla = null
					emitirTabla(it)
				}
			}
		}

		private fun nuevoParrafo() {
			texto.setLength(0)
			enlaces.clear()
			vinculos.clear()
			marcas = 0
			soloImagen = false
			hayTexto = false
			trasImagen = false
			imagenParrafo = null
			sinDescripcion = -1
			estiloP = null
			esquemaP = null
			numIdP = null
			ilvlP = null
			parrafos++
			if (parrafos % 200 == 0) ctx.comprobar()
		}

		private fun separar() {
			if (texto.isNotEmpty() && !texto.last().isWhitespace()) texto.append(' ')
		}

		private fun anadirTexto(s: String) {
			if (s.isEmpty()) return
			if (trasImagen && !s[0].isWhitespace()) separar()
			trasImagen = false
			texto.append(s)
			if (s.isNotBlank()) hayTexto = true
		}

		private fun imagen(descripcion: String?) {
			if (!ctx.opciones.leerImagenes || descripcion.isNullOrBlank()) return
			val d = Textos.unirEspacios(descripcion.replace('\n', ' ').replace('\r', ' ')).trim()
			val vacio = texto.isBlank()
			separar()
			texto.append(if (vacio) "Imagen: $d" else "[Imagen: $d]")
			if (vacio) soloImagen = true
			trasImagen = true
		}

		/** Imagen sin descripción: solo si está sola en su párrafo (si luego hay texto, se quita). */
		private fun imagenSinDescripcion() {
			if (!ctx.opciones.leerImagenes || texto.isNotBlank() || tabla != null) return
			sinDescripcion = texto.length
			texto.append(SIN_DESCRIPCION)
			soloImagen = true
			trasImagen = true
		}

		private fun llamada(id: String?, pie: Boolean) {
			if (id == null) return
			val ancla = (if (pie) "#footnote-" else "#endnote-") + id
			val ll = llamadas.getOrPut(ancla) {
				val etiqueta = if (pie) {
					(++contadorPie).toString()
				} else {
					val n = ++contadorFin
					if (hayNotasPie) AyudaOffice.romano(n).lowercase() else n.toString()
				}
				Llamada(ancla, etiqueta, id, pie)
			}
			val inicio = texto.length
			texto.append('[').append(ll.etiqueta).append(']')
			enlaces += Enlace(inicio, texto.length, ancla)
			marcas = marcas or Marca.LLAMADA_NOTA
			hayTexto = true
		}

		private fun cerrarCampo(campo: Campo) {
			if (campo.inicio < 0 || campo.parrafo != parrafos || texto.length <= campo.inicio) return
			val destino = AyudaOffice.destinoCampo(campo.instruccion.toString()) ?: return
			enlaces += Enlace(campo.inicio, texto.length, destino)
		}

		/** Nivel de encabezado de un estilo (0 si no lo es), siguiendo basedOn. */
		private fun nivelTitulo(id: String?): Int {
			if (id == null) return 0
			cacheNivel[id]?.let { return it }
			var actual: String? = id
			var nivel = 0
			var vueltas = 0
			while (actual != null && vueltas++ < 12) {
				val e = estilos[actual]
				val nombre = e?.nombre.orEmpty().trim()
				TITULO_NOMBRE.find(nombre)?.let { nivel = it.groupValues[2].toInt() }
				if (nivel == 0) TITULO_ID.find(actual)?.let { nivel = it.groupValues[2].toInt() }
				if (nivel == 0 && (nombre.equals("Title", true) || nombre.equals("Título", true) || actual.equals("Title", true))) nivel = 1
				val esquema = e?.esquema
				if (nivel == 0 && esquema != null && esquema in 0..8) nivel = esquema + 1
				if (nivel != 0) break
				actual = e?.basadoEn
			}
			nivel = nivel.coerceIn(0, 6)
			cacheNivel[id] = nivel
			return nivel
		}

		/** Numeración heredada del estilo (numId, ilvl). */
		private fun numeracionEstilo(id: String?): Pair<String?, Int?> {
			var actual = id
			var vueltas = 0
			while (actual != null && vueltas++ < 12) {
				val e = estilos[actual] ?: break
				if (e.numId != null) return e.numId to e.ilvl
				actual = e.basadoEn
			}
			return null to null
		}

		private fun esCita(id: String?): Boolean {
			var actual = id
			var vueltas = 0
			while (actual != null && vueltas++ < 12) {
				val e = estilos[actual] ?: return false
				if (CITA.containsMatchIn(e.nombre)) return true
				actual = e.basadoEn
			}
			return false
		}

		private fun terminarParrafo() {
			val t = tabla
			if (t != null) {
				if (texto.isNotBlank()) {
					if (t.celda.isNotEmpty()) t.celda.append(' ')
					t.celda.append(texto)
				}
				return
			}
			// Imagen sin descripción con texto detrás: solo el texto (la imagen se puede describir igual)
			if (sinDescripcion >= 0 && hayTexto && enlaces.none { it.inicio < sinDescripcion + SIN_DESCRIPCION.length }) {
				var hasta = (sinDescripcion + SIN_DESCRIPCION.length).coerceAtMost(texto.length)
				while (hasta < texto.length && texto[hasta] == ' ') hasta++
				val quitados = hasta - sinDescripcion
				texto.delete(sinDescripcion, hasta)
				for (i in enlaces.indices) enlaces[i] = Enlace(enlaces[i].inicio - quitados, enlaces[i].fin - quitados, enlaces[i].destino)
				soloImagen = false
			}
			if (texto.isBlank()) return
			val estilo = estiloP
			val nivel = esquemaP?.takeIf { it in 0..8 }?.plus(1)?.coerceAtMost(6) ?: nivelTitulo(estilo)
			val (numEstilo, ilvlEstilo) = numeracionEstilo(estilo)
			val numId = numIdP ?: numEstilo
			val ilvl = ilvlP ?: ilvlEstilo ?: 0
			val enLista = numId != null && numId != "0"
			val prefijo = if (numId != null && enLista) numeracion.prefijo(numId, ilvl) else ""
			val tipo = when {
				nivel > 0 && texto.length <= 300 -> TipoBloque.ENCABEZADO
				enLista -> TipoBloque.ELEMENTO_LISTA
				soloImagen && !hayTexto -> TipoBloque.IMAGEN
				esCita(estilo) -> TipoBloque.CITA
				else -> TipoBloque.PARRAFO
			}
			val contenido = prefijo + texto
			val desplazados = if (prefijo.isEmpty()) enlaces.toList()
			else enlaces.map { Enlace(it.inicio + prefijo.length, it.fin + prefijo.length, it.destino) }
			var m = marcas
			if (tipo == TipoBloque.ELEMENTO_LISTA && ultimoTipo != TipoBloque.ELEMENTO_LISTA) m = m or Marca.INICIO_LISTA
			val nivelBloque = when (tipo) {
				TipoBloque.ENCABEZADO -> nivel
				TipoBloque.ELEMENTO_LISTA -> (ilvl + 1).coerceIn(1, 9)
				else -> 0
			}
			val n = c.bloque(tipo, contenido, nivelBloque, desplazados, m)
			if (n >= 0) {
				ultimoTipo = tipo
				imagenParrafo?.let { c.imagen(n, it) }
			}
		}

		private fun emitirTabla(t: TablaDocx) {
			if (t.fila.isNotEmpty()) t.filas.add(t.fila)
			val encabezado = t.encabezado || (t.filas.size >= 2 && ctx.opciones.tablasConEncabezados)
			val antes = c.tamano
			AyudaOffice.emitirTabla(c, t.filas, encabezado, ctx.opciones)
			if (c.tamano > antes) ultimoTipo = TipoBloque.FILA_TABLA
		}
	}
}

/**
 * OpenDocument de texto (ODT, OTT y FODT plano): content.xml con Jsoup en
 * modo XML.
 *
 * - text:h → encabezados (text:outline-level); text:p → párrafos.
 * - Listas anidadas con su profundidad; numeradas si su estilo de lista es de números.
 * - Tablas como en DOCX; notas con llamada "[N]" y el texto al final ("Notas").
 * - Enlaces (text:a), marcadores (text:bookmark) e imágenes con título o descripción.
 */
object LectorOdt : LectorFormato {

	private class FormatoLista(val numerado: Boolean, val formato: String, val prefijo: String, val sufijo: String, val inicio: Int)

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val c = ctx.nuevoConstructor()
		val estilosLista = HashMap<String, Map<Int, FormatoLista>>()
		val cabecera = runCatching { fuente.cabecera(8) }.getOrDefault(ByteArray(0))
		val doc: Element = if (fuente.extension == "fodt" || !AyudaOffice.esZip(cabecera)) {
			val d = Jsoup.parse(Textos.decodificar(fuente.bytes()), "", Parser.xmlParser())
			metadatos(d, c)
			leerEstilosLista(d, estilosLista)
			d
		} else {
			fuente.contenedor().use { z ->
				val manifiesto = z.texto("META-INF/manifest.xml").orEmpty()
				if (manifiesto.contains("encryption-data")) {
					throw ExcepcionFormato(R.string.fmt_cifrado)
				}
				val contenido = z.texto("content.xml") ?: throw ExcepcionFormato(R.string.fmt_falta_parte)
				z.texto("meta.xml")?.let { metadatos(Jsoup.parse(it, "", Parser.xmlParser()), c) }
				z.texto("styles.xml")?.let { leerEstilosLista(Jsoup.parse(it, "", Parser.xmlParser()), estilosLista) }
				ctx.comprobar()
				val d = Jsoup.parse(contenido, "", Parser.xmlParser())
				leerEstilosLista(d, estilosLista)
				d
			}
		}
		ctx.progreso(30)
		val cuerpo = doc.getElementsByTag("office:text").firstOrNull()
			?: throw ExcepcionFormato.noValido(Formato.ODT)
		val conversor = ConversorOdt(c, ctx, estilosLista)
		conversor.bloques(cuerpo)
		conversor.notasFinales()
		if (c.titulo.isBlank()) c.titulo = fuente.nombre.substringBeforeLast('.')
		return Contenido.Texto(c.construir())
	}

	private fun metadatos(doc: Element, c: ConstructorDocumento) {
		val meta = doc.getElementsByTag("office:meta").firstOrNull() ?: return
		fun valor(nombre: String) = meta.getElementsByTag(nombre).firstOrNull()?.text()?.trim().orEmpty()
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
		c.metadatos["Palabras clave"] = meta.getElementsByTag("meta:keyword").joinToString(", ") { it.text().trim() }
		c.metadatos["Fecha"] = valor("meta:creation-date").ifEmpty { valor("dc:date") }.take(10)
	}

	private fun leerEstilosLista(doc: Element, mapa: HashMap<String, Map<Int, FormatoLista>>) {
		for (estilo in doc.getElementsByTag("text:list-style")) {
			val nombre = estilo.attr("style:name")
			if (nombre.isBlank()) continue
			val niveles = HashMap<Int, FormatoLista>()
			for (n in estilo.children()) {
				val nivel = n.attr("text:level").toIntOrNull() ?: continue
				niveles[nivel] = if (n.normalName() == "text:list-level-style-number") {
					val formato = n.attr("style:num-format")
					FormatoLista(formato.isNotEmpty(), formato, n.attr("style:num-prefix"), n.attr("style:num-suffix"), n.attr("text:start-value").toIntOrNull() ?: 1)
				} else {
					FormatoLista(false, "", "", "", 1)
				}
			}
			mapa[nombre] = niveles
		}
	}

	/** Texto en línea que se va formando (un párrafo, una celda o una nota). */
	private class Linea {
		val texto = StringBuilder()
		val enlaces = ArrayList<Enlace>()
		var marcas = 0
		var soloImagen = false
		var hayTexto = false
		/** Imagen de la línea (ruta dentro del .odt) para describirla. */
		var imagen: String? = null

		fun anadir(s: String) {
			if (s.isEmpty()) return
			if (s[0] == ' ' && (texto.isEmpty() || texto.last() == ' ' || texto.last() == '\n')) {
				val sin = s.trimStart(' ')
				if (sin.isEmpty()) return
				texto.append(sin)
			} else {
				texto.append(s)
			}
			if (s.isNotBlank()) hayTexto = true
		}

		fun separar() {
			if (texto.isNotEmpty() && !texto.last().isWhitespace()) texto.append(' ')
		}
	}

	private class ConversorOdt(
		val c: ConstructorDocumento,
		val ctx: ContextoLectura,
		val listas: Map<String, Map<Int, FormatoLista>>,
	) {
		private val notas = ArrayList<Pair<String, String>>()
		private var contadorNotas = 0
		private var ultimoTipo: TipoBloque? = null
		private var elementos = 0
		/** Dentro de una nota: no se registran anclas (apuntarían a otro sitio). */
		private var enNota = 0

		fun bloques(padre: Element) {
			for (hijo in padre.children()) bloque(hijo)
		}

		private fun bloque(e: Element) {
			if (++elementos % 200 == 0) ctx.comprobar()
			when (e.normalName()) {
				"text:h" -> {
					val nivel = (e.attr("text:outline-level").toIntOrNull() ?: 1).coerceIn(1, 6)
					parrafo(e, TipoBloque.ENCABEZADO, nivel)
				}
				"text:p" -> parrafo(e, TipoBloque.PARRAFO, 0)
				"text:list" -> lista(e, 1, null)
				"table:table" -> tabla(e)
				"text:table-of-content", "text:tracked-changes", "office:forms", "text:sequence-decls", "text:variable-decls",
				"text:user-field-decls", "text:dde-connection-decls", "office:annotation", "text:soft-page-break" -> Unit
				"draw:frame", "draw:a", "draw:g", "draw:custom-shape", "draw:rect", "draw:ellipse" -> {
					val l = Linea()
					enLinea(e, l)
					emitir(l, TipoBloque.PARRAFO, 0)
				}
				else -> bloques(e)
			}
		}

		private fun parrafo(e: Element, tipo: TipoBloque, nivel: Int, prefijo: String = "") {
			val l = Linea()
			l.texto.append(prefijo)
			for (n in e.childNodes()) enLinea(n, l)
			if (tipo == TipoBloque.ENCABEZADO) {
				val titulo = l.texto.toString().trim()
				if (titulo.isNotEmpty()) c.ancla("#$titulo|outline")
			}
			emitir(l, tipo, nivel)
		}

		private fun emitir(l: Linea, tipo: TipoBloque, nivel: Int) {
			if (l.texto.isBlank()) return
			var t = tipo
			if (t == TipoBloque.PARRAFO && l.soloImagen && !l.hayTexto) t = TipoBloque.IMAGEN
			var marcas = l.marcas
			if (t == TipoBloque.ELEMENTO_LISTA && ultimoTipo != TipoBloque.ELEMENTO_LISTA) marcas = marcas or Marca.INICIO_LISTA
			val n = c.bloque(t, l.texto.toString(), nivel, l.enlaces.toList(), marcas)
			if (n >= 0) {
				ultimoTipo = t
				l.imagen?.let { c.imagen(n, it) }
			}
		}

		private fun lista(e: Element, nivel: Int, estiloHeredado: String?) {
			val estilo = e.attr("text:style-name").ifBlank { estiloHeredado.orEmpty() }
			val formato = listas[estilo]?.get(nivel)
			var contador = (formato?.inicio ?: 1) - 1
			for (item in e.children()) {
				val nombre = item.normalName()
				if (nombre != "text:list-item" && nombre != "text:list-header") continue
				item.attr("text:start-value").toIntOrNull()?.let { contador = it - 1 }
				val numerado = nombre == "text:list-item"
				if (numerado) contador++
				var prefijo = if (numerado && formato != null && formato.numerado) {
					formato.prefijo + formatear(contador, formato.formato) + formato.sufijo + " "
				} else ""
				for (hijo in item.children()) {
					when (hijo.normalName()) {
						"text:p" -> {
							parrafo(hijo, TipoBloque.ELEMENTO_LISTA, nivel.coerceAtMost(9), prefijo)
							prefijo = ""
						}
						"text:h" -> {
							parrafo(hijo, TipoBloque.ENCABEZADO, (hijo.attr("text:outline-level").toIntOrNull() ?: 1).coerceIn(1, 6), prefijo)
							prefijo = ""
						}
						"text:list" -> lista(hijo, nivel + 1, estilo)
						else -> bloque(hijo)
					}
				}
			}
		}

		private fun formatear(n: Int, formato: String): String = when (formato) {
			"a" -> AyudaOffice.letras(n)
			"A" -> AyudaOffice.letras(n).uppercase()
			"i" -> AyudaOffice.romano(n).lowercase()
			"I" -> AyudaOffice.romano(n)
			else -> n.toString()
		}

		// --- En línea ---------------------------------------------------------------

		private fun enLinea(nodo: Node, l: Linea) {
			if (nodo is TextNode) {
				l.anadir(ESPACIOS.replace(nodo.wholeText, " "))
				return
			}
			if (nodo !is Element) return
			when (nodo.normalName()) {
				"text:s" -> l.texto.append(" ".repeat((nodo.attr("text:c").toIntOrNull() ?: 1).coerceIn(1, 20)))
				"text:tab" -> l.separar()
				"text:line-break" -> l.texto.append('\n')
				"text:a" -> {
					val href = nodo.attr("xlink:href").trim()
					val inicio = l.texto.length
					for (n in nodo.childNodes()) enLinea(n, l)
					val destino = when {
						href.isEmpty() -> null
						href.startsWith("#") -> href
						href.contains("://") || href.startsWith("mailto:") || href.startsWith("tel:") -> href
						href.startsWith("www.", true) -> "http://$href"
						else -> null
					}
					if (destino != null && l.texto.length > inicio) l.enlaces += Enlace(inicio, l.texto.length, destino)
				}
				"text:bookmark", "text:bookmark-start", "text:reference-mark", "text:reference-mark-start" -> {
					val nombre = nodo.attr("text:name")
					if (enNota == 0 && nombre.isNotBlank()) c.ancla("#$nombre")
				}
				"text:bookmark-ref", "text:reference-ref", "text:sequence-ref", "text:note-ref" -> {
					val inicio = l.texto.length
					for (n in nodo.childNodes()) enLinea(n, l)
					val nombre = nodo.attr("text:ref-name")
					if (nombre.isNotBlank() && l.texto.length > inicio) l.enlaces += Enlace(inicio, l.texto.length, "#$nombre")
				}
				"text:note" -> nota(nodo, l)
				"draw:frame" -> marco(nodo, l)
				"text:soft-page-break", "office:annotation", "office:annotation-end", "text:bookmark-end",
				"text:reference-mark-end", "text:toc-mark", "text:toc-mark-start", "text:toc-mark-end",
				"text:alphabetical-index-mark", "text:alphabetical-index-mark-start", "text:alphabetical-index-mark-end",
				"text:change", "text:change-start", "text:change-end", "svg:title", "svg:desc", "text:tracked-changes" -> Unit
				"text:p", "text:h" -> {
					l.separar()
					for (n in nodo.childNodes()) enLinea(n, l)
					l.separar()
				}
				"table:table-cell", "table:covered-table-cell", "table:table-row" -> {
					for (n in nodo.childNodes()) enLinea(n, l)
					l.separar()
				}
				else -> for (n in nodo.childNodes()) enLinea(n, l)
			}
		}

		private fun marco(marco: Element, l: Linea) {
			var esImagen = false
			var ruta: String? = null
			for (hijo in marco.children()) {
				when (hijo.normalName()) {
					"draw:text-box" -> {
						l.separar()
						for (n in hijo.childNodes()) enLinea(n, l)
						l.separar()
					}
					"draw:image", "draw:object", "draw:object-ole" -> {
						esImagen = true
						if (hijo.normalName() == "draw:image" && ruta == null) {
							ruta = hijo.attr("xlink:href").trim().takeIf { it.isNotEmpty() && !it.contains("://") && !it.startsWith("#") }
								?.let { Contenedor.resolver("", it) }
						}
					}
				}
			}
			if (!esImagen || !ctx.opciones.leerImagenes) return
			if (l.imagen == null) l.imagen = ruta
			val descripcion = marco.children().firstOrNull { it.normalName() == "svg:title" }?.text()?.trim().orEmpty()
				.ifEmpty { marco.children().firstOrNull { it.normalName() == "svg:desc" }?.text()?.trim().orEmpty() }
			if (descripcion.isEmpty()) {
				// Sin descripción: solo si va sola (las de dentro del texto suelen ser adornos)
				if (ruta != null && l.texto.isBlank()) {
					l.texto.append(SIN_DESCRIPCION)
					l.soloImagen = true
				}
				return
			}
			val vacio = l.texto.isBlank()
			l.separar()
			l.texto.append(if (vacio) "Imagen: $descripcion" else "[Imagen: $descripcion]")
			if (vacio) l.soloImagen = true
		}

		private fun nota(nodo: Element, l: Linea) {
			if (enNota > 0) return
			contadorNotas++
			val cita = nodo.children().firstOrNull { it.normalName() == "text:note-citation" }
				?.let { it.attr("text:label").ifBlank { it.text() } }?.trim().orEmpty()
				.ifEmpty { contadorNotas.toString() }
			val ancla = "#nota-$contadorNotas"
			val inicio = l.texto.length
			l.texto.append('[').append(cita).append(']')
			l.enlaces += Enlace(inicio, l.texto.length, ancla)
			l.marcas = l.marcas or Marca.LLAMADA_NOTA
			l.hayTexto = true
			val cuerpo = nodo.children().firstOrNull { it.normalName() == "text:note-body" } ?: return
			val ln = Linea()
			enNota++
			for (n in cuerpo.childNodes()) enLinea(n, ln)
			enNota--
			val texto = Textos.unirEspacios(ln.texto.toString().replace('\n', ' ')).trim()
			if (texto.isNotEmpty()) notas += ancla to "$cita. $texto"
		}

		// --- Tablas -----------------------------------------------------------------

		private fun tabla(t: Element) {
			val filas = ArrayList<List<String>>()
			var encabezado = false
			fun filasDe(e: Element, enCabecera: Boolean) {
				for (h in e.children()) {
					when (h.normalName()) {
						"table:table-header-rows" -> filasDe(h, true)
						"table:table-rows", "table:table-row-group" -> filasDe(h, enCabecera)
						"table:table-row" -> {
							val celdas = ArrayList<String>()
							for (celda in h.children()) {
								val nombre = celda.normalName()
								if (nombre != "table:table-cell" && nombre != "table:covered-table-cell") continue
								val texto = if (nombre == "table:covered-table-cell") "" else textoCelda(celda)
								val repetir = (celda.attr("table:number-columns-repeated").toIntOrNull() ?: 1).coerceIn(1, 100)
								repeat(repetir) { celdas += texto }
							}
							while (celdas.isNotEmpty() && celdas.last().isBlank()) celdas.removeAt(celdas.size - 1)
							if (enCabecera && filas.isEmpty()) encabezado = true
							val repetir = (h.attr("table:number-rows-repeated").toIntOrNull() ?: 1).coerceIn(1, 100)
							if (celdas.isEmpty()) filas.add(celdas) else repeat(repetir) { filas.add(celdas) }
						}
					}
				}
			}
			filasDe(t, false)
			val conEncabezado = encabezado || (filas.count { f -> f.any { it.isNotBlank() } } >= 2 && ctx.opciones.tablasConEncabezados)
			val antes = c.tamano
			AyudaOffice.emitirTabla(c, filas, conEncabezado, ctx.opciones)
			if (c.tamano > antes) ultimoTipo = TipoBloque.FILA_TABLA
		}

		private fun textoCelda(celda: Element): String {
			val l = Linea()
			for (n in celda.childNodes()) enLinea(n, l)
			return Textos.unirEspacios(l.texto.toString().replace('\n', ' ')).trim()
		}

		fun notasFinales() {
			if (notas.isEmpty()) return
			c.encabezado("Notas", 1)
			for ((ancla, texto) in notas) {
				c.ancla(ancla)
				c.bloque(TipoBloque.NOTA, texto)
			}
		}

		companion object {
			private val ESPACIOS = Regex("[ \t\r\n]+")
		}
	}
}
