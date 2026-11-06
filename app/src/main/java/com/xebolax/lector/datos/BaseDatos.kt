package com.xebolax.lector.datos

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.xebolax.lector.modelo.Posicion
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Estado de lectura de un libro. */
object Estado {
	const val NUEVO = 0
	const val LEYENDO = 1
	const val TERMINADO = 2
	/** Lo quieres leer (aún sin empezar). */
	const val QUIERO_LEER = 3
	/** Lo dejaste sin terminar. */
	const val ABANDONADO = 4
}

data class Libro(
	val id: Long,
	val uri: String,
	val nombre: String,
	val titulo: String,
	val autor: String,
	val formato: String,
	val audio: Boolean,
	val tamano: Long,
	val carpeta: Long?,
	val anadido: Long,
	val abierto: Long,
	val posicion: Posicion?,
	val progreso: Double,
	val estado: Int,
	val favorito: Boolean,
	val idioma: String,
	val segundos: Long,
	/** Ajustes propios del libro (voz, velocidad...) en JSON. */
	val ajustes: String,
	/** Serie a la que pertenece y su número en ella (de los datos del libro o puesta a mano). */
	val serie: String = "",
	val numeroSerie: Double = 0.0,
) {
	val tituloVisible: String get() = titulo.ifBlank { nombre.substringBeforeLast('.') }
}

data class Carpeta(val id: Long, val nombre: String, val padre: Long?, val orden: Int)

/** Subrayado: un fragmento (de inicio a fin) de un párrafo, con su categoría. */
data class Subrayado(val id: Long, val libro: Long, val bloque: Int, val inicio: Int, val fin: Int, val texto: String, val categoria: String, val creado: Long)

/** Colección: un libro puede estar en varias (a diferencia de las carpetas). */
data class Coleccion(val id: Long, val nombre: String, val libros: Int)

data class Marcador(
	val id: Long,
	val libro: Long,
	val posicion: Posicion,
	val extracto: String,
	val nota: String,
	val creado: Long,
	/** Etiqueta del sitio (capítulo, página o tiempo). */
	val lugar: String,
)

data class Pronunciacion(
	val id: Long,
	val buscar: String,
	val reemplazar: String,
	val regex: Boolean,
	val mayusculas: Boolean,
	val activo: Boolean,
	/** null = para todos los libros. */
	val libro: Long?,
)

/** Orden de las listas de libros. */
enum class Orden(val sql: String) {
	RECIENTES("abierto DESC, anadido DESC"),
	TITULO("titulo COLLATE NOCASE, nombre COLLATE NOCASE"),
	AUTOR("autor COLLATE NOCASE, titulo COLLATE NOCASE"),
	ANADIDOS("anadido DESC"),
	PROGRESO("progreso DESC, abierto DESC"),
	FORMATO("formato, titulo COLLATE NOCASE"),
	TAMANO("tamano DESC"),
}

/**
 * Base de datos de la biblioteca: libros, carpetas (anidadas), marcadores y
 * notas, estadísticas de lectura y diccionario de pronunciación. Una sola
 * instancia para toda la app.
 */
class BaseDatos private constructor(contexto: Context) : SQLiteOpenHelper(contexto, "biblioteca.db", null, VERSION) {

	companion object {
		private const val VERSION = 2

		@Volatile
		private var instancia: BaseDatos? = null

		fun obtener(contexto: Context): BaseDatos =
			instancia ?: synchronized(this) { instancia ?: BaseDatos(contexto.applicationContext).also { instancia = it } }

		private val DIA = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
		fun hoy(): String = synchronized(DIA) { DIA.format(Date()) }
	}

	/** Se avisa a las pantallas abiertas cuando cambia la biblioteca. */
	fun interface Oyente {
		fun alCambiar()
	}

	private val oyentes = java.util.concurrent.CopyOnWriteArrayList<Oyente>()
	fun suscribir(o: Oyente) = oyentes.addIfAbsent(o)
	fun desuscribir(o: Oyente) = oyentes.remove(o)
	private fun avisar() = oyentes.forEach { runCatching { it.alCambiar() } }

	override fun onConfigure(db: SQLiteDatabase) {
		db.setForeignKeyConstraintsEnabled(true)
	}

