package com.xebolax.lector

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xebolax.lector.formatos.CacheContenido
import com.xebolax.lector.formatos.ContextoLectura
import com.xebolax.lector.formatos.Formato
import com.xebolax.lector.formatos.Fuente
import com.xebolax.lector.formatos.Lectores
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.Documento
import com.xebolax.lector.modelo.LibroAudio
import com.xebolax.lector.modelo.TipoBloque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Abre los libros de muestra de cada formato (androidTest/assets/muestras)
 * en el dispositivo y comprueba que salen texto, encabezados, índice,
 * capítulos, páginas... Se pasan con: compilar.bat pruebas-dispositivo
 * (gradle connectedDebugAndroidTest). El resumen de cada libro queda en el
 * registro con la etiqueta "Formatos".
 */
@RunWith(AndroidJUnit4::class)
class FormatosTest {

	private lateinit var app: Context
	private lateinit var carpeta: File

	@Before
	fun preparar() {
		app = InstrumentationRegistry.getInstrumentation().targetContext
		CacheContenido.borrar(app)
		carpeta = File(app.cacheDir, "muestras").apply { deleteRecursively(); mkdirs() }
		copiar(InstrumentationRegistry.getInstrumentation().context, "muestras", carpeta)
	}

	private fun copiar(origen: Context, ruta: String, destino: File) {
		val hijos = origen.assets.list(ruta).orEmpty()
		if (hijos.isEmpty()) {
			origen.assets.open(ruta).use { e -> destino.outputStream().use { e.copyTo(it) } }
			return
		}
		destino.mkdirs()
		for (h in hijos) copiar(origen, "$ruta/$h", File(destino, h))
	}

	private fun abrir(nombre: String): Contenido {
		val archivo = File(carpeta, nombre)
		val fuente = Fuente(app, Uri.fromFile(archivo))
		val formato = Formato.reconocer(fuente)
		val inicio = System.currentTimeMillis()
		val c = Lectores.abrir(fuente, ContextoLectura(app), formato)
		val ms = System.currentTimeMillis() - inicio
		Log.i("Formatos", "=== $nombre ($formato, $ms ms) ===")
		when (c) {
			is Contenido.Texto -> resumen(c.documento)
			is Contenido.Audio -> resumenAudio(c.libro)
		}
		return c
	}

	private fun resumen(d: Documento) {
		Log.i("Formatos", "Título: «${d.titulo}», autor: «${d.autor}», idioma: ${d.idioma}, bloques: ${d.bloques.size}, " +
			"encabezados: ${d.bloques.count { it.tipo == TipoBloque.ENCABEZADO }}, índice: ${d.indice.size}, páginas: ${d.paginas.size}, avisos: ${d.avisos}")
		d.bloques.take(12).forEachIndexed { i, b -> Log.i("Formatos", "  $i ${b.tipo}${if (b.nivel > 0) b.nivel else ""}: ${b.texto.take(90)}") }
		d.indice.take(8).forEach { Log.i("Formatos", "  índice: ${"  ".repeat(it.nivel - 1)}${it.titulo} → ${it.bloque}") }
	}

	private fun resumenAudio(l: LibroAudio) {
		Log.i("Formatos", "Título: «${l.titulo}», autor: «${l.autor}», narrador: «${l.narrador}», pistas: ${l.pistas.size}, " +
			"capítulos: ${l.capitulos.size}, páginas: ${l.paginas.size}, frases: ${l.frases.size}, con texto: ${l.texto != null}")
		l.capitulos.take(10).forEach { Log.i("Formatos", "  capítulo nivel ${it.nivel}: ${it.titulo} (pista ${it.pista}, ${it.ms} ms)") }
	}

	private fun texto(nombre: String, minimoBloques: Int = 3): Documento {
		val c = abrir(nombre)
		assertTrue("$nombre debería ser texto", c is Contenido.Texto)
		val d = (c as Contenido.Texto).documento
		assertTrue("$nombre: pocos bloques (${d.bloques.size})", d.bloques.size >= minimoBloques)
		assertTrue("$nombre: sin texto", d.caracteres > 50)
		return d
	}

	private fun conEncabezados(d: Documento, nombre: String) =
		assertTrue("$nombre: sin encabezados", d.bloques.any { it.tipo == TipoBloque.ENCABEZADO })

