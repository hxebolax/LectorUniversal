package com.xebolax.lector

import com.xebolax.lector.formatos.Contenedor
import com.xebolax.lector.formatos.OrdenNatural
import com.xebolax.lector.lectura.Elemento
import com.xebolax.lector.lectura.Navegador
import com.xebolax.lector.modelo.ConstructorDocumento
import com.xebolax.lector.modelo.Enlace
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.Posicion
import com.xebolax.lector.modelo.Segmentador
import com.xebolax.lector.modelo.TipoBloque
import com.xebolax.lector.modelo.Textos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pruebas del modelo de documento, el segmentador y la navegación (se pasan en el PC: compilar.bat pruebas). */
class ModeloTest {

	@Test
	fun frases() {
		val t = "El Sr. García llegó a las 3.30 h. ¿Vienes? ¡Claro! Lo dijo J. R. R. Tolkien… y se fue."
		val f = Segmentador.frases(t).map { t.substring(it.first, it.last + 1) }
		assertEquals(listOf("El Sr. García llegó a las 3.30 h.", "¿Vienes?", "¡Claro!", "Lo dijo J. R. R. Tolkien… y se fue."), f)
	}

	@Test
	fun frasesLargasSeCortan() {
		val t = (1..60).joinToString(", ") { "palabra$it" } + "."
		val f = Segmentador.frases(t, maximo = 100)
		assertTrue(f.size > 3)
		assertTrue(f.all { it.last - it.first < 100 })
	}

	@Test
	fun enlacesInternosSeResuelven() {
		val c = ConstructorDocumento()
		c.ancla("cap1.xhtml")
		c.encabezado("Capítulo 1", 1)
		c.parrafo("Ver la nota 1.", listOf(Enlace(10, 13, "cap1.xhtml#n1")))
		c.ancla("cap1.xhtml#n1")
		c.bloque(TipoBloque.NOTA, "1. Una nota.")
		c.parrafo("Enlace roto", listOf(Enlace(0, 6, "#no-existe")))
		val d = c.construir()
		assertEquals("#2", d.bloques[1].enlaces.single().destino)
		assertTrue(d.bloques[3].enlaces.isEmpty())
		assertEquals(1, d.indice.size)
	}

	@Test
	fun listasHechasAMano() {
		val c = ConstructorDocumento()
		c.parrafo("Tres cosas:")
		c.parrafo("• Una")
		c.parrafo("• Dos")
		c.parrafo("1. Primero")
		c.parrafo("2. Segundo")
		c.parrafo("- Suelto")
		val d = c.construir()
		assertEquals(TipoBloque.ELEMENTO_LISTA, d.bloques[1].tipo)
		assertEquals("Una", d.bloques[1].texto)
		assertTrue(d.bloques[1].tiene(Marca.INICIO_LISTA))
		assertEquals("1. Primero", d.bloques[3].texto)
		assertEquals(TipoBloque.PARRAFO, d.bloques[5].tipo)
	}

	@Test
	fun parrafosLargosSeDividen() {
		val c = ConstructorDocumento().apply { dividirParrafos = 60 }
		c.parrafo("Primera frase bastante larga para el ejemplo. Segunda frase también larga aquí. Tercera.")
		val d = c.construir()
		assertTrue(d.bloques.size >= 2)
		assertTrue(d.bloques.all { it.tipo == TipoBloque.PARRAFO })
	}

	@Test
	fun navegacionPorElementos() {
		val c = ConstructorDocumento()
		c.encabezado("Uno", 1)
		c.parrafo("a")
		c.encabezado("Uno punto uno", 2)
		c.parrafo("b")
		c.encabezado("Dos", 1)
		c.pagina("7")
		c.parrafo("c")
		val d = c.construir()
		val n = Navegador(d)
		assertEquals(2, n.buscar(Elemento.ENCABEZADO, 0, true))
		assertEquals(4, n.buscar(Elemento.NIVEL_1, 0, true))
		assertEquals(-1, n.buscar(Elemento.NIVEL_2, 2, true))
		assertEquals(2, n.buscar(Elemento.NIVEL_2, 2, true, circular = true))
		assertEquals(5, n.buscar(Elemento.PAGINA, 0, true))
		assertEquals("7", d.paginaDe(6)?.etiqueta)
	}

	@Test
	fun porcentajes() {
		val c = ConstructorDocumento()
		repeat(10) { c.parrafo("x".repeat(100)) }
		val d = c.construir()
		assertEquals(5, d.bloqueDePorcentaje(50.0))
		assertEquals(50.0, d.porcentaje(5), 0.01)
	}

	@Test
	fun textos() {
		assertEquals("palabra", Textos.limpiar("pala­bra"))
		assertEquals("canción", Textos.decodificar("canción".toByteArray(Charsets.UTF_8)))
		assertEquals("canción", Textos.decodificar("canción".toByteArray(charset("windows-1252"))))
		assertEquals(4, Textos.palabras("Hola, ¿qué tal estás?"))
	}

	@Test
	fun rutas() {
		assertEquals("OEBPS/img/a.png", Contenedor.resolver("OEBPS/text/c1.xhtml", "../img/a.png"))
		assertEquals("OEBPS/text/c2.xhtml", Contenedor.resolver("OEBPS/text/c1.xhtml", "c2.xhtml#n3"))
		assertEquals("a b.html", Contenedor.resolver("x.html", "a%20b.html"))
	}

	@Test
	fun ordenNatural() {
		val l = listOf("Capítulo 10.mp3", "capítulo 2.mp3", "Capítulo 1.mp3").sortedWith(OrdenNatural)
		assertEquals(listOf("Capítulo 1.mp3", "capítulo 2.mp3", "Capítulo 10.mp3"), l)
	}

	@Test
	fun posiciones() {
		assertEquals(Posicion.texto(12, 3), Posicion.leer("t:12:3"))
		assertEquals(Posicion.audio(2, 61000), Posicion.leer(Posicion.audio(2, 61000).toString()))
		assertEquals(null, Posicion.leer("basura"))
	}
}
