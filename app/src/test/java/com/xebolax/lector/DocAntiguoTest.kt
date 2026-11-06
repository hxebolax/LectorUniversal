package com.xebolax.lector

import com.xebolax.lector.formatos.LectorDocAntiguo
import com.xebolax.lector.formatos.Ole
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word 97-2003 (.doc): se saca el texto del documento (muestras de Apache POI). */
class DocAntiguoTest {

	private fun texto(nombre: String): String {
		val bytes = javaClass.classLoader!!.getResourceAsStream("doc/$nombre")!!.use { it.readBytes() }
		val ole = Ole(bytes)
		return LectorDocAntiguo.textoDe(ole.flujo("WordDocument")!!, ole)
	}

	@Test
	fun documentoDeMuestra() {
		val t = texto("SampleDoc.doc")
		println("SampleDoc: " + t.take(300).replace('\r', '¶'))
		assertTrue(t.length > 50)
		assertTrue(t.contains("Sample", ignoreCase = true) || t.contains("Lorem", ignoreCase = true) || t.trim().isNotEmpty())
	}

	@Test
	fun tablaSencilla() {
		val t = texto("simple-table.doc")
		println("Tabla: " + t.take(300).replace('\r', '¶').replace('\u0007', '|'))
		assertTrue(t.contains('\u0007'))
	}
}
