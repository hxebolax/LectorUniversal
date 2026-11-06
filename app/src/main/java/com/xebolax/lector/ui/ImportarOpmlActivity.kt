package com.xebolax.lector.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.xebolax.lector.R
import com.xebolax.lector.databinding.ActivityListaBinding
import com.xebolax.lector.podcast.PodcastLector
import com.xebolax.lector.podcast.Podcasts
import com.xebolax.lector.podcast.PodcastsBaseDatos
import com.xebolax.lector.rss.RssBaseDatos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Importar un OPML, de noticias, de podcasts o mezclado. Antes de importar
 * se comprueba cada fuente (si sus elementos traen audio, es un podcast) y se
 * muestra la lista: cada fuente dice adónde irá (Noticias o Podcasts), en qué
 * carpeta y si se importa. Se puede quitar o poner cada una (doble toque),
 * cambiarla de sección o de carpeta, cambiarle el nombre, y elegir todas o
 * ninguna. Las que ya tienes salen sin marcar.
 */
class ImportarOpmlActivity : AppCompatActivity() {

	companion object {
		const val EXTRA_DESTINO = "destino"

		/** El texto del OPML (se pasa en memoria: puede ser largo). */
		private var pendiente: String? = null

		/** destino: dónde van las que no se puedan comprobar (true = Podcasts). */
		fun abrir(c: Context, opml: String, podcasts: Boolean) {
			pendiente = opml
			c.startActivity(Intent(c, ImportarOpmlActivity::class.java).putExtra(EXTRA_DESTINO, podcasts))
		}
	}

	/** Cómo se supo adónde va: comprobada (se descargó), por pistas (nombre, dirección) o lo elegido por el usuario. */
	private enum class Origen { COMPROBADA, PISTAS, ELEGIDA }

	private class Fuente(
		var titulo: String,
		val url: String,
		val sitio: String,
		var carpeta: String,
		var podcast: Boolean,
		var origen: Origen,
		val yaLaTienes: Boolean,
		var importar: Boolean,
	)

	private lateinit var b: ActivityListaBinding
	private var fuentes: List<Fuente> = emptyList()
	private var porDefectoPodcast = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityListaBinding.inflate(layoutInflater)
		setContentView(b.root)
		configurarBarra(b.barra, getString(R.string.rss_importar_opml))
		b.filtroContenedor.isVisible = false
		b.estado.isVisible = true
		b.recycler.layoutManager = LinearLayoutManager(this)
		b.recycler.sinFocoPropio()
		b.recycler.adapter = Adaptador().also { it.setHasStableIds(true) }
		// Al marcar o desmarcar, la fila se actualiza en su sitio (sin animación que la sustituya): el foco de TalkBack no se mueve
		(b.recycler.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
		porDefectoPodcast = intent.getBooleanExtra(EXTRA_DESTINO, false)
		val opml = pendiente ?: run {
			finish()
			return
		}
		if (savedInstanceState == null) cargar(opml)
	}

	private fun cargar(opml: String) {
		lifecycleScope.launch {
			val leidas = runCatching { withContext(Dispatchers.IO) { leer(opml) } }.getOrElse { emptyList() }
			if (leidas.isEmpty()) {
				mensaje(getString(R.string.rss_importar_opml), getString(R.string.opml_vacio))
				return@launch finish()
			}
			// Se comprueba cada una (unas cuantas a la vez) para saber si es un podcast
			var hechas = 0
			val comprobadas = runCatching {
				conEspera(getString(R.string.rss_importar_opml), getString(R.string.opml_comprobando, leidas.size)) {
					val turno = Semaphore(6)
					withContext(Dispatchers.IO) {
						leidas.map { f ->
							async {
								turno.withPermit {
									comprobar(f)
									hechas++
								}
							}
						}.awaitAll()
					}
					leidas
				}
			}.getOrElse { if (it is kotlinx.coroutines.CancellationException) leidas else throw it }
			fuentes = comprobadas
			b.recycler.adapter?.notifyDataSetChanged()
			ponerEstado()
			invalidateOptionsMenu()
			avisar(resumen(), mostrar = false)
		}
	}

