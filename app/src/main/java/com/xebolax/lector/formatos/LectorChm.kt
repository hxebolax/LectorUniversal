package com.xebolax.lector.formatos

import com.xebolax.lector.modelo.ConstructorDocumento
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.Textos
import com.xebolax.lector.modelo.TipoBloque
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder

/**
 * Ayuda de Windows (CHM): un contenedor propio de Microsoft (ITSF) con un
 * directorio de archivos (PMGL) y el contenido comprimido con LZX. Dentro
 * hay páginas HTML; el orden y el índice salen del archivo .hhc.
 */
object LectorChm : LectorFormato {

	private class EntradaToc(val nombre: String, val nivel: Int, val ruta: String, val fragmento: String)

	private val PAGINAS = listOf(".htm", ".html", ".xhtml", ".shtml")
	private const val PAGINAS_MAXIMAS = 5000

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val c = ctx.nuevoConstructor()
		ArchivoChm(fuente.archivoLocal()).use { chm -> leerChm(chm, c, ctx) }
		if (c.titulo.isBlank()) c.titulo = fuente.nombre.substringBeforeLast('.')
		return Contenido.Texto(c.construir())
	}

	private fun leerChm(chm: ArchivoChm, c: ConstructorDocumento, ctx: ContextoLectura) {
		// #SYSTEM: título, idioma (LCID) y nombre del índice
		var titulo = ""
		var lcid = chm.lcid
		var archivoToc: String? = null
		val sistema = runCatching { chm.leer("/#SYSTEM") }.getOrNull()
		if (sistema != null && sistema.size >= 4) {
			var p = 4
			while (p + 4 <= sistema.size) {
				val codigo = le16(sistema, p)
				val largo = le16(sistema, p + 2)
				p += 4
				if (p + largo > sistema.size) break
				when (codigo) {
					0 -> archivoToc = cadena(sistema, p, largo)
					3 -> titulo = cadena(sistema, p, largo)
					4 -> if (largo >= 4) le32(sistema, p).toInt().takeIf { it != 0 }?.let { lcid = it }
				}
				p += largo
			}
		}
		val codificacion = AyudaBinarios.codificacionDeLcid(lcid)
		c.idioma = AyudaBinarios.idiomaDeLcid(lcid)
		if (c.idioma.isNotEmpty()) c.metadatos["Idioma"] = c.idioma

		// Páginas: ruta normalizada (minúsculas) → nombre real
		val paginas = LinkedHashMap<String, String>()
		for (nombre in chm.entradas.keys) {
			if (esPagina(nombre)) paginas.putIfAbsent(normalizar(nombre), nombre)
		}

		// Índice (.hhc)
		val hhc = archivoToc?.let { chm.buscar(normalizar(it)) }
			?: chm.entradas.keys.firstOrNull { it.lowercase().endsWith(".hhc") }
		val toc = hhc?.let { nombre ->
			runCatching { chm.leer(nombre) }.getOrNull()?.let { leerToc(it, codificacion, paginas) }
		} ?: emptyList()
		if (titulo.isBlank()) titulo = toc.firstOrNull()?.nombre.orEmpty()
		c.titulo = Textos.limpiar(titulo)

		// Orden de lectura: el del índice y detrás lo que no esté en él
		val orden = LinkedHashSet<String>()
		for (e in toc) orden += e.ruta
		orden += paginas.keys
		if (orden.isEmpty()) throw ExcepcionFormato("El archivo CHM no contiene páginas que se puedan leer.")
		val lista = orden.take(PAGINAS_MAXIMAS)
		val primeraEntrada = HashMap<String, EntradaToc>()
		for (e in toc) {
			val actual = primeraEntrada[e.ruta]
			if (actual == null || (actual.fragmento.isNotEmpty() && e.fragmento.isEmpty())) primeraEntrada[e.ruta] = e
		}

		val sinEncabezados = HashSet<String>()
		var fallos = 0
		for ((i, ruta) in lista.withIndex()) {
			ctx.comprobar()
			ctx.progreso(i * 100 / lista.size)
			val nombreReal = paginas[ruta] ?: continue
			val bytes = try {
				chm.leer(nombreReal)
			} catch (e: ExcepcionFormato) {
				fallos++
				null
			} ?: continue
			val doc = Jsoup.parse(Textos.decodificar(bytes, codificacion))
			ajustarEnlaces(doc)
			val tieneH = doc.body().selectFirst("h1, h2, h3, h4, h5, h6") != null
			c.ancla(ruta)
			c.marcarSiguiente(Marca.INICIO_SECCION)
			if (!tieneH) {
				sinEncabezados += ruta
				// Página sin títulos: se pone el del índice si el texto no empieza por él
				val entrada = primeraEntrada[ruta]
				if (entrada != null && entrada.fragmento.isEmpty()) {
					val inicio = AyudaBinarios.normalizar(doc.body().text().take(400))
					val nombre = AyudaBinarios.normalizar(entrada.nombre)
					if (nombre.isNotEmpty() && !inicio.startsWith(nombre)) c.encabezado(entrada.nombre, entrada.nivel)
				}
			}
			ConversorHtml(c, ruta, ctx.opciones).convertir(doc.body())
		}
		if (c.estaVacio()) {
			if (fallos > 0) throw ExcepcionFormato("No se pudo descomprimir este archivo CHM.")
			throw ExcepcionFormato("El archivo CHM no contiene texto.")
		}
		if (fallos > 0) c.avisos += "El archivo CHM está dañado: $fallos páginas no se han podido leer."

		// Índice y encabezados de las páginas que no los tienen
		for (e in toc) {
			val ancla = if (e.fragmento.isEmpty()) e.ruta else "${e.ruta}#${e.fragmento}"
			c.indiceAncla(e.nombre, e.nivel, ancla)
			if (e.ruta in sinEncabezados) {
				val b = c.resolver(ancla) ?: continue
				for (j in b until minOf(b + 5, c.tamano)) {
					if (c.tipoDe(j) == TipoBloque.ENCABEZADO) break
					if (AyudaBinarios.parecidos(c.textoDe(j).orEmpty(), e.nombre)) {
						c.cambiarAEncabezado(j, e.nivel)
						break
					}
				}
			}
		}
	}

	private fun esPagina(nombre: String): Boolean {
		if (nombre.startsWith("/#") || nombre.startsWith("/$") || nombre.startsWith("::") ||
			nombre.startsWith("#") || nombre.startsWith("$") || nombre.endsWith("/")
		) return false
		val n = nombre.lowercase()
		return PAGINAS.any { n.endsWith(it) }
	}

	/** Ruta de un archivo interno o de un enlace: sin "ms-its:...::", sin "/" inicial y en minúsculas. */
	private fun normalizar(ruta: String): String {
		var s = ruta.trim()
		val dobles = s.lastIndexOf("::")
		if (dobles >= 0) s = s.substring(dobles + 2)
		s = s.replace('\\', '/')
		if (s.contains('%')) s = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
		return Contenedor.normalizar(s).lowercase()
	}

	/** Lee el .hhc: objetos text/sitemap con Name y Local, anidados en listas <ul>. */
	private fun leerToc(bytes: ByteArray, codificacion: String, paginas: Map<String, String>): List<EntradaToc> {
		val doc = Jsoup.parse(Textos.decodificar(bytes, codificacion))
		val salida = ArrayList<EntradaToc>()
		val pendientes = ArrayList<Pair<String, Int>>()
		for (obj in doc.select("object")) {
			if (!obj.attr("type").equals("text/sitemap", ignoreCase = true)) continue
			var nombre = ""
			var local = ""
			for (param in obj.select("param")) {
				val n = param.attr("name").lowercase()
				if (n == "name" && nombre.isEmpty()) nombre = param.attr("value").trim()
				else if (n == "local" && local.isEmpty()) local = param.attr("value").trim()
			}
			val nivel = obj.parents().count { it.normalName() == "ul" }.coerceIn(1, 6)
			if (nombre.isEmpty()) nombre = local.substringAfterLast('/').substringBefore('#')
			if (nombre.isEmpty()) continue
			val ruta = if (local.isEmpty()) "" else normalizar(local.substringBefore('#'))
			if (ruta.isEmpty() || ruta !in paginas) {
				// Carpeta del índice sin página: apunta a la siguiente entrada que tenga
				if (local.isEmpty()) pendientes += nombre to nivel
				continue
			}
			val fragmento = local.substringAfter('#', "")
			for ((n, nv) in pendientes) salida += EntradaToc(n, nv, ruta, fragmento)
			pendientes.clear()
			salida += EntradaToc(nombre, nivel, ruta, fragmento)
		}
		return salida
	}

	/** Enlaces en minúsculas (Windows no distingue) y sin el prefijo ms-its:/mk:@MSITStore:. */
	private fun ajustarEnlaces(doc: Document) {
		for (a in doc.select("a[href]")) {
			var h = a.attr("href").trim()
			val minus = h.lowercase()
			if (minus.startsWith("ms-its:") || minus.startsWith("mk:@msitstore:") || minus.startsWith("its:")) {
				val i = h.lastIndexOf("::")
				if (i < 0) continue
				h = "/" + h.substring(i + 2).trimStart('/')
			} else if (h.startsWith("#") || h.contains("://") || minus.startsWith("mailto:") || minus.startsWith("javascript:")) {
				continue
			}
			val fragmento = h.substringAfter('#', "")
			val camino = h.substringBefore('#').lowercase()
			a.attr("href", if (fragmento.isEmpty()) camino else "$camino#$fragmento")
		}
	}

	private fun cadena(b: ByteArray, desde: Int, largo: Int): String {
		var fin = desde
		while (fin < desde + largo && b[fin].toInt() != 0) fin++
		return String(b, desde, fin - desde, Charsets.UTF_8).trim()
	}
}

