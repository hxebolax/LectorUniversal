package com.xebolax.lector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Estado
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Biblioteca: estados nuevos, series, autores, colecciones y subrayados. */
@RunWith(AndroidJUnit4::class)
class BibliotecaOrdenTest {

	private val app = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun seriesColeccionesYSubrayados() {
		val db = BaseDatos.obtener(app)
		listOf("prueba-orden-1", "prueba-orden-2").forEach { u -> db.libroPorUri("file:///$u.epub")?.let { db.quitarLibro(it.id) } }
		val a = db.anadirLibro("file:///prueba-orden-1.epub", "a.epub", "Trafalgar", "Benito Pérez Galdós", "EPUB", false, 1, null)
		val b = db.anadirLibro("file:///prueba-orden-2.epub", "b.epub", "La corte de Carlos IV", "Benito Pérez Galdós", "EPUB", false, 1, null)
		// Series: se entienden «Nombre, número»
		db.ponerSerieDeTexto(a, "Episodios Nacionales, 1")
		db.ponerSerieDeTexto(b, "Episodios Nacionales 2")
		assertEquals(listOf("Trafalgar", "La corte de Carlos IV"), db.deSerie("Episodios Nacionales").map { it.titulo })
		assertTrue(db.series().any { it.first == "Episodios Nacionales" && it.second == 2 })
		assertTrue(db.autores().any { it.first == "Benito Pérez Galdós" && it.second >= 2 })
		// Estados nuevos
		db.cambiarEstado(a, Estado.QUIERO_LEER)
		db.cambiarEstado(b, Estado.ABANDONADO)
		assertEquals(Estado.QUIERO_LEER, db.libro(a)?.estado)
		assertEquals(Estado.ABANDONADO, db.libro(b)?.estado)
		// Colecciones: un libro en varias; borrar la colección no borra el libro
		val clasicos = db.crearColeccion("Prueba clásicos")
		val verano = db.crearColeccion("Prueba verano")
		db.ponerEnColeccion(a, clasicos, true)
		db.ponerEnColeccion(a, verano, true)
		assertEquals(setOf(clasicos, verano), db.coleccionesDe(a))
		assertEquals(listOf("Trafalgar"), db.deColeccion(clasicos).map { it.titulo })
		db.borrarColeccion(clasicos)
		db.borrarColeccion(verano)
		assertEquals(emptySet<Long>(), db.coleccionesDe(a))
		// Subrayados
		val s = db.anadirSubrayado(a, 3, 0, 10, "Era el día", "Importante")
		assertEquals("Importante", db.subrayados(a).single().categoria)
		db.borrarSubrayado(s)
		assertTrue(db.subrayados(a).isEmpty())
		db.quitarLibro(a)
		db.quitarLibro(b)
	}
}
