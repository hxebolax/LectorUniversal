package com.xebolax.lector

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xebolax.lector.catalogo.Feed
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Los catálogos de libros que vienen puestos funcionan (con internet): Gutenberg e Internet Archive (OPDS 2). */
@RunWith(AndroidJUnit4::class)
class CatalogosTest {

	@Test
	fun internetArchiveLibresEnEspanol() {
		val url = "https://archive.org/services/opds/catalog?type=browse&section=ebooks&item=open-access&facet_section=languages&facet_item=spanish"
		val p = Feed.leer(Feed.descargarTexto(url), url)
		val libros = p.entradas.filter { it.descargas.isNotEmpty() }
		Log.i("Catalogos", "IA español: ${p.entradas.size} entradas, ${libros.size} libros, búsqueda=${p.busqueda}, siguiente=${p.siguiente != null}")
		assertTrue(libros.isNotEmpty())
		assertTrue(p.busqueda?.contains("{searchTerms}") == true)
		// El archivo real de un libro (EPUB, PDF con texto o texto)
		val archivo = libros.firstNotNullOfOrNull { e -> runCatching { Feed.archivoInternetArchive(Feed.mejorDescarga(e.descargas)!!) }.getOrNull() }
		Log.i("Catalogos", "IA archivo: ${archivo?.url} (${archivo?.tipo})")
		assertTrue(archivo?.url?.startsWith("https://archive.org/download/") == true)
		// Buscar
		val busqueda = Feed.direccionBusqueda(p.busqueda!!, "cervantes", null)
		val r = Feed.leer(Feed.descargarTexto(busqueda), busqueda)
		Log.i("Catalogos", "IA buscar cervantes: ${r.entradas.count { it.descargas.isNotEmpty() }} libros libres de ${r.entradas.size}")
	}

	@Test
	fun gutenberg() {
		val url = "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads&query=l.es"
		val p = Feed.leer(Feed.descargarTexto(url), url)
		Log.i("Catalogos", "Gutenberg español: ${p.entradas.size} entradas")
		assertTrue(p.entradas.isNotEmpty())
	}
}
