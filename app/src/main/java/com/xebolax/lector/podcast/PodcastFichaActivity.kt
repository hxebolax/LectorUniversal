package com.xebolax.lector.podcast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.xebolax.lector.R
import com.xebolax.lector.databinding.ActivityListaBinding
import com.xebolax.lector.databinding.ItemListaBinding
import com.xebolax.lector.rss.Rss
import com.xebolax.lector.ui.elegirCarpeta
import com.xebolax.lector.ui.ZonaAcciones
import com.xebolax.lector.ui.Accion
import com.xebolax.lector.ui.accionesAccesibles
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.conEspera
import com.xebolax.lector.ui.configurarBarra
import com.xebolax.lector.ui.dp
import com.xebolax.lector.ui.sinFocoPropio
import com.xebolax.lector.ui.elegir
import com.xebolax.lector.ui.mensaje
import com.xebolax.lector.ui.tiempoHablado
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Ficha de un podcast antes de suscribirse (desde Buscar o Descubrir), como
 * en AntennaPod: toda su información, su descripción y todos sus episodios,
 * que se pueden escuchar sin suscribirse. Arriba, Suscribirse (o ir a él si
 * ya lo estás), su web, compartir y apoyarlo.
 */
class PodcastFichaActivity : AppCompatActivity() {

	companion object {
		private const val EXTRA_URL = "url"
		private const val EXTRA_ID_APPLE = "id_apple"
		private const val EXTRA_TITULO = "titulo"
		private const val EXTRA_AUTOR = "autor"

		fun abrir(c: Context, s: Podcasts.Sugerencia) = c.startActivity(Intent(c, PodcastFichaActivity::class.java)
			.putExtra(EXTRA_URL, s.url).putExtra(EXTRA_ID_APPLE, s.id).putExtra(EXTRA_TITULO, s.titulo).putExtra(EXTRA_AUTOR, s.autor))

		fun abrir(c: Context, url: String, titulo: String = "") = c.startActivity(Intent(c, PodcastFichaActivity::class.java)
			.putExtra(EXTRA_URL, url).putExtra(EXTRA_TITULO, titulo))
	}

	private sealed class Fila {
		class Info(val id: Long, val titulo: String, val texto: String) : Fila()
		class DeEpisodio(val ep: Ep) : Fila() {
			val e get() = ep.e
		}
	}

	/** Un episodio con su descripción en texto plano (para el filtro) y un id fijo (para no perder el foco al filtrar). */
	private class Ep(val e: PodcastLector.Elemento, val texto: String, val id: Long)

