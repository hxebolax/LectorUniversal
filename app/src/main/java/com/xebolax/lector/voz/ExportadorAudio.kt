package com.xebolax.lector.voz

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.documentfile.provider.DocumentFile
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.modelo.Documento
import com.xebolax.lector.modelo.Segmentador
import com.xebolax.lector.modelo.TipoBloque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Exporta un libro a audio con la voz elegida (la del libro o la de Ajustes):
 * un archivo M4A (AAC) por capítulo, numerados, en la carpeta que se elija.
 * Así se puede escuchar en cualquier reproductor, en el coche o en un reloj.
 *
 * El motor de voz genera el audio de cada trozo en WAV y aquí se pasa a AAC
 * con el codificador del móvil (MediaCodec), uniendo los trozos del capítulo.
 */
class ExportadorAudio(private val c: Context) {

	class Capitulo(val titulo: String, val bloques: IntRange)

	/** progreso(capítulo actual, total de capítulos, porcentaje del libro). */
	suspend fun exportar(doc: Documento, idLibro: Long, titulo: String, carpeta: Uri, progreso: (Int, Int, Int) -> Unit): Int {
		val capitulos = capitulos(doc)
		val tts = iniciarMotor(idLibro, doc)
		val destino = DocumentFile.fromTreeUri(c, carpeta) ?: error("Carpeta no válida")
		val subcarpeta = destino.findFile(nombreArchivo(titulo))?.takeIf { it.isDirectory } ?: destino.createDirectory(nombreArchivo(titulo))
			?: error("No se puede crear la carpeta")
		val temporal = File(c.cacheDir, "exportar").apply { deleteRecursively(); mkdirs() }
		val p = Preferencias(c)
		val pronunciador = if (p.pronunciacionActiva) Pronunciador(c, idLibro.takeIf { it > 0 }) else null
		val total = doc.caracteres.coerceAtLeast(1)
		// Lo que ya hay en la carpeta, de una vez (buscar archivo a archivo en una carpeta del sistema es muy lento)
		val existentes = withContext(Dispatchers.IO) { subcarpeta.listFiles().associateBy { it.name } }
		var hechos = 0
		try {
			for ((i, cap) in capitulos.withIndex()) {
				coroutineContext.ensureActive()
				progreso(i + 1, capitulos.size, (doc.inicioBloque[cap.bloques.first] * 100 / total).toInt())
				// Trozos de unos 2500 caracteres en frases completas (el motor tiene un máximo por petición)
				val trozos = trozos(doc, cap.bloques, p.saltarNotas).map { t -> pronunciador?.takeIf { !it.vacio }?.aplicar(t) ?: t }
				if (trozos.isEmpty()) continue
				// Cada trozo se codifica en cuanto se genera y su WAV se borra: la caché no crece con capítulos largos
				val m4a = File(temporal, "capitulo.m4a")
				var codificador: Codificador? = null
				try {
					for ((k, t) in trozos.withIndex()) {
						coroutineContext.ensureActive()
						val wav = File(temporal, "t$k.wav")
						if (sintetizar(tts, t, wav)) withContext(Dispatchers.Default) {
							val cod = codificador ?: crearCodificador(wav, m4a)?.also { codificador = it }
							cod?.anadir(wav)
						}
						wav.delete()
					}
					withContext(Dispatchers.Default) { codificador?.terminar() }
				} finally {
					codificador?.liberar()
				}
				if (codificador == null) continue
				val nombre = "%02d - %s.m4a".format(i + 1, nombreArchivo(cap.titulo).take(60))
				existentes[nombre]?.delete()
				val archivo = subcarpeta.createFile("audio/mp4", nombre) ?: error("No se puede crear $nombre")
				withContext(Dispatchers.IO) {
					c.contentResolver.openOutputStream(archivo.uri)?.use { s -> m4a.inputStream().use { it.copyTo(s) } }
				}
				m4a.delete()
				hechos++
			}
		} finally {
			tts.shutdown()
			temporal.deleteRecursively()
		}
		return hechos
	}

	/** Capítulos: las entradas de primer nivel del índice (o el libro entero si no tiene). */
	private fun capitulos(doc: Documento): List<Capitulo> {
		val total = doc.bloques.size
		if (total == 0) return emptyList()
		val nivel = doc.indice.minOfOrNull { it.nivel } ?: return listOf(Capitulo(doc.titulo.ifBlank { "Libro" }, 0 until total))
		val entradas = doc.indice.filter { it.nivel == nivel }.distinctBy { it.bloque }.sortedBy { it.bloque }
		val lista = ArrayList<Capitulo>()
		if (entradas.first().bloque > 0) lista += Capitulo(doc.titulo.ifBlank { "Inicio" }, 0 until entradas.first().bloque)
		for ((i, e) in entradas.withIndex()) {
			val fin = entradas.getOrNull(i + 1)?.bloque ?: total
			if (fin > e.bloque) lista += Capitulo(e.titulo, e.bloque until fin)
		}
		return lista
	}

