package com.xebolax.lector.compartidos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xebolax.lector.LectorApp
import com.xebolax.lector.R
import com.xebolax.lector.databinding.ActivityRssBinding
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Estado
import com.xebolax.lector.ui.BibliotecaActivity
import com.xebolax.lector.ui.Seccion
import com.xebolax.lector.ui.Teclado
import com.xebolax.lector.ui.TareasInicio
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.campoTexto
import com.xebolax.lector.ui.conEspera
import com.xebolax.lector.ui.configurarBarra
import com.xebolax.lector.ui.confirmar
import com.xebolax.lector.ui.dp
import com.xebolax.lector.ui.elegir
import com.xebolax.lector.ui.elegirUno
import com.xebolax.lector.ui.mensaje
import com.xebolax.lector.ui.pedirTexto
import com.xebolax.lector.ui.ponerMenuLateral
import com.xebolax.lector.ui.ponerPestanas
import com.xebolax.lector.ui.seguirMiniReproductor
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compartidos, una sección como las demás: lo que se envía a la app desde
 * otras apps o desde internet para que lo lea (un enlace, un texto, un
 * archivo), sin mezclarlo con la biblioteca. Pestañas abajo: Pendientes,
 * Leídos y Todo. Cada cosa se puede pasar a la biblioteca (con su tipo y
 * su carpeta) si se quiere conservar.
 *
 * También es la que recibe lo que se comparte o se abre con la app desde
 * otras (salvo que en Ajustes se elija mandarlo a la biblioteca, como antes).
 */
class CompartidosActivity : AppCompatActivity() {

	companion object {
		fun abrir(c: Context) = c.startActivity(Intent(c, CompartidosActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
	}

	private lateinit var b: ActivityRssBinding
	private val pestanas = CompartidosFragment.Modo.entries
	private val actualizaciones = com.xebolax.lector.actualizaciones.GestorActualizaciones(this)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// Lo que llega de otra app y en Ajustes va a la biblioteca: se le pasa tal cual
		if (savedInstanceState == null && esRecibido(intent) && Compartidos.vaABiblioteca(this)) {
			startActivity(Intent(intent).setClass(this, BibliotecaActivity::class.java))
			finish()
			return
		}
		b = ActivityRssBinding.inflate(layoutInflater)
		configurarBarra(b.barra, getString(R.string.compartidos), conAtras = false)
		setContentView(ponerMenuLateral(b.root, b.barra, Seccion.COMPARTIDOS, actualizaciones) { b.paginas.setCurrentItem(0, false) })
		seguirMiniReproductor(b.miniReproductor)
		b.paginas.adapter = object : FragmentStateAdapter(this) {
			override fun getItemCount() = pestanas.size
			override fun createFragment(posicion: Int): Fragment = CompartidosFragment.nuevo(pestanas[posicion])
		}
		ponerPestanas(b.barra, b.paginas, b.pestanas, pestanas.map { it.nombre to it.icono }, getString(R.string.compartidos))
		if (savedInstanceState == null) atender(intent)
		// Los leídos de hace tiempo se borran solos (si se eligió en Ajustes)
		LectorApp.ambito.launch { runCatching { Compartidos.limpiar(applicationContext) } }
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		if (esRecibido(intent) && Compartidos.vaABiblioteca(this)) {
			startActivity(Intent(intent).setClass(this, BibliotecaActivity::class.java))
			return
		}
		atender(intent)
	}

	/** Lo que llega de otra app (el texto seleccionado, «Leer con Lector Universal», se queda siempre aquí). */
	private fun esRecibido(i: Intent?) = i?.action in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)

	// --- Lo que llega de otras apps ---------------------------------------------------------

