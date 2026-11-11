package com.xebolax.lector.ui

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xebolax.lector.R
import com.xebolax.lector.databinding.ItemLibroBinding
import com.xebolax.lector.datos.Carpeta
import com.xebolax.lector.datos.Estado
import com.xebolax.lector.datos.Libro
import com.xebolax.lector.datos.TipoContenido
import com.xebolax.lector.datos.tipoContenido
import com.xebolax.lector.formatos.Formato

/** Fila de la lista: una carpeta de la biblioteca, un libro o algo de Compartidos. */
sealed class Fila {
	/** Identificador estable (TalkBack no pierde el foco al refrescar la lista). */
	abstract val id: Long

	/** nombreTipo: el tipo de la carpeta, si es de uno (se dice en «Todo»). */
	data class DeCarpeta(val carpeta: Carpeta, val libros: Int, val subcarpetas: Int, val nombreTipo: String = "") : Fila() {
		override val id get() = -10L - carpeta.id
	}

	/** conTipo: se dice si es libro, documento… (en las listas que los mezclan), con nombreTipo (también los propios). */
	data class DeLibro(val libro: Libro, val rutaCarpeta: String = "", val conTipo: Boolean = false, val nombreTipo: String = "") : Fila() {
		override val id get() = libro.id
	}

	/** Algo de Compartidos: se dice qué es, de dónde vino y cuándo llegó. */
	data class DeCompartido(val libro: Libro) : Fila() {
		override val id get() = libro.id
	}

	/** "Subir a la carpeta superior". */
	data class Subir(val nombre: String) : Fila() {
		override val id get() = Long.MIN_VALUE
	}

	/** Un autor, una serie o una colección, con sus libros (se entra como en una carpeta). */
	data class Grupo(val clave: String, val nombre: String, val libros: Int) : Fila() {
		override val id get() = -1_000_000_000_000L - (clave.hashCode().toLong() and 0xffffffffL)
	}
}

/**
 * Lista de libros y carpetas. Cada fila es un solo elemento para TalkBack
 * con toda su información (título, autor, formato, progreso, estado) y sus
 * acciones en el menú de acciones; tocar abre, mantener pulsado muestra las acciones.
 *
 * Los cambios se calculan con DiffUtil e ids estables: al refrescar (un libro
 * que termina de leer sus datos, otro que se añade) solo cambian las filas
 * afectadas y el foco de TalkBack se queda donde estaba.
 */
