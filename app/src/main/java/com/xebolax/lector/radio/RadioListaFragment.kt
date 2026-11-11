package com.xebolax.lector.radio

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.xebolax.lector.R
import com.xebolax.lector.audio.ControlAudio
import com.xebolax.lector.databinding.FragmentLibrosBinding
import com.xebolax.lector.databinding.ItemListaBinding
import com.xebolax.lector.ui.Accion
import com.xebolax.lector.ui.ZonaAcciones
import com.xebolax.lector.ui.accionesAccesibles
import com.xebolax.lector.ui.accionesOrden
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.campoTexto
import com.xebolax.lector.ui.conEspera
import com.xebolax.lector.ui.confirmar
import com.xebolax.lector.ui.dp
import com.xebolax.lector.ui.elegir
import com.xebolax.lector.ui.elegirCarpeta
import com.xebolax.lector.ui.elegirUno
import com.xebolax.lector.ui.mensaje
import com.xebolax.lector.ui.pedirNombreCarpeta
import com.xebolax.lector.ui.pedirTexto
import com.xebolax.lector.ui.sinFocoPropio
import com.xebolax.lector.ui.tamanoLegible
import com.xebolax.lector.ui.tiempoCorto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Una pestaña de la Radio. Cada fila es un solo elemento para TalkBack
 * («Sonando, Radio 3, España, pop, MP3 128 kbps») con sus acciones; tocar
 * escucha (o entra), mantener pulsado muestra todas las acciones.
 */
class RadioListaFragment : Fragment() {

	enum class Modo(val nombre: Int, val icono: Int) {
		FAVORITAS(R.string.radio_pestana_favoritas, R.drawable.ic_estrella),
		EXPLORAR(R.string.radio_pestana_explorar, R.drawable.ic_globo),
		BUSCAR(R.string.radio_pestana_buscar, R.drawable.ic_buscar),
		HISTORIAL(R.string.radio_pestana_historial, R.drawable.ic_recientes),
		PROGRAMAR(R.string.radio_pestana_programar, R.drawable.ic_alarma),
	}

	/** Listas fijas del directorio, en la raíz de Explorar. */
	enum class Lista(val nombre: Int, val orden: RadioBrowser.Orden) {
		DE_TU_PAIS(R.string.radio_de_tu_pais, RadioBrowser.Orden.CLICS),
		MAS_ESCUCHADAS(R.string.radio_mas_escuchadas, RadioBrowser.Orden.CLICS),
		MAS_VOTADAS(R.string.radio_mas_votadas, RadioBrowser.Orden.VOTOS),
		TENDENCIA(R.string.radio_tendencia, RadioBrowser.Orden.TENDENCIA),
		SONANDO(R.string.radio_sonando_ahora, RadioBrowser.Orden.SONANDO),
		NOVEDADES(R.string.radio_novedades, RadioBrowser.Orden.CAMBIOS),
	}

	enum class TipoCategoria(val nombre: Int, val ayuda: Int) {
		GENERO(R.string.radio_por_genero, R.string.radio_filtrar_generos),
		PAIS(R.string.radio_por_pais, R.string.radio_filtrar_paises),
		IDIOMA(R.string.radio_por_idioma, R.string.radio_filtrar_idiomas),
	}

	/** Dónde se está dentro de Explorar. */
	private sealed class Nivel {
		data object Raiz : Nivel()
		data class DeLista(val lista: Lista) : Nivel()
		data class Categorias(val tipo: TipoCategoria) : Nivel()
		data class DeCategoria(val tipo: TipoCategoria, val categoria: Categoria) : Nivel()
	}

	enum class VistaHistorial(val nombre: Int) {
		EMISORAS(R.string.radio_ver_emisoras),
		CANCIONES(R.string.radio_ver_canciones),
		GRABACIONES(R.string.radio_grabaciones),
	}

	companion object {
		val PESTANAS = Modo.entries.toList()

		fun nuevo(modo: Modo) = RadioListaFragment().apply { arguments = bundleOf("modo" to modo.name) }

		/** Géneros, países e idiomas ya descargados (no cambian en una sesión), con y sin las emisoras que no funcionan. */
		private val categorias = HashMap<Pair<TipoCategoria, Boolean>, List<Categoria>>()

		/** Órdenes que se ofrecen en una lista de emisoras. */
		private val ORDENES = listOf(RadioBrowser.Orden.CLICS, RadioBrowser.Orden.VOTOS, RadioBrowser.Orden.TENDENCIA, RadioBrowser.Orden.NOMBRE, RadioBrowser.Orden.CALIDAD)
	}

	private sealed class Fila {
		class Subir(val nombre: String) : Fila()
		class Carpeta(val nombre: String, val emisoras: Int) : Fila()
		class DeEmisora(val emisora: Emisora, val extra: String = "") : Fila()
		class DeLista(val lista: Lista) : Fila()
		class DeTipo(val tipo: TipoCategoria) : Fila()
		class DeCategoria(val tipo: TipoCategoria, val categoria: Categoria) : Fila()
		class DeCancion(val cancion: Cancion) : Fila()
		class DeGrabacion(val grabacion: Grabacion) : Fila()
		class DeProgramacion(val programacion: Programacion) : Fila()
		data object CargarMas : Fila()
	}

	private var _b: FragmentLibrosBinding? = null
	private val b get() = _b!!
	private lateinit var modo: Modo
	private val db by lazy { RadioBaseDatos.obtener(requireContext()) }
	private var filas: List<Fila> = emptyList()
	private var carga: Job? = null
	private val actividad get() = activity as? RadioActivity

	// Favoritas
	private var carpeta: String? = null

	// Explorar y Buscar: emisoras descargadas y si hay más
	private var pila = mutableListOf<Nivel>(Nivel.Raiz)
	private val nivel get() = pila.last()
	private var emisoras: List<Emisora> = emptyList()
	private var hayMas = false
	private var orden = RadioBrowser.Orden.CLICS
	private var filtroCategorias = ""

	// Buscar
	private var filtro = RadioBrowser.Filtro()
	private var buscado = false

	// Historial
	private var vista = VistaHistorial.EMISORAS

	// Explorar y Buscar: si se está descargando una lista y si falló (para no quedarse en «Cargando…» para siempre)
	private var cargando = false
	private var falloCarga = false

	// Lo que las filas consultan al dibujarse, leído en cada recarga fuera del hilo principal
	private var misFavoritas: List<Favorita> = emptyList()
	private var uuidsFavoritas: Set<String> = emptySet()
	private var misCarpetas: List<String> = emptyList()
	/** Canciones: las emisoras conocidas (favoritas o escuchadas) por su id. */
	private var conocidas: Map<String, Emisora> = emptyMap()
	private var proximaProgramacion: Pair<Long, Programacion>? = null

	/** Lo que lee una recarga fuera del hilo principal. */
	private class Datos(val filas: List<Fila>, val favoritas: List<Favorita>, val carpetas: List<String>, val conocidas: Map<String, Emisora>, val proxima: Pair<Long, Programacion>?)

	/** El botón de la cabecera que se pulsó (pauta 34: si la cabecera se rehace, el foco vuelve a su equivalente). */
	private var clavePulsada: String? = null
	/** Lo que se está grabando según la cabecera (para rehacerla si la grabación empieza o acaba sola). */
	private var cabeceraGrabando: String? = null
	/** Si la cabecera de Programar tiene «Cancelar despertador pospuesto». */
	private var cabeceraPospuesto = false

	// Cambios en segundo plano (una canción nueva, una grabación): se recarga sin anunciar el recuento
	private val oyenteDb = RadioBaseDatos.Oyente { view?.post { recargar(anunciar = false) } }
	// Lo que suena cambia el estado de las filas («Sonando»)
	private var ultimoSonando: String? = null
	private val oyenteAudio = ControlAudio.Oyente {
		val ahora = Radio.actual?.uuid?.takeIf { ControlAudio.reproduciendo }
		if (ahora != ultimoSonando) {
			ultimoSonando = ahora
			view?.post { _b?.recycler?.adapter?.notifyDataSetChanged() }
		}
	}
	private val atras = object : OnBackPressedCallback(false) {
		override fun handleOnBackPressed() = subir()
	}

