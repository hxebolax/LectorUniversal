package com.xebolax.lector.rss

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xebolax.lector.R
import com.xebolax.lector.databinding.ActivityRssBinding
import com.xebolax.lector.ui.conDeshacer
import com.xebolax.lector.ui.elegirCarpeta
import com.xebolax.lector.ui.AjustesActivity
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.conEspera
import com.xebolax.lector.ui.campoTexto
import com.xebolax.lector.ui.Seccion
import com.xebolax.lector.ui.configurarBarra
import com.xebolax.lector.ui.ponerMenuLateral
import com.xebolax.lector.ui.ponerPestanas
import com.xebolax.lector.ui.seguirMiniReproductor
import com.xebolax.lector.ui.dp
import com.xebolax.lector.ui.elegir
import com.xebolax.lector.ui.mensaje
import com.xebolax.lector.ui.pedirTexto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Noticias (RSS), una sección como la Biblioteca: «Abrir menú» arriba y las
 * pestañas abajo. Sin leer, Fuentes (por
 * categorías), Favoritos, Más tarde y Buscar. Los artículos no pasan a la
 * biblioteca salvo que se pida.
 */
class RssActivity : AppCompatActivity() {

	companion object {
		fun abrir(c: Context) = c.startActivity(Intent(c, RssActivity::class.java))
		/** Id del artículo que hay que abrir (desde el aviso de artículos nuevos). */
		const val EXTRA_ARTICULO = "articulo"
	}

	private lateinit var b: ActivityRssBinding
	private val pestanas = RssListaFragment.Modo.entries
	private val actualizaciones = com.xebolax.lector.actualizaciones.GestorActualizaciones(this)

