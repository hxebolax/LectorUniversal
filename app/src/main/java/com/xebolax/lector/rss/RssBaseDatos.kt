package com.xebolax.lector.rss

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.CopyOnWriteArrayList

/** Una suscripción (RSS, Atom, RDF o JSON Feed). */
data class Fuente(
	val id: Long,
	val titulo: String,
	val url: String,
	/** Página web de la fuente. */
	val sitio: String,
	/** Categoría (carpeta) a la que pertenece; vacía = sin categoría. */
	val categoria: String,
	val etag: String,
	val modificado: String,
	/** Última actualización correcta (ms). */
	val ultima: Long,
	/** Último error al actualizar (vacío si fue bien). */
	val error: String,
	/** Avisar en la notificación de artículos nuevos. */
	val avisar: Boolean,
	/** Descargar siempre el artículo completo de la web (si el RSS solo trae un resumen). */
	val textoCompleto: Boolean,
	/** Abrir los artículos en el navegador en lugar de en la app. */
	val enNavegador: Boolean,
	/** Palabras que ocultan un artículo si aparecen en su título o resumen (separadas por comas). */
	val silenciar: String,
	val pausada: Boolean,
	/** Idioma que declara la fuente (vacío si no lo dice): la voz lee sus artículos en ese idioma. */
	val idioma: String = "",
	/** No guardar los artículos que ya llegaron por otra fuente (mismo titular o enlace). */
	val sinDuplicados: Boolean = false,
	/** Si no está vacío, solo se guardan los artículos con alguna de estas palabras (separadas por comas). */
	val soloCon: String = "",
	/** Artículos sin leer (se calcula al consultar). */
	val sinLeer: Int = 0,
	val total: Int = 0,
)

/** Un artículo (o episodio de podcast). */
data class Articulo(
	val id: Long,
	val fuente: Long,
	val guid: String,
	val titulo: String,
	val enlace: String,
	val autor: String,
	/** Fecha de publicación (ms). */
	val fecha: Long,
	val resumen: String,
	/** HTML del artículo (el del RSS o el descargado de la web). */
	val contenido: String,
	/** ¿El contenido es el artículo completo de la web? */
	val completo: Boolean,
	/** Audio o vídeo adjunto (podcasts). */
	val adjunto: String,
	val tipoAdjunto: String,
	val duracion: String,
	val leido: Boolean,
	val favorito: Boolean,
	val masTarde: Boolean,
	/** Id del libro si se guardó en la biblioteca (0 si no). */
	val enBiblioteca: Long,
	/** Por dónde se quedó escuchando el episodio (ms). */
	val posicionAudio: Long,
	/** Nombre de la fuente (se rellena al consultar). */
	val nombreFuente: String = "",
	/** Palabras del texto (para decir cuánto se tarda en leerlo). */
	val palabras: Int = 0,
	/** Idioma de su fuente (se rellena al consultar). */
	val idiomaFuente: String = "",
) {
	val esPodcast: Boolean get() = adjunto.isNotBlank() && (tipoAdjunto.startsWith("audio") || tipoAdjunto.startsWith("video") ||
		adjunto.substringBefore('?').lowercase().let { it.endsWith(".mp3") || it.endsWith(".m4a") || it.endsWith(".ogg") || it.endsWith(".opus") })
}

/**
 * Base de datos de la sección de noticias, separada de la biblioteca: los
 * artículos no van a la biblioteca salvo que se pida expresamente.
 */
class RssBaseDatos private constructor(c: Context) : SQLiteOpenHelper(c, "rss.db", null, 5) {

	companion object {
		@Volatile
		private var instancia: RssBaseDatos? = null

		fun obtener(c: Context): RssBaseDatos = instancia ?: synchronized(this) {
			instancia ?: RssBaseDatos(c.applicationContext).also { instancia = it }
		}
	}

	fun interface Oyente {
		fun alCambiar()
	}

	private val oyentes = CopyOnWriteArrayList<Oyente>()
	fun suscribir(o: Oyente) = oyentes.addIfAbsent(o)
	fun desuscribir(o: Oyente) = oyentes.remove(o)
	fun avisar() = oyentes.forEach { runCatching { it.alCambiar() } }