	override fun onCreateView(inflador: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
		_b = FragmentLibrosBinding.inflate(inflador, contenedor, false)
		return b.root
	}

	override fun onViewCreated(vista: View, estado: Bundle?) {
		modo = Modo.valueOf(requireArguments().getString("modo", Modo.FAVORITAS.name))
		b.recycler.layoutManager = LinearLayoutManager(requireContext())
		b.recycler.sinFocoPropio()
		b.recycler.adapter = Adaptador()
		requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, atras)
		construirCabecera()
	}

	override fun onResume() {
		super.onResume()
		db.suscribir(oyenteDb)
		ControlAudio.registrar(oyenteAudio)
		recargar()
		atras.isEnabled = puedeSubir()
	}

	override fun onPause() {
		db.desuscribir(oyenteDb)
		ControlAudio.desregistrar(oyenteAudio)
		atras.isEnabled = false
		super.onPause()
	}

	override fun onDestroyView() {
		_b = null
		super.onDestroyView()
	}

	private fun puedeSubir() = (modo == Modo.FAVORITAS && carpeta != null) || (modo == Modo.EXPLORAR && pila.size > 1)

	// --- Cabecera ----------------------------------------------------------------------------

	private fun boton(texto: String, clave: String = texto, accion: () -> Unit) = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
		text = texto
		tag = clave
		minHeight = dp(requireContext(), 48)
		setOnClickListener {
			clavePulsada = clave
			accion()
		}
	}

	private fun fila(vararg vistas: View) = LinearLayout(requireContext()).apply {
		orientation = LinearLayout.HORIZONTAL
		vistas.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(requireContext(), 4) }) }
	}

	private fun nombreOrden(o: RadioBrowser.Orden) = getString(when (o) {
		RadioBrowser.Orden.VOTOS -> R.string.radio_orden_votos
		RadioBrowser.Orden.TENDENCIA -> R.string.radio_orden_tendencia
		RadioBrowser.Orden.NOMBRE -> R.string.radio_orden_nombre
		RadioBrowser.Orden.CALIDAD -> R.string.radio_orden_calidad
		RadioBrowser.Orden.RELEVANCIA -> R.string.radio_orden_relevancia
		else -> R.string.radio_orden_clics
	})

	private fun construirCabecera() {
		val c = _b?.cabecera ?: return
		// El foco volverá al botón equivalente: si lo tenía uno de la cabecera, o si se rehace con un diálogo encima
		val conDialogo = activity?.hasWindowFocus() == false
		val volverA = claveConFoco(c) ?: clavePulsada?.takeIf { conDialogo }
		clavePulsada = null
		c.removeAllViews()
		cabeceraGrabando = ServicioGrabacion.grabando?.uuid
		cabeceraPospuesto = modo == Modo.PROGRAMAR && RadioProgramador.pospuestoHasta(requireContext()) > 0
		when (modo) {
			Modo.FAVORITAS -> {
				val nombres = resources.getStringArray(R.array.radio_ordenes_favoritas).toList()
				val actual = Radio.prefs(requireContext()).getInt("radio_orden_favoritas", 0).coerceIn(0, nombres.size - 1)
				c.addView(fila(
					if (carpeta != null) boton(getString(R.string.opciones_carpeta)) { carpeta?.let { n -> actividad?.elegir(n, accionesCarpeta(n)) } }
					else boton(getString(R.string.nueva_carpeta)) { nuevaCarpeta() },
					boton(getString(R.string.etiqueta_valor, getString(R.string.orden), nombres[actual]), "orden") {
						requireContext().elegirUno(getString(R.string.orden), nombres, actual) { i ->
							Radio.prefs(requireContext()).edit().putInt("radio_orden_favoritas", i).apply()
							construirCabecera()
							recargar()
						}
					},
				))
			}
			Modo.EXPLORAR -> when (val n = nivel) {
				is Nivel.Raiz -> c.addView(boton(getString(R.string.radio_tu_pais, nombrePais(paisElegido())), "pais") { elegirPais() })
				is Nivel.Categorias -> c.addView(campoFiltro(getString(n.tipo.ayuda), filtroCategorias) {
					filtroCategorias = it
					recargar()
				})
				is Nivel.DeCategoria, is Nivel.DeLista -> if (n !is Nivel.DeLista || n.lista in setOf(Lista.DE_TU_PAIS)) {
					c.addView(boton(getString(R.string.etiqueta_valor, getString(R.string.orden), nombreOrden(orden)), "orden") {
						requireContext().elegirUno(getString(R.string.orden), ORDENES.map { nombreOrden(it) }, ORDENES.indexOf(orden)) { i ->
							orden = ORDENES[i]
							construirCabecera()
							cargarEmisoras(desde = 0)
						}
					})
				}
			}
			Modo.BUSCAR -> {
				val (contenedor, campo) = requireContext().campoTexto(getString(R.string.radio_nombre_buscar), filtro.nombre)
				campo.imeOptions = EditorInfo.IME_ACTION_SEARCH
				campo.isSingleLine = true
				campo.minHeight = dp(requireContext(), 48)
				contenedor.endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
				campo.setOnEditorActionListener { _, accion, _ ->
					if (accion == EditorInfo.IME_ACTION_SEARCH) {
						filtro = filtro.copy(nombre = campo.text.toString())
						buscar()
						true
					} else false
				}
				campo.addTextChangedListener(object : TextWatcher {
					override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
					override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
					override fun afterTextChanged(s: Editable?) {
						filtro = filtro.copy(nombre = s?.toString().orEmpty())
					}
				})
				c.addView(contenedor)
				c.addView(fila(
					boton(getString(R.string.radio_buscar_emisoras)) { buscar() },
					boton(getString(R.string.radio_filtros_x, resumenFiltro()), "filtros") { elegirFiltros() },
				))
			}
			Modo.HISTORIAL -> {
				val botones = mutableListOf<View>(boton(getString(R.string.etiqueta_valor, getString(R.string.radio_ver), getString(vista.nombre)), "ver") {
					val vistas = VistaHistorial.entries
					requireContext().elegirUno(getString(R.string.radio_ver), vistas.map { getString(it.nombre) }, vistas.indexOf(vista)) { i ->
						vista = vistas[i]
						construirCabecera()
						recargar()
					}
				})
				when (vista) {
					VistaHistorial.EMISORAS -> botones += boton(getString(R.string.radio_vaciar_historial)) {
						actividad?.confirmar(getString(R.string.radio_confirmar_vaciar_historial), getString(R.string.vaciar)) { db.vaciarHistorial() }
					}
					VistaHistorial.CANCIONES -> botones += boton(getString(R.string.radio_vaciar_canciones)) {
						actividad?.confirmar(getString(R.string.radio_confirmar_vaciar_canciones), getString(R.string.vaciar)) { db.vaciarCanciones() }
					}
					VistaHistorial.GRABACIONES -> ServicioGrabacion.grabando?.let { g ->
						botones += boton(getString(R.string.radio_detener_grabacion_x, g.nombre)) {
							ServicioGrabacion.parar(requireContext())
							actividad?.avisar(getString(R.string.radio_grabacion_guardada))
							view?.postDelayed({ construirCabecera() }, 500)
						}
					}
				}
				c.addView(fila(*botones.toTypedArray()))
			}
			Modo.PROGRAMAR -> {
				c.addView(fila(
					boton(getString(R.string.radio_nuevo_despertador)) { editarProgramacion(null, Programacion.DESPERTADOR, null) },
					boton(getString(R.string.radio_nueva_grabacion)) { editarProgramacion(null, Programacion.GRABACION, null) },
				))
				// Con un despertador pospuesto, cancelarlo también desde aquí (no solo desde su aviso)
				if (cabeceraPospuesto) c.addView(fila(boton(getString(R.string.audio_cancelar_pospuesto)) {
					RadioProgramador.cancelarPospuesto(requireContext())
					actividad?.avisar(getString(R.string.audio_pospuesto_cancelado))
				}))
			}
		}
		// Pauta 34: los botones son nuevos y MemoriaFoco no encuentra el que se pulsó; se lleva el foco al que lo sustituye
		if (volverA != null) devolverFoco(c, volverA, conDialogo)
	}

	/** La clave (tag) del botón de la cabecera que tiene el foco de TalkBack, si lo tiene alguno. */
	private fun claveConFoco(v: View): String? {
		if (!v.isShown) return null
		if (v is ViewGroup) for (i in 0 until v.childCount) claveConFoco(v.getChildAt(i))?.let { return it }
		return (v.tag as? String)?.takeIf { runCatching { v.createAccessibilityNodeInfo().isAccessibilityFocused }.getOrDefault(false) }
	}

	/**
	 * Pauta 34: la cabecera se ha rehecho (sus botones son nuevos). El foco va al botón equivalente
	 * o, si ya no está, al primero; tras un diálogo, después de que MemoriaFoco lo intente.
	 */
	private fun devolverFoco(c: ViewGroup, clave: String, conDialogo: Boolean) {
		val destino = c.findViewWithTag<View>(clave) ?: primerBoton(c) ?: return
		destino.postDelayed({
			if (destino.isAttachedToWindow && destino.isShown) destino.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
		}, if (conDialogo) 700L else 100L)
	}

	private fun primerBoton(v: View): View? {
		if (v.tag is String && v.isShown) return v
		if (v is ViewGroup) for (i in 0 until v.childCount) primerBoton(v.getChildAt(i))?.let { return it }
		return null
	}

	private fun campoFiltro(pista: String, inicial: String, alCambiar: (String) -> Unit): View {
		val (contenedor, campo) = requireContext().campoTexto(pista, inicial)
		contenedor.endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
		campo.isSingleLine = true
		campo.minHeight = dp(requireContext(), 48)
		campo.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
			override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
			override fun afterTextChanged(s: Editable?) = alCambiar(s?.toString().orEmpty())
		})
		return contenedor
	}

	// --- País, filtros y búsqueda ------------------------------------------------------------------

	/** El país de «Las de tu país»: el elegido o el del móvil (SIM, red o idioma; antes solo el idioma, y «Español (Estados Unidos)» daba Estados Unidos). */
	private fun paisElegido(): String = Radio.prefs(requireContext()).getString("radio_pais", null)?.takeIf { it.length == 2 }
		?: com.xebolax.lector.datos.Pais.detectado(requireContext())

	private fun nombrePais(codigo: String) = Locale("", codigo).displayCountry.ifBlank { codigo }

	private fun ocultarRotas() = Radio.prefs(requireContext()).getBoolean("radio_ocultar_rotas", true)

	/** Los géneros, países o idiomas ya descargados con el ajuste «Ocultar las que no funcionan» de ahora. */
	private fun categoriasDe(tipo: TipoCategoria): List<Categoria>? = categorias[tipo to ocultarRotas()]

	/** Lista de países (descargada una vez) para elegir el tuyo o filtrar una búsqueda. */
	private fun conCategorias(tipo: TipoCategoria, hacer: (List<Categoria>) -> Unit) {
		categoriasDe(tipo)?.let { return hacer(it) }
		val a = actividad ?: return
		viewLifecycleOwner.lifecycleScope.launch {
			val lista = runCatching { a.conEspera(getString(tipo.nombre), getString(R.string.cargando)) { cargarCategorias(tipo) } }
				.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
			if (lista.isNullOrEmpty()) {
				// No se quedan en «Cargando…»: se dice que no se pudo
				falloCarga = true
				recargar()
				return@launch a.mensaje(getString(tipo.nombre), getString(R.string.radio_sin_directorio))
			}
			hacer(lista)
		}
	}

	private suspend fun cargarCategorias(tipo: TipoCategoria): List<Categoria> {
		val ocultar = ocultarRotas()
		return categorias[tipo to ocultar] ?: when (tipo) {
			TipoCategoria.GENERO -> RadioBrowser.generos(ocultar)
			TipoCategoria.PAIS -> RadioBrowser.paises(ocultar)
			TipoCategoria.IDIOMA -> RadioBrowser.idiomas(ocultar)
		}.also { categorias[tipo to ocultar] = it }
	}

	private fun elegirPais() {
		conCategorias(TipoCategoria.PAIS) { paises ->
			val actual = paisElegido()
			requireContext().elegirUno(getString(R.string.radio_elegir_pais), paises.map { "${it.nombre} (${it.emisoras})" }, paises.indexOfFirst { it.clave == actual }) { i ->
				Radio.prefs(requireContext()).edit().putString("radio_pais", paises[i].clave).apply()
				construirCabecera()
				recargar()
			}
		}
	}

	private fun resumenFiltro(): String {
		val partes = listOfNotNull(
			filtro.codigoPais.takeIf { it.isNotBlank() }?.let { nombrePais(it) },
			filtro.idioma.takeIf { it.isNotBlank() }?.let { i -> categoriasDe(TipoCategoria.IDIOMA)?.firstOrNull { it.clave == i }?.nombre ?: i },
			filtro.genero.takeIf { it.isNotBlank() },
			filtro.bitrateMin.takeIf { it > 0 }?.let { getString(R.string.radio_desde_kbps, it) },
		)
		return partes.joinToString(", ").ifBlank { getString(R.string.radio_sin_filtros) }
	}

	/** Filtros de la búsqueda: país, idioma, género, calidad mínima y orden. */
	private fun elegirFiltros() {
		val a = actividad ?: return
		val opciones = listOf<Accion>(
			getString(R.string.etiqueta_valor, getString(R.string.radio_pais), filtro.codigoPais.takeIf { it.isNotBlank() }?.let { nombrePais(it) } ?: getString(R.string.radio_cualquiera)) to {
				conCategorias(TipoCategoria.PAIS) { l ->
					val nombres = listOf(getString(R.string.radio_cualquiera)) + l.map { it.nombre }
					a.elegirUno(getString(R.string.radio_pais), nombres, l.indexOfFirst { it.clave == filtro.codigoPais } + 1) { i ->
						filtro = filtro.copy(codigoPais = if (i == 0) "" else l[i - 1].clave)
						alCambiarFiltro()
					}
				}
			},
			getString(R.string.etiqueta_valor, getString(R.string.radio_idioma), filtro.idioma.ifBlank { getString(R.string.radio_cualquiera) }) to {
				conCategorias(TipoCategoria.IDIOMA) { l ->
					val nombres = listOf(getString(R.string.radio_cualquiera)) + l.map { it.nombre }
					a.elegirUno(getString(R.string.radio_idioma), nombres, l.indexOfFirst { it.clave == filtro.idioma } + 1) { i ->
						filtro = filtro.copy(idioma = if (i == 0) "" else l[i - 1].clave)
						alCambiarFiltro()
					}
				}
			},
			getString(R.string.etiqueta_valor, getString(R.string.radio_genero), filtro.genero.ifBlank { getString(R.string.radio_cualquiera) }) to {
				a.pedirTexto(getString(R.string.radio_genero), getString(R.string.radio_genero_ayuda), filtro.genero) { t ->
					filtro = filtro.copy(genero = t.trim())
					alCambiarFiltro()
				}
			},
			getString(R.string.etiqueta_valor, getString(R.string.radio_calidad_minima), if (filtro.bitrateMin > 0) getString(R.string.radio_desde_kbps, filtro.bitrateMin) else getString(R.string.radio_cualquiera)) to {
				val valores = listOf(0, 64, 96, 128, 192, 256, 320)
				a.elegirUno(getString(R.string.radio_calidad_minima), valores.map { if (it == 0) getString(R.string.radio_cualquiera) else getString(R.string.radio_desde_kbps, it) }, valores.indexOf(filtro.bitrateMin).coerceAtLeast(0)) { i ->
					filtro = filtro.copy(bitrateMin = valores[i])
					alCambiarFiltro()
				}
			},
			getString(R.string.etiqueta_valor, getString(R.string.orden), nombreOrden(filtro.orden)) to {
				val ordenes = listOf(RadioBrowser.Orden.CLICS, RadioBrowser.Orden.VOTOS, RadioBrowser.Orden.NOMBRE, RadioBrowser.Orden.CALIDAD, RadioBrowser.Orden.TENDENCIA)
				a.elegirUno(getString(R.string.orden), ordenes.map { nombreOrden(it) }, ordenes.indexOf(filtro.orden).coerceAtLeast(0)) { i ->
					filtro = filtro.copy(orden = ordenes[i])
					alCambiarFiltro()
				}
			},
			getString(R.string.radio_quitar_filtros) to {
				filtro = RadioBrowser.Filtro(nombre = filtro.nombre)
				alCambiarFiltro()
			},
		)
		a.elegir(getString(R.string.radio_filtros), opciones)
	}

	private fun alCambiarFiltro() {
		construirCabecera()
		if (buscado || filtro.nombre.isNotBlank()) buscar() else actividad?.avisar(getString(R.string.radio_filtros_x, resumenFiltro()))
	}

	private fun buscar() {
		if (filtro.nombre.isBlank() && filtro.codigoPais.isBlank() && filtro.idioma.isBlank() && filtro.genero.isBlank()) {
			return actividad?.mensaje(getString(R.string.buscar), getString(R.string.radio_buscar_vacio)) ?: Unit
		}
		buscado = true
		cargarEmisoras(desde = 0)
	}

	/** Descarga (o sigue con «Cargar más») la lista de emisoras de lo que se está viendo. */
	private fun cargarEmisoras(desde: Int) {
		val a = actividad ?: return
		val ocultar = Radio.prefs(a).getBoolean("radio_ocultar_rotas", true)
		val f: RadioBrowser.Filtro = when (modo) {
			Modo.BUSCAR -> filtro.copy(ocultarRotas = ocultar)
			else -> when (val n = nivel) {
				is Nivel.DeLista -> when (n.lista) {
					Lista.DE_TU_PAIS -> RadioBrowser.Filtro(codigoPais = paisElegido(), orden = orden, ocultarRotas = ocultar)
					else -> RadioBrowser.Filtro(orden = n.lista.orden, ocultarRotas = ocultar)
				}
				is Nivel.DeCategoria -> when (n.tipo) {
					TipoCategoria.GENERO -> RadioBrowser.Filtro(genero = n.categoria.clave, orden = orden, ocultarRotas = ocultar)
					TipoCategoria.PAIS -> RadioBrowser.Filtro(codigoPais = n.categoria.clave, orden = orden, ocultarRotas = ocultar)
					TipoCategoria.IDIOMA -> RadioBrowser.Filtro(idioma = n.categoria.clave, orden = orden, ocultarRotas = ocultar)
				}
				else -> return
			}
		}
		cargando = true
		falloCarga = false
		viewLifecycleOwner.lifecycleScope.launch {
			val r = runCatching { a.conEspera(titulo(), getString(R.string.radio_buscando_emisoras)) { RadioBrowser.buscar(f, desde) } }
				.onFailure {
					cargando = false
					if (it is kotlinx.coroutines.CancellationException) {
						if (_b != null) recargar()
						throw it
					}
				}
			cargando = false
			val nuevas = r.getOrNull() ?: run {
				// No se queda en «Cargando…»: la línea de estado dice que no se pudo
				falloCarga = true
				recargar()
				return@launch a.mensaje(titulo(), getString(R.string.radio_sin_directorio))
			}
			if (_b == null) return@launch
			val antes = emisoras.size
			emisoras = if (desde == 0) nuevas else (emisoras + nuevas).distinctBy { it.uuid }
			hayMas = nuevas.size >= RadioBrowser.PAGINA
			recargar()
			if (desde == 0) b.recycler.scrollToPosition(0)
			else a.avisar(resources.getQuantityString(R.plurals.radio_n_mas, emisoras.size - antes, emisoras.size - antes))
		}
	}

	private fun titulo(): String = when (modo) {
		Modo.BUSCAR -> getString(R.string.buscar)
		else -> when (val n = nivel) {
			is Nivel.DeLista -> nombreLista(n.lista)
			is Nivel.Categorias -> getString(n.tipo.nombre)
			is Nivel.DeCategoria -> n.categoria.nombre
			Nivel.Raiz -> getString(R.string.radio_pestana_explorar)
		}
	}

	private fun nombreLista(l: Lista) = if (l == Lista.DE_TU_PAIS) getString(R.string.radio_emisoras_de, nombrePais(paisElegido())) else getString(l.nombre)

	// --- Navegación en Explorar y en las carpetas --------------------------------------------------

	private fun entrar(n: Nivel) {
		pila.add(n)
		emisoras = emptyList()
		hayMas = false
		falloCarga = false
		filtroCategorias = ""
		orden = RadioBrowser.Orden.CLICS
		atras.isEnabled = true
		construirCabecera()
		when (n) {
			is Nivel.DeLista, is Nivel.DeCategoria -> {
				recargar()
				cargarEmisoras(desde = 0)
			}
			is Nivel.Categorias -> conCategorias(n.tipo) {
				recargar()
				actividad?.avisar(getString(n.tipo.nombre), mostrar = false)
			}
			Nivel.Raiz -> recargar()
		}
	}

	private fun subir() {
		if (modo == Modo.FAVORITAS) carpeta = null
		else if (pila.size > 1) {
			pila.removeAt(pila.size - 1)
			// Al volver a una lista de emisoras, se vuelve a cargar
			if (nivel is Nivel.DeLista || nivel is Nivel.DeCategoria) cargarEmisoras(desde = 0) else emisoras = emptyList()
		}
		atras.isEnabled = puedeSubir()
		construirCabecera()
		recargar()
	}

	private fun nuevaCarpeta() {
		val a = actividad ?: return
		a.pedirNombreCarpeta(getString(R.string.nueva_carpeta)) { nombre ->
			val ya = db.carpetas().any { it.equals(nombre, ignoreCase = true) }
			val n = db.crearCarpeta(nombre)
			a.avisar(getString(if (ya) R.string.carpeta_ya_existe else R.string.carpeta_creada, n))
		}
	}

	private fun entrarCarpeta(n: String) {
		carpeta = n
		atras.isEnabled = true
		construirCabecera()
		recargar()
		actividad?.avisar(getString(R.string.carpeta_x, n), mostrar = false)
	}

	private fun accionesCarpeta(n: String): List<Accion> {
		val a = actividad ?: return emptyList()
		// De la última recarga (esto se llama al dibujar cada fila)
		val carpetas = misCarpetas
		return listOf<Accion>(
			getString(R.string.abrir) to { entrarCarpeta(n) },
			getString(R.string.radio_escuchar_carpeta) to {
				val l = db.favoritas().filter { it.carpeta.equals(n, true) }.map { it.emisora }
				if (l.isEmpty()) a.avisar(getString(R.string.carpeta_vacia_corto)) else Radio.escuchar(a, l.first(), l)
			},
			getString(R.string.renombrar) to {
				a.pedirNombreCarpeta(getString(R.string.renombrar), n) { nuevo ->
					db.renombrarCarpeta(n, nuevo)
					if (carpeta == n) carpeta = db.carpetas().firstOrNull { it.equals(nuevo, true) } ?: nuevo
					a.avisar(getString(R.string.carpeta_renombrada, nuevo))
				}
			},
			getString(R.string.borrar_carpeta) to { borrarCarpeta(n) },
		) + a.accionesOrden(carpetas, carpetas.firstOrNull { it.equals(n, true) } ?: n) { db.ordenarCarpetas(it) }
	}

	private fun borrarCarpeta(n: String) {
		val a = actividad ?: return
		val suyas = db.favoritas().count { it.carpeta.equals(n, true) }
		val hecho = {
			if (carpeta.equals(n, true)) {
				carpeta = null
				atras.isEnabled = false
				construirCabecera()
			}
			a.avisar(getString(R.string.carpeta_borrada_x, n))
		}
		if (suyas == 0) return a.confirmar(getString(R.string.confirmar_borrar_carpeta_vacia, n), getString(R.string.borrar)) {
			db.borrarCarpeta(n, conEmisoras = false)
			hecho()
		}
		a.elegir(getString(R.string.borrar_carpeta_x, n), listOf<Accion>(
			resources.getQuantityString(R.plurals.radio_borrar_solo_carpeta, suyas, suyas) to {
				db.borrarCarpeta(n, conEmisoras = false)
				hecho()
			},
			resources.getQuantityString(R.plurals.radio_borrar_carpeta_y_emisoras, suyas, suyas) to {
				a.confirmar(resources.getQuantityString(R.plurals.radio_confirmar_borrar_carpeta_y_emisoras, suyas, n, suyas), getString(R.string.borrar)) {
					db.borrarCarpeta(n, conEmisoras = true)
					hecho()
				}
			},
		))
	}

	/** Desde la notificación de una grabación. */
	fun verGrabaciones() {
		vista = VistaHistorial.GRABACIONES
		if (_b == null) return
		construirCabecera()
		recargar()
	}

	// --- Datos ------------------------------------------------------------------------------------

	/** Las favoritas en el orden elegido. Sin contexto: se llama fuera del hilo principal. */
	private fun ordenarFavoritas(l: List<Favorita>, orden: Int, base: RadioBaseDatos): List<Favorita> = when (orden) {
		1 -> l.sortedWith(compareBy(java.text.Collator.getInstance()) { it.emisora.nombre })
		2 -> base.historial().associate { it.emisora.uuid to it.segundos }.let { s -> l.sortedByDescending { s[it.emisora.uuid] ?: 0L } }
		else -> l
	}

	private fun recargar(anunciar: Boolean = true) {
		if (_b == null) return
		val app = requireContext().applicationContext
		val m = modo
		val car = carpeta
		val texto = filtroCategorias.trim()
		val n = nivel
		// Copias de lo que el hilo principal puede cambiar mientras se lee (la pila de Explorar, las emisoras descargadas…)
		val lista = emisoras
		val mas = hayMas
		val vistaH = vista
		val ordenFav = Radio.prefs(app).getInt("radio_orden_favoritas", 0)
		val cats = (n as? Nivel.Categorias)?.let { categoriasDe(it.tipo) }.orEmpty()
		val subirExplorar = if (pila.size > 2) titulo(pila[pila.size - 2]) else getString(R.string.radio_pestana_explorar)
		val base = db
		carga?.cancel()
		carga = viewLifecycleOwner.lifecycleScope.launch {
			// Todo lo que las filas consultan al dibujarse se lee aquí, una vez por recarga (sin base de datos en el hilo principal)
			val datos = withContext(Dispatchers.IO) {
				val favoritas = base.favoritas()
				val carpetas = base.carpetas()
				var porUuid: Map<String, Emisora> = emptyMap()
				val nuevas: List<Fila> = when (m) {
					Modo.FAVORITAS -> {
						val todas = ordenarFavoritas(favoritas, ordenFav, base)
						if (car != null) listOf<Fila>(Fila.Subir(app.getString(R.string.radio_pestana_favoritas))) +
							todas.filter { it.carpeta.equals(car, true) }.map { Fila.DeEmisora(it.emisora) }
						else carpetas.map { c -> Fila.Carpeta(c, todas.count { it.carpeta.equals(c, true) }) } +
							todas.filter { f -> f.carpeta.isBlank() || carpetas.none { it.equals(f.carpeta, true) } }.map { Fila.DeEmisora(it.emisora) }
					}
					Modo.EXPLORAR -> when (n) {
						Nivel.Raiz -> Lista.entries.map { Fila.DeLista(it) } + TipoCategoria.entries.map { Fila.DeTipo(it) }
						is Nivel.Categorias -> listOf<Fila>(Fila.Subir(app.getString(R.string.radio_pestana_explorar))) +
							cats.filter { texto.length < 2 || it.nombre.contains(texto, true) }.map { Fila.DeCategoria(n.tipo, it) }
						else -> listOf<Fila>(Fila.Subir(subirExplorar)) +
							lista.map { Fila.DeEmisora(it) } + (if (mas) listOf(Fila.CargarMas) else emptyList())
					}
					Modo.BUSCAR -> lista.map { Fila.DeEmisora(it) } + (if (mas) listOf(Fila.CargarMas) else emptyList())
					Modo.HISTORIAL -> when (vistaH) {
						VistaHistorial.EMISORAS -> base.historial().map { h ->
							Fila.DeEmisora(h.emisora, app.getString(R.string.radio_escuchada_hace, RadioAcciones.cuando(h.ultima)))
						}
						VistaHistorial.CANCIONES -> {
							// La emisora de cada canción (favorita o escuchada), para «Escuchar…» sin consultar al dibujar
							porUuid = base.historial().associate { it.emisora.uuid to it.emisora } + favoritas.associate { it.emisora.uuid to it.emisora }
							base.canciones().map { Fila.DeCancion(it) }
						}
						VistaHistorial.GRABACIONES -> base.grabaciones().map { Fila.DeGrabacion(it) }
					}
					Modo.PROGRAMAR -> base.programaciones().map { Fila.DeProgramacion(it) }
				}
				val proxima = if (m == Modo.PROGRAMAR) nuevas.mapNotNull { f ->
					(f as? Fila.DeProgramacion)?.programacion?.let { p -> RadioProgramador.proxima(p)?.let { it to p } }
				}.minByOrNull { it.first } else null
				Datos(nuevas, favoritas, carpetas, porUuid, proxima)
			}
			if (_b == null) return@launch
			misFavoritas = datos.favoritas
			uuidsFavoritas = datos.favoritas.mapTo(HashSet()) { it.emisora.uuid }
			misCarpetas = datos.carpetas
			conocidas = datos.conocidas
			proximaProgramacion = datos.proxima
			filas = datos.filas
			@Suppress("NotifyDataSetChanged")
			b.recycler.adapter?.notifyDataSetChanged()
			// Solo si cambia: la línea de estado es una región viva y TalkBack la repetiría en cada recarga
			ponerEstado(estado(), anunciar)
			// La grabación ha empezado o acabado sola: el botón «Detener la grabación de…» de la cabecera, al día
			if (modo == Modo.HISTORIAL && vista == VistaHistorial.GRABACIONES && ServicioGrabacion.grabando?.uuid != cabeceraGrabando) construirCabecera()
			// Se ha pospuesto, ha sonado o se ha cancelado un despertador: el botón de cancelarlo, al día
			if (modo == Modo.PROGRAMAR && (RadioProgramador.pospuestoHasta(app) > 0) != cabeceraPospuesto) construirCabecera()
		}
	}

	/**
	 * La línea de estado es una región viva. Si la recarga viene de un cambio en segundo plano
	 * (una canción nueva, una actualización), el texto cambia sin que TalkBack lo anuncie (pauta 10).
	 */
	private fun ponerEstado(texto: String, anunciar: Boolean) {
		val v = _b?.estado ?: return
		if (v.text.toString() == texto) return
		v.removeCallbacks(regionViva)
		if (anunciar) {
			v.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
			v.text = texto
			return
		}
		v.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
		v.text = texto
		v.postDelayed(regionViva, 1000)
	}

	private val regionViva = Runnable { _b?.estado?.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }

	private fun titulo(n: Nivel): String = when (n) {
		is Nivel.DeLista -> nombreLista(n.lista)
		is Nivel.Categorias -> getString(n.tipo.nombre)
		is Nivel.DeCategoria -> n.categoria.nombre
		Nivel.Raiz -> getString(R.string.radio_pestana_explorar)
	}

	private fun estado(): String {
		val nEmisoras = filas.count { it is Fila.DeEmisora }
		return when (modo) {
			Modo.FAVORITAS -> {
				val total = misFavoritas.size
				when {
					total == 0 -> getString(R.string.radio_sin_favoritas)
					carpeta != null && nEmisoras == 0 -> getString(R.string.carpeta_vacia_corto)
					carpeta != null -> resources.getQuantityString(R.plurals.radio_n_emisoras, nEmisoras, nEmisoras)
					else -> resources.getQuantityString(R.plurals.radio_n_favoritas, total, total)
				}
			}
			Modo.EXPLORAR -> when (val n = nivel) {
				Nivel.Raiz -> getString(R.string.radio_explorar_ayuda)
				is Nivel.Categorias -> {
					val c = filas.count { it is Fila.DeCategoria }
					when {
						categoriasDe(n.tipo) != null -> resources.getQuantityString(R.plurals.radio_n_categorias, c, c, getString(n.tipo.nombre))
						falloCarga -> getString(R.string.radio_sin_directorio)
						else -> getString(R.string.cargando)
					}
				}
				else -> when {
					nEmisoras > 0 -> getString(R.string.radio_n_de, resources.getQuantityString(R.plurals.radio_n_emisoras, nEmisoras, nEmisoras), titulo(n))
					cargando -> getString(R.string.cargando)
					falloCarga -> getString(R.string.radio_sin_directorio)
					else -> getString(R.string.rss_sin_resultados)
				}
			}
			Modo.BUSCAR -> when {
				!buscado -> getString(R.string.radio_buscar_ayuda)
				nEmisoras == 0 -> getString(R.string.rss_sin_resultados)
				else -> resources.getQuantityString(R.plurals.radio_n_encontradas, nEmisoras, nEmisoras)
			}
			Modo.HISTORIAL -> when (vista) {
				VistaHistorial.EMISORAS -> if (nEmisoras == 0) getString(R.string.radio_sin_historial) else resources.getQuantityString(R.plurals.radio_n_emisoras, nEmisoras, nEmisoras)
				VistaHistorial.CANCIONES -> filas.size.let { if (it == 0) getString(R.string.radio_sin_canciones) else resources.getQuantityString(R.plurals.radio_n_canciones, it, it) }
				VistaHistorial.GRABACIONES -> filas.size.let { n ->
					val g = ServicioGrabacion.grabando?.let { getString(R.string.radio_grabando_x, it.nombre) + ". " }.orEmpty()
					g + if (n == 0) getString(R.string.radio_sin_grabaciones) else resources.getQuantityString(R.plurals.radio_n_grabaciones, n, n)
				}
			}
			Modo.PROGRAMAR -> {
				val proximas = proximaProgramacion
				// El despertador pospuesto también se dice (se cancela desde su aviso)
				val pospuesto = RadioProgramador.pospuestoHasta(requireContext()).takeIf { it > 0 }
					?.let { getString(R.string.audio_despertador_pospuesto, RadioProgramador.cuandoLegible(requireContext(), it)) + " " }.orEmpty()
				pospuesto + when {
					filas.isEmpty() -> getString(R.string.radio_sin_programaciones)
					proximas == null -> getString(R.string.radio_ninguna_activa)
					else -> getString(if (proximas.second.esDespertador) R.string.radio_proximo_despertador else R.string.radio_proxima_grabacion,
						RadioProgramador.cuandoLegible(requireContext(), proximas.first), proximas.second.emisora.nombre)
				}
			}
		}
	}

	private fun emisorasVisibles() = filas.mapNotNull { (it as? Fila.DeEmisora)?.emisora }

	// --- Programar -----------------------------------------------------------------------------------

	/** Nuevo despertador o grabación programada (o editar una). */
	fun editarProgramacion(antes: Programacion?, tipo: Int, emisora: Emisora?) {
		val a = actividad ?: return
		val candidatas = (listOfNotNull(Radio.actual) + db.favoritas().map { it.emisora } + db.historial().map { it.emisora }).distinctBy { it.uuid }
		var elegida = antes?.emisora ?: emisora ?: Radio.actual ?: candidatas.firstOrNull()
			?: return a.mensaje(getString(R.string.radio_pestana_programar), getString(R.string.radio_programar_sin_emisoras))
		val ahora = java.util.Calendar.getInstance()
		var hora = antes?.hora ?: if (tipo == Programacion.DESPERTADOR) 7 else ahora.get(java.util.Calendar.HOUR_OF_DAY)
		var minuto = antes?.minuto ?: if (tipo == Programacion.DESPERTADOR) 30 else 0
		var dias = antes?.dias ?: if (tipo == Programacion.DESPERTADOR) RadioProgramador.LABORABLES else 0
		val duraciones = if (tipo == Programacion.DESPERTADOR) listOf(15, 30, 60, 120, 0) else listOf(15, 30, 60, 90, 120, 180, 240)
		var duracion = antes?.duracion ?: if (tipo == Programacion.DESPERTADOR) 60 else 60
		fun nombreDuracion(m: Int) = if (m == 0) getString(R.string.radio_sin_limite) else resources.getQuantityString(R.plurals.minutos_n, m, m)
		fun textoHora() = String.format(Locale.getDefault(), "%d:%02d", hora, minuto)

		val bEmisora = boton("") {}
		val bHora = boton("") {}
		val bDias = boton("") {}
		val bDuracion = boton("") {}
		val activa = com.google.android.material.checkbox.MaterialCheckBox(a).apply {
			text = getString(R.string.radio_activada)
			isChecked = antes?.activa ?: true
			minHeight = dp(a, 48)
		}
		fun poner() {
			bEmisora.text = getString(R.string.etiqueta_valor, getString(R.string.radio_emisora), elegida.nombre)
			bHora.text = getString(R.string.etiqueta_valor, getString(R.string.radio_hora), textoHora())
			bDias.text = getString(R.string.etiqueta_valor, getString(R.string.radio_dias), RadioProgramador.diasLegibles(a, dias))
			bDuracion.text = getString(R.string.etiqueta_valor, getString(if (tipo == Programacion.DESPERTADOR) R.string.radio_sonar_durante else R.string.radio_grabar_durante), nombreDuracion(duracion))
		}
		poner()
		bEmisora.setOnClickListener {
			a.elegirUno(getString(R.string.radio_emisora), candidatas.map { it.nombre }, candidatas.indexOfFirst { it.uuid == elegida.uuid }) { i ->
				elegida = candidatas[i]
				poner()
			}
		}
		bHora.setOnClickListener {
			// Con el teclado (escribir la hora) en lugar del reloj, que con TalkBack es difícil de usar
			val selector = com.google.android.material.timepicker.MaterialTimePicker.Builder()
				.setTimeFormat(if (android.text.format.DateFormat.is24HourFormat(a)) com.google.android.material.timepicker.TimeFormat.CLOCK_24H else com.google.android.material.timepicker.TimeFormat.CLOCK_12H)
				.setInputMode(com.google.android.material.timepicker.MaterialTimePicker.INPUT_MODE_KEYBOARD)
				.setHour(hora).setMinute(minuto)
				.setTitleText(getString(R.string.radio_hora))
				.build()
			selector.addOnPositiveButtonClickListener {
				hora = selector.hour
				minuto = selector.minute
				poner()
			}
			selector.show(childFragmentManager, "hora")
		}
		bDias.setOnClickListener {
			val rapidos = listOf(0, RadioProgramador.TODOS, RadioProgramador.LABORABLES, RadioProgramador.FIN_DE_SEMANA)
			a.elegir(getString(R.string.radio_dias), rapidos.map { d -> RadioProgramador.diasLegibles(a, d) to { dias = d; poner() } } + (getString(R.string.radio_elegir_dias) to {
				val orden = listOf(java.util.Calendar.MONDAY, java.util.Calendar.TUESDAY, java.util.Calendar.WEDNESDAY, java.util.Calendar.THURSDAY,
					java.util.Calendar.FRIDAY, java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY)
				val nombres = java.text.DateFormatSymbols.getInstance().weekdays
				val marcados = orden.map { dias and RadioProgramador.bit(it) != 0 }.toBooleanArray()
				MaterialAlertDialogBuilder(a)
					.setTitle(R.string.radio_dias)
					.setMultiChoiceItems(orden.map { nombres[it] }.toTypedArray(), marcados) { _, i, si -> marcados[i] = si }
					.setPositiveButton(R.string.aceptar) { _, _ ->
						dias = orden.indices.filter { marcados[it] }.sumOf { RadioProgramador.bit(orden[it]) }
						poner()
					}
					.setNegativeButton(R.string.cancelar, null)
					.show()
			}))
		}
		bDuracion.setOnClickListener {
			a.elegirUno(bDuracion.text.toString().substringBefore(':'), duraciones.map { nombreDuracion(it) }, duraciones.indexOf(duracion).coerceAtLeast(0)) { i ->
				duracion = duraciones[i]
				poner()
			}
		}
		val formulario = LinearLayout(a).apply {
			orientation = LinearLayout.VERTICAL
			val m = dp(a, 20)
			setPadding(m, m / 2, m, 0)
			listOf(bEmisora, bHora, bDias, bDuracion, activa).forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
		}
		MaterialAlertDialogBuilder(a)
			.setTitle(if (tipo == Programacion.DESPERTADOR) (if (antes == null) R.string.radio_nuevo_despertador else R.string.radio_editar_despertador)
				else if (antes == null) R.string.radio_nueva_grabacion else R.string.radio_editar_grabacion)
			.setView(android.widget.ScrollView(a).apply { addView(formulario) })
			.setPositiveButton(R.string.guardar) { _, _ ->
				val p = Programacion(antes?.id ?: 0, tipo, elegida, hora, minuto, dias, duracion, activa.isChecked)
				val id = db.guardarProgramacion(p)
				RadioProgramador.programar(a)
				if (tipo == Programacion.DESPERTADOR) a.pedirPermisoAvisos()
				val cuando = RadioProgramador.proxima(p.copy(id = id))
				a.avisar(if (cuando == null) getString(R.string.guardado)
					else getString(if (tipo == Programacion.DESPERTADOR) R.string.radio_despertador_puesto else R.string.radio_grabacion_programada,
						RadioProgramador.cuandoLegible(a, cuando)))
			}
			.setNegativeButton(R.string.cancelar, null)
			.show()
	}

	private fun accionesProgramacion(p: Programacion): List<Accion> {
		val a = actividad ?: return emptyList()
		return listOfNotNull(
			(if (p.activa) getString(R.string.radio_desactivar) else getString(R.string.radio_activar)) to {
				db.activarProgramacion(p.id, !p.activa)
				RadioProgramador.programar(a)
				a.avisar(getString(if (p.activa) R.string.radio_desactivada else R.string.radio_activada_aviso))
			},
			getString(R.string.radio_editar_programacion) to { editarProgramacion(p, p.tipo, null) },
			(getString(R.string.radio_probar_despertador) to { RadioProgramador.despertar(a, p) }).takeIf { p.esDespertador },
			getString(R.string.borrar) to {
				a.confirmar(getString(R.string.radio_confirmar_borrar_programacion), getString(R.string.borrar)) {
					RadioProgramador.quitar(a, p.id)
					db.borrarProgramacion(p.id)
					a.avisar(getString(R.string.borrado))
				}
			},
		)
	}

	// --- Acciones de las filas ---------------------------------------------------------------------

	private fun accionesEmisora(e: Emisora): List<Accion> {
		val a = actividad ?: return emptyList()
		val extra = mutableListOf<Accion>()
		// De la última recarga (esto se llama al dibujar cada fila)
		val fav = if (modo == Modo.FAVORITAS) misFavoritas.firstOrNull { it.emisora.uuid == e.uuid } else null
		if (fav != null) {
			extra += getString(R.string.mover_a_carpeta) to {
				a.elegirCarpeta(getString(R.string.mover_a_carpeta), db.carpetas(), fav.carpeta) { destino ->
					db.ponerCarpeta(e.uuid, destino)
					a.avisar(if (destino.isBlank()) getString(R.string.sin_carpeta) else getString(R.string.movida_a_carpeta, destino))
				}
			}
			// Orden a mano entre las de su carpeta (solo con el orden «A mano»)
			if (Radio.prefs(a).getInt("radio_orden_favoritas", 0) == 0) {
				val vecinas = misFavoritas.filter { it.carpeta.equals(fav.carpeta, true) }.map { it.emisora.uuid }
				extra += a.accionesOrden(vecinas, e.uuid) { nuevo ->
					val resto = db.favoritas().map { it.emisora.uuid }.filter { it !in nuevo }
					db.ordenarFavoritas(nuevo + resto)
				}
			}
		}
		if (modo == Modo.HISTORIAL) extra += getString(R.string.radio_quitar_historial) to {
			db.quitarDelHistorial(e.uuid)
			a.avisar(getString(R.string.radio_quitada_historial, e.nombre))
		}
		return RadioAcciones.deEmisora(a, e, emisorasVisibles(), extra, esFavorita = e.uuid in uuidsFavoritas)
	}

	private fun accionesCancion(c: Cancion): List<Accion> {
		val a = actividad ?: return emptyList()
		val e = conocidas[c.uuid]
		return RadioAcciones.deCancion(a, c.titulo) + listOfNotNull(
			e?.let { getString(R.string.radio_escuchar_x, it.nombre) to { Radio.escuchar(a, it) } },
			// Borrar no se puede deshacer: se pregunta antes
			getString(R.string.borrar) to {
				a.confirmar(getString(R.string.audio_confirmar_borrar_cancion, c.titulo), getString(R.string.borrar)) {
					db.borrarCancion(c.id)
					a.avisar(getString(R.string.borrado))
				}
			},
		)
	}

	private fun accionesGrabacion(g: Grabacion): List<Accion> {
		val a = actividad ?: return emptyList()
		val f = java.io.File(g.archivo)
		// La que se está grabando: solo detenerla (renombrarla o borrarla ahora la perdería)
		if (ServicioGrabacion.grabando != null && f.absolutePath == ServicioGrabacion.archivoActual) return listOf(
			getString(R.string.radio_detener_grabacion) to { ServicioGrabacion.parar(a) },
		)
		return listOf(
			getString(R.string.radio_escuchar) to { RadioAcciones.escucharGrabacion(a, g) },
			getString(R.string.radio_guardar_biblioteca) to { RadioAcciones.guardarEnBiblioteca(a, g) },
			getString(R.string.compartir) to { RadioAcciones.compartirGrabacion(a, g) },
			getString(R.string.renombrar) to {
				a.pedirTexto(getString(R.string.renombrar), getString(R.string.nombre), f.nameWithoutExtension) { nuevo ->
					val limpio = nuevo.replace(Regex("""[\\/:*?"<>|]"""), " ").trim()
					if (limpio.isBlank()) return@pedirTexto
					val destino = java.io.File(f.parentFile, "$limpio.${f.extension}")
					if (destino.exists() || !f.renameTo(destino)) return@pedirTexto a.mensaje(getString(R.string.renombrar), getString(R.string.radio_no_se_pudo_renombrar))
					db.renombrarGrabacion(g.id, destino.absolutePath, g.emisora)
					a.avisar(getString(R.string.guardado))
				}
			},
			getString(R.string.borrar) to {
				a.confirmar(getString(R.string.radio_confirmar_borrar_grabacion, f.nameWithoutExtension), getString(R.string.borrar)) {
					f.delete()
					db.borrarGrabacion(g.id)
					a.avisar(getString(R.string.borrado))
				}
			},
		)
	}

	// --- Lista -----------------------------------------------------------------------------------

	/** «Favorita, sonando» + nombre + país, géneros, calidad. */
	private fun describir(e: Emisora, extra: String): Triple<String, String, String> {
		val estado = listOfNotNull(
			getString(R.string.radio_sonando).takeIf { Radio.suena(e) },
			getString(R.string.radio_grabando).takeIf { ServicioGrabacion.grabandoEsta(e) },
			getString(R.string.radio_favorita).takeIf { modo != Modo.FAVORITAS && e.uuid in uuidsFavoritas },
			getString(R.string.radio_no_funciona).takeIf { !e.funciona },
		)
		val datos = listOf(extra, e.paisLegible(), e.generos(), e.calidad()).filter { it.isNotBlank() }
		val detalle = (estado + datos).joinToString(" · ")
		val voz = (estado + e.nombre + datos).joinToString(", ")
		return Triple(e.nombre, detalle, voz)
	}

	private inner class Vista(val f: ItemListaBinding) : RecyclerView.ViewHolder(f.root)

	// Ids estables: al recargar (favorita, sonando) TalkBack no pierde el sitio
	private inner class Adaptador : RecyclerView.Adapter<Vista>() {
		init {
			setHasStableIds(true)
		}

		override fun getItemId(posicion: Int): Long = when (val f = filas[posicion]) {
			is Fila.Subir -> -1L
			is Fila.CargarMas -> -2L
			// Cada tipo en su propio tramo de 2^32 (antes los tramos de carpetas, emisoras y géneros se pisaban)
			is Fila.Carpeta -> (1L shl 32) + (f.nombre.lowercase().hashCode().toLong() and 0xffffffffL)
			is Fila.DeEmisora -> (2L shl 32) + (f.emisora.uuid.hashCode().toLong() and 0xffffffffL)
			is Fila.DeLista -> 10L + f.lista.ordinal
			is Fila.DeTipo -> 50L + f.tipo.ordinal
			is Fila.DeCategoria -> (3L shl 32) + (f.categoria.clave.hashCode().toLong() and 0xffffffffL)
			is Fila.DeCancion -> (4L shl 32) + f.cancion.id
			is Fila.DeGrabacion -> (5L shl 32) + f.grabacion.id
			is Fila.DeProgramacion -> (6L shl 32) + f.programacion.id
		}

		override fun getItemCount() = filas.size

		override fun onCreateViewHolder(padre: ViewGroup, tipo: Int) = Vista(ItemListaBinding.inflate(LayoutInflater.from(padre.context), padre, false))

		override fun onBindViewHolder(v: Vista, posicion: Int) {
			val fila = filas[posicion]
			val a = actividad ?: return
			val (texto, detalle, voz) = when (fila) {
				is Fila.Subir -> getString(R.string.subir_a, fila.nombre).let { Triple(it, "", it) }
				is Fila.CargarMas -> getString(R.string.radio_cargar_mas).let { Triple(it, "", it) }
				is Fila.Carpeta -> {
					val d = if (fila.emisoras == 0) getString(R.string.carpeta_vacia_corto) else resources.getQuantityString(R.plurals.radio_n_emisoras, fila.emisoras, fila.emisoras)
					Triple(fila.nombre, d, getString(R.string.carpeta_x, fila.nombre) + ", " + d)
				}
				is Fila.DeEmisora -> describir(fila.emisora, fila.extra)
				is Fila.DeLista -> nombreLista(fila.lista).let { Triple(it, "", it) }
				is Fila.DeTipo -> getString(fila.tipo.nombre).let { Triple(it, "", it) }
				is Fila.DeCategoria -> {
					val d = resources.getQuantityString(R.plurals.radio_n_emisoras, fila.categoria.emisoras, fila.categoria.emisoras)
					Triple(fila.categoria.nombre, d, "${fila.categoria.nombre}, $d")
				}
				is Fila.DeCancion -> {
					val d = listOf(fila.cancion.emisora, RadioAcciones.cuando(fila.cancion.fecha)).filter { it.isNotBlank() }.joinToString(" · ")
					Triple(fila.cancion.titulo, d, "${fila.cancion.titulo}, ${d.replace(" · ", ", ")}")
				}
				is Fila.DeGrabacion -> {
					val g = fila.grabacion
					val enMarcha = ServicioGrabacion.grabando != null && g.archivo == ServicioGrabacion.archivoActual
					val estado = listOfNotNull(getString(R.string.radio_grabando).takeIf { enMarcha })
					val datos = listOfNotNull(
						g.emisora.takeIf { it.isNotBlank() },
						com.xebolax.lector.rss.Rss.fechaLegible(g.inicio),
						g.duracion.takeIf { it > 0 }?.let { tiempoCorto(it) },
						g.tamano.takeIf { it > 0 }?.let { tamanoLegible(it) },
					)
					val d = (estado + datos).joinToString(" · ")
					val nombre = java.io.File(g.archivo).nameWithoutExtension
					// El estado primero (pauta 14): «Grabando, nombre, emisora, fecha…»
					Triple(nombre, d, (estado + nombre + datos).joinToString(", "))
				}
				is Fila.DeProgramacion -> {
					val p = fila.programacion
					val tipo = getString(if (p.esDespertador) R.string.radio_despertador else R.string.radio_grabacion)
					val hora = String.format(Locale.getDefault(), "%d:%02d", p.hora, p.minuto)
					val estado = getString(if (p.activa) R.string.radio_activada else R.string.radio_desactivada)
					val dur = if (p.duracion > 0) resources.getQuantityString(R.plurals.minutos_n, p.duracion, p.duracion) else getString(R.string.radio_sin_limite)
					val t = "$tipo, $hora"
					val d = listOf(estado, RadioProgramador.diasLegibles(a, p.dias), p.emisora.nombre, dur).joinToString(" · ")
					// Estado primero: «Desactivado, despertador, 7:30, …»
					Triple(t, d, "$estado, $t, " + listOf(RadioProgramador.diasLegibles(a, p.dias), p.emisora.nombre, dur).joinToString(", "))
				}
			}
			v.f.texto.text = texto
			v.f.texto.setTypeface(null, if (fila is Fila.DeEmisora && Radio.suena(fila.emisora)) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
			v.f.detalle.text = detalle
			v.f.detalle.isVisible = detalle.isNotBlank()
			v.f.root.contentDescription = voz
			v.f.root.setOnClickListener {
				when (fila) {
					is Fila.Subir -> subir()
					is Fila.CargarMas -> cargarEmisoras(desde = emisoras.size)
					is Fila.Carpeta -> entrarCarpeta(fila.nombre)
					is Fila.DeEmisora -> if (Radio.actual?.uuid == fila.emisora.uuid) RadioAcciones.abrirReproductor(a) else Radio.escuchar(a, fila.emisora, emisorasVisibles())
					is Fila.DeLista -> entrar(Nivel.DeLista(fila.lista))
					is Fila.DeTipo -> entrar(Nivel.Categorias(fila.tipo))
					is Fila.DeCategoria -> entrar(Nivel.DeCategoria(fila.tipo, fila.categoria))
					is Fila.DeCancion -> a.elegir(fila.cancion.titulo, accionesCancion(fila.cancion))
					is Fila.DeGrabacion -> RadioAcciones.escucharGrabacion(a, fila.grabacion)
					is Fila.DeProgramacion -> editarProgramacion(fila.programacion, fila.programacion.tipo, null)
				}
			}
			val acciones: List<Accion> = when (fila) {
				is Fila.Carpeta -> accionesCarpeta(fila.nombre)
				is Fila.DeEmisora -> accionesEmisora(fila.emisora)
				is Fila.DeCancion -> accionesCancion(fila.cancion)
				is Fila.DeGrabacion -> accionesGrabacion(fila.grabacion)
				is Fila.DeProgramacion -> accionesProgramacion(fila.programacion)
				else -> emptyList()
			}
			// Cada tipo de fila con las acciones que el usuario eligió para su zona (Ajustes, Acciones de TalkBack)
			v.f.root.accionesAccesibles(acciones, when (fila) {
				is Fila.Carpeta -> ZonaAcciones.CARPETA_RADIO
				is Fila.DeEmisora -> ZonaAcciones.EMISORA
				is Fila.DeCancion -> ZonaAcciones.CANCION
				is Fila.DeGrabacion -> ZonaAcciones.GRABACION
				is Fila.DeProgramacion -> ZonaAcciones.PROGRAMACION
				else -> null
			})
			v.f.root.setOnLongClickListener {
				if (acciones.isEmpty()) false else {
					a.elegir(texto.take(60), acciones)
					true
				}
			}
			// Mantener pulsado solo donde hace algo («Subir a…», «Cargar más» no), y con su nombre para TalkBack
			v.f.root.isLongClickable = acciones.isNotEmpty()
			if (acciones.isNotEmpty()) androidx.core.view.ViewCompat.replaceAccessibilityAction(v.f.root,
				androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK, getString(R.string.audio_mas_acciones), null)
			else androidx.core.view.ViewCompat.removeAccessibilityAction(v.f.root, android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK)
		}
	}
}
