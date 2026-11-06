package com.xebolax.lector.formatos

import com.xebolax.lector.modelo.ConstructorDocumento
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.Textos
import com.xebolax.lector.modelo.TipoBloque
import org.jsoup.Jsoup
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.text.Normalizer
import java.util.TreeSet

/**
 * Libros de Kindle MOBI, AZW, AZW3 (KF8) y PRC, y textos PalmDOC (PDB).
 *
 * Todos van en una base de datos Palm: cabecera, lista de registros y los
 * registros. El registro 0 lleva las cabeceras PalmDOC, MOBI y EXTH; detrás
 * vienen los registros de texto comprimidos (PalmDOC LZ77 o HUFF/CDIC).
 *
 * - MOBI 6: un solo HTML; los enlaces apuntan a posiciones de byte (filepos)
 *   y los capítulos se separan con <mbp:pagebreak/>.
 * - KF8: los XHTML del EPUB original partidos en esqueletos y fragmentos
 *   (índices SKEL y FRAG); los enlaces son kindle:pos:fid:...:off:...
 */
object LectorMobi : LectorFormato {

	private const val NULO = -1
	private const val HUFF = 17480
	private const val DRM = "Este libro tiene DRM (protección anticopia) y no se puede abrir."

	private val FILEPOS = Regex("""(?i)\bfilepos\s*=\s*["']?0*(\d+)["']?""")
	private val KINDLE_POS = Regex("""(?i)kindle:pos:fid:([0-9a-v]+):off:([0-9a-v]+)(\?[^"'\s>]*)?""")
	private val SALTO = Regex("""(?i)<mbp:pagebreak[^>]*>""")
	private val INICIO_HTML = Regex("""(?i)<html[\s>]""")
	private val AID = Regex("""aid\s*=\s*['"]([^'"]+)['"]""")

	private class EntradaNcx(val titulo: String, val nivel: Int, val pos: Int)
	private class Fragmento(val insercion: Int, val aid: String, val inicio: Int, val largo: Int)
	private class Esqueleto(val fragmentos: Int, val pos: Int, val largo: Int)

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido =
		BaseDatosPalm.abrir(fuente.archivoLocal()).use { pdb -> leerBase(pdb, fuente, ctx) }

	private fun leerBase(pdb: BaseDatosPalm, fuente: Fuente, ctx: ContextoLectura): Contenido {
		val cab = CabeceraMobi(pdb.registro(0))
		if (cab.cifrado != 0) throw ExcepcionDrm(DRM)
		if (!cab.esMobi) return Contenido.Texto(textoPalm(pdb, cab, fuente, ctx).construir())

		// KF8: AZW3 independiente o libro combinado (MOBI 6 + KF8 tras el registro BOUNDARY)
		var base8 = -1
		if (cab.esKf8) {
			base8 = 0
		} else {
			val k = cab.exthEntero(121)
			if (k in 1 until pdb.cuantos && pdb.empiezaPor(k - 1, "BOUNDARY")) base8 = k
		}
		if (base8 >= 0) {
			try {
				val cab8 = if (base8 == 0) cab else CabeceraMobi(pdb.registro(base8))
				if (cab8.cifrado != 0) throw ExcepcionDrm(DRM)
				val c = constructorMobi(ctx, listOf(cab8, cab), pdb, fuente)
				leerKf8(pdb, base8, cab8, c, ctx)
				if (!c.estaVacio()) return Contenido.Texto(c.construir())
			} catch (e: ExcepcionDrm) {
				throw e
			} catch (e: InterruptedException) {
				throw e
			} catch (e: Exception) {
				// Libro combinado: se lee la parte MOBI 6
				if (base8 == 0) throw e
			}
		}
		val c = constructorMobi(ctx, listOf(cab), pdb, fuente)
		leerMobi6(pdb, cab, c, ctx)
		if (c.estaVacio()) throw ExcepcionFormato("El libro no contiene texto.")
		return Contenido.Texto(c.construir())
	}

	// --- Metadatos ---------------------------------------------------------------------

	private fun constructorMobi(ctx: ContextoLectura, cabs: List<CabeceraMobi>, pdb: BaseDatosPalm, fuente: Fuente): ConstructorDocumento {
		val c = ctx.nuevoConstructor()
		fun exth(tipo: Int): List<String> =
			cabs.firstNotNullOfOrNull { cab -> cab.exthTextos(tipo).takeIf { it.isNotEmpty() } }.orEmpty()
		c.titulo = exth(503).firstOrNull()
			?: cabs.firstNotNullOfOrNull { it.nombreCompleto.takeIf { n -> n.isNotBlank() } }
			?: pdb.nombre.replace('_', ' ').trim().ifBlank { fuente.nombre.substringBeforeLast('.') }
		c.autor = exth(100).distinct().joinToString(", ")
		if (c.autor.isNotEmpty()) c.metadatos["Autor"] = c.autor
		exth(101).firstOrNull()?.let { c.metadatos["Editorial"] = it }
		exth(106).firstOrNull()?.let { c.metadatos["Fecha"] = it.substringBefore('T') }
		exth(103).firstOrNull()?.let { c.metadatos["Descripción"] = Jsoup.parse(it).text() }
		exth(104).firstOrNull()?.let { c.metadatos["ISBN"] = it }
		exth(105).takeIf { it.isNotEmpty() }?.let { c.metadatos["Tema"] = it.distinct().joinToString("; ") }
		c.idioma = exth(524).firstOrNull()
			?: cabs.firstNotNullOfOrNull { cab -> AyudaBinarios.idiomaDeLcid(cab.locale and 0xFF).takeIf { cab.locale != NULO && it.isNotEmpty() } }
			?: ""
		if (c.idioma.isNotEmpty()) c.metadatos["Idioma"] = c.idioma
		return c
	}