	/** Las fuentes del OPML con su carpeta (la del grupo que las contiene) y si ya las tienes. */
	private fun leer(opml: String): List<Fuente> {
		val doc = Jsoup.parse(opml, "", Parser.xmlParser())
		val rss = RssBaseDatos.obtener(this)
		val pod = PodcastsBaseDatos.obtener(this)
		// Un OPML de una app de podcasts (o con «podcast» en su título): sus fuentes, en principio, son podcasts
		val tituloOpml = doc.selectFirst("head > title")?.text().orEmpty()
		val vistas = HashSet<String>()
		return doc.select("outline[xmlUrl]").mapNotNull { o ->
			val url = o.attr("xmlUrl").trim()
			if (url.isEmpty() || !vistas.add(url)) return@mapNotNull null
			val padre: org.jsoup.nodes.Element? = o.parent()?.takeIf { it.tagName() == "outline" && !it.hasAttr("xmlUrl") }
			val carpeta = padre?.attr("title")?.ifBlank { padre.attr("text") }.orEmpty().trim()
			val titulo = o.attr("title").ifBlank { o.attr("text") }.ifBlank { url }.trim()
			val esPodcast = pistasDePodcast(url, titulo, carpeta, tituloOpml, o.attr("type")) ?: porDefectoPodcast
			val ya = rss.fuentePorUrl(url) != null || pod.podcastPorUrl(url) != null
			Fuente(titulo, url, o.attr("htmlUrl"), carpeta, esPodcast, Origen.PISTAS, ya, importar = !ya)
		}
	}

	/** Pistas sin descargar nada: servicios de podcasts conocidos o «podcast» en el nombre. null = no se sabe. */
	private fun pistasDePodcast(url: String, titulo: String, carpeta: String, tituloOpml: String, tipo: String): Boolean? {
		val u = url.lowercase()
		val servicios = listOf(
			"podcast", "anchor.fm", "libsyn", "ivoox", "spreaker", "podbean", "simplecast", "soundcloud", "buzzsprout", "omny", "acast",
			"megaphone", "art19", "audioboom", "transistor.fm", "captivate", "redcircle", "feeds.npr.org", "podigee", "blubrry",
		)
		return when {
			tipo.equals("audio", ignoreCase = true) -> true
			servicios.any { it in u } -> true
			listOf(titulo, carpeta).any { it.contains("podcast", ignoreCase = true) } -> true
			tituloOpml.contains("podcast", ignoreCase = true) -> true
			else -> null
		}
	}

	/** Descarga la fuente: si la mayoría de sus elementos traen audio, es un podcast. */
	private fun comprobar(f: Fuente) {
		val canal = runCatching { PodcastLector.descargar(f.url).canal }.getOrNull() ?: return
		val elementos = canal.elementos.take(20)
		if (elementos.isEmpty()) return
		f.podcast = elementos.count { it.audio.isNotBlank() } * 2 > elementos.size
		f.origen = Origen.COMPROBADA
		if (f.titulo == f.url && canal.titulo.isNotBlank()) f.titulo = canal.titulo
	}

	private fun resumen(): String {
		val elegidas = fuentes.filter { it.importar }
		return getString(R.string.opml_resumen, fuentes.size, elegidas.count { !it.podcast }, elegidas.count { it.podcast })
	}