	private fun atender(i: Intent?) {
		i ?: return
		when (i.action) {
			Intent.ACTION_VIEW -> i.data?.let { recibirArchivo(it) }
			Intent.ACTION_SEND -> {
				@Suppress("DEPRECATION")
				val flujo = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
				val texto = i.getStringExtra(Intent.EXTRA_TEXT) ?: i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
				val asunto = i.getStringExtra(Intent.EXTRA_SUBJECT)
				when {
					flujo != null -> recibirArchivo(flujo)
					texto != null && Compartidos.enlaceDe(texto) != null -> recibirWeb(Compartidos.enlaceDe(texto)!!)
					!texto.isNullOrBlank() -> recibirTexto(texto, asunto)
				}
			}
			// Texto seleccionado en otra app: se guarda y se lee en voz alta al momento
			// Accesos directos del icono de la app
			Compartidos.ATAJO_COPIADO -> copiadoPendiente = true
			Compartidos.ATAJO_PENDIENTE -> leerPendiente()
			Compartidos.ATAJO_WEB -> pedirWeb()
			Intent.ACTION_PROCESS_TEXT -> {
				val texto = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
				if (!texto.isNullOrBlank()) lifecycleScope.launch {
					val id = Compartidos.guardarTexto(this@CompartidosActivity, texto, null)
					b.paginas.setCurrentItem(0, false)
					Compartidos.abrir(this@CompartidosActivity, id, leer = true)
				}
			}
			Intent.ACTION_SEND_MULTIPLE -> {
				@Suppress("DEPRECATION")
				val lista = i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
				if (lista.isNotEmpty()) lifecycleScope.launch {
					val ids = try {
						conEspera(getString(R.string.compartidos), getString(R.string.comp_guardando)) {
							lista.mapNotNull { runCatching { Compartidos.guardarArchivo(this@CompartidosActivity, it) }.getOrNull() }
						}
					} catch (e: kotlinx.coroutines.CancellationException) {
						throw e
					}
					TareasInicio.completar(this@CompartidosActivity, ids)
					avisar(resources.getQuantityString(R.plurals.comp_recibidos, ids.size, ids.size), mostrar = true)
				}
			}
		}
		i.action = null
	}

	/** Tras guardarlo: abrirlo, abrirlo y leerlo en voz alta, o solo avisar (Ajustes). */
	private fun recibido(id: Long) {
		b.paginas.setCurrentItem(0, false)
		if (Compartidos.alRecibir(this) == "guardar") {
			val titulo = BaseDatos.obtener(this).libro(id)?.tituloVisible.orEmpty()
			avisar(getString(R.string.comp_guardado_x, titulo), mostrar = true)
		} else Compartidos.abrir(this, id)
	}

	private fun recibirArchivo(uri: Uri) {
		lifecycleScope.launch {
			try {
				val id = conEspera(getString(R.string.compartidos), getString(R.string.comp_guardando)) { Compartidos.guardarArchivo(this@CompartidosActivity, uri) }
				TareasInicio.completar(this@CompartidosActivity, listOf(id))
				recibido(id)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				mensaje(getString(R.string.error), getString(R.string.error_abrir, e.message ?: ""))
			}
		}
	}

	/**
	 * Un enlace: si ya estaba (aunque venga con otras marcas de seguimiento), se
	 * avisa; si no hay conexión, se ofrece guardarlo para descargarlo después.
	 */
	private fun recibirWeb(url: String, forzar: Boolean = false) {
		lifecycleScope.launch {
			if (!forzar) {
				val ya = withContext(kotlinx.coroutines.Dispatchers.IO) {
					val db = BaseDatos.obtener(this@CompartidosActivity)
					db.porOrigen(com.xebolax.lector.ui.PaginaWeb.limpiarUrl(url)) ?: db.porOrigen(url)
				}
				if (ya != null && !com.xebolax.lector.compartidos.DescargasPendientes.pendiente(ya.ajustes)) return@launch yaLoTienes(ya, url)
			}
			try {
				val id = conEspera(getString(R.string.abrir_pagina_web), getString(R.string.descargando_pagina)) { Compartidos.guardarWeb(this@CompartidosActivity, url, forzar) }
				if (forzar) com.xebolax.lector.lectura.Abiertos.olvidar()
				recibido(id)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				// Sin conexión o la web no responde: no se pierde, se guarda el enlace y se descarga solo después
				MaterialAlertDialogBuilder(this@CompartidosActivity)
					.setTitle(R.string.abrir_pagina_web)
					.setMessage(getString(R.string.error_web, e.message ?: "") + "\n\n" + getString(R.string.comp_guardar_pendiente_pregunta))
					.setPositiveButton(R.string.comp_guardar_pendiente) { _, _ ->
						lifecycleScope.launch {
							runCatching { com.xebolax.lector.ui.PaginaWeb.guardarPendiente(this@CompartidosActivity, url) }
							avisar(getString(R.string.comp_pendiente_guardado), mostrar = true)
						}
					}
					.setNegativeButton(R.string.cancelar, null)
					.show()
			}
		}
	}

