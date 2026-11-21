package com.xebolax.lector

import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.lectura.LectorActivity
import com.xebolax.lector.voz.LectorVoz
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue 3 de GitHub: con TalkBack, tras escuchar un rato en voz alta y pausar,
 * al volver al libro TalkBack devolvía el foco al párrafo donde se empezó (el
 * foco no sigue a la voz) y, al salir, se guardaba ese párrafo encima de la
 * posición de la voz. Al día siguiente (con la app cerrada) el libro se abría
 * donde se había empezado a leer el día anterior.
 */
@RunWith(AndroidJUnit4::class)
class VozPosicionTest {

	private val instrumentacion = InstrumentationRegistry.getInstrumentation()
	private val app = instrumentacion.targetContext
	private val ui = instrumentacion.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
	private val db = BaseDatos.obtener(app)
	private var libro = -1L

	private fun nodo(texto: String): AccessibilityNodeInfo? {
		repeat(30) {
			ui.rootInActiveWindow?.findAccessibilityNodeInfosByText(texto)?.firstOrNull { it.isVisibleToUser }?.let { return it }
			Thread.sleep(300)
		}
		return null
	}

	private fun abrirLibro(): Long {
		val parrafos = (1..80).joinToString("") { "<p>Párrafo número $it del libro de prueba, con texto suficiente para que la voz tenga algo que leer.</p>" }
		val archivo = File(app.filesDir, "voz_posicion.html").apply { writeText("<html><head><title>Libro de la voz</title></head><body><h1>Libro de la voz</h1>$parrafos</body></html>") }
		val id = db.anadirLibro(Uri.fromFile(archivo).toString(), archivo.name, "Libro de la voz", "", "HTML", false, archivo.length(), null)
		app.startActivity(
			Intent(app, LectorActivity::class.java)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
				.putExtra(LectorActivity.EXTRA_LIBRO, id)
		)
		Thread.sleep(5000)
		return id
	}

	/** Lee en voz alta y hace que la voz llegue al párrafo 40 (bloque 40: el 0 es el título). */
	private fun leerHastaElParrafo40() {
		val voz = nodo(app.getString(R.string.leer_en_voz_alta))
		assertNotNull("no está el botón Voz", voz)
		// Como con TalkBack: el foco va al botón y se pulsa
		voz!!.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
		Thread.sleep(500)
		voz.performAction(AccessibilityNodeInfo.ACTION_CLICK)
		Thread.sleep(2000)
		instrumentacion.runOnMainSync { LectorVoz.irA(40) }
		Thread.sleep(2500)
	}

	/** Como TalkBack al volver: el foco otra vez en el párrafo que tenía, el del principio. */
	private fun focoEnElParrafoDelPrincipio() {
		val viejo = nodo("Párrafo número 1 ")
		assertNotNull("no se ve el párrafo del principio", viejo)
		viejo!!.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
		Thread.sleep(800)
	}

	private fun salirDelLibro() {
		ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
		Thread.sleep(2500)
	}

	private fun bloqueGuardado(): Int = db.libro(libro)?.posicion?.bloque ?: -1

	@Before
	fun permisos() {
		// Sin el diálogo del permiso de avisos encima del libro la primera vez que se lee en voz alta
		ui.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS").close()
	}

	@Test
	fun enPausaNoSePisaLaPosicionDeLaVoz() {
		libro = abrirLibro()
		focoEnElParrafoDelPrincipio()
		leerHastaElParrafo40()
		instrumentacion.runOnMainSync { LectorVoz.pausar() }
		Thread.sleep(1000)
		// Al pausar, la lista va al párrafo de la voz. Si el párrafo 1 aún se viera, el foco
		// repetido en él (TalkBack al volver) no debe contar
		ui.rootInActiveWindow?.findAccessibilityNodeInfosByText("Párrafo número 1 ")?.firstOrNull { it.isVisibleToUser }
			?.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
		Thread.sleep(800)
		assertNotNull("al pausar, la lista no fue al párrafo de la voz", nodo("Párrafo número 40 "))
		salirDelLibro()
		Log.i("VozPosicion", "en pausa: guardado ${bloqueGuardado()}")
		assertEquals(40, bloqueGuardado())
	}

	@Test
	fun enPausaElPanelMueveLaPosicion() {
		libro = abrirLibro()
		leerHastaElParrafo40()
		instrumentacion.runOnMainSync { LectorVoz.pausar() }
		Thread.sleep(800)
		// Párrafo siguiente en el panel con la voz en pausa: se guarda el 41
		instrumentacion.runOnMainSync { LectorVoz.siguienteParrafo() }
		Thread.sleep(800)
		salirDelLibro()
		Log.i("VozPosicion", "panel en pausa: guardado ${bloqueGuardado()}")
		assertEquals(41, bloqueGuardado())
	}

	@Test
	fun alDetenerNoSePisaLaPosicionDeLaVoz() {
		libro = abrirLibro()
		focoEnElParrafoDelPrincipio()
		leerHastaElParrafo40()
		instrumentacion.runOnMainSync { LectorVoz.detener() }
		Thread.sleep(1500)
		// Con TalkBack, al detenerse la pantalla va a donde paró la voz: el párrafo 1 ya no se ve.
		// Si aún se viera, el foco repetido en él no debe contar
		ui.rootInActiveWindow?.findAccessibilityNodeInfosByText("Párrafo número 1 ")?.firstOrNull { it.isVisibleToUser }
			?.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
		Thread.sleep(800)
		salirDelLibro()
		Log.i("VozPosicion", "al detener: guardado ${bloqueGuardado()}")
		assertEquals(40, bloqueGuardado())
	}

	@Test
	fun leerEnVozAltaSigueDesdeLaFraseGuardada() {
		libro = abrirLibro()
		leerHastaElParrafo40()
		instrumentacion.runOnMainSync { LectorVoz.detener() }
		salirDelLibro()
		// Al día siguiente: la app se abre de nuevo y Leer en voz alta empieza en el párrafo 40
		app.startActivity(
			Intent(app, LectorActivity::class.java)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
				.putExtra(LectorActivity.EXTRA_LIBRO, libro)
		)
		Thread.sleep(5000)
		nodo(app.getString(R.string.leer_en_voz_alta))!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
		Thread.sleep(2000)
		var bloque = -1
		instrumentacion.runOnMainSync { bloque = LectorVoz.bloque }
		assertEquals(40, bloque)
	}

	@After
	fun limpiar() {
		instrumentacion.runOnMainSync { LectorVoz.detener() }
		if (libro > 0) db.quitarLibro(libro)
	}
}
