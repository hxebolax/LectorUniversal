package com.xebolax.lector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xebolax.lector.podcast.Podcasts
import com.xebolax.lector.podcast.PodcastsBaseDatos
import com.xebolax.lector.rss.Rss
import com.xebolax.lector.rss.RssBaseDatos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Carpetas de Noticias y Podcasts: crear, ordenar, renombrar, borrar (con o sin su contenido) y la copia en OPML. */
@RunWith(AndroidJUnit4::class)
class CarpetasTest {

	private val app = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun carpetasDeNoticias() {
		val db = RssBaseDatos.obtener(app)
		db.fuentes().filter { it.url.startsWith("https://carpetas.invalid/") }.forEach { db.borrarFuente(it.id) }
		db.categorias().filter { it.startsWith("Prueba ") }.forEach { db.borrarCarpeta(it, conFuentes = false) }
		// Una carpeta vacía existe y sale; otra con fuentes
		db.crearCarpeta("Prueba vacía")
		val a = db.anadirFuente("https://carpetas.invalid/a", "Fuente A", categoria = "Prueba tecnología")
		val b = db.anadirFuente("https://carpetas.invalid/b", "Fuente B", categoria = "prueba TECNOLOGÍA")
		assertTrue("Prueba vacía" in db.categorias())
		assertEquals("Mismo nombre sin distinguir mayúsculas: una sola carpeta", 1, db.categorias().count { it.equals("Prueba tecnología", ignoreCase = true) })
		// Orden a mano
		val mias = db.categorias().filter { it.startsWith("Prueba ") }
		db.ordenarCarpetas(db.categorias().filter { it !in mias } + mias.reversed())
		assertEquals(mias.reversed(), db.categorias().filter { it.startsWith("Prueba ") })
		// Mover una fuente y renombrar
		db.ponerCategoria(b, "Prueba nueva")
		assertTrue("Prueba nueva" in db.categorias())
		db.renombrarCategoria("Prueba tecnología", "Prueba tecno")
		assertEquals("Prueba tecno", db.fuente(a)?.categoria)
		// OPML: guarda también las carpetas vacías
		val opml = Rss.exportarOpml(app)
		assertTrue(opml.contains("title=\"Prueba vacía\""))
		// Borrar solo la carpeta: la fuente queda sin carpeta
		db.borrarCarpeta("Prueba tecno", conFuentes = false)
		assertEquals("", db.fuente(a)?.categoria)
		// Borrar la carpeta con sus fuentes
		db.borrarCarpeta("Prueba nueva", conFuentes = true)
		assertEquals(null, db.fuente(b))
		db.borrarCarpeta("Prueba vacía", conFuentes = false)
		db.borrarFuente(a)
	}

	@Test
	fun carpetasDePodcasts() {
		val db = PodcastsBaseDatos.obtener(app)
		db.podcasts().filter { it.url.startsWith("https://carpetas.invalid/") }.forEach { db.borrarPodcast(it.id) }
		db.carpetas().filter { it.startsWith("Prueba ") }.forEach { db.borrarCarpeta(it, conPodcasts = false) }
		val p = db.anadirPodcast("https://carpetas.invalid/p", "Podcast P", carpeta = "Prueba ciencia")
		db.crearCarpeta("Prueba vacía")
		assertEquals("Prueba ciencia", db.podcast(p)?.carpeta)
		val opml = Podcasts.exportarOpml(app)
		assertTrue(opml.contains("title=\"Prueba ciencia\"") && opml.contains("title=\"Prueba vacía\""))
		db.renombrarCarpeta("Prueba ciencia", "Prueba vacía")
		assertEquals("Al renombrar con el nombre de otra, se juntan", "Prueba vacía", db.podcast(p)?.carpeta)
		assertEquals(1, db.carpetas().count { it.startsWith("Prueba ") })
		val borrados = db.borrarCarpeta("Prueba vacía", conPodcasts = true)
		assertEquals(listOf(p), borrados.map { it.id })
		borrados.forEach { db.borrarPodcast(it.id) }
	}
}