	override fun onCreate(db: SQLiteDatabase) {
		db.execSQL(
			"""CREATE TABLE fuentes (
				id INTEGER PRIMARY KEY AUTOINCREMENT,
				titulo TEXT NOT NULL DEFAULT '',
				url TEXT NOT NULL UNIQUE,
				sitio TEXT NOT NULL DEFAULT '',
				categoria TEXT NOT NULL DEFAULT '',
				etag TEXT NOT NULL DEFAULT '',
				modificado TEXT NOT NULL DEFAULT '',
				ultima INTEGER NOT NULL DEFAULT 0,
				error TEXT NOT NULL DEFAULT '',
				avisar INTEGER NOT NULL DEFAULT 0,
				texto_completo INTEGER NOT NULL DEFAULT 0,
				en_navegador INTEGER NOT NULL DEFAULT 0,
				silenciar TEXT NOT NULL DEFAULT '',
				pausada INTEGER NOT NULL DEFAULT 0,
				orden INTEGER NOT NULL DEFAULT 0,
				remoto TEXT NOT NULL DEFAULT ''
			)"""
		)
		db.execSQL(
			"""CREATE TABLE articulos (
				id INTEGER PRIMARY KEY AUTOINCREMENT,
				fuente INTEGER NOT NULL,
				guid TEXT NOT NULL,
				titulo TEXT NOT NULL DEFAULT '',
				enlace TEXT NOT NULL DEFAULT '',
				autor TEXT NOT NULL DEFAULT '',
				fecha INTEGER NOT NULL DEFAULT 0,
				resumen TEXT NOT NULL DEFAULT '',
				contenido TEXT NOT NULL DEFAULT '',
				completo INTEGER NOT NULL DEFAULT 0,
				adjunto TEXT NOT NULL DEFAULT '',
				tipo_adjunto TEXT NOT NULL DEFAULT '',
				duracion TEXT NOT NULL DEFAULT '',
				leido INTEGER NOT NULL DEFAULT 0,
				favorito INTEGER NOT NULL DEFAULT 0,
				mas_tarde INTEGER NOT NULL DEFAULT 0,
				en_biblioteca INTEGER NOT NULL DEFAULT 0,
				posicion_audio INTEGER NOT NULL DEFAULT 0,
				recibido INTEGER NOT NULL DEFAULT 0,
				remoto TEXT NOT NULL DEFAULT '',
				cambiado INTEGER NOT NULL DEFAULT 0,
				papelera INTEGER NOT NULL DEFAULT 0,
				UNIQUE(fuente, guid)
			)"""
		)
		db.execSQL("CREATE INDEX articulos_fecha ON articulos(fecha DESC)")
		db.execSQL("CREATE INDEX articulos_fuente ON articulos(fuente, leido)")
		crearCarpetas(db)
		columnas5(db)
	}

