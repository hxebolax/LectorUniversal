package com.xebolax.lector.formatos

import com.xebolax.lector.R
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.TipoBloque
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Word 97-2003 (.doc): el texto del documento principal, párrafo a párrafo,
 * con las filas de las tablas como «celda; celda». Lee el contenedor OLE
 * (Compound File) y la tabla de piezas del documento (CLX). No da estilos
 * (encabezados): un .doc antiguo se lee como texto seguido. Si es un archivo
 * cifrado, una presentación o una hoja de cálculo antiguas, lo dice.
 */
object LectorDocAntiguo : LectorFormato {

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val ole = Ole(fuente.bytes())
		when {
			ole.tiene("EncryptedPackage") || ole.tiene("EncryptionInfo") -> throw ExcepcionFormato(R.string.fmt_cifrado)
			ole.tiene("PowerPoint Document") -> throw ExcepcionFormato(R.string.fmt_ppt_antiguo)
			ole.tiene("Workbook") || ole.tiene("Book") -> throw ExcepcionFormato(R.string.fmt_xls_antiguo)
		}
		val word = ole.flujo("WordDocument") ?: throw ExcepcionFormato.noValido(Formato.DOC)
		val texto = textoDe(word, ole)
		val c = ctx.nuevoConstructor()
		c.titulo = fuente.nombre.substringBeforeLast('.')
		anadir(c, texto, ctx)
		return Contenido.Texto(c.construir())
	}

	/** El texto del documento principal, desde la tabla de piezas. */
	internal fun textoDe(word: ByteArray, ole: Ole): String {
		val fib = ByteBuffer.wrap(word).order(ByteOrder.LITTLE_ENDIAN)
		if (word.size < 0x01AA || fib.getShort(0).toInt() and 0xFFFF != 0xA5EC) throw ExcepcionFormato(R.string.fmt_doc_muy_antiguo)
		val banderas = fib.getShort(0x0A).toInt() and 0xFFFF
		if (banderas and 0x0100 != 0) throw ExcepcionFormato(R.string.fmt_cifrado)
		val tabla = ole.flujo(if (banderas and 0x0200 != 0) "1Table" else "0Table") ?: throw ExcepcionFormato(R.string.fmt_falta_parte)
		val ccpTexto = fib.getInt(0x004C)
		val fcClx = fib.getInt(0x01A2)
		val lcbClx = fib.getInt(0x01A6)
		if (fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > tabla.size) throw ExcepcionFormato(R.string.fmt_danado)
		val t = ByteBuffer.wrap(tabla).order(ByteOrder.LITTLE_ENDIAN)
		// CLX: varios Prc (0x01) y luego el Pcdt (0x02) con la tabla de piezas
		var i = fcClx
		while (i < fcClx + lcbClx && tabla[i].toInt() == 0x01) i += 3 + (t.getShort(i + 1).toInt() and 0xFFFF)
		if (i >= fcClx + lcbClx || tabla[i].toInt() != 0x02) throw ExcepcionFormato(R.string.fmt_danado)
		val lcb = t.getInt(i + 1)
		val inicio = i + 5
		val piezas = (lcb - 4) / 12
		val sb = StringBuilder()
		for (k in 0 until piezas) {
			val cpIni = t.getInt(inicio + k * 4)
			val cpFin = t.getInt(inicio + (k + 1) * 4)
			if (cpIni >= ccpTexto) break
			val n = (minOf(cpFin, ccpTexto) - cpIni).coerceAtLeast(0)
			var fc = t.getInt(inicio + (piezas + 1) * 4 + k * 8 + 2)
			val comprimido = fc and 0x40000000 != 0
			if (comprimido) {
				fc = (fc and 0x40000000.inv()) / 2
				if (fc + n <= word.size) sb.append(String(word, fc, n, charset("windows-1252")))
			} else if (fc + n * 2 <= word.size) sb.append(String(word, fc, n * 2, Charsets.UTF_16LE))
		}
		return limpiar(sb.toString())
	}

	/** Quita los códigos de campo (se deja su resultado) y los caracteres de control de Word. */
	internal fun limpiar(t: String): String {
		val sb = StringBuilder()
		// Campos: 0x13 instrucción 0x14 resultado 0x15 (pueden ir anidados)
		val pila = ArrayDeque<Boolean>()
		for (ch in t) {
			when (ch.code) {
				0x13 -> pila.addLast(false)
				0x14 -> if (pila.isNotEmpty()) { pila.removeLast(); pila.addLast(true) }
				0x15 -> if (pila.isNotEmpty()) pila.removeLast()
				else -> if (pila.isEmpty() || pila.last()) when (ch.code) {
					0x0B -> sb.append('\n') // salto de línea
					0x0C -> sb.append('\r') // salto de página o de sección
					0x1E -> sb.append('-')
					0x1F, 0x01, 0x02, 0x05, 0x08 -> Unit // guion opcional, imagen, nota, comentario, dibujo
					else -> sb.append(ch)
				}
			}
		}
		return sb.toString()
	}

	/** Párrafos (separados por \r) y filas de tabla (celdas terminadas en 0x07). */
	private fun anadir(c: com.xebolax.lector.modelo.ConstructorDocumento, texto: String, ctx: ContextoLectura) {
		for ((tipo, t) in bloques(texto) { ctx.comprobar() }) {
			if (tipo == TipoBloque.FILA_TABLA) c.bloque(TipoBloque.FILA_TABLA, t) else c.parrafo(t)
		}
	}

	/**
	 * Párrafos y filas de tabla del texto de Word, en orden. Cada celda acaba
	 * en 0x07 y cada fila con otra marca sin texto; como una celda vacía
	 * también es una marca sin texto, se cuentan las columnas de la primera
	 * fila: una marca vacía antes de llegar a ellas es una celda vacía, no el
	 * final de la fila.
	 */
	internal fun bloques(texto: String, comprobar: () -> Unit = {}): List<Pair<TipoBloque, String>> {
		val salida = ArrayList<Pair<TipoBloque, String>>()
		val celdas = ArrayList<String>()
		val actual = StringBuilder()
		// Columnas de la tabla en curso (0: aún no se saben)
		var columnas = 0
		fun parrafo() {
			val p = actual.toString().trim()
			actual.clear()
			if (p.isEmpty()) return
			salida += TipoBloque.PARRAFO to p.replace('\n', ' ')
			// Texto fuera de una fila: la tabla terminó
			if (celdas.isEmpty()) columnas = 0
		}
		for (ch in texto) {
			comprobar()
			when (ch) {
				'\r' -> parrafo()
				'\u0007' -> {
					val celda = actual.toString().replace('\r', ' ').trim()
					actual.clear()
					if (celda.isEmpty() && celdas.isNotEmpty() && (columnas == 0 || celdas.size >= columnas)) {
						if (columnas == 0) columnas = celdas.size
						salida += TipoBloque.FILA_TABLA to celdas.filter { it.isNotEmpty() }.joinToString("; ")
						celdas.clear()
					} else celdas += celda
				}
				else -> actual.append(ch)
			}
		}
		if (celdas.any { it.isNotEmpty() }) salida += TipoBloque.FILA_TABLA to celdas.filter { it.isNotEmpty() }.joinToString("; ")
		parrafo()
		return salida
	}
}

