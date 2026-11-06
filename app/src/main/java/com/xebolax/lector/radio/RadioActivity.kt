package com.xebolax.lector.radio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xebolax.lector.R
import com.xebolax.lector.databinding.ActivityRssBinding
import com.xebolax.lector.ui.Seccion
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.campoTexto
import com.xebolax.lector.ui.conEspera
import com.xebolax.lector.ui.configurarBarra
import com.xebolax.lector.ui.dp
import com.xebolax.lector.ui.mensaje
import com.xebolax.lector.ui.ponerMenuLateral
import com.xebolax.lector.ui.ponerPestanas
import com.xebolax.lector.ui.seguirMiniReproductor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Radio, una sección como Podcasts o Noticias: «Abrir menú» arriba y las
 * pestañas abajo: Favoritas, Explorar (el directorio mundial radio-browser.info
 * por listas, géneros, países e idiomas), Buscar, Historial (emisoras,
 * canciones y grabaciones) y Programar (despertador y grabaciones a una hora).
 * Lo que suena sigue en el mini reproductor de abajo al moverse por la app.
 */
class RadioActivity : AppCompatActivity() {

	companion object {
		/** Acceso directo de la pantalla de inicio: escuchar una emisora. */
		const val ACCION_ESCUCHAR = "com.xebolax.lector.radio.ESCUCHAR"
		const val ACCION_GRABACIONES = "com.xebolax.lector.radio.GRABACIONES"
		const val ACCION_PROGRAMAR = "com.xebolax.lector.radio.PROGRAMAR"
		const val EXTRA_EMISORA = "emisora"

		fun abrir(c: Context) = c.startActivity(Intent(c, RadioActivity::class.java))
	}

	private lateinit var b: ActivityRssBinding
	private val pestanas = RadioListaFragment.PESTANAS
	private val actualizaciones = com.xebolax.lector.actualizaciones.GestorActualizaciones(this)

