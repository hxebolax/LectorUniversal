package com.xebolax.lector.copias

import com.xebolax.lector.R
import com.xebolax.lector.datos.FalloIo
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado de las copias de seguridad protegidas con contraseña.
 *
 * - La clave (256 bits) sale de la contraseña con PBKDF2-HMAC-SHA256, con una
 *   sal aleatoria de 16 bytes y 600.000 vueltas (lo que recomienda OWASP), para
 *   que probar contraseñas a ciegas sea muy lento. Se calcula aquí mismo con
 *   HMAC (igual en todos los Android, desde el 7) y la contraseña en UTF-8.
 * - Los datos se cifran con AES-256-GCM en bloques de 256 KiB, como la
 *   construcción STREAM: cada bloque lleva su número y si es el último en el
 *   nonce, y la cabecera como datos asociados. Así se detecta cualquier cambio,
 *   un bloque cambiado de sitio, una copia cortada o una contraseña equivocada,
 *   y una copia grande no tiene que caber entera en la memoria.
 */
object Cifrado {

	const val VUELTAS = 600_000
	const val BLOQUE = 256 * 1024
	private const val ULTIMO = 0x80000000.toInt()

	class ContrasenaIncorrecta : FalloIo(R.string.cj_copia_contrasena_incorrecta)

	fun aleatorio(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

	/** PBKDF2-HMAC-SHA256 (RFC 8018). */
	fun derivar(contrasena: String, sal: ByteArray, vueltas: Int = VUELTAS, longitud: Int = 32): ByteArray {
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(contrasena.toByteArray(Charsets.UTF_8).let { if (it.isEmpty()) ByteArray(1) else it }, "HmacSHA256"))
		val resultado = ByteArray(longitud)
		var hecho = 0
		var bloque = 1
		while (hecho < longitud) {
			mac.update(sal)
			mac.update(ByteBuffer.allocate(4).putInt(bloque).array())
			val u = mac.doFinal()
			val t = u.copyOf()
			for (i in 1 until vueltas) {
				mac.update(u)
				mac.doFinal(u, 0)
				for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
			}
			val n = minOf(t.size, longitud - hecho)
			System.arraycopy(t, 0, resultado, hecho, n)
			hecho += n
			bloque++
		}
		return resultado
	}

	private fun nonce(prefijo: ByteArray, numero: Int, ultimo: Boolean): ByteArray =
		ByteBuffer.allocate(12).put(prefijo, 0, 7).putInt(numero).put(if (ultimo) 1 else 0).array()

	private fun cifrador(modo: Int, clave: ByteArray, prefijo: ByteArray, numero: Int, ultimo: Boolean, cabecera: ByteArray): Cipher =
		Cipher.getInstance("AES/GCM/NoPadding").apply {
			init(modo, SecretKeySpec(clave, "AES"), GCMParameterSpec(128, nonce(prefijo, numero, ultimo)))
			updateAAD(cabecera)
		}

	/** Cifra lo que se escribe; close() escribe el último bloque (aunque esté vacío). */
	class Salida(destino: OutputStream, private val clave: ByteArray, private val prefijo: ByteArray, private val cabecera: ByteArray) : FilterOutputStream(destino) {
		private val bufer = ByteArray(BLOQUE)
		private var usados = 0
		private var numero = 0
		private var cerrada = false

		override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

		override fun write(b: ByteArray, desde: Int, cuantos: Int) {
			var i = desde
			var quedan = cuantos
			while (quedan > 0) {
				// El bloque lleno solo se escribe cuando llega más: así se sabe cuál es el último
				if (usados == BLOQUE) escribirBloque(false)
				val n = minOf(quedan, BLOQUE - usados)
				System.arraycopy(b, i, bufer, usados, n)
				usados += n
				i += n
				quedan -= n
			}
		}

		private fun escribirBloque(ultimo: Boolean) {
			val cifrado = cifrador(Cipher.ENCRYPT_MODE, clave, prefijo, numero, ultimo, cabecera).doFinal(bufer, 0, usados)
			out.write(ByteBuffer.allocate(4).putInt(cifrado.size or (if (ultimo) ULTIMO else 0)).array())
			out.write(cifrado)
			numero++
			usados = 0
		}

		override fun flush() = out.flush()

		override fun close() {
			if (cerrada) return
			cerrada = true
			escribirBloque(true)
			out.flush()
			out.close()
		}
	}

	/** Descifra y comprueba cada bloque; si algo no cuadra, lanza ContrasenaIncorrecta o IOException. */
	class Entrada(origen: InputStream, private val clave: ByteArray, private val prefijo: ByteArray, private val cabecera: ByteArray) : InputStream() {
		private val datos = DataInputStream(origen)
		private var bloque = ByteArray(0)
		private var pos = 0
		private var numero = 0
		private var terminada = false

		private fun siguiente(): Boolean {
			if (terminada) return false
			val marca = try {
				datos.readInt()
			} catch (e: EOFException) {
				throw FalloIo(R.string.cj_copia_incompleta)
			}
			val ultimo = marca and ULTIMO != 0
			val tamano = marca and ULTIMO.inv()
			if (tamano < 16 || tamano > BLOQUE + 16) throw FalloIo(R.string.cj_copia_danada)
			val cifrado = ByteArray(tamano)
			try {
				datos.readFully(cifrado)
			} catch (e: EOFException) {
				throw FalloIo(R.string.cj_copia_incompleta)
			}
			bloque = try {
				cifrador(Cipher.DECRYPT_MODE, clave, prefijo, numero, ultimo, cabecera).doFinal(cifrado)
			} catch (e: AEADBadTagException) {
				throw ContrasenaIncorrecta()
			}
			pos = 0
			numero++
			if (ultimo) {
				terminada = true
				// Nada puede ir detrás del último bloque
				if (datos.read() != -1) throw FalloIo(R.string.cj_copia_danada)
			}
			return true
		}

		override fun read(): Int {
			val b = ByteArray(1)
			return if (read(b, 0, 1) == -1) -1 else b[0].toInt() and 0xff
		}

		override fun read(b: ByteArray, desde: Int, cuantos: Int): Int {
			if (cuantos == 0) return 0
			while (pos >= bloque.size) if (!siguiente()) return -1
			val n = minOf(cuantos, bloque.size - pos)
			System.arraycopy(bloque, pos, b, desde, n)
			pos += n
			return n
		}

		override fun close() = datos.close()
	}
}