class AdaptadorLibros(
	private val contexto: Context,
	private val alAbrir: (Fila) -> Unit,
	private val acciones: (Fila) -> List<Accion>,
) : ListAdapter<Fila, AdaptadorLibros.Vista>(Diferencias) {

	init {
		setHasStableIds(true)
	}

	private object Diferencias : DiffUtil.ItemCallback<Fila>() {
		override fun areItemsTheSame(a: Fila, b: Fila) = a.id == b.id && a::class == b::class
		override fun areContentsTheSame(a: Fila, b: Fila) = a == b
	}

	val filas: List<Fila> get() = currentList

	/** Libros seleccionados para hacer algo con todos a la vez. */
	var seleccionados: Set<Long> = emptySet()
		set(valor) {
			val cambiados = (field - valor) + (valor - field)
			field = valor
			// Solo se vuelven a pintar las filas que cambian de estado
			currentList.forEachIndexed { i, f -> if (f.id in cambiados) notifyItemChanged(i) }
		}

	fun reemplazar(lista: List<Fila>, alTerminar: () -> Unit = {}) = submitList(lista.toList(), alTerminar)

	override fun getItemId(posicion: Int) = getItem(posicion).id

	class Vista(val b: ItemLibroBinding) : RecyclerView.ViewHolder(b.root)

	override fun onCreateViewHolder(padre: ViewGroup, tipo: Int) =
		Vista(ItemLibroBinding.inflate(LayoutInflater.from(padre.context), padre, false))

	private fun formatoDe(l: Libro): String =
		// Audio suelto: la extensión dice más (M4B, MP3...)
		if (l.formato == Formato.AUDIO.name && l.nombre.contains('.')) l.nombre.substringAfterLast('.').uppercase()
		else runCatching { Formato.valueOf(l.formato).nombre }.getOrDefault(l.formato)

	private fun estadoDe(l: Libro): String = when (l.estado) {
		Estado.TERMINADO -> contexto.getString(if (l.compartido) R.string.estado_leido else R.string.estado_terminado)
		Estado.LEYENDO -> contexto.getString(R.string.porcentaje_n, l.progreso.toInt())
		Estado.QUIERO_LEER -> contexto.getString(R.string.estado_quiero_leer)
		Estado.ABANDONADO -> contexto.getString(R.string.estado_abandonado)
		else -> contexto.getString(if (l.compartido) R.string.estado_sin_leer else R.string.estado_nuevo)
	}

	override fun onBindViewHolder(v: Vista, posicion: Int) {
		val fila = getItem(posicion)
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
					fila.nombreTipo.takeIf { it.isNotBlank() },
					contexto.resources.getQuantityString(R.plurals.elementos, fila.libros, fila.libros),
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
				b.detalle.text = contexto.resources.getQuantityString(R.plurals.elementos, fila.libros, fila.libros)
				b.detalle.isVisible = true
				b.progreso.isVisible = false
				b.root.contentDescription = "${fila.nombre}, ${b.detalle.text}"
			}
			is Fila.DeLibro -> {
				val l = fila.libro
				val tipo = l.tipoContenido
				b.icono.setImageResource(tipo.icono)
				b.titulo.text = l.tituloVisible
				// La serie: «Episodios Nacionales, 3»
				val serie = l.serie.takeIf { it.isNotBlank() }?.let { s ->
					if (l.numeroSerie > 0) "$s, ${l.numeroSerie.toString().removeSuffix(".0")}" else s
				}
				val partes = listOfNotNull(
					fila.nombreTipo.ifBlank { contexto.getString(tipo.nombre) }.takeIf { fila.conTipo },
					l.autor.takeIf { it.isNotBlank() },
					serie,
					formatoDe(l).takeIf { it.isNotBlank() },
					estadoDe(l),
					contexto.getString(R.string.favorito).takeIf { l.favorito },
					fila.rutaCarpeta.takeIf { it.isNotBlank() }?.let { contexto.getString(R.string.en_carpeta, it) },
				)
				b.detalle.text = partes.joinToString(" · ")
				b.detalle.isVisible = true
				b.progreso.isVisible = l.estado == Estado.LEYENDO
				b.progreso.progress = l.progreso.toInt()
				b.root.contentDescription = (listOf(l.tituloVisible) + partes).joinToString(", ")
			}
			is Fila.DeCompartido -> {
				val l = fila.libro
				val tipo = l.tipoContenido
				b.icono.setImageResource(tipo.icono)
				b.titulo.text = l.tituloVisible
				val sitio = l.origen.takeIf { it.startsWith("http") }?.let { runCatching { android.net.Uri.parse(it).host?.removePrefix("www.") }.getOrNull() }
				val cuando = if (System.currentTimeMillis() - l.anadido < DateUtils.MINUTE_IN_MILLIS) contexto.getString(R.string.comp_ahora_mismo)
				else DateUtils.getRelativeTimeSpanString(l.anadido, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
				// El estado primero (como en Noticias): «Sin leer, título, página web, elpais.com, hace 5 minutos»
				val partes = listOfNotNull(
					contexto.getString(tipo.nombre),
					sitio,
					formatoDe(l).takeIf { tipo != TipoContenido.WEB && it.isNotBlank() },
					cuando,
				)
				b.detalle.text = (listOf(estadoDe(l)) + partes).joinToString(" · ")
				b.detalle.isVisible = true
				b.progreso.isVisible = l.estado == Estado.LEYENDO
				b.progreso.progress = l.progreso.toInt()
				b.root.contentDescription = (listOf(estadoDe(l), l.tituloVisible) + partes).joinToString(", ")
			}
		}
		val elegido = (fila is Fila.DeLibro && fila.libro.id in seleccionados) || (fila is Fila.DeCompartido && fila.libro.id in seleccionados)
		b.root.isActivated = elegido
		androidx.core.view.ViewCompat.setStateDescription(b.root, if (elegido) contexto.getString(R.string.seleccionado) else null)
		if (elegido) b.icono.setImageResource(R.drawable.ic_seleccionado)
		b.root.setOnClickListener { alAbrir(fila) }
		val lista = acciones(fila)
		b.root.accionesAccesibles(lista, when (fila) {
			is Fila.DeLibro -> ZonaAcciones.LIBRO
			is Fila.DeCarpeta -> ZonaAcciones.CARPETA
			is Fila.DeCompartido -> ZonaAcciones.COMPARTIDO
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

/** Lista sin animación de cambio: una fila que cambia se repinta en su sitio y no pierde el foco de TalkBack. */
fun RecyclerView.sinAnimarCambios() {
	(itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
}
