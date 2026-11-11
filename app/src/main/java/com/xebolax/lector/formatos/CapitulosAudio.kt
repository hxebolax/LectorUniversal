package com.xebolax.lector.formatos

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Etiquetas de un archivo de audio (las que da MediaMetadataRetriever). */
data class InfoAudio(
	val titulo: String = "",
	val artista: String = "",
	val artistaAlbum: String = "",
	val album: String = "",
	val autor: String = "",
	val compositor: String = "",
	val escritor: String = "",
	val genero: String = "",
	val fecha: String = "",
	val duracionMs: Long = 0,
) {
	/** Autor del libro: artista, artista del álbum, autor o escritor. */
	val autorLibro: String get() = artista.ifBlank { artistaAlbum }.ifBlank { autor }.ifBlank { escritor }

	/** Narrador: en los audiolibros suele ir en el compositor. */
	val narrador: String get() = compositor.takeIf { it != autorLibro }.orEmpty()
}

/**
 * Capítulos internos de un archivo de audio, leídos con acceso aleatorio
 * (sin cargar el archivo entero):
 *
 * - MP4/M4B/M4A: pista de capítulos de QuickTime (tref/chap → pista de
 *   texto) y lista de Nero (moov/udta/chpl). Se prefiere la de QuickTime.
 * - MP3: marcos CHAP de ID3v2.3/2.4 (con CTOC para el orden).
 */
object CapitulosAudio {

	/** Capítulos (título, inicio en ms) ordenados por tiempo; vacío si no tiene o no se pueden leer. */
	fun leer(contexto: Context, uri: Uri): List<Pair<String, Long>> = try {
		if (uri.scheme == "http" || uri.scheme == "https") leerDe(AleatorioWeb(uri.toString()))
		else conCanal(contexto, uri) { canal -> leerDe(AleatorioArchivo(canal)) } ?: emptyList()
	} catch (e: InterruptedException) {
		throw e
	} catch (_: Throwable) {
		emptyList()
	}

