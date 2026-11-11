package com.xebolax.lector.lectura

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xebolax.lector.R
import com.xebolax.lector.audio.ReproductorActivity
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Libro
import com.xebolax.lector.datos.Marcador
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.databinding.ActivityLectorBinding
import com.xebolax.lector.formatos.ExcepcionClave
import com.xebolax.lector.formatos.ExcepcionFormato
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.modelo.Documento
import com.xebolax.lector.modelo.Enlace
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.Posicion
import com.xebolax.lector.modelo.Textos
import com.xebolax.lector.modelo.TipoBloque
import com.xebolax.lector.ui.Accion
import com.xebolax.lector.ui.ListaActivity
import com.xebolax.lector.ui.Sonidos
import com.xebolax.lector.ui.TextoActivity
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.conEspera
import com.xebolax.lector.ui.accionesAccesibles
import com.xebolax.lector.ui.confirmar
import com.xebolax.lector.ui.copiar
import com.xebolax.lector.ui.elegir
import com.xebolax.lector.ui.elegirUno
import com.xebolax.lector.ui.elegirTemporizador
import com.xebolax.lector.ui.nombreTemporizador
import com.xebolax.lector.ui.conTemporizadorAlMantener
import com.xebolax.lector.ui.lectorActivo
import com.xebolax.lector.ui.mensaje
import com.xebolax.lector.ui.pedirTexto
import com.xebolax.lector.ui.tiempoHablado
import com.xebolax.lector.ia.conversarConIA
import com.xebolax.lector.voz.LectorVoz
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * Pantalla de lectura de documentos de texto, pensada para TalkBack:
 *
 * - Un párrafo por fila: deslizar avanza de párrafo en párrafo y "Leer desde
 *   el siguiente elemento" lee el libro entero. Los encabezados son
 *   encabezados de verdad y los enlaces aparecen en el menú de enlaces.
 * - Barra de navegación: elegir el elemento (encabezados, nivel 2, páginas,
 *   enlaces, tablas, marcadores...) deslizando arriba/abajo sobre el
 *   selector, y Anterior/Siguiente para saltar. También con las teclas de
 *   volumen (opcional) y con teclado físico (H, 1-6, P, K, T, L...).
 * - Cada párrafo tiene acciones de TalkBack: leer desde aquí, marcador,
 *   nota, copiar, siguiente encabezado... (configurables en Ajustes).
 * - Se guarda la posición donde está el foco de TalkBack.
 * - Lectura en voz alta propia con resaltado de la frase.
 */
class LectorActivity : AppCompatActivity(), AdaptadorBloques.Oyente {

	companion object {
		const val EXTRA_LIBRO = "libro"
		/** Documento que no está en la biblioteca (el manual, una web...). */
		const val EXTRA_URI = "uri"
		const val EXTRA_TITULO = "titulo"
		/** Empezar a leer en voz alta nada más abrir (noticias seguidas). */
		const val EXTRA_LEER_AL_ABRIR = "leer_al_abrir"
		/** «Leer todo» de Noticias: los artículos, en orden; al salir se marcan como leídos los que se han alcanzado. */
		const val EXTRA_SEGUIDOS = "rss_seguidos"
		/** Bloque en el que abrir (marcadores, resultados de búsqueda en la biblioteca...). */
		const val EXTRA_BLOQUE = "bloque"
		/** Abrir el texto de un libro DAISY con audio (en lugar del reproductor). */
		const val EXTRA_TEXTO_DE_AUDIO = "texto_de_audio"
	}

	private lateinit var b: ActivityLectorBinding
	private lateinit var p: Preferencias
	private lateinit var db: BaseDatos
	private var libro: Libro? = null
	private var doc: Documento? = null
	private var adaptador: AdaptadorBloques? = null
	private var navegador: Navegador? = null
	private var carga: Job? = null
	private var clave: String? = null

	/** Bloque actual: donde está el foco de TalkBack (o el primero visible). */
	private var actual = 0
	private var elemento = Elemento.ENCABEZADO
	private val atras = ArrayDeque<Int>()
	private val adelante = ArrayDeque<Int>()
	private var marcadores: List<Marcador> = emptyList()
	private var marcadoresPorBloque: Map<Int, List<Marcador>> = emptyMap()
	private var busqueda: Busqueda? = null
	private var elementosDisponibles: List<Elemento> = Elemento.entries

	// Estadísticas: tiempo con la pantalla de lectura activa y moviéndose
	private var ultimoLatido = 0L
	private var segundosPendientes = 0L
	private var bloqueSesion = 0

	private val idLibro: Long get() = libro?.id ?: -1

	private val oyenteVoz = LectorVoz.Oyente { e -> alCambiarVoz(e) }