	/** Ese enlace ya estaba guardado: dónde y cómo va, con Abrir o Descargar otra vez. */
	private fun yaLoTienes(l: com.xebolax.lector.datos.Libro, url: String) {
		val donde = if (l.compartido) getString(R.string.comp_ya_en_compartidos, when (l.estado) {
			Estado.TERMINADO -> getString(R.string.comp_leido)
			Estado.LEYENDO -> getString(R.string.porcentaje_n, l.progreso.toInt())
			else -> getString(R.string.comp_sin_leer)
		}) else getString(R.string.comp_ya_en_biblioteca)
		MaterialAlertDialogBuilder(this)
			.setTitle(l.tituloVisible)
			.setMessage(donde)
			.setPositiveButton(R.string.abrir) { _, _ -> com.xebolax.lector.lectura.Abiertos.abrirPantalla(this, l) }
			.setNeutralButton(R.string.descargar_otra_vez) { _, _ -> recibirWeb(url, forzar = true) }
			.setNegativeButton(R.string.cancelar, null)
			.show()
	}

	private fun pedirWeb() {
		pedirTexto(getString(R.string.abrir_pagina_web), getString(R.string.direccion_web), teclado = Teclado.DIRECCION) { d ->
			if (d.isNotBlank()) recibirWeb(if (d.trim().startsWith("http")) d.trim() else "https://${d.trim()}")
		}
	}

	private fun leerPendiente() {
		lifecycleScope.launch {
			val lista = withContext(kotlinx.coroutines.Dispatchers.IO) { BaseDatos.obtener(this@CompartidosActivity).compartidos().filter { it.estado != Estado.TERMINADO }.reversed() }
			com.xebolax.lector.voz.ListaLectura.empezar(this@CompartidosActivity, lista)
		}
	}

	// --- El enlace copiado -----------------------------------------------------------------------

	/** Leer lo copiado (acceso directo): el portapapeles solo se puede leer con la ventana delante. */
	private var copiadoPendiente = false

	override fun onWindowFocusChanged(conFoco: Boolean) {
		super.onWindowFocusChanged(conFoco)
		if (!conFoco) return
		if (copiadoPendiente) {
			copiadoPendiente = false
			leerPortapapeles()
			return
		}
		ofrecerCopiado()
	}

