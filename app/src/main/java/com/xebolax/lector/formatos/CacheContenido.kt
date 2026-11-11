package com.xebolax.lector.formatos

import android.content.Context
import android.provider.DocumentsContract
import com.xebolax.lector.modelo.Bloque
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.Documento
import com.xebolax.lector.modelo.Enlace
import com.xebolax.lector.modelo.EntradaIndice
import com.xebolax.lector.modelo.FraseAudio
import com.xebolax.lector.modelo.LibroAudio
import com.xebolax.lector.modelo.MarcaAudio
import com.xebolax.lector.modelo.Pagina
import com.xebolax.lector.modelo.Pista
import com.xebolax.lector.modelo.TipoBloque
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Caché de documentos ya convertidos (en la carpeta de caché de la app):
 * volver a abrir un libro es instantáneo. La clave incluye el archivo
 * (dirección, tamaño y fecha), las opciones de conversión y VERSION: al
 * mejorar un lector de formato basta con subir VERSION para que los libros
 * se vuelvan a convertir.
 */
object CacheContenido {

	/** Súbela cuando cambie cualquier lector de formato o el modelo. */
	const val VERSION = 5
	private const val MAGICO = 0x4C55_4331 // "LUC1"
	private const val MAXIMO_BYTES = 400L * 1024 * 1024

	private fun carpeta(contexto: Context) = File(contexto.cacheDir, "documentos").apply { mkdirs() }

	fun clave(fuente: Fuente, formato: Formato, opciones: OpcionesLectura): String {
		val modificado = runCatching {
			if (fuente.uri.scheme == "file") File(fuente.uri.path!!).lastModified()
			else fuente.contexto.contentResolver.query(
				fuente.uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null,
			)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
		}.getOrDefault(0L)
		return Fuente.hash("${fuente.uri}|${fuente.tamano}|$modificado|$formato|${opciones.firma()}|$VERSION")
	}

	fun borrar(contexto: Context) {
		carpeta(contexto).listFiles()?.forEach { it.delete() }
	}

	fun tamano(contexto: Context): Long = carpeta(contexto).listFiles()?.sumOf { it.length() } ?: 0L

	fun cargar(contexto: Context, clave: String): Contenido? {
		val archivo = File(carpeta(contexto), "$clave.bin")
		if (!archivo.exists()) return null
		return try {
			DataInputStream(BufferedInputStream(GZIPInputStream(archivo.inputStream()), 64 * 1024)).use { e ->
				if (e.readInt() != MAGICO || e.readInt() != VERSION) return null
				archivo.setLastModified(System.currentTimeMillis())
				when (e.readByte().toInt()) {
					0 -> Contenido.Texto(leerDocumento(e))
					1 -> Contenido.Audio(leerAudio(e))
					else -> null
				}
			}
		} catch (_: Exception) {
			archivo.delete()
			null
		}
	}

	fun guardar(contexto: Context, clave: String, contenido: Contenido) {
		val dir = carpeta(contexto)
		val parcial = File(dir, "$clave.parte")
		DataOutputStream(BufferedOutputStream(GZIPOutputStream(parcial.outputStream()), 64 * 1024)).use { s ->
			s.writeInt(MAGICO)
			s.writeInt(VERSION)
			when (contenido) {
				is Contenido.Texto -> {
					s.writeByte(0)
					escribirDocumento(s, contenido.documento)
				}
				is Contenido.Audio -> {
					s.writeByte(1)
					escribirAudio(s, contenido.libro)
				}
			}
		}
		parcial.renameTo(File(dir, "$clave.bin"))
		// Límite de tamaño: se borran los menos usados
		val archivos = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
		var total = archivos.sumOf { it.length() }
		for (a in archivos) {
			if (total <= MAXIMO_BYTES) break
			total -= a.length()
			a.delete()
		}
	}

	// --- Escritura ---------------------------------------------------------------

	private fun DataOutputStream.texto(t: String) {
		val b = t.toByteArray(Charsets.UTF_8)
		writeInt(b.size)
		write(b)
	}

	private fun DataOutputStream.mapa(m: Map<String, String>) {
		writeInt(m.size)
		m.forEach { (k, v) -> texto(k); texto(v) }
	}

	private fun DataOutputStream.lista(l: List<String>) {
		writeInt(l.size)
		l.forEach { texto(it) }
	}