// --- Lectura en little-endian ------------------------------------------------------

/** Un archivo de dentro de un CHM (por ejemplo una imagen, para describirla). */
internal fun leerDeChm(archivo: File, ruta: String): ByteArray? = ArchivoChm(archivo).use { it.leer(ruta) ?: it.leer("/$ruta") }

private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

private fun le32(b: ByteArray, o: Int): Long =
	(b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
		((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

private fun le64(b: ByteArray, o: Int): Long = le32(b, o) or (le32(b, o + 4) shl 32)

private fun firmaChm(b: ByteArray, o: Int, firma: String): Boolean {
	if (o < 0 || o + firma.length > b.size) return false
	for (i in firma.indices) if (b[o + i] != firma[i].code.toByte()) return false
	return true
}

/** Archivo CHM abierto: directorio leído y acceso a los archivos internos. */
private class ArchivoChm(archivo: File) : Closeable {

	class Entrada(val nombre: String, val seccion: Int, val desplazamiento: Long, val largo: Long)

	private val raf = RandomAccessFile(archivo, "r")
	private val tamano = raf.length()
	val entradas = LinkedHashMap<String, Entrada>()
	private val porRuta = HashMap<String, String>()
	private var inicioContenido = 0L
	var lcid = 0
		private set

	// Sección 1 (LZX)
	private var lzxPreparado = false
	private var inicioComprimido = 0L
	private var tamDescomprimido = 0L
	private var tamComprimido = 0L
	private var reinicios = LongArray(0)
	private var bitsVentana = 16
	private var marcosPorGrupo = 1
	private val fallidos = HashSet<Int>()
	private val cache = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) = size > 48
	}

	init {
		try {
			leerCabecera()
		} catch (e: ExcepcionFormato) {
			raf.close()
			throw e
		} catch (e: Exception) {
			raf.close()
			throw ExcepcionFormato("El archivo CHM está dañado.", e)
		}
	}

	override fun close() = raf.close()

	private fun bytes(pos: Long, n: Long): ByteArray {
		if (pos < 0 || n < 0 || pos + n > tamano || n > 256L * 1024 * 1024) throw ExcepcionFormato("El archivo CHM está dañado o incompleto.")
		val b = ByteArray(n.toInt())
		raf.seek(pos)
		raf.readFully(b)
		return b
	}

	private fun leerCabecera() {
		if (tamano < 0x60) throw ExcepcionFormato("El archivo no es una ayuda de Windows (CHM) válida.")
		val cab = bytes(0, 0x60)
		if (!firmaChm(cab, 0, "ITSF")) throw ExcepcionFormato("El archivo no es una ayuda de Windows (CHM) válida.")
		val version = le32(cab, 4)
		lcid = le32(cab, 0x14).toInt()
		val inicioDirectorio = le64(cab, 0x48)
		val largoDirectorio = le64(cab, 0x50)
		inicioContenido = if (version >= 3) le64(cab, 0x58) else inicioDirectorio + largoDirectorio
		leerDirectorio(inicioDirectorio)
		if (entradas.isEmpty()) throw ExcepcionFormato("El archivo CHM no tiene directorio de archivos.")
	}

	private fun leerDirectorio(inicio: Long) {
		val cab = bytes(inicio, 0x54)
		if (!firmaChm(cab, 0, "ITSP")) throw ExcepcionFormato("El directorio del archivo CHM está dañado.")
		val largoCabecera = le32(cab, 8)
		val tamTrozo = le32(cab, 0x10)
		val primero = le32(cab, 0x20).toInt()
		val ultimo = le32(cab, 0x24).toInt()
		val cuantos = le32(cab, 0x2C)
		if (tamTrozo < 32 || tamTrozo > 1 shl 20 || cuantos <= 0 || cuantos > 1_000_000) {
			throw ExcepcionFormato("El directorio del archivo CHM está dañado.")
		}
		val base = inicio + largoCabecera
		val desde = if (primero in 0 until cuantos) primero.toLong() else 0L
		val hasta = if (ultimo >= 0 && ultimo < cuantos) ultimo.toLong() else cuantos - 1
		for (n in desde..hasta) {
			val pos = base + n * tamTrozo
			if (pos + tamTrozo > tamano) break
			val trozo = bytes(pos, tamTrozo)
			if (firmaChm(trozo, 0, "PMGL")) leerTrozo(trozo)
		}
	}

	private fun leerTrozo(t: ByteArray) {
		val libre = le32(t, 4)
		val fin = (t.size - libre).coerceIn(20, t.size.toLong()).toInt()
		val pos = intArrayOf(20)
		fun encint(): Long {
			var v = 0L
			var vueltas = 0
			while (pos[0] < fin && vueltas < 9) {
				val b = t[pos[0]++].toInt() and 0xFF
				v = (v shl 7) or (b and 0x7F).toLong()
				vueltas++
				if (b and 0x80 == 0) return v
			}
			return -1
		}
		while (pos[0] < fin) {
			val largoNombre = encint()
			if (largoNombre <= 0 || pos[0] + largoNombre > fin) return
			val nombre = String(t, pos[0], largoNombre.toInt(), Charsets.UTF_8)
			pos[0] += largoNombre.toInt()
			val seccion = encint()
			val desplazamiento = encint()
			val largo = encint()
			if (seccion < 0 || desplazamiento < 0 || largo < 0) return
			entradas[nombre] = Entrada(nombre, seccion.toInt(), desplazamiento, largo)
			val clave = Contenedor.normalizar(nombre).lowercase()
			if (clave.isNotEmpty()) porRuta.putIfAbsent(clave, nombre)
		}
	}

	/** Nombre real de un archivo a partir de su ruta normalizada (sin "/" y en minúsculas). */
	fun buscar(ruta: String): String? = porRuta[ruta]

	/** Contenido de un archivo interno (null si no existe). */
	fun leer(nombre: String): ByteArray? {
		val e = entradas[nombre] ?: porRuta[Contenedor.normalizar(nombre).lowercase()]?.let { entradas[it] } ?: return null
		if (e.largo == 0L) return ByteArray(0)
		return when (e.seccion) {
			0 -> bytes(inicioContenido + e.desplazamiento, e.largo)
			1 -> leerSeccion1(e.desplazamiento, e.largo)
			else -> null
		}
	}

	private fun crudo(nombre: String): ByteArray? {
		val e = entradas[nombre] ?: return null
		if (e.seccion != 0) return null
		return bytes(inicioContenido + e.desplazamiento, e.largo)
	}

	private fun prepararLzx() {
		if (lzxPreparado) return
		val contenido = entradas["::DataSpace/Storage/MSCompressed/Content"]
		val control = crudo("::DataSpace/Storage/MSCompressed/ControlData")
		val nombreTabla = entradas.keys.firstOrNull {
			it.startsWith("::DataSpace/Storage/MSCompressed/Transform/") && it.endsWith("/InstanceData/ResetTable")
		}
		val tabla = nombreTabla?.let { crudo(it) }
		if (contenido == null || contenido.seccion != 0 || control == null || tabla == null) {
			throw ExcepcionFormato("No se pudo descomprimir este archivo CHM.")
		}
		if (control.size < 0x18 || !firmaChm(control, 4, "LZXC")) throw ExcepcionFormato("El archivo CHM usa una compresión desconocida.")
		val version = le32(control, 8)
		var intervalo = le32(control, 0x0C)
		var ventana = le32(control, 0x10)
		if (version == 2L) {
			intervalo *= 0x8000
			ventana *= 0x8000
		}
		bitsVentana = when (ventana) {
			0x8000L -> 15
			0x10000L -> 16
			0x20000L -> 17
			0x40000L -> 18
			0x80000L -> 19
			0x100000L -> 20
			0x200000L -> 21
			else -> throw ExcepcionFormato("No se pudo descomprimir este archivo CHM.")
		}
		if (intervalo <= 0 || intervalo % 0x8000 != 0L) throw ExcepcionFormato("No se pudo descomprimir este archivo CHM.")
		marcosPorGrupo = (intervalo / 0x8000).toInt()
		if (tabla.size < 0x28) throw ExcepcionFormato("No se pudo descomprimir este archivo CHM.")
		val n = le32(tabla, 4).toInt()
		val tamEntrada = le32(tabla, 8).toInt()
		val desdeTabla = le32(tabla, 0x0C).toInt()
		tamDescomprimido = le64(tabla, 0x10)
		tamComprimido = le64(tabla, 0x18)
		if (n <= 0 || (tamEntrada != 8 && tamEntrada != 4) || desdeTabla < 0 || desdeTabla + n.toLong() * tamEntrada > tabla.size) {
			throw ExcepcionFormato("No se pudo descomprimir este archivo CHM.")
		}
		reinicios = LongArray(n) { i ->
			val o = desdeTabla + i * tamEntrada
			if (tamEntrada == 8) le64(tabla, o) else le32(tabla, o)
		}
		inicioComprimido = inicioContenido + contenido.desplazamiento
		if (tamComprimido <= 0 || tamComprimido > contenido.largo) tamComprimido = contenido.largo
		lzxPreparado = true
	}

	private fun leerSeccion1(desplazamiento: Long, largo: Long): ByteArray {
		prepararLzx()
		if (desplazamiento + largo > tamDescomprimido || largo > 256L * 1024 * 1024) {
			throw ExcepcionFormato("El archivo CHM está dañado o incompleto.")
		}
		val salida = ByteArray(largo.toInt())
		val tamGrupo = marcosPorGrupo.toLong() * 0x8000
		var hecho = 0L
		while (hecho < largo) {
			val pos = desplazamiento + hecho
			val g = (pos / tamGrupo).toInt()
			val datos = grupo(g)
			val dentro = (pos - g * tamGrupo).toInt()
			val cuantos = minOf(largo - hecho, tamGrupo - dentro).toInt()
			System.arraycopy(datos, dentro, salida, hecho.toInt(), cuantos)
			hecho += cuantos
		}
		return salida
	}

	/** Descomprime (o saca de la caché) un grupo de marcos entre dos reinicios. */
	private fun grupo(g: Int): ByteArray {
		cache[g]?.let { return it }
		if (g in fallidos) throw ExcepcionFormato("No se pudo descomprimir este archivo CHM.")
		try {
			val marco = g.toLong() * marcosPorGrupo
			val marcos = (tamDescomprimido + 0x7FFF) / 0x8000
			if (marco >= reinicios.size || marco >= marcos) throw ExcepcionFormato("No se pudo descomprimir este archivo CHM.")
			val inicio = reinicios[marco.toInt()]
			val siguiente = marco + marcosPorGrupo
			val fin = if (siguiente < marcos && siguiente < reinicios.size) reinicios[siguiente.toInt()] else tamComprimido
			if (fin < inicio || fin > tamComprimido) throw ExcepcionFormato("No se pudo descomprimir este archivo CHM.")
			val comprimido = bytes(inicioComprimido + inicio, fin - inicio)
			val tam = minOf(marcosPorGrupo.toLong() * 0x8000, tamDescomprimido - marco * 0x8000).toInt()
			// Margen: una coincidencia puede pasarse del final del marco
			val salida = ByteArray(tam + 512)
			Lzx(bitsVentana).descomprimir(comprimido, salida, tam)
			cache[g] = salida
			return salida
		} catch (e: InterruptedException) {
			throw e
		} catch (e: Exception) {
			fallidos += g
			throw if (e is ExcepcionFormato) e else ExcepcionFormato("No se pudo descomprimir este archivo CHM.", e)
		}
	}
}

// --- LZX ---------------------------------------------------------------------------

private class ErrorLzx : Exception("Datos LZX no válidos")

/** Lector de bits de LZX: palabras de 16 bits little-endian, bits altos primero. */
private class BitsLzx(private val d: ByteArray) {
	var pos = 0
	private var buf = 0L
	private var n = 0

	private fun llenar(k: Int) {
		while (n < k) {
			val b0 = if (pos < d.size) d[pos].toInt() and 0xFF else 0
			val b1 = if (pos + 1 < d.size) d[pos + 1].toInt() and 0xFF else 0
			pos += 2
			buf = (buf shl 16) or (b0 or (b1 shl 8)).toLong()
			n += 16
		}
		if (pos > d.size + 1024) throw ErrorLzx()
	}

	fun leer(k: Int): Int {
		if (k == 0) return 0
		llenar(k)
		n -= k
		return ((buf ushr n) and ((1L shl k) - 1)).toInt()
	}

	fun mirar(k: Int): Int {
		llenar(k)
		return ((buf ushr (n - k)) and ((1L shl k) - 1)).toInt()
	}

	fun saltar(k: Int) {
		n -= k
	}

	/** Al final de cada marco de 32 KB se tiran los bits sueltos hasta la palabra siguiente. */
	fun alinear16() {
		n -= n and 15
	}

	/** Bloque sin comprimir: se tiran de 1 a 16 bits y se sigue leyendo bytes en crudo. */
	fun alinearBytes() {
		llenar(16)
		if (n > 16) pos -= 2
		n = 0
		buf = 0
	}

	fun byteCrudo(): Int {
		if (pos >= d.size) throw ErrorLzx()
		return d[pos++].toInt() and 0xFF
	}

	fun u32Crudo(): Int = byteCrudo() or (byteCrudo() shl 8) or (byteCrudo() shl 16) or (byteCrudo() shl 24)

	fun copiarCrudo(destino: ByteArray, desde: Int, cuantos: Int) {
		if (pos + cuantos > d.size || desde + cuantos > destino.size) throw ErrorLzx()
		System.arraycopy(d, pos, destino, desde, cuantos)
		pos += cuantos
	}

	fun saltarByte() {
		pos++
	}
}

/** Árbol Huffman canónico con tabla de búsqueda directa. */
private class ArbolLzx(n: Int) {
	val largos = IntArray(n)
	private var tabla = IntArray(0)
	private var bits = 0

	fun construir() {
		var max = 0
		for (l in largos) if (l > max) max = l
		bits = max
		if (max == 0) return
		val tam = 1 shl max
		if (tabla.size != tam) tabla = IntArray(tam) else tabla.fill(0)
		var codigo = 0
		for (largo in 1..max) {
			for (s in largos.indices) {
				if (largos[s] != largo) continue
				val cuantas = 1 shl (max - largo)
				val inicio = codigo shl (max - largo)
				if (inicio + cuantas > tam) throw ErrorLzx()
				tabla.fill((s shl 5) or largo, inicio, inicio + cuantas)
				codigo++
			}
			codigo = codigo shl 1
		}
	}

	fun leer(b: BitsLzx): Int {
		if (bits == 0) throw ErrorLzx()
		val e = tabla[b.mirar(bits)]
		val l = e and 0x1F
		if (l == 0) throw ErrorLzx()
		b.saltar(l)
		return e ushr 5
	}
}

/**
 * Descompresor LZX (el de libmspack): LZ77 con árboles Huffman que se
 * transmiten como diferencias, tres distancias recientes y traducción de
 * las llamadas E8. Descomprime un grupo de marcos entre dos reinicios.
 */
private class Lzx(bitsVentana: Int) {

	private val principal: ArbolLzx
	private val longitud = ArbolLzx(249)
	private val alineado = ArbolLzx(8)
	private val prearbol = ArbolLzx(20)
	private var r0 = 1
	private var r1 = 1
	private var r2 = 1

	init {
		val ranuras = when (bitsVentana) {
			15 -> 30
			16 -> 32
			17 -> 34
			18 -> 36
			19 -> 38
			20 -> 42
			21 -> 50
			else -> throw ErrorLzx()
		}
		principal = ArbolLzx(256 + ranuras * 8)
	}

	fun descomprimir(entrada: ByteArray, salida: ByteArray, total: Int) {
		val b = BitsLzx(entrada)
		val intel = b.leer(1) == 1
		var tamIntel = 0
		if (intel) {
			val alto = b.leer(16)
			val bajo = b.leer(16)
			tamIntel = (alto shl 16) or bajo
		}
		var intelIniciado = false
		val traducir = ArrayList<Int>()
		var pos = 0
		var tipo = 0
		var restante = 0
		var longitudBloque = 0
		var inicioMarco = 0
		while (inicioMarco < total) {
			val finMarco = minOf(inicioMarco + 0x8000, total)
			while (pos < finMarco) {
				if (restante == 0) {
					// Bloque sin comprimir de longitud impar: lleva un byte de relleno
					if (tipo == 3 && (longitudBloque and 1) == 1) b.saltarByte()
					tipo = b.leer(3)
					val alto = b.leer(16)
					val bajo = b.leer(8)
					longitudBloque = (alto shl 8) or bajo
					restante = longitudBloque
					when (tipo) {
						1, 2 -> {
							if (tipo == 2) {
								for (i in 0 until 8) alineado.largos[i] = b.leer(3)
								alineado.construir()
							}
							leerLargos(b, principal.largos, 0, 256)
							leerLargos(b, principal.largos, 256, principal.largos.size)
							principal.construir()
							if (principal.largos[0xE8] != 0) intelIniciado = true
							leerLargos(b, longitud.largos, 0, 249)
							longitud.construir()
						}
						3 -> {
							intelIniciado = true
							b.alinearBytes()
							r0 = b.u32Crudo()
							r1 = b.u32Crudo()
							r2 = b.u32Crudo()
						}
						else -> throw ErrorLzx()
					}
					continue
				}
				val este = minOf(restante, finMarco - pos)
				val antes = pos
				if (tipo == 3) {
					b.copiarCrudo(salida, pos, este)
					pos += este
				} else {
					pos = simbolos(b, salida, pos, pos + este, tipo == 2)
				}
				val hecho = pos - antes
				if (hecho > restante) throw ErrorLzx()
				restante -= hecho
			}
			if (intel && tamIntel != 0 && intelIniciado) traducir += inicioMarco
			b.alinear16()
			inicioMarco += 0x8000
		}
		for (inicio in traducir) deshacerE8(salida, inicio, minOf(inicio + 0x8000, total), tamIntel)
	}

	private fun leerLargos(b: BitsLzx, largos: IntArray, desde: Int, hasta: Int) {
		for (i in 0 until 20) prearbol.largos[i] = b.leer(4)
		prearbol.construir()
		var x = desde
		while (x < hasta) {
			when (val z = prearbol.leer(b)) {
				17 -> {
					val y = b.leer(4) + 4
					repeat(y) { if (x < hasta) largos[x++] = 0 }
				}
				18 -> {
					val y = b.leer(5) + 20
					repeat(y) { if (x < hasta) largos[x++] = 0 }
				}
				19 -> {
					val y = b.leer(1) + 4
					val w = prearbol.leer(b)
					var v = largos[x] - w
					if (v < 0) v += 17
					repeat(y) { if (x < hasta) largos[x++] = v }
				}
				else -> {
					var v = largos[x] - z
					if (v < 0) v += 17
					largos[x++] = v
				}
			}
		}
	}

	private fun simbolos(b: BitsLzx, salida: ByteArray, desde: Int, hasta: Int, conAlineado: Boolean): Int {
		var p = desde
		while (p < hasta) {
			val sim = principal.leer(b)
			if (sim < 256) {
				if (p >= salida.size) throw ErrorLzx()
				salida[p++] = sim.toByte()
				continue
			}
			val s = sim - 256
			var largo = s and 7
			if (largo == 7) largo += longitud.leer(b)
			largo += 2
			val ranura = s ushr 3
			val distancia: Int
			if (ranura > 2) {
				val extra = if (ranura >= 36) 17 else EXTRA[ranura]
				var d = BASE[ranura] - 2
				if (conAlineado) {
					when {
						extra > 3 -> {
							d += b.leer(extra - 3) shl 3
							d += alineado.leer(b)
						}
						extra == 3 -> d += alineado.leer(b)
						extra > 0 -> d += b.leer(extra)
						else -> d = 1
					}
				} else {
					d = if (extra > 0) d + b.leer(extra) else 1
				}
				r2 = r1
				r1 = r0
				r0 = d
				distancia = d
			} else if (ranura == 0) {
				distancia = r0
			} else if (ranura == 1) {
				distancia = r1
				r1 = r0
				r0 = distancia
			} else {
				distancia = r2
				r2 = r0
				r0 = distancia
			}
			val origen = p - distancia
			if (origen < 0 || distancia <= 0 || p + largo > salida.size) throw ErrorLzx()
			// Byte a byte: la distancia puede ser menor que la longitud
			for (i in 0 until largo) salida[p + i] = salida[origen + i]
			p += largo
		}
		return p
	}

	/** Deshace la traducción de las llamadas E8 de un marco (direcciones absolutas → relativas). */
	private fun deshacerE8(d: ByteArray, inicio: Int, fin: Int, tamIntel: Int) {
		if (fin - inicio <= 10) return
		val limite = fin - 10
		var i = inicio
		while (i < limite) {
			if (d[i] != 0xE8.toByte()) {
				i++
				continue
			}
			val abs = (d[i + 1].toInt() and 0xFF) or ((d[i + 2].toInt() and 0xFF) shl 8) or
				((d[i + 3].toInt() and 0xFF) shl 16) or ((d[i + 4].toInt() and 0xFF) shl 24)
			if (abs >= -i && abs < tamIntel) {
				val rel = if (abs >= 0) abs - i else abs + tamIntel
				d[i + 1] = rel.toByte()
				d[i + 2] = (rel ushr 8).toByte()
				d[i + 3] = (rel ushr 16).toByte()
				d[i + 4] = (rel ushr 24).toByte()
			}
			i += 5
		}
	}

	companion object {
		private val EXTRA = IntArray(52) { i -> if (i < 4) 0 else minOf(17, i / 2 - 1) }
		private val BASE = IntArray(52).also { b ->
			for (i in 1 until b.size) b[i] = b[i - 1] + (1 shl EXTRA[i - 1])
		}
	}
}
