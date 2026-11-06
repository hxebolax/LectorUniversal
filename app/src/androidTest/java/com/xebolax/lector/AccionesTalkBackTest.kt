package com.xebolax.lector

import android.app.UiAutomation
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xebolax.lector.ui.AccionesTalkBack
import com.xebolax.lector.ui.AccionesTalkBackActivity
import com.xebolax.lector.ui.ZonaAcciones
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Acciones de TalkBack personalizables: qué se filtra y en qué orden, y la pantalla de Ajustes con TalkBack. */
@RunWith(AndroidJUnit4::class)
class AccionesTalkBackTest {

	private val instrumentacion = InstrumentationRegistry.getInstrumentation()
	private val app = instrumentacion.targetContext
	private val nada = {}

	private fun textos(vararg ids: Int) = ids.map { app.getString(it) to nada }

	@Test
	fun filtraYOrdena() {
		ZonaAcciones.entries.forEach { AccionesTalkBack.restablecer(app, it) }
		// Sin tocar nada, los libros tienen todas las de siempre
		val libro = textos(R.string.abrir, R.string.informacion, R.string.anadir_favoritos, R.string.quitar_de_biblioteca)
		assertEquals(libro.map { it.first }, AccionesTalkBack.aplicar(app, ZonaAcciones.LIBRO, libro).map { it.first })
		// Solo favorito y abrir, en ese orden; lo desconocido sale al final
		AccionesTalkBack.guardar(app, ZonaAcciones.LIBRO, listOf("favorito", "abrir"))
		val conOtra = textos(R.string.abrir, R.string.informacion, R.string.quitar_favoritos) + ("Otra cosa" to nada)
		assertEquals(listOf(app.getString(R.string.quitar_favoritos), app.getString(R.string.abrir), "Otra cosa"),
			AccionesTalkBack.aplicar(app, ZonaAcciones.LIBRO, conOtra).map { it.first })
		// Los textos con datos se reconocen por su parte fija
		assertEquals("enlaces", AccionesTalkBack.claveDe(app, ZonaAcciones.PARRAFO, app.getString(R.string.abrir_enlace_x, "la web de la ONCE")))
		// Podcasts: por defecto solo las de siempre (Opciones del podcast), no Actualizar
		val podcast = textos(R.string.pod_opciones_podcast, R.string.abrir, R.string.rss_actualizar)
		assertEquals(listOf(app.getString(R.string.pod_opciones_podcast)), AccionesTalkBack.aplicar(app, ZonaAcciones.PODCAST, podcast).map { it.first })
		AccionesTalkBack.restablecer(app, ZonaAcciones.LIBRO)
		assertEquals(libro.size, AccionesTalkBack.aplicar(app, ZonaAcciones.LIBRO, libro).size)
	}

	@Test
	fun pantallaConTalkBack() {
		AccionesTalkBack.restablecer(app, ZonaAcciones.ARTICULO)
		val ui = instrumentacion.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
		app.startActivity(Intent(app, AccionesTalkBackActivity::class.java).putExtra(AccionesTalkBackActivity.EXTRA_ZONA, ZonaAcciones.ARTICULO.name)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
		Thread.sleep(3000)
		fun fila(texto: String): AccessibilityNodeInfo? {
			var n: AccessibilityNodeInfo? = ui.rootInActiveWindow?.findAccessibilityNodeInfosByText(texto)?.firstOrNull { it.isVisibleToUser }
			while (n != null && !n.isClickable) n = n.parent
			return n
		}
		fun describir(n: AccessibilityNodeInfo?) = n?.let { "${it.contentDescription} | ${it.className} marcable=${it.isCheckable} activado=${it.isChecked} | acciones: " +
			it.actionList.filter { a -> a.id > 0x01FFFFFF || a.label != null }.joinToString { a -> a.label?.toString().orEmpty() } } ?: "no encontrada"
		val compartir = app.getString(R.string.compartir)
		var n = fila(compartir)
		Log.i("AccionesTB", "Antes: ${describir(n)}")
		n?.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
		Thread.sleep(800)
		// Doble toque: deja de salir
		n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
		Thread.sleep(1500)
		Log.i("AccionesTB", "Tras quitarla: ${describir(fila(compartir))} | foco en: ${ui.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.contentDescription}")
		Log.i("AccionesTB", "Elegidas: ${AccionesTalkBack.elegidas(app, ZonaAcciones.ARTICULO)}")
		// Otra vez: vuelve, al final; luego subirla una posición con su acción
		fila(compartir)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
		Thread.sleep(1500)
		n = fila(compartir)
		val subir = n?.actionList?.firstOrNull { it.label?.toString() == app.getString(R.string.accion_subir) }
		subir?.let { n?.performAction(it.id) }
		Thread.sleep(1500)
		Log.i("AccionesTB", "Tras subirla: ${describir(fila(compartir))} | elegidas: ${AccionesTalkBack.elegidas(app, ZonaAcciones.ARTICULO)}")
		AccionesTalkBack.restablecer(app, ZonaAcciones.ARTICULO)
	}
}
