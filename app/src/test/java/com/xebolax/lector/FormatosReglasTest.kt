package com.xebolax.lector

import com.xebolax.lector.formatos.LectorDocAntiguo
import com.xebolax.lector.formatos.LectorEpub
import com.xebolax.lector.modelo.Textos
import com.xebolax.lector.modelo.TipoBloque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reglas de los formatos corregidas en la auditoría de noviembre de 2026. */
class FormatosReglasTest {

	@Test
	fun utf8QueHablaDeOtroCharset() {
		val texto = "Para la web usa <meta charset=iso-8859-1>. El niño soñó con una canción."
		assertEquals(texto, Textos.decodificar(texto.toByteArray(Charsets.UTF_8)))
	}

	@Test
	fun charsetDeclaradoSigueMandando() {
		val texto = "<?xml version=\"1.0\" encoding=\"windows-1252\"?><p>El niño soñó</p>"
		assertEquals(texto, Textos.decodificar(texto.toByteArray(charset("windows-1252"))))
	}

	@Test
	fun tituloCortoSoloComoPrimeraPalabra() {
		assertTrue(LectorEpub.pareceTitulo("I", "I"))
		assertTrue(LectorEpub.pareceTitulo("1. El comienzo", "1"))
		assertTrue(LectorEpub.pareceTitulo("IV", "iv"))
		assertFalse(LectorEpub.pareceTitulo("Imagina un mundo sin libros.", "I"))
		assertFalse(LectorEpub.pareceTitulo("Era el año 1910 y llovía.", "1"))
	}

	@Test
	fun tituloLargoComoAntes() {
		assertTrue(LectorEpub.pareceTitulo("Capítulo 3. La tormenta", "La tormenta"))
		assertTrue(LectorEpub.pareceTitulo("La tormenta", "Capítulo 3: La tormenta"))
		assertFalse(LectorEpub.pareceTitulo("Un párrafo cualquiera", "La tormenta"))
	}

	@Test
	fun docCeldaVaciaNoPartaLaFila() {
		// Dos filas de tres columnas; la segunda con la celda del medio vacía
		val word = "a\u0007b\u0007c\u0007\u0007d\u0007\u0007f\u0007\u0007Fin\r"
		val bloques = LectorDocAntiguo.bloques(word)
		assertEquals(
			listOf(TipoBloque.FILA_TABLA to "a; b; c", TipoBloque.FILA_TABLA to "d; f", TipoBloque.PARRAFO to "Fin"),
			bloques,
		)
	}

	@Test
	fun docCeldaVaciaAlFinalDeLaFila() {
		val word = "a\u0007b\u0007\u0007c\u0007\u0007\u0007"
		assertEquals(
			listOf(TipoBloque.FILA_TABLA to "a; b", TipoBloque.FILA_TABLA to "c"),
			LectorDocAntiguo.bloques(word),
		)
	}
}
