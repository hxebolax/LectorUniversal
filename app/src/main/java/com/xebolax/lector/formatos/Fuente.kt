package com.xebolax.lector.formatos

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.zip.ZipFile

/** No se puede leer el archivo: el mensaje se muestra tal cual al usuario. */
open class ExcepcionFormato(mensaje: String, causa: Throwable? = null) : Exception(mensaje, causa)

/** El documento está protegido con contraseña: hay que pedirla y volver a abrir. */
class ExcepcionClave(val incorrecta: Boolean) : ExcepcionFormato(if (incorrecta) "Contraseña incorrecta" else "Contraseña necesaria")

/** Libro con DRM: no se puede leer (y se dice claramente). */
class ExcepcionDrm(mensaje: String) : ExcepcionFormato(mensaje)

/** Opciones que cambian el resultado de la conversión (forman parte de la clave de la caché). */
data class OpcionesLectura(
	val clave: String? = null,
	/** Tablas como "Encabezado: valor; ..." en lugar de celdas separadas por barras. */
	val tablasConEncabezados: Boolean = true,
	/** PDF: une las líneas en párrafos (si no, una línea por bloque). */
	val pdfUnirLineas: Boolean = true,
	/** TXT: cada línea es un párrafo (si no, se unen las líneas de un mismo párrafo). */
	val txtLineaAParrafo: Boolean = false,
	/** Párrafos más largos que esto (caracteres) se dividen por frases; 0 = no dividir. */
	val dividirParrafos: Int = 0,
	/** Escribir "[Imagen: ...]" para las imágenes con descripción. */
	val leerImagenes: Boolean = true,
	/** Libros en braille: pasarlos a texto (si no, se muestran sus puntos tal cual). */
	val brailleComoTexto: Boolean = true,
	/** EPUB 3 con narración sincronizada: abrirlo con su audio (si no, solo el texto). */
	val epubConAudio: Boolean = true,
) {
	fun firma() = "t$tablasConEncabezados p$pdfUnirLineas l$txtLineaAParrafo d$dividirParrafos i$leerImagenes b$brailleComoTexto e$epubConAudio"
}

class ContextoLectura(
	val app: Context,
	val opciones: OpcionesLectura = OpcionesLectura(),
	/** Progreso de 0 a 100 (puede no llamarse nunca). */
	val progreso: (Int) -> Unit = {},
	val cancelado: () -> Boolean = { false },
) {
	fun comprobar() {
		if (cancelado()) throw InterruptedException()
	}
}

/**
 * Archivo o carpeta que se abre: content:// (selector del sistema), file://
 * (archivos de la app) o una carpeta del árbol de documentos (DAISY y
 * audiolibros en carpeta).
 */
class Fuente(val contexto: Context, val uri: Uri, nombre: String? = null) {

