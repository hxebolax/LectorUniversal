package com.xebolax.lector

import android.app.UiAutomation
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xebolax.lector.catalogo.NavegadorBibliotecaActivity
import com.xebolax.lector.datos.BaseDatos
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Bibliotecas web dentro de la app: su web se lee con TalkBack y lo descargado se añade a la biblioteca. */
@RunWith(AndroidJUnit4::class)
class NavegadorBibliotecaTest {

	private val instrumentacion = InstrumentationRegistry.getInstrumentation()
	private val app = instrumentacion.targetContext
	private val ui = instrumentacion.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

	private fun abrir(titulo: String, url: String) {
		instrumentacion.runOnMainSync {
			NavegadorBibliotecaActivity.abrir(object : android.content.ContextWrapper(app.applicationContext) {
				override fun startActivity(i: Intent) = super.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
			}, titulo, url)
		}
	}

	private fun textos(): String {
		val lista = mutableListOf<String>()
		fun recoger(n: AccessibilityNodeInfo) {
			val t = listOfNotNull(n.contentDescription?.toString(), n.text?.toString(), n.hintText?.toString()).firstOrNull { it.isNotBlank() }
			if (t != null && (n.isClickable || n.isEditable || n.childCount == 0)) lista += (if (n.isEditable) "[campo] " else "") + t.take(60)
			for (i in 0 until n.childCount) n.getChild(i)?.let { recoger(it) }
		}
		ui.rootInActiveWindow?.let { recoger(it) }
		return lista.distinct().take(25).joinToString(" | ")
	}

	@Test
	fun websSeLeen() {
		abrir("Tiflolibros", "https://www.tiflolibros.tiflonexos.org/")
		Thread.sleep(12000)
		Log.i("NavegadorBib", "Tiflolibros: ${textos()}")
	}

	@Test
	fun webOnce() {
		abrir("Biblioteca Digital ONCE (ClubONCE)", "https://club.once.es/")
		Thread.sleep(15000)
		Log.i("NavegadorBib", "ONCE: ${textos()}")
	}

	@Test
	fun descargaVaALaBiblioteca() {
		val carpeta = File(app.filesDir, "biblioteca/descargas")
		val antes = carpeta.listFiles()?.map { it.name }?.toSet().orEmpty()
		// Un EPUB que el navegador no muestra, sino que descarga (como el ZIP de una biblioteca)
		abrir("Prueba de descarga", "https://www.gutenberg.org/ebooks/2000.epub.noimages")
		Thread.sleep(30000)
		val nuevos = carpeta.listFiles()?.filter { it.name !in antes && !it.name.endsWith(".parte") }.orEmpty()
		Log.i("NavegadorBib", "Descargado: ${nuevos.map { "${it.name} ${it.length()} bytes" }} | pantalla: ${textos()}")
		assertTrue(nuevos.isNotEmpty())
		val libro = BaseDatos.obtener(app).libroPorUri(android.net.Uri.fromFile(nuevos.first()).toString())
		Log.i("NavegadorBib", "En la biblioteca: ${libro?.tituloVisible} (${libro?.formato})")
		assertTrue(libro != null)
		libro?.let { BaseDatos.obtener(app).quitarLibro(it.id) }
		nuevos.forEach { it.delete() }
	}

	/** El nombre del archivo: el del servidor (con tildes, filename*) o el título del libro y la clase de descarga. */
	@Test
	fun nombresDeDescargas() {
		val carpeta = File(app.filesDir, "biblioteca/descargas").apply { mkdirs() }
		val antes = carpeta.listFiles()?.map { it.name }?.toSet().orEmpty()
		// 1. El servidor manda el nombre con tildes en filename*
		val conNombre = "https://httpbin.org/response-headers?Content-Type=application/zip&Content-Disposition=" +
			android.net.Uri.encode("attachment; filename=\"Cien anos.zip\"; filename*=UTF-8''Cien%20a%C3%B1os%20de%20soledad.zip")
		abrir("Prueba nombre", conNombre)
		Thread.sleep(15000)
		// 2. Una página de libro con un botón «DAISY» cuya descarga no trae nombre
		val html = "<html><head><title>Ficha - Biblioteca de prueba</title></head><body><h1>Marianela</h1>" +
			"<a href=\"https://httpbin.org/response-headers?Content-Type=application/zip\">DAISY</a></body></html>"
		abrir("Biblioteca de prueba", "data:text/html;charset=utf-8," + android.net.Uri.encode(html))
		Thread.sleep(6000)
		Log.i("NavegadorBib", "Página de prueba: ${textos()}")
		fun buscar(n: AccessibilityNodeInfo?, t: String): AccessibilityNodeInfo? {
			n ?: return null
			if ((n.text ?: n.contentDescription)?.toString()?.trim() == t) return n
			for (i in 0 until n.childCount) buscar(n.getChild(i), t)?.let { return it }
			return null
		}
		// La página tarda en llegar al árbol de accesibilidad: se espera a que salga el botón
		var boton: AccessibilityNodeInfo? = null
		val limite = System.currentTimeMillis() + 20000
		while (boton == null && System.currentTimeMillis() < limite) {
			boton = buscar(ui.rootInActiveWindow, "DAISY")
			if (boton == null) Thread.sleep(500)
		}
		Log.i("NavegadorBib", "Botón DAISY: ${boton != null} pulsado=${boton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)}")
		Thread.sleep(15000)
		Log.i("NavegadorBib", "Tras pulsar: ${textos()}")
		val nuevos = carpeta.listFiles()?.filter { it.name !in antes && !it.name.endsWith(".parte") }?.map { it.name }.orEmpty()
		Log.i("NavegadorBib", "Nombres: $nuevos")
		assertTrue("Cien años de soledad.zip" in nuevos)
		assertTrue(nuevos.any { it.startsWith("Marianela - DAISY") })
		val db = BaseDatos.obtener(app)
		nuevos.forEach { n -> db.libroPorUri(android.net.Uri.fromFile(File(carpeta, n)).toString())?.let { db.quitarLibro(it.id) }; File(carpeta, n).delete() }
	}
}