	private fun tieneColumna(db: SQLiteDatabase, tabla: String, columna: String): Boolean =
		db.rawQuery("PRAGMA table_info($tabla)", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(1) else null }.any { it == columna } }

	/** Carpetas (pueden estar vacías) con su orden, y el orden de las fuentes dentro de cada carpeta. */
	private fun crearCarpetas(db: SQLiteDatabase) {
		if (!tieneColumna(db, "fuentes", "orden")) db.execSQL("ALTER TABLE fuentes ADD COLUMN orden INTEGER NOT NULL DEFAULT 0")
		db.execSQL("CREATE TABLE IF NOT EXISTS carpetas (nombre TEXT PRIMARY KEY COLLATE NOCASE, orden INTEGER NOT NULL DEFAULT 0)")
		db.execSQL("INSERT OR IGNORE INTO carpetas(nombre) SELECT DISTINCT categoria FROM fuentes WHERE categoria != ''")
	}

	/** Idioma, duplicados y «solo con» de cada fuente; palabras de cada artículo. */
	private fun columnas5(db: SQLiteDatabase) {
		if (!tieneColumna(db, "fuentes", "idioma")) db.execSQL("ALTER TABLE fuentes ADD COLUMN idioma TEXT NOT NULL DEFAULT ''")
		if (!tieneColumna(db, "fuentes", "sin_duplicados")) db.execSQL("ALTER TABLE fuentes ADD COLUMN sin_duplicados INTEGER NOT NULL DEFAULT 0")
		if (!tieneColumna(db, "fuentes", "solo_con")) db.execSQL("ALTER TABLE fuentes ADD COLUMN solo_con TEXT NOT NULL DEFAULT ''")
		if (!tieneColumna(db, "articulos", "palabras")) db.execSQL("ALTER TABLE articulos ADD COLUMN palabras INTEGER NOT NULL DEFAULT 0")
	}

	override fun onUpgrade(db: SQLiteDatabase, antes: Int, ahora: Int) {
		// Papelera: 1 = en la papelera; 2 = borrado (se queda oculto para que no vuelva al actualizar)
		if (antes < 3) db.execSQL("ALTER TABLE articulos ADD COLUMN papelera INTEGER NOT NULL DEFAULT 0")
		if (antes < 2) {
			// Sincronización con un servidor (FreshRSS, Miniflux, Nextcloud)
			db.execSQL("ALTER TABLE fuentes ADD COLUMN remoto TEXT NOT NULL DEFAULT ''")
			db.execSQL("ALTER TABLE articulos ADD COLUMN remoto TEXT NOT NULL DEFAULT ''")
			db.execSQL("ALTER TABLE articulos ADD COLUMN cambiado INTEGER NOT NULL DEFAULT 0")
		}
		if (antes < 4) crearCarpetas(db)
		if (antes < 5) columnas5(db)
	}

	// --- Sincronización ----------------------------------------------------------------

	/** Id en el servidor de cada fuente (url → id remoto) y al revés. */
	fun remotosDeFuentes(): Map<Long, String> = readableDatabase.rawQuery("SELECT id, remoto FROM fuentes WHERE remoto != ''", null).use { c ->
		generateSequence { if (c.moveToNext()) c.getLong(0) to c.getString(1) else null }.toMap()
	}

	fun ponerRemotoFuente(id: Long, remoto: String) {
		writableDatabase.update("fuentes", ContentValues().apply { put("remoto", remoto) }, "id = ?", arrayOf(id.toString()))
	}

	fun ponerCategoria(id: Long, categoria: String) {
		crearCarpeta(categoria)
		writableDatabase.update("fuentes", ContentValues().apply { put("categoria", categoria) }, "id = ?", arrayOf(id.toString()))
	}

	/** Artículos con cambios (leído, favorito) que aún no se han enviado al servidor: (id remoto, leído, favorito). */
	fun cambiosPendientes(): List<Triple<String, Boolean, Boolean>> =
		readableDatabase.rawQuery("SELECT remoto, leido, favorito FROM articulos WHERE cambiado = 1 AND remoto != ''", null).use { c ->
			generateSequence { if (c.moveToNext()) Triple(c.getString(0), c.getInt(1) == 1, c.getInt(2) == 1) else null }.toList()
		}

	fun cambiosEnviados() = writableDatabase.execSQL("UPDATE articulos SET cambiado = 0 WHERE cambiado = 1")

	/** Ids remotos de los artículos sin leer y de los favoritos de aquí (para comparar con el servidor). */
	fun remotosSinLeer(): Set<String> = readableDatabase.rawQuery("SELECT remoto FROM articulos WHERE remoto != '' AND leido = 0 AND cambiado = 0", null).use { c ->
		generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
	}

	fun remotosFavoritos(): Set<String> = readableDatabase.rawQuery("SELECT remoto FROM articulos WHERE remoto != '' AND favorito = 1 AND cambiado = 0", null).use { c ->
		generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
	}

	/** Estado que viene del servidor (sin marcarlo como cambio pendiente). */
	fun estadoRemoto(remotos: Collection<String>, leido: Boolean? = null, favorito: Boolean? = null) {
		if (remotos.isEmpty()) return
		val db = writableDatabase
		db.beginTransaction()
		try {
			for (r in remotos) {
				val v = ContentValues()
				if (leido != null) v.put("leido", if (leido) 1 else 0)
				if (favorito != null) v.put("favorito", if (favorito) 1 else 0)
				db.update("articulos", v, "remoto = ? AND cambiado = 0", arrayOf(r))
			}
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
	}

	/** Artículos que vienen del servidor: los nuevos se guardan con su id remoto; los que ya estaban, solo su estado. */
	fun anadirRemotos(fuente: Long, lista: List<Pair<RssLector.Elemento, Triple<String, Boolean, Boolean>>>): Int {
		var nuevos = 0
		val db = writableDatabase
		db.beginTransaction()
		try {
			val ahora = System.currentTimeMillis()
			for ((e, estado) in lista) {
				val (remoto, leido, favorito) = estado
				if (e.enlace.isNotBlank() && db.update("articulos", ContentValues().apply {
						put("remoto", remoto)
						put("leido", if (leido) 1 else 0)
						put("favorito", if (favorito) 1 else 0)
					}, "fuente = ? AND remoto = '' AND enlace = ?", arrayOf(fuente.toString(), e.enlace)) > 0) continue
				val id = db.insertWithOnConflict("articulos", null, ContentValues().apply {
					put("fuente", fuente)
					put("guid", "r:$remoto")
					put("titulo", e.titulo)
					put("enlace", e.enlace)
					put("autor", e.autor)
					put("fecha", if (e.fecha > 0) e.fecha else ahora)
					put("resumen", e.resumen)
					put("contenido", e.contenido)
					put("adjunto", e.adjunto)
					put("tipo_adjunto", e.tipoAdjunto)
					put("leido", if (leido) 1 else 0)
					put("favorito", if (favorito) 1 else 0)
					put("recibido", ahora)
					put("remoto", remoto)
					put("palabras", palabrasDe(e.contenido.ifBlank { e.resumen }))
				}, SQLiteDatabase.CONFLICT_IGNORE)
				if (id != -1L) nuevos++
				else db.update("articulos", ContentValues().apply {
					put("leido", if (leido) 1 else 0)
					put("favorito", if (favorito) 1 else 0)
				}, "remoto = ? AND cambiado = 0", arrayOf(remoto))
			}
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		avisar()
		return nuevos
	}

	// --- Fuentes -------------------------------------------------------------------------

	private fun Cursor.fuente(sinLeer: Int = 0, total: Int = 0) = Fuente(
		id = getLong(getColumnIndexOrThrow("id")),
		titulo = getString(getColumnIndexOrThrow("titulo")),
		url = getString(getColumnIndexOrThrow("url")),
		sitio = getString(getColumnIndexOrThrow("sitio")),
		categoria = getString(getColumnIndexOrThrow("categoria")),
		etag = getString(getColumnIndexOrThrow("etag")),
		modificado = getString(getColumnIndexOrThrow("modificado")),
		ultima = getLong(getColumnIndexOrThrow("ultima")),
		error = getString(getColumnIndexOrThrow("error")),
		avisar = getInt(getColumnIndexOrThrow("avisar")) == 1,
		textoCompleto = getInt(getColumnIndexOrThrow("texto_completo")) == 1,
		enNavegador = getInt(getColumnIndexOrThrow("en_navegador")) == 1,
		silenciar = getString(getColumnIndexOrThrow("silenciar")),
		pausada = getInt(getColumnIndexOrThrow("pausada")) == 1,
		idioma = getString(getColumnIndexOrThrow("idioma")),
		sinDuplicados = getInt(getColumnIndexOrThrow("sin_duplicados")) == 1,
		soloCon = getString(getColumnIndexOrThrow("solo_con")),
		sinLeer = sinLeer,
		total = total,
	)

	/** Todas las fuentes, con sus artículos sin leer, por categoría y nombre. */
	fun fuentes(): List<Fuente> {
		val cuentas = HashMap<Long, Pair<Int, Int>>()
		readableDatabase.rawQuery("SELECT fuente, SUM(leido = 0), COUNT(*) FROM articulos WHERE papelera = 0 GROUP BY fuente", null).use { c ->
			while (c.moveToNext()) cuentas[c.getLong(0)] = c.getInt(1) to c.getInt(2)
		}
		return readableDatabase.query("fuentes", null, null, null, null, null, "categoria COLLATE NOCASE, orden, titulo COLLATE NOCASE").use { c ->
			generateSequence { if (c.moveToNext()) c else null }.map {
				val id = it.getLong(it.getColumnIndexOrThrow("id"))
				val (sin, total) = cuentas[id] ?: (0 to 0)
				it.fuente(sin, total)
			}.toList()
		}
	}

	fun fuente(id: Long): Fuente? = readableDatabase.query("fuentes", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
		if (c.moveToFirst()) c.fuente() else null
	}

	fun fuentePorUrl(url: String): Fuente? = readableDatabase.query("fuentes", null, "url = ?", arrayOf(url), null, null, null).use { c ->
		if (c.moveToFirst()) c.fuente() else null
	}

	/** Las carpetas, en el orden elegido (las nuevas, al final; sin orden, por nombre). */
	fun categorias(): List<String> = readableDatabase.rawQuery("SELECT nombre FROM carpetas ORDER BY orden, nombre COLLATE NOCASE", null).use { c ->
		generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
	}

	/** Crea la carpeta si no existe (al final). Devuelve el nombre tal como quedó guardado. */
	fun crearCarpeta(nombre: String): String {
		val n = nombre.trim()
		if (n.isEmpty()) return n
		categorias().firstOrNull { it.equals(n, ignoreCase = true) }?.let { return it }
		writableDatabase.execSQL("INSERT OR IGNORE INTO carpetas(nombre, orden) VALUES (?, (SELECT COALESCE(MAX(orden), 0) + 1 FROM carpetas))", arrayOf(n))
		avisar()
		return n
	}

	/** Borra la carpeta. conFuentes: también sus fuentes (y sus artículos); si no, las fuentes quedan sin carpeta. Devuelve las fuentes borradas. */
	fun borrarCarpeta(nombre: String, conFuentes: Boolean): List<Fuente> {
		val suyas = fuentes().filter { it.categoria.equals(nombre, ignoreCase = true) }
		if (conFuentes) suyas.forEach {
			writableDatabase.delete("articulos", "fuente = ?", arrayOf(it.id.toString()))
			writableDatabase.delete("fuentes", "id = ?", arrayOf(it.id.toString()))
		} else writableDatabase.update("fuentes", ContentValues().apply { put("categoria", "") }, "categoria = ? COLLATE NOCASE", arrayOf(nombre))
		writableDatabase.delete("carpetas", "nombre = ?", arrayOf(nombre))
		avisar()
		return if (conFuentes) suyas else emptyList()
	}

	/** Guarda el orden de las carpetas. */
	fun ordenarCarpetas(nombres: List<String>) {
		val db = writableDatabase
		db.beginTransaction()
		try {
			nombres.forEachIndexed { i, n -> db.update("carpetas", ContentValues().apply { put("orden", i + 1) }, "nombre = ?", arrayOf(n)) }
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		avisar()
	}

	/** Guarda el orden de unas fuentes (las de una carpeta, o las que no tienen). */
	fun ordenarFuentes(ids: List<Long>) {
		val db = writableDatabase
		db.beginTransaction()
		try {
			ids.forEachIndexed { i, id -> db.update("fuentes", ContentValues().apply { put("orden", i + 1) }, "id = ?", arrayOf(id.toString())) }
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		avisar()
	}

	/** Añade una fuente (si ya estaba, devuelve la existente). */
	fun anadirFuente(url: String, titulo: String, sitio: String = "", categoria: String = ""): Long {
		fuentePorUrl(url)?.let { return it.id }
		val carpeta = crearCarpeta(categoria)
		// Al final de su carpeta (si la carpeta ya está ordenada a mano)
		val orden = readableDatabase.rawQuery("SELECT COALESCE(MAX(orden), 0) FROM fuentes WHERE categoria = ? COLLATE NOCASE", arrayOf(carpeta)).use { c ->
			if (c.moveToFirst() && c.getInt(0) > 0) c.getInt(0) + 1 else 0
		}
		val id = writableDatabase.insert("fuentes", null, ContentValues().apply {
			put("url", url)
			put("titulo", titulo)
			put("sitio", sitio)
			put("categoria", carpeta)
			put("orden", orden)
		})
		avisar()
		return id
	}

	fun editarFuente(f: Fuente) {
		crearCarpeta(f.categoria)
		val urlAntes = fuente(f.id)?.url
		writableDatabase.update("fuentes", ContentValues().apply {
			if (urlAntes != null && urlAntes != f.url) {
				put("etag", "")
				put("modificado", "")
				put("error", "")
			}
			put("sin_duplicados", if (f.sinDuplicados) 1 else 0)
			put("solo_con", f.soloCon)
			put("titulo", f.titulo)
			put("url", f.url)
			put("categoria", f.categoria)
			put("avisar", if (f.avisar) 1 else 0)
			put("texto_completo", if (f.textoCompleto) 1 else 0)
			put("en_navegador", if (f.enNavegador) 1 else 0)
			put("silenciar", f.silenciar)
			put("pausada", if (f.pausada) 1 else 0)
		}, "id = ?", arrayOf(f.id.toString()))
		avisar()
	}

	/** Cambia el nombre de una carpeta (si ya existe otra con ese nombre, se juntan). */
	fun renombrarCategoria(antes: String, despues: String) {
		val nuevo = despues.trim()
		if (nuevo.isEmpty() || nuevo == antes) return
		val existe = categorias().firstOrNull { it.equals(nuevo, ignoreCase = true) && !it.equals(antes, ignoreCase = true) }
		if (existe != null) writableDatabase.delete("carpetas", "nombre = ?", arrayOf(antes))
		else writableDatabase.update("carpetas", ContentValues().apply { put("nombre", nuevo) }, "nombre = ?", arrayOf(antes))
		writableDatabase.update("fuentes", ContentValues().apply { put("categoria", existe ?: nuevo) }, "categoria = ? COLLATE NOCASE", arrayOf(antes))
		avisar()
	}

	fun borrarFuente(id: Long) {
		writableDatabase.delete("articulos", "fuente = ?", arrayOf(id.toString()))
		writableDatabase.delete("fuentes", "id = ?", arrayOf(id.toString()))
		avisar()
	}

	/** Resultado de una actualización: cabeceras para la próxima, título y sitio si se conocen, o el error. */
	fun guardarActualizacion(id: Long, etag: String?, modificado: String?, titulo: String?, sitio: String?, error: String, idioma: String? = null) {
		writableDatabase.update("fuentes", ContentValues().apply {
			if (!idioma.isNullOrBlank()) put("idioma", idioma)
			if (etag != null) put("etag", etag)
			if (modificado != null) put("modificado", modificado)
			if (!titulo.isNullOrBlank()) put("titulo", titulo)
			if (!sitio.isNullOrBlank()) put("sitio", sitio)
			put("error", error)
			if (error.isEmpty()) put("ultima", System.currentTimeMillis())
		}, "id = ?", arrayOf(id.toString()))
	}

	// --- Artículos -----------------------------------------------------------------------

	private val SELECT = "SELECT a.*, f.titulo AS nombre_fuente, f.idioma AS idioma_fuente FROM articulos a JOIN fuentes f ON f.id = a.fuente"

	private fun Cursor.articulo() = Articulo(
		id = getLong(getColumnIndexOrThrow("id")),
		fuente = getLong(getColumnIndexOrThrow("fuente")),
		guid = getString(getColumnIndexOrThrow("guid")),
		titulo = getString(getColumnIndexOrThrow("titulo")),
		enlace = getString(getColumnIndexOrThrow("enlace")),
		autor = getString(getColumnIndexOrThrow("autor")),
		fecha = getLong(getColumnIndexOrThrow("fecha")),
		resumen = getString(getColumnIndexOrThrow("resumen")),
		contenido = getString(getColumnIndexOrThrow("contenido")),
		completo = getInt(getColumnIndexOrThrow("completo")) == 1,
		adjunto = getString(getColumnIndexOrThrow("adjunto")),
		tipoAdjunto = getString(getColumnIndexOrThrow("tipo_adjunto")),
		duracion = getString(getColumnIndexOrThrow("duracion")),
		leido = getInt(getColumnIndexOrThrow("leido")) == 1,
		favorito = getInt(getColumnIndexOrThrow("favorito")) == 1,
		masTarde = getInt(getColumnIndexOrThrow("mas_tarde")) == 1,
		enBiblioteca = getLong(getColumnIndexOrThrow("en_biblioteca")),
		posicionAudio = getLong(getColumnIndexOrThrow("posicion_audio")),
		nombreFuente = getColumnIndex("nombre_fuente").takeIf { it >= 0 }?.let { getString(it) }.orEmpty(),
		palabras = getInt(getColumnIndexOrThrow("palabras")),
		idiomaFuente = getColumnIndex("idioma_fuente").takeIf { it >= 0 }?.let { getString(it) }.orEmpty(),
	)

	// Lo que está en la papelera no sale en ninguna lista (salvo en la de la papelera)
	private fun consulta(donde: String, args: Array<String>, orden: String, limite: Int = 1000): List<Articulo> =
		readableDatabase.rawQuery("$SELECT WHERE ${if (donde.contains("papelera")) donde else "($donde) AND a.papelera = 0"} ORDER BY $orden LIMIT $limite", args).use { c ->
			generateSequence { if (c.moveToNext()) c.articulo() else null }.toList()
		}

	private fun orden(antiguosPrimero: Boolean) = if (antiguosPrimero) "a.fecha ASC, a.id ASC" else "a.fecha DESC, a.id DESC"

	fun sinLeer(antiguosPrimero: Boolean = false, categoria: String? = null): List<Articulo> =
		if (categoria == null) consulta("a.leido = 0", emptyArray(), orden(antiguosPrimero))
		else consulta("a.leido = 0 AND f.categoria = ?", arrayOf(categoria), orden(antiguosPrimero))

	fun deFuente(fuente: Long, soloSinLeer: Boolean, antiguosPrimero: Boolean = false): List<Articulo> =
		consulta(if (soloSinLeer) "a.fuente = ? AND a.leido = 0" else "a.fuente = ?", arrayOf(fuente.toString()), orden(antiguosPrimero))

	fun deCategoria(categoria: String, soloSinLeer: Boolean, antiguosPrimero: Boolean = false): List<Articulo> =
		consulta(if (soloSinLeer) "f.categoria = ? AND a.leido = 0" else "f.categoria = ?", arrayOf(categoria), orden(antiguosPrimero))

	fun favoritos(): List<Articulo> = consulta("a.favorito = 1", emptyArray(), orden(false))

	fun masTarde(): List<Articulo> = consulta("a.mas_tarde = 1", emptyArray(), "a.fecha ASC")

	fun podcasts(): List<Articulo> = consulta("a.adjunto != ''", emptyArray(), orden(false)).filter { it.esPodcast }

	fun buscar(texto: String): List<Articulo> {
		val t = "%" + texto.trim().replace("%", "") + "%"
		return consulta("(a.titulo LIKE ? OR a.resumen LIKE ? OR a.autor LIKE ?)", arrayOf(t, t, t), orden(false), 500)
	}

	fun articulo(id: Long): Articulo? = consulta("a.id = ? AND a.papelera >= 0", arrayOf(id.toString()), "a.id").firstOrNull()

	/** Los de hoy (sin leer o no), todos los recientes o los ya leídos (para «Ver» en Sin leer). */
	fun deHoy(categoria: String? = null): List<Articulo> {
		val hoy = java.util.Calendar.getInstance().apply {
			set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
		}.timeInMillis
		return if (categoria == null) consulta("a.fecha >= ?", arrayOf(hoy.toString()), orden(false))
		else consulta("a.fecha >= ? AND f.categoria = ?", arrayOf(hoy.toString(), categoria), orden(false))
	}

	fun recientes(categoria: String? = null): List<Articulo> =
		if (categoria == null) consulta("1", emptyArray(), orden(false), 400) else consulta("f.categoria = ?", arrayOf(categoria), orden(false), 400)

	fun leidos(categoria: String? = null): List<Articulo> =
		if (categoria == null) consulta("a.leido = 1", emptyArray(), orden(false), 300) else consulta("a.leido = 1 AND f.categoria = ?", arrayOf(categoria), orden(false), 300)

	/** Recibidos en esta actualización (para las reglas y alertas). */
	fun recibidosDesde(ms: Long): List<Articulo> = consulta("a.recibido >= ?", arrayOf(ms.toString()), orden(false), 2000)

	// --- Papelera ----------------------------------------------------------------------

	fun papelera(): List<Articulo> = consulta("a.papelera = 1", emptyArray(), orden(false))

	fun marcarPapelera(ids: Collection<Long>, si: Boolean) = cambiar(ids, ContentValues().apply { put("papelera", if (si) 1 else 0) })

	/** Vacía la papelera: los artículos quedan ocultos para siempre (si se borraran, volverían al actualizar). */
	fun vaciarPapelera() {
		writableDatabase.execSQL("UPDATE articulos SET papelera = 2, contenido = '', resumen = '' WHERE papelera = 1")
		avisar()
	}

	fun contarSinLeer(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM articulos WHERE leido = 0 AND papelera = 0", null).use { c ->
		if (c.moveToFirst()) c.getInt(0) else 0
	}

	/** Guarda los artículos nuevos (los que ya estaban no se tocan). Devuelve los que eran nuevos. */
	fun anadirArticulos(fuente: Long, lista: List<RssLector.Elemento>, leidos: Boolean = false): List<RssLector.Elemento> {
		val nuevos = ArrayList<RssLector.Elemento>()
		val db = writableDatabase
		db.beginTransaction()
		try {
			val ahora = System.currentTimeMillis()
			for (e in lista) {
				val id = db.insertWithOnConflict("articulos", null, ContentValues().apply {
					put("fuente", fuente)
					put("guid", e.guid)
					put("titulo", e.titulo)
					put("enlace", e.enlace)
					put("autor", e.autor)
					put("fecha", if (e.fecha > 0) e.fecha else ahora)
					put("resumen", e.resumen)
					put("contenido", e.contenido)
					put("adjunto", e.adjunto)
					put("tipo_adjunto", e.tipoAdjunto)
					put("duracion", e.duracion)
					put("palabras", palabrasDe(e.contenido.ifBlank { e.resumen }))
					put("leido", if (leidos) 1 else 0)
					put("recibido", ahora)
				}, SQLiteDatabase.CONFLICT_IGNORE)
				if (id != -1L) nuevos += e
			}
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		if (nuevos.isNotEmpty()) avisar()
		return nuevos
	}

	private fun palabrasDe(html: String): Int =
		if (html.isBlank()) 0 else com.xebolax.lector.modelo.Textos.palabras(if (html.contains('<')) org.jsoup.Jsoup.parse(html).text() else html)

	/** Id del artículo de una fuente por su guid (0 si no está). */
	fun idArticulo(fuente: Long, guid: String): Long =
		readableDatabase.rawQuery("SELECT id FROM articulos WHERE fuente = ? AND guid = ?", arrayOf(fuente.toString(), guid)).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

	/** Titulares y enlaces que llegaron por otras fuentes en los últimos días (para no guardar duplicados). */
	fun recibidosDeOtras(fuente: Long, dias: Int = 3): Pair<List<String>, Set<String>> {
		val desde = System.currentTimeMillis() - dias * 86_400_000L
		val titulos = ArrayList<String>()
		val enlaces = HashSet<String>()
		readableDatabase.rawQuery("SELECT titulo, enlace FROM articulos WHERE fuente != ? AND recibido >= ?", arrayOf(fuente.toString(), desde.toString())).use { c ->
			while (c.moveToNext()) {
				titulos += c.getString(0)
				if (c.getString(1).isNotBlank()) enlaces += c.getString(1)
			}
		}
		return titulos to enlaces
	}

	private fun cambiar(ids: Collection<Long>, valores: ContentValues) {
		if (ids.isEmpty()) return
		val db = writableDatabase
		db.beginTransaction()
		try {
			for (trozo in ids.chunked(400)) {
				db.update("articulos", valores, "id IN (${trozo.joinToString(",")})", null)
			}
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		avisar()
	}

	// Los cambios de los artículos del servidor quedan pendientes de enviar (cambiado = 1)
	fun marcarLeidos(ids: Collection<Long>, leido: Boolean = true) = cambiar(ids, ContentValues().apply {
		put("leido", if (leido) 1 else 0)
		put("cambiado", 1)
	})
	fun marcarFavorito(id: Long, si: Boolean) = cambiar(listOf(id), ContentValues().apply {
		put("favorito", if (si) 1 else 0)
		put("cambiado", 1)
	})
	fun marcarMasTarde(id: Long, si: Boolean) = cambiar(listOf(id), ContentValues().apply { put("mas_tarde", if (si) 1 else 0) })
	fun guardarPosicionAudio(id: Long, ms: Long) {
		writableDatabase.update("articulos", ContentValues().apply { put("posicion_audio", ms) }, "id = ?", arrayOf(id.toString()))
	}
	fun marcarEnBiblioteca(id: Long, libro: Long) = cambiar(listOf(id), ContentValues().apply { put("en_biblioteca", libro) })

	/** El artículo completo descargado de la web. */
	fun remotoDe(id: Long): String = readableDatabase.rawQuery("SELECT remoto FROM articulos WHERE id = ?", arrayOf(id.toString())).use { c ->
		if (c.moveToFirst()) c.getString(0) else ""
	}

	fun guardarContenidoCompleto(id: Long, html: String) {
		writableDatabase.update("articulos", ContentValues().apply {
			put("contenido", html)
			put("completo", 1)
			put("palabras", palabrasDe(html))
		}, "id = ?", arrayOf(id.toString()))
	}

	fun marcarTodoLeido(fuente: Long? = null, categoria: String? = null) {
		when {
			fuente != null -> writableDatabase.execSQL("UPDATE articulos SET leido = 1, cambiado = 1 WHERE fuente = ? AND leido = 0", arrayOf(fuente))
			categoria != null -> writableDatabase.execSQL(
				"UPDATE articulos SET leido = 1, cambiado = 1 WHERE leido = 0 AND fuente IN (SELECT id FROM fuentes WHERE categoria = ?)", arrayOf(categoria),
			)
			else -> writableDatabase.execSQL("UPDATE articulos SET leido = 1, cambiado = 1 WHERE leido = 0")
		}
		avisar()
	}

	/** Borra los artículos leídos más antiguos que 'dias' (nunca favoritos, ni de más tarde, ni podcasts a medias). */
	fun limpiar(dias: Int) {
		if (dias <= 0) return
		val limite = System.currentTimeMillis() - dias * 86_400_000L
		writableDatabase.delete(
			"articulos", "leido = 1 AND favorito = 0 AND mas_tarde = 0 AND posicion_audio = 0 AND recibido < ? AND fecha < ?",
			arrayOf(limite.toString(), limite.toString()),
		)
	}
}
