package com.xebolax.lector.formatos

/**
 * Braille de 6 puntos: tablas de «braille ASCII» y retrotraducción del braille
 * español de grado 1 (signografía básica de la Comisión Braille Española).
 *
 * Una celda es un número de 0 a 63 con un bit por punto: punto 1 = 1,
 * punto 2 = 2, punto 3 = 4, punto 4 = 8, punto 5 = 16 y punto 6 = 32 (como en
 * Unicode, U+2800 + celda). Las tablas son propias, escritas a partir de las
 * normas de la CBE y comprobadas con libros reales (no se copian archivos de
 * liblouis).
 */
object Braille {

	const val NUMERO = 0x3C        // 3456
	const val MAYUSCULA = 0x28     // 46

	/** Braille ASCII norteamericano (BRF): el carácter de cada celda (índice = celda). */
	private const val TABLA_BRF = " A1B'K2L@CIF/MSP\"E3H9O6R^DJG>NTQ,*5<-U8V.%[\$+X!&;:4\\0Z7(_?W]#Y)="
	/** Braille ASCII de la ONCE (.BRA, EBrai y QuickBraille). */
	private const val TABLA_ONCE = " a,b.k;l'cif/msp@e:h}o+r^djg|ntq_1?2-u<v{3960x\$&\"5*8>z=(%4w7#y)\\"

	private val BRF: Map<Char, Int> = HashMap<Char, Int>().apply {
		TABLA_BRF.forEachIndexed { celda, ch -> put(ch, celda) }
		// Las minúsculas valen lo mismo que su mayúscula (0x60-0x7E = 0x40-0x5E)
		TABLA_BRF.forEachIndexed { celda, ch -> if (ch in '@'..'^') putIfAbsent((ch.code + 32).toChar(), celda) }
	}
	private val ONCE: Map<Char, Int> = HashMap<Char, Int>().apply {
		TABLA_ONCE.forEachIndexed { celda, ch -> put(ch, celda) }
		// Braille ANSI (.BAN y fuentes de la CBE): seis celdas con letra propia
		put('í', 0x0C); put('Ì', 0x1C); put('ì', 0x1C); put('ó', 0x2C); put('é', 0x2E); put('á', 0x37); put('ú', 0x3E)
		// Por si alguna línea viene en mayúsculas
		for (ch in 'a'..'z') putIfAbsent(ch.uppercaseChar(), get(ch)!!)
	}

	enum class Tabla { BRF, ONCE }

	/** Las celdas de una línea (-1 si un carácter no es braille: se toma como espacio). */
	fun celdas(linea: String, tabla: Tabla): IntArray {
		val mapa = if (tabla == Tabla.BRF) BRF else ONCE
		return IntArray(linea.length) { i ->
			val ch = linea[i]
			when {
				ch in '⠀'..'⣿' -> (ch.code - 0x2800) and 0x3F
				else -> mapa[ch] ?: 0
			}
		}
	}

	fun unicode(celdas: IntArray, desde: Int = 0, hasta: Int = celdas.size): String =
		buildString { for (i in desde until hasta) append((0x2800 + celdas[i]).toChar()) }

	// --- Retrotraducción: braille español de grado 1 --------------------------------------------

	private val LETRAS = mapOf(
		0x01 to 'a', 0x03 to 'b', 0x09 to 'c', 0x19 to 'd', 0x11 to 'e', 0x0B to 'f', 0x1B to 'g', 0x13 to 'h', 0x0A to 'i', 0x1A to 'j',
		0x05 to 'k', 0x07 to 'l', 0x0D to 'm', 0x1D to 'n', 0x15 to 'o', 0x0F to 'p', 0x1F to 'q', 0x17 to 'r', 0x0E to 's', 0x1E to 't',
		0x25 to 'u', 0x27 to 'v', 0x3A to 'w', 0x2D to 'x', 0x3D to 'y', 0x35 to 'z', 0x3B to 'ñ',
		0x37 to 'á', 0x2E to 'é', 0x0C to 'í', 0x2C to 'ó', 0x3E to 'ú', 0x33 to 'ü', 0x2F to 'ç',
	)
	/** a-j como cifras (tras el signo de número). */
	private val CIFRAS = mapOf(0x01 to '1', 0x03 to '2', 0x09 to '3', 0x19 to '4', 0x11 to '5', 0x0B to '6', 0x1B to '7', 0x13 to '8', 0x0A to '9', 0x1A to '0')
	/** Cifras «bajas» (ordinales: #,o = 1.º). */
	private val CIFRAS_BAJAS = mapOf(0x02 to '1', 0x06 to '2', 0x12 to '3', 0x32 to '4', 0x22 to '5', 0x16 to '6', 0x36 to '7', 0x26 to '8', 0x14 to '9', 0x34 to '0')

