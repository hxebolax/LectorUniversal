package com.xebolax.lector

import android.util.Log
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xebolax.lector.actualizaciones.Actualizador
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fuentes de actualización contra lo publicado de verdad (necesita internet):
 * servidor propio, GitHub, los dos y una dirección personalizada (de GitHub o
 * una carpeta web). Todas tienen que dar la misma versión, con su APK, tamaño
 * y SHA-256.
 */
@RunWith(AndroidJUnit4::class)
class ActualizacionesTest {

	private val app = InstrumentationRegistry.getInstrumentation().targetContext
	private val p = PreferenceManager.getDefaultSharedPreferences(app)

	@After
	fun dejarComoEstaba() {
		p.edit().remove("fuente_actualizaciones").remove("url_personalizada").commit()
	}

	private fun leer(fuente: String, personalizada: String = "") = runBlocking {
		p.edit().putString("fuente_actualizaciones", fuente).putString("url_personalizada", personalizada).commit()
		Actualizador.versionPublicada(app).also { v ->
			Log.i("Actualizaciones", "$fuente $personalizada -> ${v.version} (${v.versionCode}) ${v.url} ${v.tamano} bytes ${v.sha256} desde ${v.origen}")
			assertTrue(v.url.endsWith(".apk"))
			assertTrue(v.tamano > 1_000_000)
			assertEquals(64, v.sha256.length)
		}
	}

	/** El servidor propio puede no responder desde el emulador (la red de casa): entonces no cuenta. */
	private fun leerServidor(fuente: String, personalizada: String = "") = try {
		leer(fuente, personalizada)
	} catch (e: java.io.IOException) {
		Log.w("Actualizaciones", "$fuente $personalizada: el servidor no responde desde aquí ($e)")
		null
	}

	@Test
	fun todasLasFuentesDanLaUltimaVersion() {
		val github = leer("github")
		assertEquals("GitHub", github.origen)
		assertTrue(github.url.startsWith("https://github.com/"))
		// Los dos: aunque el servidor no responda, vale GitHub
		val ambos = leer("ambos")
		val repo = leer("personalizada", "https://github.com/${BuildConfig.REPO_GITHUB}")
		val servidor = leerServidor("servidor")
		val carpeta = leerServidor("personalizada", BuildConfig.URL_ACTUALIZACIONES)
		for (v in listOfNotNull(ambos, repo, servidor, carpeta)) {
			assertEquals(github.version, v.version)
			assertEquals(github.versionCode, v.versionCode)
			assertEquals(github.sha256, v.sha256)
			assertEquals(github.tamano, v.tamano)
		}
	}

	/**
	 * Descarga el APK de GitHub (que redirige a su almacén de archivos) y lo
	 * verifica: tamaño y SHA-256 tienen que coincidir. La app de pruebas tiene otro
	 * nombre de paquete, así que la última comprobación lo rechaza, como debe.
	 */
	@Test
	fun descargaDesdeGitHub() {
		val v = leer("github")
		var ultimo = 0L
		val error = runCatching { runBlocking { Actualizador.descargar(app, v) { leidos, _ -> ultimo = leidos } } }.exceptionOrNull()
		Log.i("Actualizaciones", "Descargados $ultimo de ${v.tamano} bytes; resultado: $error")
		assertEquals(v.tamano, ultimo)
		// En el idioma de la app (el del emulador puede ser otro)
		assertEquals(com.xebolax.lector.LectorApp.texto(R.string.cj_act_no_lector), error?.message)
	}
}
