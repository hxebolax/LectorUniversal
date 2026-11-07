package com.xebolax.lector.podcast

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.CopyOnWriteArrayList

/** Un podcast al que se está suscrito, con sus ajustes propios. */
data class Podcast(
	val id: Long,
	val titulo: String,
	val url: String,
	val autor: String,
	val descripcion: String,
	val imagen: String,
	val sitio: String,
	val etag: String,
	val modificado: String,
	val ultima: Long,
	val error: String,
	/** Velocidad propia en % (0 = la de Ajustes). */
	val velocidad: Int,
	/** Segundos que saltar al empezar cada episodio (intro) y antes del final. */
	val saltarInicio: Int,
	val saltarFinal: Int,
	/** Descargar los episodios nuevos: 0 = lo de Ajustes, 1 = sí, 2 = no. */
	val autoDescargar: Int,
	/** Dónde van los episodios nuevos: 0 = lo de Ajustes, 1 = Novedades, 2 = la cola, 3 = ningún sitio. */
	val nuevos: Int,
	val avisar: Boolean,
	val pausado: Boolean,
	/** Carpeta en la que está (vacía = sin carpeta). */
	val carpeta: String = "",
	/** Filtro de episodios nuevos: palabras que tiene que tener alguna, que no puede tener, y minutos mínimos. */
	val filtroIncluir: String = "",
	val filtroExcluir: String = "",
	val duracionMin: Int = 0,
	/** Sus episodios, del más antiguo al más nuevo (series que se escuchan en orden). */
	val antiguosPrimero: Boolean = false,
	/** Usuario y contraseña del feed (podcasts privados o de mecenazgo). */
	val usuario: String = "",
	val clave: String = "",
	val sinEscuchar: Int = 0,
	val total: Int = 0,
)

/** Un episodio. estado: -1 nuevo (en Novedades), 0 sin escuchar, 1 escuchado. */
data class Episodio(
	val id: Long,
	/** Descargas fallidas seguidas (3 = se dejó de reintentar). */
	val fallos: Int = 0,
	val podcast: Long,
	val guid: String,
	val titulo: String,
	val descripcion: String,
	val fecha: Long,
	val audio: String,
	val tipo: String,
	val tamano: Long,
	/** Duración en ms (0 si no se conoce). */
	val duracion: Long,
	val enlace: String,
	val temporada: Int,
	val numero: Int,
	val capitulos: String,
	val transcripcion: String,
	val tipoTranscripcion: String,
	val estado: Int,
	/** Por dónde se quedó (ms). */
	val posicion: Long,
	/** Posición en la cola (0 = no está en la cola). */
	val cola: Int,
	/** Archivo descargado (vacío si no lo está). */
	val archivo: String,
	/** Descarga en marcha (id del gestor de descargas). */
	val descarga: Long,
	val favorito: Boolean,
	/** Última vez que se escuchó (para el historial). */
	val escuchado: Long,
	val nombrePodcast: String = "",
) {
	val nuevo get() = estado == -1
	val terminado get() = estado == 1
	val enCola get() = cola > 0
	val descargado get() = archivo.isNotBlank()
}

/** Base de datos de los podcasts (aparte de la biblioteca y de las noticias). */
class PodcastsBaseDatos private constructor(c: Context) : SQLiteOpenHelper(c, "podcasts.db", null, 4) {