/** Contenedor OLE (Compound File Binary) de los documentos de Office antiguos: sus flujos por nombre. */
class Ole(private val datos: ByteArray) {
	private val b = ByteBuffer.wrap(datos).order(ByteOrder.LITTLE_ENDIAN)
	private val sector = 1 shl (b.getShort(0x1E).toInt() and 0xFFFF)
	private val miniSector = 1 shl (b.getShort(0x20).toInt() and 0xFFFF)
	private val corteMini = b.getInt(0x38)
	private val fat: IntArray
	private val miniFat: IntArray
	private class Entrada(val nombre: String, val tipo: Int, val inicio: Int, val tamano: Long)
	private val entradas: List<Entrada>
	private val miniFlujo: ByteArray

	init {
		if (datos.size < 512 || b.getInt(0) != 0xE011CFD0.toInt()) throw ExcepcionFormato.noValido(Formato.DOC)
		// DIFAT: los primeros 109 sectores de la FAT en la cabecera y el resto en una cadena de sectores
		val sectoresFat = ArrayList<Int>()
		for (k in 0 until 109) b.getInt(0x4C + k * 4).takeIf { it >= 0 }?.let { sectoresFat += it }
		var difat = b.getInt(0x44)
		var vueltas = 0
		while (difat >= 0 && vueltas++ < datos.size / sector + 1) {
			val off = desplazamiento(difat)
			if (off + sector > datos.size) break
			for (k in 0 until sector / 4 - 1) b.getInt(off + k * 4).takeIf { it >= 0 }?.let { sectoresFat += it }
			difat = b.getInt(off + sector - 4)
		}
		// Nunca más sectores de FAT de los que necesita el archivo (una cabecera dañada no llena la memoria)
		val necesarios = datos.size / sector / (sector / 4) + 2
		val usados = sectoresFat.take(necesarios)
		fat = IntArray(usados.size * sector / 4)
		usados.forEachIndexed { k, s ->
			val off = desplazamiento(s)
			for (j in 0 until sector / 4) if (off + j * 4 + 4 <= datos.size) fat[k * sector / 4 + j] = b.getInt(off + j * 4)
		}
		val dir = cadena(b.getInt(0x30))
		val db = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
		entradas = (0 until dir.size / 128).map { k ->
			val o = k * 128
			val largo = (db.getShort(o + 0x40).toInt() and 0xFFFF).coerceIn(0, 64)
			val nombre = String(dir, o, (largo - 2).coerceAtLeast(0), Charsets.UTF_16LE)
			Entrada(nombre, dir[o + 0x42].toInt(), db.getInt(o + 0x74), db.getInt(o + 0x78).toLong() and 0xFFFFFFFFL)
		}
		val raiz = entradas.firstOrNull { it.tipo == 5 }
		miniFlujo = raiz?.let { cadena(it.inicio).copyOf(it.tamano.toInt().coerceAtMost(cadena(it.inicio).size)) } ?: ByteArray(0)
		val mf = cadena(b.getInt(0x3C))
		val mb = ByteBuffer.wrap(mf).order(ByteOrder.LITTLE_ENDIAN)
		miniFat = IntArray(mf.size / 4) { mb.getInt(it * 4) }
	}