	// --- Texto -------------------------------------------------------------------------

	/** Descomprime y une los registros de texto (base: registro 0 de la parte que se lee). */
	private fun textoCrudo(pdb: BaseDatosPalm, base: Int, cab: CabeceraMobi, ctx: ContextoLectura): ByteArray {
		val total = cab.registrosTexto
		if (cab.compresion != 1 && cab.compresion != 2 && cab.compresion != HUFF) {
			throw ExcepcionFormato("El libro usa una compresión desconocida (${cab.compresion}).")
		}
		val huff = if (cab.compresion == HUFF) HuffmanCdic.cargar(pdb, base + cab.huffRegistro, cab.huffCuenta) else null
		val salida = BuferMobi(if (cab.longitudTexto in 1..(256 shl 20)) cab.longitudTexto + 8192 else 1 shl 20)
		for (i in 1..total) {
			if (i % 16 == 0) {
				ctx.comprobar()
				ctx.progreso(i * 50 / total)
			}
			val idx = base + i
			if (idx >= pdb.cuantos) break
			val reg = pdb.registro(idx)
			val fin = finSinCola(reg, cab.extra)
			when (cab.compresion) {
				1 -> salida.anadir(reg, 0, fin)
				2 -> palmDoc(reg, fin, salida)
				else -> huff!!.descomprimir(reg, fin, salida, 0)
			}
		}
		val n = if (cab.longitudTexto in 1..salida.tamano) cab.longitudTexto else salida.tamano
		return salida.copia(n)
	}

	/** Quita los datos de cola de un registro de texto según las banderas de la cabecera. */
	private fun finSinCola(r: ByteArray, banderas: Int): Int {
		var fin = r.size
		var b = banderas ushr 1
		while (b != 0) {
			if (b and 1 != 0) {
				val t = tamanoCola(r, fin)
				if (t > fin) {
					fin = r.size
					break
				}
				fin -= t
			}
			b = b ushr 1
		}
		if (banderas and 1 != 0 && fin > 0) fin -= (r[fin - 1].toInt() and 3) + 1
		return fin.coerceAtLeast(0)
	}

	/** Tamaño de un dato de cola: entero de 7 bits por byte leído hacia atrás. */
	private fun tamanoCola(r: ByteArray, fin: Int): Int {
		var resultado = 0
		var bits = 0
		var p = fin
		while (p > 0) {
			val v = r[p - 1].toInt() and 0xFF
			resultado = resultado or ((v and 0x7F) shl bits)
			bits += 7
			p--
			if (v and 0x80 != 0 || bits >= 28) break
		}
		return resultado
	}

	/** PalmDOC: LZ77 con pares de bytes (distancia, longitud) y "espacio + letra". */
	private fun palmDoc(d: ByteArray, fin: Int, s: BuferMobi) {
		var i = 0
		while (i < fin) {
			val c = d[i++].toInt() and 0xFF
			when {
				c == 0 || c in 9..0x7F -> s.anadir(c)
				c in 1..8 -> {
					val n = minOf(c, fin - i)
					s.anadir(d, i, n)
					i += n
				}
				c <= 0xBF -> {
					if (i >= fin) break
					val par = (c shl 8) or (d[i++].toInt() and 0xFF)
					val distancia = (par ushr 3) and 0x7FF
					val n = (par and 7) + 3
					if (distancia in 1..s.tamano) {
						s.asegurar(n)
						var o = s.tamano - distancia
						repeat(n) { s.datos[s.tamano++] = s.datos[o++] }
					}
				}
				else -> {
					s.anadir(0x20)
					s.anadir(c xor 0x80)
				}
			}
		}
	}

	/** Texto PalmDOC sin cabecera MOBI (TEXtREAd): texto plano. */
	private fun textoPalm(pdb: BaseDatosPalm, cab: CabeceraMobi, fuente: Fuente, ctx: ContextoLectura): ConstructorDocumento {
		val c = ctx.nuevoConstructor()
		c.titulo = pdb.nombre.replace('_', ' ').trim().ifBlank { fuente.nombre.substringBeforeLast('.') }
		val texto = Textos.decodificar(textoCrudo(pdb, 0, cab, ctx)).replace("\r\n", "\n").replace('\r', '\n')
		val conBlancos = texto.contains("\n\n")
		val parrafos = if (conBlancos) texto.split(Regex("\n\\s*\n")).map { it.replace('\n', ' ') } else texto.split('\n')
		for (p in parrafos) c.parrafo(Textos.unirEspacios(p))
		if (c.estaVacio()) throw ExcepcionFormato("El libro no contiene texto.")
		AyudaBinarios.promoverCapitulos(c)
		return c
	}

	// --- MOBI 6 ------------------------------------------------------------------------