	private fun escribirDocumento(s: DataOutputStream, d: Documento) {
		s.texto(d.titulo)
		s.texto(d.autor)
		s.texto(d.idioma)
		s.mapa(d.metadatos)
		s.lista(d.avisos)
		s.writeInt(d.bloques.size)
		for (b in d.bloques) {
			s.writeByte(b.tipo.ordinal)
			s.texto(b.texto)
			s.writeByte(b.nivel)
			s.writeInt(b.marcas)
			s.texto(b.idioma.orEmpty())
			s.writeShort(b.enlaces.size)
			for (e in b.enlaces) {
				s.writeInt(e.inicio)
				s.writeInt(e.fin)
				s.texto(e.destino)
			}
		}
		s.writeInt(d.indice.size)
		for (i in d.indice) {
			s.texto(i.titulo)
			s.writeByte(i.nivel)
			s.writeInt(i.bloque)
		}
		s.writeInt(d.paginas.size)
		for (p in d.paginas) {
			s.texto(p.etiqueta)
			s.writeInt(p.bloque)
		}
		s.writeInt(d.imagenes.size)
		for ((bloque, recurso) in d.imagenes) {
			s.writeInt(bloque)
			s.texto(recurso)
		}
	}

	private fun escribirAudio(s: DataOutputStream, l: LibroAudio) {
		s.texto(l.titulo)
		s.texto(l.autor)
		s.texto(l.narrador)
		s.mapa(l.metadatos)
		s.lista(l.avisos)
		s.writeInt(l.pistas.size)
		for (p in l.pistas) {
			s.texto(p.uri)
			s.texto(p.titulo)
			s.writeLong(p.duracionMs)
		}
		for (marcas in listOf(l.capitulos, l.paginas)) {
			s.writeInt(marcas.size)
			for (m in marcas) {
				s.texto(m.titulo)
				s.writeByte(m.nivel)
				s.writeInt(m.pista)
				s.writeLong(m.ms)
			}
		}
		s.writeInt(l.frases.size)
		for (f in l.frases) {
			s.writeInt(f.pista)
			s.writeLong(f.inicioMs)
			s.writeLong(f.finMs)
			s.writeInt(f.bloque)
		}
		s.writeBoolean(l.texto != null)
		l.texto?.let { escribirDocumento(s, it) }
	}

	// --- Lectura -----------------------------------------------------------------

	private fun DataInputStream.texto(): String {
		val n = readInt()
		val b = ByteArray(n)
		readFully(b)
		return String(b, Charsets.UTF_8)
	}

	private fun DataInputStream.mapa(): Map<String, String> {
		val n = readInt()
		val m = LinkedHashMap<String, String>(n)
		repeat(n) { m[texto()] = texto() }
		return m
	}

	private fun DataInputStream.lista(): List<String> = List(readInt()) { texto() }

	private fun leerDocumento(e: DataInputStream): Documento {
		val titulo = e.texto()
		val autor = e.texto()
		val idioma = e.texto()
		val metadatos = e.mapa()
		val avisos = e.lista()
		val tipos = TipoBloque.entries
		val bloques = List(e.readInt()) {
			val tipo = tipos[e.readByte().toInt()]
			val texto = e.texto()
			val nivel = e.readByte().toInt()
			val marcas = e.readInt()
			val idiomaBloque = e.texto().ifEmpty { null }
			val enlaces = List(e.readShort().toInt()) { Enlace(e.readInt(), e.readInt(), e.texto()) }
			Bloque(tipo, texto, nivel, enlaces, marcas, idiomaBloque)
		}
		val indice = List(e.readInt()) { EntradaIndice(e.texto(), e.readByte().toInt(), e.readInt()) }
		val paginas = List(e.readInt()) { Pagina(e.texto(), e.readInt()) }
		val imagenes = HashMap<Int, String>()
		repeat(e.readInt()) { imagenes[e.readInt()] = e.texto() }
		return Documento(titulo, autor, idioma, bloques, indice, paginas, metadatos, avisos, imagenes)
	}

	private fun leerAudio(e: DataInputStream): LibroAudio {
		val titulo = e.texto()
		val autor = e.texto()
		val narrador = e.texto()
		val metadatos = e.mapa()
		val avisos = e.lista()
		val pistas = List(e.readInt()) { Pista(e.texto(), e.texto(), e.readLong()) }
		val capitulos = List(e.readInt()) { MarcaAudio(e.texto(), e.readByte().toInt(), e.readInt(), e.readLong()) }
		val paginas = List(e.readInt()) { MarcaAudio(e.texto(), e.readByte().toInt(), e.readInt(), e.readLong()) }
		val frases = List(e.readInt()) { FraseAudio(e.readInt(), e.readLong(), e.readLong(), e.readInt()) }
		val texto = if (e.readBoolean()) leerDocumento(e) else null
		return LibroAudio(titulo, autor, narrador, pistas, capitulos, paginas, frases, texto, metadatos, avisos)
	}
}
