package com.xebolax.lector.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.xebolax.lector.R
import com.xebolax.lector.databinding.ItemLibroBinding
import com.xebolax.lector.datos.Carpeta
import com.xebolax.lector.datos.Estado
import com.xebolax.lector.datos.Libro
import com.xebolax.lector.formatos.Formato

/** Fila de la lista: una carpeta de la biblioteca o un libro. */
sealed class Fila {
	class DeCarpeta(val carpeta: Carpeta, val libros: Int, val subcarpetas: Int) : Fila()
	class DeLibro(val libro: Libro, val rutaCarpeta: String = "") : Fila()
	/** "Subir a la carpeta superior". */
	class Subir(val nombre: String) : Fila()
	/** Un autor, una serie o una colección, con sus libros (se entra como en una carpeta). */
	class Grupo(val clave: String, val nombre: String, val libros: Int) : Fila()
}

/**
 * Lista de libros y carpetas. Cada fila es un solo elemento para TalkBack
 * con toda su información (título, autor, formato, progreso, estado) y sus
 * acciones en el menú de acciones; tocar abre, mantener pulsado muestra las acciones.
 */
class AdaptadorLibros(
	private val contexto: Context,
	private val alAbrir: (Fila) -> Unit,
	private val acciones: (Fila) -> List<Accion>,
) : RecyclerView.Adapter<AdaptadorLibros.Vista>() {

	val filas = mutableListOf<Fila>()
	/** Libros seleccionados para hacer algo con todos a la vez. */
	var seleccionados: Set<Long> = emptySet()

	fun reemplazar(lista: List<Fila>) {
		filas.clear()
		filas.addAll(lista)
		@Suppress("NotifyDataSetChanged")
		notifyDataSetChanged()
	}

	class Vista(val b: ItemLibroBinding) : RecyclerView.ViewHolder(b.root)

	override fun getItemCount() = filas.size

	override fun onCreateViewHolder(padre: ViewGroup, tipo: Int) =
		Vista(ItemLibroBinding.inflate(LayoutInflater.from(padre.context), padre, false))

	override fun onBindViewHolder(v: Vista, posicion: Int) {
		val fila = filas[posicion]
		val b = v.b
		when (fila) {
			is Fila.Subir -> {
				b.icono.setImageResource(R.drawable.ic_subir)
				b.titulo.text = contexto.getString(R.string.subir_a, fila.nombre)
				b.detalle.isVisible = false
				b.progreso.isVisible = false
				b.root.contentDescription = b.titulo.text
			}
			is Fila.DeCarpeta -> {
				b.icono.setImageResource(R.drawable.ic_carpeta)
				b.titulo.text = fila.carpeta.nombre
				val detalle = listOfNotNull(
					contexto.resources.getQuantityString(R.plurals.libros, fila.libros, fila.libros),
					fila.subcarpetas.takeIf { it > 0 }?.let { contexto.resources.getQuantityString(R.plurals.subcarpetas, it, it) },
				).joinToString(", ")
				b.detalle.text = detalle
				b.detalle.isVisible = true
				b.progreso.isVisible = false
				b.root.contentDescription = contexto.getString(R.string.carpeta_x, fila.carpeta.nombre) + ", " + detalle
			}
			is Fila.Grupo -> {
				b.icono.setImageResource(R.drawable.ic_lista)
				b.titulo.text = fila.nombre
				b.detalle.text = contexto.resources.getQuantityString(R.plurals.libros, fila.libros, fila.libros)
				b.detalle.isVisible = true
				b.progreso.isVisible = false
				b.root.contentDescription = "${fila.nombre}, ${b.detalle.text}"
			}
			is Fila.DeLibro -> {
				val l = fila.libro
				b.icono.setImageResource(if (l.audio) R.drawable.ic_voz else R.drawable.ic_libro)
				b.titulo.text = l.tituloVisible
				// Audio suelto: la extensión dice más (M4B, MP3...)
				val formato = if (l.formato == Formato.AUDIO.name && l.nombre.contains('.')) l.nombre.substringAfterLast('.').uppercase()
				else runCatching { Formato.valueOf(l.formato).nombre }.getOrDefault(l.formato)
				val estado = when (l.estado) {
					Estado.TERMINADO -> contexto.getString(R.string.estado_terminado)
					Estado.LEYENDO -> contexto.getString(R.string.porcentaje_n, l.progreso.toInt())
					Estado.QUIERO_LEER -> contexto.getString(R.string.estado_quiero_leer)
					Estado.ABANDONADO -> contexto.getString(R.string.estado_abandonado)
					else -> contexto.getString(R.string.estado_nuevo)
				}
				// La serie: «Episodios Nacionales, 3»
				val serie = l.serie.takeIf { it.isNotBlank() }?.let { s ->
					if (l.numeroSerie > 0) "$s, ${l.numeroSerie.toString().removeSuffix(".0")}" else s
				}
				val partes = listOfNotNull(
					l.autor.takeIf { it.isNotBlank() },
					serie,
					formato.takeIf { it.isNotBlank() },
					estado,
					contexto.getString(R.string.favorito).takeIf { l.favorito },
					fila.rutaCarpeta.takeIf { it.isNotBlank() }?.let { contexto.getString(R.string.en_carpeta, it) },
				)
				b.detalle.text = partes.joinToString(" · ")
				b.detalle.isVisible = true
				b.progreso.isVisible = l.estado == Estado.LEYENDO
				b.progreso.progress = l.progreso.toInt()
				b.root.contentDescription = (listOf(l.tituloVisible) + partes).joinToString(", ")
			}
		}
		val elegido = fila is Fila.DeLibro && fila.libro.id in seleccionados
		b.root.isActivated = elegido
		androidx.core.view.ViewCompat.setStateDescription(b.root, if (elegido) contexto.getString(R.string.seleccionado) else null)
		if (elegido) b.icono.setImageResource(R.drawable.ic_seleccionado)
		b.root.setOnClickListener { alAbrir(fila) }
		val lista = acciones(fila)
		b.root.accionesAccesibles(lista, when (fila) {
			is Fila.DeLibro -> ZonaAcciones.LIBRO
			is Fila.DeCarpeta -> ZonaAcciones.CARPETA
			else -> null
		})
		b.root.setOnLongClickListener {
			if (lista.isEmpty()) false else {
				contexto.elegir(b.titulo.text.toString(), lista)
				true
			}
		}
	}
}
