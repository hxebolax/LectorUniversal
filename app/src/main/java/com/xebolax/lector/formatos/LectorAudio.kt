package com.xebolax.lector.formatos

import android.net.Uri
import com.xebolax.lector.LectorApp
import com.xebolax.lector.R
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.LibroAudio
import com.xebolax.lector.modelo.MarcaAudio
import com.xebolax.lector.modelo.Pista
import com.xebolax.lector.modelo.Textos
import java.io.File

/**
 * Un solo archivo de audio (m4b, m4a, mp3, ogg, opus, flac, wav...): una
 * pista con los capítulos internos si los tiene (si no, se navega por tiempo).
 */
object LectorAudio : LectorFormato {

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		// ZIP con pistas de audio dentro: lo extrae y lo abre como carpeta
		val cabecera = runCatching { fuente.cabecera(4) }.getOrDefault(ByteArray(0))
		if (fuente.extension == "zip" || (cabecera.size >= 2 && cabecera[0] == 'P'.code.toByte() && cabecera[1] == 'K'.code.toByte())) {
			return LectorZip.leer(fuente, ctx)
		}
		ctx.progreso(10)
		val info = CapitulosAudio.metadatos(ctx.app, fuente.uri)
		ctx.comprobar()
		ctx.progreso(40)
		val capitulos = CapitulosAudio.leer(ctx.app, fuente.uri)
		ctx.progreso(90)
		val nombre = fuente.nombre.substringBeforeLast('.').ifBlank { fuente.nombre }
		val titulo = info.album.ifBlank { info.titulo }.ifBlank { nombre }
		val autor = info.autorLibro
		val narrador = info.narrador
		val pista = Pista(fuente.uri.toString(), info.titulo.ifBlank { titulo }, info.duracionMs)
		// Las etiquetas, en el idioma de la app (se muestran en Información)
		fun t(recurso: Int) = LectorApp.texto(recurso)
		val metadatos = LinkedHashMap<String, String>()
		metadatos[t(R.string.cj_meta_autor)] = autor
		metadatos[t(R.string.cj_meta_narrador)] = narrador
		if (info.album.isNotBlank() && info.album != titulo) metadatos[t(R.string.cj_meta_album)] = info.album
		if (info.titulo.isNotBlank() && info.titulo != titulo) metadatos[t(R.string.cj_meta_titulo_pista)] = info.titulo
		metadatos[t(R.string.cj_meta_genero)] = info.genero
		metadatos[t(R.string.cj_meta_fecha)] = info.fecha
		if (info.duracionMs > 0) metadatos[t(R.string.cj_meta_duracion)] = CapitulosAudio.duracionLegible(info.duracionMs)
		if (capitulos.isNotEmpty()) metadatos[t(R.string.cj_meta_capitulos)] = capitulos.size.toString()
		metadatos[t(R.string.cj_meta_archivo)] = fuente.nombre
		return Contenido.Audio(
			LibroAudio(
				titulo = titulo,
				autor = autor,
				narrador = narrador,
				pistas = listOf(pista),
				capitulos = capitulos.map { (t, ms) -> MarcaAudio(t, 1, 0, ms) },
				metadatos = metadatos.filterValues { it.isNotBlank() },
			),
		)
	}
}

/**
 * Listas de reproducción: M3U/M3U8 (#EXTINF), PLS (FileN/TitleN) y CUE
 * (FILE, TRACK, TITLE, INDEX 01 mm:ss:ff → capítulos dentro del archivo).
 * Las rutas relativas solo se pueden resolver si la lista es un archivo
 * local (file://); desde el selector del sistema hace falta la carpeta.
 */
object LectorListaAudio : LectorFormato {

