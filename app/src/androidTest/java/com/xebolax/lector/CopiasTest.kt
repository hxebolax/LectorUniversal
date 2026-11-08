package com.xebolax.lector

import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xebolax.lector.copias.Cifrado
import com.xebolax.lector.copias.CopiaSeguridad
import com.xebolax.lector.copias.CopiaSeguridad.Parte
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Secretos
import com.xebolax.lector.formatos.Formato
import com.xebolax.lector.radio.Emisora
import com.xebolax.lector.radio.RadioBaseDatos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * El gestor de copias de seguridad de principio a fin: crear con contraseña,
 * cambiar los datos, restaurar todo o una parte, deshacer, y las claves
 * apartadas de los ajustes (y de la copia de Android si el usuario no quiere).
 */
@RunWith(AndroidJUnit4::class)
class CopiasTest {

	private val app = InstrumentationRegistry.getInstrumentation().targetContext
	private val p = PreferenceManager.getDefaultSharedPreferences(app)
	private val db = BaseDatos.obtener(app)
	private val radio = RadioBaseDatos.obtener(app)
	private val emisora = Emisora(uuid = "copia-prueba", nombre = "Emisora de la copia", url = "http://127.0.0.1/copia", pais = "Spain", codigoPais = "ES")
	private val contrasena = "contraseña de prueba"

	private fun libroDePrueba(): Long {
		val f = File(app.filesDir, "textos/copia_prueba.txt").apply { parentFile?.mkdirs(); writeText("Texto guardado en la app para la copia.") }
		return db.libroPorUri(android.net.Uri.fromFile(f).toString())?.id
			?: db.anadirLibro(android.net.Uri.fromFile(f).toString(), f.name, "Libro de la copia", "", Formato.TXT.name, false, f.length(), null)
	}

	private fun crear(partes: Set<Parte>, clave: String?): File {
		val f = File(app.cacheDir, "prueba.${CopiaSeguridad.EXTENSION}")
		val inicio = SystemClock.elapsedRealtime()
		f.outputStream().buffered().use { CopiaSeguridad.crear(app, it, partes, clave) }
		Log.i("Copias", "Copia de ${partes.size} partes, ${f.length()} bytes, con contraseña=${clave != null}: ${SystemClock.elapsedRealtime() - inicio} ms")
		return f
	}

	@Test
	fun crearCambiarYRestaurarTodo() {
		Secretos.cambiarCopiaAndroid(app, false)
		p.edit().putBoolean(Secretos.OPCION_COPIA_ANDROID, false).commit()
		val libro = libroDePrueba()
		if (!radio.esFavorita(emisora.uuid)) radio.anadirFavorita(emisora)
		p.edit().putString("portada_elementos", "5").commit()
		Secretos.guardar(app, "ia_clave_gemini", "clave-de-prueba-123")
		Secretos.guardar(app, Secretos.deCatalogo("https://catalogo.ejemplo/opds"), "secreto-del-catalogo")

		val copia = crear(Parte.entries.toSet(), contrasena)

		// Lo que lleva, y la contraseña
		try {
			copia.inputStream().use { CopiaSeguridad.info(it, null) }
			fail("Abrió sin contraseña")
		} catch (e: CopiaSeguridad.NecesitaContrasena) {
			// Bien
		}
		try {
			copia.inputStream().use { CopiaSeguridad.info(it, "otra contraseña") }
			fail("Abrió con otra contraseña")
		} catch (e: Cifrado.ContrasenaIncorrecta) {
			// Bien
		}
		val info = copia.inputStream().use { CopiaSeguridad.info(it, contrasena) }
		Log.i("Copias", "Partes: ${info.partes} cifrada=${info.cifrada} dispositivo=${info.dispositivo}")
		assertTrue(info.cifrada)
		assertTrue(Parte.BIBLIOTECA in info.partes && Parte.CLAVES in info.partes && Parte.AJUSTES in info.partes && Parte.ARCHIVOS in info.partes)

		// Se cambia todo...
		db.quitarLibro(libro)
		File(app.filesDir, "textos/copia_prueba.txt").delete()
		radio.quitarFavorita(emisora.uuid)
		p.edit().putString("portada_elementos", "10").commit()
		Secretos.guardar(app, "ia_clave_gemini", "otra-clave")
		Secretos.guardar(app, Secretos.deCatalogo("https://catalogo.ejemplo/opds"), "")

		// ...y se restaura
		copia.inputStream().buffered().use { CopiaSeguridad.restaurar(app, it, contrasena, info.partes.toSet()) }
		assertNotNull("El libro no volvió", BaseDatos.obtener(app).todos().firstOrNull { it.tituloVisible == "Libro de la copia" })
		assertTrue("El archivo del libro no volvió", File(app.filesDir, "textos/copia_prueba.txt").exists())
		assertTrue("La favorita no volvió", RadioBaseDatos.obtener(app).esFavorita(emisora.uuid))
		assertEquals("5", p.getString("portada_elementos", null))
		assertEquals("clave-de-prueba-123", Secretos.leer(app, "ia_clave_gemini"))
		assertEquals("secreto-del-catalogo", Secretos.leer(app, Secretos.deCatalogo("https://catalogo.ejemplo/opds")))
		copia.delete()
	}

