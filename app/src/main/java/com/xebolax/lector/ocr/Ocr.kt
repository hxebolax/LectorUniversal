package com.xebolax.lector.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.xebolax.lector.R
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.formatos.Formato
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Reconocimiento de texto (OCR) sin internet con ML Kit (el modelo va dentro
 * de la app): PDF escaneados, páginas sueltas, imágenes del
 * libro y documentos escaneados con la cámara. El resultado es un documento
 * HTML con una página por cada hoja, que se lee y se navega como cualquier libro.
 */
object Ocr {

	class ErrorOcr(mensaje: String) : Exception(mensaje)

	private val reconocedor by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

	/** Texto de una imagen: párrafos separados por una línea en blanco. */
	suspend fun reconocer(c: Context, imagen: Bitmap): String {
		var intentos = 0
		val resultado = run {
			while (true) {
				try {
					return@run reconocedor.process(InputImage.fromBitmap(imagen, 0)).await()
				} catch (e: Exception) {
					// La primera vez Google Play está bajando el reconocedor: se espera un poco (hasta 2 minutos)
					val bajando = e.message.orEmpty().contains("download", ignoreCase = true)
					if (!bajando || ++intentos > 40) throw ErrorOcr(c.getString(R.string.ocr_error, e.message.orEmpty()))
					kotlinx.coroutines.delay(3000)
				}
			}
			@Suppress("UNREACHABLE_CODE")
			error("")
		}
		return resultado.textBlocks.joinToString("\n\n") { bloque ->
			// Las líneas de un bloque son un párrafo; las palabras partidas con guion se unen
			bloque.lines.joinToString(" ") { it.text.trim() }.replace(Regex("""(\p{L})- (\p{Ll})"""), "$1$2")
		}.trim()
	}

	suspend fun reconocer(c: Context, archivo: File): String {
		val mapa = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(archivo.path) } ?: throw ErrorOcr(c.getString(R.string.imagen_no_disponible))
		return reconocer(c, mapa)
	}

	/** Número de páginas de un PDF. */
	fun paginas(archivo: File): Int = ParcelFileDescriptor.open(archivo, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
		PdfRenderer(fd).use { it.pageCount }
	}

	/** Dibuja una página de un PDF (fondo blanco), con el lado mayor de unos 'lado' píxeles. */
	fun dibujarPagina(archivo: File, indice: Int, lado: Int = 2000): Bitmap =
		ParcelFileDescriptor.open(archivo, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
			PdfRenderer(fd).use { pdf ->
				pdf.openPage(indice.coerceIn(0, pdf.pageCount - 1)).use { pagina ->
					val escala = lado.toFloat() / maxOf(pagina.width, pagina.height)
					val mapa = Bitmap.createBitmap((pagina.width * escala).toInt().coerceAtLeast(1), (pagina.height * escala).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
					Canvas(mapa).drawColor(Color.WHITE)
					pagina.render(mapa, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
					mapa
				}
			}
		}

	/** Página de un PDF como JPEG en la caché (para describirla o enviarla). */
	fun paginaComoImagen(c: Context, archivo: File, indice: Int): File {
		val mapa = dibujarPagina(archivo, indice, 2000)
		val carpeta = File(c.cacheDir, "imagenes").apply { mkdirs() }
		carpeta.listFiles()?.forEach { it.delete() }
		val destino = File(carpeta, "pagina_${indice + 1}.jpg")
		destino.outputStream().use { mapa.compress(Bitmap.CompressFormat.JPEG, 88, it) }
		return destino
	}

	/** Texto de todas las páginas de un PDF. progreso(página, total). */
	suspend fun reconocerPdf(c: Context, archivo: File, progreso: (Int, Int) -> Unit): List<String> {
		val total = withContext(Dispatchers.IO) { paginas(archivo) }
		val textos = ArrayList<String>(total)
		for (i in 0 until total) {
			coroutineContext.ensureActive()
			progreso(i + 1, total)
			val mapa = withContext(Dispatchers.IO) { dibujarPagina(archivo, i, 2200) }
			textos += reconocer(c, mapa)
			mapa.recycle()
		}
		return textos
	}

	/** Guarda el texto (una entrada por página) como documento de la biblioteca y devuelve su id. */
	fun guardar(c: Context, titulo: String, paginas: List<String>, carpeta: Long?): Long {
		val html = buildString {
			append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>")
			append(escapar("$titulo (OCR)"))
			append("</title></head><body>\n<h1>").append(escapar(titulo)).append("</h1>\n")
			for ((i, texto) in paginas.withIndex()) {
				val n = i + 1
				append("<span epub:type=\"pagebreak\" title=\"$n\" id=\"pagina$n\"></span>\n")
				if (paginas.size > 1) append("<h2>").append(c.getString(R.string.pagina_n, n.toString())).append("</h2>\n")
				val parrafos = texto.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
				if (parrafos.isEmpty()) append("<p>").append(escapar(c.getString(R.string.ocr_pagina_vacia))).append("</p>\n")
				for (p in parrafos) append("<p>").append(escapar(p)).append("</p>\n")
			}
			append("</body></html>")
		}
		val dir = File(c.filesDir, "biblioteca/ocr").apply { mkdirs() }
		val nombre = titulo.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + " (OCR) " + System.currentTimeMillis() + ".html"
		val destino = File(dir, nombre)
		destino.writeText(html)
		return BaseDatos.obtener(c).anadirLibro(
			Uri.fromFile(destino).toString(), destino.name, "$titulo (OCR)", "", Formato.HTML.name, false, destino.length(), carpeta,
		)
	}

	private fun escapar(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
