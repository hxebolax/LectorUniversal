package com.xebolax.lector

import com.xebolax.lector.formatos.Braille
import org.junit.Assert.assertEquals
import org.junit.Test

/** Braille español de grado 1 (signografía básica de la CBE) en braille ASCII de la ONCE. */
class BrailleTest {

	private fun texto(once: String) = Braille.aTexto(Braille.celdas(once, Braille.Tabla.ONCE))

	@Test
	fun letrasAcentosYMayusculas() {
		assertEquals("Tras cuatro años después de su última", texto("{tras cuatro a7os despu\$s de su )ltima"))
		assertEquals("Comisión Braille Española", texto("{comisi0n {braille {espa7ola"))
		assertEquals("ONCE", texto("{{once"))
		assertEquals("Pingüino", texto("{ping8ino"))
	}

	@Test
	fun numeros() {
		assertEquals("501.439.678", texto("#eja.dci.fgh"))
		assertEquals("28,34", texto("#bh,cd"))
		assertEquals("18:56", texto("#ah:#ef"))
		assertEquals("8b", texto("#h@b"))
		assertEquals("1.º y 2.ª", texto("#,o y #;a"))
		assertEquals("versión 1:", texto("versi0n #a:"))
		assertEquals("28001 Madrid", texto("#bhjja {madrid"))
	}

	@Test
	fun signosQueAbrenYCierran() {
		assertEquals("¿Qué tal?", texto("?{qu\$ tal?"))
		assertEquals("¡Hola!", texto("+{hola+"))
		assertEquals("«cita» y (nota)", texto("<cita< y 2nota|"))
		assertEquals("10 €", texto("#aj %e"))
		assertEquals("cbes@once.es", texto("cbes@once.es"))
		assertEquals("Código matemático", texto("}{c0digo matem(tico}"))
	}

	@Test
	fun brailleAsciiNorteamericano() {
		// Las mismas letras en BRF: «,hola» es mayúscula + hola
		assertEquals("Hola", Braille.aTexto(Braille.celdas(".hola", Braille.Tabla.BRF)))
	}

	@Test
	fun parrafosYCortesDePalabra() {
		val pagina = listOf(
			"                               #e     #i",
			"  {como consecuencia de todas estas no-",
			"vedades y modificaciones.",
			"",
			"        #b. {el sistema braille",
			"  {otro p(rrafo -adem(s del ingl\$s-",
			"sigue.",
			"                                #jcfegeh",
		).map { Braille.celdas(it, Braille.Tabla.ONCE) }
		val trozos = Braille.maquetar(listOf(pagina))
		val textos = trozos.map { Braille.aTexto(it.celdas) }
		assertEquals(listOf("Como consecuencia de todas estas novedades y modificaciones.", "2. El sistema braille", "Otro párrafo -además del inglés- sigue."), textos)
		assertEquals(Braille.Trozo.Tipo.TITULO, trozos[1].tipo)
		assertEquals("9 (tinta 5)", trozos[0].pagina)
	}
}