	/** Signos de varias celdas (gana el más largo). */
	private val SECUENCIAS: List<Pair<IntArray, String>> = listOf(
		intArrayOf(0x38, 0x34, 0x34) to "‰", intArrayOf(0x38, 0x03, 0x0E) to "Bs", intArrayOf(0x20, 0x22, 0x02) to "//", intArrayOf(0x10, 0x14, 0x04) to "\\\\",
		intArrayOf(0x04, 0x04, 0x04) to "…",
		intArrayOf(0x23, 0x28, 0x1E, 0x28, 0x0D, 0x1C) to "™", intArrayOf(0x23, 0x28, 0x09, 0x1C) to "©", intArrayOf(0x23, 0x28, 0x17, 0x1C) to "®",
		intArrayOf(0x38, 0x11) to "€", intArrayOf(0x38, 0x0E) to "$", intArrayOf(0x38, 0x0F) to "$", intArrayOf(0x38, 0x09) to "₡", intArrayOf(0x38, 0x17) to "R$",
		intArrayOf(0x38, 0x3D) to "¥", intArrayOf(0x38, 0x03) to "₿", intArrayOf(0x38, 0x34) to "%", intArrayOf(0x38, 0x02) to "}", intArrayOf(0x38, 0x07) to "‖",
		intArrayOf(0x20, 0x02) to "/", intArrayOf(0x20, 0x26) to "\"", intArrayOf(0x20, 0x2F) to "&", intArrayOf(0x20, 0x24) to "",
		intArrayOf(0x10, 0x07) to "{", intArrayOf(0x10, 0x04) to "\\", intArrayOf(0x10, 0x05) to "⟨", intArrayOf(0x10, 0x2E) to "£", intArrayOf(0x10, 0x02) to "",
		intArrayOf(0x18, 0x09) to "¢", intArrayOf(0x28, 0x02) to "⟩", intArrayOf(0x28, 0x2A) to "*",
		intArrayOf(0x24, 0x24) to "—", intArrayOf(0x12, 0x12) to "—", intArrayOf(0x14, 0x14) to "", intArrayOf(0x30, 0x14) to "",
	)

