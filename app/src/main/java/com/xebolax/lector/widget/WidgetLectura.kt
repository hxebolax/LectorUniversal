package com.xebolax.lector.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.xebolax.lector.R
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.audio.ReproductorActivity
import com.xebolax.lector.lectura.Abiertos
import com.xebolax.lector.lectura.LectorActivity
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.ui.PortadaActivity
import com.xebolax.lector.voz.LectorVoz
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Widget de la pantalla de inicio: el libro que estás leyendo, «Seguir
 * leyendo» (abre el libro donde lo dejaste) y Leer en voz alta / Pausar sin
 * abrir la app.
 */
class WidgetLectura : AppWidgetProvider() {

	companion object {
		private const val ALTERNAR = "com.xebolax.lector.widget.ALTERNAR"
		private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main)

		/** Vuelve a dibujar los widgets (cambió el libro o el estado de la lectura). */
		fun actualizar(contexto: Context) {
			val gestor = AppWidgetManager.getInstance(contexto)
			val ids = runCatching { gestor.getAppWidgetIds(ComponentName(contexto, WidgetLectura::class.java)) }.getOrNull() ?: return
			if (ids.isEmpty()) return
			ambito.launch {
				val vistas = withContext(Dispatchers.IO) { vistas(contexto) }
				gestor.updateAppWidget(ids, vistas)
			}
		}

		private fun vistas(contexto: Context): RemoteViews {
			val v = RemoteViews(contexto.packageName, R.layout.widget_lectura)
			val estado = LectorVoz.estado()
			val libro = if (estado.activo) BaseDatos.obtener(contexto).libro(estado.libro) else BaseDatos.obtener(contexto).ultimoAbierto()
			val titulo = libro?.tituloVisible ?: estado.titulo.ifBlank { contexto.getString(R.string.widget_sin_libro) }
			v.setTextViewText(R.id.widget_titulo, titulo)
			val detalle = libro?.let { contexto.getString(R.string.porcentaje_n, it.progreso.toInt()) }.orEmpty()
			v.setTextViewText(R.id.widget_detalle, detalle)
			// Seguir leyendo: el libro en su pantalla; sin libros, la Portada
			val abrir = if (libro != null) {
				val destino = if (libro.audio) ReproductorActivity::class.java else LectorActivity::class.java
				Intent(contexto, destino).putExtra(LectorActivity.EXTRA_LIBRO, libro.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			} else Intent(contexto, PortadaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
			v.setOnClickPendingIntent(R.id.widget_abrir, PendingIntent.getActivity(contexto, 1, abrir, flags))
			v.setContentDescription(R.id.widget_abrir, contexto.getString(R.string.widget_seguir_leyendo_x, titulo))
			val leyendo = estado.leyendo
			v.setImageViewResource(R.id.widget_voz, if (leyendo) R.drawable.ic_pausa else R.drawable.ic_reproducir)
			v.setContentDescription(R.id.widget_voz, contexto.getString(if (leyendo) R.string.pausar_lectura else R.string.leer_en_voz_alta))
			val alternar = Intent(contexto, WidgetLectura::class.java).setAction(ALTERNAR)
			v.setOnClickPendingIntent(R.id.widget_voz, PendingIntent.getBroadcast(contexto, 2, alternar, flags))
			return v
		}
	}

	override fun onUpdate(contexto: Context, gestor: AppWidgetManager, ids: IntArray) {
		actualizar(contexto)
	}

	override fun onReceive(contexto: Context, intent: Intent) {
		super.onReceive(contexto, intent)
		if (intent.action != ALTERNAR) return
		LectorVoz.iniciar(contexto)
		if (LectorVoz.documento != null) {
			LectorVoz.alternar()
			return
		}
		// Nada cargado: abre el último libro y lee desde donde se quedó (un audiolibro, en su pantalla)
		val libro = BaseDatos.obtener(contexto).ultimoAbierto() ?: return
		if (libro.audio) {
			Abiertos.abrirPantalla(contexto.applicationContext, libro)
			return
		}
		val pendiente = goAsync()
		ambito.launch {
			try {
				val app = contexto.applicationContext
				val contenido = runCatching { withContext(Dispatchers.IO) { Abiertos.abrir(app, libro, Abiertos.opciones(app)) } }.getOrNull()
				val doc = (contenido as? Contenido.Texto)?.documento ?: return@launch
				val pos = libro.posicion?.takeIf { !it.audio }
				LectorVoz.leerDesde(doc, libro.id, doc.titulo.ifBlank { libro.tituloVisible }, pos?.bloque ?: 0, pos?.caracter ?: 0)
			} finally {
				pendiente.finish()
			}
		}
	}
}
