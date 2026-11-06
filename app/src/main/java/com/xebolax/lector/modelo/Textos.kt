package com.xebolax.lector.modelo

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Limpieza de texto y detección de codificaciones, comunes a todos los formatos. */
object Textos {

	/** Guion blando, espacios de anchura cero y marcas de dirección que el lector de pantalla no debe ver. */
	private val INVISIBLES = Regex("[­​‌‍⁠﻿‎‏]")
	private val ESPACIOS = Regex("[ \t  -   　]+")

	/** Quita invisibles y convierte los espacios especiales en espacios normales (sin recortar). */
	fun limpiar(texto: String): String {
		if (texto.isEmpty()) return texto
		return INVISIBLES.replace(texto, "").replace(' ', ' ').trim()
	}

	/** Une espacios seguidos en uno (para HTML y PDF). */
	fun unirEspacios(texto: String): String = ESPACIOS.replace(texto, " ")

	/**
	 * Limpia el texto de un bloque y recoloca los enlaces: quita invisibles y
	 * los espacios del principio y del final sin que los enlaces se desplacen.
	 */
	fun recortarConEnlaces(texto: String, enlaces: List<Enlace>): Pair<String, List<Enlace>> {
		val sb = StringBuilder(texto.length)
		// mapa[i] = posición en el resultado del carácter i del original
		val mapa = IntArray(texto.length + 1)
		for (i in texto.indices) {
			mapa[i] = sb.length
			val c = texto[i]
			when {
				c == '­' || c == '​' || c == '‌' || c == '‍' || c == '⁠' || c == '﻿' ||
					c == '‎' || c == '‏' -> Unit
				c == ' ' -> sb.append(' ')
				else -> sb.append(c)
			}
		}
		mapa[texto.length] = sb.length
		var inicio = 0
		while (inicio < sb.length && sb[inicio].isWhitespace()) inicio++
		var fin = sb.length
		while (fin > inicio && sb[fin - 1].isWhitespace()) fin--
		val resultado = sb.substring(inicio, fin)
		val nuevos = enlaces.mapNotNull { e ->
			val a = (mapa[e.inicio.coerceIn(0, texto.length)] - inicio).coerceIn(0, resultado.length)
			val b = (mapa[e.fin.coerceIn(0, texto.length)] - inicio).coerceIn(0, resultado.length)
			if (b > a) Enlace(a, b, e.destino) else null
		}
		return resultado to nuevos
	}

	/**
	 * Decodifica bytes de texto: BOM (UTF-8/16/32), charset declarado (XML o
	 * HTML, en los primeros 2 KB), UTF-8 estricto y, si falla, windows-1252
	 * (o la codificación indicada como alternativa).
	 */
	fun decodificar(bytes: ByteArray, alternativa: String = "windows-1252"): String {
		if (bytes.size >= 4) {
			val b0 = bytes[0].toInt() and 0xFF
			val b1 = bytes[1].toInt() and 0xFF
			val b2 = bytes[2].toInt() and 0xFF
			val b3 = bytes[3].toInt() and 0xFF
			if (b0 == 0 && b1 == 0 && b2 == 0xFE && b3 == 0xFF) return String(bytes, 4, bytes.size - 4, Charset.forName("UTF-32BE"))
			if (b0 == 0xFF && b1 == 0xFE && b2 == 0 && b3 == 0) return String(bytes, 4, bytes.size - 4, Charset.forName("UTF-32LE"))
		}
		if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
			return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
		}
		if (bytes.size >= 2) {
			if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
			if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
			// UTF-16 sin BOM: muchos ceros en posiciones pares o impares
			if (bytes.size >= 64) {
				var pares = 0
				var impares = 0
				for (i in 0 until minOf(bytes.size, 2000)) if (bytes[i].toInt() == 0) { if (i % 2 == 0) pares++ else impares++ }
				val muestra = minOf(bytes.size, 2000) / 2
				if (impares > muestra * 0.4 && pares < muestra * 0.05) return String(bytes, Charsets.UTF_16LE)
				if (pares > muestra * 0.4 && impares < muestra * 0.05) return String(bytes, Charsets.UTF_16BE)
			}
		}
		declarado(bytes)?.let { nombre ->
			runCatching { Charset.forName(nombre) }.getOrNull()?.let { cs ->
				if (cs != Charsets.UTF_8) return String(bytes, cs)
			}
		}
		return try {
			Charsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes)).toString()
		} catch (_: CharacterCodingException) {
			// No es UTF-8: Windows (lo habitual) o MS-DOS (TXT antiguos, CP850 o CP437); gana la que dé texto con sentido
			val candidatas = listOf(alternativa, "IBM850", "IBM437").mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }.distinct()
			val muestra = bytes.copyOf(minOf(bytes.size, 64 * 1024))
			val mejor = candidatas.maxByOrNull { puntuacion(String(muestra, it)) } ?: Charsets.ISO_8859_1
			String(bytes, mejor)
		}
	}

	private val RANGO_CAJAS = '\u2500'..'\u259F'
	private val RANGO_C1 = '\u0080'..'\u009F'
	private const val SUSTITUTO = '\uFFFD'
	/** Signos que en un texto normal casi no salen, pero sí al leer MS-DOS como Windows. */
	private const val RAROS = "\u0192\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\u017D\u02DC\u2122\u0161\u0153\u017E\u0178\u00A4\u00A6\u00A8\u00AF\u00B4\u00B8"
	private val LETRAS = "áéíóúñüÁÉÍÓÚÑÜàèìòùçÇ¿¡ºª€"

	/** Cuánto parece texto de verdad: letras con tilde y eñes suman; dibujos de cajas y controles restan. */
	private fun puntuacion(t: String): Int {
		var p = 0
		for (ch in t) {
			when {
				ch in LETRAS -> p += 2
				ch in RANGO_CAJAS || ch in RANGO_C1 || ch == SUSTITUTO || ch in RAROS -> p -= 3
			}
		}
		return p
	}

	private val DECLARACION = Regex("""(?:encoding|charset)\s*=\s*["']?([A-Za-z0-9_\-:.]+)""", RegexOption.IGNORE_CASE)

	/** Codificación declarada en la cabecera XML o en el meta de HTML. */
	fun declarado(bytes: ByteArray): String? {
		val cabecera = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
		return DECLARACION.find(cabecera)?.groupValues?.get(1)
	}

	/** Número de palabras aproximado. */
	fun palabras(texto: String): Int {
		var n = 0
		var dentro = false
		for (c in texto) {
			if (c.isLetterOrDigit()) {
				if (!dentro) { n++; dentro = true }
			} else dentro = false
		}
		return n
	}
}
