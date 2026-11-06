package com.xebolax.lector.formatos

import com.xebolax.lector.modelo.ConstructorDocumento
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.Enlace
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.Textos
import com.xebolax.lector.modelo.TipoBloque
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * RTF (y Windows Write .wri) sin bibliotecas: un intérprete de grupos,
 * palabras de control y destinos.
 *
 * - Codificación: \ansicpg y el \fcharset de cada fuente; los \'hh seguidos
 *   se decodifican juntos (japonés, chino, coreano); \uN con sus \ucN de reserva.
 * - Encabezados por \outlinelevelN o por los estilos "heading N" de la hoja de estilos.
 * - Tablas (\trowd ... \cell ... \row), listas (\listtext, \pntext), notas
 *   (\footnote) al final bajo "Notas" y campos HYPERLINK como enlaces.
 * - Se saltan fuentes, colores, imágenes, objetos, cabeceras y pies.
 */
object LectorRtf : LectorFormato {

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val bytes = fuente.bytes(256L * 1024 * 1024)
		val c = ctx.nuevoConstructor()
		var inicio = 0
		while (inicio < bytes.size && inicio < 16 && (bytes[inicio].toInt() and 0xFF) in setOf(0x20, 0x09, 0x0A, 0x0D, 0xEF, 0xBB, 0xBF)) inicio++
		val esRtf = bytes.size - inicio >= 5 && String(bytes, inicio, 5, Charsets.ISO_8859_1) == "{\\rtf"
		when {
			esRtf -> InterpreteRtf(bytes, inicio, c, ctx).ejecutar()
			esWrite(bytes) || (fuente.extension == "wri" && bytes.size > 128) -> leerWrite(bytes, c, ctx)
			else -> throw ExcepcionFormato("El archivo no es un documento RTF válido.")
		}
		if (c.titulo.isBlank()) c.titulo = fuente.nombre.substringBeforeLast('.')
		return Contenido.Texto(c.construir())
	}

	// --- Windows Write -------------------------------------------------------------

	/** Cabecera de Write: wIdent 0xBE31 (sin OLE) o 0xBE32 (con OLE), en little endian. */
	private fun esWrite(b: ByteArray) = b.size > 128 && (b[1].toInt() and 0xFF) == 0xBE &&
		((b[0].toInt() and 0xFF) == 0x31 || (b[0].toInt() and 0xFF) == 0x32)

	/** Write: el texto va del byte 128 a fcMac (bytes 14-17), en windows-1252. */
	private fun leerWrite(b: ByteArray, c: ConstructorDocumento, ctx: ContextoLectura) {
		val fcMac = ((b[14].toInt() and 0xFF) or ((b[15].toInt() and 0xFF) shl 8) or
			((b[16].toInt() and 0xFF) shl 16) or ((b[17].toInt() and 0xFF) shl 24))
		val fin = if (fcMac in 129..b.size) fcMac else b.size
		val texto = String(b, 128, fin - 128, Charset.forName("windows-1252"))
		for ((i, parte) in texto.split("\r\n", "\n", "\r").withIndex()) {
			if (i % 500 == 0) ctx.comprobar()
			// Los párrafos con imágenes son datos binarios: se descartan
			val raros = parte.count { it.code < 0x20 && it != '\t' }
			if (parte.isNotEmpty() && raros > parte.length / 4) continue
			c.parrafo(parte.filter { it == '\t' || it.code >= 0x20 }.replace('\t', ' '))
		}
	}

	// --- RTF -------------------------------------------------------------------------

	private enum class Destino { TEXTO, NOTA, SALTAR, FUENTES, ESTILOS, INFO, TITULO, AUTOR, ASUNTO, COMENTARIO, EMPRESA, CLAVES, CAMPO, LISTA, MARCADOR }

	private class Campo {
		val instruccion = StringBuilder()
	}

	/** Estado de un grupo { }: se copia al abrir y se recupera al cerrar. */
	private class Estado(
		var destino: Destino = Destino.TEXTO,
		var fuente: Int = -1,
		var uc: Int = 1,
		var oculto: Boolean = false,
		var esquema: Int = -1,
		var estilo: Int = 0,
		var enTabla: Boolean = false,
		var nivelLista: Int = 0,
		var campo: Campo? = null,
		var negrita: Boolean = false,
		/** Tamaño de letra en medios puntos (\fs). */
		var tamano: Int = 24,
	) {
		/** Propios del grupo (no se heredan). */
		var asterisco = false
		var destinoPropio = false
		var esResultado = false
		var inicioResultado = -1
		var numNota = 0

		fun copia() = Estado(destino, fuente, uc, oculto, esquema, estilo, enTabla, nivelLista, campo, negrita, tamano)
	}

	private val DESTINOS_SALTAR = setOf(
		"colortbl", "pict", "object", "header", "footer", "headerl", "headerr", "headerf", "footerl", "footerr", "footerf",
		"listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "themedata", "datastore", "latentstyles",
		"pn", "pnseclvl", "filetbl", "revtbl", "ftnsep", "ftnsepc", "aftnsep", "aftnsepc", "ftncn", "aftncn", "bkmkend",
		"annotation", "atnid", "atnauthor", "atnref", "atndate", "atrfstart", "atrfend", "atnicn", "atnparent",
		"template", "docvar", "nonshppict", "shppict", "shp", "shpinst", "shprslt", "shptxt", "txe", "xe", "tc", "tcn",
		"userprops", "private", "pgdsctbl", "mmathPr", "listpicture", "protusertbl", "fchars", "lchars", "objdata",
		"blipuid", "nonesttables", "nesttableprops", "wgrffmtfilter", "passwordhash", "mhtmltag", "htmltag",
		"factoidname", "do", "xform", "formfield", "background", "pgptbl", "oldcprops", "oldpprops", "oldsprops",
		"oldtprops", "picprop", "sp", "sn", "sv", "falt", "panose", "fname", "operator", "result", "comment",
		"bkmkcolf", "bkmkcoll", "styrsid", "defchp", "defpap", "revtim", "creatim", "printim", "buptim",
	)

	private val LENGUAS = mapOf(
		1034 to "es", 3082 to "es", 2058 to "es-MX", 1033 to "en", 2057 to "en-GB", 1036 to "fr", 1031 to "de",
		1040 to "it", 1046 to "pt-BR", 2070 to "pt", 1049 to "ru", 1027 to "ca", 1069 to "eu", 1110 to "gl",
		1043 to "nl", 1045 to "pl", 1029 to "cs", 1041 to "ja", 2052 to "zh", 1028 to "zh-TW", 1042 to "ko",
		1032 to "el", 1055 to "tr", 1037 to "he", 1025 to "ar", 1058 to "uk",
	)

	private val TITULO_ESTILO = Regex(
		"""^(heading|título|titulo|titre|überschrift|titolo|kop|nagłówek|rubrik|overskrift|заголовок)\s*(\d)$""",
		RegexOption.IGNORE_CASE,
	)
	private val HIPERVINCULO = Regex("""HYPERLINK\s+"([^"]*)"""", RegexOption.IGNORE_CASE)
	private val HIPERVINCULO_SIN_COMILLAS = Regex("""HYPERLINK\s+([^\s"\\]+)""", RegexOption.IGNORE_CASE)
	private val LOCAL = Regex("""\\l\s+"([^"]+)"""")
	private val REFERENCIA = Regex("""^\s*(?:REF|PAGEREF|NOTEREF)\s+([^\s\\]+)""", RegexOption.IGNORE_CASE)

	/** Destino de un campo: HYPERLINK "url", HYPERLINK \l "marcador", REF marcador. */
	private fun destinoCampo(instruccion: String): String? {
		val t = instruccion.trim()
		if (t.startsWith("HYPERLINK", ignoreCase = true)) {
			val local = LOCAL.find(t)?.groupValues?.get(1)
			val url = HIPERVINCULO.find(t)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
				?: HIPERVINCULO_SIN_COMILLAS.find(t)?.groupValues?.get(1)
			val completa = url?.let { if (it.startsWith("www.", true)) "http://$it" else it }
			return when {
				completa != null && local != null -> "$completa#$local"
				completa != null -> completa
				local != null -> "#$local"
				else -> null
			}
		}
		return REFERENCIA.find(t)?.groupValues?.get(1)?.let { "#$it" }
	}

	/** Página de códigos de un \fcharset (null: la del documento). */
	private fun paginaDeCharset(charset: Int): Int? = when (charset) {
		0, 2 -> 1252
		77 -> 10000
		128 -> 932
		129 -> 949
		130 -> 1361
		134 -> 936
		136 -> 950
		161 -> 1253
		162 -> 1254
		163 -> 1258
		177 -> 1255
		178 -> 1256
		186 -> 1257
		204 -> 1251
		222 -> 874
		238 -> 1250
		255 -> 437
		else -> null
	}

	private val cacheCharset = HashMap<Int, Charset>()

	private fun charsetDe(pagina: Int): Charset = synchronized(cacheCharset) {
		cacheCharset.getOrPut(pagina) {
			val nombres = when (pagina) {
				932 -> listOf("windows-31j", "Shift_JIS")
				936 -> listOf("GBK", "GB2312")
				949 -> listOf("windows-949", "x-windows-949", "EUC-KR")
				950 -> listOf("Big5", "x-windows-950")
				1361 -> listOf("x-Johab", "EUC-KR")
				874 -> listOf("windows-874", "x-windows-874", "TIS-620")
				10000 -> listOf("x-MacRoman", "MacRoman")
				437 -> listOf("IBM437", "cp437")
				850 -> listOf("IBM850", "cp850")
				65001 -> listOf("UTF-8")
				else -> listOf("windows-$pagina", "x-windows-$pagina", "Cp$pagina")
			}
			nombres.firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() } ?: Charset.forName("windows-1252")
		}
	}

	private class InterpreteRtf(
		private val b: ByteArray,
		private val desde: Int,
		private val c: ConstructorDocumento,
		private val ctx: ContextoLectura,
	) {
		private val pila = ArrayList<Estado>()
		private var e = Estado()
		private val pendientes = ByteArrayOutputStream()
		/** Caracteres de reserva que quedan por saltar tras un \uN. */
		private var saltar = 0

		// Fuentes y codificación
		private val charsetFuente = HashMap<Int, Int>()
		private val paginaFuente = HashMap<Int, Int>()
		private var fuenteDefinida = 0
		private var paginaDocumento = 1252
		private var fuentePorDefecto = 0

		// Hoja de estilos
		private val nivelEstilo = HashMap<Int, Int>()
		private var estiloDefinido = 0
		private val nombreEstilo = StringBuilder()

		// Metadatos
		private val info = HashMap<Destino, StringBuilder>()

		// Párrafo en curso
		private val parrafo = StringBuilder()
		private val enlaces = ArrayList<Enlace>()
		private var marcas = 0
		private val prefijo = StringBuilder()
		private var tieneLista = false
		private val anclasPendientes = ArrayList<String>()
		private var ultimoTipo: TipoBloque? = null
		private var parrafoSinNegrita = false
		private var parrafoTamano = 0
		/** Letras escritas con cada tamaño (el más usado es el del cuerpo del texto). */
		private val tamanos = HashMap<Int, Int>()
		/** Párrafos cortos en negrita: (bloque, tamaño). Pueden ser títulos. */
		private val candidatos = ArrayList<Pair<Int, Int>>()

		// Tabla en curso
		private val filas = ArrayList<List<String>>()
		private var fila = ArrayList<String>()
		private val celda = StringBuilder()
		private var filaEncabezado = false
		private var primeraFilaEncabezado = false

		// Notas y marcadores
		private val notas = ArrayList<String>()
		private val nota = StringBuilder()
		private val marcador = StringBuilder()

		fun ejecutar() {
			val n = b.size
			var i = desde
			var pasos = 0
			while (i < n) {
				if (++pasos and 0x3FFF == 0) {
					ctx.comprobar()
					ctx.progreso((i * 100L / n).toInt())
				}
				when (val ch = b[i].toInt() and 0xFF) {
					'{'.code -> {
						volcarBytes()
						saltar = 0
						pila.add(e)
						e = e.copia()
						if (e.destino == Destino.ESTILOS) {
							estiloDefinido = 0
							nombreEstilo.setLength(0)
						}
						i++
					}
					'}'.code -> {
						volcarBytes()
						saltar = 0
						cerrarGrupo()
						i++
					}
					'\\'.code -> i = control(i + 1)
					'\r'.code, '\n'.code -> i++
					else -> {
						caracter(ch)
						i++
					}
				}
			}
			volcarBytes()
			// Final del documento
			if (e.enTabla && parrafo.isNotBlank()) {
				finCelda()
				finFila()
			}
			if (fila.isNotEmpty()) finFila()
			emitirTabla()
			emitirParrafo()
			titulosPorFormato()
			if (notas.any { it.isNotBlank() }) {
				c.encabezado("Notas", 1)
				for ((k, texto) in notas.withIndex()) {
					c.ancla("#rtfnota-${k + 1}")
					c.bloque(TipoBloque.NOTA, "${k + 1}. $texto")
				}
			}
			metadatos()
		}

		private fun metadatos() {
			fun valor(d: Destino) = info[d]?.toString()?.let { Textos.unirEspacios(it).trim() }.orEmpty()
			val titulo = valor(Destino.TITULO)
			if (titulo.isNotEmpty()) c.titulo = titulo
			val autor = valor(Destino.AUTOR)
			if (autor.isNotEmpty()) {
				c.autor = autor
				c.metadatos["Autor"] = autor
			}
			c.metadatos["Tema"] = valor(Destino.ASUNTO)
			c.metadatos["Descripción"] = valor(Destino.COMENTARIO)
			c.metadatos["Empresa"] = valor(Destino.EMPRESA)
			c.metadatos["Palabras clave"] = valor(Destino.CLAVES)
		}

		// --- Lectura de palabras de control -------------------------------------------

		private fun esLetra(x: Int) = (x in 'a'.code..'z'.code) || (x in 'A'.code..'Z'.code)

		private fun esDigito(x: Int) = x in '0'.code..'9'.code

		private fun hex(x: Int): Int = when (x) {
			in '0'.code..'9'.code -> x - '0'.code
			in 'a'.code..'f'.code -> x - 'a'.code + 10
			in 'A'.code..'F'.code -> x - 'A'.code + 10
			else -> -1
		}

		/** Lee la palabra o símbolo de control que empieza en i (tras la barra); devuelve la posición siguiente. */
		private fun control(inicio: Int): Int {
			val n = b.size
			var i = inicio
			if (i >= n) return n
			val ch = b[i].toInt() and 0xFF
			if (esLetra(ch)) {
				while (i < n && i - inicio < 32 && esLetra(b[i].toInt() and 0xFF)) i++
				val palabra = String(b, inicio, i - inicio, Charsets.ISO_8859_1)
				var parametro: Int? = null
				if (i < n && (b[i].toInt() == '-'.code || esDigito(b[i].toInt() and 0xFF))) {
					var negativo = false
					if (b[i].toInt() == '-'.code) {
						negativo = true
						i++
					}
					var v = 0L
					var digitos = 0
					while (i < n && esDigito(b[i].toInt() and 0xFF)) {
						if (digitos < 10) v = v * 10 + (b[i] - '0'.code.toByte())
						digitos++
						i++
					}
					parametro = (if (negativo) -v else v).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
				}
				if (i < n && b[i].toInt() == ' '.code) i++
				if (palabra == "bin") {
					// Datos binarios: se saltan tal cual
					volcarBytes()
					val longitud = (parametro ?: 0).coerceAtLeast(0).toLong()
					return minOf(n.toLong(), i + longitud).toInt()
				}
				volcarBytes()
				saltar = 0
				palabraControl(palabra, parametro)
				return i
			}
			i++
			when (ch) {
				'\''.code -> {
					if (i + 1 < n) {
						val alto = hex(b[i].toInt() and 0xFF)
						val bajo = hex(b[i + 1].toInt() and 0xFF)
						i += 2
						if (alto >= 0 && bajo >= 0) caracter(alto * 16 + bajo)
					}
				}
				else -> {
					volcarBytes()
					if (saltar > 0) {
						saltar--
						return i
					}
					when (ch) {
						'\\'.code, '{'.code, '}'.code -> caracter(ch)
						'~'.code -> anadirTexto(" ")
						'_'.code -> anadirTexto("-")
						'*'.code -> e.asterisco = true
						'\r'.code, '\n'.code -> finParrafo()
					}
				}
			}
			return i
		}

		/** Un byte de texto (literal o \'hh) en la codificación actual. */
		private fun caracter(ch: Int) {
			if (saltar > 0) {
				saltar--
				return
			}
			when (e.destino) {
				Destino.SALTAR, Destino.FUENTES, Destino.INFO -> return
				Destino.ESTILOS -> if (ch == ';'.code) {
					volcarBytes()
					confirmarEstilo()
					return
				}
				else -> Unit
			}
			pendientes.write(ch)
		}

		private fun charsetActual(): Charset {
			val f = e.fuente
			val pagina = paginaFuente[f] ?: charsetFuente[f]?.let { paginaDeCharset(it) } ?: paginaDocumento
			return charsetDe(pagina)
		}

		/** Decodifica los bytes acumulados (así los caracteres de dos bytes salen bien). */
		private fun volcarBytes() {
			if (pendientes.size() == 0) return
			val s = String(pendientes.toByteArray(), charsetActual())
			pendientes.reset()
			anadir(s)
		}

		private fun anadirTexto(s: String) {
			volcarBytes()
			anadir(s)
		}

		/** Envía texto al destino actual. */
		private fun anadir(s: String) {
			when (e.destino) {
				Destino.TEXTO -> if (!e.oculto) {
					parrafo.append(s)
					// Para reconocer títulos hechos a mano (negrita y letra grande)
					val letras = s.count { !it.isWhitespace() }
					if (letras > 0) {
						if (!e.negrita) parrafoSinNegrita = true
						parrafoTamano = maxOf(parrafoTamano, e.tamano)
						tamanos[e.tamano] = (tamanos[e.tamano] ?: 0) + letras
					}
				}
				Destino.NOTA -> if (!e.oculto) nota.append(s)
				Destino.CAMPO -> e.campo?.instruccion?.append(s)
				Destino.LISTA -> prefijo.append(s)
				Destino.TITULO, Destino.AUTOR, Destino.ASUNTO, Destino.COMENTARIO, Destino.EMPRESA, Destino.CLAVES ->
					info.getOrPut(e.destino) { StringBuilder() }.append(s)
				Destino.ESTILOS -> nombreEstilo.append(s)
				Destino.MARCADOR -> marcador.append(s)
				else -> Unit
			}
		}

		private fun palabraControl(w: String, p: Int?) {
			if (e.destino == Destino.SALTAR) return
			val asterisco = e.asterisco
			e.asterisco = false

			// Destinos
			when (w) {
				"fonttbl" -> return entrarEn(Destino.FUENTES)
				"stylesheet" -> return entrarEn(Destino.ESTILOS)
				"info" -> return entrarEn(Destino.INFO)
				"title", "author", "subject", "doccomm", "company", "keywords" -> {
					if (e.destino != Destino.INFO) return entrarEn(Destino.SALTAR)
					return entrarEn(
						when (w) {
							"title" -> Destino.TITULO
							"author" -> Destino.AUTOR
							"subject" -> Destino.ASUNTO
							"doccomm" -> Destino.COMENTARIO
							"company" -> Destino.EMPRESA
							else -> Destino.CLAVES
						}
					)
				}
				"footnote" -> return iniciarNota()
				"field" -> {
					e.campo = Campo()
					return
				}
				"fldinst" -> return entrarEn(if (e.campo != null) Destino.CAMPO else Destino.SALTAR)
				"fldrslt" -> {
					if (e.destino == Destino.TEXTO) {
						e.esResultado = true
						e.inicioResultado = parrafo.length
					}
					return
				}
				"pntext", "listtext" -> {
					if (e.destino == Destino.TEXTO) {
						prefijo.setLength(0)
						tieneLista = true
						entrarEn(Destino.LISTA)
					} else entrarEn(Destino.SALTAR)
					return
				}
				"bkmkstart" -> {
					marcador.setLength(0)
					return entrarEn(Destino.MARCADOR)
				}
				in DESTINOS_SALTAR -> return entrarEn(Destino.SALTAR)
			}
			if (asterisco) return entrarEn(Destino.SALTAR)

			// Definiciones de fuentes y estilos
			when (e.destino) {
				Destino.FUENTES -> {
					when (w) {
						"f" -> fuenteDefinida = p ?: 0
						"fcharset" -> charsetFuente[fuenteDefinida] = p ?: 0
						"cpg" -> if (p != null && p > 0) paginaFuente[fuenteDefinida] = p
					}
					return
				}
				Destino.ESTILOS -> {
					when (w) {
						"s" -> estiloDefinido = p ?: 0
						"cs", "ds", "ts", "tsrowd" -> estiloDefinido = -1
						"outlinelevel" -> if (estiloDefinido >= 0 && p != null && p in 0..8) nivelEstilo[estiloDefinido] = p + 1
						"u" -> unicode(p)
						"uc" -> e.uc = (p ?: 1).coerceIn(0, 10)
					}
					return
				}
				else -> Unit
			}

			when (w) {
				// Documento
				"ansi" -> paginaDocumento = 1252
				"mac" -> paginaDocumento = 10000
				"pc" -> paginaDocumento = 437
				"pca" -> paginaDocumento = 850
				"ansicpg" -> if (p != null && p > 0) paginaDocumento = p
				"deff" -> {
					fuentePorDefecto = p ?: 0
					if (e.fuente < 0) e.fuente = fuentePorDefecto
				}
				"deflang" -> if (c.idioma.isBlank() && p != null) LENGUAS[p]?.let { c.idioma = it }
				// Caracteres
				"f" -> e.fuente = p ?: 0
				"plain" -> {
					e.fuente = fuentePorDefecto
					e.oculto = false
					e.negrita = false
					e.tamano = 24
				}
				"b" -> e.negrita = (p ?: 1) != 0
				"fs" -> e.tamano = p ?: 24
				"uc" -> e.uc = (p ?: 1).coerceIn(0, 10)
				"u" -> unicode(p)
				"v" -> e.oculto = (p ?: 1) != 0
				"line" -> anadirTexto("\n")
				"tab" -> anadirTexto(" ")
				"emdash" -> anadirTexto("—")
				"endash" -> anadirTexto("–")
				"bullet" -> anadirTexto("•")
				"lquote" -> anadirTexto("‘")
				"rquote" -> anadirTexto("’")
				"ldblquote" -> anadirTexto("“")
				"rdblquote" -> anadirTexto("”")
				"emspace", "enspace", "qmspace" -> anadirTexto(" ")
				// Párrafos
				"par", "sect", "page" -> finParrafo()
				"pard" -> {
					e.esquema = -1
					e.estilo = 0
					e.enTabla = false
					e.nivelLista = 0
				}
				"s" -> e.estilo = p ?: 0
				"outlinelevel" -> e.esquema = p ?: -1
				"intbl" -> e.enTabla = true
				"ilvl" -> e.nivelLista = (p ?: 0).coerceIn(0, 8)
				// Tablas
				"cell" -> if (e.destino == Destino.TEXTO) finCelda()
				"nestcell" -> if (e.destino == Destino.TEXTO) moverACelda()
				"row" -> if (e.destino == Destino.TEXTO) finFila()
				"trowd" -> filaEncabezado = false
				"trhdr" -> filaEncabezado = true
			}
		}

		private fun entrarEn(destino: Destino) {
			e.destino = destino
			e.destinoPropio = true
		}

		private fun unicode(p: Int?) {
			var v = p ?: return
			if (v < 0) v += 65536
			anadirTexto(v.toChar().toString())
			saltar = e.uc
		}

		private fun confirmarEstilo() {
			val nombre = nombreEstilo.toString().trim().trimEnd(';').trim()
			nombreEstilo.setLength(0)
			if (estiloDefinido < 0 || nombre.isEmpty()) return
			if (estiloDefinido in nivelEstilo) return
			val coincidencia = TITULO_ESTILO.find(nombre)
			if (coincidencia != null) {
				nivelEstilo[estiloDefinido] = coincidencia.groupValues[2].toInt().coerceIn(1, 6)
			} else if (nombre.equals("title", true) || nombre.equals("título", true)) {
				nivelEstilo[estiloDefinido] = 1
			}
		}

		private fun cerrarGrupo() {
			if (pila.isEmpty()) return
			val cerrado = e
			e = pila.removeAt(pila.size - 1)
			if (cerrado.destinoPropio) {
				when (cerrado.destino) {
					Destino.NOTA -> if (cerrado.numNota in 1..notas.size) {
						notas[cerrado.numNota - 1] = Textos.unirEspacios(nota.toString().replace('\n', ' ')).trim()
						nota.setLength(0)
					}
					Destino.MARCADOR -> {
						val nombre = marcador.toString().trim()
						if (nombre.isNotEmpty() && nombre != "_GoBack") anclasPendientes += "#$nombre"
						marcador.setLength(0)
					}
					else -> Unit
				}
			}
			if (cerrado.esResultado && cerrado.inicioResultado >= 0 && e.destino == Destino.TEXTO) {
				val destino = cerrado.campo?.instruccion?.toString()?.let { destinoCampo(it) }
				if (destino != null && parrafo.length > cerrado.inicioResultado) {
					enlaces += Enlace(cerrado.inicioResultado, parrafo.length, destino)
				}
			}
			if (cerrado.destino == Destino.ESTILOS && e.destino == Destino.ESTILOS && nombreEstilo.isNotBlank()) confirmarEstilo()
		}

		// --- Notas, párrafos y tablas ---------------------------------------------------

		private fun iniciarNota() {
			if (e.destino != Destino.TEXTO) return entrarEn(Destino.SALTAR)
			notas.add("")
			val numero = notas.size
			val inicio = parrafo.length
			parrafo.append('[').append(numero).append(']')
			enlaces += Enlace(inicio, parrafo.length, "#rtfnota-$numero")
			marcas = marcas or Marca.LLAMADA_NOTA
			nota.setLength(0)
			entrarEn(Destino.NOTA)
			e.numNota = numero
		}

		private fun finParrafo() {
			when (e.destino) {
				Destino.NOTA -> {
					nota.append(' ')
					return
				}
				Destino.TEXTO -> Unit
				else -> return
			}
			if (e.enTabla) {
				moverACelda()
				return
			}
			emitirTabla()
			emitirParrafo()
		}

		private fun limpiarParrafo() {
			parrafo.setLength(0)
			enlaces.clear()
			marcas = 0
			prefijo.setLength(0)
			tieneLista = false
			parrafoSinNegrita = false
			parrafoTamano = 0
		}

		/**
		 * Documentos sin estilos de título: los párrafos cortos en negrita con
		 * letra más grande que la del cuerpo son encabezados; el nivel sale del
		 * tamaño (el mayor, nivel 1).
		 */
		private fun titulosPorFormato() {
			if (c.tieneEncabezados() || candidatos.isEmpty()) return
			val cuerpo = tamanos.maxByOrNull { it.value }?.key ?: return
			val tamanosTitulo = candidatos.map { it.second }.filter { it > cuerpo }.distinct().sortedDescending()
			for ((bloque, tamano) in candidatos) {
				val nivel = tamanosTitulo.indexOf(tamano) + 1
				if (nivel > 0) c.cambiarAEncabezado(bloque, nivel.coerceAtMost(6))
			}
		}

		private fun registrarAnclas() {
			for (a in anclasPendientes) c.ancla(a)
			anclasPendientes.clear()
		}

		private fun emitirParrafo() {
			if (parrafo.isBlank()) {
				limpiarParrafo()
				return
			}
			val texto = parrafo.toString()
			val nivel = if (e.esquema in 0..8) e.esquema + 1 else nivelEstilo[e.estilo] ?: 0
			var tipo = TipoBloque.PARRAFO
			var nivelBloque = 0
			var antes = ""
			if (nivel > 0 && texto.length <= 400) {
				tipo = TipoBloque.ENCABEZADO
				nivelBloque = nivel.coerceIn(1, 6)
			} else if (tieneLista) {
				tipo = TipoBloque.ELEMENTO_LISTA
				nivelBloque = e.nivelLista + 1
				val marcaLista = prefijo.toString().replace('\t', ' ').trim()
				if (marcaLista.any { it.isLetterOrDigit() }) antes = "$marcaLista "
			}
			var m = marcas
			if (tipo == TipoBloque.ELEMENTO_LISTA && ultimoTipo != TipoBloque.ELEMENTO_LISTA) m = m or Marca.INICIO_LISTA
			val desplazados = if (antes.isEmpty()) enlaces.toList() else enlaces.map { Enlace(it.inicio + antes.length, it.fin + antes.length, it.destino) }
			registrarAnclas()
			val indice = c.bloque(tipo, antes + texto, nivelBloque, desplazados, m)
			if (indice >= 0) {
				ultimoTipo = tipo
				val limpio = texto.trim()
				if (tipo == TipoBloque.PARRAFO && !parrafoSinNegrita && limpio.length <= 150 && !limpio.endsWith(".") && !limpio.endsWith(",")) {
					candidatos += indice to parrafoTamano
				}
			}
			limpiarParrafo()
		}

		/** El párrafo en curso pasa a la celda (dentro de una tabla). */
		private fun moverACelda() {
			if (parrafo.isNotBlank()) {
				if (celda.isNotEmpty()) celda.append(' ')
				celda.append(parrafo)
			}
			limpiarParrafo()
		}

		private fun finCelda() {
			moverACelda()
			fila.add(celda.toString().trim())
			celda.setLength(0)
		}

		private fun finFila() {
			if (celda.isNotBlank()) finCelda()
			if (fila.isNotEmpty()) {
				if (filas.isEmpty()) primeraFilaEncabezado = filaEncabezado
				filas.add(fila)
			}
			fila = ArrayList()
		}

		private fun emitirTabla() {
			if (fila.isNotEmpty()) finFila()
			if (filas.isEmpty()) return
			registrarAnclas()
			val utiles = filas.map { f -> f.map { Textos.unirEspacios(it.replace('\n', ' ')).trim() } }.filter { f -> f.any { it.isNotEmpty() } }
			filas.clear()
			if (utiles.isEmpty()) return
			val columnas = utiles.maxOf { it.size }
			if (columnas <= 1) {
				for (f in utiles) c.parrafo(f.firstOrNull().orEmpty())
				ultimoTipo = TipoBloque.PARRAFO
				return
			}
			val primero = c.tamano
			c.bloque(TipoBloque.PARRAFO, "Tabla de ${utiles.size} filas y $columnas columnas")
			val conEncabezado = utiles.size >= 2 && (primeraFilaEncabezado || ctx.opciones.tablasConEncabezados)
			val cabeceras = if (conEncabezado) utiles[0] else emptyList()
			for ((i, f) in utiles.withIndex()) {
				val linea = when {
					i == 0 && conEncabezado -> f.joinToString(" | ")
					ctx.opciones.tablasConEncabezados && cabeceras.isNotEmpty() -> f.mapIndexed { j, valor ->
						val cabecera = cabeceras.getOrNull(j).orEmpty()
						if (cabecera.isBlank() || valor.isBlank()) valor else "$cabecera: $valor"
					}.filter { it.isNotBlank() }.joinToString("; ")
					else -> f.joinToString(" | ")
				}
				c.bloque(TipoBloque.FILA_TABLA, linea.ifBlank { "(fila vacía)" }, marcas = if (i == 0 && conEncabezado) Marca.ENCABEZADO_TABLA else 0)
			}
			if (c.tamano > primero) c.anadirMarca(primero, Marca.INICIO_TABLA)
			ultimoTipo = TipoBloque.FILA_TABLA
			primeraFilaEncabezado = false
		}
	}
}
