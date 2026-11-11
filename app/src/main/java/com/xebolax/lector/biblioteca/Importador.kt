package com.xebolax.lector.biblioteca

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.formatos.ContextoLectura
import com.xebolax.lector.formatos.Formato
import com.xebolax.lector.formatos.Fuente
import com.xebolax.lector.formatos.Lectores
import com.xebolax.lector.modelo.Contenido
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Añade libros a la biblioteca:
 * - archivos elegidos con el selector del sistema (se guarda el permiso para
 *   volver a abrirlos);
 * - carpetas completas: se buscan los libros dentro, los libros DAISY y los
 *   audiolibros en carpeta se añaden como un solo libro, y las subcarpetas
 *   pueden convertirse en carpetas de la biblioteca;
 * - archivos que llegan de otras apps ("Abrir con"): si no se puede guardar
 *   el permiso, se copian a la memoria de la app.
 */
object Importador {

	private const val ETIQUETA = "Importador"

	class Resultado(val anadidos: List<Long>, val yaEstaban: Int)

	/** Guarda el permiso de lectura para siempre (solo con direcciones del selector de documentos). */
	fun guardarPermiso(contexto: Context, uri: Uri): Boolean = runCatching {
		contexto.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
		true
	}.getOrDefault(uri.scheme == "file")

	private fun esLibro(nombre: String): Boolean {
		val ext = Fuente.extensionDe(nombre)
		if (ext.isEmpty()) return false
		val f = Formato.deExtension(ext)
		// .xml/.json/.ini sueltos no son libros; .opf solo como parte de un DAISY
		return f != Formato.DESCONOCIDO && ext !in setOf("xml", "json", "ini", "opf", "ncc", "log", "srt", "vtt", "cue", "nfo")
	}

	/** Añade archivos sueltos (ya con permiso guardado o copiados). */
	suspend fun anadirArchivos(contexto: Context, uris: List<Uri>, carpeta: Long?): Resultado = withContext(Dispatchers.IO) {
		val db = BaseDatos.obtener(contexto)
		val anadidos = ArrayList<Long>()
		var yaEstaban = 0
		for (uri in uris) {
			guardarPermiso(contexto, uri)
			val existe = db.libroPorUri(uri.toString())
			if (existe != null) {
				// Estaba en Compartidos: pasa a la biblioteca (a esta carpeta)
				if (existe.compartido) {
					db.moverABiblioteca(existe.id, existe.tipo, carpeta)
					anadidos += existe.id
				} else yaEstaban++
				continue
			}
			anadidos += anadir(contexto, uri, null, carpeta)
		}
		Resultado(anadidos, yaEstaban)
	}

	private fun anadir(contexto: Context, uri: Uri, nombre: String?, carpeta: Long?, formatoForzado: Formato? = null): Long {
		val fuente = Fuente(contexto, uri, nombre)
		val formato = formatoForzado ?: Formato.deExtension(fuente.extension).takeIf { it != Formato.DESCONOCIDO } ?: Formato.TXT
		return BaseDatos.obtener(contexto).anadirLibro(
			uri = uri.toString(),
			nombre = fuente.nombre,
			titulo = fuente.nombre.substringBeforeLast('.').replace('_', ' ').trim(),
			autor = "",
			formato = formato.name,
			audio = formato.audio,
			tamano = fuente.tamano.coerceAtLeast(0),
			carpeta = carpeta,
		)
	}