	companion object {
		@Volatile
		private var instancia: PodcastsBaseDatos? = null

		fun obtener(c: Context): PodcastsBaseDatos = instancia ?: synchronized(this) {
			instancia ?: PodcastsBaseDatos(c.applicationContext).also { instancia = it }
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
			"""CREATE TABLE podcasts (
				id INTEGER PRIMARY KEY AUTOINCREMENT,
				titulo TEXT NOT NULL DEFAULT '',
				url TEXT NOT NULL UNIQUE,
				autor TEXT NOT NULL DEFAULT '',
				descripcion TEXT NOT NULL DEFAULT '',
				imagen TEXT NOT NULL DEFAULT '',
				sitio TEXT NOT NULL DEFAULT '',
				etag TEXT NOT NULL DEFAULT '',
				modificado TEXT NOT NULL DEFAULT '',
				ultima INTEGER NOT NULL DEFAULT 0,
				error TEXT NOT NULL DEFAULT '',
				velocidad INTEGER NOT NULL DEFAULT 0,
				saltar_inicio INTEGER NOT NULL DEFAULT 0,
				saltar_final INTEGER NOT NULL DEFAULT 0,
				auto_descargar INTEGER NOT NULL DEFAULT 0,
				nuevos INTEGER NOT NULL DEFAULT 0,
				avisar INTEGER NOT NULL DEFAULT 0,
				pausado INTEGER NOT NULL DEFAULT 0
			)"""
		)
		db.execSQL(
			"""CREATE TABLE episodios (
				id INTEGER PRIMARY KEY AUTOINCREMENT,
				podcast INTEGER NOT NULL,
				guid TEXT NOT NULL,
				titulo TEXT NOT NULL DEFAULT '',
				descripcion TEXT NOT NULL DEFAULT '',
				fecha INTEGER NOT NULL DEFAULT 0,
				audio TEXT NOT NULL DEFAULT '',
				tipo TEXT NOT NULL DEFAULT '',
				tamano INTEGER NOT NULL DEFAULT 0,
				duracion INTEGER NOT NULL DEFAULT 0,
				enlace TEXT NOT NULL DEFAULT '',
				temporada INTEGER NOT NULL DEFAULT 0,
				numero INTEGER NOT NULL DEFAULT 0,
				capitulos TEXT NOT NULL DEFAULT '',
				transcripcion TEXT NOT NULL DEFAULT '',
				tipo_transcripcion TEXT NOT NULL DEFAULT '',
				estado INTEGER NOT NULL DEFAULT -1,
				posicion INTEGER NOT NULL DEFAULT 0,
				cola INTEGER NOT NULL DEFAULT 0,
				archivo TEXT NOT NULL DEFAULT '',
				descarga INTEGER NOT NULL DEFAULT 0,
				favorito INTEGER NOT NULL DEFAULT 0,
				escuchado INTEGER NOT NULL DEFAULT 0,
				sinc INTEGER NOT NULL DEFAULT 0,
				UNIQUE(podcast, guid)
			)"""
		)
		db.execSQL("CREATE INDEX episodios_fecha ON episodios(fecha DESC)")
		db.execSQL("CREATE INDEX episodios_podcast ON episodios(podcast, estado)")
		crearCarpetas(db)
		version4(db)
	}

	/** Versión 4: filtro de episodios, orden de episodios y credenciales por podcast; fallos de descarga. */
	private fun version4(db: SQLiteDatabase) {
		for ((col, tipo) in listOf("filtro_incluir" to "TEXT NOT NULL DEFAULT ''", "filtro_excluir" to "TEXT NOT NULL DEFAULT ''",
			"duracion_min" to "INTEGER NOT NULL DEFAULT 0", "antiguos_primero" to "INTEGER NOT NULL DEFAULT 0",
			"usuario" to "TEXT NOT NULL DEFAULT ''", "clave" to "TEXT NOT NULL DEFAULT ''")) {
			if (!tieneColumna(db, "podcasts", col)) db.execSQL("ALTER TABLE podcasts ADD COLUMN $col $tipo")
		}
		if (!tieneColumna(db, "episodios", "fallos")) db.execSQL("ALTER TABLE episodios ADD COLUMN fallos INTEGER NOT NULL DEFAULT 0")
	}