	override fun onCreate(db: SQLiteDatabase) {
		db.execSQL(
			"""CREATE TABLE carpetas (
				id INTEGER PRIMARY KEY AUTOINCREMENT,
				nombre TEXT NOT NULL,
				padre INTEGER REFERENCES carpetas(id) ON DELETE CASCADE,
				orden INTEGER NOT NULL DEFAULT 0,
				creada INTEGER NOT NULL DEFAULT 0)"""
		)
		db.execSQL(
			"""CREATE TABLE libros (
				id INTEGER PRIMARY KEY AUTOINCREMENT,
				uri TEXT NOT NULL UNIQUE,
				nombre TEXT NOT NULL DEFAULT '',
				titulo TEXT NOT NULL DEFAULT '',
				autor TEXT NOT NULL DEFAULT '',
				formato TEXT NOT NULL DEFAULT '',
				audio INTEGER NOT NULL DEFAULT 0,
				tamano INTEGER NOT NULL DEFAULT 0,
				carpeta INTEGER REFERENCES carpetas(id) ON DELETE SET NULL,
				anadido INTEGER NOT NULL DEFAULT 0,
				abierto INTEGER NOT NULL DEFAULT 0,
				posicion TEXT,
				progreso REAL NOT NULL DEFAULT 0,
				estado INTEGER NOT NULL DEFAULT 0,
				favorito INTEGER NOT NULL DEFAULT 0,
				idioma TEXT NOT NULL DEFAULT '',
				segundos INTEGER NOT NULL DEFAULT 0,
				ajustes TEXT NOT NULL DEFAULT '')"""
		)
		db.execSQL("CREATE INDEX libros_carpeta ON libros(carpeta)")
		db.execSQL(
			"""CREATE TABLE marcadores (
				id INTEGER PRIMARY KEY AUTOINCREMENT,
				libro INTEGER NOT NULL REFERENCES libros(id) ON DELETE CASCADE,
				posicion TEXT NOT NULL,
				extracto TEXT NOT NULL DEFAULT '',
				nota TEXT NOT NULL DEFAULT '',
				lugar TEXT NOT NULL DEFAULT '',
				creado INTEGER NOT NULL DEFAULT 0)"""
		)
		db.execSQL("CREATE INDEX marcadores_libro ON marcadores(libro)")
		db.execSQL(
			"""CREATE TABLE estadisticas (
				dia TEXT NOT NULL,
				libro INTEGER NOT NULL REFERENCES libros(id) ON DELETE CASCADE,
				segundos INTEGER NOT NULL DEFAULT 0,
				caracteres INTEGER NOT NULL DEFAULT 0,
				PRIMARY KEY (dia, libro))"""
		)
		db.execSQL(
			"""CREATE TABLE pronunciacion (
				id INTEGER PRIMARY KEY AUTOINCREMENT,
				buscar TEXT NOT NULL,
				reemplazar TEXT NOT NULL DEFAULT '',
				regex INTEGER NOT NULL DEFAULT 0,
				mayusculas INTEGER NOT NULL DEFAULT 0,
				activo INTEGER NOT NULL DEFAULT 1,
				libro INTEGER REFERENCES libros(id) ON DELETE CASCADE)"""
		)
		crearSeriesYColecciones(db)
	}

