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
import kotlinx.coroutines.ensureActive
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
		/** Editar una emisora propia (desde el reproductor o la Portada, fuera de la sección). */
		const val ACCION_EDITAR = "com.xebolax.lector.radio.EDITAR"
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
			val db = RadioBaseDatos.obtener(this@RadioActivity)
			// Leer la lista y guardar sus emisoras puede tardar: con espera y Cancelar. null = la lista no trae emisoras
			val nuevas = try {
				conEspera(getString(R.string.radio_importar), getString(R.string.audio_importando)) {
					withContext(Dispatchers.IO) {
						val texto = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(2_000_000) } }.getOrNull()
						val emisoras = texto?.let { Radio.leerLista(it) }.orEmpty()
						if (emisoras.isEmpty()) return@withContext null
						var n = 0
						for ((e, carpeta) in emisoras) {
							ensureActive()
							if (db.esFavorita(e.uuid)) continue
							db.anadirFavorita(e, carpeta.trim().takeIf { it.isNotBlank() }?.let { db.crearCarpeta(it) } ?: "")
							n++
						}
						n
					}
				}
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} ?: return@launch mensaje(getString(R.string.radio_importar), getString(R.string.radio_importar_vacio))
			mensaje(getString(R.string.radio_importar), resources.getQuantityString(R.plurals.radio_importadas, nuevas, nuevas))
		}
	}

	private val permisoAvisos = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
		if (!concedido) mensaje(getString(R.string.radio_despertador), getString(R.string.radio_sin_permiso_avisos))
	}

	/** La pestaña con la que se abre la sección: Favoritas o, si no hay, Explorar. */
	private var pestanaInicio = 0

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityRssBinding.inflate(layoutInflater)
		configurarBarra(b.barra, getString(R.string.radio), conAtras = false)
		setContentView(ponerMenuLateral(b.root, b.barra, Seccion.RADIO, actualizaciones) { b.paginas.setCurrentItem(pestanaInicio, false) })
		seguirMiniReproductor(b.miniReproductor)
		b.paginas.adapter = object : FragmentStateAdapter(this) {
			override fun getItemCount() = pestanas.size
			override fun createFragment(posicion: Int): Fragment = RadioListaFragment.nuevo(pestanas[posicion])
		}
		ponerPestanas(b.barra, b.paginas, b.pestanas, pestanas.map { it.nombre to it.icono }, getString(R.string.radio)) { pestanaInicio }
		// Si no hay favoritas, se empieza en Explorar (y Atrás desde ella vuelve a la Portada)
		if (RadioBaseDatos.obtener(this).favoritas().isEmpty()) pestanaInicio = pestanas.indexOf(RadioListaFragment.Modo.EXPLORAR)
		if (savedInstanceState == null && pestanaInicio != 0) b.paginas.setCurrentItem(pestanaInicio, false)
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
		// Por el alias que ven otras apps (Compartir) solo se acepta Compartir: no se escucha ni se programa lo que mande otra app
		if (i.component?.className?.endsWith("AnadirARadio") == true && i.action != Intent.ACTION_SEND) return
		when (i.action) {
			ACCION_ESCUCHAR -> Emisora.deJson(i.getStringExtra(EXTRA_EMISORA).orEmpty())?.let { Radio.escuchar(this, it) }
			ACCION_GRABACIONES -> irA(RadioListaFragment.Modo.HISTORIAL) { it.verGrabaciones() }
			ACCION_PROGRAMAR -> {
				// Desde el reproductor («Despertarme con esta emisora»): se abre el despertador con ella
				val e = Emisora.deJson(i.getStringExtra(EXTRA_EMISORA).orEmpty())
				irA(RadioListaFragment.Modo.PROGRAMAR) { f -> e?.let { f.editarProgramacion(null, Programacion.DESPERTADOR, it) } }
			}
			ACCION_EDITAR -> Emisora.deJson(i.getStringExtra(EXTRA_EMISORA).orEmpty())?.takeIf { it.propia }?.let { anadirAMano(editar = it) }
			Intent.ACTION_SEND -> {
				// Compartir una dirección con «Añadir a Radio»
				val texto = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
				val url = Regex("""https?://\S+""").find(texto)?.value
				if (url != null) anadirAMano(url = url, nombre = texto.substringBefore(url).trim().lines().firstOrNull().orEmpty())
			}
		}
		i.action = null
	}

	/** Lo que se encontró en una dirección escrita a mano. */
	private sealed class Analisis {
		class Encontrada(val sonda: Flujos.Sonda, val desdeWeb: Boolean) : Analisis()
		class Varias(val candidatos: List<Flujos.Candidato>) : Analisis()
		object NadaEnWeb : Analisis()
		class Fallo(val motivo: String) : Analisis()
	}

	/** Comprueba la dirección: sonido, lista o página web (y, en ese caso, busca el sonido en ella). */
	private suspend fun analizar(direccion: String): Analisis {
		val s = try {
			Flujos.sondear(direccion)
		} catch (e: kotlinx.coroutines.CancellationException) {
			throw e
		} catch (e: Flujos.ErrorFlujo) {
			return Analisis.Fallo(e.motivo)
		} catch (e: Exception) {
			return Analisis.Fallo("red")
		}
		if (!s.web) return Analisis.Encontrada(s, desdeWeb = false)
		val candidatos = Flujos.buscarEnWeb(direccion)
		return when (candidatos.size) {
			0 -> Analisis.NadaEnWeb
			1 -> Analisis.Encontrada(candidatos[0].sonda, desdeWeb = true)
			else -> Analisis.Varias(candidatos)
		}
	}

	/** El motivo de un fallo dicho con palabras. */
	private fun motivo(m: String): String = when {
		m.startsWith("http:") -> {
			val codigo = m.removePrefix("http:").toIntOrNull() ?: 0
			getString(if (codigo in 400..499) R.string.radio_error_rechaza else R.string.radio_error_servidor, codigo)
		}
		m == "ssl" -> getString(R.string.radio_motivo_ssl)
		m == "no_estandar" -> getString(R.string.radio_motivo_no_estandar)
		m == "formato" -> getString(R.string.radio_motivo_formato)
		m == "lista" -> getString(R.string.radio_motivo_lista)
		m == "redirecciones" -> getString(R.string.radio_motivo_redirecciones)
		else -> getString(R.string.radio_motivo_red)
	}

	/** Pasa a una pestaña y hace algo en ella. */
	fun irA(modo: RadioListaFragment.Modo, hacer: (RadioListaFragment) -> Unit) {
		val i = pestanas.indexOf(modo)
		b.paginas.setCurrentItem(i, false)
		b.paginas.post { enPestana(i, 20, hacer) }
	}

	/** ViewPager2 crea el fragmento de la pestaña al dibujarse: si aún no existe, se espera un poco (si no, se perdía lo pedido). */
	private fun enPestana(i: Int, intentos: Int, hacer: (RadioListaFragment) -> Unit) {
		val f = supportFragmentManager.findFragmentByTag("f$i") as? RadioListaFragment
		if (f != null && f.view != null) hacer(f)
		else if (intentos > 0 && !isFinishing) b.paginas.postDelayed({ enPestana(i, intentos - 1, hacer) }, 50)
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
		val dialogo = MaterialAlertDialogBuilder(this)
			.setTitle(if (editar != null) R.string.radio_editar else R.string.radio_anadir_a_mano)
			.setView(android.widget.ScrollView(this).apply { addView(formulario) })
			.setPositiveButton(R.string.guardar, null)
			.setNegativeButton(R.string.cancelar, null)
			.show()
		dialogo.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
				var direccion = campoUrl.text.toString().trim()
				val mal = getString(R.string.radio_direccion_mal)
				// La dirección no vale: se dice en el campo (TalkBack lo lee) y la ventana sigue abierta con lo escrito
				if (direccion.isBlank() || direccion.contains(' ')) {
					(cUrl as? com.google.android.material.textfield.TextInputLayout)?.error = mal
					campoUrl.requestFocus()
					return@setOnClickListener
				}
				// «radioarrecife.com» o «icy://…» también valen
				direccion = direccion.replaceFirst(Regex("(?i)^(icy|mms|mmsh)://"), "http://")
				if (!direccion.contains("://")) direccion = "https://$direccion"
				if (!direccion.startsWith("http", ignoreCase = true)) {
					(cUrl as? com.google.android.material.textfield.TextInputLayout)?.error = mal
					campoUrl.requestFocus()
					return@setOnClickListener
				}
				dialogo.dismiss()
				lifecycleScope.launch {
					val r = try {
						conEspera(getString(R.string.radio_anadir_a_mano), getString(R.string.radio_comprobando)) { analizar(direccion) }
					} catch (e: kotlinx.coroutines.CancellationException) {
						throw e
					}
					fun guardar(s: Flujos.Sonda?, desdeWeb: Boolean) {
						val db = RadioBaseDatos.obtener(this@RadioActivity)
						val nueva = Emisora.propia(
							// El escrito, el que da el servidor o, si no, el nombre del servidor (mejor que la dirección entera)
							nombre = campoNombre.text.toString().ifBlank { s?.nombre.orEmpty() }.ifBlank {
								runCatching { java.net.URL(s?.url ?: direccion).host.removePrefix("www.") }.getOrDefault("")
							},
							url = s?.url ?: direccion,
							genero = campoGenero.text.toString().ifBlank { s?.generos.orEmpty() },
							web = campoWeb.text.toString().ifBlank { if (desdeWeb) direccion else s?.webEmisora.orEmpty() },
							hls = s?.hls,
						)
						lifecycleScope.launch {
							withContext(Dispatchers.IO) {
								// Al editarla (su id sale de la dirección) conserva su carpeta, su orden, su historial y lo programado con ella
								if (editar != null) db.cambiarEmisora(editar.uuid, nueva)
								if (!db.esFavorita(nueva.uuid)) db.anadirFavorita(nueva)
							}
							avisar(getString(R.string.radio_guardada_favoritas, nueva.nombre))
						}
					}
					when (r) {
						is Analisis.Encontrada -> guardar(r.sonda, r.desdeWeb)
						is Analisis.Varias -> com.google.android.material.dialog.MaterialAlertDialogBuilder(this@RadioActivity)
							.setTitle(R.string.radio_elige_sonido)
							.setItems(r.candidatos.map { it.etiqueta }.toTypedArray()) { _, i -> guardar(r.candidatos[i].sonda, true) }
							.setNegativeButton(R.string.cancelar, null)
							.show()
						is Analisis.NadaEnWeb -> mensaje(getString(R.string.radio_anadir_a_mano), getString(R.string.radio_no_encontrada_en_web))
						is Analisis.Fallo -> com.google.android.material.dialog.MaterialAlertDialogBuilder(this@RadioActivity)
							.setTitle(R.string.radio_anadir_a_mano)
							.setMessage(getString(R.string.radio_no_comprobada, motivo(r.motivo)))
							.setPositiveButton(R.string.radio_guardar_igualmente) { _, _ -> guardar(null, false) }
							.setNegativeButton(R.string.cancelar, null)
							.show()
					}
				}
		}
	}
}
