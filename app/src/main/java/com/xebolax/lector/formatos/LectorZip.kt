package com.xebolax.lector.formatos

import android.net.Uri
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.Textos
import org.jsoup.Jsoup
import java.io.File

/**
 * ZIP genérico:
 * - con un solo libro dentro (EPUB, PDF, DOCX...): se abre ese libro;
 * - con audio: audiolibro (se extrae a la carpeta de la app);
 * - con varios HTML o TXT: un documento con un capítulo por archivo.
 */
object LectorZip : LectorFormato {

	override fun leer(fuente: Fuente, ctx: ContextoLectura): Contenido {
		val zip = fuente.contenedor() as? ContenedorZip ?: throw ExcepcionFormato("No es un ZIP válido.")
		zip.use { z ->
			val entradas = z.entradas().filterNot { it.startsWith("__MACOSX") || it.substringAfterLast('/').startsWith(".") }
			val formatos = entradas.associateWith { Formato.deExtension(Fuente.extensionDe(it)) }
			val libros = formatos.filterValues { it != Formato.DESCONOCIDO && it != Formato.TXT && it != Formato.HTML && !it.audio && it != Formato.ZIP }
			val audio = formatos.filterValues { it == Formato.AUDIO }
			val textos = formatos.filterValues { it == Formato.TXT || it == Formato.HTML }.keys.sortedWith(OrdenNatural)

			if (libros.size == 1) {
				val (ruta, formato) = libros.entries.first()
				// Con el tamaño del ZIP en la clave: si el archivo cambia, se vuelve a sacar
				val destino = File(ctx.app.cacheDir, "fuentes/zip_" + Fuente.hash(fuente.uri.toString() + ":" + fuente.tamano + ":" + ruta) + "." + Fuente.extensionDe(ruta))
				destino.parentFile?.mkdirs()
				val tamano = z.tamano(ruta)
				if (!destino.exists() || (tamano >= 0 && destino.length() != tamano)) {
					// Primero a un temporal y después se renombra: una copia cortada no se queda como buena
					val parte = File(destino.path + "." + Thread.currentThread().id + ".parte")
					try {
						z.abrir(ruta)?.use { e -> parte.outputStream().use { e.copyTo(it) } } ?: throw ExcepcionFormato("No se puede leer $ruta dentro del ZIP.")
						if (!parte.renameTo(destino) && !destino.exists()) throw ExcepcionFormato("No se puede leer $ruta dentro del ZIP.")
					} finally {
						parte.delete()
					}
				}
				val interior = Fuente(ctx.app, Uri.fromFile(destino), ruta.substringAfterLast('/'))
				return (Lectores.lectorDe(formato) ?: LectorTexto).leer(interior, ctx)
			}
			if (audio.isNotEmpty() && textos.size <= 2) {
				// La misma carpeta de siempre, pero sin restos de una extracción cortada ni dos hilos a la vez
				val carpeta = LectorDaisy.extraerZip(fuente, ctx, "audiolibros")
				return LectorCarpeta.leer(Fuente(ctx.app, Uri.fromFile(carpeta), fuente.nombre.substringBeforeLast('.')), ctx)
			}
			if (textos.isEmpty()) throw ExcepcionFormato("El ZIP no contiene ningún documento que se pueda leer.")
			val c = ctx.nuevoConstructor()
			c.titulo = fuente.nombre.substringBeforeLast('.')
			for ((i, ruta) in textos.withIndex()) {
				ctx.comprobar()
				ctx.progreso(i * 100 / textos.size)
				c.marcarSiguiente(Marca.INICIO_SECCION)
				c.ancla(ruta)
				val bytes = z.bytes(ruta) ?: continue
				if (formatos[ruta] == Formato.HTML) {
					ConversorHtml(c, ruta, ctx.opciones).convertir(Jsoup.parse(Textos.decodificar(bytes, "utf-8")).body())
				} else {
					if (textos.size > 1) c.encabezado(ruta.substringAfterLast('/').substringBeforeLast('.'), 1)
					LectorTexto.texto(c, Textos.decodificar(bytes), ctx)
				}
			}
			return Contenido.Texto(c.construir())
		}
	}
}

/** Orden natural: "Capítulo 2" antes que "Capítulo 10", sin distinguir mayúsculas. */
object OrdenNatural : Comparator<String> {
	private val TROZOS = Regex("\\d+|\\D+")

	override fun compare(a: String, b: String): Int {
		val x = TROZOS.findAll(a.lowercase()).map { it.value }.toList()
		val y = TROZOS.findAll(b.lowercase()).map { it.value }.toList()
		for (i in 0 until minOf(x.size, y.size)) {
			val p = x[i]
			val q = y[i]
			val r = if (p[0].isDigit() && q[0].isDigit()) {
				val n = p.trimStart('0').length.compareTo(q.trimStart('0').length)
				if (n != 0) n else p.trimStart('0').compareTo(q.trimStart('0'))
			} else p.compareTo(q)
			if (r != 0) return r
		}
		return x.size.compareTo(y.size)
	}
}