	private lateinit var b: ActivityListaBinding
	private val cabecera by lazy { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
	private var url = ""
	private var canal: PodcastLector.Canal? = null
	private var filas: List<Fila> = emptyList()
	private var filtro = ""
	/** Los episodios en su orden, preparados al cargar (fuera del hilo principal). */
	private var eps: List<Ep> = emptyList()
	private var filtrando: kotlinx.coroutines.Job? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityListaBinding.inflate(layoutInflater)
		setContentView(b.root)
		val titulo = intent.getStringExtra(EXTRA_TITULO).orEmpty().ifBlank { getString(R.string.podcasts) }
		configurarBarra(b.barra, titulo)
		// Los botones van debajo de la barra, antes del filtro
		val m = dp(this, 12)
		cabecera.setPadding(m, 0, m, 0)
		b.root.addView(cabecera, 1)
		b.filtroContenedor.hint = getString(R.string.pod_buscar_episodios)
		b.filtroContenedor.isVisible = false
		b.filtro.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
			override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
			override fun afterTextChanged(s: Editable?) {
				filtro = s?.toString().orEmpty().trim()
				// Se espera a que deje de escribir un momento (con muchos episodios, filtrar en cada tecla da tirones)
				filtrando?.cancel()
				filtrando = lifecycleScope.launch {
					kotlinx.coroutines.delay(300)
					mostrar()
				}
			}
		})
		b.recycler.layoutManager = LinearLayoutManager(this)
		b.recycler.sinFocoPropio()
		b.recycler.adapter = Adaptador()
		cargar()
	}

	override fun onResume() {
		super.onResume()
		// Al volver (por ejemplo tras suscribirse desde otra pantalla), el botón dice si ya lo estás.
		// Solo cambia su texto: rehacer la cabecera haría perder el foco (pauta 34)
		if (canal != null) ponerSuscripcion()
	}

	private fun cargar() {
		b.estado.text = getString(R.string.cargando)
		avisar(getString(R.string.cargando), mostrar = false)
		lifecycleScope.launch {
			try {
				url = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank {
					val id = intent.getLongExtra(EXTRA_ID_APPLE, 0)
					if (id > 0) Podcasts.direccionDe(id).orEmpty() else ""
				}
				if (url.isBlank()) error(getString(R.string.pod_sin_rss))
				val c = withContext(Dispatchers.IO) { PodcastLector.descargar(url).canal } ?: error(getString(R.string.pod_sin_rss))
				// El texto plano de cada descripción, una sola vez y fuera del hilo principal
				eps = withContext(Dispatchers.Default) {
					episodios(c).mapIndexed { i, e -> Ep(e, runCatching { Jsoup.parse(e.descripcion).text() }.getOrDefault(e.descripcion), i.toLong()) }
				}
				canal = c
				if (c.titulo.isNotBlank()) {
					supportActionBar?.title = c.titulo
					title = c.titulo
				}
				construirCabecera()
				b.filtroContenedor.isVisible = c.elementos.size > 10
				mostrar()
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				b.estado.text = getString(R.string.error_catalogo, e.message.orEmpty())
				avisar(getString(R.string.error_catalogo, e.message.orEmpty()))
			}
		}
	}

	// --- Cabecera ------------------------------------------------------------------------

	private fun boton(texto: String, accion: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
		text = texto
		minHeight = dp(this@PodcastFichaActivity, 48)
		setOnClickListener { accion() }
	}

	private fun construirCabecera() {
		val c = canal ?: return
		cabecera.removeAllViews()
		val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
		fun anadir(v: android.view.View) = fila.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		anadir(boton("") {}.also { botonSuscripcion = it })
		ponerSuscripcion()
		if (c.sitio.isNotBlank()) anadir(boton(getString(R.string.rss_web_corto)) { navegador(c.sitio) })
		c.trailer?.let { (titulo, audio) ->
			anadir(boton(getString(R.string.pod_trailer)) {
				val e = PodcastLector.Elemento("trailer", titulo.ifBlank { getString(R.string.pod_trailer) }, "", 0, audio, "", 0, 0, "", 0, 0, "", "", "")
				lifecycleScope.launch { Podcasts.escucharSinSuscribir(this@PodcastFichaActivity, c.titulo, e) }
			})
		}
		anadir(boton(getString(R.string.compartir)) { compartir(c.titulo, url) })
		if (c.apoyo.isNotEmpty()) anadir(boton(getString(R.string.pod_apoyar)) {
			elegir(getString(R.string.pod_apoyar), c.apoyo.map { (texto, direccion) -> texto.ifBlank { Uri.parse(direccion).host.orEmpty() } to { navegador(direccion) } })
		})
		cabecera.addView(fila)
	}

	/** El botón de suscribirse, que pasa a «Ver en Suscripciones» al estar suscrito. */
	private var botonSuscripcion: MaterialButton? = null

	private fun ponerSuscripcion() {
		val boton = botonSuscripcion ?: return
		val suscrito = PodcastsBaseDatos.obtener(this).podcastPorUrl(url)
		boton.text = getString(if (suscrito == null) R.string.pod_suscribirse else R.string.pod_ver_suscrito)
		boton.setOnClickListener { if (suscrito == null) suscribir() else verSuscrito(suscrito.id) }
	}

	private fun suscribir() {
		val c = canal ?: return
		lifecycleScope.launch {
			// Ventana de espera hasta que la suscripción está hecha
			try {
				val id = conEspera(getString(R.string.pod_suscribirse), getString(R.string.pod_suscribiendo)) { Podcasts.suscribir(this@PodcastFichaActivity, url, c.titulo, c.autor) }
				avisar(getString(R.string.rss_suscrito, c.titulo))
				// Si hay carpetas, se ofrece ponerlo en una
				val db = PodcastsBaseDatos.obtener(this@PodcastFichaActivity)
				if (db.carpetas().isNotEmpty()) elegirCarpeta(getString(R.string.en_que_carpeta, c.titulo), db.carpetas(), "") { destino ->
					if (destino.isNotBlank()) {
						db.ponerCarpeta(id, destino)
						avisar(getString(R.string.movida_a_carpeta, destino))
					}
				}
				ponerSuscripcion()
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				mensaje(getString(R.string.pod_suscribirse), getString(R.string.pod_no_encontrado, e.message.orEmpty()))
			}
		}
	}

	private fun verSuscrito(id: Long) {
		startActivity(Intent(this, PodcastActivity::class.java).putExtra(PodcastActivity.EXTRA_PODCAST, id)
			.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
		finish()
	}

	private fun navegador(direccion: String) {
		runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(direccion))) }.onFailure { avisar(getString(R.string.no_disponible)) }
	}

	private fun compartir(titulo: String, direccion: String) {
		startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "$titulo\n$direccion"), getString(R.string.compartir)))
	}

	// --- Lista -----------------------------------------------------------------------------

	private fun episodios(c: PodcastLector.Canal) = if (c.serie) c.elementos.sortedBy { it.fecha } else c.elementos.sortedByDescending { it.fecha }

	private fun mostrar() {
		val c = canal ?: return
		val lista = eps.filter { filtro.length < 2 || it.e.titulo.contains(filtro, true) || it.texto.contains(filtro, true) }
		filas = if (filtro.length >= 2) lista.map { Fila.DeEpisodio(it) }
		else listOf(Fila.Info(-1, getString(R.string.pod_informacion), informacion(c)), Fila.Info(-2, getString(R.string.pod_descripcion), c.descripcion.ifBlank { "—" })) +
			lista.map { Fila.DeEpisodio(it) }
		@Suppress("NotifyDataSetChanged")
		b.recycler.adapter?.notifyDataSetChanged()
		b.estado.text = resources.getQuantityString(R.plurals.pod_n_episodios, lista.size, lista.size)
	}

	/** Todo lo que se sabe del podcast, en frases. */
	private fun informacion(c: PodcastLector.Canal): String {
		// Fechas razonables (sin las que vienen mal y salen como 1970)
		val fechas = c.elementos.map { it.fecha }.filter { it > 946_684_800_000L }.sorted()
		val frecuencia = if (fechas.size >= 3) {
			val dias = (fechas.last() - fechas.first()) / 86_400_000.0 / (fechas.size - 1)
			when {
				dias < 1.5 -> getString(R.string.pod_frecuencia_diaria)
				dias < 10 -> getString(R.string.pod_frecuencia_semanal)
				dias < 20 -> getString(R.string.pod_frecuencia_quincenal)
				dias < 45 -> getString(R.string.pod_frecuencia_mensual)
				else -> getString(R.string.pod_frecuencia_irregular)
			}
		} else ""
		val duraciones = c.elementos.map { it.duracion }.filter { it > 0 }
		return listOf(
			c.autor.ifBlank { c.propietario },
			resources.getQuantityString(R.plurals.pod_n_episodios, c.elementos.size, c.elementos.size),
			fechas.lastOrNull()?.let { getString(R.string.pod_ultimo_episodio, Rss.fechaLegible(it)) }.orEmpty(),
			fechas.firstOrNull()?.let { getString(R.string.pod_primer_episodio, Rss.fechaLegible(it)) }.orEmpty(),
			frecuencia,
			if (duraciones.isNotEmpty()) getString(R.string.pod_duracion_media, tiempoHablado(duraciones.average().toLong(), conSegundos = false)) else "",
			if (c.serie) getString(R.string.pod_es_serie) else "",
			c.categorias.joinToString(", "),
			c.idioma.takeIf { it.isNotBlank() }?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.getDefault()) }.orEmpty(),
			if (c.elementos.any { it.transcripcion.isNotBlank() }) getString(R.string.pod_tiene_transcripciones) else "",
			if (c.elementos.any { it.capitulos.isNotBlank() }) getString(R.string.pod_tiene_capitulos) else "",
			c.copyright.takeIf { it != c.autor && it != c.propietario }.orEmpty(),
		).filter { it.isNotBlank() }.distinct().joinToString(". ") + "."
	}

	private fun descripcionEpisodio(e: PodcastLector.Elemento): String = listOf(
		if (e.temporada > 0 && e.numero > 0) getString(R.string.pod_temporada_episodio, e.temporada, e.numero)
		else if (e.numero > 0) getString(R.string.pod_episodio_n, e.numero) else "",
		Rss.fechaLegible(e.fecha),
		if (e.duracion > 0) tiempoHablado(e.duracion, conSegundos = false) else "",
		if (e.transcripcion.isNotBlank()) getString(R.string.pod_con_transcripcion) else "",
	).filter { it.isNotBlank() }.joinToString(", ")

	private fun accionesEpisodio(e: PodcastLector.Elemento): List<Accion> {
		val podcast = canal?.titulo.orEmpty()
		val r = mutableListOf<Accion>(
			getString(R.string.pod_escuchar_sin_suscribir) to { lifecycleScope.launch { Podcasts.escucharSinSuscribir(this@PodcastFichaActivity, podcast, e) } },
			getString(R.string.pod_notas) to { Podcasts.abrirTexto(this, "notas_ficha", e.titulo, Podcasts.htmlNotas(this, podcast, e)) },
		)
		if (e.transcripcion.isNotBlank()) r += getString(R.string.pod_transcripcion) to {
			lifecycleScope.launch {
				runCatching { conEspera(getString(R.string.pod_transcripcion), getString(R.string.cargando)) { Podcasts.htmlTranscripcion(this@PodcastFichaActivity, e.titulo, e.transcripcion, e.tipoTranscripcion) } }
					.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
					.onSuccess { Podcasts.abrirTexto(this@PodcastFichaActivity, "transcripcion_ficha", e.titulo, it) }
					.onFailure { avisar(getString(R.string.error_catalogo, it.message.orEmpty())) }
			}
		}
		r += getString(R.string.compartir) to { compartir(e.titulo, e.enlace.ifBlank { e.audio }) }
		if (e.enlace.isNotBlank()) r += getString(R.string.abrir_navegador) to { navegador(e.enlace) }
		return r
	}

	private inner class Vista(val f: ItemListaBinding) : RecyclerView.ViewHolder(f.root)

	// Ids estables (pauta 15): al filtrar, TalkBack no pierde el sitio
	private inner class Adaptador : RecyclerView.Adapter<Vista>() {
		init {
			setHasStableIds(true)
		}

		override fun getItemId(posicion: Int): Long = when (val f = filas[posicion]) {
			is Fila.Info -> f.id
			is Fila.DeEpisodio -> f.ep.id
		}

		override fun getItemCount() = filas.size

		override fun onCreateViewHolder(padre: ViewGroup, tipo: Int) = Vista(ItemListaBinding.inflate(LayoutInflater.from(padre.context), padre, false))

		override fun onBindViewHolder(v: Vista, posicion: Int) {
			when (val fila = filas[posicion]) {
				is Fila.Info -> {
					v.f.texto.text = fila.titulo
					v.f.detalle.text = fila.texto
					v.f.detalle.isVisible = true
					v.f.detalle.maxLines = Int.MAX_VALUE
					v.f.root.contentDescription = "${fila.titulo}. ${fila.texto}"
					v.f.root.setOnClickListener(null)
					v.f.root.isClickable = false
					v.f.root.accionesAccesibles(emptyList())
					v.f.root.setOnLongClickListener(null)
					// Sin nada que hacer al mantener pulsado (setOnLongClickListener lo deja activado)
					v.f.root.isLongClickable = false
				}
				is Fila.DeEpisodio -> {
					val e = fila.e
					val d = descripcionEpisodio(e)
					v.f.texto.text = e.titulo
					v.f.detalle.text = d
					v.f.detalle.isVisible = d.isNotBlank()
					v.f.detalle.maxLines = 2
					v.f.root.contentDescription = listOf(e.titulo, d).filter { it.isNotBlank() }.joinToString(", ")
					val acciones = accionesEpisodio(e)
					v.f.root.setOnClickListener { elegir(e.titulo, acciones) }
					v.f.root.accionesAccesibles(acciones, ZonaAcciones.EPISODIO)
					v.f.root.setOnLongClickListener {
						elegir(e.titulo, acciones)
						true
					}
				}
			}
		}
	}
}
