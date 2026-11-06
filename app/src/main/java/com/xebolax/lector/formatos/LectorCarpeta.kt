package com.xebolax.lector.formatos

import android.net.Uri
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.LibroAudio
import com.xebolax.lector.modelo.MarcaAudio
import com.xebolax.lector.modelo.Pista

/**
 * Carpeta elegida con el selector (o extraída de un ZIP): libro DAISY
 * (ncc.html u OPF con NCX/SMIL) o audiolibro en varios archivos (una pista
 * por archivo en orden natural, o el orden de su lista .cue/.m3u).
 */
object LectorCarpeta : LectorFormato {

	/** Extensiones con capítulos internos que merece la pena leer. */
	private val CON_CAPITULOS = setOf("m4b", "m4a", "mp4", "mp3")
	private val LISTAS = setOf("cue", "m3u", "m3u8")
	/** Archivos que acompañan a cualquier cosa y no son "el documento". */
	private val ACOMPANANTES = setOf("nfo", "log", "ini", "json", "xml", "srt", "vtt", "csv", "tsv", "sfv", "md5")

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido =
		fuente.contenedor().use { leerContenedor(it, ctx, fuente.nombre) }

	fun leerContenedor(z: Contenedor, ctx: ContextoLectura, nombre: String): Contenido {
		val entradas = z.entradas().filterNot { ruta ->
			ruta.startsWith("__MACOSX") || ruta.split('/').any { it.startsWith(".") }
		}
		fun profundidad(ruta: String) = ruta.count { it == '/' }
		val minusculas = entradas.map { it.lowercase() }
		val hayNcc = minusculas.any { (it.substringAfterLast('/') == "ncc.html" || it.substringAfterLast('/') == "ncc.htm") && profundidad(it) <= 2 }
		val hayDaisy3 = minusculas.any { it.endsWith(".opf") && profundidad(it) <= 2 } &&
			minusculas.any { it.endsWith(".ncx") || it.endsWith(".smil") }
		if (hayNcc || hayDaisy3) return LectorDaisy.leerContenedor(z, ctx, nombre)

		val audios = entradas.filter { Formato.deExtension(Fuente.extensionDe(it)) == Formato.AUDIO }
		if (audios.isNotEmpty()) {
			val listas = entradas.filter { Fuente.extensionDe(it) in LISTAS }
			return audiolibro(z, ctx, nombre, audios, listas)
		}
		val documentos = entradas.filter {
			val ext = Fuente.extensionDe(it)
			val f = Formato.deExtension(ext)
			f != Formato.DESCONOCIDO && f != Formato.ZIP && !f.audio && ext !in ACOMPANANTES
		}
		if (documentos.size == 1) {
			throw ExcepcionFormato("Esta carpeta no es un libro DAISY ni un audiolibro. Abre el archivo directamente.")
		}
		throw ExcepcionFormato("Esta carpeta no contiene un libro DAISY ni archivos de audio.")
	}