	private fun trozos(doc: Documento, bloques: IntRange, saltarNotas: Boolean): List<String> {
		val trozos = ArrayList<String>()
		val actual = StringBuilder()
		for (b in bloques) {
			val bl = doc.bloques.getOrNull(b) ?: continue
			if (bl.tipo == TipoBloque.SEPARADOR || (saltarNotas && bl.tipo == TipoBloque.NOTA)) continue
			for (r in Segmentador.frases(bl.texto)) {
				val frase = bl.texto.substring(r.first, r.last + 1)
				if (actual.length + frase.length > 2500 && actual.isNotEmpty()) {
					trozos += actual.toString()
					actual.setLength(0)
				}
				actual.append(frase).append(' ')
			}
			// Fin de párrafo: una pausa corta
			actual.append("\n")
		}
		if (actual.isNotBlank()) trozos += actual.toString()
		return trozos
	}

	private suspend fun iniciarMotor(idLibro: Long, doc: Documento): TextToSpeech {
		val p = Preferencias(c)
		// La voz con la que se lee este libro (la suya sobre la de su sección; lo demás, la general)
		val perfil = withContext(Dispatchers.IO) { PerfilesVoz.efectivo(c, idLibro) }
		val listo = CompletableDeferred<Boolean>()
		val tts = withContext(Dispatchers.Main) {
			TextToSpeech(c, { listo.complete(it == TextToSpeech.SUCCESS) }, perfil.motorEfectivo(p).ifBlank { null }?.takeUnless { ElevenLabs.esMotor(it) })
		}
		if (!listo.await()) {
			tts.shutdown()
			error("El motor de voz no está disponible")
		}
		val velocidad = perfil.velocidadEfectiva(p)
		val nombreVoz = perfil.vozEfectiva(p).takeUnless { ElevenLabs.esVozSuya(it) }.orEmpty()
		tts.setSpeechRate(velocidad / 100f)
		tts.setPitch(perfil.tonoEfectivo(p) / 100f)
		// Pedir las voces es una llamada al motor: fuera del hilo principal
		val voz = if (nombreVoz.isNotBlank()) withContext(Dispatchers.IO) { runCatching { tts.voices?.firstOrNull { it.name == nombreVoz } }.getOrNull() } else null
		if (voz != null) tts.voice = voz
		else runCatching { tts.language = doc.idioma.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault() }
		return tts
	}