	/** Al abrir Compartidos con un enlace nuevo copiado: «¿Guardar el enlace copiado?» (una vez por enlace; se quita en Ajustes). */
	private fun ofrecerCopiado() {
		if (!androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getBoolean("comp_ofrecer_copiado", true)) return
		val texto = runCatching {
			(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
		}.getOrNull()?.trim().orEmpty()
		if (texto.isEmpty() || texto.length > 400) return
		val url = Compartidos.enlaceDe(texto) ?: return
		val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
		if (prefs.getString("comp_ultimo_copiado", "") == url) return
		prefs.edit().putString("comp_ultimo_copiado", url).apply()
		lifecycleScope.launch {
			val ya = withContext(kotlinx.coroutines.Dispatchers.IO) {
				BaseDatos.obtener(this@CompartidosActivity).porOrigen(com.xebolax.lector.ui.PaginaWeb.limpiarUrl(url))
			}
			if (ya != null) return@launch
			MaterialAlertDialogBuilder(this@CompartidosActivity)
				.setTitle(R.string.comp_enlace_copiado_titulo)
				.setMessage(getString(R.string.comp_enlace_copiado_pregunta, url))
				.setPositiveButton(R.string.guardar) { _, _ -> recibirWeb(url) }
				.setNegativeButton(R.string.ahora_no, null)
				.show()
		}
	}

	/** leer: lo pidió el usuario con «Leer» (portapapeles, escribir un texto): se lee en voz alta ya. */
	private fun recibirTexto(texto: String, titulo: String? = null, leer: Boolean = false) {
		lifecycleScope.launch {
			val id = Compartidos.guardarTexto(this@CompartidosActivity, texto, titulo)
			if (leer) {
				b.paginas.setCurrentItem(0, false)
				Compartidos.abrir(this@CompartidosActivity, id, leer = true)
			} else recibido(id)
		}
	}

	// --- Menú de la barra ----------------------------------------------------------------------

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menu.add(Menu.NONE, 1, Menu.NONE, R.string.comp_anadir).setIcon(R.drawable.ic_anadir).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
		menu.add(Menu.NONE, 4, Menu.NONE, R.string.seguido_compartidos)
		menu.add(Menu.NONE, 5, Menu.NONE, R.string.aj_comp_orden)
		menu.add(Menu.NONE, 2, Menu.NONE, R.string.comp_borrar_leidos)
		menu.add(Menu.NONE, 3, Menu.NONE, R.string.comp_borrar_todo)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			1 -> elegir(getString(R.string.comp_anadir), listOf(
				getString(R.string.abrir_pagina_web) to { pedirWeb() },
				getString(R.string.leer_portapapeles) to { leerPortapapeles() },
				getString(R.string.comp_escribir_texto) to { escribirTexto() },
			))
			// Leer seguido lo pendiente, del más antiguo al más nuevo
			4 -> leerPendiente()
			5 -> {
				val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
				val valores = resources.getStringArray(R.array.comp_orden_valores)
				elegirUno(getString(R.string.aj_comp_orden), resources.getStringArray(R.array.comp_orden_nombres).toList(), valores.indexOf(prefs.getString("comp_orden", "nuevos"))) { i ->
					prefs.edit().putString("comp_orden", valores[i]).apply()
					supportFragmentManager.fragments.filterIsInstance<CompartidosFragment>().forEach { it.recargar() }
				}
			}
			2 -> {
				val db = BaseDatos.obtener(this)
				val leidos = db.compartidos().filter { it.estado == Estado.TERMINADO }
				if (leidos.isEmpty()) return avisar(getString(R.string.comp_sin_leidos)).let { true }
				confirmar(resources.getQuantityString(R.plurals.comp_confirmar_borrar_leidos, leidos.size, leidos.size), getString(R.string.borrar)) {
					lifecycleScope.launch {
						withContext(kotlinx.coroutines.Dispatchers.IO) { leidos.forEach { Compartidos.borrar(this@CompartidosActivity, it) } }
						avisar(resources.getQuantityString(R.plurals.comp_borrados, leidos.size, leidos.size))
					}
				}
			}
			3 -> {
				val todos = BaseDatos.obtener(this).compartidos()
				if (todos.isEmpty()) return avisar(getString(R.string.comp_vacio_corto)).let { true }
				confirmar(resources.getQuantityString(R.plurals.comp_confirmar_borrar_todo, todos.size, todos.size), getString(R.string.borrar)) {
					lifecycleScope.launch {
						withContext(kotlinx.coroutines.Dispatchers.IO) { todos.forEach { Compartidos.borrar(this@CompartidosActivity, it) } }
						avisar(resources.getQuantityString(R.plurals.comp_borrados, todos.size, todos.size))
					}
				}
			}
			else -> return super.onOptionsItemSelected(item)
		}
		return true
	}

	private fun leerPortapapeles() {
		val portapapeles = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
		val texto = portapapeles.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
		if (texto.isBlank()) return avisar(getString(R.string.portapapeles_vacio))
		Compartidos.enlaceDe(texto)?.takeIf { texto.trim().length < 400 }?.let { return recibirWeb(it) }
		recibirTexto(texto, leer = true)
	}

	/** Escribir o pegar un texto largo para que la app lo lea. */
	private fun escribirTexto() {
		val (contenedor, campo) = campoTexto(getString(R.string.comp_texto))
		campo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
		campo.minLines = 4
		campo.maxLines = 12
		val formulario = android.widget.LinearLayout(this).apply {
			orientation = android.widget.LinearLayout.VERTICAL
			val m = dp(this@CompartidosActivity, 20)
			setPadding(m, m / 2, m, 0)
			addView(contenedor)
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.comp_escribir_texto)
			.setView(android.widget.ScrollView(this).apply { addView(formulario) })
			.setPositiveButton(R.string.comp_leer) { _, _ ->
				val t = campo.text.toString()
				if (t.isNotBlank()) recibirTexto(t, leer = true)
			}
			.setNegativeButton(R.string.cancelar, null)
			.show()
		campo.requestFocus()
	}
}