	private fun leerMobi6(pdb: BaseDatosPalm, cab: CabeceraMobi, c: ConstructorDocumento, ctx: ContextoLectura) {
		val crudo = textoCrudo(pdb, 0, cab, ctx)
		// ISO-8859-1: un carácter por byte, así las posiciones filepos siguen valiendo
		val s = String(crudo, Charsets.ISO_8859_1)
		val destinos = TreeSet<Int>()
		for (m in FILEPOS.findAll(s)) m.groupValues[1].toIntOrNull()?.let { destinos += it }
		val toc = runCatching { leerNcx(pdb, 0, cab, null) }.getOrDefault(emptyList())
		for (e in toc) destinos += e.pos
		var html = insertarAnclas(s, destinos.filter { it in 0..s.length }.map { it to "filepos$it" })
		html = FILEPOS.replace(html) { "href=\"#filepos${it.groupValues[1].toIntOrNull() ?: 0}\"" }
		val secciones = SALTO.split(html)
		for ((i, seccion) in secciones.withIndex()) {
			if (seccion.isBlank()) continue
			ctx.comprobar()
			ctx.progreso(50 + i * 50 / secciones.size)
			c.marcarSiguiente(Marca.INICIO_SECCION)
			for (trozo in trocear(seccion)) convertir(c, decodificar(trozo, cab.codificacion), "", ctx)
		}
		val anclas = toc.map { Triple(it.titulo, it.nivel, "#filepos${it.pos}") }
		for ((titulo, nivel, ancla) in anclas) c.indiceAncla(titulo, nivel, ancla)
		AyudaBinarios.encabezadosDeIndice(c, anclas)
	}

	// --- KF8 ---------------------------------------------------------------------------

	private fun leerKf8(pdb: BaseDatosPalm, base: Int, cab: CabeceraMobi, c: ConstructorDocumento, ctx: ContextoLectura) {
		val crudo = textoCrudo(pdb, base, cab, ctx)
		// Flujo 0 (el XHTML); los demás son CSS y SVG
		var finFlujo = crudo.size
		if (cab.fdst != NULO && cab.fdst > 0 && base + cab.fdst < pdb.cuantos) {
			val f = pdb.registro(base + cab.fdst)
			if (f.size >= 20 && firma(f, 0, "FDST") && be32(f, 8) >= 1) {
				val fin0 = be32(f, 16)
				if (fin0 in 1..crudo.size) finFlujo = fin0
			}
		}
		val texto = String(crudo, 0, finFlujo, Charsets.ISO_8859_1)
		val codec = cab.codificacion

		val frags: List<Fragmento> = if (cab.frag == NULO) emptyList() else runCatching {
			leerIndice(pdb, base + cab.frag, codec)?.let { ind ->
				ind.entradas.map { e ->
					val pos = e.etiquetas[6]
					Fragmento(
						e.nombre.trim().toIntOrNull() ?: -1,
						e.etiquetas[2]?.firstOrNull()?.let { ind.cncx[it] }.orEmpty(),
						pos?.getOrNull(0) ?: 0,
						pos?.getOrNull(1) ?: 0,
					)
				}
			}
		}.getOrNull().orEmpty()
		val esqueletos: List<Esqueleto> = if (cab.skel == NULO) emptyList() else runCatching {
			leerIndice(pdb, base + cab.skel, codec)?.entradas?.map { e ->
				val pos = e.etiquetas[6]
				Esqueleto(e.etiquetas[1]?.firstOrNull() ?: 0, pos?.getOrNull(0) ?: -1, pos?.getOrNull(1) ?: 0)
			}
		}.getOrNull().orEmpty()

		fun posDe(fid: Int, off: Int): Int? = frags.getOrNull(fid)?.takeIf { it.insercion >= 0 }?.let { it.insercion + off }

		// Destinos de enlaces e índice: posiciones del texto
		val destinos = TreeSet<Int>()
		for (m in KINDLE_POS.findAll(texto)) {
			val fid = m.groupValues[1].toIntOrNull(32) ?: continue
			val off = m.groupValues[2].toIntOrNull(32) ?: continue
			posDe(fid, off)?.let { destinos += it }
		}
		val toc = runCatching { leerNcx(pdb, base, cab, ::posDe) }.getOrDefault(emptyList())
		for (e in toc) destinos += e.pos

		val partes = runCatching { coser(texto, esqueletos, frags) }.getOrNull() ?: trocearPorHtml(texto)
		for ((i, parte) in partes.withIndex()) {
			ctx.comprobar()
			ctx.progreso(50 + i * 50 / partes.size)
			val (inicio, s) = parte
			val propios = destinos.subSet(inicio, inicio + s.length).map { (it - inicio) to "kpos$it" }
			var html = insertarAnclas(s, propios)
			html = KINDLE_POS.replace(html) { m ->
				val fid = m.groupValues[1].toIntOrNull(32)
				val off = m.groupValues[2].toIntOrNull(32)
				val pos = if (fid != null && off != null) posDe(fid, off) else null
				if (pos != null) "#kpos$pos" else ""
			}
			c.marcarSiguiente(Marca.INICIO_SECCION)
			convertir(c, decodificar(html, codec), "part%04d.html".format(i), ctx)
		}
		val anclas = toc.map { Triple(it.titulo, it.nivel, "#kpos${it.pos}") }
		for ((titulo, nivel, ancla) in anclas) c.indiceAncla(titulo, nivel, ancla)
		AyudaBinarios.encabezadosDeIndice(c, anclas)
	}

	/** Vuelve a montar cada XHTML metiendo sus fragmentos en el esqueleto (como calibre). */
	private fun coser(texto: String, esqueletos: List<Esqueleto>, frags: List<Fragmento>): List<Pair<Int, String>>? {
		if (esqueletos.isEmpty() || frags.isEmpty()) return null
		val partes = ArrayList<Pair<Int, String>>()
		var f = 0
		for (e in esqueletos) {
			if (e.pos < 0 || e.largo < 0 || e.pos + e.largo > texto.length) return null
			var base = e.pos + e.largo
			val sb = StringBuilder(texto.substring(e.pos, base))
			for (k in 0 until e.fragmentos) {
				val fr = frags.getOrNull(f++) ?: return null
				if (fr.largo < 0 || base + fr.largo > texto.length) return null
				var ins = fr.insercion - e.pos
				if (ins !in 0..sb.length || etiquetaPartida(sb, ins)) {
					// Posición de inserción mala: se busca la etiqueta con su aid
					val aid = AID.find(fr.aid)?.groupValues?.get(1)
						?: fr.aid.takeIf { it.length > 14 }?.substring(12, fr.aid.length - 2)
					val fin = aid?.let { buscarAid(sb, it) }
					ins = (if (fin != null) fin + fr.inicio else ins).coerceIn(0, sb.length)
				}
				sb.insert(ins, texto, base, base + fr.largo)
				base += fr.largo
			}
			partes += e.pos to sb.toString()
		}
		return partes
	}