	private fun tieneColumna(db: SQLiteDatabase, tabla: String, columna: String): Boolean =
		db.rawQuery("PRAGMA table_info($tabla)", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(1) else null }.any { it == columna } }

	/** Carpetas (pueden estar vacías) con su orden, y el orden de los podcasts dentro de cada una. */
	private fun crearCarpetas(db: SQLiteDatabase) {
		if (!tieneColumna(db, "podcasts", "carpeta")) db.execSQL("ALTER TABLE podcasts ADD COLUMN carpeta TEXT NOT NULL DEFAULT ''")
		if (!tieneColumna(db, "podcasts", "orden")) db.execSQL("ALTER TABLE podcasts ADD COLUMN orden INTEGER NOT NULL DEFAULT 0")
		db.execSQL("CREATE TABLE IF NOT EXISTS carpetas (nombre TEXT PRIMARY KEY COLLATE NOCASE, orden INTEGER NOT NULL DEFAULT 0)")
	}

	/** Si se instala una versión anterior de la app, la base de datos (más nueva) se deja como está, sin cerrar la app. */
	override fun onDowngrade(db: SQLiteDatabase, antes: Int, ahora: Int) = Unit

	override fun onUpgrade(db: SQLiteDatabase, antes: Int, ahora: Int) {
		// sinc = 1: lo escuchado ha cambiado y falta enviarlo al servidor (gpodder)
		if (antes < 2) db.execSQL("ALTER TABLE episodios ADD COLUMN sinc INTEGER NOT NULL DEFAULT 0")
		if (antes < 3) crearCarpetas(db)
		if (antes < 4) version4(db)
	}

	// --- Carpetas ------------------------------------------------------------------------

	/** Las carpetas, en el orden elegido (sin orden, por nombre). */
	fun carpetas(): List<String> = readableDatabase.rawQuery("SELECT nombre FROM carpetas ORDER BY orden, nombre COLLATE NOCASE", null).use { c ->
		generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
	}

	/** Crea la carpeta si no existe (al final). Devuelve el nombre tal como quedó guardado. */
	fun crearCarpeta(nombre: String): String {
		val n = nombre.trim()
		if (n.isEmpty()) return n
		carpetas().firstOrNull { it.equals(n, ignoreCase = true) }?.let { return it }
		writableDatabase.execSQL("INSERT OR IGNORE INTO carpetas(nombre, orden) VALUES (?, (SELECT COALESCE(MAX(orden), 0) + 1 FROM carpetas))", arrayOf(n))
		avisar()
		return n
	}

	fun ponerCarpeta(id: Long, carpeta: String) {
		val n = crearCarpeta(carpeta)
		writableDatabase.update("podcasts", ContentValues().apply { put("carpeta", n) }, "id = ?", arrayOf(id.toString()))
		avisar()
	}

	/** Cambia el nombre de una carpeta (si ya existe otra con ese nombre, se juntan). */
	fun renombrarCarpeta(antes: String, despues: String) {
		val nuevo = despues.trim()
		if (nuevo.isEmpty() || nuevo == antes) return
		val existe = carpetas().firstOrNull { it.equals(nuevo, ignoreCase = true) && !it.equals(antes, ignoreCase = true) }
		if (existe != null) writableDatabase.delete("carpetas", "nombre = ?", arrayOf(antes))
		else writableDatabase.update("carpetas", ContentValues().apply { put("nombre", nuevo) }, "nombre = ?", arrayOf(antes))
		writableDatabase.update("podcasts", ContentValues().apply { put("carpeta", existe ?: nuevo) }, "carpeta = ? COLLATE NOCASE", arrayOf(antes))
		avisar()
	}

	/** Borra la carpeta. conPodcasts: los devuelve para borrarlos (descargas y sincronización); si no, quedan sin carpeta. */
	fun borrarCarpeta(nombre: String, conPodcasts: Boolean): List<Podcast> {
		val suyos = podcasts().filter { it.carpeta.equals(nombre, ignoreCase = true) }
		if (!conPodcasts) writableDatabase.update("podcasts", ContentValues().apply { put("carpeta", "") }, "carpeta = ? COLLATE NOCASE", arrayOf(nombre))
		writableDatabase.delete("carpetas", "nombre = ?", arrayOf(nombre))
		avisar()
		return if (conPodcasts) suyos else emptyList()
	}

	fun ordenarCarpetas(nombres: List<String>) = ordenar("carpetas", "nombre", nombres)

	/** Pone la cola en este orden (ordenar por fecha, duración, podcast...). */
	fun ordenarCola(ids: List<Long>) {
		guardarOrdenCola(ids)
		avisar()
	}

	fun ordenEpisodios(id: Long, antiguosPrimero: Boolean) {
		writableDatabase.update("podcasts", ContentValues().apply { put("antiguos_primero", if (antiguosPrimero) 1 else 0) }, "id = ?", arrayOf(id.toString()))
		avisar()
	}

	fun credenciales(id: Long, usuario: String, clave: String) {
		writableDatabase.update("podcasts", ContentValues().apply { put("usuario", usuario); put("clave", clave) }, "id = ?", arrayOf(id.toString()))
	}

	/** Fecha del episodio más reciente de cada podcast (para ordenar por lo último publicado). */
	fun ultimoEpisodio(): Map<Long, Long> = readableDatabase.rawQuery("SELECT podcast, MAX(fecha) FROM episodios GROUP BY podcast", null).use { c ->
		generateSequence { if (c.moveToNext()) c.getLong(0) to c.getLong(1) else null }.toMap()
	}

	/** Fallos seguidos al descargar un episodio (se reintenta hasta 3 veces). */
	fun fallosDescarga(id: Long, fallos: Int) {
		writableDatabase.update("episodios", ContentValues().apply { put("fallos", fallos) }, "id = ?", arrayOf(id.toString()))
	}

	fun ordenarPodcasts(ids: List<Long>) = ordenar("podcasts", "id", ids.map { it.toString() })

	private fun ordenar(tabla: String, clave: String, valores: List<String>) {
		val db = writableDatabase
		db.beginTransaction()
		try {
			valores.forEachIndexed { i, v -> db.update(tabla, ContentValues().apply { put("orden", i + 1) }, "$clave = ?", arrayOf(v)) }
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		avisar()
	}

	// --- Sincronización (gpodder) --------------------------------------------------------

	fun pendientesDeEnviar(): List<Episodio> = consulta("e.sinc = 1 AND e.audio != ''", limite = 5000)

	fun enviados(ids: Collection<Long>) {
		for (t in ids.chunked(400)) writableDatabase.execSQL("UPDATE episodios SET sinc = 0 WHERE id IN (${t.joinToString(",")})")
	}

	fun episodioPorAudio(url: String): Episodio? = consulta("e.audio = ?", arrayOf(url), limite = 1).firstOrNull()

	/** Lo escuchado en otro dispositivo: por dónde va o si ya se terminó. */
	fun aplicarRemoto(id: Long, posicion: Long, terminado: Boolean, cuando: Long) {
		writableDatabase.update("episodios", ContentValues().apply {
			if (terminado) {
				put("estado", 1)
				put("cola", 0)
				put("posicion", 0)
			} else {
				put("posicion", posicion)
				put("estado", 0)
			}
			put("escuchado", cuando)
		}, "id = ? AND sinc = 0", arrayOf(id.toString()))
	}

	fun renumerar() = renumerarCola()

	// --- Podcasts ------------------------------------------------------------------------

	private fun Cursor.podcast(sin: Int = 0, total: Int = 0) = Podcast(
		id = getLong(getColumnIndexOrThrow("id")),
		titulo = getString(getColumnIndexOrThrow("titulo")),
		url = getString(getColumnIndexOrThrow("url")),
		autor = getString(getColumnIndexOrThrow("autor")),
		descripcion = getString(getColumnIndexOrThrow("descripcion")),
		imagen = getString(getColumnIndexOrThrow("imagen")),
		sitio = getString(getColumnIndexOrThrow("sitio")),
		etag = getString(getColumnIndexOrThrow("etag")),
		modificado = getString(getColumnIndexOrThrow("modificado")),
		ultima = getLong(getColumnIndexOrThrow("ultima")),
		error = getString(getColumnIndexOrThrow("error")),
		velocidad = getInt(getColumnIndexOrThrow("velocidad")),
		saltarInicio = getInt(getColumnIndexOrThrow("saltar_inicio")),
		saltarFinal = getInt(getColumnIndexOrThrow("saltar_final")),
		autoDescargar = getInt(getColumnIndexOrThrow("auto_descargar")),
		nuevos = getInt(getColumnIndexOrThrow("nuevos")),
		avisar = getInt(getColumnIndexOrThrow("avisar")) == 1,
		pausado = getInt(getColumnIndexOrThrow("pausado")) == 1,
		carpeta = getString(getColumnIndexOrThrow("carpeta")),
		filtroIncluir = getString(getColumnIndexOrThrow("filtro_incluir")),
		filtroExcluir = getString(getColumnIndexOrThrow("filtro_excluir")),
		duracionMin = getInt(getColumnIndexOrThrow("duracion_min")),
		antiguosPrimero = getInt(getColumnIndexOrThrow("antiguos_primero")) == 1,
		usuario = getString(getColumnIndexOrThrow("usuario")),
		clave = getString(getColumnIndexOrThrow("clave")),
		sinEscuchar = sin,
		total = total,
	)

	fun podcasts(): List<Podcast> {
		val cuentas = HashMap<Long, Pair<Int, Int>>()
		readableDatabase.rawQuery("SELECT podcast, SUM(estado < 1), COUNT(*) FROM episodios GROUP BY podcast", null).use { c ->
			while (c.moveToNext()) cuentas[c.getLong(0)] = c.getInt(1) to c.getInt(2)
		}
		return readableDatabase.query("podcasts", null, null, null, null, null, "orden, titulo COLLATE NOCASE").use { c ->
			generateSequence { if (c.moveToNext()) c else null }.map {
				val (s, t) = cuentas[it.getLong(it.getColumnIndexOrThrow("id"))] ?: (0 to 0)
				it.podcast(s, t)
			}.toList()
		}
	}

	fun podcast(id: Long): Podcast? = readableDatabase.query("podcasts", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
		if (c.moveToFirst()) c.podcast() else null
	}

	fun podcastPorUrl(url: String): Podcast? = readableDatabase.query("podcasts", null, "url = ?", arrayOf(url), null, null, null).use { c ->
		if (c.moveToFirst()) c.podcast() else null
	}

	fun anadirPodcast(url: String, titulo: String, autor: String = "", imagen: String = "", carpeta: String = ""): Long {
		podcastPorUrl(url)?.let { return it.id }
		val n = crearCarpeta(carpeta)
		// Al final de su carpeta (si ya está ordenada a mano)
		val orden = readableDatabase.rawQuery("SELECT COALESCE(MAX(orden), 0) FROM podcasts WHERE carpeta = ? COLLATE NOCASE", arrayOf(n)).use { c ->
			if (c.moveToFirst() && c.getInt(0) > 0) c.getInt(0) + 1 else 0
		}
		val id = writableDatabase.insert("podcasts", null, ContentValues().apply {
			put("carpeta", n)
			put("orden", orden)
			put("url", url)
			put("titulo", titulo)
			put("autor", autor)
			put("imagen", imagen)
		})
		avisar()
		return id
	}

	fun editarPodcast(p: Podcast) {
		val carpeta = crearCarpeta(p.carpeta)
		writableDatabase.update("podcasts", ContentValues().apply {
			put("titulo", p.titulo)
			put("carpeta", carpeta)
			put("filtro_incluir", p.filtroIncluir)
			put("filtro_excluir", p.filtroExcluir)
			put("duracion_min", p.duracionMin)
			put("antiguos_primero", if (p.antiguosPrimero) 1 else 0)
			put("usuario", p.usuario)
			put("clave", p.clave)
			put("velocidad", p.velocidad)
			put("saltar_inicio", p.saltarInicio)
			put("saltar_final", p.saltarFinal)
			put("auto_descargar", p.autoDescargar)
			put("nuevos", p.nuevos)
			put("avisar", if (p.avisar) 1 else 0)
			put("pausado", if (p.pausado) 1 else 0)
		}, "id = ?", arrayOf(p.id.toString()))
		avisar()
	}

	fun guardarVelocidad(id: Long, velocidad: Int) {
		writableDatabase.update("podcasts", ContentValues().apply { put("velocidad", velocidad) }, "id = ?", arrayOf(id.toString()))
	}

	fun borrarPodcast(id: Long) {
		writableDatabase.delete("episodios", "podcast = ?", arrayOf(id.toString()))
		writableDatabase.delete("podcasts", "id = ?", arrayOf(id.toString()))
		avisar()
	}

	fun guardarActualizacion(id: Long, etag: String?, modificado: String?, datos: PodcastLector.Canal?, error: String) {
		writableDatabase.update("podcasts", ContentValues().apply {
			if (etag != null) put("etag", etag)
			if (modificado != null) put("modificado", modificado)
			datos?.let {
				if (it.titulo.isNotBlank()) put("titulo", it.titulo)
				if (it.autor.isNotBlank()) put("autor", it.autor)
				if (it.descripcion.isNotBlank()) put("descripcion", it.descripcion)
				if (it.imagen.isNotBlank()) put("imagen", it.imagen)
				if (it.sitio.isNotBlank()) put("sitio", it.sitio)
				// El podcast se ha mudado (itunes:new-feed-url)
				if (it.nuevaUrl.isNotBlank() && podcastPorUrl(it.nuevaUrl) == null) put("url", it.nuevaUrl)
			}
			put("error", error)
			if (error.isEmpty()) put("ultima", System.currentTimeMillis())
		}, "id = ?", arrayOf(id.toString()))
	}

	// --- Episodios -----------------------------------------------------------------------

	private val SELECT = "SELECT e.*, p.titulo AS nombre_podcast FROM episodios e JOIN podcasts p ON p.id = e.podcast"

	private fun Cursor.episodio() = Episodio(
		id = getLong(getColumnIndexOrThrow("id")),
		fallos = getColumnIndex("fallos").let { if (it >= 0) getInt(it) else 0 },
		podcast = getLong(getColumnIndexOrThrow("podcast")),
		guid = getString(getColumnIndexOrThrow("guid")),
		titulo = getString(getColumnIndexOrThrow("titulo")),
		descripcion = getString(getColumnIndexOrThrow("descripcion")),
		fecha = getLong(getColumnIndexOrThrow("fecha")),
		audio = getString(getColumnIndexOrThrow("audio")),
		tipo = getString(getColumnIndexOrThrow("tipo")),
		tamano = getLong(getColumnIndexOrThrow("tamano")),
		duracion = getLong(getColumnIndexOrThrow("duracion")),
		enlace = getString(getColumnIndexOrThrow("enlace")),
		temporada = getInt(getColumnIndexOrThrow("temporada")),
		numero = getInt(getColumnIndexOrThrow("numero")),
		capitulos = getString(getColumnIndexOrThrow("capitulos")),
		transcripcion = getString(getColumnIndexOrThrow("transcripcion")),
		tipoTranscripcion = getString(getColumnIndexOrThrow("tipo_transcripcion")),
		estado = getInt(getColumnIndexOrThrow("estado")),
		posicion = getLong(getColumnIndexOrThrow("posicion")),
		cola = getInt(getColumnIndexOrThrow("cola")),
		archivo = getString(getColumnIndexOrThrow("archivo")),
		descarga = getLong(getColumnIndexOrThrow("descarga")),
		favorito = getInt(getColumnIndexOrThrow("favorito")) == 1,
		escuchado = getLong(getColumnIndexOrThrow("escuchado")),
		nombrePodcast = getColumnIndex("nombre_podcast").takeIf { it >= 0 }?.let { getString(it) }.orEmpty(),
	)

	private fun consulta(donde: String, args: Array<String> = emptyArray(), orden: String = "e.fecha DESC", limite: Int = 1000): List<Episodio> =
		readableDatabase.rawQuery("$SELECT WHERE $donde ORDER BY $orden LIMIT $limite", args).use { c ->
			generateSequence { if (c.moveToNext()) c.episodio() else null }.toList()
		}

	fun episodio(id: Long): Episodio? = consulta("e.id = ?", arrayOf(id.toString())).firstOrNull()
	fun cola(): List<Episodio> = consulta("e.cola > 0", orden = "e.cola ASC")
	fun novedades(limite: Int = 1000): List<Episodio> = consulta("e.estado = -1", limite = limite)
	fun contarNovedades(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM episodios WHERE estado = -1", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
	/** Para las estadísticas: cuántos episodios cumplen una condición (sin datos del usuario en ella). */
	fun contarEpisodios(donde: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM episodios WHERE $donde", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
	/** Los podcasts con más episodios escuchados: (título, episodios). */
	fun masEscuchados(limite: Int): List<Pair<String, Int>> = readableDatabase.rawQuery(
		"SELECT p.titulo, COUNT(*) FROM episodios e JOIN podcasts p ON p.id = e.podcast WHERE e.estado = 1 GROUP BY p.id ORDER BY 2 DESC, p.titulo LIMIT $limite", null,
	).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) to c.getInt(1) else null }.toList() }
	fun contarPodcasts(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM podcasts", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
	fun dePodcast(id: Long, soloSinEscuchar: Boolean, antiguosPrimero: Boolean = false): List<Episodio> =
		consulta(if (soloSinEscuchar) "e.podcast = ? AND e.estado < 1" else "e.podcast = ?", arrayOf(id.toString()),
			if (antiguosPrimero) "e.fecha ASC" else "e.fecha DESC")
	fun descargados(): List<Episodio> = consulta("e.archivo != '' OR e.descarga != 0")
	fun favoritos(): List<Episodio> = consulta("e.favorito = 1")
	fun historial(): List<Episodio> = consulta("e.escuchado > 0", orden = "e.escuchado DESC", limite = 200)
	fun enCurso(): List<Episodio> = consulta("e.posicion > 0 AND e.estado < 1", orden = "e.escuchado DESC", limite = 50)
	fun buscar(texto: String): List<Episodio> {
		val t = "%" + texto.trim().replace("%", "") + "%"
		return consulta("(e.titulo LIKE ? OR e.descripcion LIKE ? OR p.titulo LIKE ?)", arrayOf(t, t, t), limite = 300)
	}

	/** Guarda los episodios nuevos. estado: -1 Novedades, 0 sin escuchar (sin pasar por Novedades), 1 escuchado. */
	fun anadirEpisodios(podcast: Long, lista: List<PodcastLector.Elemento>, estado: Int): List<Long> {
		val nuevos = ArrayList<Long>()
		val db = writableDatabase
		db.beginTransaction()
		try {
			for (e in lista) {
				val id = db.insertWithOnConflict("episodios", null, ContentValues().apply {
					put("podcast", podcast)
					put("guid", e.guid)
					put("titulo", e.titulo)
					put("descripcion", e.descripcion)
					put("fecha", e.fecha)
					put("audio", e.audio)
					put("tipo", e.tipo)
					put("tamano", e.tamano)
					put("duracion", e.duracion)
					put("enlace", e.enlace)
					put("temporada", e.temporada)
					put("numero", e.numero)
					put("capitulos", e.capitulos)
					put("transcripcion", e.transcripcion)
					put("tipo_transcripcion", e.tipoTranscripcion)
					put("estado", estado)
				}, SQLiteDatabase.CONFLICT_IGNORE)
				if (id != -1L) nuevos += id
			}
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		if (nuevos.isNotEmpty()) avisar()
		return nuevos
	}

	private fun cambiar(ids: Collection<Long>, v: ContentValues) {
		if (ids.isEmpty()) return
		val db = writableDatabase
		db.beginTransaction()
		try {
			for (t in ids.chunked(400)) db.update("episodios", v, "id IN (${t.joinToString(",")})", null)
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		avisar()
	}

	/** Escuchado o no; al marcarlo escuchado sale de Novedades y de la cola. */
	fun marcarEscuchados(ids: Collection<Long>, si: Boolean) {
		cambiar(ids, ContentValues().apply {
			put("estado", if (si) 1 else 0)
			put("sinc", 1)
			if (si) {
				put("cola", 0)
				put("posicion", 0)
			} else put("posicion", 0)
		})
		if (si) renumerarCola()
	}

	/** Sale de Novedades sin escucharlo. */
	fun quitarDeNovedades(ids: Collection<Long>) = cambiar(ids, ContentValues().apply { put("estado", 0) })

	fun marcarFavorito(id: Long, si: Boolean) = cambiar(listOf(id), ContentValues().apply { put("favorito", if (si) 1 else 0) })

	fun guardarPosicion(id: Long, ms: Long, duracion: Long) {
		writableDatabase.update("episodios", ContentValues().apply {
			put("posicion", ms)
			if (duracion > 0) put("duracion", duracion)
			put("escuchado", System.currentTimeMillis())
			put("sinc", 1)
		}, "id = ?", arrayOf(id.toString()))
	}

	fun guardarDescarga(id: Long, archivo: String, descarga: Long) {
		writableDatabase.update("episodios", ContentValues().apply {
			put("archivo", archivo)
			put("descarga", descarga)
		}, "id = ?", arrayOf(id.toString()))
		avisar()
	}

	// --- Cola --------------------------------------------------------------------------------

	private fun maxCola(): Int = readableDatabase.rawQuery("SELECT MAX(cola) FROM episodios", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

	/** Añade a la cola (al final o al principio). Sale de Novedades. */
	fun anadirACola(ids: List<Long>, alPrincipio: Boolean = false) {
		if (ids.isEmpty()) return
		val actual = cola().filter { it.id !in ids }.map { it.id }
		val orden = if (alPrincipio) ids + actual else actual + ids
		guardarOrdenCola(orden, ids)
	}

	fun quitarDeCola(ids: Collection<Long>) {
		cambiar(ids, ContentValues().apply { put("cola", 0) })
		renumerarCola()
	}

	fun vaciarCola() {
		writableDatabase.execSQL("UPDATE episodios SET cola = 0")
		avisar()
	}

	/** Mueve un episodio de la cola (paso -1 arriba, +1 abajo; o a una posición). */
	fun moverEnCola(id: Long, paso: Int? = null, aPosicion: Int? = null) {
		val orden = cola().map { it.id }.toMutableList()
		val i = orden.indexOf(id)
		if (i < 0) return
		orden.removeAt(i)
		val destino = (aPosicion ?: (i + (paso ?: 0))).coerceIn(0, orden.size)
		orden.add(destino, id)
		guardarOrdenCola(orden)
	}

	private fun guardarOrdenCola(orden: List<Long>, salenDeNovedades: List<Long> = emptyList()) {
		val db = writableDatabase
		db.beginTransaction()
		try {
			db.execSQL("UPDATE episodios SET cola = 0 WHERE cola > 0")
			orden.forEachIndexed { i, id -> db.execSQL("UPDATE episodios SET cola = ? WHERE id = ?", arrayOf<Any>(i + 1, id)) }
			salenDeNovedades.forEach { db.execSQL("UPDATE episodios SET estado = 0 WHERE id = ? AND estado = -1", arrayOf<Any>(it)) }
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
		avisar()
	}

	private fun renumerarCola() = guardarOrdenCola(cola().map { it.id })

	fun marcarTodoEscuchado(podcast: Long) {
		writableDatabase.execSQL("UPDATE episodios SET sinc = (sinc OR estado != 1), estado = 1, cola = 0, posicion = 0 WHERE podcast = ?", arrayOf(podcast))
		renumerarCola()
	}

	fun vaciarNovedades() {
		writableDatabase.execSQL("UPDATE episodios SET estado = 0 WHERE estado = -1")
		avisar()
	}

	fun ultimoNumeroCola() = maxCola()
}
