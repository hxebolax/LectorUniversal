package com.xebolax.lector

import com.xebolax.lector.voz.PerfilVoz
import org.junit.Assert.assertEquals
import org.junit.Test

/** Herencia de la voz: libro sobre su sección, sección sobre la voz general. */
class PerfilVozTest {

	private val seccion = PerfilVoz(motor = "elevenlabs", voz = "abcdefghijklmnopqrst", velocidad = 120)

	@Test
	fun loQueNoCambiaSeHereda() {
		val libro = PerfilVoz(tono = 90)
		assertEquals(PerfilVoz(motor = "elevenlabs", voz = "abcdefghijklmnopqrst", velocidad = 120, tono = 90), libro.sobre(seccion))
	}

	@Test
	fun loPropioManda() {
		val libro = PerfilVoz(velocidad = 150)
		assertEquals(150, libro.sobre(seccion).velocidad)
	}

	@Test
	fun otroMotorSinVozNoUsaLaVozDeOtroMotor() {
		val libro = PerfilVoz(motor = "com.google.android.tts")
		val r = libro.sobre(seccion)
		assertEquals("com.google.android.tts", r.motor)
		assertEquals(null, r.voz)
		assertEquals(120, r.velocidad)
	}

	@Test
	fun sinNadaEsComoLaSeccion() = assertEquals(seccion, PerfilVoz().sobre(seccion))
}