	private fun desplazamiento(s: Int) = (s + 1) * sector

	/** Los bytes de una cadena de sectores de la FAT (vacía si no hay). */
	private fun cadena(primero: Int): ByteArray {
		if (primero < 0) return ByteArray(0)
		val salida = java.io.ByteArrayOutputStream()
		var s = primero
		var vueltas = 0
		// Una cadena no puede tener más sectores que el archivo: con una FAT dañada en ciclo, se para ahí
		val maximo = datos.size / sector + 1
		while (s >= 0 && s < fat.size && vueltas++ < maximo) {
			val off = desplazamiento(s)
			if (off >= datos.size) break
			salida.write(datos, off, minOf(sector, datos.size - off))
			s = fat[s]
		}
		return salida.toByteArray()
	}

	fun tiene(nombre: String) = entradas.any { it.tipo == 2 && it.nombre.equals(nombre, ignoreCase = true) }

	/** Los bytes de un flujo por su nombre (null si no está). */
	fun flujo(nombre: String): ByteArray? {
		val e = entradas.firstOrNull { it.tipo == 2 && it.nombre.equals(nombre, ignoreCase = true) } ?: return null
		val tam = e.tamano.toInt()
		if (e.tamano < corteMini) {
			// Flujo pequeño: en el miniflujo, por la mini FAT
			val salida = java.io.ByteArrayOutputStream()
			var s = e.inicio
			var vueltas = 0
			while (s >= 0 && s < miniFat.size + 1 && salida.size() < tam && vueltas++ < miniFlujo.size / miniSector + 1) {
				val off = s * miniSector
				if (off >= miniFlujo.size) break
				salida.write(miniFlujo, off, minOf(miniSector, miniFlujo.size - off))
				s = if (s < miniFat.size) miniFat[s] else -2
			}
			return salida.toByteArray().copyOf(tam.coerceAtMost(salida.size()))
		}
		return cadena(e.inicio).let { it.copyOf(tam.coerceAtMost(it.size)) }
	}
}
