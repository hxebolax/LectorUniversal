package com.xebolax.lector.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationBarView
import com.xebolax.lector.R
import com.xebolax.lector.actualizaciones.GestorActualizaciones
import com.xebolax.lector.biblioteca.Importador
import com.xebolax.lector.databinding.ActivityPrincipalBinding
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Orden
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.formatos.ContextoLectura
import com.xebolax.lector.formatos.Formato
import com.xebolax.lector.formatos.Fuente
import com.xebolax.lector.formatos.LectorHtml
import com.xebolax.lector.lectura.Abiertos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ventana principal, la sección Biblioteca: pestañas abajo (Leyendo,
 * Biblioteca, Marcadores y Catálogos, las bibliotecas en línea) y el menú
 * lateral (Secciones.kt) para ir a Podcasts, Noticias y lo general. Las pestañas se ocultan y muestran, no se recrean.
 * También recibe los archivos que se abren con la app desde otras.
 */
class PrincipalActivity : AppCompatActivity() {

	companion object {
		private const val ESTADO_PESTANA = "pestana"
	}

	private lateinit var b: ActivityPrincipalBinding
	private var pestana = R.id.pestana_recientes
	val actualizaciones = GestorActualizaciones(this)

	private val elegirArchivos = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
		if (uris.isNotEmpty()) anadirArchivos(uris)
	}

	private val elegirCarpeta = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
		uri?.let { preguntarCarpeta(it) }
	}

	private val permisoNotificaciones = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

	/** Escáner de documentos de Google: fotos de las hojas, recortadas y enderezadas. */
	private val escanear = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
		if (r.resultCode != RESULT_OK) return@registerForActivityResult
		val resultado = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(r.data) ?: return@registerForActivityResult
		val imagenes = resultado.pages?.map { it.imageUri }.orEmpty()
		if (imagenes.isEmpty()) return@registerForActivityResult
		reconocerEscaneado(imagenes)
	}

	/** Escáner con guía de voz (sin servicios de Google): devuelve el texto de cada hoja. */
	private val escanearConGuia = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
		val paginas = com.xebolax.lector.ocr.EscanerActivity.paginas
		com.xebolax.lector.ocr.EscanerActivity.paginas = emptyList()
		if (r.resultCode == RESULT_OK && paginas.isNotEmpty()) guardarEscaneado(paginas)
	}

	/** Atrás: en una subcarpeta sube; en otra pestaña vuelve a Leyendo; en Leyendo sale. */
	private val atras = object : OnBackPressedCallback(true) {
		override fun handleOnBackPressed() {
			when {
				pestana == R.id.pestana_biblioteca && biblioteca?.subir() == true -> Unit
				pestana != R.id.pestana_recientes -> b.navegacion.selectedItemId = R.id.pestana_recientes
				else -> {
					isEnabled = false
					onBackPressedDispatcher.onBackPressed()
					isEnabled = true
				}
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityPrincipalBinding.inflate(layoutInflater)
		onBackPressedDispatcher.addCallback(this, atras)
		setSupportActionBar(b.barra)
		setContentView(ponerMenuLateral(b.root, b.barra, Seccion.BIBLIOTECA, actualizaciones) { b.navegacion.selectedItemId = R.id.pestana_recientes })
		seguirMiniReproductor(b.miniReproductor)
		ajustarPestanas(resources.configuration)

		pestana = savedInstanceState?.getInt(ESTADO_PESTANA, R.id.pestana_recientes) ?: R.id.pestana_recientes
		b.navegacion.selectedItemId = pestana
		mostrarPestana(pestana)
		b.navegacion.setOnItemSelectedListener { item ->
			mostrarPestana(item.itemId)
			true
		}
		b.navegacion.setOnItemReselectedListener { }

		if (savedInstanceState == null) {
			atenderIntent(intent)
			actualizaciones.alIniciar()
			TareasInicio.alAbrir(this)
			if (Build.VERSION.SDK_INT >= 33) permisoNotificaciones.launch(android.Manifest.permission.POST_NOTIFICATIONS)
			// Abrir el último libro al iniciar (Ajustes)
			if (Preferencias(this).abrirUltimo && intent?.action == Intent.ACTION_MAIN) {
				BaseDatos.obtener(this).ultimoAbierto()?.let { Abiertos.abrirPantalla(this, it) }
			}
		}
	}

	override fun onConfigurationChanged(nueva: Configuration) {
		super.onConfigurationChanged(nueva)
		ajustarPestanas(nueva)
	}

	/** En horizontal las pestañas son solo iconos (TalkBack sigue leyendo su nombre). */
	private fun ajustarPestanas(configuracion: Configuration) {
		b.navegacion.labelVisibilityMode =
			if (configuracion.orientation == Configuration.ORIENTATION_LANDSCAPE) NavigationBarView.LABEL_VISIBILITY_UNLABELED
			else NavigationBarView.LABEL_VISIBILITY_LABELED
	}

	override fun onSaveInstanceState(estado: Bundle) {
		super.onSaveInstanceState(estado)
		estado.putInt(ESTADO_PESTANA, pestana)
	}

	override fun onStart() {
		super.onStart()
		// Sincronizar con el servidor (si está configurado): trae lo leído en otros dispositivos
		com.xebolax.lector.LectorApp.ambito.launch { com.xebolax.lector.sincronizacion.Sincronizador.siToca(applicationContext, 60_000L) }
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		atenderIntent(intent)
	}

	// --- Pestañas ----------------------------------------------------------------

	private fun etiqueta(id: Int) = when (id) {
		R.id.pestana_biblioteca -> "biblioteca"
		R.id.pestana_marcadores -> "marcadores"
		R.id.pestana_catalogos -> "catalogos"
		else -> "recientes"
	}

	private fun crear(id: Int): Fragment = when (id) {
		R.id.pestana_biblioteca -> LibrosFragment.nuevo(LibrosFragment.Modo.BIBLIOTECA)
		R.id.pestana_marcadores -> MarcadoresFragment()
		R.id.pestana_catalogos -> com.xebolax.lector.catalogo.CatalogoFragment()
		else -> LibrosFragment.nuevo(LibrosFragment.Modo.RECIENTES)
	}

	private fun mostrarPestana(id: Int) {
		pestana = id
		val fm = supportFragmentManager
		val transaccion = fm.beginTransaction().setReorderingAllowed(true)
		fm.fragments.forEach { if (it.tag != etiqueta(id) && !it.isHidden) transaccion.hide(it) }
		val destino = fm.findFragmentByTag(etiqueta(id))
		if (destino == null) transaccion.add(R.id.contenedor_pestanas, crear(id), etiqueta(id)) else transaccion.show(destino)
		transaccion.commitNow()
		val nombre = b.navegacion.menu.findItem(id).title.toString()
		// En Catálogos, el catálogo abierto
		val catalogo = (fm.findFragmentByTag(etiqueta(id)) as? com.xebolax.lector.catalogo.CatalogoFragment)?.titulo()
		b.barra.title = catalogo ?: if (id == R.id.pestana_recientes) getString(R.string.app_name) else nombre
		title = b.barra.title
		// Cada pestaña es un panel: TalkBack anuncia su nombre al aparecer
		fm.findFragmentByTag(etiqueta(id))?.view?.let {
			if (ViewCompat.getAccessibilityPaneTitle(it) == null) ViewCompat.setAccessibilityPaneTitle(it, getString(R.string.pestana_de, nombre))
		}
		invalidateOptionsMenu()
	}

	// --- Añadir libros --------------------------------------------------------------

	private fun anadirArchivos(uris: List<Uri>) {
		lifecycleScope.launch {
			val r = conEspera(getString(R.string.anadir_libros), getString(R.string.anadiendo_libros)) { Importador.anadirArchivos(this@PrincipalActivity, uris, carpetaDestino()) }
			resultado(r)
			// Uno solo: se abre directamente
			if (uris.size == 1) {
				val id = r.anadidos.firstOrNull() ?: BaseDatos.obtener(this@PrincipalActivity).libroPorUri(uris[0].toString())?.id
				id?.let { Abiertos.abrirPantalla(this@PrincipalActivity, it) }
			}
		}
	}

	/** Carpeta de la biblioteca abierta en la pestaña Carpetas (ahí van los libros nuevos). */
	private fun carpetaDestino(): Long? = biblioteca?.takeIf { it.porCarpetas }?.carpetaActual

	private val biblioteca: LibrosFragment?
		get() = supportFragmentManager.findFragmentByTag(etiqueta(R.id.pestana_biblioteca)) as? LibrosFragment

	private fun preguntarCarpeta(arbol: Uri) {
		elegir(getString(R.string.anadir_carpeta), listOf(
			getString(R.string.carpeta_con_subcarpetas) to { anadirCarpeta(arbol, crearCarpetas = true) },
			getString(R.string.carpeta_sin_subcarpetas) to { anadirCarpeta(arbol, crearCarpetas = false) },
			getString(R.string.carpeta_vigilar) to {
				val p = Preferencias(this)
				p.carpetasVigiladas = (p.carpetasVigiladas + arbol.toString()).distinct()
				anadirCarpeta(arbol, crearCarpetas = true)
			},
		))
	}

	private fun anadirCarpeta(arbol: Uri, crearCarpetas: Boolean) {
		lifecycleScope.launch {
			val r = try {
				conEspera(getString(R.string.anadir_carpeta), getString(R.string.buscando_libros)) {
					Importador.anadirCarpeta(this@PrincipalActivity, arbol, carpetaDestino(), crearCarpetas)
				}
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				mensaje(getString(R.string.anadir_carpeta), getString(R.string.error_carpeta, e.message ?: ""))
				return@launch
			}
			resultado(r)
		}
	}

	private fun resultado(r: Importador.Resultado) {
		Sonidos.sonar(this, Sonidos.Tipo.LISTO)
		val texto = when {
			r.anadidos.isEmpty() && r.yaEstaban > 0 -> getString(R.string.ya_estaban, r.yaEstaban)
			r.anadidos.isEmpty() -> getString(R.string.ningun_libro)
			else -> resources.getQuantityString(R.plurals.libros_anadidos, r.anadidos.size, r.anadidos.size)
		}
		avisar(texto, mostrar = true)
		if (r.anadidos.isNotEmpty()) TareasInicio.completar(this, r.anadidos)
	}

	private fun revisar() {
		lifecycleScope.launch {
			val n = conEspera(getString(R.string.revisar_carpetas), getString(R.string.buscando_libros)) { Importador.revisarVigiladas(this@PrincipalActivity) }
			avisar(if (n == 0) getString(R.string.sin_libros_nuevos) else resources.getQuantityString(R.plurals.libros_anadidos, n, n), mostrar = true)
		}
	}

	/** Descarga una página web, guarda su contenido principal y la abre como un libro más. */
	private fun abrirWeb(direccion: String? = null) {
		if (direccion == null) {
			pedirTexto(getString(R.string.abrir_pagina_web), getString(R.string.direccion_web), teclado = Teclado.DIRECCION) { abrirWeb(it) }
			return
		}
		val url = if (direccion.startsWith("http")) direccion else "https://$direccion"
		lifecycleScope.launch {
			try {
				val id = conEspera(getString(R.string.abrir_pagina_web), getString(R.string.descargando_pagina)) { PaginaWeb.guardar(this@PrincipalActivity, url) }
				Abiertos.abrirPantalla(this@PrincipalActivity, id)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				mensaje(getString(R.string.abrir_pagina_web), getString(R.string.error_web, e.message ?: ""))
			}
		}
	}

	/** ¿Están los servicios de Google Play? (sin ellos no hay escáner de Google) */
	private fun hayPlayServices() = runCatching { packageManager.getApplicationInfo("com.google.android.gms", 0).enabled }.getOrDefault(false)

	/** Escanear: con guía de voz (siempre) o con el escáner de Google (si el móvil tiene sus servicios). */
	private fun elegirEscaner() {
		if (!hayPlayServices()) return abrirEscanerConGuia()
		elegir(getString(R.string.escanear_documento), listOf(
			getString(R.string.esc_con_guia) to { abrirEscanerConGuia() },
			getString(R.string.esc_de_google) to { abrirEscaner() },
		))
	}

	private fun abrirEscanerConGuia() = escanearConGuia.launch(Intent(this, com.xebolax.lector.ocr.EscanerActivity::class.java))

	private fun abrirEscaner() {
		val opciones = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
			.setGalleryImportAllowed(true)
			.setPageLimit(100)
			.setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
			.setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
			.build()
		com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(opciones).getStartScanIntent(this)
			.addOnSuccessListener { emisor -> escanear.launch(androidx.activity.result.IntentSenderRequest.Builder(emisor).build()) }
			.addOnFailureListener { e -> mensaje(getString(R.string.escanear_documento), getString(R.string.escaner_no_disponible, e.message.orEmpty())) }
	}

	/** Guarda como documento nuevo el texto de las hojas escaneadas y lo abre. */
	private fun guardarEscaneado(paginas: List<String>, carpeta: Long? = carpetaDestino()) {
		lifecycleScope.launch {
			val ahora = java.util.Date()
			val titulo = getString(R.string.escaneado_el, android.text.format.DateFormat.getMediumDateFormat(this@PrincipalActivity).format(ahora) +
				" " + android.text.format.DateFormat.getTimeFormat(this@PrincipalActivity).format(ahora))
			val id = withContext(Dispatchers.IO) { com.xebolax.lector.ocr.Ocr.guardar(this@PrincipalActivity, titulo, paginas, carpeta) }
			avisar(getString(R.string.ocr_hecho))
			Abiertos.abrirPantalla(this@PrincipalActivity, id)
		}
	}

	/** Reconoce el texto de las hojas escaneadas y lo guarda como documento nuevo. */
	private fun reconocerEscaneado(imagenes: List<Uri>) {
		val carpeta = carpetaDestino()
		lifecycleScope.launch {
			try {
				val paginas = conEspera(getString(R.string.escanear_documento), getString(R.string.ocr_reconociendo)) {
					imagenes.mapIndexed { i, uri ->
						if (imagenes.size > 1) avisar(getString(R.string.ocr_progreso, i + 1, imagenes.size), mostrar = false)
						val mapa = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } }
							?: return@mapIndexed ""
						com.xebolax.lector.ocr.Ocr.reconocer(this@PrincipalActivity, mapa)
					}
				}
				guardarEscaneado(paginas, carpeta)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				mensaje(getString(R.string.escanear_documento), e.message ?: getString(R.string.ocr_sin_texto))
			}
		}
	}

	private fun leerPortapapeles() {
		val portapapeles = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
		val texto = portapapeles.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
		if (texto.isBlank()) return avisar(getString(R.string.portapapeles_vacio))
		guardarTexto(texto)
	}

	/** Guarda un texto (portapapeles, compartido) como documento de la biblioteca y lo abre. */
	private fun guardarTexto(texto: String) {
		val carpeta = File(filesDir, "textos").apply { mkdirs() }
		val titulo = texto.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: getString(R.string.texto)
		val archivo = File(carpeta, "texto_${System.currentTimeMillis()}.txt")
		archivo.writeText(texto)
		val db = BaseDatos.obtener(this)
		val id = db.anadirLibro(Uri.fromFile(archivo).toString(), archivo.name, titulo, "", Formato.TXT.name, false, archivo.length(), null)
		Abiertos.abrirPantalla(this, id)
	}

	// --- Archivos de otras apps --------------------------------------------------------

	private fun atenderIntent(intent: Intent?) {
		intent ?: return
		when (intent.action) {
			Intent.ACTION_VIEW -> intent.data?.let { abrirExterno(it) }
			Intent.ACTION_SEND -> {
				@Suppress("DEPRECATION")
				val flujo = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
				val texto = intent.getStringExtra(Intent.EXTRA_TEXT)
				when {
					flujo != null -> abrirExterno(flujo)
					texto != null && Regex("^https?://\\S+$").matches(texto.trim()) -> abrirWeb(texto.trim())
					!texto.isNullOrBlank() -> guardarTexto(texto)
				}
			}
			Intent.ACTION_SEND_MULTIPLE -> {
				@Suppress("DEPRECATION")
				intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { lista ->
					lifecycleScope.launch {
						val ids = lista.mapNotNull { runCatching { Importador.anadirExterno(this@PrincipalActivity, it) }.getOrNull() }
						avisar(resources.getQuantityString(R.plurals.libros_anadidos, ids.size, ids.size), mostrar = true)
					}
				}
			}
		}
		intent.action = null
	}

	private fun abrirExterno(uri: Uri) {
		lifecycleScope.launch {
			try {
				val id = Importador.anadirExterno(this@PrincipalActivity, uri)
				TareasInicio.completar(this@PrincipalActivity, listOf(id))
				Abiertos.abrirPantalla(this@PrincipalActivity, id)
			} catch (e: Exception) {
				mensaje(getString(R.string.error), getString(R.string.error_abrir, e.message ?: ""))
			}
		}
	}

	// --- Menú de la barra -------------------------------------------------------------------

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.principal, menu)
		return true
	}

	override fun onPrepareOptionsMenu(menu: Menu): Boolean {
		menu.findItem(R.id.menu_orden)?.isVisible = pestana == R.id.pestana_biblioteca
		menu.findItem(R.id.menu_revisar)?.isVisible = Preferencias(this).carpetasVigiladas.isNotEmpty()
		menu.findItem(R.id.menu_vaciar_recientes)?.isVisible = pestana == R.id.pestana_recientes
		return super.onPrepareOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.menu_anadir -> elegir(getString(R.string.anadir_libros), listOf(
				getString(R.string.anadir_archivos) to { elegirArchivos.launch(Formato.TIPOS_MIME) },
				getString(R.string.anadir_carpeta) to { elegirCarpeta.launch(null) },
				getString(R.string.abrir_pagina_web) to { abrirWeb() },
				getString(R.string.leer_portapapeles) to { leerPortapapeles() },
				getString(R.string.escanear_documento) to { elegirEscaner() },
			))
			R.id.menu_orden -> {
				val p = Preferencias(this)
				val nombres = resources.getStringArray(R.array.ordenes).toList()
				elegirUno(getString(R.string.ordenar), nombres, p.ordenBiblioteca.ordinal) { i ->
					p.ordenBiblioteca = Orden.entries[i]
					supportFragmentManager.fragments.filterIsInstance<LibrosFragment>().forEach { it.recargar() }
				}
			}
			R.id.menu_revisar -> revisar()
			R.id.menu_vaciar_recientes -> confirmar(getString(R.string.confirmar_vaciar_leyendo), getString(R.string.vaciar)) {
				BaseDatos.obtener(this).vaciarRecientes()
				avisar(getString(R.string.leyendo_vaciado))
			}
			else -> return super.onOptionsItemSelected(item)
		}
		return true
	}
}
