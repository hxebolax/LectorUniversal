package com.xebolax.lector.formatos

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import com.xebolax.lector.R
import com.xebolax.lector.datos.textoUsuario
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.zip.ZipFile

/** No se puede leer el archivo: el mensaje se muestra tal cual al usuario (en el idioma de la app). */
open class ExcepcionFormato(mensaje: String, causa: Throwable? = null) : Exception(mensaje, causa) {

	/** Con un texto de los recursos (R.string), en el idioma de la app. */
	constructor(@StringRes recurso: Int, causa: Throwable? = null) : this(textoUsuario(recurso, emptyArray<Any>()), causa)

	companion object {
		/** Un texto de los recursos con sus datos, en el idioma de la app (como LectorApp.texto, también en las pruebas). */
		fun texto(@StringRes recurso: Int, vararg datos: Any): String = textoUsuario(recurso, datos)

		/** «Este archivo no es válido (formato: …)»: no es de ese formato o está dañado. */
		fun noValido(formato: Formato, causa: Throwable? = null) =
			ExcepcionFormato(texto(R.string.fmt_no_valido, texto(formato.textoNombre)), causa)
	}
}

/** El documento está protegido con contraseña: hay que pedirla y volver a abrir. */
class ExcepcionClave(val incorrecta: Boolean) : ExcepcionFormato(if (incorrecta) R.string.fmt_clave_incorrecta else R.string.fmt_clave_necesaria)

/** Libro con DRM: no se puede leer (y se dice claramente). */
class ExcepcionDrm : ExcepcionFormato(R.string.fmt_drm)

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
		if (tamano > maximo) throw ExcepcionFormato(ExcepcionFormato.texto(R.string.fmt_demasiado_grande, tamano / 1024 / 1024))
		// Con el límite también al leer: hay sitios que no dicen el tamaño (-1)
		return abrir().use { leerConLimite(it, maximo) ?: throw ExcepcionFormato(ExcepcionFormato.texto(R.string.fmt_demasiado_grande_mas, maximo / 1024 / 1024)) }
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
		val parcial = File(carpeta, destino.name + "." + Thread.currentThread().id + ".parte")
		try {
			abrir().use { entrada -> parcial.outputStream().use { entrada.copyTo(it, 256 * 1024) } }
			if (!parcial.renameTo(destino) && !(destino.exists() && destino.length() == tamano)) throw java.io.IOException("No se pudo copiar el archivo")
		} finally {
			parcial.delete()
		}
		limpiarCache(carpeta, destino)
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

		/** Todo lo que queda en [entrada] si no pasa de [maximo] bytes; null si pasa (sin cargarlo entero en memoria). */
		fun leerConLimite(entrada: InputStream, maximo: Long): ByteArray? {
			val salida = java.io.ByteArrayOutputStream()
			val bufer = ByteArray(64 * 1024)
			var total = 0L
			while (true) {
				val n = entrada.read(bufer)
				if (n < 0) break
				total += n
				if (total > maximo) return null
				salida.write(bufer, 0, n)
			}
			return salida.toByteArray()
		}

		fun hash(texto: String): String =
			MessageDigest.getInstance("SHA-1").digest(texto.toByteArray()).joinToString("") { "%02x".format(it) }

		/** Deja la caché de copias por debajo de 1 GB borrando las más antiguas. */
		private fun limpiarCache(carpeta: File, guardar: File? = null) {
			val archivos = carpeta.listFiles()?.sortedBy { it.lastModified() } ?: return
			var total = archivos.sumOf { it.length() }
			for (a in archivos) {
				if (total < 1024L * 1024 * 1024) break
				// La que se acaba de copiar (y se va a abrir) nunca
				if (a == guardar) continue
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
		throw ExcepcionFormato.noValido(Formato.ZIP, e)
	}
	private val porNombre: Map<String, String> by lazy {
		zip.entries().asSequence().filter { !it.isDirectory }.associate { it.name.lowercase().replace('\\', '/') to it.name }
	}

	override fun entradas() = porNombre.values.toList()
	override fun buscar(ruta: String) = porNombre[Contenedor.normalizar(ruta).lowercase()]
	override fun abrir(ruta: String): InputStream? = buscar(ruta)?.let { zip.getInputStream(zip.getEntry(it)) }
	override fun uriReproducible(ruta: String): String? = null
	override fun close() = zip.close()

	/** Tamaño descomprimido que declara una entrada (-1 si no lo dice o no existe). */
	fun tamano(ruta: String): Long = buscar(ruta)?.let { zip.getEntry(it)?.size } ?: -1L

	/**
	 * Una entrada entera en memoria, con límite: un ZIP manipulado («bomba»)
	 * puede decir que ocupa poco y descomprimir gigas.
	 */
	override fun bytes(ruta: String): ByteArray? {
		val nombre = buscar(ruta) ?: return null
		val entrada = zip.getEntry(nombre) ?: return null
		if (entrada.size > MAXIMO_ENTRADA) throw ExcepcionFormato(ExcepcionFormato.texto(R.string.fmt_parte_grande, nombre))
		return zip.getInputStream(entrada).use { Fuente.leerConLimite(it, MAXIMO_ENTRADA) }
			?: throw ExcepcionFormato(ExcepcionFormato.texto(R.string.fmt_parte_grande, nombre))
	}

	/** Extrae todo a una carpeta (DAISY en ZIP: el audio tiene que estar en archivos sueltos). */
	fun extraer(destino: File) {
		destino.mkdirs()
		val raiz = destino.canonicalPath
		// Hace falta el tamaño que declaran las entradas (más un margen pequeño), y no se escribe mucho más de eso:
		// un ZIP manipulado que dice ocupar poco y descomprime gigas se corta a tiempo
		val declarado = zip.entries().asSequence().filter { !it.isDirectory }.sumOf { it.size.coerceAtLeast(0L) }
		val margen = 16L * 1024 * 1024
		if (declarado + margen > destino.usableSpace) comprobarSitio(-1)
		var queda = declarado + maxOf(declarado / 10, margen)
		val bufer = ByteArray(256 * 1024)
		for (e in zip.entries()) {
			if (e.isDirectory) continue
			val salida = File(destino, e.name.replace('\\', '/'))
			// Nunca fuera de la carpeta (rutas con ".." en ZIP malintencionados)
			if (!salida.canonicalPath.startsWith(raiz + File.separator)) continue
			salida.parentFile?.mkdirs()
			zip.getInputStream(e).use { entrada ->
				salida.outputStream().use { s ->
					while (true) {
						val n = entrada.read(bufer)
						if (n < 0) break
						queda -= n
						comprobarSitio(queda)
						s.write(bufer, 0, n)
					}
				}
			}
		}
	}

	private fun comprobarSitio(queda: Long) {
		if (queda < 0) throw ExcepcionFormato(R.string.fmt_sin_espacio)
	}

	companion object {
		/** Lo más grande que se lee de una vez en memoria de dentro de un ZIP (un capítulo, una imagen). */
		const val MAXIMO_ENTRADA = 256L * 1024 * 1024
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
	private val raiz = DocumentFile.fromTreeUri(contexto, arbol) ?: throw ExcepcionFormato(R.string.fmt_carpeta_no_abre)
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
