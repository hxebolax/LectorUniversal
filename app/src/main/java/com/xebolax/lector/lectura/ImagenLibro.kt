package com.xebolax.lector.lectura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.xebolax.lector.formatos.Fuente
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Saca una imagen del libro (de dentro del EPUB o la carpeta, de la web o
 * de un "data:") y la deja como JPEG en la caché, lista para enviarla a
 * otra app que la describa (Be My Eyes, Gemini, Lookout...) o para verla.
 */
object ImagenLibro {

	/** Lado mayor de la imagen enviada: suficiente para describirla y no pesa. */
	private const val LADO_MAXIMO = 2048

	/** Prepara la imagen (en segundo plano). null si no se puede leer o no es una imagen. */
	fun preparar(contexto: Context, libro: Uri, nombreLibro: String, recurso: String): File? {
		val bytes = runCatching { leer(contexto, libro, nombreLibro, recurso) }.getOrNull() ?: return null
		val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeByteArray(bytes, 0, bytes.size, limites)
		if (limites.outWidth <= 0 || limites.outHeight <= 0) return null
		var muestra = 1
		while (maxOf(limites.outWidth, limites.outHeight) / muestra > LADO_MAXIMO * 2) muestra *= 2
		val mapa = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = muestra })
			?: return null
		val escala = LADO_MAXIMO.toFloat() / maxOf(mapa.width, mapa.height)
		val final = if (escala < 1f) Bitmap.createScaledBitmap(mapa, (mapa.width * escala).toInt(), (mapa.height * escala).toInt(), true) else mapa
		// Fondo blanco: las imágenes con transparencia (PNG) se ven negras en JPEG
		val conFondo = Bitmap.createBitmap(final.width, final.height, Bitmap.Config.ARGB_8888)
		android.graphics.Canvas(conFondo).apply {
			drawColor(android.graphics.Color.WHITE)
			drawBitmap(final, 0f, 0f, null)
		}
		val carpeta = File(contexto.cacheDir, "imagenes").apply { mkdirs() }
		carpeta.listFiles()?.forEach { it.delete() }
		val nombre = recurso.substringAfterLast('/').substringBefore('?').substringBeforeLast('.')
			.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40).ifBlank { "imagen" }
		val archivo = File(carpeta, "$nombre.jpg")
		archivo.outputStream().use { conFondo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
		return archivo
	}

	private fun leer(contexto: Context, libro: Uri, nombreLibro: String, recurso: String): ByteArray? = when {
		recurso.startsWith("data:") -> {
			val datos = recurso.substringAfter(',', "")
			if (recurso.substringBefore(',').endsWith(";base64")) Base64.decode(datos, Base64.DEFAULT)
			else Uri.decode(datos).toByteArray(Charsets.ISO_8859_1)
		}
		recurso.startsWith("http://") || recurso.startsWith("https://") -> {
			val conexion = URL(recurso).openConnection() as HttpURLConnection
			conexion.connectTimeout = 15_000
			conexion.readTimeout = 20_000
			conexion.setRequestProperty("User-Agent", "LectorUniversal")
			try {
				conexion.inputStream.use { it.readBytes() }
			} finally {
				conexion.disconnect()
			}
		}
		else -> {
			val fuente = Fuente(contexto, libro, nombreLibro)
			val cabecera = if (fuente.esCarpeta) ByteArray(0) else runCatching { fuente.cabecera(4) }.getOrDefault(ByteArray(0))
			val esZip = cabecera.size >= 2 && cabecera[0] == 'P'.code.toByte() && cabecera[1] == 'K'.code.toByte()
			when {
				recurso.startsWith(com.xebolax.lector.formatos.ConversorHtml.PREFIJO_MOBI) -> com.xebolax.lector.formatos.LectorMobi.imagen(fuente.archivoLocal(), recurso)
				fuente.extension == "chm" -> com.xebolax.lector.formatos.leerDeChm(fuente.archivoLocal(), recurso)
				fuente.esCarpeta || esZip -> fuente.contenedor().use { it.bytes(recurso) }
				// Un archivo suelto (HTML, Markdown): la imagen está a su lado
				else -> junto(libro, recurso)
			}
		}
	}

	/** Una imagen junto a un documento suelto (solo si es un archivo del móvil y sin salir de su carpeta). */
	private fun junto(libro: Uri, recurso: String): ByteArray? {
		if (libro.scheme != "file") return null
		val carpeta = File(libro.path ?: return null).parentFile?.canonicalFile ?: return null
		val imagen = File(carpeta, recurso).canonicalFile
		if (!imagen.path.startsWith(carpeta.path + File.separator) || !imagen.isFile || imagen.length() > 64L * 1024 * 1024) return null
		return imagen.readBytes()
	}
}
