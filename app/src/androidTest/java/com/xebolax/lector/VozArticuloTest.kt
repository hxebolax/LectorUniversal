package com.xebolax.lector

import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xebolax.lector.lectura.LectorActivity
import com.xebolax.lector.rss.RssAcciones
import com.xebolax.lector.rss.RssBaseDatos
import com.xebolax.lector.rss.RssLector
import com.xebolax.lector.voz.LectorVoz
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Leer un artículo de Noticias en voz alta con TalkBack: al pulsar Voz tiene
 * que empezar a sonar (no quedarse en «Pausar» callado). Deja en el registro
 * (etiqueta "VozArticulo") si suena cada segundo.
 */
@RunWith(AndroidJUnit4::class)
class VozArticuloTest {

	private val instrumentacion = InstrumentationRegistry.getInstrumentation()
	private val app = instrumentacion.targetContext
	private val ui = instrumentacion.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

	private fun nodo(vararg textos: String): AccessibilityNodeInfo? {
		repeat(20) {
			val r = ui.rootInActiveWindow
			if (r != null) for (t in textos) r.findAccessibilityNodeInfosByText(t).firstOrNull { it.isVisibleToUser && (it.contentDescription ?: it.text)?.toString() == t }?.let { return it }
			Thread.sleep(300)
		}
		return null
	}

	private fun estado(): String {
		var s = ""
		instrumentacion.runOnMainSync {
			val e = LectorVoz.estado()
			val j = LectorVoz.jugador()
			s = "leyendo=${e.leyendo} suena=${j.isPlaying} playWhenReady=${j.playWhenReady} estado=${j.playbackState} elementos=${j.mediaItemCount} supresion=${j.playbackSuppressionReason}"
		}
		return s
	}

	@Test
	fun vozEmpiezaAlPulsar() {
		val db = RssBaseDatos.obtener(app)
		val fuente = db.fuentePorUrl("https://prueba.invalid/rss")?.id ?: db.anadirFuente("https://prueba.invalid/rss", "Fuente de prueba")
		val texto = (1..12).joinToString("") { "<p>Este es el párrafo número $it del artículo de prueba, con texto suficiente para que la voz tenga algo que leer durante un rato.</p>" }
		db.anadirArticulos(fuente, listOf(RssLector.Elemento("prueba-voz-1", "Artículo de prueba", "", "", System.currentTimeMillis(), "", texto, "", "", "")))
		val articulo = db.deFuente(fuente, false).first()
		val html = File(app.cacheDir, "articulo_prueba.html").apply { writeText("<html><head><title>Artículo de prueba</title></head><body><h1>Artículo de prueba</h1>$texto</body></html>") }
		app.startActivity(
			Intent(app, LectorActivity::class.java)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
				.putExtra(LectorActivity.EXTRA_URI, Uri.fromFile(html).toString())
				.putExtra(LectorActivity.EXTRA_TITULO, articulo.titulo)
				.putExtra(RssAcciones.EXTRA_ARTICULO, articulo.id)
				.putExtra(RssAcciones.EXTRA_LISTA, longArrayOf(articulo.id))
		)
		Thread.sleep(6000)
		val voz = nodo(app.getString(R.string.leer_en_voz_alta), "Leer en voz alta", "Read aloud")
		if (voz == null) {
			Log.w("VozArticulo", "No se encontró el botón Voz")
			return
		}
		// Como con TalkBack: foco en el botón y doble toque
		voz.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
		Thread.sleep(1500)
		voz.performAction(AccessibilityNodeInfo.ACTION_CLICK)
		for (i in 1..30) {
			Thread.sleep(1000)
			Log.i("VozArticulo", "segundo $i | ${estado()}")
		}
		instrumentacion.runOnMainSync { LectorVoz.detener() }
	}
}
