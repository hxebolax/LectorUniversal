package com.xebolax.lector

import com.xebolax.lector.catalogo.NombreDescarga
import org.junit.Assert.assertEquals
import org.junit.Test

/** Nombres de los archivos descargados de las bibliotecas web. */
class NombreDescargaTest {

	@Test
	fun cabeceraAspNetConLasDosFormas() {
		// Lo que manda ASP.NET: filename sin tildes y filename* con ellas (Android solo miraba el final)
		assertEquals("Cien años de soledad.zip",
			NombreDescarga.deCabecera("attachment; filename=\"Cien anos de soledad.zip\"; filename*=UTF-8''Cien%20a%C3%B1os%20de%20soledad.zip"))
		assertEquals("Rayuela.zip", NombreDescarga.deCabecera("attachment; filename=Rayuela.zip; size=12345"))
		assertEquals("García Márquez - Relato.txt", NombreDescarga.deCabecera("attachment; filename=\"GarcÃ­a MÃ¡rquez - Relato.txt\""))
		assertEquals(null, NombreDescarga.deCabecera("inline"))
	}

	@Test
	fun genericosYDireccion() {
		assertEquals(true, NombreDescarga.generico("download0"))
		assertEquals(true, NombreDescarga.generico("downloadfile.bin"))
		assertEquals(true, NombreDescarga.generico("12345.zip"))
		assertEquals(false, NombreDescarga.generico("El Quijote.zip"))
		assertEquals("La Regenta.epub", NombreDescarga.deUrl("https://ejemplo.org/libros/La%20Regenta.epub?x=1"))
		assertEquals(null, NombreDescarga.deUrl("https://ejemplo.org/Works/Download/12345"))
	}

	@Test
	fun sinNombreUsaElTituloYLaClaseDeDescarga() {
		// Ni cabecera ni nombre en la dirección: el título del libro de la página y el botón pulsado
		assertEquals("Marianela - DAISY.zip",
			NombreDescarga.elegir(null, "https://www.tiflolibros.tiflonexos.org/Works/Download/77", "application/zip", "Marianela - Tiflolibros", "DAISY", "Tiflolibros"))
		assertEquals("Marianela.zip",
			NombreDescarga.elegir("attachment; filename=download0", "https://x.org/d?id=0", "application/zip", "Marianela", "Descargar", "Tiflolibros"))
		// Con cabecera buena, se respeta tal cual
		assertEquals("Marianela (texto).zip",
			NombreDescarga.elegir("attachment; filename=\"Marianela (texto).zip\"", "https://x.org/d?id=0", "application/octet-stream", "Otra cosa", "DAISY", "Tiflolibros"))
	}

	@Test
	fun extensionPorContenido() {
		assertEquals("zip", NombreDescarga.extensionDeContenido(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0)))
		assertEquals("pdf", NombreDescarga.extensionDeContenido("%PDF-1.7".toByteArray()))
		assertEquals("", NombreDescarga.extensionDeContenido("hola".toByteArray()))
	}
}