	/** Pasa a texto las celdas de un párrafo (los espacios son la celda 0). */
	fun aTexto(c: IntArray): String {
		val sb = StringBuilder()
		val n = c.size
		var i = 0
		var numero = false
		var mayusculas = false
		fun en(k: Int) = if (k in 0 until n) c[k] else 0
		// ¿Abre? (signos que abren y cierran con la misma celda: ¿?, ¡!, comillas)
		fun abre(k: Int): Boolean {
			val antes = sb.lastOrNull()
			return (antes == null || antes.isWhitespace() || antes in "(«¿¡[—\"") && en(k + 1) != 0
		}
		while (i < n) {
			val x = c[i]
			if (x == 0) {
				if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
				numero = false
				mayusculas = false
				i++
				continue
			}
			if (x == NUMERO) {
				// Ordinal: #,o = 1.º; #;a = 2.ª; #:r = 3.er
				var k = i + 1
				val bajas = StringBuilder()
				while (en(k) in CIFRAS_BAJAS && en(k) !in CIFRAS) { bajas.append(CIFRAS_BAJAS[en(k)]); k++ }
				val tras = LETRAS[en(k)]
				if (bajas.isNotEmpty() && (tras == 'o' || tras == 'a' || tras == 'r')) {
					sb.append(bajas).append(when (tras) { 'o' -> ".º"; 'a' -> ".ª"; else -> ".er" })
					i = k + 1
					continue
				}
				numero = true
				i++
				continue
			}
			if (numero) {
				CIFRAS[x]?.let { sb.append(it); i++; continue }
				// Punto de millares y coma decimal: el número sigue
				if ((x == 0x04 || x == 0x02) && en(i + 1) in CIFRAS) { sb.append(if (x == 0x04) '.' else ','); i++; continue }
				// Punto 5 y una letra a-j dentro de un número: esa letra (8b, 234ae)
				if (x == 0x10 && en(i + 1) in CIFRAS) { sb.append(LETRAS[en(i + 1)]); i += 2; continue }
				when {
					x == 0x34 -> { sb.append('°'); i++; numero = false; continue }
					x == 0x33 && en(i + 1) == 0x33 -> { sb.append('″'); i += 2; numero = false; continue }
					x == 0x33 -> { sb.append('′'); i++; numero = false; continue }
					x == 0x26 && en(i + 1) == NUMERO -> { sb.append('×'); i++; numero = false; continue }
				}
				numero = false
			}
			// Arroba dentro de una palabra (correo): siempre @
			if (x == 0x10 && sb.lastOrNull()?.isLetterOrDigit() == true) { sb.append('@'); i++; continue }
			// Signos de varias celdas
			val seq = SECUENCIAS.firstOrNull { (s, _) -> s.indices.all { en(i + it) == s[it] } && i + s.size <= n }
			if (seq != null) {
				sb.append(seq.second)
				i += seq.first.size
				continue
			}
			if (x == MAYUSCULA) {
				if (en(i + 1) == MAYUSCULA) { mayusculas = true; i += 2; continue }
				val l = LETRAS[en(i + 1)]
				if (l != null) { sb.append(l.uppercaseChar()); i += 2; continue }
				i++
				continue
			}
			LETRAS[x]?.let { sb.append(if (mayusculas) it.uppercaseChar() else it); i++; continue }
			mayusculas = false
			when (x) {
				0x04 -> sb.append('.')
				0x02 -> sb.append(',')
				0x06 -> sb.append(';')
				0x12 -> sb.append(':')
				0x22 -> sb.append(if (abre(i)) '¿' else '?')
				0x16 -> sb.append(if (en(i + 1) == NUMERO) '+' else if (abre(i)) '¡' else '!')
				0x26 -> sb.append(if (abre(i)) '«' else '»')
				0x23 -> sb.append('(')
				0x1C -> sb.append(')')
				0x24 -> sb.append('-')
				// Tipografía especial (cursiva, negrita): se quita; suelta, es un asterisco
				0x14 -> if (en(i - 1) == 0 && en(i + 1) == 0) sb.append('*')
				0x10 -> sb.append('@')
				0x38 -> sb.append('|')
				0x32 -> sb.append('/')
				0x36 -> sb.append('=')
				// Recordatorio de comillas, superíndice y prefijos sueltos: no se dicen
				0x34, 0x21, 0x20, 0x30, 0x18, 0x08 -> Unit
				else -> sb.append((0x2800 + x).toChar())
			}
			i++
		}
		return sb.toString().replace(Regex(" +"), " ").trim()
	}

	// --- Maquetación: páginas, números de página y párrafos --------------------------------------

	/** Un trozo del libro: párrafo, título o separador, y la página en la que empieza. */
	class Trozo(val tipo: Tipo, val celdas: IntArray, val pagina: String?) {
		enum class Tipo { PARRAFO, TITULO, SEPARADOR }
	}

