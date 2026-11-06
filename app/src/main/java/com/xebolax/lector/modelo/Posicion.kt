package com.xebolax.lector.modelo

/**
 * Posición de lectura. En texto: bloque y carácter dentro del bloque. En
 * audio: pista y milisegundos. Se guarda como texto ("t:120:35", "a:2:61000").
 */
data class Posicion(val audio: Boolean, val a: Int, val b: Long) {

	val bloque: Int get() = a
	val caracter: Int get() = b.toInt()
	val pista: Int get() = a
	val ms: Long get() = b

	override fun toString() = (if (audio) "a:" else "t:") + "$a:$b"

	companion object {
		val INICIO = Posicion(false, 0, 0)

		fun texto(bloque: Int, caracter: Int = 0) = Posicion(false, bloque, caracter.toLong())
		fun audio(pista: Int, ms: Long) = Posicion(true, pista, ms)

		fun leer(texto: String?): Posicion? {
			val partes = texto?.split(':') ?: return null
			if (partes.size != 3) return null
			val a = partes[1].toIntOrNull() ?: return null
			val b = partes[2].toLongOrNull() ?: return null
			return Posicion(partes[0] == "a", a, b)
		}
	}
}