	private suspend fun sintetizar(tts: TextToSpeech, texto: String, destino: File): Boolean {
		val hecho = CompletableDeferred<Boolean>()
		val id = destino.name + System.nanoTime()
		tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
			override fun onStart(utteranceId: String?) {}
			override fun onDone(utteranceId: String?) {
				if (utteranceId == id) hecho.complete(true)
			}
			@Deprecated("Deprecated in Java")
			override fun onError(utteranceId: String?) {
				if (utteranceId == id) hecho.complete(false)
			}
			override fun onError(utteranceId: String?, errorCode: Int) {
				if (utteranceId == id) hecho.complete(false)
			}
		})
		if (tts.synthesizeToFile(texto, Bundle(), destino, id) != TextToSpeech.SUCCESS) return false
		return hecho.await() && destino.length() > 44
	}

	// --- WAV → AAC (M4A) ---------------------------------------------------------------

	private class Wav(val frecuencia: Int, val canales: Int, val inicio: Long, val largo: Long)

	private fun leerCabecera(f: File): Wav? = RandomAccessFile(f, "r").use { raf ->
		val cabecera = ByteArray(12)
		raf.readFully(cabecera)
		if (String(cabecera, 0, 4) != "RIFF" || String(cabecera, 8, 4) != "WAVE") return null
		var frecuencia = 0
		var canales = 1
		while (raf.filePointer + 8 <= raf.length()) {
			val id = ByteArray(4).also { raf.readFully(it) }
			val tam = Integer.reverseBytes(raf.readInt()).toLong() and 0xFFFFFFFFL
			val nombre = String(id)
			if (nombre == "fmt ") {
				val fmt = ByteArray(tam.toInt()).also { raf.readFully(it) }
				val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
				canales = b.getShort(2).toInt()
				frecuencia = b.getInt(4)
			} else if (nombre == "data") {
				// Algunos motores dejan el tamaño a 0 o al máximo: se usa lo que queda del archivo
				val largo = if (tam == 0L || raf.filePointer + tam > raf.length()) raf.length() - raf.filePointer else tam
				return Wav(frecuencia, canales, raf.filePointer, largo)
			} else raf.seek(raf.filePointer + tam + (tam and 1))
		}
		null
	}

	/** Un codificador para el capítulo con el formato del primer WAV (null si no es un WAV válido). */
	private fun crearCodificador(primero: File, destino: File): Codificador? {
		val w = leerCabecera(primero) ?: return null
		return Codificador(destino, w.frecuencia.takeIf { it > 0 } ?: 22050, w.canales.coerceIn(1, 2))
	}

	/**
	 * Pasa a AAC (M4A) los WAV de un capítulo según se van generando, con
	 * medio segundo de pausa entre trozos. Así no hace falta guardar todos los
	 * WAV del capítulo (un libro sin índice serían gigas en la caché).
	 */
	private inner class Codificador(destino: File, private val frecuencia: Int, canales: Int) {
		private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
		private val bytesPorMuestra = 2 * canales
		// Pausa de medio segundo entre trozos
		private val silencio = ByteArray(frecuencia / 2 * bytesPorMuestra)
		private val trozo = ByteArray(16_384)
		private val info = MediaCodec.BufferInfo()
		private var pista = -1
		private var muestras = 0L
		private var trozos = 0
		private var liberado = false

		init {
			try {
				val formato = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, frecuencia, canales).apply {
					setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
					setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
					setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
				}
				codec.configure(formato, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
				codec.start()
			} catch (e: Exception) {
				codec.release()
				throw e
			}
		}

		private val muxer = try {
			MediaMuxer(destino.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
		} catch (e: Exception) {
			runCatching { codec.stop() }
			codec.release()
			throw e
		}

		private fun vaciar(final: Boolean) {
			while (true) {
				val i = codec.dequeueOutputBuffer(info, if (final) 10_000 else 0)
				when {
					i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!final) return
					i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
						pista = muxer.addTrack(codec.outputFormat)
						muxer.start()
					}
					i >= 0 -> {
						val salida = codec.getOutputBuffer(i)!!
						if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
						if (info.size > 0 && pista >= 0) {
							salida.position(info.offset)
							salida.limit(info.offset + info.size)
							muxer.writeSampleData(pista, salida, info)
						}
						codec.releaseOutputBuffer(i, false)
						if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
					}
				}
			}
		}

		private fun meter(datos: ByteArray, largo: Int) {
			var hecho = 0
			while (hecho < largo) {
				val i = codec.dequeueInputBuffer(10_000)
				if (i < 0) {
					vaciar(false)
					continue
				}
				val entrada = codec.getInputBuffer(i)!!
				entrada.clear()
				val n = minOf(entrada.remaining(), largo - hecho)
				entrada.put(datos, hecho, n)
				codec.queueInputBuffer(i, 0, n, muestras * 1_000_000L / frecuencia, 0)
				muestras += n / bytesPorMuestra
				hecho += n
				vaciar(false)
			}
		}

		/** Añade un WAV (si no es válido, se salta). */
		fun anadir(f: File) {
			val w = leerCabecera(f) ?: return
			if (trozos++ > 0) meter(silencio, silencio.size)
			RandomAccessFile(f, "r").use { raf ->
				raf.seek(w.inicio)
				var queda = w.largo
				while (queda > 0) {
					val n = raf.read(trozo, 0, minOf(trozo.size.toLong(), queda).toInt())
					if (n <= 0) break
					meter(trozo, n - n % bytesPorMuestra)
					queda -= n
				}
			}
		}

		/** Cierra el audio del capítulo (el archivo queda completo). */
		fun terminar() {
			val i = codec.dequeueInputBuffer(10_000)
			if (i >= 0) codec.queueInputBuffer(i, 0, 0, muestras * 1_000_000L / frecuencia, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
			vaciar(true)
		}

		/** Suelta el codificador y el archivo (también si se canceló a medias). */
		fun liberar() {
			if (liberado) return
			liberado = true
			runCatching { codec.stop() }
			runCatching { codec.release() }
			runCatching { if (pista >= 0) muxer.stop() }
			runCatching { muxer.release() }
		}
	}

	private fun nombreArchivo(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex(" +"), " ").trim().ifBlank { "Libro" }
}
