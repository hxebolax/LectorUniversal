package com.xebolax.lector.podcast

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
import com.xebolax.lector.audio.ControlAudio
import com.xebolax.lector.databinding.ActivityRssBinding
import com.xebolax.lector.ui.elegirUno
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Podcasts, una sección como la Biblioteca: «Abrir menú» arriba y las
 * pestañas abajo: Cola, Novedades, Suscripciones, Episodios (descargas,
 * favoritos e historial) y Descubrir (buscar y los más escuchados). Los
 * episodios se escuchan con el reproductor de la app y no van a la biblioteca.
 */
class PodcastActivity : AppCompatActivity() {

	companion object {
		/** Abrir directamente un podcast suscrito (desde su ficha). */
		const val EXTRA_PODCAST = "podcast"

		fun abrir(c: Context) = c.startActivity(Intent(c, PodcastActivity::class.java))
	}

	private lateinit var b: ActivityRssBinding
	private val pestanas = PodcastListaFragment.PESTANAS
	private val actualizaciones = com.xebolax.lector.actualizaciones.GestorActualizaciones(this)

	private val exportar = registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
		uri ?: return@registerForActivityResult
		lifecycleScope.launch {
			val ok = withContext(Dispatchers.IO) {
				runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(Podcasts.exportarOpml(this@PodcastActivity)) } }.isSuccess
			}
			avisar(getString(if (ok) R.string.rss_opml_exportado else R.string.error_exportar))
		}
	}

	private val importar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		uri ?: return@registerForActivityResult
		lifecycleScope.launch {
			val texto = withContext(Dispatchers.IO) { runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull() }
				?: return@launch avisar(getString(R.string.error_importar))
			// Se revisa antes de importar: qué es cada fuente (podcast o noticias), cuáles y en qué carpeta
			com.xebolax.lector.ui.ImportarOpmlActivity.abrir(this@PodcastActivity, texto, podcasts = true)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityRssBinding.inflate(layoutInflater)
		configurarBarra(b.barra, getString(R.string.podcasts), conAtras = false)
		setContentView(ponerMenuLateral(b.root, b.barra, Seccion.PODCASTS, actualizaciones) { b.paginas.setCurrentItem(0, false) })
		seguirMiniReproductor(b.miniReproductor)
		b.paginas.adapter = object : FragmentStateAdapter(this) {
			override fun getItemCount() = pestanas.size
			override fun createFragment(posicion: Int): Fragment = PodcastListaFragment.nuevo(pestanas[posicion])
		}
		ponerPestanas(b.barra, b.paginas, b.pestanas, pestanas.map { it.nombre to it.icono }, getString(R.string.podcasts))
		com.xebolax.lector.rss.RssTrabajo.programar(this)
		if (savedInstanceState == null && System.currentTimeMillis() - Podcasts.prefs(this).getLong("pod_ultima", 0) > 30 * 60_000L) actualizarTodo(silencioso = true)
		if (savedInstanceState == null) intent.getLongExtra(EXTRA_PODCAST, 0).takeIf { it > 0 }?.let { irAPodcast(it) }
		if (savedInstanceState == null) compartido(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		intent.getLongExtra(EXTRA_PODCAST, 0).takeIf { it > 0 }?.let { irAPodcast(it) }
		compartido(intent)
	}

	/** Llegó una dirección desde Compartir de otra app: se busca el podcast y se suscribe. */
	private fun compartido(i: Intent) {
		if (i.action != Intent.ACTION_SEND) return
		val texto = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
		val direccion = Regex("""https?://\S+""").find(texto)?.value ?: return
		i.action = null
		suscribirDireccion(direccion)
	}

	override fun onResume() {
		super.onResume()
		descargasTerminadas()
		invalidateOptionsMenu()
	}

	private fun descargasTerminadas() {
		val app = applicationContext
		lifecycleScope.launch {
			// Gestor de descargas y base de datos: fuera del hilo principal
			val estado = withContext(Dispatchers.IO) { Podcasts.comprobarDescargas(app) }
			val terminadas = estado.terminadas
			if (terminadas.isNotEmpty()) avisar(resources.getQuantityString(R.plurals.pod_descargas_terminadas, terminadas.size, terminadas.size), mostrar = false)
			// Las que han fallado tres veces se dicen (antes se perdían en silencio)
			if (estado.fallidas.isNotEmpty()) mensaje(getString(R.string.pod_descargar), getString(R.string.pod_descargas_fallidas, estado.fallidas.joinToString("\n")))
		}
	}

	/**
	 * Hace algo en el fragmento de una pestaña. ViewPager2 lo crea al dibujarse:
	 * si aún no existe, se espera un poco (si no, se perdía lo que había que hacer).
	 */
	private fun enPestana(i: Int, intentos: Int = 20, hacer: (PodcastListaFragment) -> Unit) {
		val f = supportFragmentManager.findFragmentByTag("f$i") as? PodcastListaFragment
		if (f != null && f.view != null) hacer(f)
		else if (intentos > 0 && !isFinishing) b.paginas.postDelayed({ enPestana(i, intentos - 1, hacer) }, 50)
	}

	// Una descarga termina con la pantalla abierta: pasa a "descargado" en el momento
	private val alDescargar = object : android.content.BroadcastReceiver() {
		override fun onReceive(c: android.content.Context, i: android.content.Intent) = descargasTerminadas()
	}

	override fun onStart() {
		super.onStart()
		androidx.core.content.ContextCompat.registerReceiver(this, alDescargar,
			android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
	}

	override fun onStop() {
		runCatching { unregisterReceiver(alDescargar) }
		super.onStop()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menu.add(Menu.NONE, 1, Menu.NONE, R.string.rss_actualizar).setIcon(R.drawable.ic_actualizar).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
		menu.add(Menu.NONE, 2, Menu.NONE, R.string.pod_anadir).setIcon(R.drawable.ic_anadir).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
		menu.add(Menu.NONE, 4, Menu.NONE, R.string.rss_importar_opml)
		menu.add(Menu.NONE, 5, Menu.NONE, R.string.rss_exportar_opml)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			1 -> actualizarTodo()
			2 -> anadir()
			4 -> importar.launch(arrayOf("*/*"))
			5 -> exportar.launch("podcasts.opml")
			else -> return super.onOptionsItemSelected(item)
		}
		return true
	}

	/** Pasa a Suscripciones y abre ese podcast. */
	fun irAPodcast(id: Long) {
		val i = pestanas.indexOf(PodcastListaFragment.Modo.SUSCRIPCIONES)
		b.paginas.setCurrentItem(i, false)
		b.paginas.post { enPestana(i) { it.abrirPodcast(id) } }
	}

	fun actualizarTodo(silencioso: Boolean = false) {
		lifecycleScope.launch {
			// A mano, con ventana de espera; la automática al entrar va por detrás sin molestar
			val r = if (silencioso) Podcasts.actualizarTodos(this@PodcastActivity, avisar = false)
			else conEspera(getString(R.string.rss_actualizar), getString(R.string.rss_actualizando)) { Podcasts.actualizarTodos(this@PodcastActivity, avisar = false) }
			val texto = (if (r.nuevos > 0) resources.getQuantityString(R.plurals.pod_nuevos, r.nuevos, r.nuevos) else getString(R.string.pod_sin_nuevos)) +
				if (r.errores > 0) " " + resources.getQuantityString(R.plurals.pod_errores, r.errores, r.errores) else ""
			if (!silencioso || r.nuevos > 0) avisar(texto)
		}
	}

	/** Añadir: buscar por nombre o tema, por dirección o desde los más escuchados. */
	fun anadir() {
		elegir(getString(R.string.pod_anadir), listOf(
			getString(R.string.pod_buscar) to { buscar() },
			getString(R.string.pod_por_direccion) to {
				pedirTexto(getString(R.string.pod_por_direccion), getString(R.string.pod_direccion_ayuda), teclado = com.xebolax.lector.ui.Teclado.DIRECCION) { t ->
					if (t.isNotBlank()) suscribirDireccion(t.trim())
				}
			},
			getString(R.string.pod_mas_escuchados) to { b.paginas.currentItem = pestanas.indexOf(PodcastListaFragment.Modo.DESCUBRIR) },
		))
	}

	/** Busca podcasts y muestra los resultados en Descubrir (cada uno con su ficha y sus acciones). */
	fun buscar() {
		pedirTexto(getString(R.string.pod_buscar), getString(R.string.pod_buscar_ayuda)) { texto ->
			if (texto.isBlank()) return@pedirTexto
			lifecycleScope.launch {
				val lista = runCatching { conEspera(getString(R.string.pod_buscar), getString(R.string.buscando_x, texto)) { Podcasts.buscar(texto) } }
					.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
				if (lista.isNullOrEmpty()) return@launch avisar(getString(R.string.rss_sin_resultados))
				val i = pestanas.indexOf(PodcastListaFragment.Modo.DESCUBRIR)
				b.paginas.setCurrentItem(i, false)
				b.paginas.post { enPestana(i) { it.mostrarBusqueda(texto, lista) } }
			}
		}
	}

	/** Suscribirse sin abrir la ficha (acción de un resultado). */
	fun suscribirSugerencia(s: Podcasts.Sugerencia) {
		Podcasts.suscritoA(this, s)?.let { return avisar(getString(R.string.rss_ya_suscrito, it.titulo)) }
		lifecycleScope.launch {
			val url = s.url.ifBlank {
				runCatching { conEspera(getString(R.string.pod_suscribirse), getString(R.string.pod_suscribiendo)) { Podcasts.direccionDe(s.id) } }
					.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull().orEmpty()
			}
			if (url.isBlank()) return@launch mensaje(getString(R.string.pod_suscribirse), getString(R.string.pod_sin_rss))
			suscribirDireccion(url, s.titulo, s.autor)
		}
	}

	/** Ficha de un podcast encontrado, con Suscribirse. */
	fun confirmarSuscripcion(s: Podcasts.Sugerencia) {
		val texto = listOf(s.autor, s.genero, s.descripcion.take(1200)).filter { it.isNotBlank() }.joinToString("\n\n")
		MaterialAlertDialogBuilder(this)
			.setTitle(s.titulo)
			.setMessage(texto.ifBlank { s.titulo })
			.setPositiveButton(R.string.pod_suscribirse) { _, _ ->
				lifecycleScope.launch {
					val url = s.url.ifBlank { runCatching { Podcasts.direccionDe(s.id) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull().orEmpty() }
					if (url.isBlank()) return@launch avisar(getString(R.string.pod_sin_rss))
					suscribirDireccion(url, s.titulo, s.autor)
				}
			}
			.setNegativeButton(R.string.cancelar, null)
			.show()
	}

	private fun suscribirDireccion(url: String, titulo: String = "", autor: String = "") {
		val db = PodcastsBaseDatos.obtener(this)
		if (db.podcastPorUrl(url) != null) return avisar(getString(R.string.rss_ya_suscrito, titulo.ifBlank { url }))
		lifecycleScope.launch {
			try {
				// Ventana de espera hasta que la suscripción está hecha
				val id = conEspera(getString(R.string.pod_suscribirse), getString(R.string.pod_suscribiendo)) {
					// Si es una web, se busca su RSS
					var direccion = url
					val prueba = runCatching { withContext(Dispatchers.IO) { PodcastLector.descargar(url).canal != null } }
					// Un podcast privado: el servidor pide usuario y contraseña
					if (prueba.exceptionOrNull() is PodcastLector.SinPermiso) throw PodcastLector.SinPermiso()
					val es = prueba.getOrDefault(false)
					if (!es) {
						val encontradas = runCatching { com.xebolax.lector.rss.Rss.descubrir(url) }.getOrDefault(emptyList())
						direccion = encontradas.firstOrNull()?.second ?: error(getString(R.string.pod_no_encontrado, url))
					}
					Podcasts.suscribir(this@PodcastActivity, direccion, titulo, autor)
				}
				avisar(getString(R.string.rss_suscrito, db.podcast(id)?.titulo ?: titulo))
				ofrecerCarpeta(id)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: PodcastLector.SinPermiso) {
				pedirCredenciales(url, titulo, autor)
			} catch (e: Exception) {
				mensaje(getString(R.string.pod_anadir), getString(R.string.pod_no_encontrado, e.message.orEmpty()))
			}
		}
	}

	/** Ajustes de un podcast: nombre, velocidad, saltar intro y final, descargas, nuevos episodios, avisos y pausa. */
	/** Botón «Etiqueta: opción elegida» que abre la lista de opciones (en lugar de un desplegable). */
	private fun botonOpcion(etiqueta: Int, opciones: Int, actual: () -> Int, alElegir: (Int) -> Unit) =
		com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
			val nombres = resources.getStringArray(opciones).toList()
			fun poner() {
				text = getString(R.string.etiqueta_valor, getString(etiqueta), nombres.getOrElse(actual()) { "" })
			}
			poner()
			setOnClickListener {
				elegirUno(getString(etiqueta), nombres, actual()) {
					alElegir(it)
					poner()
				}
			}
		}

	/** Un podcast privado: se piden usuario y contraseña y se vuelve a intentar con ellos. */
	private fun pedirCredenciales(url: String, titulo: String, autor: String) {
		val (cUsuario, usuario) = campoTexto(getString(R.string.aj_sinc_usuario))
		val (cClave, clave) = campoTexto(getString(R.string.aj_sinc_clave))
		clave.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
		(cClave as? com.google.android.material.textfield.TextInputLayout)?.endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
		val formulario = android.widget.LinearLayout(this).apply {
			orientation = android.widget.LinearLayout.VERTICAL
			val m = dp(this@PodcastActivity, 20)
			setPadding(m, m / 2, m, 0)
			addView(cUsuario)
			addView(cClave)
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.pod_privado)
			.setMessage(R.string.pod_privado_texto)
			.setView(formulario)
			.setPositiveButton(R.string.pod_suscribirse) { _, _ ->
				lifecycleScope.launch {
					try {
						val id = conEspera(getString(R.string.pod_suscribirse), getString(R.string.pod_suscribiendo)) {
							Podcasts.suscribir(this@PodcastActivity, url, titulo, autor, usuario = usuario.text.toString().trim(), clave = clave.text.toString())
						}
						avisar(getString(R.string.rss_suscrito, PodcastsBaseDatos.obtener(this@PodcastActivity).podcast(id)?.titulo ?: titulo))
						ofrecerCarpeta(id)
					} catch (e: kotlinx.coroutines.CancellationException) {
						throw e
					} catch (e: PodcastLector.SinPermiso) {
						mensaje(getString(R.string.pod_privado), getString(R.string.pod_credenciales_mal))
					} catch (e: Exception) {
						mensaje(getString(R.string.pod_anadir), getString(R.string.pod_no_encontrado, e.message.orEmpty()))
					}
				}
			}
			.setNegativeButton(R.string.cancelar, null)
			.show()
	}

	/** Recién suscrito: si hay carpetas, se ofrece ponerlo en una (o en una nueva). */
	fun ofrecerCarpeta(id: Long) {
		val db = PodcastsBaseDatos.obtener(this)
		val p = db.podcast(id) ?: return
		if (db.carpetas().isEmpty() || p.carpeta.isNotBlank()) return
		elegirCarpeta(getString(R.string.en_que_carpeta, p.titulo), db.carpetas(), "") { destino ->
			if (destino.isNotBlank()) {
				db.ponerCarpeta(id, destino)
				avisar(getString(R.string.movida_a_carpeta, destino))
			}
		}
	}

	fun ajustesPodcast(p: Podcast) {
		val (cNombre, nombre) = campoTexto(getString(R.string.nombre), p.titulo)
		val (cVelocidad, velocidad) = campoTexto(getString(R.string.pod_velocidad_propia), if (p.velocidad > 0) p.velocidad.toString() else "")
		val (cInicio, inicio) = campoTexto(getString(R.string.pod_saltar_inicio), if (p.saltarInicio > 0) p.saltarInicio.toString() else "")
		val (cFinal, final) = campoTexto(getString(R.string.pod_saltar_final), if (p.saltarFinal > 0) p.saltarFinal.toString() else "")
		val (cIncluir, incluir) = campoTexto(getString(R.string.pod_filtro_incluir), p.filtroIncluir)
		val (cExcluir, excluir) = campoTexto(getString(R.string.pod_filtro_excluir), p.filtroExcluir)
		val (cMinimo, minimo) = campoTexto(getString(R.string.pod_duracion_minima), if (p.duracionMin > 0) p.duracionMin.toString() else "")
		minimo.inputType = android.text.InputType.TYPE_CLASS_NUMBER
		val (cUsuario, usuario) = campoTexto(getString(R.string.aj_sinc_usuario), p.usuario)
		val (cClave, clave) = campoTexto(getString(R.string.aj_sinc_clave), p.clave)
		clave.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
		(cClave as? com.google.android.material.textfield.TextInputLayout)?.endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
		val antiguos = com.google.android.material.checkbox.MaterialCheckBox(this).apply { text = getString(R.string.pod_antiguos_primero); isChecked = p.antiguosPrimero }
		listOf(velocidad, inicio, final).forEach { it.inputType = android.text.InputType.TYPE_CLASS_NUMBER }
		var autoDescargar = p.autoDescargar.coerceIn(0, 2)
		var nuevosVan = p.nuevos.coerceIn(0, 3)
		// Volumen propio: como en el reproductor, sin refuerzo o +3 a +15 dB (para los podcasts que se oyen muy bajos)
		val volumenes = listOf(-1, 0, 3, 6, 9, 12, 15)
		var volumen = volumenes.indexOf(p.volumen).coerceAtLeast(0)
		val botonVolumen = botonOpcion(R.string.pod_volumen, R.array.pod_volumen_nombres, { volumen }) { volumen = it }
		val descargar = botonOpcion(R.string.pod_auto_descargar, R.array.pod_auto_descargar_nombres, { autoDescargar }) { autoDescargar = it }
		val nuevos = botonOpcion(R.string.pod_nuevos_van, R.array.pod_nuevos_podcast_nombres, { nuevosVan }) { nuevosVan = it }
		// Carpeta: un botón que dice la actual y abre la lista (con «Nueva carpeta…» para crear una)
		var carpeta = p.carpeta
		val botonCarpeta = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
		fun ponerCarpeta() {
			botonCarpeta.text = getString(R.string.carpeta_actual, carpeta.ifBlank { getString(R.string.sin_carpeta) })
		}
		ponerCarpeta()
		botonCarpeta.setOnClickListener {
			elegirCarpeta(getString(R.string.mover_a_carpeta), PodcastsBaseDatos.obtener(this).carpetas(), carpeta) {
				carpeta = it
				ponerCarpeta()
			}
		}
		val avisos = com.google.android.material.checkbox.MaterialCheckBox(this).apply { text = getString(R.string.pod_avisar); isChecked = p.avisar }
		val pausado = com.google.android.material.checkbox.MaterialCheckBox(this).apply { text = getString(R.string.pod_pausado); isChecked = p.pausado }
		val formulario = android.widget.LinearLayout(this).apply {
			orientation = android.widget.LinearLayout.VERTICAL
			val m = dp(this@PodcastActivity, 20)
			setPadding(m, m / 2, m, 0)
			listOf(cNombre, botonCarpeta, antiguos, cIncluir, cExcluir, cMinimo, cUsuario, cClave, cVelocidad, botonVolumen, cInicio, cFinal, descargar, nuevos, avisos, pausado).forEach { addView(it) }
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.pod_ajustes_podcast)
			.setView(android.widget.ScrollView(this).apply { addView(formulario) })
			.setPositiveButton(R.string.guardar) { _, _ ->
				PodcastsBaseDatos.obtener(this).editarPodcast(p.copy(
					titulo = nombre.text.toString().trim().ifBlank { p.titulo },
					carpeta = carpeta.trim(),
					filtroIncluir = incluir.text.toString().trim(),
					filtroExcluir = excluir.text.toString().trim(),
					duracionMin = minimo.text.toString().toIntOrNull()?.coerceIn(0, 600) ?: 0,
					antiguosPrimero = antiguos.isChecked,
					usuario = usuario.text.toString().trim(),
					clave = clave.text.toString(),
					velocidad = velocidad.text.toString().toIntOrNull()?.let { if (it <= 0) 0 else it.coerceIn(25, 400) } ?: 0,
					volumen = volumenes[volumen],
					saltarInicio = inicio.text.toString().toIntOrNull()?.coerceIn(0, 600) ?: 0,
					saltarFinal = final.text.toString().toIntOrNull()?.coerceIn(0, 600) ?: 0,
					autoDescargar = autoDescargar,
					nuevos = nuevosVan,
					avisar = avisos.isChecked,
					pausado = pausado.isChecked,
				))
				avisar(getString(R.string.guardado))
				if (avisos.isChecked && !p.avisar) com.xebolax.lector.ui.PermisoAvisos.pedir(this, R.string.cj_avisos_por_podcasts)
			}
			.setNegativeButton(R.string.cancelar, null)
			.show()
	}
}
