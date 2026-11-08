package com.xebolax.lector.copias

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.preference.PreferenceManager
import com.xebolax.lector.BuildConfig
import com.xebolax.lector.R
import com.xebolax.lector.datos.Actividad
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Secretos
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copias de seguridad completas de la app, para cambiar de móvil y tenerlo
 * todo igual, o para volver atrás. Se elige qué guardar y, al restaurar, qué
 * recuperar (Parte). Las claves y contraseñas solo van en copias protegidas con
 * contraseña (Cifrado: PBKDF2 + AES-256-GCM).
 *
 * El archivo (.lucopia):
 *   «LUCOPIA» + versión (1 byte) + cifrada (1 byte)
 *   si está cifrada: sal (16) + vueltas (4) + prefijo del nonce (7)
 *   y después un ZIP (cifrado o no) con copia.json al principio y una carpeta
 *   por parte. El ZIP comprueba cada archivo (CRC); el cifrado, todo.
 *
 * Al restaurar, primero se saca todo a una carpeta aparte y se comprueba; solo
 * si la copia está entera se sustituye lo que hay en el móvil.
 */
object CopiaSeguridad {

	private val MAGIA = "LUCOPIA".toByteArray(Charsets.US_ASCII)
	private const val FORMATO = 1
	const val EXTENSION = "lucopia"
	private const val MANIFIESTO = "copia.json"

	/** Lo que se puede guardar y restaurar, en el orden en que se ofrece. */
	enum class Parte(val id: String, val nombre: Int, val descripcion: Int) {
		BIBLIOTECA("biblioteca", R.string.parte_biblioteca, R.string.parte_biblioteca_desc),
		ARCHIVOS("archivos", R.string.parte_archivos, R.string.parte_archivos_desc),
		PODCASTS("podcasts", R.string.parte_podcasts, R.string.parte_podcasts_desc),
		NOTICIAS("noticias", R.string.parte_noticias, R.string.parte_noticias_desc),
		RADIO("radio", R.string.parte_radio, R.string.parte_radio_desc),
		GRABACIONES("grabaciones", R.string.parte_grabaciones, R.string.parte_grabaciones_desc),
		ESTADISTICAS("estadisticas", R.string.parte_estadisticas, R.string.parte_estadisticas_desc),
		AJUSTES("ajustes", R.string.parte_ajustes, R.string.parte_ajustes_desc),
		CLAVES("claves", R.string.parte_claves, R.string.parte_claves_desc),
	}

	/** Base de datos de cada parte. */
	private val BASES = mapOf(
		Parte.BIBLIOTECA to "biblioteca.db",
		Parte.PODCASTS to "podcasts.db",
		Parte.NOTICIAS to "rss.db",
		Parte.RADIO to "radio.db",
		Parte.ESTADISTICAS to "actividad.db",
	)

	/** Carpetas de la app con libros y documentos guardados (no la caché ni los modelos de IA). */
	private val CARPETAS_ARCHIVOS = listOf("biblioteca", "textos", "web")

	/** Ajustes que son de este móvil y no se cambian al restaurar. */
	private val AJUSTES_DE_ESTE_MOVIL = setOf("secretos_apartados", "version_anterior", "ultima_comprobacion", "migracion")

	/** Lo que dice una copia de sí misma. */
	class Info(
		val cifrada: Boolean,
		val fecha: Long,
		val version: String,
		val versionCode: Int,
		val dispositivo: String,
		val partes: List<Parte>,
		val tamanos: Map<Parte, Long>,
	)

	class NecesitaContrasena : IOException("La copia está protegida con contraseña")
	class NoEsUnaCopia : IOException("El archivo no es una copia de seguridad de Lector Universal")

	// --- Tamaños -------------------------------------------------------------------------------

	private fun carpetaGrabaciones(c: Context) = com.xebolax.lector.radio.Radio.carpetaGrabaciones(c)

	private fun archivosDe(raiz: File): List<File> = if (!raiz.exists()) emptyList() else raiz.walkTopDown().filter { it.isFile }.toList()

	private fun archivosApp(c: Context): List<Pair<String, File>> =
		CARPETAS_ARCHIVOS.flatMap { carpeta -> archivosDe(File(c.filesDir, carpeta)).map { it.relativeTo(c.filesDir).invariantSeparatorsPath to it } }

