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
import com.xebolax.lector.ui.mensaje
import com.xebolax.lector.ui.pedirTexto
import com.xebolax.lector.ui.ponerMenuLateral
import com.xebolax.lector.ui.ponerPestanas
import com.xebolax.lector.ui.seguirMiniReproductor
import kotlinx.coroutines.launch

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

	private fun recibirWeb(url: String) {
		lifecycleScope.launch {
			try {
				val id = conEspera(getString(R.string.abrir_pagina_web), getString(R.string.descargando_pagina)) { Compartidos.guardarWeb(this@CompartidosActivity, url) }
				recibido(id)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				mensaje(getString(R.string.abrir_pagina_web), getString(R.string.error_web, e.message ?: ""))
			}
		}
	}

	private fun recibirTexto(texto: String, titulo: String? = null) {
		lifecycleScope.launch {
			val id = Compartidos.guardarTexto(this@CompartidosActivity, texto, titulo)
			recibido(id)
		}
	}

	// --- Menú de la barra ----------------------------------------------------------------------

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menu.add(Menu.NONE, 1, Menu.NONE, R.string.comp_anadir).setIcon(R.drawable.ic_anadir).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
		menu.add(Menu.NONE, 4, Menu.NONE, R.string.seguido_compartidos)
		menu.add(Menu.NONE, 2, Menu.NONE, R.string.comp_borrar_leidos)
		menu.add(Menu.NONE, 3, Menu.NONE, R.string.comp_borrar_todo)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			1 -> elegir(getString(R.string.comp_anadir), listOf(
				getString(R.string.abrir_pagina_web) to {
					pedirTexto(getString(R.string.abrir_pagina_web), getString(R.string.direccion_web), teclado = Teclado.DIRECCION) { d ->
						if (d.isNotBlank()) recibirWeb(if (d.trim().startsWith("http")) d.trim() else "https://${d.trim()}")
					}
				},
				getString(R.string.leer_portapapeles) to { leerPortapapeles() },
				getString(R.string.comp_escribir_texto) to { escribirTexto() },
			))
			// Leer seguido lo pendiente, del más antiguo al más nuevo
			4 -> com.xebolax.lector.voz.ListaLectura.empezar(this, BaseDatos.obtener(this).compartidos().filter { it.estado != Estado.TERMINADO }.reversed())
			2 -> {
				val db = BaseDatos.obtener(this)
				val leidos = db.compartidos().filter { it.estado == Estado.TERMINADO }
				if (leidos.isEmpty()) return avisar(getString(R.string.comp_sin_leidos)).let { true }
				confirmar(resources.getQuantityString(R.plurals.comp_confirmar_borrar_leidos, leidos.size, leidos.size), getString(R.string.borrar)) {
					leidos.forEach { Compartidos.borrar(this, it) }
					avisar(resources.getQuantityString(R.plurals.comp_borrados, leidos.size, leidos.size))
				}
			}
			3 -> {
				val todos = BaseDatos.obtener(this).compartidos()
				if (todos.isEmpty()) return avisar(getString(R.string.comp_vacio_corto)).let { true }
				confirmar(resources.getQuantityString(R.plurals.comp_confirmar_borrar_todo, todos.size, todos.size), getString(R.string.borrar)) {
					todos.forEach { Compartidos.borrar(this, it) }
					avisar(resources.getQuantityString(R.plurals.comp_borrados, todos.size, todos.size))
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
		recibirTexto(texto)
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
				if (t.isNotBlank()) recibirTexto(t)
			}
			.setNegativeButton(R.string.cancelar, null)
			.show()
		campo.requestFocus()
	}
}