	/** Carpeta donde dejar el audio del libro (un M4A por capítulo). */
	private val carpetaAudio = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
		uri ?: return@registerForActivityResult
		runCatching {
			contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
		}
		com.xebolax.lector.voz.ServicioExportar.empezar(this, idLibro, uri)
		avisar(getString(R.string.exportar_audio_empezado))
	}

	private val exportar = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
		uri?.let { exportarTexto(it) }
	}

	private val lista = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
		val bloque = r.data?.getIntExtra(ListaActivity.RESULTADO, -1) ?: -1
		if (r.resultCode == RESULT_OK && bloque >= 0) irA(bloque, desdeSalto = true)
	}

	/** Otro libro desde el propio lector (OCR, IA, widget...): la pantalla se rehace con él. */
	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		val otroLibro = intent.getLongExtra(EXTRA_LIBRO, -1).let { it > 0 && it != idLibro }
		val otraUri = intent.getStringExtra(EXTRA_URI)?.let { it != this.intent.getStringExtra(EXTRA_URI) } ?: false
		if (otroLibro || otraUri) {
			guardarPosicion()
			setIntent(intent)
			recreate()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityLectorBinding.inflate(layoutInflater)
		setContentView(b.root)
		p = Preferencias(this)
		db = BaseDatos.obtener(this)
		LectorVoz.iniciar(this)
		setSupportActionBar(b.barra)
		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		b.barra.setNavigationContentDescription(R.string.volver)
		b.barra.setNavigationOnClickListener { finish() }

		elemento = runCatching { Elemento.valueOf(p.elementoNavegacion) }.getOrDefault(Elemento.ENCABEZADO)
		configurarControles()
		adaptarATipo()
		aplicarPreferencias()
		cargar()
	}

	// --- Carga -------------------------------------------------------------------

	private fun cargar() {
		val id = intent.getLongExtra(EXTRA_LIBRO, -1)
		val uri = intent.getStringExtra(EXTRA_URI)
		libro = if (id > 0) db.libro(id) else uri?.let {
			Libro(-1, it, intent.getStringExtra(EXTRA_TITULO) ?: "documento", intent.getStringExtra(EXTRA_TITULO).orEmpty(), "", "", false, 0, null, 0, 0, null, 0.0, 0, false, "", 0, "")
		}
		val l = libro ?: run {
			finish()
			return
		}
		title = l.tituloVisible
		supportActionBar?.title = l.tituloVisible
		b.carga.isVisible = true
		b.lista.isVisible = false
		b.estadoCarga.text = getString(R.string.abriendo_titulo, l.tituloVisible)
		b.botonCancelarCarga.setOnClickListener { carga?.cancel(); finish() }
		val opciones = p.opcionesLectura.copy(clave = clave)
		var ultimoProgreso = -10
		carga = lifecycleScope.launch {
			try {
				val contenido = withContext(Dispatchers.IO) {
					Abiertos.abrir(this@LectorActivity, l, opciones, progreso = { pct ->
						if (pct - ultimoProgreso >= 10) {
							ultimoProgreso = pct
							runOnUiThread { b.estadoCarga.text = getString(R.string.abriendo_progreso, pct) }
						}
					}, cancelado = { carga?.isCancelled == true })
				}
				when (contenido) {
					is Contenido.Audio -> {
						val texto = contenido.libro.texto
						if (texto != null && intent.getBooleanExtra(EXTRA_TEXTO_DE_AUDIO, false)) {
							mostrar(texto)
							return@launch
						}
						startActivity(Intent(this@LectorActivity, ReproductorActivity::class.java).putExtra(EXTRA_LIBRO, l.id))
						finish()
					}
					is Contenido.Texto -> mostrar(contenido.documento)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: ExcepcionClave) {
				pedirTexto(getString(if (e.incorrecta) R.string.clave_incorrecta else R.string.documento_protegido), getString(R.string.contrasena)) { texto ->
					clave = texto
					cargar()
				}
			} catch (e: Throwable) {
				val mensaje = (e as? ExcepcionFormato)?.message ?: getString(R.string.error_abrir, e.message ?: e.javaClass.simpleName)
				b.indicador.isVisible = false
				b.estadoCarga.text = mensaje
				b.botonCancelarCarga.setText(R.string.cerrar)
				Sonidos.sonar(this@LectorActivity, Sonidos.Tipo.ERROR)
			}
		}
	}

	private fun mostrar(d: Documento) {
		doc = d
		val l = libro ?: return
		if (d.titulo.isNotBlank()) {
			title = d.titulo
			supportActionBar?.title = d.titulo
		}
		val nav = Navegador(d)
		navegador = nav
		val a = AdaptadorBloques(this, d, this).apply {
			seleccionable = p.textoSeleccionable
			anunciarNivel = p.anunciarNivel
			anunciarTipo = "tipo" in p.detalleParrafos
		}
		adaptador = a
		b.lista.adapter = a
		b.carga.isVisible = false
		b.lista.isVisible = true
		cargarMarcadores()
		cargarSubrayados()
		// Elementos que tiene el documento (en segundo plano: en libros enormes tarda un poco)
		lifecycleScope.launch {
			elementosDisponibles = withContext(Dispatchers.Default) {
				Elemento.entries.filter { it == Elemento.PARRAFO || it == Elemento.MARCADOR || it == Elemento.BUSQUEDA || nav.contar(it) > 0 }
			}
			if (elemento !in elementosDisponibles && elemento != Elemento.MARCADOR) {
				elemento = if (Elemento.ENCABEZADO in elementosDisponibles) Elemento.ENCABEZADO else Elemento.PARRAFO
			}
			actualizarSelector()
		}
		// Posición: la indicada, la de la voz si está leyendo este libro, o la guardada
		val inicio = when {
			intent.hasExtra(EXTRA_BLOQUE) -> intent.getIntExtra(EXTRA_BLOQUE, 0)
			LectorVoz.esActual(l.id) -> LectorVoz.bloque
			else -> l.posicion?.takeIf { !it.audio }?.bloque ?: 0
		}
		actual = inicio.coerceIn(0, (d.bloques.size - 1).coerceAtLeast(0))
		bloqueSesion = actual
		(b.lista.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(actual, 0)
		enfocar(actual)
		if (l.id > 0) db.marcarAbierto(l.id)
		if (d.bloques.isEmpty()) avisar(getString(R.string.documento_vacio))
		// Noticias seguidas: empieza a leer en voz alta
		if (intent.getBooleanExtra(EXTRA_LEER_AL_ABRIR, false) && d.bloques.isNotEmpty()) {
			intent.removeExtra(EXTRA_LEER_AL_ABRIR)
			b.lista.post { leerDesde(actual) }
		}
		if (d.avisos.isNotEmpty()) {
			val escaneado = esPdf && d.bloques.count { it.texto.isNotBlank() } <= d.paginas.size + 2 && d.avisos.any { it.contains("OCR") }
			if (escaneado) MaterialAlertDialogBuilder(this)
				.setTitle(R.string.avisos)
				.setMessage(d.avisos.joinToString("\n\n"))
				.setPositiveButton(R.string.ocr_todo) { _, _ -> ocrPdf() }
				.setNegativeButton(R.string.cerrar, null)
				.show()
			else mensaje(getString(R.string.avisos), d.avisos.joinToString("\n\n"))
		}
		LectorVoz.registrar(oyenteVoz)
		invalidateOptionsMenu()
	}

	// --- Controles -------------------------------------------------------------------

	private fun configurarControles() {
		val gestor = LinearLayoutManager(this)
		b.lista.layoutManager = gestor
		b.lista.itemAnimator = null
		b.lista.addOnScrollListener(object : RecyclerView.OnScrollListener() {
			override fun onScrollStateChanged(recycler: RecyclerView, estado: Int) {
				// Sin TalkBack, la posición es el primer párrafo visible
				if (estado == RecyclerView.SCROLL_STATE_IDLE && !lectorActivo()) {
					val primero = gestor.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 } ?: gestor.findFirstVisibleItemPosition()
					if (primero >= 0) moverActual(primero)
				}
			}
		})
		b.botonIndice.setOnClickListener { indice() }
		b.botonBuscar.setOnClickListener { buscar() }
		b.botonIr.setOnClickListener { irADialogo() }
		b.botonMarcadores.setOnClickListener { listaMarcadores() }
		b.botonVoz.setOnClickListener {
			val empieza = !(LectorVoz.esActual(idLibro) && LectorVoz.documento === doc && LectorVoz.leyendo)
			alternarVoz()
			// Con TalkBack, al empezar a leer el foco va a los controles del reproductor (Pausa), que están abajo
			if (empieza && lectorActivo()) b.panelVoz.postDelayed({
				if (b.panelVoz.isVisible) b.vozPausa.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
			}, 600)
		}
		b.botonVoz.conTemporizadorAlMantener { leerConTemporizador() }
		b.botonDonde.setOnClickListener { dondeEstoy() }
		b.botonIa.setOnClickListener { menuIA(actual) }
		b.botonElementos.setOnClickListener { listaElementos() }
		b.botonTemporizador.setOnClickListener { temporizador() }
		b.botonAspecto.setOnClickListener { aspectoRapido() }
		b.botonAjustesLibro.setOnClickListener { ajustesDelLibro() }
		b.botonInformacion.setOnClickListener { informacion() }
		b.botonSeleccionar.setOnClickListener { seleccionarTexto(actual) }
		b.botonExportar.setOnClickListener {
			elegir(getString(R.string.exportar), listOf(
				getString(R.string.exportar_texto) to { exportar.launch((doc?.titulo?.ifBlank { null } ?: libro?.tituloVisible ?: "texto") + ".txt") },
				getString(R.string.exportar_audio_opcion) to {
					when {
						idLibro <= 0 -> avisar(getString(R.string.solo_biblioteca))
						com.xebolax.lector.voz.ServicioExportar.exportando -> avisar(getString(R.string.exportar_audio_ya))
						else -> confirmar(getString(R.string.exportar_audio_explicacion), getString(R.string.elegir_carpeta)) { carpetaAudio.launch(null) }
					}
				},
			))
		}
		b.botonera.addOnLayoutChangeListener { _, izq, _, der, _, izqAntes, _, derAntes, _ ->
			if (der - izq != derAntes - izqAntes) b.botonera.post { repartirBotonera() }
		}

		b.botonAnterior.setOnClickListener { saltar(false) }
		b.botonSiguiente.setOnClickListener { saltar(true) }
		b.selectorElemento.rol = getString(R.string.rol_selector)
		b.selectorElemento.alSubir = { cambiarElemento(+1) }
		b.selectorElemento.alBajar = { cambiarElemento(-1) }
		b.selectorElemento.setOnClickListener { elegirElemento() }
		actualizarSelector()

		b.vozPausa.setOnClickListener { LectorVoz.alternar() }
		b.vozPausa.conTemporizadorAlMantener { leerConTemporizador() }
		// Llevar el foco al párrafo que suena (la app no lo mueve sola: TalkBack lo leería encima de la voz)
		val irALaVoz = getString(R.string.ir_a_lectura) to { if (LectorVoz.esActual(idLibro)) irA(LectorVoz.bloque) }
		b.vozPausa.accionesAccesibles(listOf(irALaVoz))
		b.botonVoz.accionesAccesibles(listOf(irALaVoz))
		b.vozFraseAnterior.setOnClickListener { LectorVoz.anteriorFrase() }
		b.vozFraseSiguiente.setOnClickListener { LectorVoz.siguienteFrase() }
		b.vozParrafoAnterior.setOnClickListener { LectorVoz.anteriorParrafo() }
		b.vozParrafoSiguiente.setOnClickListener { LectorVoz.siguienteParrafo() }
		b.vozDetener.setOnClickListener { LectorVoz.detener() }
		b.vozVelocidad.rol = getString(R.string.rol_velocidad)
		b.vozVelocidad.alSubir = { cambiarVelocidad(+5) }
		b.vozVelocidad.alBajar = { cambiarVelocidad(-5) }
		b.vozVelocidad.setOnClickListener { elegirVelocidad() }
		actualizarVelocidad()
	}

	private fun aplicarPreferencias() {
		val a = Apariencia.de(this)
		b.lista.setBackgroundColor(a.fondo)
		adaptador?.let {
			it.apariencia = a
			it.seleccionable = p.textoSeleccionable
			it.anunciarNivel = p.anunciarNivel
			it.anunciarTipo = "tipo" in p.detalleParrafos
			it.notifyDataSetChanged()
		}
		b.barraNavegacion.isVisible = p.barraNavegacion
		if (p.mantenerPantalla) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		requestedOrientation = when (p.orientacion) {
			"vertical" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
			"horizontal" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
			else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
		}
		val ventana = WindowCompat.getInsetsController(window, window.decorView)
		if (p.ocultarBarras) {
			ventana.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
			ventana.hide(WindowInsetsCompat.Type.systemBars())
		} else ventana.show(WindowInsetsCompat.Type.systemBars())
	}

	/** Para aplicar lo que se cambie en Ajustes (desde el menú lateral) al volver a la lectura. */
	private var ajustesAlSalir = ""

	override fun onResume() {
		super.onResume()
		val ajustes = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).all.toString()
		if (ajustesAlSalir.isNotEmpty() && ajustes != ajustesAlSalir) {
			aplicarPreferencias()
			actualizarVelocidad()
			repartirBotonera()
		}
		ajustesAlSalir = ""
		ultimoLatido = System.currentTimeMillis()
	}

	override fun onStop() {
		super.onStop()
		ajustesAlSalir = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).all.toString()
	}

	override fun onPause() {
		marcarSeguidosLeidos()
		guardarPosicion()
		volcarEstadisticas()
		com.xebolax.lector.widget.WidgetLectura.actualizar(this)
		com.xebolax.lector.LectorApp.ambito.launch { com.xebolax.lector.sincronizacion.Sincronizador.siToca(applicationContext, 30_000L) }
		super.onPause()
	}

	override fun onDestroy() {
		LectorVoz.desregistrar(oyenteVoz)
		carga?.cancel()
		super.onDestroy()
	}

	// --- Posición --------------------------------------------------------------------

	/** El bloque actual cambió (foco de TalkBack, desplazamiento, salto). */
	/** El bloque más avanzado al que se ha llegado (para marcar los artículos escuchados). */
	private var masLejos = 0

	private fun moverActual(bloque: Int) {
		if (bloque == actual) return
		actual = bloque
		if (bloque > masLejos) masLejos = bloque
		latido()
		val marcas = marcadoresPorBloque[bloque]
		if (marcas != null && p.sonidoMarcadores) {
			Sonidos.sonar(this, if (marcas.any { it.nota.isNotBlank() }) Sonidos.Tipo.NOTA else Sonidos.Tipo.MARCADOR)
		} else doc?.let { d -> Sonidos.alLlegar(this, d, bloque) }
	}

	private fun guardarPosicion() {
		val d = doc ?: return
		if (idLibro <= 0 || d.bloques.isEmpty()) return
		// El texto de un DAISY con audio no cambia la posición del audio
		if (intent.getBooleanExtra(EXTRA_TEXTO_DE_AUDIO, false)) return
		// Si la voz está leyendo este libro, ella guarda su posición
		if (LectorVoz.esActual(idLibro) && LectorVoz.leyendo) return
		db.guardarPosicion(idLibro, Posicion.texto(actual), d.porcentaje(actual))
	}

	private fun latido() {
		val ahora = System.currentTimeMillis()
		val hueco = ahora - ultimoLatido
		// Un hueco de más de 2 minutos sin moverse no cuenta como lectura
		if (hueco in 1..120_000) segundosPendientes += hueco / 1000
		ultimoLatido = ahora
		if (segundosPendientes >= 60) volcarEstadisticas()
	}

	private fun volcarEstadisticas() {
		val d = doc ?: return
		if (idLibro <= 0) {
			// Una noticia: su tiempo cuenta en las estadísticas de Noticias
			if (intent.hasExtra(com.xebolax.lector.rss.RssAcciones.EXTRA_ARTICULO) && segundosPendientes > 0) {
				com.xebolax.lector.datos.Actividad.sumarLuego(this, com.xebolax.lector.datos.Actividad.NOTICIAS, segundosPendientes)
			}
			segundosPendientes = 0
			return
		}
		val avance = d.inicioBloque[actual.coerceIn(0, d.bloques.size)] - d.inicioBloque[bloqueSesion.coerceIn(0, d.bloques.size)]
		if (segundosPendientes > 0 || avance > 0) db.sumarLectura(idLibro, segundosPendientes, avance.coerceAtLeast(0))
		segundosPendientes = 0
		bloqueSesion = actual
	}

	/** Va a un bloque: lo muestra arriba y pone en él el foco de TalkBack. */
	fun irA(bloque: Int, desdeSalto: Boolean = false, caracter: Int = 0) {
		val d = doc ?: return
		if (d.bloques.isEmpty()) return
		val destino = bloque.coerceIn(0, d.bloques.size - 1)
		if (desdeSalto && destino != actual) {
			atras.addLast(actual)
			if (atras.size > 100) atras.removeFirst()
			adelante.clear()
		}
		moverActual(destino)
		(b.lista.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(destino, 0)
		enfocar(destino)
		if (LectorVoz.esActual(idLibro) && LectorVoz.leyendo) LectorVoz.irA(destino, caracter)
	}

	/** Pone el foco de accesibilidad en un bloque cuando ya está en pantalla. */
	private fun enfocar(bloque: Int, intentos: Int = 0) {
		b.lista.post {
			val vista = b.lista.findViewHolderForAdapterPosition(bloque)?.itemView
			if (vista == null) {
				if (intentos < 10) b.lista.postDelayed({ enfocar(bloque, intentos + 1) }, 50)
				return@post
			}
			if (lectorActivo()) vista.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
			else vista.requestFocus()
		}
	}

	// --- Navegación por elementos ----------------------------------------------------------

	private fun saltar(haciaDelante: Boolean, e: Elemento = elemento) {
		val nav = navegador ?: return
		val d = doc ?: return
		nav.marcadores = marcadores.map { it.posicion.bloque }
		nav.resultados = busqueda?.resultados?.map { it.bloque }.orEmpty()
		val destino = nav.buscar(e, actual, haciaDelante, p.navegacionCircular)
		if (destino < 0) {
			Sonidos.sonar(this, Sonidos.Tipo.LIMITE)
			avisar(getString(R.string.no_hay_mas, getString(e.nombre).lowercase()))
			return
		}
		val volvio = (haciaDelante && destino <= actual) || (!haciaDelante && destino >= actual)
		if (volvio) avisar(getString(if (haciaDelante) R.string.vuelta_al_principio else R.string.vuelta_al_final))
		irA(destino, desdeSalto = e != Elemento.PARRAFO)
		// La página o el resultado se anuncian (el texto del bloque no los dice)
		if (p.anunciarSaltos) when (e) {
			Elemento.PAGINA -> d.paginaDe(destino)?.let { avisar(getString(R.string.pagina_n, it.etiqueta), mostrar = false) }
			Elemento.BUSQUEDA -> busqueda?.let { bq ->
				val n = bq.resultados.indexOfFirst { it.bloque == destino } + 1
				avisar(getString(R.string.resultado_n_de, n, bq.resultados.size), mostrar = false)
			}
			Elemento.DIEZ_POR_CIENTO -> avisar(getString(R.string.porcentaje_n, d.porcentaje(destino).toInt()), mostrar = false)
			else -> Unit
		}
	}

	private fun cambiarElemento(paso: Int) {
		val lista = elementosDisponibles
		val i = lista.indexOf(elemento).coerceAtLeast(0)
		elemento = lista[(i + paso + lista.size) % lista.size]
		p.p.edit().putString("elemento_navegacion", elemento.name).apply()
		actualizarSelector()
		avisar(getString(elemento.nombre), mostrar = false)
	}

	private fun elegirElemento() {
		val lista = elementosDisponibles
		elegirUno(getString(R.string.navegar_por), lista.map { getString(it.nombre) }, lista.indexOf(elemento)) { i ->
			elemento = lista[i]
			p.p.edit().putString("elemento_navegacion", elemento.name).apply()
			actualizarSelector()
		}
	}

	private fun actualizarSelector() {
		val nombre = getString(elemento.nombre)
		b.selectorElemento.text = nombre
		b.selectorElemento.valor = nombre
		b.selectorElemento.contentDescription = getString(R.string.navegar_por)
		b.botonAnterior.contentDescription = getString(elemento.anterior)
		b.botonSiguiente.contentDescription = getString(elemento.siguiente)
		ViewCompat.setTooltipText(b.botonAnterior, getString(elemento.anterior))
		ViewCompat.setTooltipText(b.botonSiguiente, getString(elemento.siguiente))
	}

	// --- Oyente del adaptador ------------------------------------------------------------

	override fun alEnfocar(bloque: Int) = moverActual(bloque)

	override fun alTocar(bloque: Int) {
		moverActual(bloque)
		when (p.toqueParrafo) {
			"menu" -> menuParrafo(bloque)
			"voz" -> leerDesde(bloque)
			"enlace" -> doc?.bloques?.getOrNull(bloque)?.enlaces?.firstOrNull()?.let { alAbrirEnlace(bloque, it) } ?: menuParrafo(bloque)
		}
	}

	override fun alMantener(bloque: Int) {
		moverActual(bloque)
		menuParrafo(bloque)
	}

	/** «Leer todo» de Noticias: marca como leídos los artículos cuyo titular se ha alcanzado (no todos al empezar). */
	private fun marcarSeguidosLeidos() {
		val ids = intent.getLongArrayExtra(EXTRA_SEGUIDOS) ?: return
		val d = doc ?: return
		val hasta = maxOf(masLejos, actual, if (LectorVoz.documento === d) LectorVoz.bloque else 0)
		val db = com.xebolax.lector.rss.RssBaseDatos.obtener(this)
		val leidos = mutableListOf<Long>()
		var desde = 0
		for (id in ids) {
			val titulo = db.articulo(id)?.titulo?.trim() ?: continue
			// Cada artículo empieza con su titular, en el mismo orden
			val i = (desde until d.bloques.size).firstOrNull { d.bloques[it].tipo == TipoBloque.ENCABEZADO && d.bloques[it].texto.trim() == titulo } ?: break
			if (i > hasta) break
			leidos += id
			desde = i + 1
		}
		if (leidos.isNotEmpty()) db.marcarLeidos(leidos)
	}

	override fun alAbrirEnlace(bloque: Int, enlace: Enlace) {
		val d = doc ?: return
		if (enlace.interno) {
			val destino = enlace.bloqueDestino
			val b = d.bloques.getOrNull(destino) ?: return
			// Nota al pie: se lee en una ventana sin perder el sitio
			if (d.bloques.getOrNull(bloque)?.tiene(Marca.LLAMADA_NOTA) == true && (b.tipo == TipoBloque.NOTA || b.texto.length < 1500)) {
				mostrarNota(destino)
			} else irA(destino, desdeSalto = true)
		} else if (enlace.destino.startsWith(com.xebolax.lector.rss.RssAcciones.ESQUEMA_ARTICULO)) {
			// Titular de «Leer titulares»: abre su artículo (Siguiente y Anterior recorren los titulares)
			val id = enlace.destino.removePrefix(com.xebolax.lector.rss.RssAcciones.ESQUEMA_ARTICULO).toLongOrNull() ?: return
			val art = com.xebolax.lector.rss.RssBaseDatos.obtener(this).articulo(id) ?: return
			LectorVoz.pausar()
			com.xebolax.lector.rss.RssAcciones.abrir(this, art, intent.getLongArrayExtra(com.xebolax.lector.rss.RssAcciones.EXTRA_LISTA) ?: longArrayOf(id))
		} else if (enlace.destino.startsWith(com.xebolax.lector.podcast.Podcasts.ESQUEMA_TIEMPO)) {
			// Marca de tiempo de las notas de un episodio: suena desde ese momento
			val (id, ms) = enlace.destino.removePrefix(com.xebolax.lector.podcast.Podcasts.ESQUEMA_TIEMPO).split(':').map { it.toLongOrNull() ?: 0L } + listOf(0L, 0L)
			val db = com.xebolax.lector.podcast.PodcastsBaseDatos.obtener(this)
			val e = db.episodio(id) ?: return
			db.guardarPosicion(e.id, ms, e.duracion)
			lifecycleScope.launch { com.xebolax.lector.podcast.Podcasts.escuchar(this@LectorActivity, e.copy(posicion = ms, estado = if (e.terminado) 0 else e.estado)) }
		} else {
			try {
				startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(enlace.destino)))
			} catch (_: ActivityNotFoundException) {
				avisar(getString(R.string.sin_app_enlace))
			}
		}
	}

	private fun mostrarNota(bloque: Int) {
		val d = doc ?: return
		// La nota puede ocupar varios bloques seguidos de tipo NOTA
		val partes = mutableListOf(d.bloques[bloque].texto)
		var i = bloque + 1
		while (i < d.bloques.size && d.bloques[i].tipo == TipoBloque.NOTA && partes.size < 6 && !Regex("^\\[?\\d+[.\\])]").containsMatchIn(d.bloques[i].texto)) {
			partes += d.bloques[i].texto
			i++
		}
		com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
			.setTitle(R.string.nota)
			.setMessage(partes.joinToString("\n\n"))
			.setPositiveButton(R.string.cerrar, null)
			.setNeutralButton(R.string.ir_a_la_nota) { _, _ -> irA(bloque, desdeSalto = true) }
			.show()
	}

	override fun estado(bloque: Int): String? {
		val d = doc ?: return null
		val partes = mutableListOf<String>()
		val detalle = p.detalleParrafos
		if ("pagina" in detalle && d.bloques.getOrNull(bloque)?.tiene(Marca.PAGINA) == true) d.paginaDe(bloque)?.let { partes += getString(R.string.pagina_n, it.etiqueta) }
		if ("marcador" in detalle) marcadoresPorBloque[bloque]?.let { lista ->
			val notas = lista.filter { it.nota.isNotBlank() }
			if (notas.isNotEmpty()) partes += getString(R.string.con_nota, notas.joinToString("; ") { it.nota })
			else partes += getString(R.string.con_marcador)
		}
		if ("marcador" in detalle) subrayadosPorBloque[bloque]?.let { lista ->
			partes += getString(R.string.subrayado_x, lista.map { it.categoria }.distinct().joinToString(", "))
		}
		if ("busqueda" in detalle) busqueda?.let { bq -> if (bq.resultados.any { it.bloque == bloque }) partes += getString(R.string.coincide_busqueda) }
		return partes.joinToString(", ").ifEmpty { null }
	}

	/** Acciones de TalkBack de cada párrafo (las elegidas en Ajustes) y sus enlaces. */
	override fun acciones(bloque: Int): List<Accion> {
		val d = doc ?: return emptyList()
		val lista = mutableListOf<Accion>()
		val bl = d.bloques.getOrNull(bloque) ?: return lista
		// Las que el usuario eligió, en su orden (Ajustes, Acciones de TalkBack, Párrafos del lector)
		for (clave in com.xebolax.lector.ui.AccionesTalkBack.elegidas(this, com.xebolax.lector.ui.ZonaAcciones.PARRAFO)) when (clave) {
			"enlaces" -> for (e in bl.enlaces.take(5)) {
				val texto = bl.texto.substring(e.inicio, e.fin).take(40)
				lista += getString(R.string.abrir_enlace_x, texto) to { alAbrirEnlace(bloque, e) }
			}
			"describir" -> {
				if (d.imagenes.containsKey(bloque)) lista += getString(R.string.describir_imagen) to { describirImagen(bloque) }
				if (esPdf) d.paginaDe(bloque)?.let { pag -> lista += getString(R.string.describir_pagina_n, pag.etiqueta) to { describirPagina(bloque) } }
			}
			else -> accionParrafo(clave, bloque)?.let { lista += it }
		}
		return lista.distinctBy { it.first }
	}

	private val ORDEN_ACCIONES = listOf(
		"leer_aqui", "marcador", "nota", "copiar", "consultar", "siguiente_encabezado", "anterior_encabezado", "siguiente_elemento",
		"anterior_elemento", "indice", "buscar", "ir_a", "donde_estoy", "seleccionar", "compartir", "explicar", "traducir", "subrayar",
		"salir", "copiar_tramo", "marca_rapida",
	)

	/** Elegir una palabra del párrafo (o el párrafo entero) para consultarla. */
	private fun consultarPalabra(bloque: Int) {
		val texto = doc?.bloques?.getOrNull(bloque)?.texto ?: return
		val palabras = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’\\-]+").findAll(texto).map { it.value }.distinct().take(80).toList()
		elegir(getString(R.string.consultar_palabra),
			palabras.map { p -> p to { opcionesConsulta(p) } } + (getString(R.string.todo_el_parrafo) to { opcionesConsulta(texto.take(1000)) }))
	}

	private fun opcionesConsulta(t: String) {
		val idioma = (doc?.idioma?.ifBlank { null } ?: resources.configuration.locales[0].language).substringBefore('-').lowercase()
		val codigo = Uri.encode(t)
		val web = { url: String ->
			try {
				startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
			} catch (_: ActivityNotFoundException) {
				avisar(getString(R.string.sin_app_enlace))
			}
		}
		val opciones = mutableListOf<Accion>()
		val unaPalabra = !t.contains(' ')
		if (unaPalabra) {
			if (idioma == "es") opciones += getString(R.string.diccionario_rae) to { web("https://dle.rae.es/$codigo") }
			opciones += getString(R.string.wikcionario) to { web("https://$idioma.wiktionary.org/wiki/${Uri.encode(t.lowercase())}") }
			opciones += getString(R.string.deletrear) to { avisar(t.toCharArray().joinToString(", "), mostrar = true) }
		}
		opciones += getString(R.string.wikipedia) to { web("https://$idioma.wikipedia.org/w/index.php?search=$codigo") }
		opciones += getString(R.string.traducir) to {
			val destino = resources.configuration.locales[0].language
			val traductor = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
				.putExtra(Intent.EXTRA_PROCESS_TEXT, t).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
				.setPackage("com.google.android.apps.translate")
			try {
				startActivity(traductor)
			} catch (_: ActivityNotFoundException) {
				web("https://translate.google.com/?sl=auto&tl=$destino&text=$codigo&op=translate")
			}
		}
		opciones += getString(R.string.otras_aplicaciones) to {
			val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
				.putExtra(Intent.EXTRA_PROCESS_TEXT, t).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
			try {
				startActivity(Intent.createChooser(intent, getString(R.string.consultar_palabra)))
			} catch (_: ActivityNotFoundException) {
				avisar(getString(R.string.sin_app_enlace))
			}
		}
		opciones += getString(R.string.copiar) to { copiar(t) }
		elegir(t.take(60), opciones)
	}

	private fun accionParrafo(accion: String, bloque: Int): Accion? = when (accion) {
		"leer_aqui" -> getString(R.string.leer_desde_aqui) to { leerDesde(bloque) }
		"marcador" -> (if (marcadoresPorBloque.containsKey(bloque)) getString(R.string.quitar_marcador) else getString(R.string.anadir_marcador)) to {
			alternarMarcador(bloque)
		}
		"nota" -> getString(R.string.anadir_nota) to { anadirNota(bloque) }
		"copiar" -> getString(R.string.copiar_parrafo) to { doc?.bloques?.getOrNull(bloque)?.let { copiar(it.texto) } }
		"consultar" -> getString(R.string.consultar_palabra) to { consultarPalabra(bloque) }
		"siguiente_encabezado" -> getString(R.string.sig_encabezado) to { moverActual(bloque); saltar(true, Elemento.ENCABEZADO) }
		"anterior_encabezado" -> getString(R.string.ant_encabezado) to { moverActual(bloque); saltar(false, Elemento.ENCABEZADO) }
		"siguiente_elemento" -> getString(elemento.siguiente) to { moverActual(bloque); saltar(true) }
		"anterior_elemento" -> getString(elemento.anterior) to { moverActual(bloque); saltar(false) }
		"indice" -> getString(R.string.indice) to { indice() }
		"buscar" -> getString(R.string.buscar_en_libro) to { buscar() }
		"ir_a" -> getString(R.string.ir_a) to { irADialogo() }
		"donde_estoy" -> getString(R.string.donde_estoy) to { dondeEstoy() }
		"seleccionar" -> getString(R.string.seleccionar_texto) to { seleccionarTexto(bloque) }
		"compartir" -> getString(R.string.compartir_parrafo) to { compartirTexto(doc?.bloques?.getOrNull(bloque)?.texto.orEmpty()) }
		"explicar" -> getString(R.string.ia_explicar_parrafo) to { preguntarIA(bloque, "explicar") }
		"traducir" -> getString(R.string.traducir_parrafo) to { traducir(bloque) }
		"subrayar" -> getString(R.string.subrayar) to { subrayar(bloque) }
		// Dentro de una tabla, lista o cita: salir de ella de una vez (sin recorrer todas sus filas)
		"salir" -> doc?.bloques?.getOrNull(bloque)?.tipo?.let { t ->
			val texto = when (t) {
				TipoBloque.FILA_TABLA -> R.string.salir_tabla
				TipoBloque.ELEMENTO_LISTA -> R.string.salir_lista
				TipoBloque.CITA -> R.string.salir_cita
				else -> return@let null
			}
			getString(texto) to { salirDeContenedor(bloque) }
		}
		// Copiar varios párrafos: se marca el inicio y luego «copiar desde la marca hasta aquí»
		"copiar_tramo" -> {
			val inicio = inicioCopia
			if (inicio < 0 || inicio == bloque) getString(R.string.marcar_inicio_copia) to {
				inicioCopia = bloque
				avisar(getString(R.string.inicio_copia_marcado))
			} else getString(R.string.copiar_desde_marca, kotlin.math.abs(bloque - inicio) + 1) to { copiarTramo(inicio, bloque) }
		}
		// Marca rápida: un sitio para volver sin llenar la lista de marcadores
		"marca_rapida" -> {
			val marca = marcaRapida()
			if (marca >= 0 && marca != bloque) getString(R.string.ir_marca_rapida) to { irA(marca, desdeSalto = true) }
			else getString(R.string.poner_marca_rapida) to { ponerMarcaRapida(bloque) }
		}
		else -> null
	}

	/** Bloque desde el que se copiará (marcado con «Marcar el inicio de la copia»); -1 = ninguno. */
	private var inicioCopia = -1

	private fun copiarTramo(desde: Int, hasta: Int) {
		val d = doc ?: return
		val (a, b) = if (desde <= hasta) desde to hasta else hasta to desde
		val texto = (a..b).mapNotNull { d.bloques.getOrNull(it)?.texto }.joinToString("\n\n")
		copiar(texto)
		inicioCopia = -1
		avisar(resources.getQuantityString(R.plurals.parrafos_copiados, b - a + 1, b - a + 1))
	}

	private fun salirDeContenedor(bloque: Int) {
		val fin = navegador?.finContenedor(bloque) ?: -1
		if (fin < 0) return avisar(getString(R.string.nada_despues))
		irA(fin, desdeSalto = true)
	}

	private fun claveMarcaRapida() = "marca_rapida_" + (if (idLibro > 0) idLibro.toString() else libro?.uri.orEmpty().hashCode().toString())

	private fun marcaRapida(): Int = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getInt(claveMarcaRapida(), -1)

	private fun ponerMarcaRapida(bloque: Int) {
		androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).edit().putInt(claveMarcaRapida(), bloque).apply()
		avisar(getString(R.string.marca_rapida_puesta))
	}

	/** Menú del párrafo (doble toque o pulsación larga): todas las acciones. */
	private fun menuParrafo(bloque: Int) {
		val d = doc ?: return
		val bl = d.bloques.getOrNull(bloque) ?: return
		val opciones = mutableListOf<Accion>()
		for (e in bl.enlaces) opciones += getString(R.string.abrir_enlace_x, bl.texto.substring(e.inicio, e.fin).take(50)) to { alAbrirEnlace(bloque, e) }
		if (d.imagenes.containsKey(bloque)) opciones += getString(R.string.describir_imagen) to { describirImagen(bloque) }
		if (esPdf) d.paginaDe(bloque)?.let { pag -> opciones += getString(R.string.describir_pagina_n, pag.etiqueta) to { describirPagina(bloque) } }
		for (accion in ORDEN_ACCIONES) accionParrafo(accion, bloque)?.let { opciones += it }
		elegir(bl.texto.take(80), opciones.distinctBy { it.first })
	}

	// --- Índice, ir a, elementos ---------------------------------------------------------------

	private fun indice() {
		val d = doc ?: return
		if (d.indice.isEmpty()) {
			avisar(getString(R.string.sin_indice))
			return
		}
		val actualEntrada = d.indice.indexOfLast { it.bloque <= actual }.coerceAtLeast(0)
		val elementos = d.indice.map { e ->
			ListaActivity.Item(e.titulo, getString(R.string.nivel_porcentaje, e.nivel, d.porcentaje(e.bloque).toInt()), e.bloque, e.nivel)
		}
		abrirLista(getString(R.string.indice), elementos, actualEntrada)
	}

	private fun abrirLista(titulo: String, elementos: List<ListaActivity.Item>, seleccionado: Int = 0) {
		ListaActivity.datos = ListaActivity.Datos(titulo, elementos, seleccionado)
		lista.launch(Intent(this, ListaActivity::class.java))
	}

	private fun irADialogo() {
		val d = doc ?: return
		val opciones = mutableListOf<Accion>(
			getString(R.string.ir_a_porcentaje) to {
				pedirTexto(getString(R.string.ir_a_porcentaje), getString(R.string.porcentaje_ayuda), teclado = com.xebolax.lector.ui.Teclado.NUMERO) { t ->
					val relativo = t.startsWith("+") || t.startsWith("-")
					val n = t.replace("%", "").replace(",", ".").toDoubleOrNull() ?: return@pedirTexto
					val objetivo = if (relativo) d.porcentaje(actual) + n else n
					irA(d.bloqueDePorcentaje(objetivo), desdeSalto = true)
				}
			},
		)
		if (d.paginas.isNotEmpty()) opciones += getString(R.string.ir_a_pagina) to {
			val actualPag = d.paginaDe(actual)?.etiqueta.orEmpty()
			pedirTexto(getString(R.string.ir_a_pagina), getString(R.string.pagina_ayuda, d.paginas.first().etiqueta, d.paginas.last().etiqueta, actualPag)) { t ->
				val pagina = d.paginas.firstOrNull { it.etiqueta.equals(t, ignoreCase = true) }
					?: t.toIntOrNull()?.let { n -> d.paginas.getOrNull(n - 1) }
				if (pagina != null) irA(pagina.bloque, desdeSalto = true) else avisar(getString(R.string.pagina_no_existe))
			}
		}
		opciones += getString(R.string.ir_a_parrafo) to {
			pedirTexto(getString(R.string.ir_a_parrafo), getString(R.string.parrafo_ayuda, actual + 1, d.bloques.size), teclado = com.xebolax.lector.ui.Teclado.NUMERO) { t ->
				val n = t.toIntOrNull() ?: return@pedirTexto
				val destino = if (t.startsWith("+") || t.startsWith("-")) actual + n else n - 1
				irA(destino, desdeSalto = true)
			}
		}
		opciones += getString(R.string.principio) to { irA(0, desdeSalto = true) }
		opciones += getString(R.string.final_documento) to { irA(d.bloques.size - 1, desdeSalto = true) }
		if (atras.isNotEmpty()) opciones += getString(R.string.posicion_anterior) to { volver() }
		if (adelante.isNotEmpty()) opciones += getString(R.string.posicion_siguiente) to { avanzarHistorial() }
		elegir(getString(R.string.ir_a), opciones)
	}

	private fun volver() {
		val destino = atras.removeLastOrNull() ?: return avisar(getString(R.string.sin_posicion_anterior))
		adelante.addLast(actual)
		irA(destino)
	}

	private fun avanzarHistorial() {
		val destino = adelante.removeLastOrNull() ?: return
		atras.addLast(actual)
		irA(destino)
	}

	/** Lista de todos los elementos de un tipo (encabezados, enlaces, tablas...). */
	private fun listaElementos() {
		val d = doc ?: return
		val nav = navegador ?: return
		val tipos = elementosDisponibles.filter { it != Elemento.PARRAFO && it != Elemento.DIEZ_POR_CIENTO && it != Elemento.BUSQUEDA }
		elegir(getString(R.string.lista_elementos), tipos.map { e ->
			"${getString(e.nombre)} (${if (e == Elemento.MARCADOR) marcadores.size else nav.contar(e)})" to {
				val indices = when (e) {
					Elemento.PAGINA -> d.paginas.map { it.bloque }
					Elemento.MARCADOR -> marcadores.map { it.posicion.bloque }
					Elemento.CAPITULO -> {
						val r = mutableListOf<Int>()
						var i = -1
						while (true) { i = nav.buscar(e, i, true); if (i < 0) break; r += i }
						r
					}
					else -> d.bloques.indices.filter { nav.coincide(e, it) }
				}
				val elementos = indices.map { i ->
					val texto = when (e) {
						Elemento.PAGINA -> getString(R.string.pagina_n, d.paginaDe(i)?.etiqueta.orEmpty()) + ": " + d.bloques[i].texto.take(80)
						Elemento.ENLACE -> d.bloques[i].enlaces.joinToString(", ") { en -> d.bloques[i].texto.substring(en.inicio, en.fin) }
						else -> d.bloques[i].texto.take(120)
					}
					ListaActivity.Item(texto, getString(R.string.porcentaje_n, d.porcentaje(i).toInt()), i, d.bloques[i].nivel.coerceAtLeast(1))
				}
				val seleccion = indices.indexOfLast { it <= actual }.coerceAtLeast(0)
				abrirLista(getString(e.nombre), elementos, seleccion)
			}
		})
	}

	private fun dondeEstoy() {
		val d = doc ?: return
		val partes = mutableListOf<String>()
		d.capituloDe(actual)?.let { partes += it.titulo }
		d.paginaDe(actual)?.let { partes += getString(R.string.pagina_de, it.etiqueta, d.paginas.last().etiqueta) }
		partes += getString(R.string.porcentaje_n, d.porcentaje(actual).toInt())
		partes += getString(R.string.parrafo_de, actual + 1, d.bloques.size)
		val quedan = d.caracteres - d.inicioBloque[actual]
		// Ritmo: unas 1000 palabras de 6 letras cada 5 minutos leyendo; la voz, según su velocidad
		val minutosVoz = quedan / 6.0 / (160.0 * LectorVoz.velocidadActual() / 100.0)
		partes += getString(R.string.quedan_con_voz, tiempoHablado((minutosVoz * 60_000).toLong(), conSegundos = false))
		avisar(partes.joinToString(". "), mostrar = true)
	}

	// --- Búsqueda -----------------------------------------------------------------------

	class Resultado(val bloque: Int, val inicio: Int, val fin: Int)
	class Busqueda(val texto: String, val resultados: List<Resultado>)

	private fun buscar() {
		val d = doc ?: return
		val vista = layoutInflater.inflate(R.layout.dialogo_buscar, null)
		val campo = vista.findViewById<android.widget.AutoCompleteTextView>(R.id.campo_buscar)
		val mayusculas = vista.findViewById<android.widget.CheckBox>(R.id.opcion_mayusculas)
		val palabra = vista.findViewById<android.widget.CheckBox>(R.id.opcion_palabra)
		val regex = vista.findViewById<android.widget.CheckBox>(R.id.opcion_regex)
		val historial = p.historialBusquedas
		campo.setAdapter(android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, historial))
		campo.setText(busqueda?.texto.orEmpty())
		campo.selectAll()
		val dialogo = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
			.setTitle(R.string.buscar_en_libro)
			.setView(vista)
			.setPositiveButton(R.string.buscar) { _, _ -> }
			.setNeutralButton(R.string.ver_todos) { _, _ -> }
			.setNegativeButton(R.string.cancelar, null)
			.create()
		fun lanzar(todos: Boolean) {
			val texto = campo.text.toString()
			if (texto.isBlank()) return
			dialogo.dismiss()
			p.historialBusquedas = listOf(texto) + historial
			ejecutarBusqueda(d, texto, mayusculas.isChecked, palabra.isChecked, regex.isChecked, todos)
		}
		campo.setOnEditorActionListener { _, _, _ -> lanzar(false); true }
		dialogo.setOnShowListener {
			dialogo.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { lanzar(false) }
			dialogo.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { lanzar(true) }
			campo.requestFocus()
		}
		dialogo.show()
	}

	private fun ejecutarBusqueda(d: Documento, texto: String, mayusculas: Boolean, palabra: Boolean, regex: Boolean, todos: Boolean) {
		lifecycleScope.launch {
			val patron = runCatching {
				var base = if (regex) texto else Regex.escape(texto)
				if (palabra) base = "(?<![\\p{L}\\p{N}])$base(?![\\p{L}\\p{N}])"
				Regex(base, if (mayusculas) emptySet() else setOf(RegexOption.IGNORE_CASE))
			}.getOrElse {
				avisar(getString(R.string.expresion_no_valida))
				return@launch
			}
			// Sin mayúsculas ni regex, también sin tildes ("cancion" encuentra "canción")
			val sinTildes = !mayusculas && !regex
			val clave = if (sinTildes) quitarTildes(texto) else texto
			val resultados = withContext(Dispatchers.Default) {
				val r = ArrayList<Resultado>()
				for ((i, bl) in d.bloques.withIndex()) {
					if (sinTildes && !palabra) {
						val normal = quitarTildes(bl.texto)
						var desde = 0
						while (true) {
							val pos = normal.indexOf(clave, desde, ignoreCase = true)
							if (pos < 0) break
							r += Resultado(i, pos, pos + clave.length)
							desde = pos + clave.length.coerceAtLeast(1)
						}
					} else patron.findAll(bl.texto).forEach { m -> r += Resultado(i, m.range.first, m.range.last + 1) }
				}
				r
			}
			if (resultados.isEmpty()) {
				Sonidos.sonar(this@LectorActivity, Sonidos.Tipo.LIMITE)
				avisar(getString(R.string.sin_resultados, texto))
				return@launch
			}
			val anteriores = busqueda?.resultados?.map { it.bloque }.orEmpty()
			busqueda = Busqueda(texto, resultados)
			(anteriores + resultados.map { it.bloque }).distinct().forEach { adaptador?.refrescarEstado(it) }
			elemento = Elemento.BUSQUEDA
			actualizarSelector()
			Sonidos.sonar(this@LectorActivity, Sonidos.Tipo.LISTO)
			if (todos) {
				val elementos = resultados.map { r ->
					val t = d.bloques[r.bloque].texto
					val desde = (r.inicio - 50).coerceAtLeast(0)
					val hasta = (r.fin + 70).coerceAtMost(t.length)
					ListaActivity.Item(
						(if (desde > 0) "…" else "") + t.substring(desde, hasta) + (if (hasta < t.length) "…" else ""),
						getString(R.string.porcentaje_n, d.porcentaje(r.bloque).toInt()), r.bloque, 1,
					)
				}
				abrirLista(getString(R.string.resultados_de, resultados.size, texto), elementos, resultados.indexOfFirst { it.bloque >= actual }.coerceAtLeast(0))
			} else {
				val siguiente = resultados.firstOrNull { it.bloque > actual } ?: resultados.first()
				avisar(getString(R.string.n_resultados, resultados.size), mostrar = false)
				irA(siguiente.bloque, desdeSalto = true)
			}
		}
	}

	private fun quitarTildes(t: String): String =
		Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
			.let { if (it.length == t.length) it else t }

	// --- Marcadores y notas -------------------------------------------------------------------

	private var subrayadosPorBloque: Map<Int, List<com.xebolax.lector.datos.Subrayado>> = emptyMap()

	private fun cargarSubrayados() {
		if (idLibro <= 0) return
		val antes = subrayadosPorBloque.keys
		subrayadosPorBloque = db.subrayados(idLibro).groupBy { it.bloque }
		(antes + subrayadosPorBloque.keys).forEach { adaptador?.notifyItemChanged(it) }
	}

	override fun subrayados(bloque: Int): List<IntRange> = subrayadosPorBloque[bloque].orEmpty().map { it.inicio until it.fin }

	/** Las categorías de los subrayados (las de siempre y las que el usuario haya escrito). */
	private fun categoriasSubrayado(): List<String> =
		(resources.getStringArray(R.array.categorias_subrayado).toList() + db.subrayados(idLibro).map { it.categoria }).filter { it.isNotBlank() }.distinct()

	/** Subrayar el párrafo entero o una de sus frases, con una categoría; o quitar un subrayado. */
	private fun subrayar(bloque: Int) {
		if (idLibro <= 0) return avisar(getString(R.string.solo_biblioteca))
		val bl = doc?.bloques?.getOrNull(bloque) ?: return
		val frases = Regex("""[^.!?…;]+[.!?…;]*""").findAll(bl.texto).map { it.range }.filter { r -> bl.texto.substring(r.first, r.last + 1).isNotBlank() }.toList()
		val opciones = mutableListOf<Accion>(getString(R.string.todo_el_parrafo) to { elegirCategoria(bloque, 0 until bl.texto.length) })
		if (frases.size > 1) frases.forEachIndexed { i, r ->
			opciones += getString(R.string.frase_n, i + 1, bl.texto.substring(r.first, r.last + 1).trim().take(80)) to { elegirCategoria(bloque, r) }
		}
		subrayadosPorBloque[bloque].orEmpty().forEach { su ->
			opciones += getString(R.string.quitar_subrayado_x, su.texto.take(60)) to {
				db.borrarSubrayado(su.id)
				cargarSubrayados()
				avisar(getString(R.string.subrayado_quitado))
			}
		}
		elegir(getString(R.string.subrayar), opciones)
	}

	private fun elegirCategoria(bloque: Int, rango: IntRange) {
		val bl = doc?.bloques?.getOrNull(bloque) ?: return
		val categorias = categoriasSubrayado()
		val guardar = { categoria: String ->
			val ini = rango.first.coerceIn(0, bl.texto.length)
			val fin = (rango.last + 1).coerceIn(ini, bl.texto.length)
			db.anadirSubrayado(idLibro, bloque, ini, fin, bl.texto.substring(ini, fin).trim(), categoria)
			cargarSubrayados()
			avisar(getString(R.string.subrayado_hecho, categoria))
		}
		elegir(getString(R.string.categoria_subrayado), categorias.map { c -> c to { guardar(c) } } + (getString(R.string.otra_categoria) to {
			pedirTexto(getString(R.string.otra_categoria), getString(R.string.nombre)) { t -> if (t.isNotBlank()) guardar(t.trim()) }
		}))
	}

	/** Los subrayados del libro: ir a uno (con Editar categoría y Borrar), o exportarlos por categorías. */
	private fun listaSubrayados() {
		val lista = db.subrayados(idLibro)
		if (lista.isEmpty()) return avisar(getString(R.string.sin_subrayados))
		val elementos = lista.map { su -> ListaActivity.Item("${su.categoria}: ${su.texto.take(120)}", lugar(su.bloque), su.bloque, 1, su.id) }
		ListaActivity.alAccion = { item, accion ->
			when (accion) {
				ListaActivity.ACCION_BORRAR -> db.borrarSubrayado(item.id)
				ListaActivity.ACCION_EDITAR -> {
					val categorias = categoriasSubrayado()
					elegirUno(getString(R.string.categoria_subrayado), categorias, -1) { i -> db.categoriaSubrayado(item.id, categorias[i]) }
				}
			}
			cargarSubrayados()
		}
		ListaActivity.etiquetaEditar = getString(R.string.cambiar_categoria)
		abrirLista(getString(R.string.subrayados), elementos, lista.indexOfLast { it.bloque <= actual }.coerceAtLeast(0))
	}

	/** Exporta los subrayados agrupados por categoría (con el sitio de cada uno). */
	private fun exportarSubrayados() {
		val lista = db.subrayados(idLibro)
		if (lista.isEmpty()) return avisar(getString(R.string.sin_subrayados))
		val texto = buildString {
			appendLine(libro?.tituloVisible.orEmpty())
			for ((categoria, suyos) in lista.groupBy { it.categoria }) {
				appendLine()
				appendLine(categoria)
				suyos.forEach { appendLine("- ${it.texto} (${lugar(it.bloque)})") }
			}
		}
		compartirTexto(texto)
	}

	private fun cargarMarcadores() {
		if (idLibro <= 0) return
		val antes = marcadoresPorBloque.keys
		marcadores = db.marcadores(idLibro).filter { !it.posicion.audio }
		marcadoresPorBloque = marcadores.groupBy { it.posicion.bloque }
		(antes + marcadoresPorBloque.keys).forEach { adaptador?.refrescarEstado(it) }
	}

	private fun lugar(bloque: Int): String {
		val d = doc ?: return ""
		return listOfNotNull(
			d.capituloDe(bloque)?.titulo,
			d.paginaDe(bloque)?.let { getString(R.string.pagina_n, it.etiqueta) },
			getString(R.string.porcentaje_n, d.porcentaje(bloque).toInt()),
		).joinToString(" · ")
	}

	private fun alternarMarcador(bloque: Int) {
		if (idLibro <= 0) return avisar(getString(R.string.solo_biblioteca))
		val existentes = marcadoresPorBloque[bloque]
		if (existentes != null) {
			existentes.forEach { db.borrarMarcador(it.id) }
			avisar(getString(R.string.marcador_quitado))
		} else {
			val texto = doc?.bloques?.getOrNull(bloque)?.texto.orEmpty()
			db.anadirMarcador(idLibro, Posicion.texto(bloque), texto, "", lugar(bloque))
			Sonidos.sonar(this, Sonidos.Tipo.MARCADOR)
			avisar(getString(R.string.marcador_anadido))
		}
		cargarMarcadores()
	}

	private fun anadirNota(bloque: Int) {
		if (idLibro <= 0) return avisar(getString(R.string.solo_biblioteca))
		pedirTexto(getString(R.string.anadir_nota), getString(R.string.nota), multilinea = true) { nota ->
			if (nota.isBlank()) return@pedirTexto
			db.anadirMarcador(idLibro, Posicion.texto(bloque), doc?.bloques?.getOrNull(bloque)?.texto.orEmpty(), nota, lugar(bloque))
			Sonidos.sonar(this, Sonidos.Tipo.NOTA)
			avisar(getString(R.string.nota_anadida))
			cargarMarcadores()
		}
	}

	private fun listaMarcadores() {
		if (idLibro <= 0) return avisar(getString(R.string.solo_biblioteca))
		cargarMarcadores()
		val opciones = mutableListOf<Accion>(
			getString(R.string.anadir_marcador_aqui) to { if (!marcadoresPorBloque.containsKey(actual)) alternarMarcador(actual) else avisar(getString(R.string.ya_hay_marcador)) },
			getString(R.string.anadir_nota_aqui) to { anadirNota(actual) },
		)
		if (marcadores.isNotEmpty()) {
			opciones += getString(R.string.ver_marcadores_n, marcadores.size) to {
				val elementos = marcadores.map { m ->
					val titulo = if (m.nota.isNotBlank()) getString(R.string.nota_x, m.nota) else m.extracto.take(100)
					ListaActivity.Item(titulo, m.lugar, m.posicion.bloque, 1, m.id)
				}
				ListaActivity.alAccion = { item, accion -> accionMarcador(item.id, accion) }
				abrirLista(getString(R.string.marcadores_y_notas), elementos, marcadores.indexOfLast { it.posicion.bloque <= actual }.coerceAtLeast(0))
			}
			opciones += getString(R.string.exportar_marcadores) to { exportarMarcadores() }
		}
		// Los subrayados, junto a los marcadores
		val subrayados = db.subrayados(idLibro).size
		if (subrayados > 0) {
			opciones += getString(R.string.ver_subrayados_n, subrayados) to { listaSubrayados() }
			opciones += getString(R.string.exportar_subrayados) to { exportarSubrayados() }
		}
		elegir(getString(R.string.marcadores_y_notas), opciones)
	}

	/** Acciones sobre un marcador desde su lista (editar nota, borrar). */
	private fun accionMarcador(id: Long, accion: String) {
		val m = marcadores.firstOrNull { it.id == id } ?: return
		when (accion) {
			ListaActivity.ACCION_EDITAR -> pedirTexto(getString(R.string.editar_nota), getString(R.string.nota), m.nota, multilinea = true) {
				db.editarNota(id, it)
				cargarMarcadores()
			}
			ListaActivity.ACCION_BORRAR -> {
				db.borrarMarcador(id)
				cargarMarcadores()
			}
		}
	}

	private fun exportarMarcadores() {
		val l = libro ?: return
		val texto = buildString {
			appendLine(l.tituloVisible)
			appendLine()
			for (m in marcadores) {
				appendLine("— ${m.lugar}")
				appendLine(m.extracto)
				if (m.nota.isNotBlank()) appendLine(getString(R.string.nota_x, m.nota))
				appendLine()
			}
		}
		compartirTexto(texto)
	}

	// --- Voz -----------------------------------------------------------------------------

	private fun leerDesde(bloque: Int) {
		val d = doc ?: return
		val l = libro ?: return
		moverActual(bloque)
		LectorVoz.leerDesde(d, l.id, d.titulo.ifBlank { l.tituloVisible }, bloque, noticia = esNoticia)
		// El mini reproductor y la notificación vuelven aquí (una noticia no tiene id de libro)
		LectorVoz.volver = Intent(this, LectorActivity::class.java).putExtras(intent).apply { removeExtra(EXTRA_LEER_AL_ABRIR) }
	}

	private fun alternarVoz() {
		if (LectorVoz.esActual(idLibro) && LectorVoz.documento === doc) LectorVoz.alternar() else leerDesde(actual)
	}

	private var ultimoBloqueVoz = -1

	private fun alCambiarVoz(e: LectorVoz.Estado) {
		val propio = e.activo && LectorVoz.documento === doc
		b.panelVoz.isVisible = propio
		val leyendo = propio && e.leyendo
		b.vozPausa.contentDescription = getString(if (leyendo) R.string.pausar else R.string.reanudar)
		b.vozPausa.setIconResource(if (leyendo) R.drawable.ic_pausa else R.drawable.ic_reproducir)
		b.botonVoz.contentDescription = getString(if (leyendo) R.string.pausar_lectura else R.string.leer_en_voz_alta)
		b.botonVoz.setIconResource(if (leyendo) R.drawable.ic_pausa else R.drawable.ic_voz)
		actualizarVelocidad()
		val a = adaptador ?: return
		if (!propio) {
			a.resaltado = null
			return
		}
		// Con TalkBack no se resalta: cambiar el texto del párrafo enfocado hace que TalkBack lo vuelva a leer
		a.resaltado = if (p.resaltarFrase && !lectorActivo() && e.fin > e.inicio) Resaltado(e.bloque, e.inicio, e.fin) else null
		if (leyendo && e.bloque != ultimoBloqueVoz) {
			ultimoBloqueVoz = e.bloque
			actual = e.bloque
			// La lista sigue a la voz, sin mover el foco de TalkBack (no se oyen dos voces).
			// Con TalkBack no se desplaza: el foco podría saltar a otro párrafo y leerse encima de la voz
			if (p.seguirLectura && !lectorActivo()) {
				val gestor = b.lista.layoutManager as LinearLayoutManager
				if (e.bloque < gestor.findFirstVisibleItemPosition() || e.bloque > gestor.findLastCompletelyVisibleItemPosition()) {
					gestor.scrollToPositionWithOffset(e.bloque, 0)
				}
			}
		} else if (!leyendo && ultimoBloqueVoz >= 0) {
			// Al pausar, la posición se queda donde iba la voz. Con TalkBack no se mueve el foco
			// (TalkBack leería el párrafo entero al pausar); sin TalkBack se muestra ese párrafo
			ultimoBloqueVoz = -1
			actual = e.bloque
			if (!lectorActivo()) enfocar(e.bloque)
		}
	}

	private fun cambiarVelocidad(paso: Int) {
		// De 5 en 5 y siempre en múltiplos de 5 (como en el reproductor y en Ajustes)
		val v = LectorVoz.velocidadActual()
		LectorVoz.cambiarVelocidad(if (paso > 0) (v / 5 + 1) * 5 else ((v - 1) / 5) * 5)
		actualizarVelocidad()
		avisar(getString(R.string.velocidad_n, LectorVoz.velocidadActual()), mostrar = false)
	}

	private fun elegirVelocidad() {
		val valores = (5..40).map { it * 10 }
		val actualV = LectorVoz.velocidadActual()
		elegirUno(getString(R.string.velocidad_voz), valores.map { "$it %" }, valores.indexOfFirst { it >= actualV }.coerceAtLeast(0)) { i ->
			LectorVoz.cambiarVelocidad(valores[i])
			actualizarVelocidad()
		}
	}

	private fun actualizarVelocidad() {
		val v = getString(R.string.velocidad_n, LectorVoz.velocidadActual())
		b.vozVelocidad.text = "${LectorVoz.velocidadActual()} %"
		b.vozVelocidad.valor = v
		b.vozVelocidad.contentDescription = getString(R.string.velocidad_voz)
	}

	private fun temporizador() {
		elegirTemporizador(getString(R.string.temporizador), conQuitar = true) { minutos ->
			LectorVoz.temporizador(minutos)
			avisar(if (minutos == 0) getString(R.string.temporizador_quitado) else getString(R.string.temporizador_puesto, nombreTemporizador(minutos)))
		}
	}

	/** Mantener pulsado Voz o Reproducir: elegir el temporizador y empezar a leer con él. */
	private fun leerConTemporizador() {
		elegirTemporizador(getString(R.string.reproducir_con_temporizador), conQuitar = false) { minutos ->
			if (!(LectorVoz.esActual(idLibro) && LectorVoz.documento === doc && LectorVoz.leyendo)) {
				if (LectorVoz.esActual(idLibro) && LectorVoz.documento === doc) LectorVoz.reanudar() else leerDesde(actual)
			}
			LectorVoz.temporizador(minutos)
			avisar(getString(R.string.temporizador_puesto, nombreTemporizador(minutos)))
		}
	}

	// --- Texto: copiar, exportar, seleccionar -------------------------------------------------------

	private fun seleccionarTexto(bloque: Int) {
		val d = doc ?: return
		// El capítulo actual en un campo de texto: se puede mover el cursor, seleccionar y copiar
		val capitulos = d.indice.map { it.bloque }.sorted()
		val inicio = capitulos.lastOrNull { it <= bloque } ?: 0
		val fin = (capitulos.firstOrNull { it > bloque } ?: d.bloques.size).coerceAtMost(inicio + 3000)
		val texto = d.bloques.subList(inicio, fin).joinToString("\n\n") { it.texto }
		TextoActivity.texto = texto
		startActivity(
			Intent(this, TextoActivity::class.java)
				.putExtra(TextoActivity.EXTRA_TITULO, d.capituloDe(bloque)?.titulo ?: title.toString())
				.putExtra(TextoActivity.EXTRA_CURSOR, d.bloques.subList(inicio, bloque).sumOf { it.texto.length + 2 })
		)
	}

	/** Imagen del párrafo: enviarla a una app que la describa (Be My Eyes, Gemini, Lookout...) o verla. */
	private fun describirImagen(bloque: Int) {
		val recurso = doc?.imagenes?.get(bloque) ?: return
		val l = libro ?: return
		avisar(getString(R.string.preparando_imagen))
		lifecycleScope.launch {
			val archivo = withContext(Dispatchers.IO) { ImagenLibro.preparar(this@LectorActivity, Uri.parse(l.uri), l.nombre, recurso) }
				?: return@launch avisar(getString(R.string.imagen_no_disponible))
			val texto = doc?.bloques?.getOrNull(bloque)?.texto.orEmpty()
			opcionesImagen(getString(R.string.describir_imagen), archivo, getString(R.string.ia_pedir_descripcion, texto.take(300)), texto)
		}
	}

	/** Lo que se puede hacer con una imagen o una página: describirla con IA, leer su texto, enviarla o verla. */
	private fun opcionesImagen(titulo: String, archivo: java.io.File, pregunta: String, texto: String) {
		val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.archivos", archivo)
		elegir(titulo, listOf(
			getString(R.string.describir_aqui) to { conversarConIA(titulo, pregunta, archivo) },
			getString(R.string.ocr_leer_texto) to {
				lifecycleScope.launch {
					try {
						val leido = conEspera(getString(R.string.ocr_leer_texto), getString(R.string.ocr_reconociendo)) { com.xebolax.lector.ocr.Ocr.reconocer(this@LectorActivity, archivo) }
						if (leido.isBlank()) mensaje(getString(R.string.ocr_leer_texto), getString(R.string.ocr_sin_texto))
						else MaterialAlertDialogBuilder(this@LectorActivity)
							.setTitle(R.string.ocr_leer_texto)
							.setMessage(leido)
							.setPositiveButton(R.string.aceptar, null)
							.setNeutralButton(R.string.copiar) { _, _ -> copiar(leido) }
							.show()
					} catch (e: kotlinx.coroutines.CancellationException) {
						throw e
					} catch (e: Exception) {
						mensaje(getString(R.string.ocr_leer_texto), e.message ?: getString(R.string.ocr_sin_texto))
					}
				}
			},
			getString(R.string.enviar_imagen) to {
				val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg")
					.putExtra(Intent.EXTRA_STREAM, uri)
					.putExtra(Intent.EXTRA_TEXT, getString(R.string.pedir_descripcion))
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				intent.clipData = android.content.ClipData.newRawUri(texto.take(80), uri)
				startActivity(Intent.createChooser(intent, getString(R.string.enviar_imagen)))
			},
			getString(R.string.ver_imagen) to {
				try {
					startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
				} catch (_: ActivityNotFoundException) {
					avisar(getString(R.string.no_disponible))
				}
			},
		))
	}

	private val esPdf get() = libro?.formato == com.xebolax.lector.formatos.Formato.PDF.name
	private val esComic get() = libro?.formato == com.xebolax.lector.formatos.Formato.COMIC.name

	/** PDF: la página como imagen, para describirla, leerla con OCR, enviarla o verla. */
	private fun describirPagina(bloque: Int) {
		val d = doc ?: return
		val l = libro ?: return
		val pagina = d.paginaDe(bloque) ?: return
		val indice = d.paginas.indexOf(pagina)
		avisar(getString(R.string.preparando_imagen))
		lifecycleScope.launch {
			val archivo = withContext(Dispatchers.IO) {
				runCatching {
					val pdf = com.xebolax.lector.formatos.Fuente(this@LectorActivity, Uri.parse(l.uri), l.nombre).archivoLocal()
					com.xebolax.lector.ocr.Ocr.paginaComoImagen(this@LectorActivity, pdf, indice)
				}.getOrNull()
			} ?: return@launch avisar(getString(R.string.imagen_no_disponible))
			val titulo = getString(R.string.describir_pagina_n, pagina.etiqueta)
			opcionesImagen(titulo, archivo, getString(R.string.ia_pedir_pagina), "")
		}
	}

	/** PDF escaneado o cómic: reconoce el texto de todas las páginas y lo guarda como un documento nuevo. */
	private fun ocrPdf() {
		val l = libro ?: return
		val d = doc ?: return
		val espera = MaterialAlertDialogBuilder(this)
			.setTitle(R.string.ocr_todo)
			.setMessage(R.string.ocr_reconociendo)
			.setNegativeButton(R.string.cancelar, null)
			.create()
		val trabajo = lifecycleScope.launch {
			try {
				var ultimoAviso = 0L
				val progreso: (Int, Int) -> Unit = { n, total ->
					runOnUiThread {
						val texto = getString(R.string.ocr_progreso, n, total)
						espera.setMessage(texto)
						// Con TalkBack, un aviso cada 15 segundos como mucho
						if (System.currentTimeMillis() - ultimoAviso > 15_000) {
							ultimoAviso = System.currentTimeMillis()
							avisar(texto)
						}
					}
				}
				val paginas = if (esComic) {
					// Cómic: cada página es una imagen del ZIP
					val recursos = d.imagenes.toSortedMap().values.toList()
					val textos = ArrayList<String>(recursos.size)
					for ((i, recurso) in recursos.withIndex()) {
						ensureActive()
						progreso(i + 1, recursos.size)
						val imagen = withContext(Dispatchers.IO) { ImagenLibro.preparar(this@LectorActivity, Uri.parse(l.uri), l.nombre, recurso) }
						textos += imagen?.let { com.xebolax.lector.ocr.Ocr.reconocer(this@LectorActivity, it) }.orEmpty()
					}
					textos
				} else {
					val pdf = withContext(Dispatchers.IO) { com.xebolax.lector.formatos.Fuente(this@LectorActivity, Uri.parse(l.uri), l.nombre).archivoLocal() }
					com.xebolax.lector.ocr.Ocr.reconocerPdf(this@LectorActivity, pdf, progreso)
				}
				val id = withContext(Dispatchers.IO) {
					com.xebolax.lector.ocr.Ocr.guardar(this@LectorActivity, d.titulo.ifBlank { l.tituloVisible }, paginas, l.carpeta)
				}
				espera.dismiss()
				avisar(getString(R.string.ocr_hecho))
				Abiertos.abrirPantalla(this@LectorActivity, id)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				espera.dismiss()
				mensaje(getString(R.string.ocr_todo), e.message ?: getString(R.string.ocr_sin_texto))
			}
		}
		espera.setOnDismissListener { if (trabajo.isActive) trabajo.cancel() }
		espera.show()
	}

	// --- Inteligencia artificial ---------------------------------------------------------

	/** Resumir, preguntar, explicar o traducir con la IA (Ajustes, Inteligencia artificial). */
	private fun menuIA(bloque: Int) {
		val esLibro = tipo == Tipo.LIBRO
		val resumir = if (esLibro) getString(R.string.ia_resumir_capitulo) else getString(R.string.ia_resumir_tipo, nombreTipo())
		val preguntar = if (esLibro) getString(R.string.ia_preguntar_capitulo) else getString(R.string.ia_preguntar_tipo, nombreTipo())
		elegir(getString(R.string.ia_menu), listOf(
			resumir to { preguntarIA(bloque, "resumir_capitulo") },
			getString(R.string.ia_resumir_hasta_aqui) to { preguntarIA(bloque, "resumir_hasta_aqui") },
			preguntar to {
				pedirTexto(preguntar, getString(R.string.ia_tu_pregunta), multilinea = true) { t ->
					if (t.isNotBlank()) preguntarIA(bloque, "preguntar", t)
				}
			},
			getString(R.string.ia_explicar_parrafo) to { preguntarIA(bloque, "explicar") },
			getString(R.string.traducir_parrafo) to { traducir(bloque) },
		) + (if (esPdf || esComic) listOf(getString(R.string.ocr_todo) to { ocrPdf() }) else emptyList()))
	}

	/** Bloques del capítulo (o sección) en el que está un bloque. */
	private fun rangoCapitulo(bloque: Int): IntRange {
		val d = doc ?: return IntRange.EMPTY
		val inicio = d.capituloDe(bloque)?.bloque ?: 0
		val fin = d.indice.firstOrNull { it.bloque > bloque }?.bloque ?: d.bloques.size
		return inicio until fin
	}

	private fun textoDe(rango: IntRange): String {
		val d = doc ?: return ""
		return rango.mapNotNull { d.bloques.getOrNull(it)?.texto }.joinToString("\n")
	}

	private fun preguntarIA(bloque: Int, tipo: String, pregunta: String = "") {
		val d = doc ?: return
		val tituloLibro = d.titulo.ifBlank { libro?.tituloVisible.orEmpty() }
		lifecycleScope.launch {
			val maximo = com.xebolax.lector.ia.AsistenteIA.maximoTexto(this@LectorActivity)
			val capitulo = d.capituloDe(bloque)?.titulo.orEmpty()
			// Artículos y textos: se habla de «este artículo» y se usa el texto entero, no un capítulo
			if (this@LectorActivity.tipo != Tipo.LIBRO) {
				val n = nombreTipo()
				val todo = textoDe(0 until d.bloques.size)
				val (titulo, peticion) = when (tipo) {
					"resumir_capitulo" -> getString(R.string.ia_resumir_tipo, n) to getString(R.string.ia_pedir_resumen_tipo, n, tituloLibro, todo.take(maximo))
					"resumir_hasta_aqui" -> getString(R.string.ia_resumir_hasta_aqui) to getString(R.string.ia_pedir_hasta_aqui_tipo, n, tituloLibro, textoDe(0..bloque).takeLast(maximo))
					"preguntar" -> getString(R.string.ia_preguntar_tipo, n) to getString(R.string.ia_pedir_pregunta_tipo, n, tituloLibro, todo.take(maximo), pregunta)
					else -> getString(R.string.ia_explicar_parrafo) to getString(R.string.ia_pedir_explicacion_tipo, n, tituloLibro, d.bloques.getOrNull(bloque)?.texto.orEmpty().take(maximo))
				}
				conversarConIA(titulo, peticion)
				return@launch
			}
			val (titulo, peticion) = when (tipo) {
				"resumir_capitulo" -> getString(R.string.ia_resumir_capitulo) to
					getString(R.string.ia_pedir_resumen_capitulo, tituloLibro, capitulo, textoDe(rangoCapitulo(bloque)).take(maximo))
				"resumir_hasta_aqui" -> getString(R.string.ia_resumir_hasta_aqui) to
					getString(R.string.ia_pedir_resumen_hasta_aqui, tituloLibro, textoDe(0..bloque).takeLast(maximo))
				"preguntar" -> getString(R.string.ia_preguntar_capitulo) to
					getString(R.string.ia_pedir_pregunta, tituloLibro, capitulo, textoDe(rangoCapitulo(bloque)).take(maximo), pregunta)
				else -> getString(R.string.ia_explicar_parrafo) to
					getString(R.string.ia_pedir_explicacion, tituloLibro, d.bloques.getOrNull(bloque)?.texto.orEmpty().take(maximo))
			}
			conversarConIA(titulo, peticion)
		}
	}

	/** Traducir el párrafo: con la app de Google Traductor (sin internet si tiene el idioma) o con la IA. */
	private fun traducir(bloque: Int) {
		val texto = doc?.bloques?.getOrNull(bloque)?.texto ?: return
		val opciones = mutableListOf<Accion>()
		val traductor = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
			.putExtra(Intent.EXTRA_PROCESS_TEXT, texto).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
			.setPackage("com.google.android.apps.translate")
		if (traductor.resolveActivity(packageManager) != null) opciones += getString(R.string.traducir_con_google) to { startActivity(traductor) }
		opciones += getString(R.string.traducir_con_ia) to {
			val idioma = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.getDefault())
			conversarConIA(getString(R.string.traducir_parrafo), getString(R.string.ia_pedir_traduccion, idioma, texto))
		}
		opciones += getString(R.string.traducir_otra_app) to {
			startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), getString(R.string.traducir_parrafo)))
		}
		if (opciones.size == 1) opciones.first().second() else elegir(getString(R.string.traducir_parrafo), opciones)
	}

	private fun compartirTexto(texto: String) {
		startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), getString(R.string.compartir)))
	}

	private fun exportarTexto(uri: Uri) {
		val d = doc ?: return
		lifecycleScope.launch {
			val ok = withContext(Dispatchers.IO) {
				runCatching {
					contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
						for (bl in d.bloques) {
							w.write(bl.texto)
							w.write("\n\n")
						}
					}
				}.isSuccess
			}
			avisar(getString(if (ok) R.string.texto_exportado else R.string.error_exportar))
		}
	}

	private fun informacion() {
		val d = doc ?: return
		val l = libro ?: return
		val palabras = d.bloques.sumOf { Textos.palabras(it.texto) }
		val texto = buildString {
			appendLine(getString(R.string.info_titulo, d.titulo.ifBlank { l.tituloVisible }))
			if (d.autor.isNotBlank()) appendLine(getString(R.string.info_autor, d.autor))
			if (l.formato.isNotBlank()) appendLine(getString(R.string.info_formato, l.formato))
			for ((k, v) in d.metadatos) if (k != "Autor") appendLine("$k: ${v.take(500)}")
			appendLine(getString(R.string.info_palabras, palabras))
			appendLine(getString(R.string.info_parrafos, d.bloques.size))
			appendLine(getString(R.string.info_encabezados, d.bloques.count { it.tipo == TipoBloque.ENCABEZADO }))
			if (d.paginas.isNotEmpty()) appendLine(getString(R.string.info_paginas, d.paginas.size))
			// Unas 230 palabras por minuto leyendo con la vista, 160 con voz a velocidad normal
			appendLine(getString(R.string.info_tiempo_lectura, tiempoHablado(palabras * 60_000L / 160, conSegundos = false)))
			if (l.segundos > 0) appendLine(getString(R.string.info_tiempo_leido, tiempoHablado(l.segundos * 1000, conSegundos = false)))
			if (l.uri.isNotBlank() && l.id > 0) appendLine(getString(R.string.info_archivo, l.nombre))
			for (a in d.avisos) appendLine(a)
		}
		mensaje(getString(R.string.informacion), texto.trim())
	}

	// --- Menú ------------------------------------------------------------------------------

	// Todas las acciones están en la botonera, por orden de importancia; el menú Más
	// solo tiene las que no caben (nada repetido).
	/** Noticias: artículo abierto y la lista de la que viene (para Siguiente y Anterior). */
	private val articuloRss: Long get() = intent.getLongExtra(com.xebolax.lector.rss.RssAcciones.EXTRA_ARTICULO, -1)

	/**
	 * Qué se está leyendo, para que botones y textos cuadren con la sección:
	 * un libro de la biblioteca, un artículo de Noticias o un texto suelto
	 * (notas o transcripción de un episodio de Podcasts).
	 */
	private enum class Tipo { LIBRO, ARTICULO, TEXTO }

	private val tipo: Tipo get() = when {
		articuloRss > 0 -> Tipo.ARTICULO
		intent.getLongExtra(EXTRA_LIBRO, -1) <= 0 && intent.getStringExtra(EXTRA_URI) != null -> Tipo.TEXTO
		else -> Tipo.LIBRO
	}

	/** «artículo» o «texto», para los textos que no son de un libro. */
	private fun nombreTipo() = getString(if (tipo == Tipo.ARTICULO) R.string.tipo_articulo else R.string.tipo_texto)

	/** Botones y descripciones según lo que se lee (llamar al crear la pantalla). */
	private fun adaptarATipo() {
		if (tipo == Tipo.LIBRO) return
		b.botonBuscar.contentDescription = getString(R.string.buscar_en_tipo, nombreTipo())
		b.botonSeleccionar.contentDescription = getString(R.string.seleccionar_texto_tipo)
	}

	/** Botones propios de las noticias (solo al leer un artículo). */
	private val botonesRss: List<com.google.android.material.button.MaterialButton> by lazy {
		if (articuloRss <= 0) return@lazy emptyList()
		fun boton(texto: Int, descripcion: Int, icono: Int, accion: () -> Unit) =
			(layoutInflater.inflate(R.layout.boton_barra, b.botonera, false) as com.google.android.material.button.MaterialButton).apply {
				id = View.generateViewId()
				setText(texto)
				contentDescription = getString(descripcion)
				setIconResource(icono)
				setOnClickListener { accion() }
			}
		listOf(
			boton(R.string.rss_siguiente_corto, R.string.rss_siguiente_articulo, R.drawable.ic_siguiente_largo) { otroArticulo(+1) },
			boton(R.string.rss_anterior_corto, R.string.rss_anterior_articulo, R.drawable.ic_anterior_largo) { otroArticulo(-1) },
			boton(R.string.rss_favorito_corto, R.string.rss_anadir_favorito, R.drawable.ic_marcador) { favoritoRss() }.also {
				botonFavorito = it
				ponerFavorito()
			},
			boton(R.string.rss_biblioteca_corto, R.string.rss_guardar_biblioteca, R.drawable.ic_biblioteca) {
				com.xebolax.lector.rss.RssBaseDatos.obtener(this).articulo(articuloRss)?.let { com.xebolax.lector.rss.RssAcciones.guardarEnBiblioteca(this, it) { ponerGuardar() } }
			}.also {
				botonGuardar = it
				ponerGuardar()
			},
			boton(R.string.rss_web_corto, R.string.abrir_navegador, R.drawable.ic_ir) {
				com.xebolax.lector.rss.RssBaseDatos.obtener(this).articulo(articuloRss)?.enlace?.takeIf { it.isNotBlank() }
					?.let { com.xebolax.lector.rss.RssAcciones.abrirNavegador(this, it) }
			},
		)
	}

	/** Siguiente o anterior artículo de la lista de la que se vino. */
	private fun otroArticulo(paso: Int) {
		val lista = intent.getLongArrayExtra(com.xebolax.lector.rss.RssAcciones.EXTRA_LISTA) ?: return
		val i = lista.indexOf(articuloRss)
		val destino = lista.getOrNull(i + paso) ?: return avisar(getString(if (paso > 0) R.string.rss_no_hay_siguiente else R.string.rss_no_hay_anterior))
		val art = com.xebolax.lector.rss.RssBaseDatos.obtener(this).articulo(destino) ?: return
		com.xebolax.lector.rss.RssAcciones.abrir(this, art, lista)
	}

	private var botonFavorito: com.google.android.material.button.MaterialButton? = null
	private var botonGuardar: com.google.android.material.button.MaterialButton? = null

	/** El botón Guardar dice lo que hará: guardar en la biblioteca o abrirlo si ya está. */
	private fun ponerGuardar() {
		val art = com.xebolax.lector.rss.RssBaseDatos.obtener(this).articulo(articuloRss) ?: return
		val ya = com.xebolax.lector.rss.RssAcciones.enBiblioteca(this, art) != null
		botonGuardar?.contentDescription = getString(if (ya) R.string.rss_abrir_en_biblioteca else R.string.rss_guardar_biblioteca)
		botonGuardar?.let { ViewCompat.setTooltipText(it, it.contentDescription) }
		invalidateOptionsMenu()
	}

	/** El botón Favorito dice lo que hará: añadir a favoritos o quitar de favoritos. */
	private fun ponerFavorito() {
		val favorito = com.xebolax.lector.rss.RssBaseDatos.obtener(this).articulo(articuloRss)?.favorito == true
		botonFavorito?.contentDescription = getString(if (favorito) R.string.rss_quitar_favorito else R.string.rss_anadir_favorito)
		botonFavorito?.let { ViewCompat.setTooltipText(it, it.contentDescription) }
		invalidateOptionsMenu()
	}

	private fun favoritoRss() {
		val db = com.xebolax.lector.rss.RssBaseDatos.obtener(this)
		val art = db.articulo(articuloRss) ?: return
		db.marcarFavorito(art.id, !art.favorito)
		avisar(getString(if (art.favorito) R.string.rss_quitado_favorito else R.string.rss_anadido_favorito))
		ponerFavorito()
	}

	private val botonesBarra by lazy {
		if (tipo == Tipo.LIBRO) listOf(
			b.botonIndice, b.botonBuscar, b.botonIr, b.botonMarcadores, b.botonVoz, b.botonDonde, b.botonIa, b.botonElementos,
			b.botonTemporizador, b.botonAspecto, b.botonAjustesLibro, b.botonInformacion, b.botonSeleccionar,
			b.botonExportar,
		)
		// Artículos y textos: la voz lo primero, a mano; sin lo que solo vale para libros de la
		// biblioteca (marcadores, su voz, información y exportar a audio)
		else listOf(b.botonVoz) + botonesRss + listOf(
			b.botonIndice, b.botonBuscar, b.botonIr, b.botonDonde, b.botonIa, b.botonElementos,
			b.botonTemporizador, b.botonAspecto, b.botonSeleccionar,
		)
	}
	private var enMenu: List<com.google.android.material.button.MaterialButton> = emptyList()

	/** Reparte los botones en filas según el ancho; los que no caben van al menú Más. */
	private fun repartirBotonera() {
		val ancho = b.botonera.width - b.botonera.paddingLeft - b.botonera.paddingRight
		if (ancho <= 0) return
		val escala = resources.configuration.fontScale.coerceIn(1f, 1.6f)
		val minimo = (64 * resources.displayMetrics.density * escala).toInt()
		val porFila = (ancho / minimo).coerceAtLeast(1)
		val caben = if (p.filasBotonera >= 4) Int.MAX_VALUE else porFila * p.filasBotonera
		// Los que se ocultan en Ajustes van directamente al menú Más
		val ocultos = p.botonesOcultos
		val candidatos = botonesBarra.filter { b -> runCatching { resources.getResourceEntryName(b.id) }.getOrNull() !in ocultos }
		val visibles = if (candidatos.size <= caben) candidatos else candidatos.take(caben)
		val filas = (visibles.size + porFila - 1) / porFila
		// Filas igualadas: 14 botones que caben de 8 en 8 van en 2 filas de 7
		val enCadaFila = (visibles.size + filas - 1) / filas
		b.botonera.removeAllViews()
		for (grupo in visibles.chunked(enCadaFila)) {
			val fila = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
			for (boton in grupo) {
				(boton.parent as? android.view.ViewGroup)?.removeView(boton)
				fila.addView(boton, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			}
			// Huecos en la última fila para que todos los botones midan lo mismo
			repeat(enCadaFila - grupo.size) { fila.addView(View(this), android.widget.LinearLayout.LayoutParams(0, 1, 1f)) }
			b.botonera.addView(fila)
		}
		enMenu = botonesBarra.filter { it !in visibles }
		enMenu.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
		invalidateOptionsMenu()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean = true

	override fun onPrepareOptionsMenu(menu: Menu): Boolean {
		menu.clear()
		for (boton in enMenu) menu.add(Menu.NONE, boton.id, Menu.NONE, boton.contentDescription)
		return enMenu.isNotEmpty()
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		val boton = enMenu.firstOrNull { it.id == item.itemId } ?: return super.onOptionsItemSelected(item)
		boton.performClick()
		return true
	}

	/** Cambios rápidos de aspecto sin salir del libro. */
	private fun aspectoRapido() {
		elegir(getString(R.string.aspecto), listOf(
			getString(R.string.letra_mas_grande) to { cambiarLetra(+2) },
			getString(R.string.letra_mas_pequena) to { cambiarLetra(-2) },
			getString(R.string.tema_lectura) to {
				val valores = resources.getStringArray(R.array.tema_lectura_valores)
				val nombres = resources.getStringArray(R.array.tema_lectura_nombres)
				elegirUno(getString(R.string.tema_lectura), nombres.toList(), valores.indexOf(p.temaLectura)) { i ->
					p.p.edit().putString("tema_lectura", valores[i]).apply()
					aplicarPreferencias()
				}
			},
		))
	}

	private fun cambiarLetra(paso: Int) {
		val nuevo = (p.tamanoLetra + paso).coerceIn(10, 60)
		p.p.edit().putInt("tamano_letra", nuevo).apply()
		aplicarPreferencias()
		avisar(getString(R.string.tamano_letra_n, nuevo), mostrar = false)
	}

	/** Lo que se lee es una noticia (un artículo, varios seguidos o los titulares). */
	private val esNoticia: Boolean
		get() = articuloRss > 0 || intent.hasExtra(EXTRA_SEGUIDOS) || intent.hasExtra(com.xebolax.lector.rss.RssAcciones.EXTRA_LISTA)

	/**
	 * Voz propia de este libro (por ejemplo, una voz inglesa para un libro en
	 * inglés): motor, voz, velocidad, tono, idiomas y pausas. En una noticia,
	 * la voz de las noticias.
	 */
	private fun ajustesDelLibro() {
		val l = libro ?: return
		if (esNoticia) return com.xebolax.lector.voz.VozPerfilActivity.abrirNoticias(this)
		if (l.id <= 0) return avisar(getString(R.string.solo_biblioteca))
		com.xebolax.lector.voz.VozPerfilActivity.abrirLibro(this, l.id, l.tituloVisible)
	}

	// --- Teclado ----------------------------------------------------------------------------

	override fun dispatchKeyEvent(evento: KeyEvent): Boolean {
		if (evento.action == KeyEvent.ACTION_DOWN && atenderTecla(evento)) return true
		if (evento.action == KeyEvent.ACTION_UP && esVolumenPropio(evento.keyCode)) return true
		return super.dispatchKeyEvent(evento)
	}

	private fun esVolumenPropio(codigo: Int) =
		(codigo == KeyEvent.KEYCODE_VOLUME_UP || codigo == KeyEvent.KEYCODE_VOLUME_DOWN) && p.teclasVolumen != "nada" && doc != null &&
			!(LectorVoz.leyendo && LectorVoz.documento === doc)

	private fun atenderTecla(e: KeyEvent): Boolean {
		if (doc == null) return false
		val codigo = e.keyCode
		// Teclas de volumen: navegar (si está activado y la voz no está leyendo)
		if (esVolumenPropio(codigo)) {
			val delante = codigo == KeyEvent.KEYCODE_VOLUME_DOWN
			when (p.teclasVolumen) {
				"elemento" -> saltar(delante)
				"parrafo" -> saltar(delante, Elemento.PARRAFO)
				"pantalla" -> b.lista.smoothScrollBy(0, (if (delante) 1 else -1) * (b.lista.height * 0.9).toInt())
			}
			return true
		}
		// Escribiendo en un campo: nada de teclas rápidas
		if (currentFocus is EditText) return false
		val ctrl = e.isCtrlPressed
		val mayus = e.isShiftPressed
		if (ctrl) {
			when (codigo) {
				KeyEvent.KEYCODE_F -> buscar()
				KeyEvent.KEYCODE_G -> irADialogo()
				KeyEvent.KEYCODE_T -> indice()
				KeyEvent.KEYCODE_B -> if (mayus) listaMarcadores() else alternarMarcador(actual)
				KeyEvent.KEYCODE_N -> anadirNota(actual)
				KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_SPACE -> alternarVoz()
				KeyEvent.KEYCODE_I -> informacion()
				KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MOVE_HOME -> irA(0, desdeSalto = true)
				KeyEvent.KEYCODE_MOVE_END -> irA((doc?.bloques?.size ?: 1) - 1, desdeSalto = true)
				KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> cambiarLetra(+2)
				KeyEvent.KEYCODE_MINUS -> cambiarLetra(-2)
				else -> return false
			}
			return true
		}
		if (e.isAltPressed) {
			when (codigo) {
				KeyEvent.KEYCODE_DPAD_LEFT -> volver()
				KeyEvent.KEYCODE_DPAD_RIGHT -> avanzarHistorial()
				else -> return false
			}
			return true
		}
		when (codigo) {
			KeyEvent.KEYCODE_F3 -> {
				if (busqueda == null) buscar() else saltar(!mayus, Elemento.BUSQUEDA)
				return true
			}
			KeyEvent.KEYCODE_F7 -> {
				listaElementos()
				return true
			}
			KeyEvent.KEYCODE_EQUALS -> {
				dondeEstoy()
				return true
			}
			// Teclas multimedia (teclado o auriculares con la pantalla de lectura delante)
			KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
				alternarVoz()
				return true
			}
			KeyEvent.KEYCODE_MEDIA_PLAY -> {
				if (!(LectorVoz.esActual(idLibro) && LectorVoz.leyendo)) alternarVoz()
				return true
			}
			KeyEvent.KEYCODE_MEDIA_PAUSE -> {
				if (LectorVoz.leyendo) LectorVoz.pausar()
				return true
			}
			KeyEvent.KEYCODE_MEDIA_NEXT -> {
				if (LectorVoz.esActual(idLibro)) LectorVoz.siguienteFrase() else saltar(true)
				return true
			}
			KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
				if (LectorVoz.esActual(idLibro)) LectorVoz.anteriorFrase() else saltar(false)
				return true
			}
		}
		val caracter = e.unicodeChar.toChar()
		Elemento.deTecla(if (codigo in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_6) ('1' + (codigo - KeyEvent.KEYCODE_1)) else caracter)?.let { el ->
			if (el in elementosDisponibles || el == Elemento.MARCADOR) saltar(!mayus, el)
			else {
				Sonidos.sonar(this, Sonidos.Tipo.LIMITE)
				avisar(getString(R.string.no_hay_mas, getString(el.nombre).lowercase()))
			}
			return true
		}
		return false
	}
}
