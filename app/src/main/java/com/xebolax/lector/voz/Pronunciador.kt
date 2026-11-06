package com.xebolax.lector.voz

import android.content.Context
import com.xebolax.lector.datos.BaseDatos

/**
 * Diccionario de pronunciación: sustituciones que se aplican al texto que
 * se envía a la voz (nunca al que se muestra). Pueden ser generales o de un
 * libro, de palabra completa o expresiones regulares.
 */
class Pronunciador(contexto: Context, libro: Long?) {

	private val reglas: List<Pair<Regex, String>> = BaseDatos.obtener(contexto).pronunciaciones(libro, soloActivas = true)
		.mapNotNull { p ->
			runCatching {
				val opciones = if (p.mayusculas) emptySet() else setOf(RegexOption.IGNORE_CASE)
				val patron = if (p.regex) p.buscar else "(?<![\\p{L}\\p{N}])" + Regex.escape(p.buscar) + "(?![\\p{L}\\p{N}])"
				Regex(patron, opciones) to (if (p.regex) p.reemplazar else p.reemplazar.replace("\\", "\\\\").replace("$", "\\$"))
			}.getOrNull()
		}

	val vacio get() = reglas.isEmpty()

	fun aplicar(texto: String): String {
		var r = texto
		for ((patron, sustituto) in reglas) r = runCatching { patron.replace(r, sustituto) }.getOrDefault(r)
		return r
	}
}