	/** Si la línea solo tiene números de página braille (#e    #i): sus números como texto; si no, null. */
	fun numerosDePagina(linea: IntArray): List<String>? {
		val tokens = mutableListOf<IntArray>()
		var actual = mutableListOf<Int>()
		for (x in linea) {
			if (x == 0) {
				if (actual.isNotEmpty()) tokens += actual.toIntArray()
				actual = mutableListOf()
			} else actual += x
		}
		if (actual.isNotEmpty()) tokens += actual.toIntArray()
		if (tokens.isEmpty() || tokens.size > 2) return null
		return tokens.map { t ->
			// Puede llevar una letra delante (página de continuación: a#12)
			val inicio = if (t.size >= 2 && t[0] != NUMERO && t[1] == NUMERO && t[0] in LETRAS) 1 else 0
			if (t.size < inicio + 2 || t[inicio] != NUMERO || t.drop(inicio + 1).any { it !in CIFRAS }) return null
			(if (inicio == 1) LETRAS[t[0]].toString() else "") + t.drop(inicio + 1).map { CIFRAS[it] }.joinToString("")
		}
	}

	private fun sangria(l: IntArray): Int = l.indexOfFirst { it != 0 }.let { if (it < 0) l.size else it }
	private fun recortar(l: IntArray): IntArray {
		var fin = l.size
		while (fin > 0 && l[fin - 1] == 0) fin--
		return l.copyOf(fin)
	}

	/**
	 * Líneas por página si el archivo no tiene saltos de página: la distancia
	 * más repetida entre las cabeceras con número (en los .BRA de la ONCE, 29);
	 * si no se encuentra, [porDefecto] si el total de líneas es múltiplo; si no, 0.
	 */
	fun lineasPorPagina(lineas: List<IntArray>, porDefecto: Int): Int {
		val cabeceras = lineas.indices.filter { i -> sangria(lineas[i]) >= 8 && numerosDePagina(lineas[i])?.size == 2 }
		val distancias = cabeceras.zipWithNext { a, b -> b - a }.filter { it in 15..60 }
		val masRepetida = distancias.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
		if (masRepetida != null) return masRepetida
		return if (porDefecto > 0 && lineas.size % porDefecto == 0) porDefecto else 0
	}

