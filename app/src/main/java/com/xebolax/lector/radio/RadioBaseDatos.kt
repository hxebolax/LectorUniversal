package com.xebolax.lector.radio

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.CopyOnWriteArrayList

/** Una emisora favorita, con su carpeta y su orden. */
data class Favorita(val emisora: Emisora, val carpeta: String, val orden: Int, val anadida: Long)

/** Una emisora escuchada: cuándo fue la última vez, cuántas veces y cuánto tiempo en total. */
data class Escuchada(val emisora: Emisora, val ultima: Long, val veces: Int, val segundos: Long)

/** Una canción que sonó en una emisora (lo dice la propia emisora en el directo). */
data class Cancion(val id: Long, val titulo: String, val uuid: String, val emisora: String, val fecha: Long)

/** Una grabación terminada o en marcha. */
data class Grabacion(val id: Long, val archivo: String, val emisora: String, val uuid: String, val inicio: Long, val duracion: Long, val tamano: Long)

/**
 * Un despertador o una grabación programada. dias: lunes = 1, martes = 2,
 * miércoles = 4… domingo = 64; 0 = una sola vez.
 */
data class Programacion(
	val id: Long,
	val tipo: Int,
	val emisora: Emisora,
	val hora: Int,
	val minuto: Int,
	val dias: Int,
	/** Minutos de grabación o de despertador sonando (0 = sin límite). */
	val duracion: Int,
	val activa: Boolean,
) {
	val esDespertador get() = tipo == DESPERTADOR

	companion object {
		const val DESPERTADOR = 0
		const val GRABACION = 1
	}
}

/** Base de datos de la Radio (aparte de la biblioteca, los podcasts y las noticias). */
class RadioBaseDatos private constructor(c: Context) : SQLiteOpenHelper(c, "radio.db", null, 1) {

	companion object {
		@Volatile
		private var instancia: RadioBaseDatos? = null

		fun obtener(c: Context): RadioBaseDatos = instancia ?: synchronized(this) {
			instancia ?: RadioBaseDatos(c.applicationContext).also { instancia = it }
		}

		/** Canciones que se guardan como máximo (las más antiguas se borran). */
		private const val MAX_CANCIONES = 2000
		private const val MAX_HISTORIAL = 300
	}

	fun interface Oyente {
		fun alCambiar()
	}

	private val oyentes = CopyOnWriteArrayList<Oyente>()
	fun suscribir(o: Oyente) = oyentes.addIfAbsent(o)
	fun desuscribir(o: Oyente) = oyentes.remove(o)
	fun avisar() = oyentes.forEach { runCatching { it.alCambiar() } }

	override fun onCreate(db: SQLiteDatabase) {
		db.execSQL("CREATE TABLE favoritas (uuid TEXT PRIMARY KEY, datos TEXT NOT NULL, carpeta TEXT NOT NULL DEFAULT '', orden INTEGER NOT NULL DEFAULT 0, anadida INTEGER NOT NULL DEFAULT 0)")
		db.execSQL("CREATE TABLE carpetas (nombre TEXT PRIMARY KEY COLLATE NOCASE, orden INTEGER NOT NULL DEFAULT 0)")
		db.execSQL("CREATE TABLE historial (uuid TEXT PRIMARY KEY, datos TEXT NOT NULL, ultima INTEGER NOT NULL DEFAULT 0, veces INTEGER NOT NULL DEFAULT 0, segundos INTEGER NOT NULL DEFAULT 0)")
		db.execSQL("CREATE TABLE canciones (id INTEGER PRIMARY KEY AUTOINCREMENT, titulo TEXT NOT NULL, uuid TEXT NOT NULL DEFAULT '', emisora TEXT NOT NULL DEFAULT '', fecha INTEGER NOT NULL DEFAULT 0)")
		db.execSQL("CREATE INDEX canciones_fecha ON canciones(fecha DESC)")
		db.execSQL("CREATE TABLE grabaciones (id INTEGER PRIMARY KEY AUTOINCREMENT, archivo TEXT NOT NULL, emisora TEXT NOT NULL DEFAULT '', uuid TEXT NOT NULL DEFAULT '', inicio INTEGER NOT NULL DEFAULT 0, duracion INTEGER NOT NULL DEFAULT 0, tamano INTEGER NOT NULL DEFAULT 0)")
		db.execSQL(
			"""CREATE TABLE programaciones (
				id INTEGER PRIMARY KEY AUTOINCREMENT,
				tipo INTEGER NOT NULL DEFAULT 0,
				datos TEXT NOT NULL,
				hora INTEGER NOT NULL DEFAULT 7,
				minuto INTEGER NOT NULL DEFAULT 0,
				dias INTEGER NOT NULL DEFAULT 0,
				duracion INTEGER NOT NULL DEFAULT 0,
				activa INTEGER NOT NULL DEFAULT 1
			)"""
		)
	}