	private val exportar = registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
		uri ?: return@registerForActivityResult
		lifecycleScope.launch {
			val ok = withContext(Dispatchers.IO) {
				runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(Rss.exportarOpml(this@RssActivity)) } }.isSuccess
			}
			avisar(getString(if (ok) R.string.rss_opml_exportado else R.string.error_exportar))
		}
	}

	private var paraExportar: List<Articulo> = emptyList()

	/** Exporta los artículos de la pestaña a un HTML (para leerlos en otra app o guardarlos). */
	private val exportarLista = registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
		uri ?: return@registerForActivityResult
		val lista = paraExportar
		lifecycleScope.launch {
			val ok = withContext(Dispatchers.IO) {
				runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(Rss.htmlVarios(this@RssActivity, getString(R.string.noticias), lista)) } }.isSuccess
			}
			avisar(if (ok) resources.getQuantityString(R.plurals.rss_exportados, lista.size, lista.size) else getString(R.string.error_exportar))
		}
	}

	/** Pasa a Fuentes y abre esa fuente (desde Estado de las fuentes). */
	fun abrirFuenteDesdeHerramientas(id: Long) = irAFuente(id)

	private val importar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		uri ?: return@registerForActivityResult
		lifecycleScope.launch {
			val texto = withContext(Dispatchers.IO) { runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull() }
				?: return@launch avisar(getString(R.string.error_importar))
			// Se revisa antes de importar: qué es cada fuente (noticias o podcast), cuáles y en qué carpeta
			com.xebolax.lector.ui.ImportarOpmlActivity.abrir(this@RssActivity, texto, podcasts = false)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityRssBinding.inflate(layoutInflater)
		configurarBarra(b.barra, getString(R.string.noticias), conAtras = false)
		setContentView(ponerMenuLateral(b.root, b.barra, Seccion.NOTICIAS, actualizaciones) { b.paginas.setCurrentItem(0, false) })
		seguirMiniReproductor(b.miniReproductor)
		Rss.preparar(this)
		b.paginas.adapter = object : FragmentStateAdapter(this) {
			override fun getItemCount() = pestanas.size
			override fun createFragment(posicion: Int): Fragment = RssListaFragment.nuevo(pestanas[posicion])
		}
		b.paginas.offscreenPageLimit = 1
		ponerPestanas(b.barra, b.paginas, b.pestanas, pestanas.map { it.nombre to it.icono }, getString(R.string.noticias))
		RssTrabajo.programar(this)
		// Al entrar, actualizar si hace más de 15 minutos (no con «Solo a mano», ni con datos móviles si es solo con wifi)
		val soloWifi = Rss.prefs(this).getBoolean("rss_solo_wifi", false) &&
			(getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
		val automatica = RssTrabajo.minutos(this, RssTrabajo.NOTICIAS) > 0
		if (savedInstanceState == null && automatica && !soloWifi && System.currentTimeMillis() - Rss.prefs(this).getLong("rss_ultima", 0) > 15 * 60_000L) actualizarTodo(silencioso = true)
		if (savedInstanceState == null) {
			compartido(intent)
			desdeAviso(intent)
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		compartido(intent)
		desdeAviso(intent)
	}

	/** Se pulsó un artículo en el aviso de artículos nuevos: se abre (y se quita su aviso). */
	private fun desdeAviso(i: Intent) {
		val id = i.getLongExtra(EXTRA_ARTICULO, 0L)
		if (id <= 0) return
		i.removeExtra(EXTRA_ARTICULO)
		RssTrabajo.quitarAviso(this, id)
		lifecycleScope.launch {
			withContext(Dispatchers.IO) { RssBaseDatos.obtener(this@RssActivity).articulo(id) }?.let { RssAcciones.abrir(this@RssActivity, it) }
		}
	}

	/** Llegó una dirección desde Compartir de otra app: se busca su fuente y se suscribe. */
	private fun compartido(i: Intent) {
		if (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_VIEW) return
		val texto = i.getStringExtra(Intent.EXTRA_TEXT) ?: i.dataString ?: return
		val direccion = Regex("""(https?://|feed://)\S+""").find(texto)?.value?.replaceFirst("feed://", "https://") ?: texto.trim()
		i.action = null
		if (direccion.isNotBlank()) suscribirDireccion(direccion, "")
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menu.add(Menu.NONE, 1, Menu.NONE, R.string.rss_actualizar).setIcon(R.drawable.ic_actualizar).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
		menu.add(Menu.NONE, 2, Menu.NONE, R.string.rss_anadir_fuente).setIcon(R.drawable.ic_anadir).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
		menu.add(Menu.NONE, 3, Menu.NONE, R.string.rss_marcar_todo_leido)
		menu.add(Menu.NONE, 4, Menu.NONE, R.string.rss_importar_opml)
		menu.add(Menu.NONE, 5, Menu.NONE, R.string.rss_exportar_opml)
		menu.add(Menu.NONE, 7, Menu.NONE, R.string.rss_reglas)
		menu.add(Menu.NONE, 8, Menu.NONE, R.string.rss_papelera)
		menu.add(Menu.NONE, 9, Menu.NONE, R.string.rss_estado_fuentes)
		menu.add(Menu.NONE, 10, Menu.NONE, R.string.rss_resumir_no_leidos)
		menu.add(Menu.NONE, 11, Menu.NONE, R.string.rss_exportar_lista)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			1 -> actualizarTodo()
			2 -> anadirFuente()
			3 -> marcarTodoLeido()
			4 -> importar.launch(arrayOf("*/*"))
			5 -> exportar.launch("noticias.opml")
			7 -> RssHerramientas.reglas(this)
			8 -> RssHerramientas.papelera(this)
			9 -> RssHerramientas.estadoFuentes(this)
			10 -> RssHerramientas.resumirNoLeidos(this)
			11 -> {
				val f = supportFragmentManager.findFragmentByTag("f${b.paginas.currentItem}") as? RssListaFragment
				val lista = f?.visibles().orEmpty()
				if (lista.isEmpty()) avisar(getString(R.string.rss_nada_que_exportar))
				else {
					paraExportar = lista
					exportarLista.launch("noticias.html")
				}
			}
			else -> return super.onOptionsItemSelected(item)
		}
		return true
	}

	/** Pasa a Fuentes y abre esa fuente. */
	fun irAFuente(id: Long) {
		val i = pestanas.indexOf(RssListaFragment.Modo.FUENTES)
		b.paginas.setCurrentItem(i, false)
		// La pestaña puede no estar creada aún (desde Buscar): se espera un poco a que lo esté
		lifecycleScope.launch {
			repeat(20) {
				val f = supportFragmentManager.findFragmentByTag("f$i") as? RssListaFragment
				if (f != null && f.view != null) return@launch f.abrirFuente(id)
				delay(100)
			}
		}
	}

	/** «Marcar todo como leído» (o solo lo anterior a un día). Si no hay nada sin leer, se dice en lugar de preguntar. */
	private fun marcarTodoLeido() {
		val db = RssBaseDatos.obtener(this)
		lifecycleScope.launch {
			if (!withContext(Dispatchers.IO) { db.haySinLeer() }) return@launch mensaje(getString(R.string.rss_marcar_todo_leido), getString(R.string.noticias_nada_sin_leer))
			MaterialAlertDialogBuilder(this@RssActivity)
				.setMessage(R.string.rss_confirmar_todo_leido)
				.setPositiveButton(R.string.rss_marcar_todo_leido) { _, _ -> marcarLeidos(0L) }
				.setNeutralButton(R.string.rss_anteriores_a_un_dia) { _, _ -> marcarLeidos(System.currentTimeMillis() - 86_400_000L) }
				.setNegativeButton(R.string.cancelar, null)
				.show()
		}
	}

	/** Marca como leído todo (antesDe > 0: solo lo publicado antes), sin límite y fuera del hilo principal, con Deshacer. */
	private fun marcarLeidos(antesDe: Long) {
		val db = RssBaseDatos.obtener(this)
		lifecycleScope.launch {
			val ids = withContext(Dispatchers.IO) { db.marcarTodoLeido(antesDe = antesDe) }
			// Nada que marcar (por ejemplo, ninguno de hace más de un día): solo se dice, sin Deshacer
			if (ids.isEmpty()) return@launch avisar(getString(if (antesDe > 0) R.string.noticias_ninguno_antiguo else R.string.noticias_nada_sin_leer))
			conDeshacer(resources.getQuantityString(R.plurals.rss_marcados_leidos, ids.size, ids.size)) {
				RssAcciones.enFondo(this@RssActivity, { db.marcarLeidos(ids, false) })
			}
		}
	}

	/** Actualiza todas las fuentes y anuncia cuántos artículos nuevos hay. */
	fun actualizarTodo(silencioso: Boolean = false) {
		lifecycleScope.launch {
			// A mano, con ventana de espera; la automática al entrar va por detrás sin molestar
			val r = if (silencioso) Rss.actualizarTodas(this@RssActivity)
			else conEspera(getString(R.string.rss_actualizar), getString(R.string.rss_actualizando)) { Rss.actualizarTodas(this@RssActivity) }
			val texto = when {
				r.nuevos > 0 -> resources.getQuantityString(R.plurals.rss_nuevos, r.nuevos, r.nuevos)
				else -> getString(R.string.rss_sin_nuevos)
			} + if (r.errores > 0) " " + resources.getQuantityString(R.plurals.rss_errores, r.errores, r.errores) else ""
			if (!silencioso || r.nuevos > 0) avisar(texto)
			// A mano: si hay fuentes de este móvil que aún no están en la cuenta de sincronización, se pregunta si subirlas
			if (!silencioso) r.sincronizacion?.let { RssSincronizacion.preguntarSubir(this@RssActivity, it) }
			// Después, los artículos completos de los nuevos (si está pedido)
			Rss.completarPendientes(this@RssActivity)
		}
	}

	/** Añadir: por dirección (de la fuente o de su web) o buscando por tema. */
	fun anadirFuente(categoria: String = "") {
		elegir(getString(R.string.rss_anadir_fuente), listOf(
			getString(R.string.rss_buscar_fuentes) to { buscarFuentes(categoria) },
			getString(R.string.rss_por_direccion) to {
				pedirTexto(getString(R.string.rss_por_direccion), getString(R.string.rss_direccion_ayuda), teclado = com.xebolax.lector.ui.Teclado.DIRECCION) { t ->
					if (t.isNotBlank()) suscribirDireccion(t, categoria)
				}
			},
			getString(R.string.rss_recomendadas) to { recomendadas() },
		))
	}

	private fun recomendadas() {
		val db = RssBaseDatos.obtener(this)
		lifecycleScope.launch {
			val faltan = withContext(Dispatchers.IO) { Rss.PREDETERMINADAS.filter { db.fuentePorUrl(it.second) == null } }
			if (faltan.isEmpty()) return@launch mensaje(getString(R.string.rss_recomendadas), getString(R.string.rss_ya_todas))
			elegir(getString(R.string.rss_recomendadas), faltan.map { (nombre, url, cat) ->
				"$nombre ($cat)" to { suscribir(nombre, url, cat) }
			})
		}
	}

	private fun buscarFuentes(categoria: String) {
		pedirTexto(getString(R.string.rss_buscar_fuentes), getString(R.string.rss_buscar_ayuda)) { texto ->
			if (texto.isBlank()) return@pedirTexto
			lifecycleScope.launch {
				val lista = runCatching { conEspera(getString(R.string.rss_buscar_fuentes), getString(R.string.buscando_x, texto)) { Rss.buscarFuentes(texto) } }
					.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
				if (lista.isNullOrEmpty()) return@launch mensaje(getString(R.string.rss_buscar_fuentes), getString(R.string.rss_sin_resultados))
				val db = RssBaseDatos.obtener(this@RssActivity)
				val suscritas = withContext(Dispatchers.IO) { lista.filter { db.fuentePorUrl(it.url) != null }.map { it.url }.toSet() }
				elegir(getString(R.string.resultados_de, lista.size, texto), lista.map { s ->
					// Si ya estás suscrito, se dice
					val ya = s.url in suscritas
					val detalle = listOf(if (ya) getString(R.string.rss_ya_suscrita) else "", s.descripcion.take(120), if (s.suscriptores > 0) getString(R.string.rss_suscriptores, s.suscriptores) else "")
						.filter { it.isNotBlank() }.joinToString(". ")
					(if (detalle.isBlank()) s.titulo else "${s.titulo}. $detalle") to { suscribir(s.titulo, s.url, categoria) }
				})
			}
		}
	}

	private fun suscribirDireccion(direccion: String, categoria: String) {
		// Redes que no publican RSS: se dice claramente (antes salía «no encontrada»)
		val host = runCatching { java.net.URL(if (direccion.trim().startsWith("http")) direccion.trim() else "https://" + direccion.trim()).host.lowercase().removePrefix("www.").removePrefix("m.") }.getOrDefault("")
		val red = listOf("facebook.com", "fb.com", "fb.watch", "instagram.com", "x.com", "twitter.com", "tiktok.com", "threads.net", "whatsapp.com", "linkedin.com")
			.firstOrNull { host == it || host.endsWith(".$it") }
		if (red != null) return mensaje(getString(R.string.rss_anadir_fuente), getString(R.string.rss_red_sin_rss, red))
		lifecycleScope.launch {
			val encontradas = runCatching { conEspera(getString(R.string.rss_anadir_fuente), getString(R.string.rss_buscando_fuente)) { Rss.descubrir(direccion) } }.getOrElse {
				if (it is kotlinx.coroutines.CancellationException) throw it
				return@launch mensaje(getString(R.string.rss_anadir_fuente), getString(R.string.rss_no_encontrada, it.message.orEmpty()))
			}
			when (encontradas.size) {
				0 -> mensaje(getString(R.string.rss_anadir_fuente), getString(R.string.rss_no_encontrada, direccion))
				1 -> suscribir(encontradas[0].first, encontradas[0].second, categoria)
				else -> elegir(getString(R.string.rss_varias_fuentes), encontradas.map { (t, u) -> t to { suscribir(t, u, categoria) } })
			}
		}
	}

	private fun suscribir(titulo: String, url: String, categoria: String) {
		val db = RssBaseDatos.obtener(this)
		lifecycleScope.launch {
			val f = withContext(Dispatchers.IO) {
				if (db.fuentePorUrl(url) != null) null
				else db.anadirFuente(url, titulo, categoria = categoria).let { id ->
					// La ha añadido el usuario: es suya aunque sea una de las de ejemplo (se sube a la cuenta)
					Rss.tocarFuente(this@RssActivity, id)
					db.fuente(id)
				}
			} ?: return@launch avisar(getString(R.string.rss_ya_suscrito, titulo))
			// Ventana de espera hasta que la fuente está suscrita y con sus artículos (y las reglas aplicadas)
			val n = try {
				conEspera(getString(R.string.pod_suscribirse), getString(R.string.pod_suscribiendo)) { Rss.actualizarUna(this@RssActivity, f) }
			} catch (e: kotlinx.coroutines.CancellationException) {
				// Se ha cerrado la pantalla: nada más. Con Cancelar: la fuente ya está añadida y se dice
				if (!isActive) throw e
				return@launch avisar(getString(R.string.noticias_suscrito_luego, titulo))
			} catch (e: Exception) {
				-1
			}
			if (n < 0) mensaje(getString(R.string.rss_anadir_fuente), getString(R.string.rss_error_fuente, titulo))
			else avisar(getString(R.string.rss_suscrito, titulo) + ". " + resources.getQuantityString(R.plurals.rss_n_articulos, n, n))
			// Añadida fuera de una carpeta: se ofrece ponerla en una (o crear una nueva)
			val carpetas = if (categoria.isBlank() && n >= 0) withContext(Dispatchers.IO) { db.categorias() } else emptyList()
			if (carpetas.isNotEmpty()) elegirCarpeta(getString(R.string.en_que_carpeta, titulo), carpetas, "") { destino ->
				if (destino.isNotBlank()) RssAcciones.enFondo(this@RssActivity, { db.ponerCategoria(f.id, destino) }) {
					avisar(getString(R.string.movida_a_carpeta, destino))
				}
			}
		}
	}

	/** Editar una fuente: nombre, categoría, avisos, texto completo, navegador, palabras silenciadas, pausa. */
	fun editarFuente(f: Fuente) {
		val (cNombre, nombre) = campoTexto(getString(R.string.nombre), f.titulo)
		// La dirección se puede cambiar (si la web la movió); se avisa si ya hay otra fuente con ella
		val (cDireccion, direccion) = campoTexto(getString(R.string.rss_direccion_fuente), f.url)
		direccion.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
		// Carpeta: un botón que dice la actual y abre la lista (con «Nueva carpeta…» para crear una)
		var carpeta = f.categoria
		val botonCarpeta = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
		fun ponerCarpeta() {
			botonCarpeta.text = getString(R.string.carpeta_actual, carpeta.ifBlank { getString(R.string.sin_carpeta) })
		}
		ponerCarpeta()
		botonCarpeta.setOnClickListener {
			lifecycleScope.launch {
				val lista = withContext(Dispatchers.IO) { RssBaseDatos.obtener(this@RssActivity).categorias() }
				elegirCarpeta(getString(R.string.mover_a_carpeta), lista, carpeta) {
					carpeta = it
					ponerCarpeta()
				}
			}
		}
		val (cSilenciar, silenciar) = campoTexto(getString(R.string.rss_silenciar), f.silenciar)
		val (cSoloCon, soloCon) = campoTexto(getString(R.string.rss_solo_con), f.soloCon)
		val duplicados = com.google.android.material.checkbox.MaterialCheckBox(this).apply { text = getString(R.string.rss_sin_duplicados); isChecked = f.sinDuplicados }
		val avisos = com.google.android.material.checkbox.MaterialCheckBox(this).apply { text = getString(R.string.rss_avisar_fuente); isChecked = f.avisar }
		val completo = com.google.android.material.checkbox.MaterialCheckBox(this).apply { text = getString(R.string.rss_texto_completo_fuente); isChecked = f.textoCompleto }
		val navegador = com.google.android.material.checkbox.MaterialCheckBox(this).apply { text = getString(R.string.rss_en_navegador); isChecked = f.enNavegador }
		val pausada = com.google.android.material.checkbox.MaterialCheckBox(this).apply { text = getString(R.string.rss_pausada); isChecked = f.pausada }
		val formulario = android.widget.LinearLayout(this).apply {
			orientation = android.widget.LinearLayout.VERTICAL
			val m = dp(this@RssActivity, 20)
			setPadding(m, m / 2, m, 0)
			listOf(cNombre, cDireccion, botonCarpeta, cSilenciar, cSoloCon, duplicados, avisos, completo, navegador, pausada).forEach { addView(it) }
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.rss_editar_fuente)
			.setView(android.widget.ScrollView(this).apply { addView(formulario) })
			.setPositiveButton(R.string.guardar) { _, _ ->
				val db = RssBaseDatos.obtener(this)
				val url = direccion.text.toString().trim().let { if (it.isNotEmpty() && !it.contains("://")) "https://$it" else it }.ifBlank { f.url }
				val editada = f.copy(
					titulo = nombre.text.toString().trim().ifBlank { f.titulo },
					url = url,
					soloCon = soloCon.text.toString().trim(),
					sinDuplicados = duplicados.isChecked,
					categoria = carpeta.trim(),
					silenciar = silenciar.text.toString().trim(),
					avisar = avisos.isChecked,
					textoCompleto = completo.isChecked,
					enNavegador = navegador.isChecked,
					pausada = pausada.isChecked,
				)
				lifecycleScope.launch {
					// Se guarda fuera del hilo principal; si ya hay otra fuente con esa dirección, se dice
					val repetida = withContext(Dispatchers.IO) {
						(url != f.url && db.fuentePorUrl(url) != null).also { if (!it) db.editarFuente(editada) }
					}
					// Editada por el usuario: si era de ejemplo, ya es suya (se sube a la cuenta)
					if (!repetida) Rss.tocarFuente(this@RssActivity, f.id)
					if (repetida) mensaje(getString(R.string.rss_editar_fuente), getString(R.string.rss_direccion_repetida))
					else {
						avisar(getString(R.string.guardado))
						if (editada.avisar && !f.avisar) com.xebolax.lector.ui.PermisoAvisos.pedir(this@RssActivity, R.string.cj_avisos_por_noticias)
					}
				}
			}
			.setNegativeButton(R.string.cancelar, null)
			.show()
	}
}
