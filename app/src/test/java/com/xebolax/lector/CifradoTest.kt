package com.xebolax.lector

import com.xebolax.lector.copias.Cifrado
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.random.Random

/** El cifrado de las copias de seguridad: derivar la clave y cifrar por bloques. */
class CifradoTest {

	private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

	@Test
	fun pbkdf2ConVectoresConocidos() {
		val sal = "salt".toByteArray()
		assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", hex(Cifrado.derivar("password", sal, 1)))
		assertEquals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43", hex(Cifrado.derivar("password", sal, 2)))
		assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a", hex(Cifrado.derivar("password", sal, 4096)))
	}

	@Test
	fun pbkdf2IgualQueElDeJava() {
		val fabrica = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
		for (contrasena in listOf("contraseña con eñe y tildes", "12345678", "una frase bastante larga para la copia")) {
			val sal = Random.nextBytes(16)
			val java = fabrica.generateSecret(PBEKeySpec(contrasena.toCharArray(), sal, 1000, 256)).encoded
			assertArrayEquals(java, Cifrado.derivar(contrasena, sal, 1000))
		}
	}

	private val clave = Random.nextBytes(32)
	private val prefijo = Random.nextBytes(7)
	private val cabecera = "LUCOPIA-prueba".toByteArray()

	private fun cifrar(datos: ByteArray, k: ByteArray = clave): ByteArray {
		val salida = ByteArrayOutputStream()
		Cifrado.Salida(salida, k, prefijo, cabecera).use { s ->
			// A trozos de tamaños distintos, como escribe un ZIP
			var i = 0
			while (i < datos.size) {
				val n = minOf(datos.size - i, Random.nextInt(1, 70_000))
				s.write(datos, i, n)
				i += n
			}
		}
		return salida.toByteArray()
	}

	private fun descifrar(cifrado: ByteArray, k: ByteArray = clave, cab: ByteArray = cabecera): ByteArray =
		Cifrado.Entrada(ByteArrayInputStream(cifrado), k, prefijo, cab).use { it.readBytes() }

	@Test
	fun idaYVuelta() {
		for (tamano in listOf(0, 1, Cifrado.BLOQUE - 1, Cifrado.BLOQUE, Cifrado.BLOQUE + 1, 3 * Cifrado.BLOQUE + 12345)) {
			val datos = Random.nextBytes(tamano)
			assertArrayEquals("tamaño $tamano", datos, descifrar(cifrar(datos)))
		}
	}

	@Test
	fun claveEquivocadaNoAbre() {
		val cifrado = cifrar(Random.nextBytes(1000))
		try {
			descifrar(cifrado, Random.nextBytes(32))
			fail("Abrió con otra clave")
		} catch (e: Cifrado.ContrasenaIncorrecta) {
			// Bien
		}
	}

	@Test
	fun cabeceraCambiadaNoAbre() {
		val cifrado = cifrar(Random.nextBytes(1000))
		try {
			descifrar(cifrado, cab = "LUCOPIA-otra".toByteArray())
			fail("Abrió con otra cabecera")
		} catch (e: Cifrado.ContrasenaIncorrecta) {
			// Bien
		}
	}

	@Test
	fun unByteCambiadoSeDetecta() {
		val cifrado = cifrar(Random.nextBytes(Cifrado.BLOQUE * 2 + 10))
		for (posicion in listOf(10, Cifrado.BLOQUE + 30, cifrado.size - 5)) {
			val malo = cifrado.copyOf()
			malo[posicion] = (malo[posicion].toInt() xor 1).toByte()
			try {
				descifrar(malo)
				fail("No detectó el cambio en $posicion")
			} catch (e: IOException) {
				// Bien: ContrasenaIncorrecta o copia dañada
			}
		}
	}

	@Test
	fun copiaCortadaSeDetecta() {
		val datos = Random.nextBytes(Cifrado.BLOQUE * 3)
		val cifrado = cifrar(datos)
		// Cortada justo tras un bloque completo: sin el último bloque marcado no vale
		val primerBloque = 4 + Cifrado.BLOQUE + 16
		for (corte in listOf(primerBloque, primerBloque * 2, cifrado.size - 1)) {
			try {
				descifrar(cifrado.copyOf(corte))
				fail("No detectó la copia cortada en $corte")
			} catch (e: IOException) {
				assertTrue(e !is Cifrado.ContrasenaIncorrecta || corte == cifrado.size - 1)
			}
		}
	}

	@Test
	fun bloquesCambiadosDeSitioSeDetectan() {
		val cifrado = cifrar(Random.nextBytes(Cifrado.BLOQUE * 2 + 100))
		val tam = 4 + Cifrado.BLOQUE + 16
		// Los dos primeros bloques intercambiados
		val malo = cifrado.copyOfRange(tam, 2 * tam) + cifrado.copyOfRange(0, tam) + cifrado.copyOfRange(2 * tam, cifrado.size)
		try {
			descifrar(malo)
			fail("No detectó los bloques cambiados de sitio")
		} catch (e: IOException) {
			// Bien
		}
	}
}
