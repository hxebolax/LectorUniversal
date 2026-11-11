package com.xebolax.lector.voz

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.xebolax.lector.LectorApp
import com.xebolax.lector.R
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Libro
import com.xebolax.lector.lectura.Abiertos
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.ui.avisar
import kotlinx.coroutines.launch

/**
 * Lectura seguida, como las listas de Voice Dream: leer en voz alta, uno tras
 * otro y sin tocar el móvil, lo pendiente de Compartidos o los documentos de
 * una carpeta. Al terminar cada uno se dice «Siguiente: título» y empieza el
 * siguiente. Se olvida si se pone a leer otra cosa.
 */
object ListaLectura {

	private val principal = Handler(Looper.getMainLooper())
	private val pendientes = ArrayDeque<Long>()
	/** El que se está leyendo de la lista (si se lee otro libro, la lista se olvida). */
	private var actual = -1L

	val activa get() = actual > 0

	/** Empieza a leer seguidos [libros] (los de audio se saltan: tienen su reproductor). */
	fun empezar(a: AppCompatActivity, libros: List<Libro>) {
		val textos = libros.filter { !it.audio }
		if (textos.isEmpty()) return a.avisar(a.getString(R.string.seguido_nada))
		pendientes.clear()
		pendientes.addAll(textos.drop(1).map { it.id })
		a.avisar(a.resources.getQuantityString(R.plurals.seguido_empieza, textos.size, textos.size, textos.first().tituloVisible))
		leer(a.applicationContext, textos.first())
	}

	/** El siguiente libro, que empieza unos segundos después de terminar el anterior. */
	private var arranque: Runnable? = null

	fun cancelar() {
		arranque?.let { principal.removeCallbacks(it) }
		arranque = null
		pendientes.clear()
		actual = -1
	}

	/** Se pausó mientras se esperaba al siguiente: no empieza (la lista se olvida). */
	fun pararEspera() {
		if (arranque != null) cancelar()
	}

	/** Lo llama LectorVoz al cambiar de libro: si no es el de la lista, la lista se olvida. */
	fun alCargar(idLibro: Long) {
		if (idLibro != actual) cancelar()
	}

	/**
	 * Lo llama LectorVoz al terminar un libro. Si queda alguno, devuelve su
	 * título (para decir «Siguiente: …») y lo empieza a leer; si no, null.
	 */
	fun alTerminar(c: Context, idLibro: Long): String? {
		if (idLibro != actual) return null
		val db = BaseDatos.obtener(c)
		while (pendientes.isNotEmpty()) {
			val l = db.libro(pendientes.removeFirst()) ?: continue
			// Un momento para que se oiga «Siguiente: …» antes de empezar
			arranque?.let { principal.removeCallbacks(it) }
			val r = Runnable {
				arranque = null
				leer(c, l)
			}
			arranque = r
			principal.postDelayed(r, 2500)
			return l.tituloVisible
		}
		cancelar()
		return null
	}

	private fun leer(c: Context, l: Libro) {
		actual = l.id
		LectorApp.ambito.launch {
			val doc = (runCatching { Abiertos.abrir(c, l, Abiertos.opciones(c)) }.getOrNull() as? Contenido.Texto)?.documento
			principal.post {
				if (actual != l.id) return@post
				if (doc == null) {
					// No se ha podido abrir: se pasa al siguiente
					alTerminar(c, l.id)
					return@post
				}
				// Lo terminado empieza desde el principio; lo empezado, desde donde iba
				val pos = l.posicion?.takeIf { !it.audio && l.progreso < 99.5 }
				LectorVoz.leerDesde(doc, l.id, doc.titulo.ifBlank { l.tituloVisible }, pos?.bloque ?: 0, pos?.caracter ?: 0)
			}
		}
	}
}
