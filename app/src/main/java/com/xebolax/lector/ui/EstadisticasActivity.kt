package com.xebolax.lector.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xebolax.lector.R
import com.xebolax.lector.databinding.ActivitySimpleBinding
import com.xebolax.lector.datos.Actividad
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Estado
import com.xebolax.lector.podcast.PodcastsBaseDatos
import com.xebolax.lector.radio.RadioBaseDatos
import com.xebolax.lector.rss.RssBaseDatos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Estadísticas de toda la app, una sección tras otra en el orden de Ajustes
 * (Portada y secciones): para cada una, el tiempo de hoy, de los últimos 7
 * días, de este mes y total, la racha de días seguidos y lo más destacado.
 *
 * Es texto para leer: cada sección y cada apartado empieza con un encabezado
 * (TalkBack salta de uno a otro) y cada dato es una línea.
 */
class EstadisticasActivity : AppCompatActivity() {

	private lateinit var b: ActivitySimpleBinding
	private val formato = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

	/** Una línea de texto o un encabezado. */
	private class Linea(val texto: String, val encabezado: Boolean = false)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivitySimpleBinding.inflate(layoutInflater)
		setContentView(b.root)
		configurarBarra(b.barra, getString(R.string.estadisticas))
		lifecycleScope.launch {
			val lineas = withContext(Dispatchers.IO) {
				OrdenSecciones.orden(this@EstadisticasActivity).flatMap { s ->
					when (s) {
						Seccion.BIBLIOTECA -> biblioteca()
						Seccion.PODCASTS -> podcasts()
						Seccion.NOTICIAS -> noticias()
						Seccion.RADIO -> radio()
						// Lo leído en Compartidos cuenta como lectura (en Biblioteca)
						Seccion.PORTADA, Seccion.COMPARTIDOS -> emptyList()
					}
				}
			}
			for (l in lineas) {
				b.contenido.addView(TextView(this@EstadisticasActivity).apply {
					text = l.texto
					setTextAppearance(if (l.encabezado) com.google.android.material.R.style.TextAppearance_Material3_TitleLarge else com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
					setPadding(0, dp(this@EstadisticasActivity, if (l.encabezado) 16 else 4), 0, dp(this@EstadisticasActivity, 4))
					isFocusable = true
					if (l.encabezado) encabezado()
				})
			}
		}
	}

