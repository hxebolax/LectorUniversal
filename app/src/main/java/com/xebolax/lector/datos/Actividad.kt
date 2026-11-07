package com.xebolax.lector.datos

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.xebolax.lector.LectorApp
import com.xebolax.lector.audio.ControlAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Actividad de cada día en Podcasts, Noticias y Radio, para las estadísticas
 * (la lectura de libros va en la tabla estadisticas de la biblioteca):
 *
 * - segundos: tiempo escuchando episodios o la radio, o leyendo noticias.
 * - elementos: episodios terminados, noticias abiertas o emisoras puestas.
 *
 * El tiempo de audio se cuenta solo, mientras suena (y se guarda cada minuto,
 * para no perderlo si el sistema cierra la app).
 */
object Actividad {

	const val PODCASTS = "podcasts"
	const val NOTICIAS = "noticias"
	const val RADIO = "radio"

	private class Bd(c: Context) : SQLiteOpenHelper(c, "actividad.db", null, 1) {
		override fun onCreate(db: SQLiteDatabase) {
			db.execSQL(
				"""CREATE TABLE actividad (
					dia TEXT NOT NULL,
					seccion TEXT NOT NULL,
					segundos INTEGER NOT NULL DEFAULT 0,
					elementos INTEGER NOT NULL DEFAULT 0,
					PRIMARY KEY (dia, seccion))"""
			)
		}

		override fun onUpgrade(db: SQLiteDatabase, anterior: Int, nueva: Int) = Unit
		override fun onDowngrade(db: SQLiteDatabase, anterior: Int, nueva: Int) = Unit
	}

	@Volatile
	private var bd: Bd? = null

	private fun bd(c: Context): Bd = bd ?: synchronized(this) { bd ?: Bd(c.applicationContext).also { bd = it } }

	/** Suma tiempo y elementos de hoy a una sección. */
	fun sumar(c: Context, seccion: String, segundos: Long = 0, elementos: Int = 0) {
		if (segundos <= 0 && elementos <= 0) return
		val db = bd(c).writableDatabase
		val dia = BaseDatos.hoy()
		synchronized(this) {
			db.execSQL("UPDATE actividad SET segundos = segundos + ?, elementos = elementos + ? WHERE dia = ? AND seccion = ?",
				arrayOf<Any>(segundos, elementos, dia, seccion))
			db.insertWithOnConflict("actividad", null, ContentValues().apply {
				put("dia", dia)
				put("seccion", seccion)
				put("segundos", segundos)
				put("elementos", elementos)
			}, SQLiteDatabase.CONFLICT_IGNORE)
		}
	}

	/** Lo mismo, sin esperar (desde la interfaz). */
	fun sumarLuego(c: Context, seccion: String, segundos: Long = 0, elementos: Int = 0) {
		val app = c.applicationContext
		LectorApp.ambito.launch(Dispatchers.IO) { runCatching { sumar(app, seccion, segundos, elementos) } }
	}

	/** (segundos, elementos) de una sección desde un día (incluido) hasta hoy. */
	fun desde(c: Context, seccion: String, dia: String): Pair<Long, Int> =
		bd(c).readableDatabase.rawQuery("SELECT SUM(segundos), SUM(elementos) FROM actividad WHERE seccion = ? AND dia >= ?", arrayOf(seccion, dia)).use { cur ->
			if (cur.moveToFirst()) cur.getLong(0) to cur.getInt(1) else 0L to 0
		}

	/** Días con al menos un minuto de actividad en la sección (para la racha). */
	fun dias(c: Context, seccion: String): List<String> =
		bd(c).readableDatabase.rawQuery("SELECT dia FROM actividad WHERE seccion = ? AND segundos >= 60 ORDER BY dia DESC", arrayOf(seccion)).use { cur ->
			generateSequence { if (cur.moveToNext()) cur.getString(0) else null }.toList()
		}

	// --- Tiempo de audio ---------------------------------------------------------------------

	private val principal = Handler(Looper.getMainLooper())
	private var app: Context? = null
	/** Sección del audio que suena ahora (null si no suena nada de Podcasts, Noticias o Radio). */
	private var contando: String? = null
	private var desde = 0L

	private val minuto = object : Runnable {
		override fun run() {
			volcar()
			if (contando != null) principal.postDelayed(this, 60_000)
		}
	}

	/** Empieza a contar el tiempo de audio (una vez, al arrancar la app). */
	fun empezar(c: Context) {
		if (app != null) return
		app = c.applicationContext
		ControlAudio.registrar { principal.post { revisar() } }
	}

	private fun revisar() {
		val e = ControlAudio.externo
		val seccion = if (ControlAudio.reproduciendo && e != null) e.seccionActividad else null
		if (seccion == contando) return
		volcar()
		principal.removeCallbacks(minuto)
		contando = seccion
		desde = SystemClock.elapsedRealtime()
		if (seccion != null) principal.postDelayed(minuto, 60_000)
	}

	private fun volcar() {
		val seccion = contando ?: return
		val c = app ?: return
		val segundos = (SystemClock.elapsedRealtime() - desde) / 1000
		if (segundos <= 0) return
		desde += segundos * 1000
		sumarLuego(c, seccion, segundos)
	}
}