	/** Lo que ocupa cada parte ahora mismo en el móvil (sin comprimir); 0 = no hay nada que guardar. */
	fun tamanos(c: Context): Map<Parte, Long> = Parte.entries.associateWith { p ->
		when (p) {
			Parte.ARCHIVOS -> archivosApp(c).sumOf { it.second.length() }
			Parte.GRABACIONES -> archivosDe(carpetaGrabaciones(c)).sumOf { it.length() }
			Parte.AJUSTES -> PreferenceManager.getDefaultSharedPreferences(c).all.size.toLong() * 64
			Parte.CLAVES -> Secretos.todos(c).values.sumOf { it.length.toLong() }
			else -> BASES[p]?.let { c.getDatabasePath(it) }?.takeIf { it.exists() }?.length() ?: 0L
		}
	}

	// --- Crear ---------------------------------------------------------------------------------

	/**
	 * Escribe una copia con las partes elegidas. Con contraseña, cifrada; las
	 * claves solo con contraseña (salvo la copia interna de antes de restaurar).
	 */
	fun crear(c: Context, salida: OutputStream, partes: Set<Parte>, contrasena: String?, avance: (String) -> Unit = {}, clavesSinContrasena: Boolean = false) {
		require(Parte.CLAVES !in partes || contrasena != null || clavesSinContrasena) { "Las claves y contraseñas solo se guardan en una copia con contraseña." }
		val cabecera = ByteArrayOutputStream().apply {
			write(MAGIA)
			write(FORMATO)
			write(if (contrasena != null) 1 else 0)
		}
		val flujo: OutputStream = if (contrasena != null) {
			val sal = Cifrado.aleatorio(16)
			val prefijo = Cifrado.aleatorio(7)
			cabecera.write(sal)
			cabecera.write(java.nio.ByteBuffer.allocate(4).putInt(Cifrado.VUELTAS).array())
			cabecera.write(prefijo)
			val bytes = cabecera.toByteArray()
			salida.write(bytes)
			avance(c.getString(R.string.copia_protegiendo))
			Cifrado.Salida(salida, Cifrado.derivar(contrasena, sal), prefijo, bytes)
		} else {
			salida.write(cabecera.toByteArray())
			salida
		}
		val tamanos = tamanos(c)
		ZipOutputStream(flujo).use { zip ->
			val elegidas = Parte.entries.filter { it in partes }
			zip.putNextEntry(ZipEntry(MANIFIESTO))
			zip.write(JSONObject()
				.put("formato", FORMATO)
				.put("app", "LectorUniversal")
				.put("paquete", c.packageName)
				.put("version", BuildConfig.VERSION_NAME)
				.put("versionCode", BuildConfig.VERSION_CODE)
				.put("fecha", System.currentTimeMillis())
				.put("dispositivo", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
				.put("android", Build.VERSION.SDK_INT)
				.put("carpetaArchivos", c.filesDir.absolutePath)
				.put("carpetaGrabaciones", carpetaGrabaciones(c).absolutePath)
				.put("partes", JSONArray(elegidas.map { it.id }))
				.put("tamanos", JSONObject(elegidas.associate { it.id to (tamanos[it] ?: 0L) }))
				.toString(1).toByteArray())
			zip.closeEntry()
			for (p in elegidas) {
				avance(c.getString(R.string.copia_guardando, c.getString(p.nombre)))
				when (p) {
					Parte.ARCHIVOS -> archivosApp(c).forEach { (ruta, f) -> anadir(zip, "archivos/$ruta", f) }
					Parte.GRABACIONES -> carpetaGrabaciones(c).let { raiz ->
						archivosDe(raiz).forEach { f -> anadir(zip, "grabaciones/" + f.relativeTo(raiz).invariantSeparatorsPath, f) }
					}
					Parte.AJUSTES -> {
						zip.putNextEntry(ZipEntry("ajustes/ajustes.json"))
						zip.write(ajustesEnJson(c).toString(1).toByteArray())
						zip.closeEntry()
					}
					Parte.CLAVES -> {
						zip.putNextEntry(ZipEntry("claves/claves.json"))
						zip.write(JSONObject(Secretos.todos(c)).toString(1).toByteArray())
						zip.closeEntry()
					}
					else -> copiarBase(c, zip, p)
				}
			}
		}
	}

	private fun anadir(zip: ZipOutputStream, nombre: String, f: File) {
		zip.putNextEntry(ZipEntry(nombre).apply { time = f.lastModified() })
		f.inputStream().use { it.copyTo(zip) }
		zip.closeEntry()
	}

	/**
	 * Copia una base de datos tal como está: primero se vuelca su diario (WAL) y,
	 * mientras se copia, nadie puede escribir en ella (transacción exclusiva).
	 */
	private fun copiarBase(c: Context, zip: ZipOutputStream, p: Parte) {
		val nombre = BASES.getValue(p)
		val archivo = c.getDatabasePath(nombre)
		if (!archivo.exists()) return
		val db = SQLiteDatabase.openDatabase(archivo.path, null, SQLiteDatabase.OPEN_READWRITE)
		try {
			runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
			db.beginTransaction()
			try {
				anadir(zip, "${p.id}/$nombre", archivo)
				val diario = File(archivo.path + "-wal")
				if (diario.exists() && diario.length() > 0) anadir(zip, "${p.id}/$nombre-wal", diario)
			} finally {
				db.endTransaction()
			}
		} finally {
			db.close()
		}
	}

	/** Los ajustes con su tipo, para devolverlos tal cual. */
	private fun ajustesEnJson(c: Context): JSONObject {
		val o = JSONObject()
		for ((k, v) in PreferenceManager.getDefaultSharedPreferences(c).all) {
			if (k in AJUSTES_DE_ESTE_MOVIL || k in Secretos.CLAVES_AJUSTES) continue
			val (tipo, valor) = when (v) {
				is Boolean -> "b" to v
				is Int -> "i" to v
				is Long -> "l" to v
				is Float -> "f" to v.toDouble()
				is String -> "s" to v
				is Set<*> -> "ss" to JSONArray(v.map { it.toString() })
				else -> continue
			}
			o.put(k, JSONObject().put("t", tipo).put("v", valor))
		}
		return o
	}

	// --- Leer ----------------------------------------------------------------------------------

	/** ¿Es una copia de este formato? (si no, puede ser una copia antigua en JSON). */
	fun esCopia(entrada: InputStream): Boolean {
		val inicio = ByteArray(MAGIA.size)
		val n = entrada.readNBytesCompat(inicio)
		return n == MAGIA.size && inicio.contentEquals(MAGIA)
	}

	private fun InputStream.readNBytesCompat(b: ByteArray): Int {
		var leidos = 0
		while (leidos < b.size) {
			val n = read(b, leidos, b.size - leidos)
			if (n < 0) break
			leidos += n
		}
		return leidos
	}

	/** Abre la copia: comprueba la cabecera y, si está cifrada, la contraseña. Devuelve el ZIP y el flujo de descifrado. */
	private fun abrir(entrada: InputStream, contrasena: String?): Triple<ZipInputStream, InputStream, Boolean> {
		val datos = DataInputStream(entrada)
		val cabecera = ByteArrayOutputStream()
		val magia = ByteArray(MAGIA.size)
		if (datos.readNBytesCompat(magia) != MAGIA.size || !magia.contentEquals(MAGIA)) throw NoEsUnaCopia()
		val formato = datos.read()
		if (formato != FORMATO) throw IOException("La copia es de una versión más nueva de la app. Actualiza Lector Universal y vuelve a intentarlo.")
		val cifrada = datos.read() == 1
		cabecera.write(magia)
		cabecera.write(formato)
		cabecera.write(if (cifrada) 1 else 0)
		val flujo: InputStream = if (cifrada) {
			if (contrasena == null) throw NecesitaContrasena()
			val sal = ByteArray(16).also { datos.readFully(it) }
			val vueltas = datos.readInt()
			if (vueltas !in 1..10_000_000) throw IOException("La copia está dañada.")
			val prefijo = ByteArray(7).also { datos.readFully(it) }
			cabecera.write(sal)
			cabecera.write(java.nio.ByteBuffer.allocate(4).putInt(vueltas).array())
			cabecera.write(prefijo)
			Cifrado.Entrada(datos, Cifrado.derivar(contrasena, sal, vueltas), prefijo, cabecera.toByteArray())
		} else datos
		return Triple(ZipInputStream(flujo), flujo, cifrada)
	}

	private fun leerInfo(o: JSONObject, cifrada: Boolean): Info {
		if (o.optString("app") != "LectorUniversal") throw NoEsUnaCopia()
		val partes = o.optJSONArray("partes")?.let { a -> (0 until a.length()).mapNotNull { i -> Parte.entries.firstOrNull { it.id == a.getString(i) } } }.orEmpty()
		val t = o.optJSONObject("tamanos")
		return Info(cifrada, o.optLong("fecha"), o.optString("version"), o.optInt("versionCode"), o.optString("dispositivo"), partes,
			partes.associateWith { t?.optLong(it.id) ?: 0L })
	}

	/** Lo que contiene una copia (lee solo el principio). Lanza NecesitaContrasena o Cifrado.ContrasenaIncorrecta. */
	fun info(entrada: InputStream, contrasena: String?): Info {
		val (zip, _, cifrada) = abrir(entrada, contrasena)
		val primera = zip.nextEntry ?: throw NoEsUnaCopia()
		if (primera.name != MANIFIESTO) throw NoEsUnaCopia()
		return leerInfo(JSONObject(zip.readBytes().toString(Charsets.UTF_8)), cifrada)
	}

	// --- Restaurar -----------------------------------------------------------------------------

	/** Carpeta donde se saca la copia antes de aplicarla (no va en ninguna copia de Android). */
	private fun carpetaTemporal(c: Context) = File(c.noBackupFilesDir, "restauracion")

	/**
	 * Restaura las partes elegidas. Primero lo saca todo a una carpeta aparte y
	 * comprueba la copia entera; si algo falla, no se toca nada del móvil.
	 * Después hay que reiniciar la app (las pantallas abiertas tienen datos viejos).
	 */
	fun restaurar(c: Context, entrada: InputStream, contrasena: String?, partes: Set<Parte>, avance: (String) -> Unit = {}) {
		val temporal = carpetaTemporal(c)
		temporal.deleteRecursively()
		temporal.mkdirs()
		try {
			avance(c.getString(R.string.copia_comprobando))
			val (zip, flujo, cifrada) = abrir(entrada, contrasena)
			val primera = zip.nextEntry
			if (primera?.name != MANIFIESTO) throw NoEsUnaCopia()
			val manifiesto = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
			val info = leerInfo(manifiesto, cifrada)
			val elegidas = partes.intersect(info.partes.toSet())
			val raiz = temporal.canonicalPath + File.separator
			while (true) {
				val e = zip.nextEntry ?: break
				if (e.isDirectory) continue
				val parte = Parte.entries.firstOrNull { e.name.startsWith(it.id + "/") } ?: continue
				if (parte !in elegidas) continue
				val destino = File(temporal, e.name)
				// Nada puede salir de la carpeta temporal (nombres con «..»)
				if (!destino.canonicalPath.startsWith(raiz)) throw IOException("La copia está dañada.")
				destino.parentFile?.mkdirs()
				destino.outputStream().use { zip.copyTo(it) }
			}
			// Hasta el final: el cifrado comprueba que la copia no está cortada
			val resto = ByteArray(64 * 1024)
			while (flujo.read(resto) != -1) Unit
			aplicar(c, temporal, manifiesto, elegidas, avance)
		} finally {
			temporal.deleteRecursively()
		}
	}

	private fun aplicar(c: Context, temporal: File, manifiesto: JSONObject, partes: Set<Parte>, avance: (String) -> Unit) {
		avance(c.getString(R.string.copia_aplicando))
		// Las bases de datos abiertas se cierran: al volver a usarlas se abren las restauradas
		runCatching { BaseDatos.obtener(c).close() }
		runCatching { com.xebolax.lector.podcast.PodcastsBaseDatos.obtener(c).close() }
		runCatching { com.xebolax.lector.rss.RssBaseDatos.obtener(c).close() }
		runCatching { com.xebolax.lector.radio.RadioBaseDatos.obtener(c).close() }
		Actividad.cerrar()
		for (p in partes) {
			val nombre = BASES[p] ?: continue
			val origen = File(temporal, "${p.id}/$nombre")
			if (!origen.exists()) continue
			val destino = c.getDatabasePath(nombre)
			destino.parentFile?.mkdirs()
			for (sufijo in listOf("-wal", "-shm", "-journal")) File(destino.path + sufijo).delete()
			val nuevo = File(destino.path + ".restaurando")
			origen.copyTo(nuevo, overwrite = true)
			if (!nuevo.renameTo(destino)) {
				destino.delete()
				if (!nuevo.renameTo(destino)) throw IOException("No se pudo restaurar $nombre.")
			}
			File(temporal, "${p.id}/$nombre-wal").takeIf { it.exists() }?.copyTo(File(destino.path + "-wal"), overwrite = true)
		}
		if (Parte.ARCHIVOS in partes) File(temporal, "archivos").takeIf { it.exists() }?.copyRecursively(c.filesDir, overwrite = true)
		if (Parte.GRABACIONES in partes) File(temporal, "grabaciones").takeIf { it.exists() }?.copyRecursively(carpetaGrabaciones(c), overwrite = true)
		// Rutas de este móvil: los libros guardados en la app y las grabaciones pueden estar en otra carpeta
		cambiarRuta(c, "biblioteca.db", "UPDATE libros SET uri = replace(uri, ?, ?)", manifiesto.optString("carpetaArchivos"), c.filesDir.absolutePath, Parte.BIBLIOTECA in partes)
		cambiarRuta(c, "radio.db", "UPDATE grabaciones SET archivo = replace(archivo, ?, ?)", manifiesto.optString("carpetaGrabaciones"), carpetaGrabaciones(c).absolutePath, Parte.RADIO in partes)
		if (Parte.AJUSTES in partes) File(temporal, "ajustes/ajustes.json").takeIf { it.exists() }?.let { restaurarAjustes(c, JSONObject(it.readText())) }
		if (Parte.CLAVES in partes) File(temporal, "claves/claves.json").takeIf { it.exists() }?.let { f ->
			val o = JSONObject(f.readText())
			Secretos.reemplazarTodos(c, o.keys().asSequence().associateWith { o.optString(it) })
		} else Secretos.reubicar(c)
		// Despertadores, grabaciones programadas y actualización de noticias, según lo restaurado
		runCatching { com.xebolax.lector.radio.RadioProgramador.programar(c) }
		runCatching { com.xebolax.lector.rss.RssTrabajo.programar(c) }
	}

	private fun cambiarRuta(c: Context, base: String, sql: String, antes: String, ahora: String, restaurada: Boolean) {
		if (!restaurada || antes.isBlank() || antes == ahora) return
		val archivo = c.getDatabasePath(base)
		if (!archivo.exists()) return
		runCatching {
			SQLiteDatabase.openDatabase(archivo.path, null, SQLiteDatabase.OPEN_READWRITE).use { db -> db.execSQL(sql, arrayOf(antes, ahora)) }
		}
	}

	private fun restaurarAjustes(c: Context, o: JSONObject) {
		val p = PreferenceManager.getDefaultSharedPreferences(c)
		val e = p.edit()
		// Lo de este móvil se queda; lo demás, como en la copia
		for (k in p.all.keys) if (k !in AJUSTES_DE_ESTE_MOVIL) e.remove(k)
		for (k in o.keys()) {
			if (k in AJUSTES_DE_ESTE_MOVIL || k in Secretos.CLAVES_AJUSTES) continue
			val x = o.optJSONObject(k) ?: continue
			when (x.optString("t")) {
				"b" -> e.putBoolean(k, x.optBoolean("v"))
				"i" -> e.putInt(k, x.optInt("v"))
				"l" -> e.putLong(k, x.optLong("v"))
				"f" -> e.putFloat(k, x.optDouble("v").toFloat())
				"s" -> e.putString(k, x.optString("v"))
				"ss" -> e.putStringSet(k, x.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet())
			}
		}
		e.commit()
	}

	// --- Copia de antes de restaurar ------------------------------------------------------------

	/** Copia interna de cómo estaba todo antes de la última restauración (para deshacerla). */
	fun copiaAnterior(c: Context) = File(File(c.noBackupFilesDir, "copias"), "antes-de-restaurar.$EXTENSION")

	/** Guarda cómo está todo ahora (menos los archivos grandes), sin cifrar: no sale nunca del móvil. */
	fun guardarCopiaAnterior(c: Context, avance: (String) -> Unit = {}) {
		val f = copiaAnterior(c)
		f.parentFile?.mkdirs()
		val temporal = File(f.path + ".parte")
		temporal.outputStream().buffered().use { salida ->
			crear(c, salida, Parte.entries.toSet() - Parte.ARCHIVOS - Parte.GRABACIONES, null, avance, clavesSinContrasena = true)
		}
		f.delete()
		temporal.renameTo(f)
	}
}
