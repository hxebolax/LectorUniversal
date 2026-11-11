package com.xebolax.lector.biblioteca

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.xebolax.lector.formatos.Formato
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Busca libros en todo el dispositivo (memoria interna y tarjetas), estén
 * donde estén, como el «Buscar» de otros lectores. Es opcional: solo se usa
 * si el usuario lo pide, y entonces Android pide el permiso de acceso a
 * todos los archivos (en Android 10 y anteriores, el de leer la memoria).
 * No se mira dentro de las carpetas privadas de las apps (Android/data y
 * Android/obb) ni en las ocultas.
 */
object BuscadorDispositivo {

	/** Qué buscar: solo EPUB y PDF, todos los libros y documentos, o también audiolibros y audio. */
	enum class Alcance(val nombre: Int, val extensiones: Set<String>) {
		EPUB_PDF(com.xebolax.lector.R.string.disp_epub_pdf, setOf("epub", "kepub", "epub3", "pdf")),
		LIBROS(com.xebolax.lector.R.string.disp_libros_documentos, setOf(
			"epub", "kepub", "epub3", "pdf", "docx", "doc", "odt", "rtf", "txt", "fb2", "fbz", "mobi", "azw", "azw3", "prc", "pdb",
			"pptx", "odp", "xlsx", "ods", "chm", "cbz", "md",
		)),
		TODO(com.xebolax.lector.R.string.disp_todo, LIBROS.extensiones + setOf("m4b", "m4a", "mp3", "aac", "ogg", "opus", "flac", "wma")),
	}

	/** tamano: se mide al buscar (así la lista no mira el disco al dibujar cada fila). */
	class Encontrado(val archivo: File, val formato: Formato, val tamano: Long = 0) {
		val nombre: String get() = archivo.name
	}

	/** ¿Tiene la app permiso para buscar en todo el dispositivo? */
	fun permitido(c: Context): Boolean =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
		else ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

	/** Las raíces donde buscar: la memoria interna compartida y las tarjetas SD. */
	fun raices(c: Context): List<File> {
		val r = mutableListOf<File>()
		@Suppress("DEPRECATION")
		r += Environment.getExternalStorageDirectory()
		// Las tarjetas: la parte de antes de «/Android/» de las carpetas de la app en cada una
		c.getExternalFilesDirs(null).filterNotNull().forEach { d -> d.path.substringBefore("/Android/").takeIf { it.isNotBlank() }?.let { r += File(it) } }
		return r.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }.filter { it.isDirectory && it.canRead() }
	}

	/** Busca los archivos con esas extensiones. alAvanzar: cuántos lleva (se llama de vez en cuando). */
	suspend fun buscar(c: Context, alcance: Alcance, alAvanzar: (Int) -> Unit = {}): List<Encontrado> = withContext(Dispatchers.IO) {
		val r = ArrayList<Encontrado>()
		val pila = ArrayDeque<Pair<File, Int>>()
		raices(c).forEach { pila.addLast(it to 0) }
		val vistos = HashSet<String>()
		var ultimoAviso = 0
		while (pila.isNotEmpty()) {
			ensureActive()
			val (carpeta, nivel) = pila.removeLast()
			val clave = runCatching { carpeta.canonicalPath }.getOrDefault(carpeta.path)
			if (!vistos.add(clave)) continue
			val hijos = carpeta.listFiles() ?: continue
			for (h in hijos) {
				if (h.name.startsWith(".")) continue
				if (h.isDirectory) {
					// Las carpetas privadas de las apps no son del usuario
					val ruta = h.path.replace('\\', '/')
					if (nivel < 20 && !ruta.endsWith("/Android/data") && !ruta.endsWith("/Android/obb")) pila.addLast(h to nivel + 1)
				} else {
					val ext = h.name.substringAfterLast('.', "").lowercase()
					if (ext in alcance.extensiones) {
						val tamano = h.length()
						if (tamano > 0) r += Encontrado(h, Formato.deExtension(ext), tamano)
					}
				}
			}
			if (r.size - ultimoAviso >= 25) {
				ultimoAviso = r.size
				alAvanzar(r.size)
			}
		}
		r.sortedWith(compareBy(java.text.Collator.getInstance()) { it.nombre })
	}
}
