package com.xebolax.lector

import com.xebolax.lector.modelo.Textos
import org.junit.Assert.assertEquals
import org.junit.Test

/** Codificación de los textos: UTF-8, Windows y los TXT antiguos de MS-DOS. */
class TextosTest {

	private val frase = "El niño soñó con una canción en el año pasado: ¿qué pequeño árbol había allí?"

	@Test
	fun msDos850() = assertEquals(frase, Textos.decodificar(frase.toByteArray(charset("IBM850"))))

	@Test
	fun windows1252() = assertEquals(frase, Textos.decodificar(frase.toByteArray(charset("windows-1252"))))

	@Test
	fun utf8() = assertEquals(frase, Textos.decodificar(frase.toByteArray(Charsets.UTF_8)))
}