	/**
	 * Los archivos de una carpeta elegida con el selector también tienen
	 * dirección "de árbol" (tree/.../document/...): solo es carpeta si su
	 * tipo es el de directorio o si es la raíz del árbol.
	 */
	val esCarpeta: Boolean = when {
		uri.scheme == "file" -> File(uri.path.orEmpty()).isDirectory
		runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false) -> esDirectorio(contexto, uri)
		else -> false
	}

	val nombre: String = nombre ?: nombreDe(contexto, uri)

	val extension: String get() = extensionDe(nombre)

	val tamano: Long by lazy { tamanoDe(contexto, uri) }

	fun abrir(): InputStream = when (uri.scheme) {
		"file" -> File(uri.path!!).inputStream()
		else -> contexto.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
	}

	/** Todos los bytes (para formatos pequeños). */
	fun bytes(maximo: Long = 512L * 1024 * 1024): ByteArray {
		if (tamano > maximo) throw ExcepcionFormato("El archivo es demasiado grande (${tamano / 1024 / 1024} MB).")
		return abrir().use { it.readBytes() }
	}

	/** Los primeros bytes (para reconocer el formato). */
	fun cabecera(n: Int = 4096): ByteArray = abrir().use { entrada ->
		val bufer = ByteArray(n)
		var leidos = 0
		while (leidos < n) {
			val r = entrada.read(bufer, leidos, n - leidos)
			if (r < 0) break
			leidos += r
		}
		bufer.copyOf(leidos)
	}

	/**
	 * Archivo local con acceso aleatorio (ZIP, PDF, MOBI, MP4). Si la fuente
	 * es content://, se copia una vez a la caché de la app.
	 */
	fun archivoLocal(): File {
		if (uri.scheme == "file") return File(uri.path!!)
		val carpeta = File(contexto.cacheDir, "fuentes").apply { mkdirs() }
		val destino = File(carpeta, hash(uri.toString() + ":" + tamano) + "." + extension.ifEmpty { "bin" })
		if (destino.exists() && destino.length() == tamano && tamano > 0) return destino
		val parcial = File(carpeta, destino.name + ".parte")
		abrir().use { entrada -> parcial.outputStream().use { entrada.copyTo(it, 256 * 1024) } }
		parcial.renameTo(destino)
		limpiarCache(carpeta)
		return destino
	}

	/** Contenedor de varios archivos: ZIP o carpeta. */
	fun contenedor(): Contenedor = when {
		esCarpeta && uri.scheme == "file" -> ContenedorDirectorio(File(uri.path!!))
		esCarpeta -> ContenedorCarpeta(contexto, uri)
		else -> ContenedorZip(archivoLocal())
	}

	companion object {
		/** ¿Una dirección de árbol de documentos es una carpeta? */
		fun esDirectorio(contexto: Context, uri: Uri): Boolean {
			val esDocumento = runCatching { DocumentsContract.isDocumentUri(contexto, uri) }.getOrDefault(false)
			if (!esDocumento) return true
			val tipo = runCatching {
				contexto.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
					if (c.moveToFirst()) c.getString(0) else null
				}
			}.getOrNull()
			return tipo == DocumentsContract.Document.MIME_TYPE_DIR
		}

		fun nombreDe(contexto: Context, uri: Uri): String {
			if (uri.scheme == "file") return File(uri.path.orEmpty()).name
			if (runCatching { DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(contexto, uri) }.getOrDefault(false)) {
				return DocumentFile.fromTreeUri(contexto, uri)?.name ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
			}
			runCatching {
				contexto.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
					if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
				}
			}
			return Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast('/').substringAfterLast(':')
		}

		fun tamanoDe(contexto: Context, uri: Uri): Long {
			if (uri.scheme == "file") return File(uri.path.orEmpty()).length()
			return runCatching {
				contexto.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
					if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
				} ?: -1L
			}.getOrDefault(-1L)
		}

		fun extensionDe(nombre: String): String {
			val n = nombre.lowercase()
			// Extensiones dobles
			for (doble in listOf(".fb2.zip", ".fbz")) if (n.endsWith(doble)) return "fb2.zip"
			return n.substringAfterLast('.', "")
		}

		fun hash(texto: String): String =
			MessageDigest.getInstance("SHA-1").digest(texto.toByteArray()).joinToString("") { "%02x".format(it) }

		/** Deja la caché de copias por debajo de 1 GB borrando las más antiguas. */
		private fun limpiarCache(carpeta: File) {
			val archivos = carpeta.listFiles()?.sortedBy { it.lastModified() } ?: return
			var total = archivos.sumOf { it.length() }
			for (a in archivos) {
				if (total < 1024L * 1024 * 1024) break
				total -= a.length()
				a.delete()
			}
		}
	}
}

/**
 * Conjunto de archivos de un libro (EPUB, DOCX, DAISY...). Las rutas son
 * relativas, con "/", y se buscan sin distinguir mayúsculas (los DAISY
 * suelen mezclarlas).
 */
interface Contenedor : Closeable {
	fun entradas(): List<String>
	fun abrir(ruta: String): InputStream?
	fun bytes(ruta: String): ByteArray? = abrir(ruta)?.use { it.readBytes() }
	fun texto(ruta: String): String? = bytes(ruta)?.let { com.xebolax.lector.modelo.Textos.decodificar(it) }
	fun existe(ruta: String): Boolean = buscar(ruta) != null
	/** Ruta real de una entrada buscando sin mayúsculas. */
	fun buscar(ruta: String): String?
	/** Dirección reproducible (para el audio); null si hay que extraerlo antes. */
	fun uriReproducible(ruta: String): String?
	override fun close() {}

