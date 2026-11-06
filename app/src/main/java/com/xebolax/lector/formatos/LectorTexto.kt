package com.xebolax.lector.formatos

import com.xebolax.lector.modelo.ConstructorDocumento
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.TipoBloque
import com.xebolax.lector.modelo.Textos

/**
 * Texto plano (TXT, LOG...), subtítulos (SRT/VTT) y tablas CSV/TSV.
 *
 * - Codificación detectada (BOM, UTF-8, windows-1252...).
 * - Los párrafos se separan por líneas en blanco. Un bloque en el que casi
 *   todas las líneas son cortas (versos, listas) se lee línea a línea; si
 *   no, sus líneas se unen en un párrafo.
 * - "Capítulo 3", "PARTE II", "Prólogo"... solos en su línea se convierten
 *   en encabezados para poder navegar por ellos.
 */
object LectorTexto : LectorFormato {

	private const val MAXIMO = 96L * 1024 * 1024
	private const val NUMEROS = "uno|una|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|trece|catorce|quince|" +
		"dieci\\w+|veinte|veinti\\w+|treinta|primer[oa]?|segund[oa]|tercer[oa]?|cuart[oa]|quint[oa]|sext[oa]|s[eé]ptim[oa]|" +
		"octav[oa]|noven[oa]|d[eé]cim[oa]|[uú]ltim[oa]|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|" +
		"\\w+teen|twenty|first|second|third|fourth|fifth|last"
	private val TITULO = Regex(
		"""^(?:(parte|libro|part|book|tomo|volumen|volume)|(cap[ií]tulo|chapter|cap\.|canto|lecci[oó]n|tema|secci[oó]n|section)|(pr[oó]logo|ep[ií]logo|introducci[oó]n|prefacio|pref[aá]cio|prologue|epilogue|introduction|preface|ap[eé]ndice|appendix|agradecimientos|acknowledgements|nota del autor|dedicatoria|índice|indice|contents))\b[\s.:\-—–]*([0-9]+|[ivxlcdm]+\b|$NUMEROS)?[\s.:\-—–]*.{0,70}$""",
		RegexOption.IGNORE_CASE,
	)

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val avisoTamano = fuente.tamano > MAXIMO
		val bytes = fuente.abrir().use { entrada ->
			if (avisoTamano) {
				val b = ByteArray(MAXIMO.toInt())
				var n = 0
				while (n < b.size) {
					val r = entrada.read(b, n, b.size - n)
					if (r < 0) break
					n += r
				}
				b.copyOf(n)
			} else entrada.readBytes()
		}
		val texto = Textos.decodificar(bytes)
		val c = ctx.nuevoConstructor()
		c.titulo = fuente.nombre.substringBeforeLast('.')
		if (avisoTamano) c.avisos += "El archivo es muy grande: solo se muestran los primeros ${MAXIMO / 1024 / 1024} MB."
		when (fuente.extension) {
			"csv" -> tabla(c, texto, detectarSeparador(texto), ctx)
			"tsv" -> tabla(c, texto, '\t', ctx)
			"srt", "vtt" -> subtitulos(c, texto)
			else -> texto(c, texto, ctx)
		}
		return Contenido.Texto(c.construir())
	}

	/** Convierte texto en bloques (también lo usan el portapapeles y lo compartido con la app). */
	fun texto(c: ConstructorDocumento, texto: String, ctx: ContextoLectura) {
		val lineas = texto.replace("\r\n", "\n").replace('\r', '\n').replace('\u000C', '\n').split('\n')
		val parrafo = ArrayList<String>()
		var hayParte = false
		var contador = 0
		fun cerrar() {
			if (parrafo.isEmpty()) return
			val sinVacias = parrafo.map { it.trimEnd() }
			if (ctx.opciones.txtLineaAParrafo) {
				sinVacias.forEach { linea -> anadirLinea(c, linea, hayParte).also { if (it) hayParte = true } }
			} else if (sinVacias.size == 1) {
				anadirLinea(c, sinVacias[0], hayParte).also { if (it) hayParte = true }
			} else {
				val cortas = sinVacias.count { it.trim().length < 60 }
				if (cortas >= sinVacias.size * 0.8) {
					// Versos o lista: línea a línea
					sinVacias.forEach { linea -> anadirLinea(c, linea, hayParte).also { if (it) hayParte = true } }
				} else {
					c.parrafo(unir(sinVacias))
				}
			}
			parrafo.clear()
		}
		for (linea in lineas) {
			if (++contador % 5000 == 0) ctx.comprobar()
			if (linea.isBlank()) cerrar() else parrafo += linea
		}
		cerrar()
	}

	/** Une líneas de un párrafo, quitando los guiones de corte de palabra. */
	private fun unir(lineas: List<String>): String {
		val sb = StringBuilder()
		for (l in lineas) {
			val t = l.trim()
			if (sb.isNotEmpty()) {
				if (sb.last() == '-' && sb.length > 1 && sb[sb.length - 2].isLetter() && t.firstOrNull()?.isLowerCase() == true) {
					sb.setLength(sb.length - 1)
				} else sb.append(' ')
			}
			sb.append(t)
		}
		return sb.toString()
	}

	/** Añade una línea suelta; devuelve true si era el título de una parte. */
	private fun anadirLinea(c: ConstructorDocumento, linea: String, hayParte: Boolean): Boolean {
		val t = linea.trim()
		if (t.isEmpty()) return false
		// "Parte" o "Capítulo" solo cuentan seguidos de un número ("Parte de la gente..." no es un título)
		val m = TITULO.matchEntire(t)?.takeIf { it.groupValues[3].isNotEmpty() || it.groupValues[4].isNotEmpty() }
		if (m != null && t.length <= 90 && !t.endsWith(",")) {
			val esParte = m.groupValues[1].isNotEmpty()
			val nivel = if (esParte) 1 else if (hayParte) 2 else 1
			c.encabezado(t, nivel)
			return esParte
		}
		// Línea corta en mayúsculas (título de capítulo sin palabra clave)
		val letras = t.count { it.isLetter() }
		if (letras >= 4 && t.length <= 60 && t.none { it.isLowerCase() } && t.count { it.isUpperCase() } >= letras * 0.9 &&
			!t.endsWith(".") && !t.endsWith(",")
		) {
			c.encabezado(t, if (hayParte) 2 else 1)
			return false
		}
		c.parrafo(t)
		return false
	}

	private fun detectarSeparador(texto: String): Char {
		val muestra = texto.lineSequence().take(10).joinToString("\n")
		return listOf(';', ',', '\t', '|').maxByOrNull { s -> muestra.count { it == s } } ?: ','
	}

	/** CSV/TSV: una fila por bloque, con la primera fila como encabezados ("Columna: valor; ..."). */
	private fun tabla(c: ConstructorDocumento, texto: String, separador: Char, ctx: ContextoLectura) {
		val filas = leerCsv(texto, separador)
		if (filas.isEmpty()) return
		val encabezados = filas.first()
		c.bloque(TipoBloque.PARRAFO, "Tabla de ${filas.size} filas y ${encabezados.size} columnas", marcas = Marca.INICIO_TABLA)
		for ((i, fila) in filas.withIndex()) {
			if (i % 2000 == 0) ctx.comprobar()
			if (fila.all { it.isBlank() }) continue
			val texto = if (i == 0) fila.joinToString(" | ")
			else if (ctx.opciones.tablasConEncabezados) fila.mapIndexed { j, v ->
				val e = encabezados.getOrNull(j).orEmpty()
				if (e.isBlank() || v.isBlank()) v else "$e: $v"
			}.filter { it.isNotBlank() }.joinToString("; ")
			else fila.joinToString(" | ")
			c.bloque(TipoBloque.FILA_TABLA, texto, marcas = if (i == 0) Marca.ENCABEZADO_TABLA else 0)
		}
	}

	private fun leerCsv(texto: String, separador: Char): List<List<String>> {
		val filas = ArrayList<List<String>>()
		var fila = ArrayList<String>()
		val campo = StringBuilder()
		var comillas = false
		var i = 0
		while (i < texto.length) {
			val ch = texto[i]
			when {
				comillas && ch == '"' && i + 1 < texto.length && texto[i + 1] == '"' -> { campo.append('"'); i++ }
				ch == '"' -> comillas = !comillas
				!comillas && ch == separador -> { fila += campo.toString().trim(); campo.setLength(0) }
				!comillas && (ch == '\n' || ch == '\r') -> {
					if (ch == '\r' && i + 1 < texto.length && texto[i + 1] == '\n') i++
					fila += campo.toString().trim()
					campo.setLength(0)
					filas += fila
					fila = ArrayList()
				}
				else -> campo.append(ch)
			}
			i++
		}
		if (campo.isNotEmpty() || fila.isNotEmpty()) {
			fila += campo.toString().trim()
			filas += fila
		}
		return filas
	}

	/** Subtítulos: solo el texto de cada subtítulo, con su tiempo de inicio como página. */
	private fun subtitulos(c: ConstructorDocumento, texto: String) {
		val tiempo = Regex("""^(\d{1,2}:)?\d{2}:\d{2}[.,]\d{3}\s*-->""")
		val bloques = texto.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
		for (b in bloques) {
			val lineas = b.lines().filter { it.isNotBlank() && it.trim() != "WEBVTT" }
			val marca = lineas.firstOrNull { tiempo.containsMatchIn(it.trim()) }
			val textoSub = lineas.filter { !tiempo.containsMatchIn(it.trim()) && it.trim().toIntOrNull() == null }
				.joinToString(" ") { it.replace(Regex("<[^>]+>"), "").trim() }
			if (textoSub.isBlank()) continue
			marca?.let { c.pagina(it.substringBefore("-->").trim().substringBeforeLast(',').substringBeforeLast('.')) }
			c.parrafo(textoSub)
		}
	}
}