	private val exportar = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
		uri ?: return@registerForActivityResult
		lifecycleScope.launch {
			val ok = withContext(Dispatchers.IO) {
				runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(Radio.exportarM3u(this@RadioActivity)) } }.isSuccess
			}
			avisar(getString(if (ok) R.string.radio_exportadas else R.string.error_exportar))
		}
	}

	private val importar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		uri ?: return@registerForActivityResult
		lifecycleScope.launch {
			val texto = withContext(Dispatchers.IO) { runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(2_000_000) } }.getOrNull() }
			val emisoras = texto?.let { Radio.leerLista(it) }.orEmpty()
			if (emisoras.isEmpty()) return@launch mensaje(getString(R.string.radio_importar), getString(R.string.radio_importar_vacio))
			val db = RadioBaseDatos.obtener(this@RadioActivity)
			var nuevas = 0
			withContext(Dispatchers.IO) {
				for ((e, carpeta) in emisoras) {
					if (db.esFavorita(e.uuid)) continue
					db.anadirFavorita(e, carpeta.trim().takeIf { it.isNotBlank() }?.let { db.crearCarpeta(it) } ?: "")
					nuevas++
				}
			}
			mensaje(getString(R.string.radio_importar), resources.getQuantityString(R.plurals.radio_importadas, nuevas, nuevas))
		}
	}

	private val permisoAvisos = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
		if (!concedido) mensaje(getString(R.string.radio_despertador), getString(R.string.radio_sin_permiso_avisos))
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityRssBinding.inflate(layoutInflater)
		configurarBarra(b.barra, getString(R.string.radio), conAtras = false)
		setContentView(ponerMenuLateral(b.root, b.barra, Seccion.RADIO, actualizaciones) { b.paginas.setCurrentItem(0, false) })
		seguirMiniReproductor(b.miniReproductor)
		b.paginas.adapter = object : FragmentStateAdapter(this) {
			override fun getItemCount() = pestanas.size
			override fun createFragment(posicion: Int): Fragment = RadioListaFragment.nuevo(pestanas[posicion])
		}
		ponerPestanas(b.barra, b.paginas, b.pestanas, pestanas.map { it.nombre to it.icono }, getString(R.string.radio))
		// La primera vez, si no hay favoritas, se empieza en Explorar
		if (savedInstanceState == null && RadioBaseDatos.obtener(this).favoritas().isEmpty()) {
			b.paginas.setCurrentItem(pestanas.indexOf(RadioListaFragment.Modo.EXPLORAR), false)
		}
		if (savedInstanceState == null) atender(intent)
		// Datos de las favoritas al día (votos, direcciones que cambian), como mucho una vez al día
		lifecycleScope.launch { runCatching { Radio.ponerAlDia(this@RadioActivity) } }
		RadioProgramador.programar(this)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		atender(intent)
	}

	private fun atender(i: Intent) {
		when (i.action) {
			ACCION_ESCUCHAR -> Emisora.deJson(i.getStringExtra(EXTRA_EMISORA).orEmpty())?.let { Radio.escuchar(this, it) }
			ACCION_GRABACIONES -> irA(RadioListaFragment.Modo.HISTORIAL) { it.verGrabaciones() }
			ACCION_PROGRAMAR -> {
				// Desde el reproductor («Despertarme con esta emisora»): se abre el despertador con ella
				val e = Emisora.deJson(i.getStringExtra(EXTRA_EMISORA).orEmpty())
				irA(RadioListaFragment.Modo.PROGRAMAR) { f -> e?.let { f.editarProgramacion(null, Programacion.DESPERTADOR, it) } }
			}
			Intent.ACTION_SEND -> {
				// Compartir una dirección con «Añadir a Radio»
				val texto = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
				val url = Regex("""https?://\S+""").find(texto)?.value
				if (url != null) anadirAMano(url = url, nombre = texto.substringBefore(url).trim().lines().firstOrNull().orEmpty())
			}
		}
		i.action = null
	}

	/** Pasa a una pestaña y hace algo en ella. */
	fun irA(modo: RadioListaFragment.Modo, hacer: (RadioListaFragment) -> Unit) {
		val i = pestanas.indexOf(modo)
		b.paginas.setCurrentItem(i, false)
		b.paginas.post { (supportFragmentManager.findFragmentByTag("f$i") as? RadioListaFragment)?.let(hacer) }
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menu.add(Menu.NONE, 1, Menu.NONE, R.string.radio_anadir_a_mano).setIcon(R.drawable.ic_anadir).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
		menu.add(Menu.NONE, 2, Menu.NONE, R.string.radio_poner_al_dia)
		menu.add(Menu.NONE, 3, Menu.NONE, R.string.radio_importar)
		menu.add(Menu.NONE, 4, Menu.NONE, R.string.radio_exportar)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			1 -> anadirAMano()
			2 -> lifecycleScope.launch {
				val n = runCatching { conEspera(getString(R.string.radio_poner_al_dia), getString(R.string.cargando)) { Radio.ponerAlDia(this@RadioActivity, forzar = true) } }
					.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
				n.getOrNull()?.let { avisar(resources.getQuantityString(R.plurals.radio_puestas_al_dia, it, it)) }
					?: mensaje(getString(R.string.radio_poner_al_dia), getString(R.string.radio_sin_directorio))
			}
			3 -> importar.launch(arrayOf("*/*"))
			4 -> exportar.launch("emisoras.m3u")
			else -> return super.onOptionsItemSelected(item)
		}
		return true
	}

	/** Pide permiso para los avisos (los usa el despertador) en el momento de poner uno. */
	fun pedirPermisoAvisos() {
		if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			permisoAvisos.launch(Manifest.permission.POST_NOTIFICATIONS)
		}
	}

	/** Añadir (o editar) una emisora que no está en el directorio, con su dirección. */
	fun anadirAMano(url: String = "", nombre: String = "", editar: Emisora? = null) {
		val (cNombre, campoNombre) = campoTexto(getString(R.string.nombre), editar?.nombre ?: nombre)
		val (cUrl, campoUrl) = campoTexto(getString(R.string.radio_direccion), editar?.url ?: url)
		campoUrl.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
		cUrl.helperText = getString(R.string.radio_direccion_ayuda)
		val (cGenero, campoGenero) = campoTexto(getString(R.string.radio_genero), editar?.etiquetas.orEmpty())
		val (cWeb, campoWeb) = campoTexto(getString(R.string.radio_web), editar?.web.orEmpty())
		campoWeb.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
		val formulario = android.widget.LinearLayout(this).apply {
			orientation = android.widget.LinearLayout.VERTICAL
			val m = dp(this@RadioActivity, 20)
			setPadding(m, m / 2, m, 0)
			listOf(cNombre, cUrl, cGenero, cWeb).forEach { addView(it) }
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(if (editar != null) R.string.radio_editar else R.string.radio_anadir_a_mano)
			.setView(android.widget.ScrollView(this).apply { addView(formulario) })
			.setPositiveButton(R.string.guardar) { _, _ ->
				val direccion = campoUrl.text.toString().trim()
				if (!direccion.startsWith("http", ignoreCase = true)) return@setPositiveButton mensaje(getString(R.string.radio_anadir_a_mano), getString(R.string.radio_direccion_mal))
				lifecycleScope.launch {
					val sonido = runCatching { conEspera(getString(R.string.radio_anadir_a_mano), getString(R.string.radio_comprobando)) { Radio.resolver(direccion) } }
						.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrDefault(direccion)
					val db = RadioBaseDatos.obtener(this@RadioActivity)
					val nueva = Emisora.propia(campoNombre.text.toString(), sonido, campoGenero.text.toString(), campoWeb.text.toString())
					val carpeta = editar?.let { db.favorita(it.uuid)?.carpeta }.orEmpty()
					if (editar != null && editar.uuid != nueva.uuid) db.quitarFavorita(editar.uuid)
					db.anadirFavorita(nueva, carpeta)
					avisar(getString(R.string.radio_guardada_favoritas, nueva.nombre))
				}
			}
			.setNegativeButton(R.string.cancelar, null)
			.show()
	}
}
