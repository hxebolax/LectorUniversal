package com.xebolax.lector.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.xebolax.lector.R
import com.xebolax.lector.actualizaciones.GestorActualizaciones
import com.xebolax.lector.audio.ControlAudio
import com.xebolax.lector.databinding.ActivityPortadaBinding
import com.xebolax.lector.databinding.ItemLibroBinding
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Estado
import com.xebolax.lector.datos.Libro
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.datos.claveTipo
import com.xebolax.lector.datos.tipoContenido
import com.xebolax.lector.lectura.Abiertos
import com.xebolax.lector.podcast.Episodio
import com.xebolax.lector.podcast.Podcasts
import com.xebolax.lector.podcast.PodcastsBaseDatos
import com.xebolax.lector.radio.Emisora
import com.xebolax.lector.radio.Radio
import com.xebolax.lector.radio.RadioAcciones
import com.xebolax.lector.radio.RadioBaseDatos
import com.xebolax.lector.rss.Articulo
import com.xebolax.lector.rss.RssAcciones
import com.xebolax.lector.rss.RssBaseDatos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Portada: la pantalla de inicio de la app. Lo más a mano de cada sección, en
 * el orden de Ajustes (Portada y secciones): los libros que estás leyendo, los
 * episodios a medias, en la cola o nuevos, las últimas noticias sin leer y tus
 * emisoras favoritas.
 *
 * Cada sección empieza con un encabezado (TalkBack salta de uno a otro con la
 * navegación por encabezados) que abre la sección entera; cada elemento es una
 * sola parada con un doble toque para lo principal (abrir, escuchar) y el resto
 * en las acciones de TalkBack, como en su sección. Es el destino de inicio: Atrás
 * desde la pantalla principal de una sección vuelve aquí y, desde aquí, sale.
 */
class PortadaActivity : AppCompatActivity() {

	private lateinit var b: ActivityPortadaBinding
	val actualizaciones = GestorActualizaciones(this)
	private val permisoNotificaciones = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
	private var filas: List<Fila> = emptyList()
	private var carga: Job? = null

	/** Lo que se lee de las bases de datos (en segundo plano). */
	private class Datos(
		val libros: List<Libro>, val totalLibros: Int,
		val compartidos: List<Libro>, val pendientes: Int,
		val episodios: List<Episodio>, val totalPodcasts: Int, val novedades: Int,
		val articulos: List<Articulo>, val sinLeer: Int,
		val emisoras: List<Emisora>, val favoritas: Boolean,
	)

	private sealed class Fila(val id: Long) {
		class Encabezado(val seccion: Seccion, val resumen: String) : Fila(seccion.ordinal.toLong() - 100)
		class Vacia(val seccion: Seccion, val texto: String) : Fila(seccion.ordinal.toLong() - 200)
		class Elemento(
			clave: String,
			val icono: Int,
			val titulo: String,
			val detalle: String,
			val progreso: Int?,
			val alPulsar: () -> Unit,
			val etiqueta: String,
			val acciones: () -> List<Accion>,
			val zona: ZonaAcciones?,
		) : Fila(clave.hashCode().toLong())
	}

	private val oyenteAudio = ControlAudio.Oyente { b.recycler.post { if (OrdenSecciones.enPortada(this, Seccion.RADIO)) cargar() } }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityPortadaBinding.inflate(layoutInflater)
		setSupportActionBar(b.barra)
		setContentView(ponerMenuLateral(b.root, b.barra, Seccion.PORTADA, actualizaciones) { b.recycler.scrollToPosition(0) })
		title = getString(R.string.app_name)
		ViewCompat.setAccessibilityPaneTitle(b.recycler, getString(R.string.portada))
		seguirMiniReproductor(b.miniReproductor)
		b.recycler.layoutManager = LinearLayoutManager(this)
		b.recycler.sinFocoPropio()
		b.recycler.adapter = Adaptador().also { it.setHasStableIds(true) }

