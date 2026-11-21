package com.xebolax.lector

import com.xebolax.lector.rss.ArticuloCompleto
import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El artículo completo se encuentra también en medios que no usan <p> (cada párrafo en un div). */
class ArticuloCompletoTest {

	private val parrafo = "Este es un párrafo de prueba bastante largo, con comas, frases y datos, para que cuente como texto del artículo y no como un adorno de la página."

	private fun pagina(): String {
		val bloques = (1..6).joinToString("") { i ->
			"<div class=\"article__block\" data-type=\"text\"><div class=\"article__text\">$i. $parrafo <a href=\"/otra.html\">enlace</a> sigue.</div></div>"
		}
		val tarjeta = "<div class=\"article__block\" data-type=\"article\"><div class=\"article__article\"><a href=\"/relacionada.html\">" +
			"<div class=\"article__article-title\">Titular de otra noticia relacionada que no es del artículo</div></a></div></div>"
		val leeTambien = "<div class=\"article__block\"><div class=\"article__text\"><a href=\"/lee.html\">Lee también esta otra noticia de la agencia que no toca</a></div></div>"
		// Microdatos ocultos con el artículo entero copiado (como Sputnik)
		val meta = "<div class=\"article__meta\" itemscope itemtype=\"https://schema.org/Article\">" +
			"<div itemprop=\"mainEntityOfPage\">https://noticiaslatam.lat/a.html</div><div itemprop=\"headline\">Chile alcanza un récord de prueba</div>" +
			"<div itemprop=\"datePublished\">2026-11-21T07:29+0000</div><div itemprop=\"articleBody\">1. $parrafo 2. $parrafo 3. $parrafo</div></div>"
		val suscribete = "<div class=\"article__news-services-subscribe\"><div class=\"article__news-services-subscribe-label\">No te pierdas las noticias más importantes, suscríbete a nuestros canales</div></div>"
		return "<html><head><title>T</title></head><body><div class=\"layout\"><div class=\"menu-x\"><a href=\"/\">Portada</a></div>" +
			"<div class=\"article\">$meta<div class=\"article__header\"><h1 class=\"article__title\">Chile alcanza un récord de prueba</h1>" +
			"<div class=\"article__announce-text\">La entradilla del artículo.</div></div>" +
			"<div class=\"article__body\">$bloques$tarjeta$leeTambien</div>$suscribete</div></div></body></html>"
	}

	@Test
	fun divsSinParrafosDanElArticulo() {
		val cuerpo = ArticuloCompleto.extraer(Jsoup.parse(pagina(), "https://noticiaslatam.lat/a.html"), "Chile alcanza un récord de prueba")
		assertNotNull(cuerpo)
		val texto = cuerpo!!.text()
		for (i in 1..6) assertTrue("falta el párrafo $i", texto.contains("$i. Este es un párrafo"))
		assertFalse("la tarjeta de otra noticia no es del artículo", texto.contains("Titular de otra noticia"))
		assertFalse("«Lee también» no es del artículo", texto.contains("Lee también"))
		assertFalse("los microdatos ocultos no se leen", texto.contains("2026-11-21T07"))
		assertTrue("el texto no sale dos veces", Regex("1\\. Este es").findAll(texto).count() == 1)
		assertFalse("el aviso de suscribirse no es del artículo", texto.contains("No te pierdas"))
		assertTrue(cuerpo.select("p").size >= 6)
	}

	@Test
	fun conParrafosNormalesSigueIgual() {
		val html = "<html><body><article>" + (1..5).joinToString("") { "<p>$it. $parrafo</p>" } + "</article></body></html>"
		val cuerpo = ArticuloCompleto.extraer(Jsoup.parse(html, "https://ejemplo.org/"), "Otro")
		assertNotNull(cuerpo)
		assertTrue(cuerpo!!.text().contains("5. Este es"))
	}

	@Test
	fun articuloConMicrodatosVisiblesSeQueda() {
		// schema.org habitual: los hijos del artículo llevan itemprop y el cuerpo tiene sus <p>
		val html = "<html><body><article itemscope itemtype=\"https://schema.org/NewsArticle\">" +
			"<h1 itemprop=\"headline\">Titular visible</h1><time itemprop=\"datePublished\">2026-11-21</time>" +
			"<span itemprop=\"author\">Autora</span><div itemprop=\"articleBody\">" +
			(1..5).joinToString("") { "<p>$it. $parrafo</p>" } + "</div></article></body></html>"
		val cuerpo = ArticuloCompleto.extraer(Jsoup.parse(html, "https://ejemplo.org/"), "Titular visible")
		assertNotNull("se quitó el artículo visible", cuerpo)
		assertTrue(cuerpo!!.text().contains("5. Este es"))
	}

	@Test
	fun paginaRealSiEstaDescargada() {
		// Comprobación a mano con una página guardada (no va en el repositorio): -Dlu.pagina=ruta
		val ruta = System.getProperty("lu.pagina") ?: System.getenv("LU_PAGINA") ?: return
		val doc = Jsoup.parse(java.io.File(ruta), "UTF-8", "https://noticiaslatam.lat/")
		doc.select("script[type=application/ld+json]").remove()
		val cuerpo = ArticuloCompleto.extraer(doc, doc.select("h1").first()?.text().orEmpty())
		assertNotNull(cuerpo)
		println("TEXTO EXTRAÍDO (${cuerpo!!.text().length}): " + cuerpo.text())
		println("HTML: " + cuerpo.html().take(3000))
	}
}