	private fun tieneColumna(db: SQLiteDatabase, tabla: String, columna: String): Boolean =
		db.rawQuery("PRAGMA table_info($tabla)", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(1) else null }.any { it == columna } }

	/** Versión 2: serie y número de cada libro, y colecciones (un libro puede estar en varias). */
	private fun crearSeriesYColecciones(db: SQLiteDatabase) {
		if (!tieneColumna(db, "libros", "serie")) db.execSQL("ALTER TABLE libros ADD COLUMN serie TEXT NOT NULL DEFAULT ''")
		if (!tieneColumna(db, "libros", "numero_serie")) db.execSQL("ALTER TABLE libros ADD COLUMN numero_serie REAL NOT NULL DEFAULT 0")
		db.execSQL("CREATE TABLE IF NOT EXISTS colecciones (id INTEGER PRIMARY KEY AUTOINCREMENT, nombre TEXT NOT NULL UNIQUE COLLATE NOCASE, orden INTEGER NOT NULL DEFAULT 0)")
		db.execSQL(
			"""CREATE TABLE IF NOT EXISTS subrayados (
				id INTEGER PRIMARY KEY AUTOINCREMENT,
				libro INTEGER NOT NULL REFERENCES libros(id) ON DELETE CASCADE,
				bloque INTEGER NOT NULL,
				inicio INTEGER NOT NULL,
				fin INTEGER NOT NULL,
				texto TEXT NOT NULL DEFAULT '',
				categoria TEXT NOT NULL DEFAULT '',
				creado INTEGER NOT NULL DEFAULT 0)"""
		)
		db.execSQL(
			"""CREATE TABLE IF NOT EXISTS libro_coleccion (
				libro INTEGER NOT NULL REFERENCES libros(id) ON DELETE CASCADE,
				coleccion INTEGER NOT NULL REFERENCES colecciones(id) ON DELETE CASCADE,
				PRIMARY KEY (libro, coleccion))"""
		)
	}

	/** Si se instala una versión anterior de la app, la base de datos (más nueva) se deja como está, sin cerrar la app. */
	override fun onDowngrade(db: SQLiteDatabase, antes: Int, ahora: Int) = Unit

	override fun onUpgrade(db: SQLiteDatabase, anterior: Int, nueva: Int) {
		// Una migración por versión
		if (anterior < 2) crearSeriesYColecciones(db)
	}

	// --- Libros ------------------------------------------------------------------

	private fun Cursor.libro() = Libro(
		id = getLong(getColumnIndexOrThrow("id")),
		uri = getString(getColumnIndexOrThrow("uri")),
		nombre = getString(getColumnIndexOrThrow("nombre")),
		titulo = getString(getColumnIndexOrThrow("titulo")),
		autor = getString(getColumnIndexOrThrow("autor")),
		formato = getString(getColumnIndexOrThrow("formato")),
		audio = getInt(getColumnIndexOrThrow("audio")) != 0,
		tamano = getLong(getColumnIndexOrThrow("tamano")),
		carpeta = getColumnIndexOrThrow("carpeta").let { if (isNull(it)) null else getLong(it) },
		anadido = getLong(getColumnIndexOrThrow("anadido")),
		abierto = getLong(getColumnIndexOrThrow("abierto")),
		posicion = Posicion.leer(getString(getColumnIndexOrThrow("posicion"))),
		progreso = getDouble(getColumnIndexOrThrow("progreso")),
		estado = getInt(getColumnIndexOrThrow("estado")),
		favorito = getInt(getColumnIndexOrThrow("favorito")) != 0,
		idioma = getString(getColumnIndexOrThrow("idioma")),
		segundos = getLong(getColumnIndexOrThrow("segundos")),
		ajustes = getString(getColumnIndexOrThrow("ajustes")),
		serie = getString(getColumnIndexOrThrow("serie")),
		numeroSerie = getDouble(getColumnIndexOrThrow("numero_serie")),
	)

	private fun libros(donde: String?, argumentos: Array<String>?, orden: String, limite: String? = null): List<Libro> =
		readableDatabase.query("libros", null, donde, argumentos, null, null, orden, limite).use { c ->
			val lista = ArrayList<Libro>(c.count)
			while (c.moveToNext()) lista += c.libro()
			lista
		}

	fun libro(id: Long): Libro? = libros("id = ?", arrayOf(id.toString()), "id").firstOrNull()

	fun libroPorUri(uri: String): Libro? = libros("uri = ?", arrayOf(uri), "id").firstOrNull()

	fun todos(orden: Orden = Orden.TITULO): List<Libro> = libros(null, null, orden.sql)

	fun recientes(limite: Int = 50): List<Libro> = libros("abierto > 0", null, Orden.RECIENTES.sql, limite.toString())

	fun ultimoAbierto(): Libro? = recientes(1).firstOrNull()

	/** Libros de una carpeta (null = raíz de la biblioteca, sin carpeta). */
	fun deCarpeta(carpeta: Long?, orden: Orden = Orden.TITULO): List<Libro> =
		if (carpeta == null) libros("carpeta IS NULL", null, orden.sql)
		else libros("carpeta = ?", arrayOf(carpeta.toString()), orden.sql)

	fun favoritos(orden: Orden = Orden.TITULO): List<Libro> = libros("favorito = 1", null, orden.sql)

	fun porEstado(estado: Int, orden: Orden = Orden.TITULO): List<Libro> = libros("estado = ?", arrayOf(estado.toString()), orden.sql)

	fun buscarLibros(texto: String, orden: Orden = Orden.TITULO): List<Libro> {
		val patron = "%${texto.trim()}%"
		return libros("titulo LIKE ? OR autor LIKE ? OR nombre LIKE ?", arrayOf(patron, patron, patron), orden.sql)
	}

	/** Añade un libro (o devuelve el que ya había con esa dirección). */
	fun anadirLibro(uri: String, nombre: String, titulo: String, autor: String, formato: String, audio: Boolean, tamano: Long, carpeta: Long?): Long {
		libroPorUri(uri)?.let { return it.id }
		val v = ContentValues().apply {
			put("uri", uri)
			put("nombre", nombre)
			put("titulo", titulo)
			put("autor", autor)
			put("formato", formato)
			put("audio", if (audio) 1 else 0)
			put("tamano", tamano)
			if (carpeta != null) put("carpeta", carpeta) else putNull("carpeta")
			put("anadido", System.currentTimeMillis())
		}
		val id = writableDatabase.insert("libros", null, v)
		avisar()
		return id
	}

	private fun actualizar(id: Long, v: ContentValues, avisando: Boolean = true) {
		writableDatabase.update("libros", v, "id = ?", arrayOf(id.toString()))
		if (avisando) avisar()
	}

	/** Título, autor e idioma reales (al abrirlo por primera vez). */
	fun actualizarDatos(id: Long, titulo: String, autor: String, idioma: String, formato: String, audio: Boolean) =
		actualizar(id, ContentValues().apply {
			if (titulo.isNotBlank()) put("titulo", titulo)
			if (autor.isNotBlank()) put("autor", autor)
			put("idioma", idioma)
			put("formato", formato)
			put("audio", if (audio) 1 else 0)
		})

	/** Guarda la posición y el progreso; pasa a "leyendo" o "terminado" solo. */
	fun guardarPosicion(id: Long, posicion: Posicion, progreso: Double) {
		val libro = libro(id) ?: return
		val estado = when {
			progreso >= 99.5 -> Estado.TERMINADO
			libro.estado == Estado.TERMINADO && progreso >= 95 -> Estado.TERMINADO
			else -> Estado.LEYENDO
		}
		actualizar(id, ContentValues().apply {
			put("posicion", posicion.toString())
			put("progreso", progreso)
			put("estado", estado)
			put("abierto", System.currentTimeMillis())
		}, avisando = libro.estado != estado)
	}

	/** Posición que viene de otro dispositivo (más reciente que la de aquí). */
	fun aplicarSincronizacion(id: Long, posicion: Posicion, progreso: Double, estado: Int, cuando: Long) = actualizar(id, ContentValues().apply {
		put("posicion", posicion.toString())
		put("progreso", progreso)
		put("estado", estado)
		put("abierto", cuando)
	})

	/** Quita un libro de la lista Leyendo (conserva su posición). */
	fun quitarDeRecientes(id: Long) = actualizar(id, ContentValues().apply { put("abierto", 0) })

	/** Vacía la lista Leyendo (privacidad: no se ve qué se ha leído). */
	fun vaciarRecientes() {
		writableDatabase.update("libros", ContentValues().apply { put("abierto", 0) }, null, null)
		avisar()
	}

	fun marcarAbierto(id: Long) = actualizar(id, ContentValues().apply { put("abierto", System.currentTimeMillis()) })

	fun cambiarEstado(id: Long, estado: Int) = actualizar(id, ContentValues().apply {
		put("estado", estado)
		if (estado == Estado.NUEVO) {
			putNull("posicion")
			put("progreso", 0.0)
		}
	})

	// --- Subrayados -------------------------------------------------------------------

	fun subrayados(libro: Long): List<Subrayado> = readableDatabase.rawQuery(
		"SELECT id, libro, bloque, inicio, fin, texto, categoria, creado FROM subrayados WHERE libro = ? ORDER BY bloque, inicio", arrayOf(libro.toString()),
	).use { c -> generateSequence { if (c.moveToNext()) Subrayado(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getString(5), c.getString(6), c.getLong(7)) else null }.toList() }

	fun anadirSubrayado(libro: Long, bloque: Int, inicio: Int, fin: Int, texto: String, categoria: String): Long {
		val id = writableDatabase.insert("subrayados", null, ContentValues().apply {
			put("libro", libro)
			put("bloque", bloque)
			put("inicio", inicio)
			put("fin", fin)
			put("texto", texto)
			put("categoria", categoria)
			put("creado", System.currentTimeMillis())
		})
		avisar()
		return id
	}

	fun borrarSubrayado(id: Long) {
		writableDatabase.delete("subrayados", "id = ?", arrayOf(id.toString()))
		avisar()
	}

	fun categoriaSubrayado(id: Long, categoria: String) {
		writableDatabase.update("subrayados", ContentValues().apply { put("categoria", categoria) }, "id = ?", arrayOf(id.toString()))
		avisar()
	}

	// --- Series, autores y colecciones ----------------------------------------------

	fun ponerSerie(id: Long, serie: String, numero: Double) = actualizar(id, ContentValues().apply {
		put("serie", serie.trim())
		put("numero_serie", numero)
	})

	/** Nombre y número de serie a partir de «Episodios Nacionales, 3» (como lo dan FB2 y EPUB). */
	fun ponerSerieDeTexto(id: Long, texto: String) {
		val t = texto.trim()
		if (t.isEmpty()) return
		val m = Regex("""^(.*?)[,\s#]+(\d+(?:[.,]\d+)?)$""").find(t)
		if (m != null) ponerSerie(id, m.groupValues[1].trim(), m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: 0.0)
		else ponerSerie(id, t, 0.0)
	}

	/** Autores con cuántos libros tiene cada uno (por nombre). */
	fun autores(): List<Pair<String, Int>> = readableDatabase.rawQuery(
		"SELECT autor, COUNT(*) FROM libros WHERE autor != '' GROUP BY autor COLLATE NOCASE ORDER BY autor COLLATE NOCASE", null,
	).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) to c.getInt(1) else null }.toList() }

	fun deAutor(autor: String, orden: Orden = Orden.TITULO): List<Libro> = libros("autor = ? COLLATE NOCASE", arrayOf(autor), orden.sql)

	fun series(): List<Pair<String, Int>> = readableDatabase.rawQuery(
		"SELECT serie, COUNT(*) FROM libros WHERE serie != '' GROUP BY serie COLLATE NOCASE ORDER BY serie COLLATE NOCASE", null,
	).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) to c.getInt(1) else null }.toList() }

	/** Los libros de una serie, por su número. */
	fun deSerie(serie: String): List<Libro> = libros("serie = ? COLLATE NOCASE", arrayOf(serie), "numero_serie, titulo COLLATE NOCASE")

	fun colecciones(): List<Coleccion> = readableDatabase.rawQuery(
		"SELECT c.id, c.nombre, (SELECT COUNT(*) FROM libro_coleccion lc WHERE lc.coleccion = c.id) FROM colecciones c ORDER BY c.orden, c.nombre COLLATE NOCASE", null,
	).use { c -> generateSequence { if (c.moveToNext()) Coleccion(c.getLong(0), c.getString(1), c.getInt(2)) else null }.toList() }

	/** Crea la colección (o devuelve la que ya se llama así). */
	fun crearColeccion(nombre: String): Long {
		val n = nombre.trim()
		colecciones().firstOrNull { it.nombre.equals(n, ignoreCase = true) }?.let { return it.id }
		val id = writableDatabase.insert("colecciones", null, ContentValues().apply {
			put("nombre", n)
			put("orden", (colecciones().size + 1))
		})
		avisar()
		return id
	}

	fun renombrarColeccion(id: Long, nombre: String) {
		writableDatabase.update("colecciones", ContentValues().apply { put("nombre", nombre.trim()) }, "id = ?", arrayOf(id.toString()))
		avisar()
	}

	/** Borra la colección; los libros se quedan en la biblioteca. */
	fun borrarColeccion(id: Long) {
		writableDatabase.delete("colecciones", "id = ?", arrayOf(id.toString()))
		avisar()
	}

	fun coleccionesDe(libro: Long): Set<Long> = readableDatabase.rawQuery("SELECT coleccion FROM libro_coleccion WHERE libro = ?", arrayOf(libro.toString())).use { c ->
		generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toSet()
	}

	fun ponerEnColeccion(libro: Long, coleccion: Long, dentro: Boolean) {
		if (dentro) writableDatabase.insertWithOnConflict("libro_coleccion", null, ContentValues().apply {
			put("libro", libro)
			put("coleccion", coleccion)
		}, SQLiteDatabase.CONFLICT_IGNORE)
		else writableDatabase.delete("libro_coleccion", "libro = ? AND coleccion = ?", arrayOf(libro.toString(), coleccion.toString()))
		avisar()
	}

	fun deColeccion(coleccion: Long, orden: Orden = Orden.TITULO): List<Libro> =
		libros("id IN (SELECT libro FROM libro_coleccion WHERE coleccion = ?)", arrayOf(coleccion.toString()), orden.sql)

	/** Nombres de las colecciones de un libro (para decirlas en su fila). */
	fun nombresColecciones(libro: Long): List<String> = readableDatabase.rawQuery(
		"SELECT c.nombre FROM colecciones c JOIN libro_coleccion lc ON lc.coleccion = c.id WHERE lc.libro = ? ORDER BY c.orden, c.nombre", arrayOf(libro.toString()),
	).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

	fun cambiarFavorito(id: Long, favorito: Boolean) = actualizar(id, ContentValues().apply { put("favorito", if (favorito) 1 else 0) })

	fun renombrar(id: Long, titulo: String, autor: String) = actualizar(id, ContentValues().apply {
		put("titulo", titulo)
		put("autor", autor)
	})

	fun moverLibro(id: Long, carpeta: Long?) = actualizar(id, ContentValues().apply {
		if (carpeta != null) put("carpeta", carpeta) else putNull("carpeta")
	})

	fun guardarAjustesLibro(id: Long, ajustes: JSONObject) =
		actualizar(id, ContentValues().apply { put("ajustes", ajustes.toString()) }, avisando = false)

	fun cambiarUri(id: Long, uri: String) = actualizar(id, ContentValues().apply { put("uri", uri) })

	fun quitarLibro(id: Long) {
		writableDatabase.delete("libros", "id = ?", arrayOf(id.toString()))
		avisar()
	}

	// --- Carpetas ----------------------------------------------------------------

	private fun Cursor.carpeta() = Carpeta(
		getLong(0), getString(1), if (isNull(2)) null else getLong(2), getInt(3),
	)

	fun carpetas(padre: Long?): List<Carpeta> {
		val (donde, args) = if (padre == null) "padre IS NULL" to null else "padre = ?" to arrayOf(padre.toString())
		return readableDatabase.query("carpetas", arrayOf("id", "nombre", "padre", "orden"), donde, args, null, null, "orden, nombre COLLATE NOCASE")
			.use { c -> generateSequence { if (c.moveToNext()) c.carpeta() else null }.toList() }
	}

	fun todasLasCarpetas(): List<Carpeta> =
		readableDatabase.query("carpetas", arrayOf("id", "nombre", "padre", "orden"), null, null, null, null, "nombre COLLATE NOCASE")
			.use { c -> generateSequence { if (c.moveToNext()) c.carpeta() else null }.toList() }

	fun carpeta(id: Long): Carpeta? = todasLasCarpetas().firstOrNull { it.id == id }

	/** "Estudios › Historia": ruta de una carpeta para mostrarla. */
	fun rutaCarpeta(id: Long?): String {
		if (id == null) return ""
		val todas = todasLasCarpetas().associateBy { it.id }
		val nombres = ArrayList<String>()
		var actual = todas[id]
		var vueltas = 0
		while (actual != null && vueltas++ < 30) {
			nombres.add(0, actual.nombre)
			actual = actual.padre?.let { todas[it] }
		}
		return nombres.joinToString(" › ")
	}

	fun crearCarpeta(nombre: String, padre: Long?): Long {
		val id = writableDatabase.insert("carpetas", null, ContentValues().apply {
			put("nombre", nombre.trim())
			if (padre != null) put("padre", padre) else putNull("padre")
			put("creada", System.currentTimeMillis())
		})
		avisar()
		return id
	}

	fun renombrarCarpeta(id: Long, nombre: String) {
		writableDatabase.update("carpetas", ContentValues().apply { put("nombre", nombre.trim()) }, "id = ?", arrayOf(id.toString()))
		avisar()
	}

	/** Mueve una carpeta dentro de otra (nunca dentro de sí misma o de sus subcarpetas). */
	fun moverCarpeta(id: Long, padre: Long?): Boolean {
		var actual = padre
		val todas = todasLasCarpetas().associateBy { it.id }
		while (actual != null) {
			if (actual == id) return false
			actual = todas[actual]?.padre
		}
		writableDatabase.update("carpetas", ContentValues().apply {
			if (padre != null) put("padre", padre) else putNull("padre")
		}, "id = ?", arrayOf(id.toString()))
		avisar()
		return true
	}

	/** Borra la carpeta y sus subcarpetas; los libros pasan a la carpeta padre. */
	fun borrarCarpeta(id: Long) {
		val padre = carpeta(id)?.padre
		val db = writableDatabase
		db.beginTransaction()
		try {
			fun subcarpetas(c: Long): List<Long> = carpetas(c).flatMap { listOf(it.id) + subcarpetas(it.id) }
			for (c in listOf(id) + subcarpetas(id)) {
				db.update("libros", ContentValues().apply {
					if (padre != null) put("carpeta", padre) else putNull("carpeta")
				}, "carpeta = ?", arrayOf(c.toString()))
			}
			db.delete("carpetas", "id = ?", arrayOf(id.toString()))
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		avisar()
	}

	/** Número de libros de una carpeta, contando sus subcarpetas. */
	fun contarEnCarpeta(id: Long): Int {
		fun ids(c: Long): List<Long> = listOf(c) + carpetas(c).flatMap { ids(it.id) }
		val lista = ids(id).joinToString(",")
		return readableDatabase.rawQuery("SELECT COUNT(*) FROM libros WHERE carpeta IN ($lista)", null).use { c ->
			if (c.moveToFirst()) c.getInt(0) else 0
		}
	}

	// --- Marcadores y notas ----------------------------------------------------------

	private fun Cursor.marcador() = Marcador(
		id = getLong(getColumnIndexOrThrow("id")),
		libro = getLong(getColumnIndexOrThrow("libro")),
		posicion = Posicion.leer(getString(getColumnIndexOrThrow("posicion"))) ?: Posicion.INICIO,
		extracto = getString(getColumnIndexOrThrow("extracto")),
		nota = getString(getColumnIndexOrThrow("nota")),
		creado = getLong(getColumnIndexOrThrow("creado")),
		lugar = getString(getColumnIndexOrThrow("lugar")),
	)

	fun marcadores(libro: Long): List<Marcador> =
		readableDatabase.query("marcadores", null, "libro = ?", arrayOf(libro.toString()), null, null, "id")
			.use { c -> generateSequence { if (c.moveToNext()) c.marcador() else null }.toList() }
			.sortedWith(compareBy({ it.posicion.a }, { it.posicion.b }))

	fun todosLosMarcadores(): List<Marcador> =
		readableDatabase.query("marcadores", null, null, null, null, null, "creado DESC")
			.use { c -> generateSequence { if (c.moveToNext()) c.marcador() else null }.toList() }

	fun anadirMarcador(libro: Long, posicion: Posicion, extracto: String, nota: String, lugar: String): Long {
		val id = writableDatabase.insert("marcadores", null, ContentValues().apply {
			put("libro", libro)
			put("posicion", posicion.toString())
			put("extracto", extracto.take(300))
			put("nota", nota)
			put("lugar", lugar)
			put("creado", System.currentTimeMillis())
		})
		avisar()
		return id
	}

	/** Marcador que viene de otro dispositivo (conserva su fecha de creación). */
	fun anadirMarcadorSincronizado(libro: Long, posicion: Posicion, extracto: String, nota: String, lugar: String, creado: Long) {
		writableDatabase.insert("marcadores", null, ContentValues().apply {
			put("libro", libro)
			put("posicion", posicion.toString())
			put("extracto", extracto.take(300))
			put("nota", nota)
			put("lugar", lugar)
			put("creado", creado)
		})
		avisar()
	}

	fun editarNota(id: Long, nota: String) {
		writableDatabase.update("marcadores", ContentValues().apply { put("nota", nota) }, "id = ?", arrayOf(id.toString()))
		avisar()
	}

	fun borrarMarcador(id: Long) {
		writableDatabase.delete("marcadores", "id = ?", arrayOf(id.toString()))
		avisar()
	}

	// --- Estadísticas ------------------------------------------------------------------

	/** Suma tiempo (y caracteres avanzados) de lectura de hoy a un libro. */
	fun sumarLectura(libro: Long, segundos: Long, caracteres: Long) {
		if (segundos <= 0 && caracteres <= 0) return
		val db = writableDatabase
		// Sin UPSERT: el SQLite de Android 7 no lo tiene
		val actualizadas = db.compileStatement(
			"UPDATE estadisticas SET segundos = segundos + ?, caracteres = caracteres + ? WHERE dia = ? AND libro = ?",
		).use { s ->
			s.bindLong(1, segundos)
			s.bindLong(2, caracteres.coerceAtLeast(0))
			s.bindString(3, hoy())
			s.bindLong(4, libro)
			s.executeUpdateDelete()
		}
		if (actualizadas == 0) db.insert("estadisticas", null, ContentValues().apply {
			put("dia", hoy())
			put("libro", libro)
			put("segundos", segundos)
			put("caracteres", caracteres.coerceAtLeast(0))
		})
		db.execSQL("UPDATE libros SET segundos = segundos + ? WHERE id = ?", arrayOf<Any>(segundos, libro))
	}

	/** (segundos, caracteres) desde un día (incluido) hasta hoy; libro null = todos. */
	fun lecturaDesde(dia: String, libro: Long? = null): Pair<Long, Long> {
		val (donde, args) = if (libro == null) "dia >= ?" to arrayOf(dia) else "dia >= ? AND libro = ?" to arrayOf(dia, libro.toString())
		return readableDatabase.rawQuery("SELECT SUM(segundos), SUM(caracteres) FROM estadisticas WHERE $donde", args).use { c ->
			if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else 0L to 0L
		}
	}

	/** Días distintos con lectura (para la racha). */
	fun diasConLectura(): List<String> =
		readableDatabase.rawQuery("SELECT DISTINCT dia FROM estadisticas WHERE segundos >= 60 ORDER BY dia DESC", null).use { c ->
			generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
		}

	// --- Pronunciación -------------------------------------------------------------------

	fun pronunciaciones(libro: Long? = null, soloActivas: Boolean = false): List<Pronunciacion> {
		val condiciones = mutableListOf<String>()
		if (libro != null) condiciones += "(libro IS NULL OR libro = $libro)" else condiciones += "libro IS NULL"
		if (soloActivas) condiciones += "activo = 1"
		return readableDatabase.query("pronunciacion", null, condiciones.joinToString(" AND "), null, null, null, "id").use { c ->
			generateSequence {
				if (!c.moveToNext()) null else Pronunciacion(
					c.getLong(c.getColumnIndexOrThrow("id")),
					c.getString(c.getColumnIndexOrThrow("buscar")),
					c.getString(c.getColumnIndexOrThrow("reemplazar")),
					c.getInt(c.getColumnIndexOrThrow("regex")) != 0,
					c.getInt(c.getColumnIndexOrThrow("mayusculas")) != 0,
					c.getInt(c.getColumnIndexOrThrow("activo")) != 0,
					c.getColumnIndexOrThrow("libro").let { if (c.isNull(it)) null else c.getLong(it) },
				)
			}.toList()
		}
	}

	fun guardarPronunciacion(p: Pronunciacion): Long {
		val v = ContentValues().apply {
			put("buscar", p.buscar)
			put("reemplazar", p.reemplazar)
			put("regex", if (p.regex) 1 else 0)
			put("mayusculas", if (p.mayusculas) 1 else 0)
			put("activo", if (p.activo) 1 else 0)
			if (p.libro != null) put("libro", p.libro) else putNull("libro")
		}
		val db = writableDatabase
		return if (p.id > 0) {
			db.update("pronunciacion", v, "id = ?", arrayOf(p.id.toString()))
			p.id
		} else db.insert("pronunciacion", null, v)
	}

	fun borrarPronunciacion(id: Long) {
		writableDatabase.delete("pronunciacion", "id = ?", arrayOf(id.toString()))
	}

	// --- Copia de seguridad ------------------------------------------------------------------

	/** Toda la biblioteca en JSON (libros, carpetas, marcadores, pronunciación, estadísticas). */
	fun exportar(): JSONObject {
		val db = readableDatabase
		fun tabla(nombre: String): JSONArray = db.query(nombre, null, null, null, null, null, null).use { c ->
			val filas = JSONArray()
			while (c.moveToNext()) {
				val o = JSONObject()
				for (i in 0 until c.columnCount) {
					when (c.getType(i)) {
						Cursor.FIELD_TYPE_NULL -> o.put(c.getColumnName(i), JSONObject.NULL)
						Cursor.FIELD_TYPE_INTEGER -> o.put(c.getColumnName(i), c.getLong(i))
						Cursor.FIELD_TYPE_FLOAT -> o.put(c.getColumnName(i), c.getDouble(i))
						else -> o.put(c.getColumnName(i), c.getString(i))
					}
				}
				filas.put(o)
			}
			filas
		}
		return JSONObject().apply {
			put("version", VERSION)
			for (t in listOf("carpetas", "libros", "marcadores", "estadisticas", "pronunciacion")) put(t, tabla(t))
		}
	}

	/** Restaura una copia (sustituye la biblioteca actual). */
	fun importar(copia: JSONObject) {
		val db = writableDatabase
		db.setForeignKeyConstraintsEnabled(false)
		db.beginTransaction()
		try {
			for (t in listOf("estadisticas", "marcadores", "pronunciacion", "libros", "carpetas")) db.delete(t, null, null)
			for (t in listOf("carpetas", "libros", "marcadores", "estadisticas", "pronunciacion")) {
				val filas = copia.optJSONArray(t) ?: continue
				for (i in 0 until filas.length()) {
					val o = filas.getJSONObject(i)
					val v = ContentValues()
					for (clave in o.keys()) {
						when (val valor = o.get(clave)) {
							JSONObject.NULL -> v.putNull(clave)
							is Int -> v.put(clave, valor)
							is Long -> v.put(clave, valor)
							is Double -> v.put(clave, valor)
							is Boolean -> v.put(clave, if (valor) 1 else 0)
							else -> v.put(clave, valor.toString())
						}
					}
					db.insertWithOnConflict(t, null, v, SQLiteDatabase.CONFLICT_REPLACE)
				}
			}
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
			db.setForeignKeyConstraintsEnabled(true)
		}
		avisar()
	}
}