	private fun audiolibro(z: Contenedor, ctx: ContextoLectura, nombre: String, audios: List<String>, listas: List<String>): Contenido {
		ctx.progreso(5)
		// (ruta en la carpeta o null, pista)
		var pistas: List<Pair<String?, Pista>> = emptyList()
		var capitulosLista: List<MarcaAudio> = emptyList()
		var tituloLista = ""
		var autorLista = ""
		if (listas.size == 1) {
			val ruta = listas[0]
			val texto = runCatching { z.texto(ruta) }.getOrNull()
			if (texto != null) {
				val lista = LectorListaAudio.analizar(texto, resolverEn(z, ruta, audios))
				if (lista.pistas.isNotEmpty()) {
					pistas = lista.pistas.map { null to it }
					capitulosLista = lista.capitulos
					tituloLista = lista.titulo
					autorLista = lista.autor
				}
			}
		}
		if (pistas.isEmpty()) {
			pistas = audios.sortedWith(OrdenNatural).mapNotNull { ruta ->
				z.uriReproducible(ruta)?.let { ruta to Pista(it, LectorListaAudio.tituloDeArchivo(ruta), 0L) }
			}
		}
		if (pistas.isEmpty()) throw ExcepcionFormato("No se pueden reproducir los archivos de audio de esta carpeta.")

		// Duraciones
		val conDuracion = LectorListaAudio.conDuraciones(pistas.map { it.second }, ctx, 10, 60)
		pistas = pistas.mapIndexed { i, p -> p.first to conDuracion[i] }

		// Capítulos: una marca por pista y, dentro, los capítulos internos
		val capitulos = ArrayList<MarcaAudio>()
		if (capitulosLista.isNotEmpty()) {
			capitulos += capitulosLista
		} else {
			val leerInternos = pistas.size <= 60
			for ((i, par) in pistas.withIndex()) {
				val (ruta, pista) = par
				capitulos += MarcaAudio(pista.titulo, 1, i, 0)
				if (!leerInternos) continue
				ctx.comprobar()
				ctx.progreso(60 + 35 * i / pistas.size)
				val ext = Fuente.extensionDe(ruta ?: Uri.decode(pista.uri))
				if (ext !in CON_CAPITULOS) continue
				val internos = CapitulosAudio.leer(ctx.app, Uri.parse(pista.uri))
				// Un único capítulo al principio no aporta nada
				if (internos.size == 1 && internos[0].second < 1000) continue
				for ((t, ms) in internos) capitulos += MarcaAudio(t, 2, i, ms)
			}
		}

		// Etiquetas del primer archivo (leer las de todos sería lento)
		val info = CapitulosAudio.metadatos(ctx.app, Uri.parse(pistas[0].second.uri))
		val titulo = tituloLista.ifBlank { nombre }.ifBlank { info.album }.ifBlank { "Audiolibro" }
		val autor = autorLista.ifBlank { info.autorLibro }
		val lista = pistas.map { it.second }
		val total = if (lista.all { it.duracionMs > 0 }) lista.sumOf { it.duracionMs } else 0L
		val metadatos = linkedMapOf(
			"Autor" to autor,
			"Narrador" to info.narrador,
			"Álbum" to (if (info.album != titulo) info.album else ""),
			"Género" to info.genero,
			"Fecha" to info.fecha,
			"Pistas" to lista.size.toString(),
			"Duración total" to (if (total > 0) CapitulosAudio.duracionLegible(total) else ""),
		)
		ctx.progreso(100)
		return Contenido.Audio(
			LibroAudio(
				titulo = titulo,
				autor = autor,
				narrador = info.narrador,
				pistas = lista,
				capitulos = capitulos,
				metadatos = metadatos.filterValues { it.isNotBlank() },
			),
		)
	}

	/** Resuelve las entradas de una lista que está dentro de la carpeta. */
	private fun resolverEn(z: Contenedor, rutaLista: String, audios: List<String>): (String) -> String? = resolver@{ entrada ->
		val e = entrada.trim()
		if (e.isEmpty()) return@resolver null
		if (e.contains("://")) return@resolver if (e.startsWith("file://")) null else e
		val limpia = e.replace('\\', '/')
		val carpeta = rutaLista.substringBeforeLast('/', "")
		val candidatos = listOf(
			if (carpeta.isEmpty()) limpia else "$carpeta/$limpia",
			if (carpeta.isEmpty()) Uri.decode(limpia) else "$carpeta/${Uri.decode(limpia)}",
			limpia,
		)
		for (c in candidatos) z.buscar(Contenedor.normalizar(c))?.let { return@resolver z.uriReproducible(it) }
		// Rutas absolutas de otro equipo: por el nombre del archivo
		val nombre = limpia.substringAfterLast('/')
		audios.firstOrNull { it.substringAfterLast('/').equals(nombre, ignoreCase = true) }?.let { z.uriReproducible(it) }
	}
}