	private fun etiquetaPartida(sb: StringBuilder, ins: Int): Boolean {
		val cierreTras = sb.indexOf(">", ins)
		val aperturaTras = sb.indexOf("<", ins)
		val cierreAntes = if (ins > 0) sb.lastIndexOf(">", ins - 1) else -1
		val aperturaAntes = if (ins > 0) sb.lastIndexOf("<", ins - 1) else -1
		return cierreTras < aperturaTras || cierreAntes < aperturaAntes
	}

	/** Posición justo detrás de la etiqueta que lleva aid="..." (null si no está). */
	private fun buscarAid(sb: StringBuilder, aid: String): Int? {
		for (comilla in listOf('\'', '"')) {
			val i = sb.indexOf("aid=$comilla$aid$comilla")
			if (i >= 0) {
				val fin = sb.indexOf(">", i)
				if (fin >= 0) return fin + 1
			}
		}
		return null
	}

	/** Sin tablas de esqueletos: se parte el texto por cada <html>. */
	private fun trocearPorHtml(texto: String): List<Pair<Int, String>> {
		val cortes = INICIO_HTML.findAll(texto).map { it.range.first }.toMutableList()
		if (cortes.isEmpty() || cortes[0] > 0) cortes.add(0, 0)
		cortes += texto.length
		val partes = ArrayList<Pair<Int, String>>()
		for (i in 0 until cortes.size - 1) {
			if (cortes[i + 1] > cortes[i]) partes += cortes[i] to texto.substring(cortes[i], cortes[i + 1])
		}
		return partes
	}

	// --- Índices INDX (NCX, SKEL, FRAG) ------------------------------------------------

	private class EntradaIndx(val nombre: String, val etiquetas: Map<Int, IntArray>)
	private class Indice(val entradas: List<EntradaIndx>, val cncx: Map<Int, String>)

	private fun leerNcx(pdb: BaseDatosPalm, base: Int, cab: CabeceraMobi, posDe: ((Int, Int) -> Int?)?): List<EntradaNcx> {
		if (cab.ncx == NULO || cab.ncx <= 0) return emptyList()
		val ind = leerIndice(pdb, base + cab.ncx, cab.codificacion) ?: return emptyList()
		return ind.entradas.mapNotNull { e ->
			val titulo = e.etiquetas[3]?.firstOrNull()?.let { ind.cncx[it] }?.trim().orEmpty()
			if (titulo.isEmpty()) return@mapNotNull null
			val nivel = ((e.etiquetas[4]?.firstOrNull() ?: 0) + 1).coerceIn(1, 6)
			val fidOff = e.etiquetas[6]
			val pos = if (posDe != null && fidOff != null && fidOff.size >= 2) posDe(fidOff[0], fidOff[1])
			else e.etiquetas[1]?.firstOrNull()
			pos?.takeIf { it >= 0 }?.let { EntradaNcx(titulo, nivel, it) }
		}
	}

	/** Lee un índice INDX: cabecera con TAGX, registros de entradas (IDXT) y textos CNCX. */
	private fun leerIndice(pdb: BaseDatosPalm, primero: Int, codec: Charset): Indice? {
		if (primero <= 0 || primero >= pdb.cuantos) return null
		val cab = pdb.registro(primero)
		if (cab.size < 56 || !firma(cab, 0, "INDX")) return null
		val cuantos = be32(cab, 24)
		val ncncx = be32(cab, 52)
		var tagx = be32(cab, 4)
		if (!firma(cab, tagx, "TAGX")) tagx = buscarFirma(cab, "TAGX")
		if (tagx < 0 || tagx + 12 > cab.size || cuantos !in 0..100_000) return null
		val primeraEntrada = be32(cab, tagx + 4)
		val bytesControl = be32(cab, tagx + 8)
		val etiquetas = ArrayList<IntArray>()
		var i = 12
		while (i + 4 <= primeraEntrada && tagx + i + 4 <= cab.size) {
			val o = tagx + i
			etiquetas += IntArray(4) { k -> cab[o + k].toInt() and 0xFF }
			i += 4
		}
		val cncx = HashMap<Int, String>()
		if (ncncx in 1..1000) {
			for (k in 0 until ncncx) {
				val idx = primero + cuantos + 1 + k
				if (idx >= pdb.cuantos) break
				val r = pdb.registro(idx)
				var p = 0
				while (p < r.size) {
					val (largo, usados) = decint(r, p, r.size)
					if (largo <= 0) {
						p += maxOf(usados, 1)
						continue
					}
					val desde = p + usados
					val hasta = minOf(r.size, desde + largo)
					cncx[p + k * 0x10000] = String(r, desde, hasta - desde, codec)
					p = hasta
				}
			}
		}
		val salida = ArrayList<EntradaIndx>()
		for (k in 1..cuantos) {
			val idx = primero + k
			if (idx >= pdb.cuantos) break
			val r = pdb.registro(idx)
			if (r.size < 28 || !firma(r, 0, "INDX")) continue
			val idxt = be32(r, 20)
			val n = be32(r, 24)
			if (!firma(r, idxt, "IDXT") || n !in 0..65_535) continue
			val pos = IntArray(n + 1)
			for (j in 0 until n) {
				val o = idxt + 4 + j * 2
				pos[j] = if (o + 2 <= r.size) be16(r, o) else idxt
			}
			pos[n] = idxt
			for (j in 0 until n) {
				val a = pos[j]
				val b = pos[j + 1]
				if (a <= 0 || a >= b || b > r.size) continue
				val ln = r[a].toInt() and 0xFF
				if (a + 1 + ln > b) continue
				val nombre = String(r, a + 1, ln, Charsets.ISO_8859_1)
				salida += EntradaIndx(nombre, mapaEtiquetas(r, a + 1 + ln, b, bytesControl, etiquetas))
			}
		}
		return Indice(salida, cncx)
	}

