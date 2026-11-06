package com.xebolax.lector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xebolax.lector.ocr.Ocr
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ¿Funciona sin Google Play Services? Se pasa con Play Services desactivado
 * (adb shell pm disable-user com.google.android.gms): reconoce el texto de
 * una imagen y el idioma de una frase con los modelos que van dentro de la app.
 * Deja el resultado en el registro (etiqueta "SinGms").
 */
@RunWith(AndroidJUnit4::class)
class SinPlayServicesTest {

	private val app = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun ocrEIdiomaSinPlayServices() = runBlocking<Unit> {
		val activo = runCatching { app.packageManager.getApplicationInfo("com.google.android.gms", 0).enabled }.getOrDefault(false)
		Log.i("SinGms", "Play Services: ${if (activo) "activo" else "DESACTIVADO"}")
		// Una imagen con texto
		val imagen = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
		Canvas(imagen).apply {
			drawColor(Color.WHITE)
			val p = Paint().apply { color = Color.BLACK; textSize = 64f; isAntiAlias = true }
			drawText("El vencejo vuela muy alto", 40f, 160f, p)
			drawText("sobre la ciudad dormida.", 40f, 260f, p)
		}
		val texto = runCatching { Ocr.reconocer(app, imagen) }
		Log.i("SinGms", "OCR: ${texto.getOrNull()?.replace('\n', ' ') ?: "FALLA: ${texto.exceptionOrNull()}"}")
		val idioma = runCatching {
			com.google.mlkit.nl.languageid.LanguageIdentification.getClient().identifyLanguage("El vencejo vuela muy alto sobre la ciudad dormida y nadie lo ve.").await()
		}
		Log.i("SinGms", "Idioma: ${idioma.getOrNull() ?: "FALLA: ${idioma.exceptionOrNull()}"}")
		val escaner = runCatching {
			com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(
				com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder().build()
			)
		}
		Log.i("SinGms", "Escáner de Google: ${if (escaner.isSuccess) "cliente creado" else "FALLA: ${escaner.exceptionOrNull()}"}")
	}

	/** El escáner con guía abre la cámara sin servicios de Google y dice qué ve. */
	@Test
	fun escanerConGuia() {
		val instr = InstrumentationRegistry.getInstrumentation()
		val ui = instr.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
		ui.grantRuntimePermission(app.packageName, android.Manifest.permission.CAMERA)
		app.startActivity(android.content.Intent(app, com.xebolax.lector.ocr.EscanerActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
		Thread.sleep(10000)
		val r = ui.rootInActiveWindow
		val textos = mutableListOf<String>()
		fun recoger(n: android.view.accessibility.AccessibilityNodeInfo) {
			(n.contentDescription ?: n.text)?.toString()?.takeIf { it.isNotBlank() }?.let { textos += it }
			for (i in 0 until n.childCount) n.getChild(i)?.let { recoger(it) }
		}
		r?.let { recoger(it) }
		Log.i("SinGms", "Escáner con guía: ${textos.joinToString(" | ")}")
	}
}