	companion object {
		/** Ruta de un enlace relativo a un archivo: resolver("OEBPS/text/c1.xhtml", "../img/a.png") = "OEBPS/img/a.png". */
		fun resolver(base: String, href: String): String {
			val sinFragmento = href.substringBefore('#')
			if (sinFragmento.isEmpty()) return base
			val decodificado = runCatching { URLDecoder.decode(sinFragmento.replace("+", "%2B"), "UTF-8") }.getOrDefault(sinFragmento)
			if (decodificado.startsWith("/")) return normalizar(decodificado.trimStart('/'))
			val carpeta = base.substringBeforeLast('/', "")
			return normalizar(if (carpeta.isEmpty()) decodificado else "$carpeta/$decodificado")
		}

		fun normalizar(ruta: String): String {
			val partes = ArrayList<String>()
			for (p in ruta.replace('\\', '/').split('/')) {
				when (p) {
					"", "." -> Unit
					".." -> if (partes.isNotEmpty()) partes.removeAt(partes.size - 1)
					else -> partes += p
				}
			}
			return partes.joinToString("/")
		}
	}
}

class ContenedorZip(archivo: File) : Contenedor {
	private val zip = try {
		ZipFile(archivo)
	} catch (e: Exception) {
		throw ExcepcionFormato("El archivo está dañado o no es un ZIP válido.", e)
	}
	private val porNombre: Map<String, String> by lazy {
		zip.entries().asSequence().filter { !it.isDirectory }.associate { it.name.lowercase().replace('\\', '/') to it.name }
	}

	override fun entradas() = porNombre.values.toList()
	override fun buscar(ruta: String) = porNombre[Contenedor.normalizar(ruta).lowercase()]
	override fun abrir(ruta: String): InputStream? = buscar(ruta)?.let { zip.getInputStream(zip.getEntry(it)) }
	override fun uriReproducible(ruta: String): String? = null
	override fun close() = zip.close()

	/** Extrae todo a una carpeta (DAISY en ZIP: el audio tiene que estar en archivos sueltos). */
	fun extraer(destino: File) {
		destino.mkdirs()
		val raiz = destino.canonicalPath
		for (e in zip.entries()) {
			if (e.isDirectory) continue
			val salida = File(destino, e.name.replace('\\', '/'))
			// Nunca fuera de la carpeta (rutas con ".." en ZIP malintencionados)
			if (!salida.canonicalPath.startsWith(raiz + File.separator)) continue
			salida.parentFile?.mkdirs()
			zip.getInputStream(e).use { entrada -> salida.outputStream().use { entrada.copyTo(it) } }
		}
	}
}

class ContenedorDirectorio(private val raiz: File) : Contenedor {
	private val porNombre: Map<String, String> by lazy {
		raiz.walkTopDown().filter { it.isFile }.associate {
			val relativa = it.relativeTo(raiz).path.replace('\\', '/')
			relativa.lowercase() to relativa
		}
	}

	override fun entradas() = porNombre.values.toList()
	override fun buscar(ruta: String) = porNombre[Contenedor.normalizar(ruta).lowercase()]
	override fun abrir(ruta: String): InputStream? = buscar(ruta)?.let { File(raiz, it).inputStream() }
	override fun uriReproducible(ruta: String): String? = buscar(ruta)?.let { Uri.fromFile(File(raiz, it)).toString() }
}

/** Carpeta elegida con el selector del sistema (árbol de documentos). */
class ContenedorCarpeta(private val contexto: Context, arbol: Uri) : Contenedor {
	private val raiz = DocumentFile.fromTreeUri(contexto, arbol) ?: throw ExcepcionFormato("No se puede abrir la carpeta.")
	private val archivos: Map<String, Pair<String, Uri>> by lazy {
		val mapa = HashMap<String, Pair<String, Uri>>()
		fun recorrer(carpeta: DocumentFile, prefijo: String, profundidad: Int) {
			if (profundidad > 6) return
			for (hijo in carpeta.listFiles()) {
				val nombre = hijo.name ?: continue
				val ruta = if (prefijo.isEmpty()) nombre else "$prefijo/$nombre"
				if (hijo.isDirectory) recorrer(hijo, ruta, profundidad + 1)
				else mapa[ruta.lowercase()] = ruta to hijo.uri
			}
		}
		recorrer(raiz, "", 0)
		mapa
	}

	override fun entradas() = archivos.values.map { it.first }
	override fun buscar(ruta: String) = archivos[Contenedor.normalizar(ruta).lowercase()]?.first
	override fun abrir(ruta: String): InputStream? =
		archivos[Contenedor.normalizar(ruta).lowercase()]?.second?.let { contexto.contentResolver.openInputStream(it) }
	override fun uriReproducible(ruta: String): String? = archivos[Contenedor.normalizar(ruta).lowercase()]?.second?.toString()
}