	@Test
	fun restaurarSoloUnaParte() {
		val libro = libroDePrueba()
		p.edit().putString("portada_elementos", "5").commit()
		val copia = crear(setOf(Parte.BIBLIOTECA, Parte.AJUSTES), null)
		val info = copia.inputStream().use { CopiaSeguridad.info(it, null) }
		assertFalse(info.cifrada)
		assertEquals(listOf(Parte.BIBLIOTECA, Parte.AJUSTES), info.partes)
		db.quitarLibro(libro)
		p.edit().putString("portada_elementos", "10").commit()
		// Solo los ajustes: la biblioteca se queda como está (sin el libro)
		copia.inputStream().buffered().use { CopiaSeguridad.restaurar(app, it, null, setOf(Parte.AJUSTES)) }
		assertEquals("5", p.getString("portada_elementos", null))
		assertNull(BaseDatos.obtener(app).todos().firstOrNull { it.tituloVisible == "Libro de la copia" })
		copia.delete()
	}

	@Test
	fun clavesSoloConContrasena() {
		try {
			crear(setOf(Parte.CLAVES), null)
			fail("Guardó las claves sin contraseña")
		} catch (e: IllegalArgumentException) {
			// Bien
		}
	}

	@Test
	fun deshacerLaUltimaRestauracion() {
		p.edit().putString("portada_elementos", "3").commit()
		val copia = crear(setOf(Parte.AJUSTES), null)
		p.edit().putString("portada_elementos", "10").commit()
		// Como hace el gestor: antes de restaurar, cómo está todo
		CopiaSeguridad.guardarCopiaAnterior(app)
		copia.inputStream().buffered().use { CopiaSeguridad.restaurar(app, it, null, setOf(Parte.AJUSTES)) }
		assertEquals("3", p.getString("portada_elementos", null))
		val anterior = CopiaSeguridad.copiaAnterior(app)
		assertTrue(anterior.exists())
		val info = anterior.inputStream().use { CopiaSeguridad.info(it, null) }
		anterior.inputStream().buffered().use { CopiaSeguridad.restaurar(app, it, null, info.partes.toSet()) }
		assertEquals("10", p.getString("portada_elementos", null))
		anterior.delete()
		copia.delete()
	}

	@Test
	fun clavesEnLaCopiaDeAndroidSoloSiSeElige() {
		val privado = app.getSharedPreferences(Secretos.ARCHIVO_PRIVADO, 0)
		val conCopia = app.getSharedPreferences(Secretos.ARCHIVO_CON_COPIA, 0)
		Secretos.cambiarCopiaAndroid(app, false)
		p.edit().putBoolean(Secretos.OPCION_COPIA_ANDROID, false).commit()
		Secretos.guardar(app, "sinc_clave", "contraseña-webdav")
		assertEquals("contraseña-webdav", privado.getString("sinc_clave", null))
		assertTrue(conCopia.all.isEmpty())
		// El usuario lo activa: pasan al archivo que Android copia
		Secretos.cambiarCopiaAndroid(app, true)
		p.edit().putBoolean(Secretos.OPCION_COPIA_ANDROID, true).commit()
		assertEquals("contraseña-webdav", conCopia.getString("sinc_clave", null))
		assertTrue(privado.all.isEmpty())
		assertEquals("contraseña-webdav", Secretos.leer(app, "sinc_clave"))
		// Y lo desactiva otra vez
		Secretos.cambiarCopiaAndroid(app, false)
		p.edit().putBoolean(Secretos.OPCION_COPIA_ANDROID, false).commit()
		assertTrue(conCopia.all.isEmpty())
		assertEquals("contraseña-webdav", Secretos.leer(app, "sinc_clave"))
		Secretos.guardar(app, "sinc_clave", "")
	}

	@Test
	fun lasClavesSalenDeLosAjustesNormales() {
		// Como estaban hasta la 1.0.9
		p.edit().putString("ia_clave_openai", "sk-antigua").putBoolean("secretos_apartados", false).commit()
		Secretos.migrar(app)
		assertNull(p.getString("ia_clave_openai", null))
		assertEquals("sk-antigua", Secretos.leer(app, "ia_clave_openai"))
		assertEquals("sk-antigua", com.xebolax.lector.datos.Preferencias(app).iaClaveOpenai)
		Secretos.guardar(app, "ia_clave_openai", "")
	}
}