	/** Valores de las etiquetas de una entrada según la tabla TAGX (bytes de control + enteros variables). */
	private fun mapaEtiquetas(d: ByteArray, desde: Int, hasta: Int, nControl: Int, etiquetas: List<IntArray>): Map<Int, IntArray> {
		val finControl = desde + nControl
		if (nControl < 0 || finControl > hasta) return emptyMap()
		var p = finControl
		var ci = desde
		// [etiqueta, número de valores (-1), número de bytes (-1), valores por entrada]
		val pendientes = ArrayList<IntArray>()
		for (t in etiquetas) {
			val etiqueta = t[0]
			val porValor = t[1]
			val mascara = t[2]
			if (t[3] == 1) {
				ci++
				continue
			}
			if (ci >= finControl) break
			var valor = d[ci].toInt() and mascara
			if (valor == 0) continue
			if (valor == mascara) {
				if (Integer.bitCount(mascara) > 1) {
					val (v, u) = decint(d, p, hasta)
					p += u
					pendientes += intArrayOf(etiqueta, -1, v, porValor)
				} else {
					pendientes += intArrayOf(etiqueta, 1, -1, porValor)
				}
			} else {
				var m = mascara
				while (m and 1 == 0) {
					m = m ushr 1
					valor = valor ushr 1
				}
				pendientes += intArrayOf(etiqueta, valor, -1, porValor)
			}
		}
		val mapa = HashMap<Int, IntArray>()
		for (pt in pendientes) {
			val valores = ArrayList<Int>()
			if (pt[1] >= 0) {
				repeat(pt[1] * maxOf(1, pt[3])) {
					if (p < hasta) {
						val (v, u) = decint(d, p, hasta)
						p += u
						valores += v
					}
				}
			} else {
				var gastados = 0
				while (gastados < pt[2] && p < hasta) {
					val (v, u) = decint(d, p, hasta)
					p += u
					gastados += u
					valores += v
				}
			}
			mapa[pt[0]] = valores.toIntArray()
		}
		return mapa
	}

	/** Entero variable hacia delante: 7 bits por byte, el último con el bit alto. */
	private fun decint(d: ByteArray, p: Int, hasta: Int): Pair<Int, Int> {
		var v = 0L
		var u = 0
		while (p + u < hasta && u < 5) {
			val b = d[p + u].toInt() and 0xFF
			u++
			v = (v shl 7) or (b and 0x7F).toLong()
			if (b and 0x80 != 0) break
		}
		return v.toInt() to maxOf(u, 1)
	}

	// --- HTML --------------------------------------------------------------------------

	/**
	 * Inserta <a id="..."></a> en posiciones del texto (ISO-8859-1). Si la
	 * posición cae dentro de una etiqueta, va detrás de ella; si cae antes
	 * del <body>, justo después de él.
	 */
	private fun insertarAnclas(s: String, anclas: List<Pair<Int, String>>): String {
		if (anclas.isEmpty()) return s
		val cuerpo = s.indexOf("<body", ignoreCase = true)
		val finCuerpo = if (cuerpo >= 0) s.indexOf('>', cuerpo).let { if (it < 0) -1 else it + 1 } else -1
		val puntos = anclas.map { (p, id) ->
			var q = p.coerceIn(0, s.length)
			if (finCuerpo > 0 && q < finCuerpo) {
				q = finCuerpo
			} else if (q > 0) {
				val a = s.lastIndexOf('<', q - 1)
				val b = s.lastIndexOf('>', q - 1)
				if (a > b) {
					val f = s.indexOf('>', q)
					q = if (f < 0) s.length else f + 1
				}
			}
			q to id
		}.sortedBy { it.first }
		val sb = StringBuilder(s.length + puntos.size * 32)
		var ultimo = 0
		for ((p, id) in puntos) {
			sb.append(s, ultimo, p)
			sb.append("<a id=\"").append(id).append("\"></a>")
			ultimo = p
		}
		sb.append(s, ultimo, s.length)
		return sb.toString()
	}

	/** Parte un HTML muy grande por finales de párrafo (para no montar un DOM enorme). */
	private fun trocear(t: String): List<String> {
		if (t.length <= 3_000_000) return listOf(t)
		val salida = ArrayList<String>()
		var desde = 0
		while (t.length - desde > 2_000_000) {
			var corte = t.indexOf("</p>", desde + 2_000_000, ignoreCase = true)
			if (corte < 0) break
			corte += 4
			salida += t.substring(desde, corte)
			desde = corte
		}
		salida += t.substring(desde)
		return salida
	}

	private fun decodificar(latin1: String, codec: Charset): String = String(latin1.toByteArray(Charsets.ISO_8859_1), codec)

