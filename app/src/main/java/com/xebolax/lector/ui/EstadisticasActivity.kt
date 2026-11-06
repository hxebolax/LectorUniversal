package com.xebolax.lector.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xebolax.lector.R
import com.xebolax.lector.databinding.ActivitySimpleBinding
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Estado
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Estadísticas de lectura: tiempo hoy, esta semana, este mes y total, racha y libros más leídos. */
class EstadisticasActivity : AppCompatActivity() {

	private lateinit var b: ActivitySimpleBinding

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivitySimpleBinding.inflate(layoutInflater)
		setContentView(b.root)
		configurarBarra(b.barra, getString(R.string.estadisticas))
		lifecycleScope.launch {
			val db = BaseDatos.obtener(this@EstadisticasActivity)
			val formato = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
			fun hace(dias: Int) = formato.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -dias) }.time)
			val datos = withContext(Dispatchers.IO) {
				val mes = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.let { formato.format(it.time) }
				val todos = db.todos()
				mapOf(
					"hoy" to db.lecturaDesde(BaseDatos.hoy()),
					"semana" to db.lecturaDesde(hace(6)),
					"mes" to db.lecturaDesde(mes),
					"total" to db.lecturaDesde("0000-00-00"),
				) to Triple(db.diasConLectura(), todos, todos.sortedByDescending { it.segundos }.filter { it.segundos > 60 }.take(10))
			}
			val (tiempos, resto) = datos
			val (dias, todos, masLeidos) = resto
			fun linea(texto: String, encabezado: Boolean = false) {
				b.contenido.addView(TextView(this@EstadisticasActivity).apply {
					text = texto
					setTextAppearance(if (encabezado) com.google.android.material.R.style.TextAppearance_Material3_TitleLarge else com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
					setPadding(0, dp(this@EstadisticasActivity, if (encabezado) 16 else 4), 0, dp(this@EstadisticasActivity, 4))
					isFocusable = true
					if (encabezado) encabezado()
				})
			}
			linea(getString(R.string.tiempo_de_lectura), true)
			for ((clave, nombre) in listOf("hoy" to R.string.hoy, "semana" to R.string.ultimos_7_dias, "mes" to R.string.este_mes, "total" to R.string.total)) {
				val (segundos, caracteres) = tiempos.getValue(clave)
				linea(getString(R.string.estadistica_linea, getString(nombre), tiempoHablado(segundos * 1000, conSegundos = false), (caracteres / 6).toInt()))
			}
			// Racha: días seguidos con lectura hasta hoy (o ayer)
			var racha = 0
			var dia = 0
			val conjunto = dias.toSet()
			if (hace(0) !in conjunto) dia = 1
			while (hace(dia) in conjunto) {
				racha++
				dia++
			}
			linea(resources.getQuantityString(R.plurals.racha, racha, racha))
			linea(getString(R.string.biblioteca_resumen), true)
			linea(resources.getQuantityString(R.plurals.libros, todos.size, todos.size))
			linea(getString(R.string.libros_terminados_n, todos.count { it.estado == Estado.TERMINADO }))
			linea(getString(R.string.libros_leyendo_n, todos.count { it.estado == Estado.LEYENDO }))
			if (masLeidos.isNotEmpty()) {
				linea(getString(R.string.mas_leidos), true)
				masLeidos.forEach { linea("${it.tituloVisible}: ${tiempoHablado(it.segundos * 1000, conSegundos = false)}") }
			}
		}
	}
}