	/**
	 * Quita de cada página los números de página (cabecera y pie) y une las
	 * líneas en párrafos: una línea en blanco o con sangría de 1 a 3 empieza
	 * otro; con mucha sangría (centrada) es un título; las de 4 o 5 siguen el
	 * párrafo (listas con sangría francesa). El guion de final de línea que
	 * corta una palabra se quita al unirla.
	 */
	fun maquetar(paginas: List<List<IntArray>>): List<Trozo> {
		val trozos = mutableListOf<Trozo>()
		var actual = ArrayList<Int>()
		var tipoActual = Trozo.Tipo.PARRAFO
		var paginaActual: String? = null
		var paginaPendiente: String? = null
		var numeroPagina = 0
		fun cerrar() {
			if (actual.any { it != 0 }) trozos += Trozo(tipoActual, actual.toIntArray(), paginaActual)
			actual = ArrayList()
			tipoActual = Trozo.Tipo.PARRAFO
			paginaActual = null
		}
		fun empezar(tipo: Trozo.Tipo) {
			cerrar()
			tipoActual = tipo
			paginaActual = paginaPendiente
			paginaPendiente = null
		}
		/** ¿El guion final cierra un inciso abierto (« -además del inglés-»)? */
		fun cierraInciso(): Boolean {
			val n = actual.size
			for (k in n - 2 downTo 1) {
				if (actual[k] == 0x24) return actual[k - 1] == 0 && actual[k + 1] != 0
			}
			return false
		}
		for (pagina in paginas) {
			numeroPagina++
			val lineas = pagina.toMutableList()
			var etiqueta: String? = null
			// Cabecera: tinta y braille («#e     #i») o solo braille
			lineas.indexOfFirst { it.any { x -> x != 0 } }.takeIf { it in 0..1 }?.let { k ->
				val l = lineas[k]
				val numeros = if (sangria(l) >= 3) numerosDePagina(l) else null
				if (numeros != null) {
					etiqueta = if (numeros.size == 2) "${numeros[1]} (tinta ${numeros[0]})" else numeros[0]
					lineas[k] = IntArray(0)
				}
			}
			// Pie: un número solo, a la derecha, en las últimas líneas (número de página o de obra)
			lineas.indexOfLast { it.any { x -> x != 0 } }.takeIf { it >= lineas.size - 2 && it > 0 }?.let { k ->
				val l = lineas[k]
				if (sangria(l) >= 8 && numerosDePagina(l)?.size == 1) {
					if (etiqueta == null) etiqueta = numerosDePagina(l)!![0]
					lineas[k] = IntArray(0)
				}
			}
			// Número al final de la primera o la última línea con texto (BRF): tras 3 espacios o más
			for (k in listOf(lineas.indexOfFirst { it.any { x -> x != 0 } }, lineas.indexOfLast { it.any { x -> x != 0 } })) {
				if (k < 0) continue
				val l = recortar(lineas[k])
				val ultimoEspacio = l.lastIndexOf(0)
				if (ultimoEspacio < 3 || l.size - ultimoEspacio > 7) continue
				if ((1..3).any { l[ultimoEspacio - it] != 0 }) continue
				val numero = numerosDePagina(l.copyOfRange(ultimoEspacio + 1, l.size)) ?: continue
				if (etiqueta == null) etiqueta = numero[0]
				lineas[k] = l.copyOf(ultimoEspacio)
			}
			if (lineas.none { l -> l.any { it != 0 } }) continue
			// Las líneas que eran números de página no separan párrafos: un párrafo sigue en la
			// página siguiente si la anterior acaba en texto y esta empieza sin línea en blanco
			val quitadas = pagina.indices.filter { pagina[it].any { x -> x != 0 } && lineas[it].isEmpty() }.toSet()
			val utiles = lineas.filterIndexed { i, _ -> i !in quitadas }
			val blancoAlPrincipio = utiles.firstOrNull()?.none { it != 0 } ?: true
			lineas.clear()
			lineas.addAll(utiles.dropWhile { l -> l.none { it != 0 } })
			if (blancoAlPrincipio) cerrar()
			val blancoAlFinal = lineas.lastOrNull()?.none { it != 0 } ?: true
			while (lineas.isNotEmpty() && lineas.last().none { it != 0 }) lineas.removeAt(lineas.size - 1)
			paginaPendiente = etiqueta ?: numeroPagina.toString()
			// La página empieza a mitad de un párrafo: la marca va en ese párrafo si aún no tiene
			if (actual.isNotEmpty() && paginaActual == null) {
				paginaActual = paginaPendiente
				paginaPendiente = null
			}
			for (bruta in lineas) {
				val l = recortar(bruta)
				if (l.isEmpty()) {
					cerrar()
					continue
				}
				val s = sangria(l)
				val contenido = l.copyOfRange(s, l.size)
				// Línea de una sola celda repetida: separador (guiones, dos puntos) o subrayado de un título (puntos 1-4)
				if (contenido.size >= 6 && contenido.all { it == contenido[0] } && contenido[0] in setOf(0x24, 0x12, 0x09)) {
					cerrar()
					if (contenido[0] != 0x09) {
						trozos += Trozo(Trozo.Tipo.SEPARADOR, contenido, paginaPendiente)
						paginaPendiente = null
					}
					continue
				}
				when {
					// Centrada: título (varias líneas centradas seguidas, un solo título)
					s >= 6 -> {
						if (tipoActual != Trozo.Tipo.TITULO || actual.isEmpty()) empezar(Trozo.Tipo.TITULO) else actual.add(0)
						actual.addAll(contenido.toList())
					}
					// Sangría de párrafo
					s in 1..3 || actual.isEmpty() || tipoActual == Trozo.Tipo.TITULO -> {
						empezar(Trozo.Tipo.PARRAFO)
						actual.addAll(contenido.toList())
					}
					else -> {
						// Sigue el párrafo: palabra cortada con guion al final de la línea, o un espacio
						val n = actual.size
						val corte = n >= 2 && actual[n - 1] == 0x24 && actual[n - 2] in LETRAS && contenido[0] in LETRAS && !cierraInciso()
						if (corte) actual.removeAt(n - 1) else actual.add(0)
						actual.addAll(contenido.toList())
					}
				}
			}
			if (blancoAlFinal) cerrar()
		}
		cerrar()
		return trozos
	}
}