	private fun ponerEstado() {
		b.estado.text = resumen() + "\n" + getString(R.string.opml_ayuda)
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		if (fuentes.isEmpty()) return true
		val n = fuentes.count { it.importar }
		menu.add(Menu.NONE, 1, Menu.NONE, getString(R.string.opml_importar_n, n)).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
		menu.add(Menu.NONE, 2, Menu.NONE, R.string.opml_todas)
		menu.add(Menu.NONE, 3, Menu.NONE, R.string.opml_ninguna)
		menu.add(Menu.NONE, 4, Menu.NONE, R.string.opml_elegidas_a_noticias)
		menu.add(Menu.NONE, 5, Menu.NONE, R.string.opml_elegidas_a_podcasts)
		menu.add(Menu.NONE, 6, Menu.NONE, R.string.opml_elegidas_a_carpeta)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		val elegidas = fuentes.filter { it.importar }
		when (item.itemId) {
			1 -> importar()
			2 -> cambiar { fuentes.forEach { it.importar = true } }
			3 -> cambiar { fuentes.forEach { it.importar = false } }
			4 -> cambiar { elegidas.forEach { it.podcast = false; it.origen = Origen.ELEGIDA } }
			5 -> cambiar { elegidas.forEach { it.podcast = true; it.origen = Origen.ELEGIDA } }
			6 -> elegirCarpeta(getString(R.string.opml_elegidas_a_carpeta), carpetasPara(elegidas.all { it.podcast }), "") { c ->
				cambiar { elegidas.forEach { it.carpeta = c } }
			}
			else -> return super.onOptionsItemSelected(item)
		}
		return true
	}

	/** Aplica un cambio, redibuja y lo dice. */
	private fun cambiar(cambio: () -> Unit) {
		cambio()
		b.recycler.adapter?.notifyDataSetChanged()
		ponerEstado()
		invalidateOptionsMenu()
		avisar(resumen(), mostrar = false)
	}

	/** Carpetas que se ofrecen: las de esa sección y las que trae el OPML. */
	private fun carpetasPara(podcasts: Boolean): List<String> {
		val existentes = if (podcasts) PodcastsBaseDatos.obtener(this).carpetas() else RssBaseDatos.obtener(this).categorias()
		val delOpml = fuentes.map { it.carpeta }.filter { it.isNotBlank() }
		return (existentes + delOpml).distinctBy { it.lowercase() }
	}

	private fun importar() {
		val elegidas = fuentes.filter { it.importar }
		if (elegidas.isEmpty()) return avisar(getString(R.string.opml_nada_elegido))
		lifecycleScope.launch {
			val rss = RssBaseDatos.obtener(this@ImportarOpmlActivity)
			val pod = PodcastsBaseDatos.obtener(this@ImportarOpmlActivity)
			var noticias = 0
			var podcasts = 0
			runCatching {
				conEspera(getString(R.string.rss_importar_opml), resources.getQuantityString(R.plurals.pod_importando, elegidas.size, elegidas.size)) {
					for (f in elegidas) {
						if (f.podcast) {
							if (pod.podcastPorUrl(f.url) == null && runCatching { Podcasts.suscribir(this@ImportarOpmlActivity, f.url, f.titulo, carpeta = f.carpeta) }.isSuccess) podcasts++
						} else if (rss.fuentePorUrl(f.url) == null) {
							withContext(Dispatchers.IO) { rss.anadirFuente(f.url, f.titulo, f.sitio, f.carpeta) }
							noticias++
						}
					}
				}
			}.onFailure { if (it !is kotlinx.coroutines.CancellationException) throw it }
			// Las noticias nuevas se actualizan por detrás
			if (noticias > 0) com.xebolax.lector.LectorApp.ambito.launch { runCatching { com.xebolax.lector.rss.Rss.actualizarTodas(applicationContext) } }
			mensaje(getString(R.string.rss_importar_opml), getString(R.string.opml_importadas, noticias, podcasts)) { finish() }
		}
	}

	// --- Lista -------------------------------------------------------------------------------

	private fun detalle(f: Fuente): String = listOfNotNull(
		getString(if (f.podcast) R.string.podcasts else R.string.noticias),
		if (f.carpeta.isBlank()) getString(R.string.sin_carpeta) else getString(R.string.carpeta_x, f.carpeta),
		getString(R.string.opml_sin_comprobar).takeIf { f.origen == Origen.PISTAS },
		getString(R.string.opml_ya_la_tienes).takeIf { f.yaLaTienes },
	).joinToString(", ")

