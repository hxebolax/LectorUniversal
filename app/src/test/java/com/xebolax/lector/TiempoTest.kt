package com.xebolax.lector

import com.xebolax.lector.ui.textoATiempo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** «Ir a un tiempo»: tiempos, saltos desde donde se está, porcentajes y erratas. */
class TiempoTest {

	private val actual = 10 * 60_000L          // 10:00
	private val total = 2 * 3_600_000L         // 2:00:00

	@Test
	fun tiempos() {
		assertEquals(12 * 60_000L, textoATiempo("12", actual, total))
		assertEquals((12 * 60 + 30) * 1000L, textoATiempo("12:30", actual, total))
		assertEquals((3600 + 2 * 60 + 30) * 1000L, textoATiempo("1:02:30", actual, total))
	}

	@Test
	fun saltos() {
		assertEquals(15 * 60_000L, textoATiempo("+5:00", actual, total))
		assertEquals((8 * 60 + 30) * 1000L, textoATiempo("-1:30", actual, total))
		assertEquals(0L, textoATiempo("-20", actual, total))
	}

	@Test
	fun porcentajes() {
		assertEquals(3_600_000L, textoATiempo("50 %", actual, total))
		assertEquals(3_600_000L, textoATiempo("50%", actual, total))
		assertEquals(1_800_000L, textoATiempo("25,0%", actual, total))
		assertNull(textoATiempo("50%", actual, 0))
		assertNull(textoATiempo("150%", actual, total))
	}

	@Test
	fun erratas() {
		assertNull(textoATiempo("1:75", actual, total))
		assertNull(textoATiempo("1:02:60", actual, total))
		assertNull(textoATiempo("abc", actual, total))
		assertNull(textoATiempo("+", actual, total))
	}
}