	private fun convertir(c: ConstructorDocumento, html: String, ruta: String, ctx: ContextoLectura) {
		val doc = Jsoup.parse(html)
		ConversorHtml(c, ruta, ctx.opciones).convertir(doc.body())
	}
}

// --- Base de datos Palm y cabeceras -----------------------------------------------------

private fun be16(b: ByteArray, o: Int): Int =
	if (o < 0 || o + 2 > b.size) 0 else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

/** Entero de 32 bits big-endian (con signo: 0xFFFFFFFF = -1 = "no hay"). */
private fun be32(b: ByteArray, o: Int): Int =
	if (o < 0 || o + 4 > b.size) -1
	else ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
		((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

private fun be32u(b: ByteArray, o: Int): Long = be32(b, o).toLong() and 0xFFFFFFFFL

private fun firma(b: ByteArray, o: Int, f: String): Boolean {
	if (o < 0 || o + f.length > b.size) return false
	for (i in f.indices) if (b[o + i] != f[i].code.toByte()) return false
	return true
}

private fun buscarFirma(b: ByteArray, f: String): Int {
	for (i in 0..b.size - f.length) if (firma(b, i, f)) return i
	return -1
}

private class BaseDatosPalm private constructor(
	private val raf: RandomAccessFile,
	val nombre: String,
	private val inicios: LongArray,
) : Closeable {

	private val tamano = raf.length()
	val cuantos: Int get() = inicios.size

	fun registro(i: Int): ByteArray {
		if (i !in 0 until cuantos) throw ExcepcionFormato("El libro está dañado: falta un registro.")
		val inicio = inicios[i]
		val fin = if (i + 1 < cuantos) inicios[i + 1] else tamano
		if (inicio >= tamano || fin <= inicio) return ByteArray(0)
		val n = (minOf(fin, tamano) - inicio).coerceAtMost(64L * 1024 * 1024).toInt()
		val b = ByteArray(n)
		raf.seek(inicio)
		raf.readFully(b)
		return b
	}

	fun empiezaPor(i: Int, texto: String): Boolean = i in 0 until cuantos && firma(registro(i), 0, texto)

	override fun close() = raf.close()

	companion object {
		fun abrir(archivo: File): BaseDatosPalm {
			val raf = RandomAccessFile(archivo, "r")
			try {
				val tamano = raf.length()
				if (tamano < 86) throw ExcepcionFormato("El archivo no es un libro MOBI válido.")
				val cab = ByteArray(78)
				raf.readFully(cab)
				var finNombre = 0
				while (finNombre < 32 && cab[finNombre].toInt() != 0) finNombre++
				val nombre = String(cab, 0, finNombre, Charset.forName("windows-1252"))
				val n = be16(cab, 76)
				if (n == 0 || 78L + n * 8L > tamano) throw ExcepcionFormato("El archivo no es un libro MOBI válido.")
				val lista = ByteArray(n * 8)
				raf.readFully(lista)
				val inicios = LongArray(n) { be32u(lista, it * 8) }
				return BaseDatosPalm(raf, nombre, inicios)
			} catch (e: Exception) {
				raf.close()
				throw if (e is ExcepcionFormato) e else ExcepcionFormato("El archivo no es un libro MOBI válido.", e)
			}
		}
	}
}

/** Cabeceras PalmDOC, MOBI y EXTH del registro 0 (offsets desde el principio del registro). */
private class CabeceraMobi(private val r: ByteArray) {
	val compresion = be16(r, 0)
	val longitudTexto = be32(r, 4)
	val registrosTexto = be16(r, 8)
	val cifrado = be16(r, 12)
	val esMobi = firma(r, 16, "MOBI")
	private val largo = if (esMobi) be32(r, 20).coerceIn(0, r.size) else 0

	private fun campo(o: Int): Int = if (esMobi && o + 4 <= r.size && o + 4 <= 16 + largo) be32(r, o) else -1

	val codificacion: Charset = if (campo(28) == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
	val esKf8 = campo(0x24) == 8 || campo(0x68) == 8
	val locale = campo(92)
	val huffRegistro = campo(0x70)
	val huffCuenta = campo(0x74)
	private val banderasExth = campo(0x80)
	val fdst = if (esKf8) campo(0xC0) else -1
	val extra = if (esMobi && largo >= 0xE4 && r.size >= 0xF4) be16(r, 0xF2) else 0
	val ncx = campo(0xF4)
	val frag = if (esKf8) campo(0xF8) else -1
	val skel = if (esKf8) campo(0xFC) else -1

	val nombreCompleto: String = run {
		val desde = campo(84)
		val n = campo(88)
		if (desde > 0 && n > 0 && desde.toLong() + n <= r.size) String(r, desde, n, codificacion).replace("\u0000", "").trim() else ""
	}

	private val exth: Map<Int, List<ByteArray>> = run {
		val mapa = HashMap<Int, MutableList<ByteArray>>()
		val e = 16 + largo
		if (esMobi && banderasExth != -1 && banderasExth and 0x40 != 0 && firma(r, e, "EXTH")) {
			val n = be32(r, e + 8)
			var p = e + 12
			for (i in 0 until n.coerceIn(0, 1000)) {
				if (p + 8 > r.size) break
				val tipo = be32(r, p)
				val l = be32(r, p + 4)
				if (l < 8 || p + l > r.size) break
				mapa.getOrPut(tipo) { ArrayList() } += r.copyOfRange(p + 8, p + l)
				p += l
			}
		}
		mapa
	}

	fun exthTextos(tipo: Int): List<String> =
		exth[tipo].orEmpty().map { String(it, codificacion).replace("\u0000", "").trim() }.filter { it.isNotEmpty() }

	fun exthEntero(tipo: Int): Int = exth[tipo]?.firstOrNull { it.size >= 4 }?.let { be32(it, 0) } ?: -1
}

/** Bytes que crecen (salida de la descompresión). */
private class BuferMobi(inicial: Int) {
	var datos = ByteArray(maxOf(16, inicial))
	var tamano = 0

	fun asegurar(extra: Int) {
		if (tamano + extra > datos.size) datos = datos.copyOf(maxOf(datos.size * 2, tamano + extra))
	}

	fun anadir(b: Int) {
		asegurar(1)
		datos[tamano++] = b.toByte()
	}

	fun anadir(b: ByteArray, desde: Int, n: Int) {
		if (n <= 0) return
		asegurar(n)
		System.arraycopy(b, desde, datos, tamano, n)
		tamano += n
	}

	fun copia(n: Int): ByteArray = datos.copyOf(n)
}

/**
 * HUFF/CDIC (la compresión de los MOBI grandes), como en calibre: el
 * registro HUFF trae la tabla rápida de 256 entradas y los códigos mínimo
 * y máximo de cada longitud; los CDIC, las frases (que pueden estar a su
 * vez comprimidas y se expanden una sola vez).
 */
private class HuffmanCdic(huff: ByteArray, cdics: List<ByteArray>) {
	private val largo1 = IntArray(256)
	private val term1 = BooleanArray(256)
	private val max1 = LongArray(256)
	private val minCodigo = LongArray(33)
	private val maxCodigo = LongArray(33)
	private val frases = ArrayList<ByteArray?>()
	private val claras = ArrayList<Boolean>()

	init {
		if (huff.size < 24 || !firma(huff, 0, "HUFF")) throw ExcepcionFormato("La tabla de compresión del libro está dañada.")
		val off1 = be32(huff, 8)
		val off2 = be32(huff, 12)
		if (off1 < 0 || off1 + 1024 > huff.size || off2 < 0 || off2 + 256 > huff.size) {
			throw ExcepcionFormato("La tabla de compresión del libro está dañada.")
		}
		for (i in 0 until 256) {
			val v = be32u(huff, off1 + i * 4)
			val l = (v and 0x1F).toInt()
			largo1[i] = l
			term1[i] = v and 0x80 != 0L
			max1[i] = if (l > 0) (((v ushr 8) + 1) shl (32 - l)) - 1 else 0
		}
		for (l in 1..32) {
			val mn = be32u(huff, off2 + (l - 1) * 8)
			val mx = be32u(huff, off2 + (l - 1) * 8 + 4)
			minCodigo[l] = mn shl (32 - l)
			maxCodigo[l] = ((mx + 1) shl (32 - l)) - 1
		}
		for (cdic in cdics) cargarDiccionario(cdic)
		if (frases.isEmpty()) throw ExcepcionFormato("El diccionario de compresión del libro está vacío.")
	}

	private fun cargarDiccionario(cdic: ByteArray) {
		if (cdic.size < 16 || !firma(cdic, 0, "CDIC")) return
		val numFrases = be32(cdic, 8)
		val bits = be32(cdic, 12)
		if (bits !in 0..30) return
		val n = minOf(1 shl bits, numFrases - frases.size)
		for (i in 0 until n) {
			val o = 16 + i * 2
			if (o + 2 > cdic.size) break
			val p = 16 + be16(cdic, o)
			if (p + 2 > cdic.size) {
				frases += ByteArray(0)
				claras += true
				continue
			}
			val marca = be16(cdic, p)
			val fin = minOf(cdic.size, p + 2 + (marca and 0x7FFF))
			frases += cdic.copyOfRange(p + 2, fin)
			claras += marca and 0x8000 != 0
		}
	}

	private fun leer64(d: ByteArray, n: Int, pos: Int): Long {
		var v = 0L
		for (i in 0 until 8) {
			val p = pos + i
			v = (v shl 8) or (if (p < n) (d[p].toLong() and 0xFF) else 0L)
		}
		return v
	}

	fun descomprimir(d: ByteArray, n: Int, s: BuferMobi, profundidad: Int) {
		if (profundidad > 32) return
		var quedan = n.toLong() * 8
		var pos = 0
		var x = leer64(d, n, 0)
		var bits = 32
		while (true) {
			if (bits <= 0) {
				pos += 4
				x = leer64(d, n, pos)
				bits += 32
			}
			val codigo = (x ushr bits) and 0xFFFFFFFFL
			val i = (codigo ushr 24).toInt()
			var l = largo1[i]
			if (l == 0) break
			var max = max1[i]
			if (!term1[i]) {
				while (l < 32 && codigo < minCodigo[l]) l++
				max = maxCodigo[l]
			}
			bits -= l
			quedan -= l
			if (quedan < 0) break
			val r = (max - codigo) ushr (32 - l)
			if (r < 0 || r >= frases.size) break
			val idx = r.toInt()
			val frase = frases[idx] ?: break
			if (!claras[idx]) {
				// Frase comprimida: se expande una vez y se guarda ya en claro
				frases[idx] = null
				val t = BuferMobi(frase.size * 4)
				descomprimir(frase, frase.size, t, profundidad + 1)
				val expandida = t.copia(t.tamano)
				frases[idx] = expandida
				claras[idx] = true
				s.anadir(expandida, 0, expandida.size)
			} else {
				s.anadir(frase, 0, frase.size)
			}
		}
	}

	companion object {
		fun cargar(pdb: BaseDatosPalm, inicio: Int, cuenta: Int): HuffmanCdic {
			if (inicio <= 0 || cuenta <= 0 || inicio + cuenta > pdb.cuantos) {
				throw ExcepcionFormato("La tabla de compresión del libro falta o está dañada.")
			}
			return HuffmanCdic(pdb.registro(inicio), (1 until cuenta).map { pdb.registro(inicio + it) })
		}
	}
}

// --- Ayudas comunes a MOBI, CHM y PDF ---------------------------------------------------

internal object AyudaBinarios {

	/** Minúsculas, sin acentos y solo letras y dígitos (para comparar títulos). */
	fun normalizar(texto: String): String {
		val sin = Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD)
		val sb = StringBuilder(sin.length)
		for (ch in sin) if (ch.isLetterOrDigit()) sb.append(ch)
		return sb.toString()
	}

	/** ¿El texto de un bloque es (más o menos) el título de una entrada del índice? */
	fun parecidos(texto: String, titulo: String): Boolean {
		if (texto.length > 200) return false
		val a = normalizar(texto)
		val b = normalizar(titulo)
		if (a.isEmpty() || b.isEmpty()) return false
		return a == b || (a.startsWith(b) && a.length <= b.length + 60) || (b.startsWith(a) && a.length >= minOf(4, b.length))
	}

	/** Libros sin h1-h6: los bloques a los que apunta el índice se convierten en encabezados. */
	fun encabezadosDeIndice(c: ConstructorDocumento, entradas: List<Triple<String, Int, String>>) {
		if (c.tieneEncabezados()) return
		for ((titulo, nivel, ancla) in entradas) {
			val b = c.resolver(ancla) ?: continue
			for (i in b until minOf(b + 4, c.tamano)) {
				if (AyudaBinarios.parecidos(c.textoDe(i).orEmpty(), titulo)) {
					c.cambiarAEncabezado(i, nivel)
					break
				}
			}
		}
		if (!c.tieneEncabezados()) promoverCapitulos(c)
	}

	private val CAPITULO = Regex(
		"""^(cap[ií]tulo|chapter|parte|part|libro|book|tomo|volumen|volume)\s+([0-9]+|[ivxlcdm]+|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|primero|segundo|tercero|one|two|three|four|five|six|seven|eight|nine|ten|first|second|third)\b"""
	)
	private val PROLOGO = Regex("""^(pr[oó]logo|ep[ií]logo|prologue|epilogue|introducci[oó]n|introduction)\s*[.:]?$""")

	/** Último recurso: párrafos cortos del tipo "Capítulo 3" o "Parte II" pasan a encabezado. */
	fun promoverCapitulos(c: ConstructorDocumento) {
		for (i in 0 until c.tamano) {
			if (c.tipoDe(i) != TipoBloque.PARRAFO) continue
			val t = c.textoDe(i)?.trim()?.lowercase() ?: continue
			if (t.length < 80 && (CAPITULO.containsMatchIn(t) || PROLOGO.matches(t))) c.cambiarAEncabezado(i, 1)
		}
	}

	/** Código de idioma de un LCID de Windows (se usa el idioma principal, 10 bits bajos). */
	fun idiomaDeLcid(lcid: Int): String = when (lcid and 0x3FF) {
		0x01 -> "ar"; 0x02 -> "bg"; 0x03 -> "ca"; 0x04 -> "zh"; 0x05 -> "cs"; 0x06 -> "da"; 0x07 -> "de"
		0x08 -> "el"; 0x09 -> "en"; 0x0A -> "es"; 0x0B -> "fi"; 0x0C -> "fr"; 0x0D -> "he"; 0x0E -> "hu"
		0x0F -> "is"; 0x10 -> "it"; 0x11 -> "ja"; 0x12 -> "ko"; 0x13 -> "nl"; 0x14 -> "nb"; 0x15 -> "pl"
		0x16 -> "pt"; 0x18 -> "ro"; 0x19 -> "ru"; 0x1A -> "hr"; 0x1B -> "sk"; 0x1C -> "sq"; 0x1D -> "sv"
		0x1E -> "th"; 0x1F -> "tr"; 0x20 -> "ur"; 0x21 -> "id"; 0x22 -> "uk"; 0x23 -> "be"; 0x24 -> "sl"
		0x25 -> "et"; 0x26 -> "lv"; 0x27 -> "lt"; 0x29 -> "fa"; 0x2A -> "vi"; 0x2D -> "eu"; 0x2F -> "mk"
		0x36 -> "af"; 0x39 -> "hi"; 0x56 -> "gl"
		else -> ""
	}

	/** Página de códigos de Windows que corresponde a un LCID (para HTML sin charset). */
	fun codificacionDeLcid(lcid: Int): String = when (lcid and 0x3FF) {
		0x19, 0x22, 0x23, 0x02, 0x2F -> "windows-1251"
		0x1A -> if (lcid == 0x0C1A || lcid == 0x1C1A) "windows-1251" else "windows-1250"
		0x05, 0x0E, 0x15, 0x18, 0x1B, 0x24, 0x1C -> "windows-1250"
		0x08 -> "windows-1253"
		0x1F -> "windows-1254"
		0x0D -> "windows-1255"
		0x01, 0x29, 0x20 -> "windows-1256"
		0x25, 0x26, 0x27 -> "windows-1257"
		0x2A -> "windows-1258"
		0x1E -> "windows-874"
		0x11 -> "Shift_JIS"
		0x12 -> "EUC-KR"
		0x04 -> if (lcid == 0x0404 || lcid == 0x0C04 || lcid == 0x1404) "Big5" else "GBK"
		else -> "windows-1252"
	}
}
