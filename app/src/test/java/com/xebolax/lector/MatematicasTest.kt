package com.xebolax.lector

import com.xebolax.lector.formatos.Matematicas
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fórmulas MathML dichas con palabras. */
class MatematicasTest {

	private fun hablar(mathml: String, idioma: String = "es") =
		Matematicas.hablar(Jsoup.parse(mathml, "", Parser.xmlParser()).selectFirst("math")!!, idioma)

	@Test
	fun potenciaYSuma() = assertEquals("x al cuadrado más 1", hablar("<math><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>1</mn></math>"))

	@Test
	fun fraccionSencillaYCompuesta() {
		assertEquals("a entre b", hablar("<math><mfrac><mi>a</mi><mi>b</mi></mfrac></math>"))
		assertEquals("fracción, x más 1 entre 2, fin de la fracción,", hablar("<math><mfrac><mrow><mi>x</mi><mo>+</mo><mn>1</mn></mrow><mn>2</mn></mfrac></math>"))
	}

	@Test
	fun raizYExponenteCompuesto() {
		assertEquals("raíz cuadrada de x más 1, fin de la raíz,", hablar("<math><msqrt><mi>x</mi><mo>+</mo><mn>1</mn></msqrt></math>"))
		assertEquals("e elevado a i pi, fin del exponente,", hablar("<math><msup><mi>e</mi><mrow><mi>i</mi><mi>π</mi></mrow></msup></math>"))
	}

	@Test
	fun sumatorioEIngles() {
		assertEquals("suma desde i igual a 1 hasta n i", hablar("<math><munderover><mo>∑</mo><mrow><mi>i</mi><mo>=</mo><mn>1</mn></mrow><mi>n</mi></munderover><mi>i</mi></math>"))
		assertEquals("x squared plus 1", hablar("<math><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>1</mn></math>", "en"))
	}
}