	/** Lo que se puede hacer con una fuente antes de importarla. */
	private fun acciones(f: Fuente): List<Accion> = listOf(
		getString(if (f.importar) R.string.opml_no_importar else R.string.opml_si_importar) to { cambiar { f.importar = !f.importar } },
		getString(if (f.podcast) R.string.opml_a_noticias else R.string.opml_a_podcasts) to {
			cambiar {
				f.podcast = !f.podcast
				f.origen = Origen.ELEGIDA
			}
		},
		getString(R.string.mover_a_carpeta) to {
			elegirCarpeta(getString(R.string.mover_a_carpeta), carpetasPara(f.podcast), f.carpeta) { c -> cambiar { f.carpeta = c } }
		},
		getString(R.string.editar_nombre) to {
			pedirTexto(getString(R.string.editar_nombre), getString(R.string.nombre), f.titulo) { t -> if (t.isNotBlank()) cambiar { f.titulo = t.trim() } }
		},
		getString(R.string.opml_ver_direccion) to { mensaje(f.titulo, f.url + (if (f.sitio.isNotBlank()) "\n\n" + f.sitio else "")) },
	)

	private class Fila(val raiz: LinearLayout, val texto: TextView, val detalle: TextView, val casilla: MaterialCheckBox) : RecyclerView.ViewHolder(raiz)

	private inner class Adaptador : RecyclerView.Adapter<Fila>() {
		override fun getItemCount() = fuentes.size
		override fun getItemId(posicion: Int) = fuentes[posicion].url.hashCode().toLong()

		override fun onCreateViewHolder(padre: ViewGroup, tipo: Int): Fila {
			val dp = resources.displayMetrics.density
			val raiz = LinearLayout(padre.context).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = Gravity.CENTER_VERTICAL
				minimumHeight = (56 * dp).toInt()
				setPadding((8 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
				isFocusable = true
				isClickable = true
				val fondo = android.util.TypedValue()
				context.theme.resolveAttribute(android.R.attr.selectableItemBackground, fondo, true)
				setBackgroundResource(fondo.resourceId)
				layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			}
			// La casilla se ve, pero lo que se pulsa y lee es la fila entera (una sola parada de TalkBack)
			val casilla = MaterialCheckBox(padre.context).apply {
				isClickable = false
				isFocusable = false
				importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
			}
			val textos = LinearLayout(padre.context).apply { orientation = LinearLayout.VERTICAL }
			val texto = TextView(padre.context).apply {
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
				importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
			}
			val detalle = TextView(padre.context).apply {
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
				setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
				importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
			}
			textos.addView(texto)
			textos.addView(detalle)
			raiz.addView(casilla)
			raiz.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			return Fila(raiz, texto, detalle, casilla)
		}

		override fun onBindViewHolder(v: Fila, posicion: Int) {
			val f = fuentes[posicion]
			v.texto.text = f.titulo
			v.detalle.text = detalle(f)
			v.casilla.isChecked = f.importar
			// Se lee como una casilla: «marcada, El País, Noticias, carpeta Actualidad»
			v.raiz.contentDescription = "${f.titulo}, ${v.detalle.text}"
			ViewCompat.setAccessibilityDelegate(v.raiz, object : AccessibilityDelegateCompat() {
				override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
					super.onInitializeAccessibilityNodeInfo(host, info)
					info.className = android.widget.CheckBox::class.java.name
					info.isCheckable = true
					info.isChecked = f.importar
				}
			})
			val acciones = acciones(f)
			v.raiz.setOnClickListener {
				f.importar = !f.importar
				notifyItemChanged(posicion)
				ponerEstado()
				invalidateOptionsMenu()
			}
			// Importar o no ya es el doble toque: en las acciones, lo demás
			v.raiz.accionesAccesibles(acciones.drop(1))
			v.raiz.setOnLongClickListener {
				elegir(f.titulo, acciones)
				true
			}
		}
	}
}
