package com.xebolax.lector.modelo

/**
 * Divide un texto en frases (para la voz y para partir párrafos largos),
 * como el segmentador del Visor Universal:
 *
 * - Fin de frase: . ? ! … seguidos de comillas o paréntesis de cierre y un espacio.
 * - No cortan las abreviaturas comunes (Sr., Dra., etc.), las iniciales de
 *   una letra ni los números con punto (3.5, 1.º).
 * - El salto de línea siempre cierra la frase.
 * - Las frases muy largas se cortan por , ; : — a partir de maximo caracteres.
 */
object Segmentador {

	private const val CIERRES = "\"'”’»)]}"
	private val ABREVIATURAS = setOf(
		"sr", "sra", "srta", "sres", "dr", "dra", "dres", "lic", "ing", "arq", "prof", "profa", "d", "dña", "da",
		"ud", "uds", "vd", "vds", "etc", "pág", "págs", "pag", "cap", "caps", "vol", "vols", "núm", "num", "nº",
		"art", "arts", "fig", "figs", "ej", "p", "pp", "ed", "eds", "trad", "coord", "aprox", "min", "máx", "max",
		"av", "avda", "c", "tel", "tlf", "dpto", "depto", "admón", "cía", "sta", "sto", "s", "ss", "op", "cit",
		"mr", "mrs", "ms", "st", "jr", "vs", "no", "inc", "ltd", "co", "e.g", "i.e", "approx", "dept", "fr",
		"a.c", "d.c", "a. c", "d. c", "j.c", "ee.uu", "uu", "ss.mm", "u.s", "a.m", "p.m",
	)

	/** Rangos [inicio, fin) de cada frase, sin los espacios de alrededor. */
	fun frases(texto: String, maximo: Int = 320): List<IntRange> {
		val resultado = ArrayList<IntRange>()
		var inicio = 0
		val n = texto.length
		var i = 0
		while (i < n) {
			val ch = texto[i]
			var corte = -1
			if (ch == '\n') {
				corte = i
			} else if (ch == '.' || ch == '?' || ch == '!' || ch == '…') {
				var j = i + 1
				// Puntos suspensivos y signos repetidos
				while (j < n && (texto[j] == '.' || texto[j] == '?' || texto[j] == '!' || texto[j] == '…')) j++
				while (j < n && CIERRES.indexOf(texto[j]) >= 0) j++
				if (j >= n || texto[j].isWhitespace()) {
					// Puntos suspensivos seguidos de minúscula: la frase sigue ("Tolkien… y se fue")
					val suspensivos = ch == '…' || (j - i >= 3 && texto[i + 1] == '.')
					var s = j
					while (s < n && texto[s].isWhitespace() && texto[s] != '\n') s++
					val sigueMinuscula = s < n && texto[s].isLowerCase()
					if (suspensivos && sigueMinuscula) corte = -1
					else if (ch != '.' || suspensivos || !esAbreviatura(texto, inicio, i)) corte = j
				}
				if (corte < 0) i = j - 1
			}
			if (corte >= 0) {
				anadir(texto, inicio, corte, maximo, resultado)
				inicio = corte
				i = corte
			}
			i++
		}
		anadir(texto, inicio, n, maximo, resultado)
		return resultado
	}

	private fun esAbreviatura(texto: String, inicioFrase: Int, punto: Int): Boolean {
		// Número con punto: "3.5" no llega aquí (no hay espacio); "1. " en listas sí corta
		var k = punto - 1
		while (k >= inicioFrase && (texto[k].isLetter() || texto[k] == '.')) k--
		val palabra = texto.substring(k + 1, punto).lowercase()
		if (palabra.isEmpty()) return false
		// Inicial de una letra en mayúscula (J. R. R. Tolkien)
		if (palabra.length == 1 && texto[punto - 1].isUpperCase()) return true
		if (palabra in ABREVIATURAS) return true
		// La siguiente palabra empieza en minúscula: no era fin de frase
		var s = punto + 1
		while (s < texto.length && texto[s].isWhitespace()) s++
		return s < texto.length && texto[s].isLowerCase() && palabra.length <= 4
	}

	private fun anadir(texto: String, desde: Int, hasta: Int, maximo: Int, salida: MutableList<IntRange>) {
		var a = desde
		var b = hasta
		while (a < b && texto[a].isWhitespace()) a++
		while (b > a && texto[b - 1].isWhitespace()) b--
		if (a >= b) return
		if (b - a <= maximo) {
			salida += a until b
			return
		}
		// Frase demasiado larga: se corta por la última pausa antes del máximo
		var inicio = a
		while (b - inicio > maximo) {
			var corte = -1
			for (k in (inicio + maximo).coerceAtMost(b - 1) downTo inicio + maximo / 3) {
				val c = texto[k]
				if ((c == ',' || c == ';' || c == ':' || c == '—' || c == '–') && k + 1 < b && texto[k + 1].isWhitespace()) {
					corte = k + 1
					break
				}
			}
			if (corte < 0) {
				// Sin pausas: por el último espacio
				corte = texto.lastIndexOf(' ', inicio + maximo).takeIf { it > inicio } ?: (inicio + maximo)
			}
			salida += inicio until corte
			inicio = corte
			while (inicio < b && texto[inicio].isWhitespace()) inicio++
		}
		if (inicio < b) salida += inicio until b
	}
}