	/** Lista ya analizada. capitulos: los de la hoja CUE (vacío en M3U/PLS). */
	class ListaAudio(val titulo: String, val autor: String, val pistas: List<Pista>, val capitulos: List<MarcaAudio>)

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val texto = Textos.decodificar(fuente.bytes(4L * 1024 * 1024), if (fuente.extension == "m3u8") "UTF-8" else "windows-1252")
		val carpeta = if (fuente.uri.scheme == "file") File(fuente.uri.path!!).parentFile else null
		var relativas = 0
		val lista = analizar(texto) { entrada ->
			val e = entrada.trim()
			when {
				e.startsWith("file://") -> Uri.parse(e).path?.let { File(it) }?.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }
				e.contains("://") -> e
				carpeta != null -> enCarpeta(carpeta, e)
				File(e).isAbsolute && File(e).isFile -> Uri.fromFile(File(e)).toString()
				else -> {
					relativas++
					null
				}
			}
		}
		if (lista.pistas.isEmpty()) {
			if (relativas > 0) throw ExcepcionFormato(R.string.fmt_lista_carpeta)
			throw ExcepcionFormato(R.string.fmt_lista_vacia)
		}
		ctx.progreso(20)
		val pistas = conDuraciones(lista.pistas, ctx)
		val primera = pistas.firstOrNull { !it.uri.startsWith("http") }
		val info = primera?.let { CapitulosAudio.metadatos(ctx.app, Uri.parse(it.uri)) } ?: InfoAudio()
		val titulo = lista.titulo.ifBlank { fuente.nombre.substringBeforeLast('.') }
		val autor = lista.autor.ifBlank { info.autorLibro }
		val capitulos = lista.capitulos.ifEmpty { pistas.mapIndexed { i, p -> MarcaAudio(p.titulo, 1, i, 0) } }
		val total = if (pistas.all { it.duracionMs > 0 }) pistas.sumOf { it.duracionMs } else 0L
		val metadatos = linkedMapOf(
			"Autor" to autor,
			"Narrador" to info.narrador,
			"Pistas" to pistas.size.toString(),
			"Duración total" to (if (total > 0) CapitulosAudio.duracionLegible(total) else ""),
		)
		return Contenido.Audio(
			LibroAudio(
				titulo = titulo,
				autor = autor,
				narrador = info.narrador,
				pistas = pistas,
				capitulos = capitulos,
				metadatos = metadatos.filterValues { it.isNotBlank() },
			),
		)
	}

	/** Pistas de una lista (M3U, PLS o CUE). resolver: entrada de la lista → dirección reproducible (null si no existe). */
	fun leerLista(texto: String, resolver: (String) -> String?): List<Pista> = analizar(texto, resolver).pistas

	/** Analiza una lista reconociendo el tipo por el contenido. */
	fun analizar(texto: String, resolver: (String) -> String?): ListaAudio {
		val limpio = texto.removePrefix("﻿")
		return when {
			limpio.contains("[playlist]", ignoreCase = true) -> pls(limpio, resolver)
			CUE_FILE.containsMatchIn(limpio) && CUE_TRACK.containsMatchIn(limpio) -> cue(limpio, resolver)
			else -> m3u(limpio, resolver)
		}
	}

	private val CUE_FILE = Regex("(?im)^\\s*FILE\\s+")
	private val CUE_TRACK = Regex("(?im)^\\s*TRACK\\s+\\d+")
	private val NUMERO_INICIAL = Regex("^\\d{1,3}(?:\\s*[-._)\\]]+\\s*|\\s+)")

	/** Título a partir de un nombre de archivo: sin extensión ni número de pista inicial ("01 - "). */
	fun tituloDeArchivo(ruta: String): String {
		val nombre = Uri.decode(ruta).replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')
		val sinNumero = NUMERO_INICIAL.replace(nombre, "").trim()
		return sinNumero.ifBlank { nombre }.replace('_', ' ').trim()
	}

	private fun m3u(texto: String, resolver: (String) -> String?): ListaAudio {
		val pistas = ArrayList<Pista>()
		var titulo = ""
		var autor = ""
		var tituloPista = ""
		var duracion = 0L
		for (bruta in texto.lineSequence()) {
			val linea = bruta.trim()
			if (linea.isEmpty()) continue
			if (linea.startsWith("#")) {
				when {
					linea.startsWith("#EXTINF:", ignoreCase = true) -> {
						val resto = linea.substring(8)
						val coma = resto.indexOf(',')
						val segundos = (if (coma >= 0) resto.substring(0, coma) else resto).trim().substringBefore(' ').toDoubleOrNull() ?: -1.0
						duracion = if (segundos > 0) (segundos * 1000).toLong() else 0L
						tituloPista = if (coma >= 0) resto.substring(coma + 1).trim() else ""
					}
					linea.startsWith("#PLAYLIST:", ignoreCase = true) -> titulo = linea.substring(10).trim()
					linea.startsWith("#EXTALB:", ignoreCase = true) -> if (titulo.isEmpty()) titulo = linea.substring(8).trim()
					linea.startsWith("#EXTART:", ignoreCase = true) -> autor = linea.substring(8).trim()
				}
				continue
			}
			val uri = resolver(linea)
			if (uri != null) pistas += Pista(uri, tituloPista.ifBlank { tituloDeArchivo(linea) }, duracion)
			tituloPista = ""
			duracion = 0L
		}
		return ListaAudio(titulo, autor, pistas, emptyList())
	}

	private fun pls(texto: String, resolver: (String) -> String?): ListaAudio {
		val archivos = HashMap<Int, String>()
		val titulos = HashMap<Int, String>()
		val duraciones = HashMap<Int, Long>()
		val clave = Regex("^(File|Title|Length)(\\d+)$", RegexOption.IGNORE_CASE)
		for (linea in texto.lineSequence()) {
			val igual = linea.indexOf('=')
			if (igual <= 0) continue
			val m = clave.matchEntire(linea.substring(0, igual).trim()) ?: continue
			val n = m.groupValues[2].toIntOrNull() ?: continue
			val valor = linea.substring(igual + 1).trim()
			when (m.groupValues[1].lowercase()) {
				"file" -> archivos[n] = valor
				"title" -> titulos[n] = valor
				"length" -> valor.toDoubleOrNull()?.takeIf { it > 0 }?.let { duraciones[n] = (it * 1000).toLong() }
			}
		}
		val pistas = archivos.keys.sorted().mapNotNull { n ->
			val entrada = archivos[n]!!
			resolver(entrada)?.let { Pista(it, titulos[n].orEmpty().ifBlank { tituloDeArchivo(entrada) }, duraciones[n] ?: 0L) }
		}
		return ListaAudio("", "", pistas, emptyList())
	}

	/** Palabras de una línea de CUE respetando las comillas. */
	private fun palabras(linea: String): List<String> {
		val r = ArrayList<String>()
		val sb = StringBuilder()
		var comillas = false
		var hay = false
		for (ch in linea) {
			when {
				ch == '"' -> {
					comillas = !comillas
					hay = true
				}
				ch.isWhitespace() && !comillas -> {
					if (hay) r += sb.toString()
					sb.setLength(0)
					hay = false
				}
				else -> {
					sb.append(ch)
					hay = true
				}
			}
		}
		if (hay) r += sb.toString()
		return r
	}

	/** "mm:ss:ff" (75 fotogramas por segundo) → ms. */
	private fun tiempoCue(valor: String): Long? {
		val p = valor.split(':').map { it.trim().toLongOrNull() ?: return null }
		return when (p.size) {
			3 -> (p[0] * 60 + p[1]) * 1000 + p[2] * 1000 / 75
			2 -> (p[0] * 60 + p[1]) * 1000
			else -> null
		}
	}

	private fun cue(texto: String, resolver: (String) -> String?): ListaAudio {
		class Pendiente(val numero: Int, val pista: Int) {
			var titulo = ""
			var inicio = -1L
			var inicio00 = -1L
		}

		var titulo = ""
		var autor = ""
		val archivos = ArrayList<Pair<String, String>>() // (entrada, uri)
		var pistaActual = -1
		val tracks = ArrayList<Pendiente>()
		var actual: Pendiente? = null
		for (linea in texto.lineSequence()) {
			val p = palabras(linea.trim())
			if (p.isEmpty()) continue
			when (p[0].uppercase()) {
				"TITLE" -> {
					val v = p.getOrNull(1).orEmpty()
					if (actual != null) actual.titulo = v else titulo = v
				}
				"PERFORMER" -> if (actual == null) autor = p.getOrNull(1).orEmpty()
				"FILE" -> {
					actual = null
					val entrada = p.getOrNull(1).orEmpty()
					val uri = if (entrada.isNotEmpty()) resolver(entrada) else null
					pistaActual = if (uri != null) {
						archivos += entrada to uri
						archivos.size - 1
					} else -1
				}
				"TRACK" -> {
					val t = Pendiente(p.getOrNull(1)?.toIntOrNull() ?: (tracks.size + 1), pistaActual)
					actual = t
					if (pistaActual >= 0) tracks += t
				}
				"INDEX" -> {
					val t = actual ?: continue
					val ms = tiempoCue(p.getOrNull(2).orEmpty()) ?: continue
					when (p.getOrNull(1)?.toIntOrNull()) {
						1 -> t.inicio = ms
						0 -> t.inicio00 = ms
					}
				}
			}
		}
		val pistas = archivos.mapIndexed { i, (entrada, uri) ->
			val nombre = if (archivos.size == 1 && titulo.isNotBlank()) titulo
			else tracks.firstOrNull { it.pista == i && it.titulo.isNotBlank() }?.titulo ?: tituloDeArchivo(entrada)
			Pista(uri, nombre, 0L)
		}
		val capitulos = tracks.mapNotNull { t ->
			val ms = if (t.inicio >= 0) t.inicio else if (t.inicio00 >= 0) t.inicio00 else return@mapNotNull null
			MarcaAudio(t.titulo.ifBlank { "Pista %02d".format(t.numero) }, 1, t.pista, ms)
		}.sortedWith(compareBy({ it.pista }, { it.ms }))
		return ListaAudio(titulo, autor, pistas, capitulos)
	}

	/** Entrada relativa a una carpeta local; si no está, se busca por el nombre del archivo. */
	private fun enCarpeta(carpeta: File, entrada: String): String? {
		val limpia = entrada.replace('\\', '/')
		val absoluta = File(limpia)
		if (absoluta.isAbsolute && absoluta.isFile) return Uri.fromFile(absoluta).toString()
		val candidatos = listOf(limpia, Uri.decode(limpia))
		for (c in candidatos) {
			val f = File(carpeta, c)
			if (f.isFile) return Uri.fromFile(f).toString()
		}
		val nombre = limpia.substringAfterLast('/')
		return carpeta.listFiles()?.firstOrNull { it.isFile && it.name.equals(nombre, ignoreCase = true) }
			?.let { Uri.fromFile(it).toString() }
	}

	/** Duraciones desconocidas leídas de cada archivo (si no son demasiados). */
	internal fun conDuraciones(pistas: List<Pista>, ctx: ContextoLectura, desde: Int = 20, hasta: Int = 90): List<Pista> {
		if (pistas.size > 200) return pistas
		return pistas.mapIndexed { i, p ->
			ctx.comprobar()
			ctx.progreso(desde + (hasta - desde) * i / pistas.size)
			if (p.duracionMs > 0 || p.uri.startsWith("http")) p
			else Pista(p.uri, p.titulo, CapitulosAudio.duracion(ctx.app, Uri.parse(p.uri)))
		}
	}
}
