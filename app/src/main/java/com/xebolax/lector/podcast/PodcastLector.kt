package com.xebolax.lector.podcast

import com.xebolax.lector.rss.RssLector
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Lee el RSS de un podcast con lo propio de los podcasts: iTunes (imagen,
 * autor, duración, temporada y número de episodio, mudanza del podcast) y
 * Podcasting 2.0 (capítulos y transcripciones).
 */
object PodcastLector {

	class Elemento(
		val guid: String,
		val titulo: String,
		val descripcion: String,
		val fecha: Long,
		val audio: String,
		val tipo: String,
		val tamano: Long,
		val duracion: Long,
		val enlace: String,
		val temporada: Int,
		val numero: Int,
		val capitulos: String,
		val transcripcion: String,
		val tipoTranscripcion: String,
	)

	class Canal(
		val titulo: String,
		val autor: String,
		val descripcion: String,
		val imagen: String,
		val sitio: String,
		val nuevaUrl: String,
		val elementos: List<Elemento>,
		val idioma: String = "",
		val categorias: List<String> = emptyList(),
		/** «serial»: se escucha en orden, del primero al último. */
		val serie: Boolean = false,
		/** Enlaces para apoyar al podcast (Podcasting 2.0 funding): (texto, dirección). */
		val apoyo: List<Pair<String, String>> = emptyList(),
		val copyright: String = "",
		val propietario: String = "",
		/** Tráiler del podcast (Podcasting 2.0): (título, audio). */
		val trailer: Pair<String, String>? = null,
	)

	class Respuesta(val canal: Canal?, val etag: String?, val modificado: String?)

	/** El servidor pide usuario y contraseña (401). */
	class SinPermiso : Exception("HTTP 401")

