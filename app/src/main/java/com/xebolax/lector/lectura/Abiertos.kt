package com.xebolax.lector.lectura

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xebolax.lector.audio.ReproductorActivity
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Libro
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.formatos.ContextoLectura
import com.xebolax.lector.formatos.Formato
import com.xebolax.lector.formatos.Fuente
import com.xebolax.lector.formatos.Lectores
import com.xebolax.lector.formatos.OpcionesLectura
import com.xebolax.lector.modelo.Contenido

/**
 * El último contenido abierto se queda en memoria: pasar de la lista al
 * lector, al reproductor o volver no lo convierte otra vez.
 */
object Abiertos {

	private var uri: String? = null
	private var firma: String? = null
	private var contenido: Contenido? = null

	fun obtener(uriLibro: String, opciones: OpcionesLectura): Contenido? =
		contenido.takeIf { uri == uriLibro && firma == opciones.firma() }

	fun guardar(uriLibro: String, opciones: OpcionesLectura, c: Contenido) {
		uri = uriLibro
		firma = opciones.firma()
		contenido = c
	}

	fun olvidar() {
		uri = null
		contenido = null
	}

	/** Abre (en segundo plano) el contenido de un libro, con la caché en memoria y en disco. */
	fun abrir(contexto: Context, libro: Libro, opciones: OpcionesLectura, progreso: (Int) -> Unit = {}, cancelado: () -> Boolean = { false }): Contenido {
		obtener(libro.uri, opciones)?.let { return it }
		val fuente = Fuente(contexto, Uri.parse(libro.uri), libro.nombre)
		val c = Lectores.abrir(fuente, ContextoLectura(contexto, opciones, progreso, cancelado))
		guardar(libro.uri, opciones, c)
		// Título, autor y tipo reales la primera vez
		if (libro.titulo == libro.nombre.substringBeforeLast('.').replace('_', ' ').trim() || libro.autor.isBlank() ||
			libro.audio != (c is Contenido.Audio)
		) {
			// Una carpeta (DAISY, audiolibro) conserva el formato que se le dio al añadirla
			val formato = runCatching { Formato.reconocer(fuente) }.getOrNull()
				?.takeIf { it != Formato.CARPETA && it != Formato.ZIP }?.name ?: libro.formato
			BaseDatos.obtener(contexto).actualizarDatos(
				libro.id, c.titulo, c.autor, (c as? Contenido.Texto)?.documento?.idioma.orEmpty(), formato, c is Contenido.Audio,
			)
		}
		// La serie, si los datos del libro la traen y aún no tiene
		if (libro.serie.isBlank()) (c as? Contenido.Texto)?.documento?.metadatos?.get("Serie")?.takeIf { it.isNotBlank() }?.let {
			BaseDatos.obtener(contexto).ponerSerieDeTexto(libro.id, it)
		}
		return c
	}

	/** Abre un libro en la pantalla que le corresponde (lector de texto o reproductor). */
	fun abrirPantalla(contexto: Context, libro: Libro) {
		val destino = if (libro.audio) ReproductorActivity::class.java else LectorActivity::class.java
		contexto.startActivity(Intent(contexto, destino).putExtra(LectorActivity.EXTRA_LIBRO, libro.id))
	}

	fun abrirPantalla(contexto: Context, id: Long) {
		BaseDatos.obtener(contexto).libro(id)?.let { abrirPantalla(contexto, it) }
	}

	fun opciones(contexto: Context) = Preferencias(contexto).opcionesLectura
}
