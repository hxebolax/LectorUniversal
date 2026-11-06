package com.xebolax.lector

import com.xebolax.lector.rss.RssLector
import org.junit.Assert.assertEquals
import org.junit.Test

/** El idioma que declara cada tipo de fuente (JSON Feed no: org.json solo existe en Android) (para que la voz lea sus artículos en ese idioma). */
class RssIdiomaTest {

	@Test
	fun rss() {
		val c = RssLector.leer("<rss version=\"2.0\"><channel><title>BBC</title><language>en-gb</language><item><title>Hola</title><link>https://x/1</link></item></channel></rss>", "https://x/")
		assertEquals("en-gb", c.idioma)
		assertEquals(1, c.elementos.size)
	}

	@Test
	fun atom() {
		val c = RssLector.leer("<feed xmlns=\"http://www.w3.org/2005/Atom\" xml:lang=\"fr\"><title>Le Monde</title><entry><id>1</id><title>Bonjour</title></entry></feed>", "https://x/")
		assertEquals("fr", c.idioma)
	}

	@Test
	fun sinIdioma() {
		val c = RssLector.leer("<rss version=\"2.0\"><channel><title>T</title></channel></rss>", "https://x/")
		assertEquals("", c.idioma)
	}
}