	private fun hace(dias: Int) = formato.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -dias) }.time)
	private fun inicioDeMes() = formato.format(Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.time)

	/** Hoy, 7 días, este mes y total: (nombre, desde qué día). */
	private fun periodos() = listOf(
		R.string.hoy to hace(0),
		R.string.ultimos_7_dias to hace(6),
		R.string.este_mes to inicioDeMes(),
		R.string.total to "0000-00-00",
	)

	/** Días seguidos con actividad hasta hoy (o hasta ayer, si hoy aún no hay). */
	private fun racha(dias: List<String>): Int {
		val conjunto = dias.toSet()
		var dia = if (hace(0) in conjunto) 0 else 1
		var n = 0
		while (hace(dia) in conjunto) {
			n++
			dia++
		}
		return n
	}

	private fun tiempo(segundos: Long) = tiempoHablado(segundos * 1000, conSegundos = false)

	/** Las cuatro líneas de tiempo de una sección de Actividad, con lo que se hizo en cada periodo. */
	private fun tiempos(seccion: String, elementos: Int): List<Linea> = periodos().map { (nombre, desde) ->
		val (segundos, n) = Actividad.desde(this, seccion, desde)
		Linea(getString(R.string.est_linea, getString(nombre), tiempo(segundos), resources.getQuantityString(elementos, n, n)))
	}

	private fun biblioteca(): List<Linea> {
		val db = BaseDatos.obtener(this)
		val todos = db.todos()
		val r = mutableListOf(Linea(getString(R.string.seccion_biblioteca), true), Linea(getString(R.string.tiempo_de_lectura), true))
		for ((nombre, desde) in periodos()) {
			val (segundos, caracteres) = db.lecturaDesde(desde)
			r += Linea(getString(R.string.estadistica_linea, getString(nombre), tiempo(segundos), (caracteres / 6).toInt()))
		}
		val racha = racha(db.diasConLectura())
		r += Linea(resources.getQuantityString(R.plurals.racha, racha, racha))
		r += Linea(resources.getQuantityString(R.plurals.libros, todos.size, todos.size))
		r += Linea(getString(R.string.libros_terminados_n, todos.count { it.estado == Estado.TERMINADO }))
		r += Linea(getString(R.string.libros_leyendo_n, todos.count { it.estado == Estado.LEYENDO }))
		val masLeidos = todos.sortedByDescending { it.segundos }.filter { it.segundos > 60 }.take(10)
		if (masLeidos.isNotEmpty()) {
			r += Linea(getString(R.string.mas_leidos), true)
			masLeidos.forEach { r += Linea("${it.tituloVisible}: ${tiempo(it.segundos)}") }
		}
		return r
	}

	private fun podcasts(): List<Linea> {
		val db = PodcastsBaseDatos.obtener(this)
		val r = mutableListOf(Linea(getString(R.string.podcasts), true), Linea(getString(R.string.est_tiempo_escuchando), true))
		r += tiempos(Actividad.PODCASTS, R.plurals.est_episodios_terminados)
		val racha = racha(Actividad.dias(this, Actividad.PODCASTS))
		r += Linea(resources.getQuantityString(R.plurals.est_racha_escuchando, racha, racha))
		r += Linea(getString(R.string.est_suscripciones, db.contarPodcasts()))
		r += Linea(getString(R.string.est_episodios_escuchados, db.contarEpisodios("estado = 1")))
		r += Linea(getString(R.string.est_episodios_nuevos, db.contarNovedades()))
		r += Linea(getString(R.string.est_en_cola, db.contarEpisodios("cola > 0")))
		r += Linea(getString(R.string.est_descargados, db.contarEpisodios("archivo != ''")))
		val mas = db.masEscuchados(10)
		if (mas.isNotEmpty()) {
			r += Linea(getString(R.string.est_mas_escuchados), true)
			mas.forEach { (titulo, n) -> r += Linea("$titulo: ${resources.getQuantityString(R.plurals.est_episodios, n, n)}") }
		}
		return r
	}

	private fun noticias(): List<Linea> {
		val db = RssBaseDatos.obtener(this)
		val r = mutableListOf(Linea(getString(R.string.noticias), true), Linea(getString(R.string.est_tiempo_noticias), true))
		r += tiempos(Actividad.NOTICIAS, R.plurals.est_noticias_abiertas)
		val racha = racha(Actividad.dias(this, Actividad.NOTICIAS))
		r += Linea(resources.getQuantityString(R.plurals.est_racha_noticias, racha, racha))
		r += Linea(getString(R.string.est_fuentes, db.contarFuentes()))
		r += Linea(getString(R.string.est_sin_leer, db.contarSinLeer()))
		r += Linea(getString(R.string.est_leidas, db.contarArticulos("leido = 1 AND papelera = 0")))
		r += Linea(getString(R.string.est_favoritas_noticias, db.contarArticulos("favorito = 1")))
		r += Linea(getString(R.string.est_mas_tarde, db.contarArticulos("mas_tarde = 1")))
		return r
	}

	private fun radio(): List<Linea> {
		val db = RadioBaseDatos.obtener(this)
		val r = mutableListOf(Linea(getString(R.string.radio), true), Linea(getString(R.string.est_tiempo_escuchando), true))
		r += tiempos(Actividad.RADIO, R.plurals.est_emisoras_puestas)
		val racha = racha(Actividad.dias(this, Actividad.RADIO))
		r += Linea(resources.getQuantityString(R.plurals.est_racha_escuchando, racha, racha))
		val grabaciones = db.grabaciones()
		r += Linea(getString(R.string.est_favoritas_radio, db.favoritas().size))
		r += Linea(getString(R.string.est_canciones, db.contarCanciones()))
		r += Linea(getString(R.string.est_grabaciones, grabaciones.size, tiempoHablado(grabaciones.sumOf { it.duracion }, conSegundos = false)))
		val mas = db.masEscuchadasPorMi().filter { it.segundos >= 60 }.take(10)
		if (mas.isNotEmpty()) {
			r += Linea(getString(R.string.est_mas_escuchadas), true)
			mas.forEach { r += Linea("${it.emisora.nombre}: ${tiempo(it.segundos)}") }
		}
		return r
	}
}
