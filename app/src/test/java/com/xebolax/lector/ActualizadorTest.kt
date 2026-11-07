package com.xebolax.lector

import com.xebolax.lector.actualizaciones.Actualizador
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fuentes de actualización: direcciones de GitHub y comparación de versiones. */
class ActualizadorTest {

	@Test
	fun reconoceRepositoriosDeGitHub() {
		assertEquals("hxebolax/LectorUniversal", Actualizador.repoDeGitHub("https://github.com/hxebolax/LectorUniversal"))
		assertEquals("hxebolax/LectorUniversal", Actualizador.repoDeGitHub("https://github.com/hxebolax/LectorUniversal/"))
		assertEquals("hxebolax/LectorUniversal", Actualizador.repoDeGitHub("https://www.github.com/hxebolax/LectorUniversal.git"))
		assertEquals("otro/mi-lector", Actualizador.repoDeGitHub(" https://github.com/otro/mi-lector/releases/latest "))
		assertNull(Actualizador.repoDeGitHub("https://xebolax.synology.me/LectorUniversal"))
		assertNull(Actualizador.repoDeGitHub("https://github.com/solo-usuario"))
		assertNull(Actualizador.repoDeGitHub("https://github.com.ejemplo.net/a/b"))
	}

	@Test
	fun comparaVersiones() {
		assertTrue(Actualizador.compararVersiones("1.0.10", "1.0.9") > 0)
		assertTrue(Actualizador.compararVersiones("v1.0.9", "1.0.9") == 0)
		assertTrue(Actualizador.compararVersiones("1.0.8", "1.0.9") < 0)
		assertTrue(Actualizador.compararVersiones("1.1", "1.0.9") > 0)
	}
}