		if (savedInstanceState == null) {
			actualizaciones.alIniciar()
			TareasInicio.alAbrir(this)
			if (Build.VERSION.SDK_INT >= 33) permisoNotificaciones.launch(android.Manifest.permission.POST_NOTIFICATIONS)
			// Abrir el último libro al iniciar (Ajustes)
			if (Preferencias(this).abrirUltimo && intent?.action == Intent.ACTION_MAIN) {
				BaseDatos.obtener(this).ultimoAbierto()?.let { Abiertos.abrirPantalla(this, it) }
			}
		}
	}

	override fun onStart() {
		super.onStart()
		ControlAudio.registrar(oyenteAudio)
		// Sincronizar con el servidor (si está configurado): trae lo leído en otros dispositivos
		com.xebolax.lector.LectorApp.ambito.launch { com.xebolax.lector.sincronizacion.Sincronizador.siToca(applicationContext, 60_000L) }
	}

	override fun onStop() {
		ControlAudio.desregistrar(oyenteAudio)
		super.onStop()
	}

	override fun onResume() {
		super.onResume()
		cargar()
	}

	// --- Datos ---------------------------------------------------------------------------------

	private fun cargar() {
		carga?.cancel()
		carga = lifecycleScope.launch {
			val n = OrdenSecciones.elementos(this@PortadaActivity)
			val secciones = OrdenSecciones.orden(this@PortadaActivity).filter { OrdenSecciones.enPortada(this@PortadaActivity, it) }
			val datos = withContext(Dispatchers.IO) { leer(secciones.toSet(), n) }
			filas = construir(secciones, datos)
			b.recycler.adapter?.notifyDataSetChanged()
		}
	}

	private fun leer(secciones: Set<Seccion>, n: Int): Datos {
		val c = applicationContext
		var libros = emptyList<Libro>()
		var totalLibros = 0
		if (Seccion.BIBLIOTECA in secciones) {
			val db = BaseDatos.obtener(c)
			libros = db.recientes(n * 2).filter { it.estado != Estado.TERMINADO }.take(n)
			totalLibros = db.contarLibros()
		}
		var compartidos = emptyList<Libro>()
		var pendientes = 0
		if (Seccion.COMPARTIDOS in secciones) {
			val db = BaseDatos.obtener(c)
			compartidos = db.compartidos().filter { it.estado != Estado.TERMINADO }
			pendientes = compartidos.size
			compartidos = compartidos.take(n)
		}
		var episodios = emptyList<Episodio>()
		var totalPodcasts = 0
		var novedades = 0
		if (Seccion.PODCASTS in secciones) {
			val db = PodcastsBaseDatos.obtener(c)
			// Lo que dejaste a medias, después la cola y, al final, lo nuevo
			episodios = (db.enCurso() + db.cola() + db.novedades(n)).distinctBy { it.id }.take(n)
			totalPodcasts = db.contarPodcasts()
			novedades = db.contarNovedades()
		}
		var articulos = emptyList<Articulo>()
		var sinLeer = 0
		if (Seccion.NOTICIAS in secciones) {
			val db = RssBaseDatos.obtener(c)
			articulos = db.sinLeer(limite = n)
			sinLeer = db.contarSinLeer()
		}
		var emisoras = emptyList<Emisora>()
		var favoritas = false
		if (Seccion.RADIO in secciones) {
			val db = RadioBaseDatos.obtener(c)
			emisoras = db.favoritas().map { it.emisora }.take(n)
			favoritas = emisoras.isNotEmpty()
			// Sin favoritas, las últimas que escuchaste
			if (!favoritas) emisoras = db.historial().map { it.emisora }.take(n)
		}
		return Datos(libros, totalLibros, compartidos, pendientes, episodios, totalPodcasts, novedades, articulos, sinLeer, emisoras, favoritas)
	}

	private fun construir(secciones: List<Seccion>, d: Datos): List<Fila> {
		val r = mutableListOf<Fila>()
		for (s in secciones) when (s) {
			Seccion.BIBLIOTECA -> {
				r += Fila.Encabezado(s, if (d.libros.isEmpty()) resources.getQuantityString(R.plurals.libros, d.totalLibros, d.totalLibros)
					else getString(R.string.portada_seguir_leyendo))
				if (d.libros.isEmpty()) r += Fila.Vacia(s, getString(if (d.totalLibros == 0) R.string.portada_biblioteca_vacia else R.string.portada_nada_leyendo))
				d.libros.forEach { r += filaLibro(it) }
			}
			Seccion.COMPARTIDOS -> {
				r += Fila.Encabezado(s, resources.getQuantityString(R.plurals.comp_pendientes, d.pendientes, d.pendientes))
				if (d.compartidos.isEmpty()) r += Fila.Vacia(s, getString(R.string.portada_compartidos_vacio))
				d.compartidos.forEach { r += filaCompartido(it) }
			}
			Seccion.PODCASTS -> {
				r += Fila.Encabezado(s, when {
					d.totalPodcasts == 0 -> getString(R.string.portada_sin_podcasts_corto)
					d.novedades > 0 -> resources.getQuantityString(R.plurals.portada_episodios_nuevos, d.novedades, d.novedades)
					else -> getString(R.string.portada_seguir_escuchando)
				})
				if (d.episodios.isEmpty()) r += Fila.Vacia(s, getString(if (d.totalPodcasts == 0) R.string.portada_sin_podcasts else R.string.portada_nada_pendiente))
				d.episodios.forEach { r += filaEpisodio(it, d.episodios) }
			}
			Seccion.NOTICIAS -> {
				r += Fila.Encabezado(s, resources.getQuantityString(R.plurals.portada_sin_leer, d.sinLeer, d.sinLeer))
				if (d.articulos.isEmpty()) r += Fila.Vacia(s, getString(R.string.portada_noticias_al_dia))
				val ids = d.articulos.map { it.id }.toLongArray()
				d.articulos.forEach { r += filaArticulo(it, ids) }
			}
			Seccion.RADIO -> {
				r += Fila.Encabezado(s, getString(if (d.favoritas) R.string.portada_tus_favoritas else R.string.portada_ultimas_escuchadas))
				if (d.emisoras.isEmpty()) r += Fila.Vacia(s, getString(R.string.portada_sin_emisoras))
				d.emisoras.forEach { r += filaEmisora(it, d.emisoras) }
			}
			Seccion.PORTADA -> Unit
		}
		if (secciones.isEmpty()) r += Fila.Vacia(Seccion.PORTADA, getString(R.string.portada_vacia))
		return r
	}

	private fun filaLibro(l: Libro): Fila {
		val partes = listOfNotNull(l.autor.takeIf { it.isNotBlank() }, getString(R.string.porcentaje_n, l.progreso.toInt()))
		return Fila.Elemento("libro${l.id}", l.tipoContenido.icono, l.tituloVisible, partes.joinToString(" · "),
			l.progreso.toInt(), { Abiertos.abrirPantalla(this, l) }, getString(R.string.abrir), { AccionesLibro.acciones(this, l) }, ZonaAcciones.LIBRO)
	}

	private fun filaCompartido(l: Libro): Fila {
		val tipo = com.xebolax.lector.datos.Tipos.de(this, l.claveTipo)?.nombreSingular.orEmpty()
		val sitio = l.origen.takeIf { it.startsWith("http") }?.let { runCatching { android.net.Uri.parse(it).host?.removePrefix("www.") }.getOrNull() }
		val partes = listOfNotNull(tipo.takeIf { it.isNotBlank() }, sitio, if (l.progreso > 0) getString(R.string.porcentaje_n, l.progreso.toInt()) else null)
		return Fila.Elemento("compartido${l.id}", R.drawable.ic_compartido, l.tituloVisible, partes.joinToString(" · "),
			l.progreso.toInt(), { Abiertos.abrirPantalla(this, l) }, getString(R.string.abrir), { AccionesCompartido.acciones(this, l) }, ZonaAcciones.COMPARTIDO)
	}

	private fun filaEpisodio(e: Episodio, lista: List<Episodio>): Fila {
		val estado = when {
			e.posicion > 0 && e.duracion > e.posicion -> getString(R.string.portada_quedan, tiempoHablado(e.duracion - e.posicion, conSegundos = false))
			e.enCola -> getString(R.string.portada_en_cola)
			e.nuevo -> getString(R.string.portada_nuevo)
			else -> null
		}
		val detalle = listOfNotNull(e.nombrePodcast.takeIf { it.isNotBlank() }, estado).joinToString(" · ")
		val progreso = if (e.posicion > 0 && e.duracion > 0) (e.posicion * 100 / e.duracion).toInt() else null
		return Fila.Elemento("episodio${e.id}", R.drawable.ic_voz, e.titulo, detalle, progreso,
			{ lifecycleScope.launch { Podcasts.escuchar(this@PortadaActivity, e) } }, getString(R.string.pod_escuchar), { accionesEpisodio(e) }, ZonaAcciones.EPISODIO)
	}

	private fun accionesEpisodio(e: Episodio): List<Accion> {
		val db = PodcastsBaseDatos.obtener(this)
		val r = mutableListOf<Accion>(getString(R.string.pod_escuchar) to { lifecycleScope.launch { Podcasts.escuchar(this@PortadaActivity, e) } })
		r += if (e.enCola) getString(R.string.pod_quitar_cola) to { db.quitarDeCola(listOf(e.id)); avisar(getString(R.string.pod_quitado_cola)); cargar() }
		else getString(R.string.pod_anadir_cola) to { db.anadirACola(listOf(e.id)); avisar(getString(R.string.pod_anadido_cola)); cargar() }
		r += getString(R.string.pod_marcar_escuchado) to { db.marcarEscuchados(listOf(e.id), true); avisar(getString(R.string.pod_marcado_escuchado)); cargar() }
		if (e.nuevo) r += getString(R.string.pod_quitar_novedades) to { db.quitarDeNovedades(listOf(e.id)); avisar(getString(R.string.hecho)); cargar() }
		return r
	}

	private fun filaArticulo(a: Articulo, ids: LongArray): Fila {
		val detalle = listOfNotNull(a.nombreFuente.takeIf { it.isNotBlank() }, a.fecha.takeIf { it > 0 }?.let { RadioAcciones.cuando(it) }).joinToString(" · ")
		return Fila.Elemento("articulo${a.id}", R.drawable.ic_rss, a.titulo, detalle, null, { RssAcciones.abrir(this, a, ids) },
			getString(R.string.abrir), { RssAcciones.acciones(this, a, { ids }) { cargar() } }, ZonaAcciones.ARTICULO)
	}

	private fun filaEmisora(e: Emisora, lista: List<Emisora>): Fila {
		val detalle = if (Radio.suena(e)) getString(R.string.radio_sonando)
		else listOf(e.paisLegible(), e.generos(2)).filter { it.isNotBlank() }.joinToString(" · ")
		return Fila.Elemento("emisora${e.uuid}", R.drawable.ic_radio, e.nombre, detalle, null, { Radio.escuchar(this, e, lista) },
			getString(R.string.radio_escuchar), { RadioAcciones.deEmisora(this, e, lista) }, ZonaAcciones.EMISORA)
	}

	// --- Lista ---------------------------------------------------------------------------------

	private class VistaEncabezado(val raiz: LinearLayout, val titulo: TextView, val resumen: TextView) : RecyclerView.ViewHolder(raiz)
	private class VistaVacia(val texto: TextView) : RecyclerView.ViewHolder(texto)
	private class VistaElemento(val b: ItemLibroBinding) : RecyclerView.ViewHolder(b.root)

	private inner class Adaptador : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
		override fun getItemCount() = filas.size
		override fun getItemId(posicion: Int) = filas[posicion].id
		override fun getItemViewType(posicion: Int) = when (filas[posicion]) {
			is Fila.Encabezado -> 0
			is Fila.Vacia -> 1
			is Fila.Elemento -> 2
		}

		override fun onCreateViewHolder(padre: ViewGroup, tipo: Int): RecyclerView.ViewHolder = when (tipo) {
			0 -> nuevoEncabezado(padre)
			1 -> VistaVacia(TextView(padre.context).apply {
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
				setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
				minHeight = dp(context, 48)
				gravity = Gravity.CENTER_VERTICAL
				setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 8))
				isFocusable = true
				layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			})
			else -> VistaElemento(ItemLibroBinding.inflate(LayoutInflater.from(padre.context), padre, false))
		}

		override fun onBindViewHolder(v: RecyclerView.ViewHolder, posicion: Int) {
			when (val f = filas[posicion]) {
				is Fila.Encabezado -> (v as VistaEncabezado).let { h ->
					val nombre = getString(f.seccion.nombre)
					h.titulo.text = nombre
					h.resumen.text = f.resumen
					// «Podcasts, 3 episodios nuevos, encabezado. Toca dos veces para ver todo en Podcasts»
					h.raiz.contentDescription = "$nombre, ${f.resumen}"
					h.raiz.setOnClickListener { abrirSeccion(f.seccion) }
					h.raiz.etiquetaPulsar(getString(R.string.portada_ver_todo_en, nombre))
				}
				is Fila.Vacia -> (v as VistaVacia).texto.text = f.texto
				is Fila.Elemento -> (v as VistaElemento).b.let { e ->
					e.icono.setImageResource(f.icono)
					e.titulo.text = f.titulo
					e.detalle.text = f.detalle
					e.detalle.isVisible = f.detalle.isNotBlank()
					e.progreso.isVisible = f.progreso != null && f.progreso > 0
					e.progreso.progress = f.progreso ?: 0
					e.root.contentDescription = listOf(f.titulo, f.detalle).filter { it.isNotBlank() }.joinToString(", ")
					e.root.setOnClickListener { f.alPulsar() }
					e.root.etiquetaPulsar(f.etiqueta)
					val acciones = f.acciones()
					e.root.accionesAccesibles(acciones, f.zona)
					e.root.setOnLongClickListener {
						elegir(f.titulo, acciones)
						true
					}
				}
			}
		}
	}

	/** Encabezado de una sección: su nombre y un resumen a la izquierda, «Ver todo» a la derecha. */
	private fun nuevoEncabezado(padre: ViewGroup): VistaEncabezado {
		val c = padre.context
		val raiz = LinearLayout(c).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			minimumHeight = dp(c, 56)
			setPadding(dp(c, 16), dp(c, 16), dp(c, 8), dp(c, 4))
			isFocusable = true
			isClickable = true
			val fondo = android.util.TypedValue()
			c.theme.resolveAttribute(android.R.attr.selectableItemBackground, fondo, true)
			setBackgroundResource(fondo.resourceId)
			layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			ViewCompat.setAccessibilityHeading(this, true)
		}
		val textos = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
		val titulo = TextView(c).apply {
			setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
			setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
			importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
		}
		val resumen = TextView(c).apply {
			setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
			setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
			importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
		}
		textos.addView(titulo)
		textos.addView(resumen)
		raiz.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		raiz.addView(TextView(c).apply {
			text = getString(R.string.portada_ver_todo)
			setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
			setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
			importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
		})
		raiz.addView(ImageView(c).apply {
			setImageResource(R.drawable.ic_flecha_adelante)
			importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
		}, LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)))
		return VistaEncabezado(raiz, titulo, resumen)
	}
}