	private fun leerDe(a: Aleatorio): List<Pair<String, Long>> {
		val cab = a.leer(0, 12)
		val crudos = when {
			cab.size >= 10 && cab[0] == 'I'.code.toByte() && cab[1] == 'D'.code.toByte() && cab[2] == '3'.code.toByte() -> leerId3(a)
			cab.size >= 8 && String(cab, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> leerMp4(a)
			else -> emptyList()
		}
		return normalizar(crudos)
	}

	/** Etiquetas del archivo. Nunca falla: si no se pueden leer, devuelve campos vacíos. */
	fun metadatos(contexto: Context, uri: Uri): InfoAudio {
		val r = MediaMetadataRetriever()
		return try {
			if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(contexto, uri)
			fun dato(clave: Int) = runCatching { r.extractMetadata(clave) }.getOrNull()?.trim().orEmpty()
			InfoAudio(
				titulo = dato(MediaMetadataRetriever.METADATA_KEY_TITLE),
				artista = dato(MediaMetadataRetriever.METADATA_KEY_ARTIST),
				artistaAlbum = dato(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
				album = dato(MediaMetadataRetriever.METADATA_KEY_ALBUM),
				autor = dato(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
				compositor = dato(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
				escritor = dato(MediaMetadataRetriever.METADATA_KEY_WRITER),
				genero = dato(MediaMetadataRetriever.METADATA_KEY_GENRE),
				fecha = dato(MediaMetadataRetriever.METADATA_KEY_YEAR).ifBlank { dato(MediaMetadataRetriever.METADATA_KEY_DATE) },
				duracionMs = dato(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0L,
			)
		} catch (_: Throwable) {
			InfoAudio()
		} finally {
			runCatching { r.release() }
		}
	}

	/** Solo la duración (0 si no se sabe). */
	fun duracion(contexto: Context, uri: Uri): Long {
		val r = MediaMetadataRetriever()
		return try {
			if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(contexto, uri)
			r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.trim()?.toLongOrNull() ?: 0L
		} catch (_: Throwable) {
			0L
		} finally {
			runCatching { r.release() }
		}
	}

	/** "1:02:03" o "12:34". */
	fun duracionLegible(ms: Long): String {
		val s = (ms / 1000).coerceAtLeast(0)
		val h = s / 3600
		val m = (s % 3600) / 60
		val seg = s % 60
		return if (h > 0) "%d:%02d:%02d".format(h, m, seg) else "%d:%02d".format(m, seg)
	}

	// --- Acceso aleatorio ------------------------------------------------------

	private fun <T> conCanal(contexto: Context, uri: Uri, bloque: (FileChannel) -> T): T? {
		if (uri.scheme == "file" || uri.scheme == null) {
			return FileInputStream(File(uri.path ?: return null)).use { bloque(it.channel) }
		}
		val pfd = contexto.contentResolver.openFileDescriptor(uri, "r") ?: return null
		return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { bloque(it.channel) }
	}

	private abstract class Aleatorio {
		abstract val tamano: Long
		abstract fun leer(pos: Long, n: Int): ByteArray
	}

	/**
	 * Un audio de internet (episodio de podcast sin descargar): se leen solo los
	 * trozos que hacen falta con peticiones parciales (Range), sin bajarlo entero.
	 */
	private class AleatorioWeb(private val url: String) : Aleatorio() {
		private val bloque = 64 * 1024
		private val cache = LinkedHashMap<Long, ByteArray>()
		override val tamano: Long

		init {
			val c = abrir(0, 0)
			try {
				val rango = c.getHeaderField("Content-Range")
				tamano = rango?.substringAfter('/')?.trim()?.toLongOrNull() ?: c.contentLengthLong.takeIf { c.responseCode == 200 } ?: -1
				if (c.responseCode != 206 || tamano <= 0) error("El servidor no permite leer por partes")
			} finally {
				c.disconnect()
			}
		}

		private fun abrir(desde: Long, hasta: Long): java.net.HttpURLConnection {
			var actual = url
			repeat(6) {
				val c = java.net.URL(actual).openConnection() as java.net.HttpURLConnection
				c.connectTimeout = 15_000
				c.readTimeout = 20_000
				c.instanceFollowRedirects = false
				c.setRequestProperty("Range", "bytes=$desde-$hasta")
				c.setRequestProperty("User-Agent", "LectorUniversal")
				if (c.responseCode in 300..399) {
					actual = java.net.URL(java.net.URL(actual), c.getHeaderField("Location") ?: error("Redirección sin destino")).toString()
					c.disconnect()
					return@repeat
				}
				return c
			}
			error("Demasiadas redirecciones")
		}

		private fun trozo(i: Long): ByteArray = cache.getOrPut(i) {
			if (cache.size > 64) cache.remove(cache.keys.first())
			val desde = i * bloque
			val c = abrir(desde, minOf(desde + bloque, tamano) - 1)
			try {
				// Solo el trozo pedido: si el servidor manda el archivo entero, no se lee
				if (c.responseCode != 206) error("El servidor no permite leer por partes")
				c.inputStream.use { Fuente.leerConLimite(it, bloque.toLong()) } ?: error("Respuesta demasiado grande")
			} finally {
				c.disconnect()
			}
		}

		override fun leer(pos: Long, n: Int): ByteArray {
			if (pos < 0 || pos >= tamano || n <= 0) return ByteArray(0)
			val cuantos = minOf(n.toLong(), tamano - pos).toInt()
			// Lo que no sea cabecera de capítulos (más de 2 MB de una vez) no se lee por internet
			if (cuantos > 2 shl 20) error("Demasiado para leer por internet")
			val salida = java.io.ByteArrayOutputStream(cuantos)
			var p = pos
			while (salida.size() < cuantos) {
				val i = p / bloque
				val t = trozo(i)
				val dentro = (p - i * bloque).toInt()
				if (dentro >= t.size) break
				val n2 = minOf(t.size - dentro, cuantos - salida.size())
				salida.write(t, dentro, n2)
				p += n2
			}
			return salida.toByteArray()
		}
	}

	private class AleatorioArchivo(private val canal: FileChannel) : Aleatorio() {
		override val tamano: Long = canal.size()

		override fun leer(pos: Long, n: Int): ByteArray {
			if (pos < 0 || pos >= tamano || n <= 0) return ByteArray(0)
			val cuantos = minOf(n.toLong(), tamano - pos).toInt()
			val b = ByteBuffer.allocate(cuantos)
			var p = pos
			while (b.hasRemaining()) {
				val r = canal.read(b, p)
				if (r <= 0) break
				p += r
			}
			return if (b.position() == cuantos) b.array() else b.array().copyOf(b.position())
		}
	}

	private fun u8(d: ByteArray, p: Int) = d[p].toInt() and 0xFF
	private fun u16(d: ByteArray, p: Int) = (u8(d, p) shl 8) or u8(d, p + 1)
	private fun u32(d: ByteArray, p: Int): Long =
		(u8(d, p).toLong() shl 24) or (u8(d, p + 1).toLong() shl 16) or (u8(d, p + 2).toLong() shl 8) or u8(d, p + 3).toLong()
	private fun u64(d: ByteArray, p: Int): Long = (u32(d, p) shl 32) or u32(d, p + 4)
	private fun sincroseguro(d: ByteArray, p: Int): Int =
		((d[p].toInt() and 0x7F) shl 21) or ((d[p + 1].toInt() and 0x7F) shl 14) or
			((d[p + 2].toInt() and 0x7F) shl 7) or (d[p + 3].toInt() and 0x7F)

	/** Ordena, quita repetidos y pone título a los que no lo tienen. */
	private fun normalizar(crudos: List<Pair<String, Long>>): List<Pair<String, Long>> {
		val ordenados = crudos.filter { it.second >= 0 }.sortedBy { it.second }
		val r = ArrayList<Pair<String, Long>>()
		for ((i, c) in ordenados.withIndex()) {
			val titulo = c.first.replace('\u0000', ' ').trim().ifBlank { "Capítulo ${i + 1}" }
			if (r.isNotEmpty() && r.last().second == c.second && r.last().first == titulo) continue
			r += titulo to c.second
		}
		return r
	}

	// --- MP4 / M4B ---------------------------------------------------------------

	private class Caja(val tipo: String, val datos: Long, val fin: Long)

	private fun cajas(a: Aleatorio, desde: Long, hasta: Long): List<Caja> {
		val r = ArrayList<Caja>()
		var p = desde
		val limite = minOf(hasta, a.tamano)
		while (p + 8 <= limite && r.size < 100_000) {
			val cab = a.leer(p, 16)
			if (cab.size < 8) break
			var tam = u32(cab, 0)
			val tipo = String(cab, 4, 4, Charsets.ISO_8859_1)
			var cabecera = 8
			when (tam) {
				1L -> {
					if (cab.size < 16) break
					tam = u64(cab, 8)
					cabecera = 16
				}
				0L -> tam = limite - p
			}
			if (tam < cabecera) break
			if (p + tam > limite) tam = limite - p
			r += Caja(tipo, p + cabecera, p + tam)
			p += tam
		}
		return r
	}

	private fun hijos(a: Aleatorio, c: Caja, saltar: Int = 0) = cajas(a, c.datos + saltar, c.fin)

	private fun hijo(a: Aleatorio, c: Caja?, tipo: String): Caja? =
		c?.let { padre -> hijos(a, padre, if (padre.tipo == "meta") 4 else 0).firstOrNull { it.tipo == tipo } }

	private fun datos(a: Aleatorio, c: Caja, maximo: Int = 16 shl 20): ByteArray =
		a.leer(c.datos, minOf(c.fin - c.datos, maximo.toLong()).toInt())

	private fun leerMp4(a: Aleatorio): List<Pair<String, Long>> {
		val moov = cajas(a, 0, a.tamano).firstOrNull { it.tipo == "moov" } ?: return emptyList()
		val hijosMoov = hijos(a, moov)
		val quicktime = runCatching { capitulosQuickTime(a, hijosMoov.filter { it.tipo == "trak" }) }.getOrDefault(emptyList())
		if (quicktime.isNotEmpty()) return quicktime
		val udta = hijosMoov.firstOrNull { it.tipo == "udta" } ?: return emptyList()
		val chpl = hijo(a, udta, "chpl") ?: hijo(a, hijo(a, udta, "meta"), "chpl") ?: return emptyList()
		return leerChpl(datos(a, chpl, 4 shl 20))
	}

	/** Lista de Nero: versión, banderas, [reservado], número de capítulos y (inicio en 100 ns, longitud, título). */
	private fun leerChpl(d: ByteArray): List<Pair<String, Long>> {
		if (d.size < 5) return emptyList()
		val version = u8(d, 0)
		// (desplazamiento del contador, bytes del contador): primero la forma habitual (la de ffmpeg)
		val formas = if (version != 0) listOf(8 to 1, 4 to 1, 8 to 4, 4 to 4) else listOf(4 to 1, 8 to 1, 4 to 4, 8 to 4)
		for ((inicio, ancho) in formas) probarChpl(d, inicio, ancho)?.let { return it }
		return emptyList()
	}

	private fun probarChpl(d: ByteArray, inicio: Int, ancho: Int): List<Pair<String, Long>>? {
		if (inicio + ancho > d.size) return null
		val cuenta = if (ancho == 1) u8(d, inicio).toLong() else u32(d, inicio)
		if (cuenta <= 0 || cuenta > 10_000) return null
		var p = inicio + ancho
		val r = ArrayList<Pair<String, Long>>()
		var anterior = -1L
		for (i in 0 until cuenta.toInt()) {
			if (p + 9 > d.size) return null
			val t = u64(d, p)
			p += 8
			val largo = u8(d, p)
			p += 1
			if (p + largo > d.size || t < 0) return null
			val ms = t / 10_000
			if (ms < anterior) return null
			anterior = ms
			r += String(d, p, largo, Charsets.UTF_8) to ms
			p += largo
		}
		return r
	}

	private class Trak(val caja: Caja, val id: Long, val capitulos: List<Long>, val manejador: String)

	private fun capitulosQuickTime(a: Aleatorio, traks: List<Caja>): List<Pair<String, Long>> {
		if (traks.size < 2) return emptyList()
		val lista = traks.map { t ->
			val tkhd = hijo(a, t, "tkhd")
			val id = tkhd?.let { c ->
				val d = a.leer(c.datos, 24)
				when {
					d.size >= 24 && u8(d, 0) == 1 -> u32(d, 20)
					d.size >= 16 -> u32(d, 12)
					else -> -1L
				}
			} ?: -1L
			val chap = hijo(a, hijo(a, t, "tref"), "chap")
			val ids = chap?.let { c ->
				val d = datos(a, c, 4096)
				(0 until d.size / 4).map { u32(d, it * 4) }
			}.orEmpty()
			val hdlr = hijo(a, hijo(a, t, "mdia"), "hdlr")
			val manejador = hdlr?.let { c ->
				val d = a.leer(c.datos, 12)
				if (d.size >= 12) String(d, 8, 4, Charsets.ISO_8859_1) else ""
			}.orEmpty()
			Trak(t, id, ids, manejador)
		}
		val referidos = lista.flatMap { it.capitulos }.toSet()
		if (referidos.isEmpty()) return emptyList()
		val candidatos = lista.filter { it.id in referidos }
		val elegido = candidatos.firstOrNull { it.manejador == "text" || it.manejador == "sbtl" } ?: candidatos.firstOrNull()
			?: return emptyList()
		return leerPistaTexto(a, elegido.caja)
	}

	/** Muestras de una pista de texto: cada una, 2 bytes de longitud y el texto. */
	private fun leerPistaTexto(a: Aleatorio, trak: Caja): List<Pair<String, Long>> {
		val mdia = hijo(a, trak, "mdia") ?: return emptyList()
		val mdhd = hijo(a, mdia, "mdhd") ?: return emptyList()
		val dm = a.leer(mdhd.datos, 24)
		val escala = when {
			dm.size >= 24 && u8(dm, 0) == 1 -> u32(dm, 20)
			dm.size >= 16 -> u32(dm, 12)
			else -> 0L
		}
		if (escala <= 0) return emptyList()
		val stbl = hijo(a, hijo(a, mdia, "minf"), "stbl") ?: return emptyList()
		val tablas = hijos(a, stbl).associateBy { it.tipo }
		val stts = tablas["stts"]?.let { datos(a, it) } ?: return emptyList()
		val stsz = tablas["stsz"]?.let { datos(a, it) } ?: return emptyList()
		val stsc = tablas["stsc"]?.let { datos(a, it) } ?: return emptyList()
		val co64 = tablas["co64"] != null
		val stco = (tablas["stco"] ?: tablas["co64"])?.let { datos(a, it) } ?: return emptyList()
		val maximo = 20_000

		// Tamaños
		if (stsz.size < 12) return emptyList()
		val tamFijo = u32(stsz, 4)
		val n = minOf(u32(stsz, 8), maximo.toLong()).toInt()
		val tamanos = LongArray(n) { i ->
			if (tamFijo != 0L) tamFijo else if (12 + i * 4 + 4 <= stsz.size) u32(stsz, 12 + i * 4) else 0L
		}
		// Duraciones → inicios
		val inicios = LongArray(n)
		if (stts.size < 8) return emptyList()
		var muestra = 0
		var tiempo = 0L
		val entradasStts = u32(stts, 4).toInt()
		for (e in 0 until entradasStts) {
			val p = 8 + e * 8
			if (p + 8 > stts.size) break
			val cuenta = u32(stts, p)
			val delta = u32(stts, p + 4)
			var k = 0L
			while (k < cuenta && muestra < n) {
				inicios[muestra++] = tiempo
				tiempo += delta
				k++
			}
			if (muestra >= n) break
		}
		// Desplazamientos: fragmentos (stco/co64) y muestras por fragmento (stsc)
		if (stco.size < 8 || stsc.size < 8) return emptyList()
		val nFragmentos = u32(stco, 4).toInt().coerceAtMost(maximo)
		val fragmentos = LongArray(nFragmentos) { i ->
			if (co64) { if (8 + i * 8 + 8 <= stco.size) u64(stco, 8 + i * 8) else 0L }
			else { if (8 + i * 4 + 4 <= stco.size) u32(stco, 8 + i * 4) else 0L }
		}
		val nStsc = u32(stsc, 4).toInt()
		val primeros = ArrayList<Long>()
		val porFragmento = ArrayList<Long>()
		for (e in 0 until nStsc) {
			val p = 8 + e * 12
			if (p + 12 > stsc.size) break
			primeros += u32(stsc, p)
			porFragmento += u32(stsc, p + 4)
		}
		if (primeros.isEmpty()) return emptyList()
		val desplazamientos = LongArray(n) { -1L }
		muestra = 0
		var entrada = 0
		for (f in 0 until nFragmentos) {
			if (muestra >= n) break
			val numero = f + 1L
			while (entrada + 1 < primeros.size && primeros[entrada + 1] <= numero) entrada++
			var pos = fragmentos[f]
			var k = 0L
			while (k < porFragmento[entrada] && muestra < n) {
				desplazamientos[muestra] = pos
				pos += tamanos[muestra]
				muestra++
				k++
			}
		}
		val r = ArrayList<Pair<String, Long>>()
		for (i in 0 until n) {
			if (desplazamientos[i] < 0 || tamanos[i] < 2) continue
			val d = a.leer(desplazamientos[i], minOf(tamanos[i], 4096L).toInt())
			if (d.size < 2) continue
			val largo = minOf(u16(d, 0), d.size - 2)
			r += textoMuestra(d, 2, largo) to inicios[i] * 1000 / escala
		}
		return r
	}

	private fun textoMuestra(d: ByteArray, desde: Int, largo: Int): String {
		if (largo <= 0) return ""
		if (largo >= 2) {
			val b0 = u8(d, desde)
			val b1 = u8(d, desde + 1)
			if (b0 == 0xFE && b1 == 0xFF) return String(d, desde + 2, largo - 2, Charsets.UTF_16BE)
			if (b0 == 0xFF && b1 == 0xFE) return String(d, desde + 2, largo - 2, Charsets.UTF_16LE)
		}
		return String(d, desde, largo, Charsets.UTF_8)
	}

	// --- MP3 (ID3v2) -------------------------------------------------------------

	private class Marco(val id: String, val datos: ByteArray)

	private fun leerId3(a: Aleatorio): List<Pair<String, Long>> {
		val cab = a.leer(0, 10)
		if (cab.size < 10) return emptyList()
		val version = u8(cab, 3)
		if (version != 3 && version != 4) return emptyList()
		val banderas = u8(cab, 5)
		val tam = sincroseguro(cab, 6).coerceAtMost(64 shl 20)
		var d = a.leer(10, tam)
		val desincronizado = (banderas and 0x80) != 0
		if (version == 3 && desincronizado) d = desincronizar(d)
		var p = 0
		if ((banderas and 0x40) != 0 && d.size >= 4) {
			p = if (version == 3) 4 + u32(d, 0).toInt() else sincroseguro(d, 0)
			if (p < 0 || p > d.size) return emptyList()
		}
		val marcos = marcos(d, p, d.size, version, version == 4 && desincronizado)
		val capitulos = LinkedHashMap<String, Pair<String, Long>>()
		val indices = ArrayList<Pair<Boolean, List<String>>>()
		for (m in marcos) {
			when (m.id) {
				"CHAP" -> leerChap(m.datos, version)?.let { (id, cap) -> capitulos.putIfAbsent(id, cap) }
				"CTOC" -> leerCtoc(m.datos)?.let { indices += it }
			}
		}
		if (capitulos.isEmpty()) return emptyList()
		// Orden del índice principal (CTOC de nivel superior) si lo hay; luego se ordena por tiempo
		val principal = indices.firstOrNull { it.first } ?: indices.firstOrNull()
		val orden = ArrayList<Pair<String, Long>>()
		if (principal != null) {
			val usados = HashSet<String>()
			for (id in principal.second) capitulos[id]?.let { if (usados.add(id)) orden += it }
			for ((id, c) in capitulos) if (id !in usados) orden += c
		} else orden += capitulos.values
		return orden
	}

	private fun desincronizar(d: ByteArray): ByteArray {
		val r = java.io.ByteArrayOutputStream(d.size)
		var i = 0
		while (i < d.size) {
			r.write(d[i].toInt())
			if (d[i] == 0xFF.toByte() && i + 1 < d.size && d[i + 1] == 0.toByte()) i++
			i++
		}
		return r.toByteArray()
	}

	private fun idValido(d: ByteArray, p: Int): Boolean {
		if (p + 4 > d.size) return false
		for (k in 0 until 4) {
			val c = d[p + k].toInt().toChar()
			if (!(c in 'A'..'Z' || c in '0'..'9')) return false
		}
		return true
	}

	/** Después de un marco: fin, relleno o un marco válido. */
	private fun siguienteValido(d: ByteArray, p: Int, fin: Int): Boolean =
		p == fin || (p < fin && (d[p].toInt() == 0 || idValido(d, p)))

	private fun marcos(d: ByteArray, desde: Int, hasta: Int, version: Int, desincronizadoV4: Boolean): List<Marco> {
		val r = ArrayList<Marco>()
		var p = desde
		val fin = minOf(hasta, d.size)
		while (p + 10 <= fin && r.size < 50_000) {
			if (!idValido(d, p)) break
			val id = String(d, p, 4, Charsets.ISO_8859_1)
			var tam = if (version == 4) {
				val sinc = sincroseguro(d, p + 4)
				val plano = u32(d, p + 4).toInt()
				// Algunos programas escriben tamaños normales en la 2.4
				if (plano != sinc && !siguienteValido(d, p + 10 + sinc, fin) && siguienteValido(d, p + 10 + plano, fin)) plano else sinc
			} else u32(d, p + 4).toInt()
			if (tam < 0 || p + 10 + tam > fin) tam = fin - p - 10
			val formato = u8(d, p + 9)
			var inicio = p + 10
			var cuerpo: ByteArray? = null
			if (version == 4) {
				if ((formato and 0x0C) == 0) {
					if ((formato and 0x40) != 0) inicio++
					if ((formato and 0x01) != 0) inicio += 4
					if (inicio <= p + 10 + tam) {
						cuerpo = d.copyOfRange(inicio, p + 10 + tam)
						if ((formato and 0x02) != 0 || desincronizadoV4) cuerpo = desincronizar(cuerpo)
					}
				}
			} else {
				if ((formato and 0xC0) == 0) {
					if ((formato and 0x20) != 0) inicio++
					if (inicio <= p + 10 + tam) cuerpo = d.copyOfRange(inicio, p + 10 + tam)
				}
			}
			if (cuerpo != null) r += Marco(id, cuerpo)
			p += 10 + tam
		}
		return r
	}

	private fun finCadena(d: ByteArray, desde: Int): Int {
		var i = desde
		while (i < d.size && d[i].toInt() != 0) i++
		return i
	}

	/** CHAP: id\0, inicio, fin, desplazamientos y submarcos (TIT2). */
	private fun leerChap(d: ByteArray, version: Int): Pair<String, Pair<String, Long>>? {
		val finId = finCadena(d, 0)
		if (finId + 17 > d.size) return null
		val id = String(d, 0, finId, Charsets.ISO_8859_1)
		val inicio = u32(d, finId + 1)
		val sub = marcos(d, finId + 17, d.size, version, false)
		val titulo = sub.firstOrNull { it.id == "TIT2" }?.let { textoId3(it.datos) }
			?: sub.firstOrNull { it.id == "TIT3" }?.let { textoId3(it.datos) }
			?: ""
		return id to (titulo to inicio)
	}

	/** CTOC: id\0, banderas, número de entradas y sus id. Devuelve (es de nivel superior, hijos). */
	private fun leerCtoc(d: ByteArray): Pair<Boolean, List<String>>? {
		val finId = finCadena(d, 0)
		if (finId + 3 > d.size) return null
		val banderas = u8(d, finId + 1)
		val cuenta = u8(d, finId + 2)
		var p = finId + 3
		val hijos = ArrayList<String>()
		repeat(cuenta) {
			if (p >= d.size) return@repeat
			val f = finCadena(d, p)
			hijos += String(d, p, f - p, Charsets.ISO_8859_1)
			p = f + 1
		}
		return ((banderas and 0x02) != 0) to hijos
	}

	/** Texto de un marco: codificación (0 Latin-1, 1 UTF-16 con BOM, 2 UTF-16BE, 3 UTF-8) y valor. */
	private fun textoId3(d: ByteArray): String {
		if (d.isEmpty()) return ""
		val juego = when (d[0].toInt()) {
			1 -> Charsets.UTF_16
			2 -> Charsets.UTF_16BE
			3 -> Charsets.UTF_8
			else -> Charsets.ISO_8859_1
		}
		var largo = d.size - 1
		if (juego == Charsets.UTF_16 || juego == Charsets.UTF_16BE) largo -= largo % 2
		val texto = String(d, 1, largo, juego)
		return texto.split('\u0000').firstOrNull { it.isNotBlank() }?.trim().orEmpty()
	}
}