	fun descargar(url: String, etag: String = "", modificado: String = "", usuario: String = "", clave: String = ""): Respuesta {
		val c = RssLector.abrir(url, etag, modificado, usuario, clave)
		try {
			if (c.responseCode == 304) return Respuesta(null, etag, modificado)
			if (c.responseCode == 401) throw SinPermiso()
			if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
			val texto = c.inputStream.use { com.xebolax.lector.modelo.Textos.decodificar(it.readBytes(), "utf-8") }
			return Respuesta(leer(texto, url), c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"))
		} finally {
			c.disconnect()
		}
	}

	private fun nombre(e: Element) = e.tagName().substringAfter(':').lowercase()
	private fun prefijo(e: Element) = e.tagName().substringBefore(':', "").lowercase()
	private fun hijo(e: Element, n: String, pre: String? = null) =
		e.children().firstOrNull { nombre(it) == n && (pre == null || prefijo(it) == pre) }
	private fun hijos(e: Element, n: String) = e.children().filter { nombre(it) == n }
	private fun absoluta(base: String, href: String): String =
		if (href.isBlank()) "" else runCatching { java.net.URL(java.net.URL(base), href.trim()).toString() }.getOrDefault(href.trim())
	private fun texto(e: Element?) = e?.let { if (it.children().isNotEmpty()) it.html() else it.wholeText() }?.trim().orEmpty()

	/** "01:02:03", "62:03" o "3723" (segundos) a milisegundos. */
	fun duracion(s: String): Long {
		val t = s.trim()
		if (t.isEmpty()) return 0
		val partes = t.split(':').map { it.trim().toDoubleOrNull() ?: return 0 }
		val segundos = partes.fold(0.0) { acc, p -> acc * 60 + p }
		return (segundos * 1000).toLong()
	}

	fun leer(texto: String, base: String): Canal {
		val doc = Jsoup.parse(texto.trimStart('﻿'), base, Parser.xmlParser())
		val raiz = doc.children().firstOrNull { nombre(it) in setOf("rss", "feed", "rdf") } ?: error("No es el RSS de un podcast")
		val canal = hijo(raiz, "channel") ?: raiz
		val items = hijos(canal, "item").ifEmpty { hijos(canal, "entry") }
		val elementos = items.mapNotNull { e ->
			val adjunto = hijo(e, "enclosure")
				?: e.children().firstOrNull { nombre(it) == "content" && prefijo(it) == "media" && it.attr("type").let { t -> t.startsWith("audio") || t.startsWith("video") } }
				?: hijos(e, "link").firstOrNull { it.attr("rel") == "enclosure" }
			val audio = adjunto?.let { absoluta(base, it.attr("url").ifBlank { it.attr("href") }) }.orEmpty()
			if (audio.isBlank()) return@mapNotNull null
			val enlace = hijo(e, "link")?.let { it.text().ifBlank { it.attr("href") } }?.let { absoluta(base, it) }.orEmpty()
			val descripcion = texto(hijo(e, "encoded", "content")).ifBlank { texto(hijo(e, "description")) }
				.ifBlank { texto(hijo(e, "summary", "itunes")) }.ifBlank { texto(hijo(e, "summary")) }
			// Podcasting 2.0: transcripción (mejor VTT, SRT o JSON que HTML) y capítulos
			val transcripciones = e.children().filter { nombre(it) == "transcript" && prefijo(it) == "podcast" }
			val transcripcion = transcripciones.maxByOrNull {
				val t = it.attr("type")
				when {
					t.contains("vtt") -> 4
					t.contains("srt") -> 3
					t.contains("json") -> 2
					else -> 1
				}
			}
			Elemento(
				guid = hijo(e, "guid")?.text()?.trim().orEmpty().ifBlank { hijo(e, "id")?.text()?.trim().orEmpty() }.ifBlank { audio },
				titulo = Jsoup.parse(hijo(e, "title")?.text().orEmpty()).text().ifBlank { "—" },
				descripcion = descripcion,
				fecha = RssLector.fecha((hijo(e, "pubdate") ?: hijo(e, "published") ?: hijo(e, "updated"))?.text().orEmpty()),
				audio = audio,
				tipo = adjunto?.attr("type").orEmpty(),
				tamano = adjunto?.attr("length")?.toLongOrNull() ?: 0,
				duracion = duracion(hijo(e, "duration", "itunes")?.text().orEmpty()),
				enlace = enlace,
				temporada = hijo(e, "season", "itunes")?.text()?.trim()?.toIntOrNull() ?: 0,
				numero = hijo(e, "episode", "itunes")?.text()?.trim()?.toIntOrNull() ?: 0,
				capitulos = e.children().firstOrNull { nombre(it) == "chapters" && prefijo(it) == "podcast" }?.attr("url")?.let { absoluta(base, it) }.orEmpty(),
				transcripcion = transcripcion?.attr("url")?.let { absoluta(base, it) }.orEmpty(),
				tipoTranscripcion = transcripcion?.attr("type").orEmpty(),
			)
		}
		val imagen = hijo(canal, "image", "itunes")?.attr("href")?.takeIf { it.isNotBlank() }
			?: hijo(canal, "image")?.let { hijo(it, "url")?.text() }.orEmpty()
		return Canal(
			titulo = Jsoup.parse(hijo(canal, "title")?.text().orEmpty()).text(),
			autor = hijo(canal, "author", "itunes")?.text()?.trim().orEmpty(),
			descripcion = Jsoup.parse(texto(hijo(canal, "summary", "itunes")).ifBlank { texto(hijo(canal, "description")) }).text(),
			imagen = absoluta(base, imagen),
			sitio = absoluta(base, hijo(canal, "link")?.text().orEmpty()),
			nuevaUrl = hijo(canal, "new-feed-url", "itunes")?.text()?.trim().orEmpty(),
			elementos = elementos,
			idioma = hijo(canal, "language")?.text()?.trim().orEmpty(),
			categorias = canal.children().filter { nombre(it) == "category" && prefijo(it) == "itunes" }.flatMap { c ->
				listOf(c.attr("text")) + c.children().filter { nombre(it) == "category" }.map { it.attr("text") }
			}.filter { it.isNotBlank() }.distinct(),
			serie = hijo(canal, "type", "itunes")?.text()?.trim().equals("serial", true),
			apoyo = canal.children().filter { nombre(it) == "funding" && prefijo(it) == "podcast" }
				.map { it.text().trim() to absoluta(base, it.attr("url")) }.filter { it.second.isNotBlank() },
			copyright = hijo(canal, "copyright")?.text()?.trim().orEmpty(),
			propietario = hijo(canal, "owner", "itunes")?.let { hijo(it, "name")?.text() }?.trim().orEmpty(),
			trailer = canal.children().firstOrNull { nombre(it) == "trailer" && prefijo(it) == "podcast" }
				?.let { it.text().trim() to absoluta(base, it.attr("url")) }?.takeIf { it.second.isNotBlank() },
		)
	}
}