	/**
	 * Archivo que llega de otra app. Devuelve el id del libro (nuevo o el que
	 * ya existía con esa dirección).
	 */
	suspend fun anadirExterno(contexto: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
		if (esInterno(contexto, uri)) throw SecurityException(contexto.getString(com.xebolax.lector.R.string.error_archivo_interno))
		val db = BaseDatos.obtener(contexto)
		db.libroPorUri(uri.toString())?.let { return@withContext it.id }
		if (guardarPermiso(contexto, uri)) return@withContext anadir(contexto, uri, null, null)
		// Sin permiso permanente: copia en la memoria de la app
		val nombre = Fuente.nombreDe(contexto, uri).ifBlank { "documento" }
		val carpeta = File(contexto.filesDir, "biblioteca").apply { mkdirs() }
		var destino = File(carpeta, nombre.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
		val tamano = Fuente.tamanoDe(contexto, uri)
		if (destino.exists() && destino.length() != tamano) {
			destino = File(carpeta, "${System.currentTimeMillis()}_${destino.name}")
		}
		if (!destino.exists()) {
			contexto.contentResolver.openInputStream(uri)?.use { e -> destino.outputStream().use { e.copyTo(it) } }
				?: throw IllegalStateException(contexto.getString(com.xebolax.lector.R.string.error_leer_archivo))
		}
		val local = Uri.fromFile(destino)
		db.libroPorUri(local.toString())?.let { return@withContext it.id }
		anadir(contexto, local, nombre, null)
	}

	/** ¿Es un archivo de dentro de la app (file:// en su carpeta de datos)? No se acepta desde otras apps. */
	fun esInterno(contexto: Context, uri: Uri): Boolean {
		if (uri.scheme != "file") return false
		val ruta = runCatching { File(uri.path ?: return false).canonicalPath }.getOrNull() ?: return true
		val datos = runCatching { contexto.dataDir.canonicalPath }.getOrDefault(contexto.filesDir.parent ?: "")
		return ruta.startsWith(datos)
	}

	/** Un documento de una carpeta elegida con el selector. */
	private class Doc(val id: String, val nombre: String, val mime: String, val tamano: Long) {
		val esCarpeta get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
	}

	private fun hijos(contexto: Context, arbol: Uri, docId: String): List<Doc> {
		val uri = DocumentsContract.buildChildDocumentsUriUsingTree(arbol, docId)
		val columnas = arrayOf(
			DocumentsContract.Document.COLUMN_DOCUMENT_ID,
			DocumentsContract.Document.COLUMN_DISPLAY_NAME,
			DocumentsContract.Document.COLUMN_MIME_TYPE,
			DocumentsContract.Document.COLUMN_SIZE,
		)
		return runCatching {
			contexto.contentResolver.query(uri, columnas, null, null, null)?.use { c ->
				val lista = ArrayList<Doc>(c.count)
				while (c.moveToNext()) {
					lista += Doc(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", if (c.isNull(3)) 0 else c.getLong(3))
				}
				lista
			}
		}.getOrNull().orEmpty()
	}

	/**
	 * Añade todos los libros de una carpeta del dispositivo (y sus subcarpetas).
	 * crearCarpetas: cada subcarpeta con libros pasa a ser una carpeta de la biblioteca.
	 */
	suspend fun anadirCarpeta(
		contexto: Context,
		arbol: Uri,
		carpeta: Long?,
		crearCarpetas: Boolean,
		progreso: (String) -> Unit = {},
	): Resultado = withContext(Dispatchers.IO) {
		guardarPermiso(contexto, arbol)
		val db = BaseDatos.obtener(contexto)
		val anadidos = ArrayList<Long>()
		var yaEstaban = 0

		fun carpetaBiblioteca(nombre: String, padre: Long?): Long =
			db.carpetas(padre).firstOrNull { it.nombre.equals(nombre, ignoreCase = true) }?.id ?: db.crearCarpeta(nombre, padre)

		val trabajo = coroutineContext[kotlinx.coroutines.Job]
		fun recorrer(docId: String, nombre: String, destino: Long?, profundidad: Int) {
			if (profundidad > 12) return
			// Cancelar (botón de la espera): se para de verdad
			if (trabajo?.isActive == false) throw kotlinx.coroutines.CancellationException()
			progreso(nombre)
			val lista = hijos(contexto, arbol, docId)
			val nombres = lista.map { it.nombre.lowercase() }
			val uriCarpeta = DocumentsContract.buildDocumentUriUsingTree(arbol, docId)
			// Libro DAISY en carpeta
			val esDaisy = "ncc.html" in nombres ||
				(nombres.any { it.endsWith(".opf") } && nombres.any { it.endsWith(".smil") || it.endsWith(".ncx") })
			val audios = lista.filter { !it.esCarpeta && Formato.deExtension(Fuente.extensionDe(it.nombre)) == Formato.AUDIO }
			val otros = lista.filter { !it.esCarpeta && esLibro(it.nombre) && Formato.deExtension(Fuente.extensionDe(it.nombre)) != Formato.AUDIO }
			if (esDaisy || (audios.size >= 2 && otros.isEmpty() && lista.none { it.esCarpeta && profundidad > 0 } && profundidad > 0)) {
				if (db.libroPorUri(uriCarpeta.toString()) != null) yaEstaban++
				else anadidos += db.anadirLibro(
					uri = uriCarpeta.toString(), nombre = nombre, titulo = nombre, autor = "",
					formato = if (esDaisy) Formato.DAISY.name else Formato.CARPETA.name,
					audio = true, tamano = audios.sumOf { it.tamano }, carpeta = destino,
				)
				return
			}
			for (doc in lista.filter { !it.esCarpeta && esLibro(it.nombre) }) {
				if (trabajo?.isActive == false) throw kotlinx.coroutines.CancellationException()
				val uri = DocumentsContract.buildDocumentUriUsingTree(arbol, doc.id)
				if (db.libroPorUri(uri.toString()) != null) {
					yaEstaban++
					continue
				}
				anadidos += db.anadirLibro(
					uri = uri.toString(), nombre = doc.nombre,
					titulo = doc.nombre.substringBeforeLast('.').replace('_', ' ').trim(), autor = "",
					formato = Formato.deExtension(Fuente.extensionDe(doc.nombre)).name,
					audio = Formato.deExtension(Fuente.extensionDe(doc.nombre)).audio,
					tamano = doc.tamano, carpeta = destino,
				)
			}
			for (sub in lista.filter { it.esCarpeta && !it.nombre.startsWith(".") }) {
				// Un libro DAISY o un audiolibro en carpeta es un libro, no una carpeta de la biblioteca
				val crear = crearCarpetas && !esLibroEnCarpeta(contexto, arbol, sub.id) && contieneLibros(contexto, arbol, sub.id, 0)
				val destinoSub = if (crear) carpetaBiblioteca(sub.nombre, destino) else destino
				recorrer(sub.id, sub.nombre, destinoSub, profundidad + 1)
			}
		}

		val raiz = DocumentsContract.getTreeDocumentId(arbol)
		recorrer(raiz, Fuente.nombreDe(contexto, arbol), carpeta, 0)
		Resultado(anadidos, yaEstaban)
	}

	/** ¿La carpeta es un libro DAISY o un audiolibro de varias pistas (sin otros libros ni subcarpetas)? */
	private fun esLibroEnCarpeta(contexto: Context, arbol: Uri, docId: String): Boolean {
		val lista = hijos(contexto, arbol, docId)
		val nombres = lista.map { it.nombre.lowercase() }
		if ("ncc.html" in nombres || (nombres.any { it.endsWith(".opf") } && nombres.any { it.endsWith(".smil") || it.endsWith(".ncx") })) return true
		val audios = lista.count { !it.esCarpeta && Formato.deExtension(Fuente.extensionDe(it.nombre)) == Formato.AUDIO }
		val otros = lista.count { !it.esCarpeta && esLibro(it.nombre) && Formato.deExtension(Fuente.extensionDe(it.nombre)) != Formato.AUDIO }
		return audios >= 2 && otros == 0 && lista.none { it.esCarpeta }
	}

	private fun contieneLibros(contexto: Context, arbol: Uri, docId: String, profundidad: Int): Boolean {
		if (profundidad > 4) return false
		val lista = hijos(contexto, arbol, docId)
		if (lista.any { !it.esCarpeta && (esLibro(it.nombre) || it.nombre.equals("ncc.html", true)) }) return true
		return lista.filter { it.esCarpeta }.any { contieneLibros(contexto, arbol, it.id, profundidad + 1) }
	}

	/** Revisa las carpetas vigiladas y añade los libros nuevos. Devuelve cuántos se añadieron. */
	suspend fun revisarVigiladas(contexto: Context): Int {
		var total = 0
		for (texto in Preferencias(contexto).carpetasVigiladas) {
			val arbol = Uri.parse(texto)
			total += try {
				anadirCarpeta(contexto, arbol, null, crearCarpetas = true).anadidos.size
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(ETIQUETA, "No se pudo revisar $texto", e)
				0
			}
		}
		return total
	}

	/**
	 * Abre los libros recién añadidos en segundo plano para leer su título y
	 * autor reales (y dejarlos convertidos en la caché).
	 */
	suspend fun completarDatos(contexto: Context, ids: List<Long>) = withContext(Dispatchers.IO) {
		val db = BaseDatos.obtener(contexto)
		val opciones = Preferencias(contexto).opcionesLectura
		for (id in ids) {
			val libro = db.libro(id) ?: continue
			// Los PDF muy grandes se convierten al abrirlos (tardan)
			if (libro.formato == Formato.PDF.name && libro.tamano > 30L * 1024 * 1024) continue
			runCatching {
				val fuente = Fuente(contexto, Uri.parse(libro.uri), libro.nombre)
				val contenido = Lectores.abrir(fuente, ContextoLectura(contexto, opciones))
				val formato = Formato.reconocer(fuente).takeIf { it != Formato.CARPETA && it != Formato.ZIP }
					?: runCatching { Formato.valueOf(libro.formato) }.getOrDefault(Formato.CARPETA)
				val idioma = (contenido as? Contenido.Texto)?.documento?.idioma.orEmpty()
				db.actualizarDatos(id, contenido.titulo, contenido.autor, idioma, formato.name, contenido is Contenido.Audio)
				(contenido as? Contenido.Texto)?.documento?.metadatos?.get("Serie")?.let { db.ponerSerieDeTexto(id, it) }
			}.onFailure { Log.w(ETIQUETA, "No se pudo leer ${libro.nombre}", it) }
		}
	}
}