	/** Si se instala una versión anterior de la app, la base de datos (más nueva) se deja como está, sin cerrar la app. */
	override fun onDowngrade(db: SQLiteDatabase, antes: Int, ahora: Int) = Unit

	override fun onUpgrade(db: SQLiteDatabase, antes: Int, ahora: Int) = Unit

	private fun <T> lista(sql: String, args: Array<String>? = null, fila: (android.database.Cursor) -> T?): List<T> =
		readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) fila(c)?.let { add(it) } } }

	// --- Favoritas ---------------------------------------------------------------------------

	fun favoritas(): List<Favorita> = lista("SELECT datos, carpeta, orden, anadida FROM favoritas ORDER BY orden, anadida") { c ->
		Emisora.deJson(c.getString(0))?.let { Favorita(it, c.getString(1), c.getInt(2), c.getLong(3)) }
	}

	fun esFavorita(uuid: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM favoritas WHERE uuid = ?", arrayOf(uuid)).use { it.moveToFirst() }

	fun favorita(uuid: String): Favorita? = favoritas().firstOrNull { it.emisora.uuid == uuid }

	/** Añade (al final de su carpeta) o pone al día los datos de una favorita. */
	fun anadirFavorita(e: Emisora, carpeta: String = "") {
		val db = writableDatabase
		if (esFavorita(e.uuid)) {
			db.update("favoritas", ContentValues().apply { put("datos", e.json().toString()) }, "uuid = ?", arrayOf(e.uuid))
		} else {
			val orden = db.rawQuery("SELECT COALESCE(MAX(orden), 0) + 1 FROM favoritas", null).use { if (it.moveToFirst()) it.getInt(0) else 1 }
			db.insert("favoritas", null, ContentValues().apply {
				put("uuid", e.uuid)
				put("datos", e.json().toString())
				put("carpeta", carpeta)
				put("orden", orden)
				put("anadida", System.currentTimeMillis())
			})
		}
		avisar()
	}

	fun quitarFavorita(uuid: String) {
		writableDatabase.delete("favoritas", "uuid = ?", arrayOf(uuid))
		avisar()
	}

	/** Pone al día los datos de las favoritas que siguen en el directorio (votos, dirección, calidad). */
	fun actualizarDatos(emisoras: List<Emisora>) {
		val db = writableDatabase
		val porUuid = emisoras.associateBy { it.uuid }
		// El despertador y las grabaciones programadas guardan su emisora: también la dirección nueva
		val programadas = programaciones().filter { it.emisora.uuid in porUuid }
		db.beginTransaction()
		try {
			for (e in emisoras) {
				db.update("favoritas", ContentValues().apply { put("datos", e.json().toString()) }, "uuid = ?", arrayOf(e.uuid))
				db.update("historial", ContentValues().apply { put("datos", e.json().toString()) }, "uuid = ?", arrayOf(e.uuid))
			}
			for (p in programadas) porUuid[p.emisora.uuid]?.let { e ->
				db.update("programaciones", ContentValues().apply { put("datos", e.json().toString()) }, "id = ?", arrayOf(p.id.toString()))
			}
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		avisar()
	}

	/**
	 * Una emisora propia editada (su id sale de la dirección y puede cambiar):
	 * conserva su carpeta y su orden, y pasan a ella su historial, sus canciones,
	 * sus grabaciones y el despertador o las grabaciones programadas con ella.
	 */
	fun cambiarEmisora(antes: String, e: Emisora) {
		val db = writableDatabase
		val datos = e.json().toString()
		val programadas = programaciones().filter { it.emisora.uuid == antes }
		db.beginTransaction()
		try {
			if (antes != e.uuid) {
				// Si ya había otra con la dirección nueva, se queda la editada (con su sitio)
				if (esFavorita(antes)) db.delete("favoritas", "uuid = ?", arrayOf(e.uuid))
				db.execSQL("UPDATE favoritas SET uuid = ? WHERE uuid = ?", arrayOf(e.uuid, antes))
				db.execSQL("DELETE FROM historial WHERE uuid = ? AND EXISTS (SELECT 1 FROM historial WHERE uuid = ?)", arrayOf(e.uuid, antes))
				db.execSQL("UPDATE historial SET uuid = ? WHERE uuid = ?", arrayOf(e.uuid, antes))
				db.execSQL("UPDATE canciones SET uuid = ? WHERE uuid = ?", arrayOf(e.uuid, antes))
				db.execSQL("UPDATE grabaciones SET uuid = ? WHERE uuid = ?", arrayOf(e.uuid, antes))
			}
			db.update("favoritas", ContentValues().apply { put("datos", datos) }, "uuid = ?", arrayOf(e.uuid))
			db.update("historial", ContentValues().apply { put("datos", datos) }, "uuid = ?", arrayOf(e.uuid))
			for (p in programadas) db.update("programaciones", ContentValues().apply { put("datos", datos) }, "id = ?", arrayOf(p.id.toString()))
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		avisar()
	}

	fun ponerCarpeta(uuid: String, carpeta: String) {
		if (carpeta.isNotBlank()) crearCarpeta(carpeta)
		writableDatabase.update("favoritas", ContentValues().apply { put("carpeta", carpeta) }, "uuid = ?", arrayOf(uuid))
		avisar()
	}

	/** Guarda el orden de las favoritas (los uuid en su nuevo orden). */
	fun ordenarFavoritas(uuids: List<String>) {
		val db = writableDatabase
		db.beginTransaction()
		try {
			uuids.forEachIndexed { i, u -> db.update("favoritas", ContentValues().apply { put("orden", i + 1) }, "uuid = ?", arrayOf(u)) }
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		avisar()
	}

	// --- Carpetas de favoritas -------------------------------------------------------------------

	fun carpetas(): List<String> = lista("SELECT nombre FROM carpetas ORDER BY orden, nombre COLLATE NOCASE") { it.getString(0) }

	/** Crea la carpeta (si ya existe, no hace nada) y devuelve su nombre tal como está guardado. */
	fun crearCarpeta(nombre: String): String {
		val n = nombre.trim()
		carpetas().firstOrNull { it.equals(n, ignoreCase = true) }?.let { return it }
		val orden = readableDatabase.rawQuery("SELECT COALESCE(MAX(orden), 0) + 1 FROM carpetas", null).use { if (it.moveToFirst()) it.getInt(0) else 1 }
		writableDatabase.insert("carpetas", null, ContentValues().apply {
			put("nombre", n)
			put("orden", orden)
		})
		avisar()
		return n
	}

	fun renombrarCarpeta(antes: String, ahora: String) {
		val nuevo = ahora.trim()
		if (nuevo.isBlank()) return
		val db = writableDatabase
		db.beginTransaction()
		try {
			// Si ya hay otra con ese nombre, se juntan
			if (carpetas().any { it.equals(nuevo, true) && !it.equals(antes, true) }) db.delete("carpetas", "nombre = ?", arrayOf(antes))
			else db.update("carpetas", ContentValues().apply { put("nombre", nuevo) }, "nombre = ?", arrayOf(antes))
			db.execSQL("UPDATE favoritas SET carpeta = ? WHERE carpeta = ? COLLATE NOCASE", arrayOf(nuevo, antes))
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		avisar()
	}

	/** Borra la carpeta; sus emisoras quedan sin carpeta o se quitan de favoritas. */
	fun borrarCarpeta(nombre: String, conEmisoras: Boolean) {
		val db = writableDatabase
		db.beginTransaction()
		try {
			if (conEmisoras) db.execSQL("DELETE FROM favoritas WHERE carpeta = ? COLLATE NOCASE", arrayOf(nombre))
			else db.execSQL("UPDATE favoritas SET carpeta = '' WHERE carpeta = ? COLLATE NOCASE", arrayOf(nombre))
			db.delete("carpetas", "nombre = ? COLLATE NOCASE", arrayOf(nombre))
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		avisar()
	}

	fun ordenarCarpetas(nombres: List<String>) {
		val db = writableDatabase
		nombres.forEachIndexed { i, n -> db.update("carpetas", ContentValues().apply { put("orden", i + 1) }, "nombre = ? COLLATE NOCASE", arrayOf(n)) }
		avisar()
	}

	// --- Historial de emisoras ---------------------------------------------------------------------

	fun historial(): List<Escuchada> = lista("SELECT datos, ultima, veces, segundos FROM historial ORDER BY ultima DESC") { c ->
		Emisora.deJson(c.getString(0))?.let { Escuchada(it, c.getLong(1), c.getInt(2), c.getLong(3)) }
	}

	/** Las más escuchadas por ti (por tiempo). */
	fun masEscuchadasPorMi(): List<Escuchada> = historial().sortedByDescending { it.segundos }

	fun apuntarEscuchada(e: Emisora) {
		val db = writableDatabase
		val ahora = System.currentTimeMillis()
		val n = db.update("historial", ContentValues().apply {
			put("datos", e.json().toString())
			put("ultima", ahora)
		}, "uuid = ?", arrayOf(e.uuid))
		if (n == 0) db.insert("historial", null, ContentValues().apply {
			put("uuid", e.uuid)
			put("datos", e.json().toString())
			put("ultima", ahora)
			put("veces", 0)
		})
		db.execSQL("UPDATE historial SET veces = veces + 1 WHERE uuid = ?", arrayOf(e.uuid))
		db.execSQL("DELETE FROM historial WHERE uuid NOT IN (SELECT uuid FROM historial ORDER BY ultima DESC LIMIT $MAX_HISTORIAL)")
		avisar()
	}

	fun sumarTiempo(uuid: String, segundos: Long) {
		if (segundos <= 0) return
		writableDatabase.execSQL("UPDATE historial SET segundos = segundos + ? WHERE uuid = ?", arrayOf<Any>(segundos, uuid))
	}

	fun quitarDelHistorial(uuid: String) {
		writableDatabase.delete("historial", "uuid = ?", arrayOf(uuid))
		avisar()
	}

	fun vaciarHistorial() {
		writableDatabase.delete("historial", null, null)
		avisar()
	}

	/** Una emisora que ya se conoce (favorita o escuchada), por su id. */
	fun conocida(uuid: String): Emisora? = favorita(uuid)?.emisora
		?: lista("SELECT datos FROM historial WHERE uuid = ?", arrayOf(uuid)) { Emisora.deJson(it.getString(0)) }.firstOrNull()

	// --- Canciones -------------------------------------------------------------------------------

	fun contarCanciones(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM canciones", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
	fun canciones(uuid: String? = null): List<Cancion> = lista(
		"SELECT id, titulo, uuid, emisora, fecha FROM canciones" + (if (uuid != null) " WHERE uuid = ?" else "") + " ORDER BY fecha DESC LIMIT 1000",
		uuid?.let { arrayOf(it) },
	) { Cancion(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getLong(4)) }

	/** Apunta la canción que suena (si no es la misma que la última de esa emisora). */
	fun apuntarCancion(titulo: String, e: Emisora) {
		val t = titulo.trim()
		if (t.isBlank()) return
		val ultima = readableDatabase.rawQuery("SELECT titulo FROM canciones WHERE uuid = ? ORDER BY fecha DESC LIMIT 1", arrayOf(e.uuid)).use { if (it.moveToFirst()) it.getString(0) else null }
		if (ultima == t) return
		val db = writableDatabase
		db.insert("canciones", null, ContentValues().apply {
			put("titulo", t)
			put("uuid", e.uuid)
			put("emisora", e.nombre)
			put("fecha", System.currentTimeMillis())
		})
		db.execSQL("DELETE FROM canciones WHERE id NOT IN (SELECT id FROM canciones ORDER BY fecha DESC LIMIT $MAX_CANCIONES)")
		avisar()
	}

	fun borrarCancion(id: Long) {
		writableDatabase.delete("canciones", "id = ?", arrayOf(id.toString()))
		avisar()
	}

	fun vaciarCanciones() {
		writableDatabase.delete("canciones", null, null)
		avisar()
	}

	// --- Grabaciones -------------------------------------------------------------------------------

	fun grabaciones(): List<Grabacion> = lista("SELECT id, archivo, emisora, uuid, inicio, duracion, tamano FROM grabaciones ORDER BY inicio DESC") {
		Grabacion(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getLong(4), it.getLong(5), it.getLong(6))
	}

	fun nuevaGrabacion(archivo: String, e: Emisora): Long = writableDatabase.insert("grabaciones", null, ContentValues().apply {
		put("archivo", archivo)
		put("emisora", e.nombre)
		put("uuid", e.uuid)
		put("inicio", System.currentTimeMillis())
	}).also { avisar() }

	fun terminarGrabacion(id: Long, duracion: Long, tamano: Long) {
		writableDatabase.update("grabaciones", ContentValues().apply {
			put("duracion", duracion)
			put("tamano", tamano)
		}, "id = ?", arrayOf(id.toString()))
		avisar()
	}

	fun renombrarGrabacion(id: Long, archivo: String, emisora: String) {
		writableDatabase.update("grabaciones", ContentValues().apply {
			put("archivo", archivo)
			put("emisora", emisora)
		}, "id = ?", arrayOf(id.toString()))
		avisar()
	}

	fun borrarGrabacion(id: Long) {
		writableDatabase.delete("grabaciones", "id = ?", arrayOf(id.toString()))
		avisar()
	}

	// --- Despertadores y grabaciones programadas ----------------------------------------------------

	fun programaciones(): List<Programacion> = lista("SELECT id, tipo, datos, hora, minuto, dias, duracion, activa FROM programaciones ORDER BY hora, minuto") { c ->
		Emisora.deJson(c.getString(2))?.let { Programacion(c.getLong(0), c.getInt(1), it, c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7) == 1) }
	}

	fun programacion(id: Long): Programacion? = programaciones().firstOrNull { it.id == id }

	/** Guarda (id 0 = nueva) y devuelve su id. */
	fun guardarProgramacion(p: Programacion): Long {
		val v = ContentValues().apply {
			put("tipo", p.tipo)
			put("datos", p.emisora.json().toString())
			put("hora", p.hora)
			put("minuto", p.minuto)
			put("dias", p.dias)
			put("duracion", p.duracion)
			put("activa", if (p.activa) 1 else 0)
		}
		val id = if (p.id > 0) {
			writableDatabase.update("programaciones", v, "id = ?", arrayOf(p.id.toString()))
			p.id
		} else writableDatabase.insert("programaciones", null, v)
		avisar()
		return id
	}

	fun activarProgramacion(id: Long, activa: Boolean) {
		writableDatabase.update("programaciones", ContentValues().apply { put("activa", if (activa) 1 else 0) }, "id = ?", arrayOf(id.toString()))
		avisar()
	}

	fun borrarProgramacion(id: Long) {
		writableDatabase.delete("programaciones", "id = ?", arrayOf(id.toString()))
		avisar()
	}
}