	@Test fun epub() = texto("vencejo.epub").let { conEncabezados(it, "epub"); assertTrue("epub sin índice", it.indice.isNotEmpty()) }
	@Test fun fb2() = conEncabezados(texto("vencejo.fb2"), "fb2")
	@Test fun mobi() { texto("vencejo.mobi", 2) }
	@Test fun azw() { texto("vencejo.azw", 2) }
	@Test fun txt() { texto("vencejo.txt", 2) }
	@Test fun markdown() = conEncabezados(texto("vencejo.md"), "md")
	@Test fun html() = conEncabezados(texto("vencejo.html"), "html")
	@Test fun docx() = conEncabezados(texto("vencejo.docx"), "docx")
	@Test fun odt() = conEncabezados(texto("vencejo.odt"), "odt")
	@Test fun rtf() { texto("vencejo.rtf", 2) }
	@Test fun pptx() = conEncabezados(texto("vencejo.pptx", 2), "pptx")
	@Test fun odp() = conEncabezados(texto("vencejo.odp", 2), "odp")
	@Test fun xlsx() { texto("vencejo.xlsx", 2) }
	@Test fun ods() { texto("vencejo.ods", 2) }
	@Test fun pdf() { texto("vencejo.pdf", 2) }
	@Test fun chm() = texto("7-zip.chm", 20).let { assertTrue("chm sin índice", it.indice.size > 5) }

	@Test
	fun daisy3Carpeta() {
		val c = abrir("vencejo_daisy")
		assertNotNull(c)
		val d = (c as? Contenido.Texto)?.documento ?: (c as Contenido.Audio).libro.texto
		assertNotNull("DAISY 3 sin texto", d)
		assertTrue(d!!.bloques.isNotEmpty())
	}

	@Test
	fun daisy202Zip() {
		val c = abrir("daisy202.zip")
		assertTrue("DAISY 2.02 debería ser audio", c is Contenido.Audio)
		val l = (c as Contenido.Audio).libro
		assertEquals("Libro DAISY de prueba", l.titulo)
		assertEquals(3, l.capitulos.size)
		assertEquals(listOf(1, 2, 1), l.capitulos.map { it.nivel })
		assertEquals(2, l.paginas.size)
		assertTrue("Sin frases sincronizadas", l.frases.size >= 4)
		assertNotNull("Sin texto sincronizado", l.texto)
		assertTrue("Tildes mal decodificadas", l.capitulos.any { it.titulo == "Capítulo uno" })
	}

	@Test
	fun m4bConCapitulos() {
		val l = (abrir("prueba.m4b") as Contenido.Audio).libro
		assertEquals(3, l.capitulos.size)
		assertEquals("Capítulo dos", l.capitulos[1].titulo)
		assertTrue(l.capitulos[1].ms in 19_000..21_000)
	}

	@Test
	fun mp3ConCapitulos() {
		val l = (abrir("prueba_capitulos.mp3") as Contenido.Audio).libro
		assertEquals(3, l.capitulos.size)
		assertEquals("Capítulo tres", l.capitulos[2].titulo)
	}

	/** La segunda vez sale de la caché y es igual. */
	@Test
	fun cache() {
		val a = texto("vencejo.epub")
		val b = texto("vencejo.epub")
		assertEquals(a.bloques.size, b.bloques.size)
		assertEquals(a.indice.size, b.indice.size)
		assertEquals(a.bloques.last().texto, b.bloques.last().texto)
	}
	/** Un ZIP de imágenes (con o sin la extensión .cbz) es un cómic: una página por imagen, con su ComicInfo.xml. */
	private fun comic(nombre: String, conInfo: Boolean): File {
		val archivo = File(carpeta, nombre)
		java.util.zip.ZipOutputStream(archivo.outputStream()).use { z ->
			for (n in listOf("pag10.png", "pag2.png", "pag1.png")) {
				z.putNextEntry(java.util.zip.ZipEntry("paginas/$n"))
				val mapa = android.graphics.Bitmap.createBitmap(40, 60, android.graphics.Bitmap.Config.ARGB_8888)
				mapa.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, z)
				z.closeEntry()
			}
			if (conInfo) {
				z.putNextEntry(java.util.zip.ZipEntry("ComicInfo.xml"))
				z.write("<ComicInfo><Title>El vencejo valiente</Title><Series>Vencejos</Series><Number>3</Number><Writer>Ana Pérez</Writer></ComicInfo>".toByteArray())
				z.closeEntry()
			}
		}
		return archivo
	}

	@Test
	fun comicCbz() {
		comic("prueba.cbz", conInfo = true)
		val d = texto("prueba.cbz")
		assertEquals("El vencejo valiente", d.titulo)
		assertEquals("Ana Pérez", d.autor)
		assertEquals(3, d.paginas.size)
		assertEquals(3, d.imagenes.size)
		// Orden natural: 1, 2, 10
		assertTrue(d.imagenes.toSortedMap().values.last().endsWith("pag10.png"))
	}

	@Test
	fun zipDeImagenesEsComic() {
		comic("imagenes.zip", conInfo = false)
		assertEquals(Formato.COMIC, Formato.reconocer(Fuente(app, Uri.fromFile(File(carpeta, "imagenes.zip")))))
	}
}
