package com.xebolax.lector.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.xebolax.lector.LectorApp
import com.xebolax.lector.R
import com.xebolax.lector.actualizaciones.GestorActualizaciones
import com.xebolax.lector.databinding.ActivityAjustesBinding
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.formatos.CacheContenido
import com.xebolax.lector.lectura.Abiertos
import com.xebolax.lector.voz.LectorVoz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Ajustes, con pestañas abajo como las secciones: General (lo que afecta a
 * toda la app) y una por sección, en el orden de las secciones (Ajustes,
 * Portada y secciones): Biblioteca (lectura, voz, tipos y documentos),
 * Compartidos, Podcasts, Noticias y Radio. Las pestañas se ven siempre
 * enteras; si no caben todas, las últimas van a «Más» (ver [Pestanas]).
 * Cada pestaña tiene sus apartados; cada apartado es una pantalla con su
 * título (TalkBack la anuncia). Desde el menú lateral de una sección se abre
 * en la pestaña de esa sección. Atrás en un apartado vuelve a su pestaña; en
 * otra pestaña, a la pestaña con la que se abrió; y en esa, sale.
 */
class AjustesActivity : AppCompatActivity() {

	companion object {
		/** Abrir directamente una pestaña (GENERAL, BIBLIOTECA, COMPARTIDOS, PODCASTS, NOTICIAS, RADIO; o rss, podcasts, radio) o un apartado (copias). */
		const val EXTRA_APARTADO = "apartado"
		private const val ESTADO_PESTANA = "pestana"
		private const val ESTADO_INICIAL = "inicial"

		/** Ajustes en la pestaña de una sección (desde su menú lateral). */
		fun abrir(c: android.content.Context, seccion: Seccion? = null) {
			val i = Intent(c, AjustesActivity::class.java)
			val p = Pestana.entries.firstOrNull { it.name == seccion?.name }
			if (p != null) i.putExtra(EXTRA_APARTADO, p.name)
			c.startActivity(i)
		}
	}

	/** Las pestañas: xml de su pantalla, nombre, icono y título de la barra. */
	private enum class Pestana(val xml: String, val nombre: Int, val icono: Int, val titulo: Int) {
		GENERAL("tab_general", R.string.aj_pestana_general, R.drawable.ic_ajustes, R.string.ajustes_general_titulo),
		BIBLIOTECA("tab_biblioteca", R.string.aj_pestana_biblioteca, R.drawable.ic_biblioteca, R.string.ajustes_biblioteca_titulo),
		COMPARTIDOS("compartidos", R.string.aj_pestana_compartidos, R.drawable.ic_compartido, R.string.ajustes_compartidos_titulo),
		PODCASTS("podcasts", R.string.aj_pestana_podcasts, R.drawable.ic_voz, R.string.ajustes_podcasts_titulo),
		NOTICIAS("rss", R.string.aj_pestana_noticias, R.drawable.ic_rss, R.string.ajustes_noticias_titulo),
		RADIO("radio", R.string.aj_pestana_radio, R.drawable.ic_radio, R.string.ajustes_radio_titulo),
	}

	private lateinit var b: ActivityAjustesBinding
	private lateinit var pestanas: Pestanas
	val actualizaciones = GestorActualizaciones(this)
	private var pestana = Pestana.GENERAL
	/** La pestaña con la que se abrió (Atrás vuelve a ella y, desde ella, sale). */
	private var inicial = Pestana.GENERAL

	/** Atrás: de un apartado a su pestaña, de otra pestaña a la inicial y, desde la inicial, salir. */
	private val atras = object : androidx.activity.OnBackPressedCallback(true) {
		override fun handleOnBackPressed() {
			when {
				supportFragmentManager.backStackEntryCount > 0 -> supportFragmentManager.popBackStack()
				pestana != inicial -> {
					pestanas.elegir(inicial.name)
					mostrarPestana(inicial)
				}
				else -> finish()
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityAjustesBinding.inflate(layoutInflater)
		setContentView(b.root)
		configurarBarra(b.barra, getString(R.string.ajustes))
		onBackPressedDispatcher.addCallback(this, atras)
		val pedida = when (val e = intent.getStringExtra(EXTRA_APARTADO)) {
			"rss" -> Pestana.NOTICIAS
			"podcasts" -> Pestana.PODCASTS
			"radio" -> Pestana.RADIO
			"compartidos" -> Pestana.COMPARTIDOS
			else -> Pestana.entries.firstOrNull { it.name == e } ?: Pestana.GENERAL
		}
		inicial = savedInstanceState?.getInt(ESTADO_INICIAL, -1)?.let { Pestana.entries.getOrNull(it) } ?: pedida
		pestana = savedInstanceState?.getInt(ESTADO_PESTANA, -1)?.let { Pestana.entries.getOrNull(it) } ?: pedida
		// General y luego las secciones en su orden (el que se elige en Portada y secciones)
		val orden = listOf(Pestana.GENERAL) + OrdenSecciones.orden(this).mapNotNull { s -> Pestana.entries.firstOrNull { it.name == s.name } }
		pestanas = Pestanas(this, abajo = true)
		b.pestanas.addView(pestanas, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
		pestanas.poner(orden.map { Pestanas.Pestana(it.name, getString(it.nombre), it.icono) }, pestana.name) { clave ->
			Pestana.entries.firstOrNull { it.name == clave }?.let { mostrarPestana(it) }
		}
		// Tocar la pestaña en la que se está vuelve a su pantalla principal
		pestanas.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
			override fun onTabSelected(t: com.google.android.material.tabs.TabLayout.Tab) {}
			override fun onTabUnselected(t: com.google.android.material.tabs.TabLayout.Tab) {}
			override fun onTabReselected(t: com.google.android.material.tabs.TabLayout.Tab) {
				if (t.tag == pestana.name && supportFragmentManager.backStackEntryCount > 0) mostrarPestana(pestana)
			}
		})
		if (savedInstanceState == null) {
			mostrarPestana(pestana)
			if (intent.getStringExtra(EXTRA_APARTADO) == "copias") abrirApartado("copias", getString(R.string.aj_copias))
		} else ponerTitulo()
		supportFragmentManager.addOnBackStackChangedListener { ponerTitulo() }
	}

	override fun onSaveInstanceState(estado: Bundle) {
		super.onSaveInstanceState(estado)
		estado.putInt(ESTADO_PESTANA, pestana.ordinal)
		estado.putInt(ESTADO_INICIAL, inicial.ordinal)
	}

	private fun ponerTitulo() {
		val actual = supportFragmentManager.findFragmentById(R.id.contenedor) as? AjustesFragment
		val t = actual?.titulo ?: getString(pestana.titulo)
		supportActionBar?.title = t
		title = t
	}

	/** Muestra la pantalla principal de una pestaña (cierra el apartado que estuviera abierto). */
	private fun mostrarPestana(p: Pestana) {
		pestana = p
		supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
		supportFragmentManager.beginTransaction().replace(R.id.contenedor, AjustesFragment.nuevo(p.xml, getString(p.titulo))).commitNow()
		ponerTitulo()
	}

	fun abrirApartado(xml: String, titulo: String) {
		supportFragmentManager.beginTransaction()
			.replace(R.id.contenedor, AjustesFragment.nuevo(xml, titulo))
			.addToBackStack(xml)
			.commit()
		supportActionBar?.title = titulo
		title = titulo
	}
}

class AjustesFragment : PreferenceFragmentCompat() {

	companion object {
		fun nuevo(xml: String, titulo: String) = AjustesFragment().apply { arguments = bundleOf("xml" to xml, "titulo" to titulo) }
	}

	val titulo: String? get() = arguments?.getString("titulo")

	/** Crear, restaurar y deshacer copias de seguridad (apartado Copias de seguridad). */
	private val copias = GestorCopias(this)

	override fun onCreatePreferences(estado: Bundle?, raiz: String?) {
		val xml = arguments?.getString("xml") ?: "raiz"
		val recurso = when (xml) {
			"tab_general" -> R.xml.ajustes_tab_general
			"tab_biblioteca" -> R.xml.ajustes_tab_biblioteca
			"compartidos" -> R.xml.ajustes_compartidos
			"portada" -> R.xml.ajustes_portada
			"copias" -> R.xml.ajustes_copias
			"general" -> R.xml.ajustes_general
			"aspecto" -> R.xml.ajustes_aspecto
			"navegacion" -> R.xml.ajustes_navegacion
			"voz" -> R.xml.ajustes_voz
			"audio" -> R.xml.ajustes_audio
			"documentos" -> R.xml.ajustes_documentos
			"biblioteca" -> R.xml.ajustes_biblioteca
			"ia" -> R.xml.ajustes_ia
			"rss" -> R.xml.ajustes_rss
			"podcasts" -> R.xml.ajustes_podcasts
			"radio" -> R.xml.ajustes_radio
			"elevenlabs" -> R.xml.ajustes_elevenlabs
			else -> R.xml.ajustes_raiz
		}
		setPreferencesFromResource(recurso, raiz)
		// Claves y contraseñas: se leen y guardan aparte (Secretos), no en los ajustes normales
		for (k in com.xebolax.lector.datos.Secretos.CLAVES_AJUSTES) {
			findPreference<androidx.preference.EditTextPreference>(k)?.apply {
				preferenceDataStore = com.xebolax.lector.datos.Secretos.almacen(requireContext())
				text = com.xebolax.lector.datos.Secretos.leer(requireContext(), k)
			}
		}
		when (xml) {
			"raiz", "tab_general", "tab_biblioteca" -> configurarRaiz()
			"general" -> configurarGeneral()
			"copias" -> configurarCopias()
			"voz" -> configurarVoz()
			"documentos" -> configurarDocumentos()
			"biblioteca" -> configurarBiblioteca()
			"aspecto" -> configurarAspecto()
			"elevenlabs" -> configurarElevenLabs()
			"ia" -> configurarIA()
			"rss", "podcasts" -> {
				for (clave in listOf("rss_frecuencia", "rss_solo_wifi")) {
					findPreference<Preference>(clave)?.setOnPreferenceChangeListener { _, _ ->
						view?.post { com.xebolax.lector.rss.RssTrabajo.programar(requireContext()) }
						true
					}
				}
				configurarCuenta(if (xml == "rss") "rss" else "pod")
				if (xml == "rss") alPulsar("rss_medios") { mediosSuscritos() }
				if (xml == "rss") alPulsar("ir_voz_noticias") { com.xebolax.lector.voz.VozPerfilActivity.abrirNoticias(requireContext()) }
			}
		}
		// Portada y secciones: el orden se elige en su propia pantalla (filas que se mueven)
		alPulsar("ir_orden_secciones") { startActivity(Intent(requireContext(), SeccionesActivity::class.java)) }
		// Navegación: las acciones de cada párrafo se eligen (y ordenan) en Acciones de TalkBack
		alPulsar("ir_acciones_parrafo") { AccionesTalkBackActivity.abrir(requireContext(), ZonaAcciones.PARRAFO) }
		alPulsar("ir_acciones_compartidos") { AccionesTalkBackActivity.abrir(requireContext(), ZonaAcciones.COMPARTIDO) }
		// La voz de cada sección (la general está en General, Lectura en voz alta)
		alPulsar("ir_voz_biblioteca") { com.xebolax.lector.voz.VozPerfilActivity.abrirSeccion(requireContext(), com.xebolax.lector.voz.PerfilesVoz.BIBLIOTECA) }
		alPulsar("ir_voz_compartidos") { com.xebolax.lector.voz.VozPerfilActivity.abrirSeccion(requireContext(), com.xebolax.lector.voz.PerfilesVoz.COMPARTIDOS) }
		// Está en Voz y en Audiolibros: se aplica a los dos reproductores
		findPreference<Preference>("talkback_al_hablar")?.setOnPreferenceChangeListener { _, _ ->
			view?.post {
				LectorVoz.aplicarAtributos()
				com.xebolax.lector.audio.ControlAudio.aplicarAtributos()
			}
			true
		}
	}

	override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		titulo?.let { androidx.core.view.ViewCompat.setAccessibilityPaneTitle(view, it) }
	}

	private fun alPulsar(clave: String, accion: () -> Unit) {
		findPreference<Preference>(clave)?.setOnPreferenceClickListener {
			accion()
			true
		}
	}

	private fun configurarRaiz() {
		alPulsar("ir_acciones") { AccionesTalkBackActivity.abrir(requireContext()) }
		alPulsar("ir_pestanas") { startActivity(Intent(requireContext(), PestanasBibliotecaActivity::class.java)) }
		val apartados = listOf(
			"ir_portada" to "portada", "ir_copias" to "copias", "ir_general" to "general", "ir_aspecto" to "aspecto", "ir_navegacion" to "navegacion", "ir_voz" to "voz",
			"ir_audio" to "audio", "ir_podcasts" to "podcasts", "ir_rss" to "rss", "ir_radio" to "radio", "ir_ia" to "ia", "ir_documentos" to "documentos", "ir_biblioteca" to "biblioteca",
			"ir_compartidos" to "compartidos", "ir_elevenlabs" to "elevenlabs",
		)
		for ((clave, xml) in apartados) {
			val pref = findPreference<Preference>(clave) ?: continue
			pref.setOnPreferenceClickListener {
				(activity as? AjustesActivity)?.abrirApartado(xml, pref.title.toString())
				true
			}
		}
	}

	// --- Aspecto del texto: colores a tu gusto y una letra tuya ---------------------------------

	private val elegirLetra = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		uri ?: return@registerForActivityResult
		val c = requireContext().applicationContext
		val a = activity as? AppCompatActivity ?: return@registerForActivityResult
		a.lifecycleScope.launch {
			val nombre = withContext(Dispatchers.IO) {
				runCatching {
					val destino = com.xebolax.lector.lectura.Apariencia.archivoLetraPropia(c)
					destino.parentFile?.mkdirs()
					val temporal = java.io.File(destino.parentFile, "nueva")
					c.contentResolver.openInputStream(uri)!!.use { e -> temporal.outputStream().use { e.copyTo(it) } }
					// TrueType, OpenType o colección: lo dicen sus primeros bytes; y Android tiene que poder cargarla
					val cabecera = temporal.inputStream().use { val b = ByteArray(4); it.read(b); b }
					val valida = cabecera.contentEquals(byteArrayOf(0, 1, 0, 0)) || String(cabecera, Charsets.ISO_8859_1) in setOf("OTTO", "true", "ttcf")
					if (!valida || runCatching { android.graphics.Typeface.createFromFile(temporal) }.isFailure) {
						temporal.delete()
						null
					} else {
						temporal.renameTo(destino.also { it.delete() })
						com.xebolax.lector.formatos.Fuente.nombreDe(c, uri)
					}
				}.getOrNull()
			}
			if (nombre == null) return@launch a.avisar(getString(R.string.aj_letra_no_valida))
			PreferenceManager.getDefaultSharedPreferences(c).edit().putString("letra_propia_nombre", nombre).putString("fuente", "propia").apply()
			findPreference<ListPreference>("fuente")?.value = "propia"
			resumenesAspecto()
			a.avisar(getString(R.string.aj_letra_lista, nombre))
		}
	}

	private fun configurarAspecto() {
		val tema = findPreference<ListPreference>("tema_lectura")
		val fuente = findPreference<ListPreference>("fuente")
		tema?.setOnPreferenceChangeListener { _, _ ->
			view?.post { resumenesAspecto() }
			true
		}
		fuente?.setOnPreferenceChangeListener { _, nuevo ->
			// Una letra tuya y aún no hay archivo: se elige ya
			if (nuevo == "propia" && !com.xebolax.lector.lectura.Apariencia.archivoLetraPropia(requireContext()).exists()) {
				view?.post { abrirLetra() }
			}
			view?.post { resumenesAspecto() }
			true
		}
		alPulsar("letra_propia") { abrirLetra() }
		for (clave in com.xebolax.lector.lectura.Apariencia.COLORES_PROPIOS.keys) alPulsar(clave) { elegirColor(clave) }
		resumenesAspecto()
	}

	private fun abrirLetra() {
		try {
			elegirLetra.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream", "*/*"))
		} catch (_: ActivityNotFoundException) {
			(activity as? AppCompatActivity)?.avisar(getString(R.string.no_disponible))
		}
	}

	/** Qué color es (su nombre o su código) y, para el texto, los enlaces y el resaltado, el contraste. */
	private fun resumenesAspecto() {
		val c = context ?: return
		val prefs = PreferenceManager.getDefaultSharedPreferences(c)
		val propio = findPreference<ListPreference>("tema_lectura")?.value == "propio"
		val nombres = resources.getStringArray(R.array.colores_nombres)
		val valores = resources.getStringArray(R.array.colores_valores)
		fun nombre(color: Int): String {
			val codigo = String.format("#%06X", color and 0xFFFFFF)
			return valores.indexOfFirst { it.equals(codigo, true) }.takeIf { it >= 0 }?.let { nombres[it] } ?: codigo
		}
		fun contraste(a: Int, b: Int): String {
			val r = com.xebolax.lector.lectura.Apariencia.contraste(a, b)
			val texto = String.format(Locale.getDefault(), "%.1f", r)
			return getString(if (r >= 4.5) R.string.aj_contraste_bueno else R.string.aj_contraste_bajo, texto)
		}
		val fondo = com.xebolax.lector.lectura.Apariencia.colorPropio(c, "color_fondo")
		val texto = com.xebolax.lector.lectura.Apariencia.colorPropio(c, "color_texto")
		for (clave in com.xebolax.lector.lectura.Apariencia.COLORES_PROPIOS.keys) {
			val pref = findPreference<Preference>(clave) ?: continue
			pref.isVisible = propio
			val color = com.xebolax.lector.lectura.Apariencia.colorPropio(c, clave)
			pref.summary = when (clave) {
				"color_fondo" -> getString(R.string.aj_contraste_x, nombre(color), contraste(texto, fondo))
				// El texto se lee sobre el resaltado: su contraste es con el texto
				"color_resaltado" -> "${nombre(color)}; ${contraste(texto, color)}"
				else -> getString(R.string.aj_contraste_x, nombre(color), contraste(color, fondo))
			}
		}
		findPreference<Preference>("letra_propia")?.apply {
			isVisible = findPreference<ListPreference>("fuente")?.value == "propia"
			val puesta = prefs.getString("letra_propia_nombre", "").orEmpty()
			summary = if (com.xebolax.lector.lectura.Apariencia.archivoLetraPropia(c).exists() && puesta.isNotEmpty()) getString(R.string.aj_letra_propia_puesta, puesta)
			else getString(R.string.aj_letra_propia_ninguna)
		}
	}

	/** Un color con nombre de la lista u otro escribiendo su código. */
	private fun elegirColor(clave: String) {
		val c = requireContext()
		val nombres = resources.getStringArray(R.array.colores_nombres)
		val valores = resources.getStringArray(R.array.colores_valores)
		val actual = com.xebolax.lector.lectura.Apariencia.colorPropio(c, clave)
		val codigo = String.format("#%06X", actual and 0xFFFFFF)
		val titulo = findPreference<Preference>(clave)?.title?.toString().orEmpty()
		fun guardar(color: Int) {
			PreferenceManager.getDefaultSharedPreferences(c).edit().putInt(clave, color or 0xFF000000.toInt()).apply()
			resumenesAspecto()
		}
		c.elegirUno(titulo, nombres.toList() + getString(R.string.aj_color_otro), valores.indexOfFirst { it.equals(codigo, true) }) { i ->
			if (i < valores.size) guardar(android.graphics.Color.parseColor(valores[i]))
			else c.pedirTexto(getString(R.string.aj_color_otro), getString(R.string.aj_color_codigo), codigo) { t ->
				val limpio = t.trim().removePrefix("#")
				val color = if (Regex("[0-9A-Fa-f]{6}").matches(limpio)) android.graphics.Color.parseColor("#$limpio") else null
				if (color == null) (activity as? AppCompatActivity)?.avisar(getString(R.string.aj_color_no_valido)) else guardar(color)
			}
		}
	}

	// --- ElevenLabs: voces en la nube con la clave del usuario ------------------------------------

	private fun configurarElevenLabs() {
		val c = requireContext()
		val el = com.xebolax.lector.voz.ElevenLabs
		findPreference<androidx.preference.EditTextPreference>(el.CLAVE)?.apply {
			setOnBindEditTextListener {
				it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
				it.setSelection(it.text.length)
			}
			summaryProvider = Preference.SummaryProvider<androidx.preference.EditTextPreference> { p ->
				getString(if (p.text.isNullOrBlank()) R.string.el_clave_desc else R.string.aj_ia_clave_puesta)
			}
			setOnPreferenceChangeListener { _, _ ->
				// Otra cuenta: la voz recordada puede no existir en ella
				PreferenceManager.getDefaultSharedPreferences(c).edit().remove("el_voz_defecto").apply()
				true
			}
		}
		findPreference<ListPreference>("el_modelo")?.setOnPreferenceChangeListener { _, _ ->
			view?.post { LectorVoz.ajustesCambiados() }
			true
		}
		fun cache() {
			val ocupa = el.tamanoCache(c)
			findPreference<Preference>("el_cache")?.summary = if (ocupa > 0) getString(R.string.el_cache_ocupa, tamanoLegible(ocupa)) else getString(R.string.el_cache_vacia)
		}
		cache()
		alPulsar("el_cache") {
			el.vaciarCache(c)
			cache()
			(activity as? AppCompatActivity)?.avisar(getString(R.string.el_cache_vaciada))
		}
		LectorVoz.ultimoAvisoNube.takeIf { it.isNotBlank() }?.let { findPreference<Preference>("el_comprobar")?.summary = it }
		alPulsar("el_comprobar") {
			val a = activity as? AppCompatActivity ?: return@alPulsar
			if (!el.configurada(a)) return@alPulsar a.avisar(getString(R.string.el_error_clave))
			lifecycleScope.launch {
				val texto = try {
					a.conEspera(getString(R.string.el_comprobar), getString(R.string.el_comprobando)) {
						withContext(Dispatchers.IO) {
							val cuenta = el.cuenta(a)
							val voces = runCatching { el.voces(a).size }.getOrDefault(0)
							val quedan = (cuenta.limite - cuenta.usados).coerceAtLeast(0)
							val renueva = if (cuenta.renueva > 0) android.text.format.DateFormat.getDateFormat(a).format(java.util.Date(cuenta.renueva * 1000)) else "—"
							getString(R.string.el_cuenta, cuenta.plan, quedan, cuenta.limite, renueva, voces)
						}
					}
				} catch (e: kotlinx.coroutines.CancellationException) {
					throw e
				} catch (e: Exception) {
					el.mensaje(a, e)
				}
				findPreference<Preference>("el_comprobar")?.summary = texto
				a.mensaje(getString(R.string.el_comprobar), texto)
			}
		}
		alPulsar("el_abrir_web") {
			runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://elevenlabs.io/app/settings/api-keys"))) }
		}
	}

	private fun configurarIA() {
		// Claves: campo de contraseña y en el resumen solo si está puesta
		for (clave in listOf("ia_clave_gemini", "ia_clave_openai", "ia_clave_claude")) {
			findPreference<androidx.preference.EditTextPreference>(clave)?.apply {
				setOnBindEditTextListener {
					it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
					it.setSelection(it.text.length)
				}
				val original = summary
				summaryProvider = Preference.SummaryProvider<androidx.preference.EditTextPreference> { p ->
					if (p.text.isNullOrBlank()) original else getString(R.string.aj_ia_clave_puesta)
				}
			}
		}
		val modelos = mapOf(
			"ia_modelo_gemini" to com.xebolax.lector.ia.AsistenteIA.MODELO_GEMINI,
			"ia_modelo_openai" to com.xebolax.lector.ia.AsistenteIA.MODELO_OPENAI,
			"ia_modelo_claude" to com.xebolax.lector.ia.AsistenteIA.MODELO_CLAUDE,
		)
		for ((clave, defecto) in modelos) findPreference<androidx.preference.EditTextPreference>(clave)?.apply {
			if (text.isNullOrBlank()) text = defecto
		}
		findPreference<Preference>("ia_modelo_local")?.let { pref ->
			actualizarModeloLocal(pref)
			pref.setOnPreferenceClickListener {
				modeloLocal(pref)
				true
			}
		}
		findPreference<Preference>("ia_local_gpu")?.setOnPreferenceChangeListener { _, _ ->
			com.xebolax.lector.ia.ModeloLocal.reiniciar()
			true
		}
		alPulsar("ia_comprobar") {
			val pref = findPreference<Preference>("ia_comprobar") ?: return@alPulsar
			lifecycleScope.launch {
				val c = requireContext()
				val nano = when (com.xebolax.lector.ia.AsistenteIA.Nano.estado()) {
					com.xebolax.lector.ia.AsistenteIA.Nano.DISPONIBLE -> getString(R.string.ia_nano_disponible)
					com.xebolax.lector.ia.AsistenteIA.Nano.DESCARGABLE, com.xebolax.lector.ia.AsistenteIA.Nano.DESCARGANDO -> getString(R.string.ia_nano_descargable)
					else -> getString(R.string.ia_nano_no)
				}
				val usado = com.xebolax.lector.ia.AsistenteIA.elegir(c)?.let { getString(R.string.ia_se_usara, com.xebolax.lector.ia.AsistenteIA.nombre(c, it)) }
					?: getString(R.string.ia_sin_configurar)
				val texto = "$nano $usado"
				pref.summary = texto
				(activity as? AppCompatActivity)?.avisar(texto)
			}
		}
	}

	private fun actualizarModeloLocal(pref: Preference) {
		val c = requireContext()
		val m = com.xebolax.lector.ia.ModeloLocal
		pref.summary = when (m.estado(c)) {
			com.xebolax.lector.ia.ModeloLocal.Estado.NO_COMPATIBLE -> getString(R.string.ia_local_no_compatible)
			com.xebolax.lector.ia.ModeloLocal.Estado.NO_DESCARGADO -> getString(R.string.ia_local_no_descargado, m.NOMBRE)
			com.xebolax.lector.ia.ModeloLocal.Estado.DESCARGANDO -> getString(R.string.ia_local_descargando, m.porcentaje(c))
			com.xebolax.lector.ia.ModeloLocal.Estado.DESCARGADO -> getString(R.string.ia_local_descargado, m.NOMBRE)
		}
	}

	/** Bajar, cancelar o borrar el modelo de IA de la app. */
	private fun modeloLocal(pref: Preference) {
		val c = requireContext()
		val m = com.xebolax.lector.ia.ModeloLocal
		val a = activity as? AppCompatActivity ?: return
		when (m.estado(c)) {
			com.xebolax.lector.ia.ModeloLocal.Estado.NO_COMPATIBLE -> a.avisar(getString(R.string.ia_local_no_compatible))
			com.xebolax.lector.ia.ModeloLocal.Estado.NO_DESCARGADO -> {
				val memoria = m.memoriaGb(c)
				val aviso = getString(R.string.ia_local_confirmar, m.NOMBRE) +
					(if (memoria < 5.5) "\n\n" + getString(R.string.ia_local_poca_memoria, memoria) else "")
				com.google.android.material.dialog.MaterialAlertDialogBuilder(c)
					.setTitle(R.string.aj_ia_modelo_local)
					.setMessage(aviso)
					.setPositiveButton(R.string.ia_local_solo_wifi) { _, _ -> m.descargar(c, soloWifi = true); actualizarModeloLocal(pref); a.avisar(getString(R.string.ia_local_empezada)) }
					.setNeutralButton(R.string.ia_local_con_datos) { _, _ -> m.descargar(c, soloWifi = false); actualizarModeloLocal(pref); a.avisar(getString(R.string.ia_local_empezada)) }
					.setNegativeButton(R.string.cancelar, null)
					.show()
			}
			com.xebolax.lector.ia.ModeloLocal.Estado.DESCARGANDO -> c.confirmar(getString(R.string.ia_local_descargando, m.porcentaje(c)) + ". " + getString(R.string.ia_local_cancelar_pregunta), getString(R.string.ia_local_cancelar)) {
				m.cancelar(c)
				actualizarModeloLocal(pref)
			}
			com.xebolax.lector.ia.ModeloLocal.Estado.DESCARGADO -> c.confirmar(getString(R.string.ia_local_borrar_pregunta, m.NOMBRE), getString(R.string.borrar)) {
				m.borrar(c)
				actualizarModeloLocal(pref)
				a.avisar(getString(R.string.ia_local_borrado))
			}
		}
	}

	private fun configurarGeneral() {
		findPreference<ListPreference>("idioma_app")?.apply {
			val actual = AppCompatDelegate.getApplicationLocales().toLanguageTags()
			value = actual.substringBefore(',').substringBefore('-')
			setOnPreferenceChangeListener { _, nuevo ->
				val etiqueta = nuevo as String
				AppCompatDelegate.setApplicationLocales(if (etiqueta.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(etiqueta))
				true
			}
		}
		findPreference<ListPreference>("tema_app")?.setOnPreferenceChangeListener { _, nuevo ->
			LectorApp.aplicarTema(nuevo as String)
			true
		}
		alPulsar("comprobar_ahora") { (activity as? AjustesActivity)?.actualizaciones?.comprobarAMano() }
		alPulsar("informar_problema") { activity?.let { com.xebolax.lector.datos.RegistroFallos.informar(it) } }
		// Ahorro de batería: en muchos móviles corta la lectura con la pantalla apagada
		findPreference<Preference>("optimizacion_bateria")?.let { pref ->
			actualizarBateria(pref)
			pref.setOnPreferenceClickListener {
				val c = requireContext()
				val intent = if (sinRestriccionBateria()) Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
				else Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${c.packageName}"))
				try {
					startActivity(intent)
				} catch (_: ActivityNotFoundException) {
					(activity as? AppCompatActivity)?.avisar(getString(R.string.no_disponible))
				}
				true
			}
		}
		findPreference<androidx.preference.EditTextPreference>("url_actualizaciones")?.apply {
			if (text.isNullOrBlank()) text = Preferencias(requireContext()).urlActualizaciones
			setOnBindEditTextListener { it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
		}
		// Dirección personalizada: una carpeta web con version.json o un repositorio de GitHub
		findPreference<androidx.preference.EditTextPreference>("url_personalizada")?.apply {
			setOnBindEditTextListener { it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
			setSummaryProvider { p -> (p as androidx.preference.EditTextPreference).text?.takeIf { it.isNotBlank() } ?: getString(R.string.aj_url_personalizada_vacia) }
		}
		// Solo se ve la dirección que usa la fuente elegida
		fun mostrarDirecciones(fuente: String) {
			findPreference<Preference>("url_actualizaciones")?.isVisible = fuente == "servidor" || fuente == "ambos"
			findPreference<Preference>("url_personalizada")?.isVisible = fuente == "personalizada"
		}
		findPreference<ListPreference>("fuente_actualizaciones")?.let { pref ->
			mostrarDirecciones(pref.value ?: "ambos")
			pref.setOnPreferenceChangeListener { _, nuevo ->
				mostrarDirecciones(nuevo as String)
				true
			}
		}
	}

	private fun configurarCopias() {
		alPulsar("copia_crear") { copias.crear() }
		alPulsar("copia_restaurar") { copias.restaurar() }
		alPulsar("copia_deshacer") { copias.deshacer() }
		actualizarDeshacer()
		// Claves en la copia de Android: al cambiar, pasan al archivo que Android copia o al que no
		findPreference<androidx.preference.SwitchPreferenceCompat>(com.xebolax.lector.datos.Secretos.OPCION_COPIA_ANDROID)?.setOnPreferenceChangeListener { _, nuevo ->
			com.xebolax.lector.datos.Secretos.cambiarCopiaAndroid(requireContext(), nuevo as Boolean)
			(activity as? AppCompatActivity)?.avisar(getString(if (nuevo) R.string.copia_android_claves_si else R.string.copia_android_claves_no))
			true
		}
	}

	/** «Deshacer la última restauración» solo se ve si hay algo que deshacer. */
	private fun actualizarDeshacer() {
		findPreference<Preference>("copia_deshacer")?.apply {
			isVisible = copias.hayQueDeshacer()
			if (isVisible) summary = getString(R.string.aj_copia_deshacer_desc, copias.fechaDeshacer())
		}
	}

	private fun sinRestriccionBateria(): Boolean {
		val energia = requireContext().getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
		return energia.isIgnoringBatteryOptimizations(requireContext().packageName)
	}

	private fun actualizarBateria(pref: Preference) {
		pref.summary = getString(if (sinRestriccionBateria()) R.string.aj_bateria_ok else R.string.aj_bateria_desc)
	}

	override fun onResume() {
		super.onResume()
		findPreference<Preference>("optimizacion_bateria")?.let { actualizarBateria(it) }
		actualizarDeshacer()
		findPreference<Preference>("ia_modelo_local")?.let { actualizarModeloLocal(it) }
	}

	private fun configurarVoz() {
		val motor = findPreference<ListPreference>("motor_voz")
		val idioma = findPreference<ListPreference>("idioma_voces")
		val voz = findPreference<ListPreference>("voz")
		LectorVoz.motores { lista ->
			if (!isAdded) return@motores
			motor?.entries = (listOf(getString(R.string.motor_por_defecto)) + lista.map { it.second }).toTypedArray()
			motor?.entryValues = (listOf("") + lista.map { it.first }).toTypedArray()
			motor?.let { it.summary = it.entry ?: getString(R.string.motor_por_defecto) }
		}
		fun cargarVoces() {
			// Las voces del motor de Ajustes, con un motor aparte (el de la lectura puede ser otro: el propio de un libro)
			com.xebolax.lector.voz.VocesMotor.voces(requireContext(), PreferenceManager.getDefaultSharedPreferences(requireContext()).getString("motor_voz", "").orEmpty()) { voces ->
				if (!isAdded) return@voces
				val idiomas = voces.map { it.locale.language }.distinct().sortedBy { Locale(it).displayLanguage }
				idioma?.entries = (listOf(getString(R.string.todos_los_idiomas)) + idiomas.map { Locale(it).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { c -> c.uppercase() } }).toTypedArray()
				idioma?.entryValues = (listOf("") + idiomas).toTypedArray()
				val filtro = idioma?.value.orEmpty()
				idioma?.summary = if (filtro.isEmpty()) getString(R.string.todos_los_idiomas) else Locale(filtro).displayLanguage
				val visibles = voces.filter { filtro.isEmpty() || it.locale.language == filtro }
				voz?.entries = (listOf(getString(R.string.voz_por_defecto)) + visibles.map { v -> com.xebolax.lector.voz.VocesMotor.etiqueta(requireContext(), v) }).toTypedArray()
				voz?.entryValues = (listOf("") + visibles.map { it.name }).toTypedArray()
				voz?.let { it.summary = it.entry ?: getString(R.string.voz_por_defecto) }
			}
		}
		cargarVoces()
		motor?.setOnPreferenceChangeListener { _, _ ->
			view?.post {
				motor?.let { it.summary = it.entry ?: getString(R.string.motor_por_defecto) }
				PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString("voz", "").apply()
				voz?.value = ""
				LectorVoz.reiniciarMotor()
				cargarVoces()
			}
			true
		}
		idioma?.setOnPreferenceChangeListener { _, _ ->
			view?.post { cargarVoces() }
			true
		}
		voz?.setOnPreferenceChangeListener { pref, _ ->
			view?.post {
				LectorVoz.ajustesCambiados()
				(pref as ListPreference).summary = pref.entry ?: getString(R.string.voz_por_defecto)
			}
			true
		}
		for (clave in listOf("velocidad_voz", "tono_voz", "pausa_parrafos", "pausa_frases", "saltar_notas", "leer_paginas", "destacar_encabezados", "voz_segun_idioma", "pronunciacion_activa")) {
			findPreference<Preference>(clave)?.setOnPreferenceChangeListener { _, _ ->
				view?.post { LectorVoz.ajustesCambiados() }
				true
			}
		}
		findPreference<Preference>("detectar_idioma")?.setOnPreferenceChangeListener { _, _ ->
			view?.post { LectorVoz.idiomasCambiados() }
			true
		}
		alPulsar("probar_voz") {
			// La voz de Ajustes, aunque se esté leyendo un libro con voz propia
			com.xebolax.lector.voz.VocesMotor.probar(requireContext(), com.xebolax.lector.voz.PerfilVoz(), getString(R.string.frase_prueba))
		}
		alPulsar("ir_pronunciacion") { startActivity(Intent(requireContext(), PronunciacionActivity::class.java)) }
		alPulsar("ajustes_sistema_voz") {
			try {
				startActivity(Intent("com.android.settings.TTS_SETTINGS"))
			} catch (_: ActivityNotFoundException) {
				(activity as? AppCompatActivity)?.avisar(getString(R.string.no_disponible))
			}
		}
	}

	private fun configurarDocumentos() {
		val cache = findPreference<Preference>("vaciar_cache")
		val ocupa = CacheContenido.tamano(requireContext())
		cache?.summary = if (ocupa > 0) getString(R.string.cache_ocupa, tamanoLegible(ocupa)) else getString(R.string.cache_vacia)
		cache?.setOnPreferenceClickListener {
			CacheContenido.borrar(requireContext())
			Abiertos.olvidar()
			requireContext().cacheDir.resolve("fuentes").listFiles()?.forEach { it.delete() }
			cache.summary = getString(R.string.cache_vacia)
			(activity as? AppCompatActivity)?.avisar(getString(R.string.cache_vaciada))
			true
		}
		// Cambiar cómo se convierten los documentos: se olvida el que está en memoria
		for (clave in listOf("tablas_encabezados", "leer_imagenes", "dividir_parrafos", "pdf_unir_lineas", "txt_linea_parrafo", "braille_texto")) {
			findPreference<Preference>(clave)?.setOnPreferenceChangeListener { _, _ ->
				Abiertos.olvidar()
				true
			}
		}
	}

	private val elegirVigilada = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
		uri ?: return@registerForActivityResult
		val c = requireContext().applicationContext
		val p = Preferencias(c)
		p.carpetasVigiladas = (p.carpetasVigiladas + uri.toString()).distinct()
		val a = activity as? AppCompatActivity ?: return@registerForActivityResult
		a.lifecycleScope.launch {
			val r = runCatching {
				a.conEspera(getString(R.string.carpetas_vigiladas), getString(R.string.buscando_libros)) {
					com.xebolax.lector.biblioteca.Importador.anadirCarpeta(c, uri, null, crearCarpetas = true)
				}
			}.getOrNull() ?: return@launch
			a.avisar(resources.getQuantityString(R.plurals.libros_anadidos, r.anadidos.size, r.anadidos.size))
			if (r.anadidos.isNotEmpty()) LectorApp.ambito.launch { com.xebolax.lector.biblioteca.Importador.completarDatos(c, r.anadidos) }
		}
		actualizarVigiladas()
	}

	private fun actualizarVigiladas() {
		val n = Preferencias(requireContext()).carpetasVigiladas.size
		findPreference<Preference>("carpetas_vigiladas")?.summary =
			if (n == 0) getString(R.string.aj_carpetas_vigiladas_desc) else resources.getQuantityString(R.plurals.carpetas, n, n)
	}

	/** Carpetas del dispositivo que se revisan solas para añadir los libros nuevos. */
	private fun carpetasVigiladas() {
		val c = requireContext()
		val p = Preferencias(c)
		val opciones = mutableListOf<Accion>(
			getString(R.string.anadir_carpeta_vigilada) to { elegirVigilada.launch(null) },
		)
		if (p.carpetasVigiladas.isNotEmpty()) opciones += getString(R.string.revisar_carpetas) to {
			val a = activity as? AppCompatActivity
			val app = c.applicationContext
			a?.lifecycleScope?.launch {
				val n = runCatching { a.conEspera(getString(R.string.revisar_carpetas), getString(R.string.buscando_libros)) { com.xebolax.lector.biblioteca.Importador.revisarVigiladas(app) } }.getOrNull() ?: return@launch
				a.avisar(if (n == 0) getString(R.string.sin_libros_nuevos) else resources.getQuantityString(R.plurals.libros_anadidos, n, n))
			}
		}
		p.carpetasVigiladas.forEach { uri ->
			val nombre = com.xebolax.lector.formatos.Fuente.nombreDe(c, android.net.Uri.parse(uri))
			opciones += getString(R.string.dejar_de_vigilar, nombre) to {
				p.carpetasVigiladas = p.carpetasVigiladas - uri
				runCatching { c.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
				(activity as? AppCompatActivity)?.avisar(getString(R.string.ya_no_se_vigila, nombre))
				actualizarVigiladas()
			}
		}
		c.elegir(getString(R.string.carpetas_vigiladas), opciones)
	}

	/**
	 * Medios en los que estás suscrito: entras con tu cuenta (en una web dentro
	 * de la app) y los artículos completos de ese medio se descargan con tu sesión.
	 */
	private fun mediosSuscritos() {
		val c = requireContext()
		val lista = com.xebolax.lector.rss.ArticuloCompleto.suscripciones(c)
		val opciones = mutableListOf<Accion>(getString(R.string.aj_rss_anadir_medio) to {
			c.pedirTexto(getString(R.string.aj_rss_anadir_medio), getString(R.string.aj_rss_direccion_medio)) { texto ->
				val d = com.xebolax.lector.rss.ArticuloCompleto.dominio(texto)
				if (d.contains('.')) {
					com.xebolax.lector.rss.ArticuloCompleto.guardarSuscripciones(c, lista + d)
					com.xebolax.lector.rss.SesionActivity.abrir(c, d)
				}
			}
		})
		for (d in lista) {
			val estado = getString(if (com.xebolax.lector.rss.SesionActivity.tieneSesion(d)) R.string.aj_rss_con_sesion else R.string.aj_rss_sin_sesion)
			opciones += "$d, $estado" to {
				c.elegir(d, listOf(
					getString(R.string.aj_rss_entrar) to { com.xebolax.lector.rss.SesionActivity.abrir(c, d) },
					getString(R.string.aj_rss_cerrar_sesion) to {
						com.xebolax.lector.rss.SesionActivity.cerrarSesion(d)
						(activity as? AppCompatActivity)?.avisar(getString(R.string.aj_rss_sesion_cerrada, d))
					},
					getString(R.string.aj_rss_quitar_medio) to {
						com.xebolax.lector.rss.SesionActivity.cerrarSesion(d)
						com.xebolax.lector.rss.ArticuloCompleto.guardarSuscripciones(c, lista - d)
						(activity as? AppCompatActivity)?.avisar(getString(R.string.aj_rss_medio_quitado, d))
					},
				))
			}
		}
		c.elegir(getString(R.string.aj_rss_medios), opciones)
	}

	/** Cuenta de sincronización de noticias (rss) o de podcasts (pod). */
	private fun configurarCuenta(pre: String) {
		findPreference<androidx.preference.EditTextPreference>("${pre}_sinc_url")?.apply {
			setOnBindEditTextListener { it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
			summaryProvider = Preference.SummaryProvider<androidx.preference.EditTextPreference> { p -> p.text.orEmpty() }
		}
		findPreference<androidx.preference.EditTextPreference>("${pre}_sinc_usuario")?.summaryProvider =
			Preference.SummaryProvider<androidx.preference.EditTextPreference> { p -> p.text.orEmpty() }
		findPreference<androidx.preference.EditTextPreference>("${pre}_sinc_clave")?.apply {
			setOnBindEditTextListener { it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
			summaryProvider = Preference.SummaryProvider<androidx.preference.EditTextPreference> { p ->
				if (p.text.isNullOrBlank()) "" else getString(R.string.aj_ia_clave_puesta)
			}
		}
		// Otra cuenta: los podcasts empiezan de cero con ella
		if (pre == "pod") for (clave in listOf("pod_sinc_tipo", "pod_sinc_url", "pod_sinc_usuario")) {
			findPreference<Preference>(clave)?.setOnPreferenceChangeListener { _, _ ->
				com.xebolax.lector.podcast.PodcastsSincronizacion.olvidar(requireContext())
				true
			}
		}
		val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
		val ahora = findPreference<Preference>("${pre}_sinc_ahora")
		val ultima = prefs.getLong("${pre}_sinc_ultima", 0)
		val error = prefs.getString("${pre}_sinc_error", "").orEmpty()
		ahora?.summary = when {
			error.isNotBlank() -> getString(R.string.sinc_error, error)
			ultima > 0 -> getString(R.string.sinc_ultima, android.text.format.DateUtils.getRelativeTimeSpanString(ultima))
			else -> null
		}
		alPulsar("${pre}_sinc_ahora") {
			val a = activity as? AppCompatActivity ?: return@alPulsar
			val listo = if (pre == "rss") com.xebolax.lector.rss.RssSincronizacion.configurado(a) else com.xebolax.lector.podcast.PodcastsSincronizacion.configurado(a)
			if (!listo) return@alPulsar a.avisar(getString(R.string.sinc_falta_cuenta))
			lifecycleScope.launch {
				val texto = try {
					a.conEspera(getString(R.string.aj_sinc_ahora), getString(R.string.sinc_sincronizando)) {
						if (pre == "rss") {
							val r = com.xebolax.lector.rss.RssSincronizacion.sincronizar(a)
							getString(R.string.sinc_rss_hecho, r.fuentes, r.nuevos)
						} else {
							val r = com.xebolax.lector.podcast.PodcastsSincronizacion.sincronizar(a)
							getString(R.string.sinc_pod_hecho, r.anadidos, r.quitados, r.acciones)
						}
					}.also { prefs.edit().putString("${pre}_sinc_error", "").apply() }
				} catch (e: kotlinx.coroutines.CancellationException) {
					throw e
				} catch (e: Exception) {
					getString(R.string.sinc_error, e.message ?: e.javaClass.simpleName)
				}
				ahora?.summary = texto
				a.avisar(texto)
			}
		}
	}

	private fun configurarBiblioteca() {
		// Qué son los PDF, Word y textos que no dicen nada: se aplica al momento a toda la biblioteca
		findPreference<ListPreference>("tipo_ambiguos")?.setOnPreferenceChangeListener { _, nuevo ->
			com.xebolax.lector.datos.TipoContenido.ambiguosComo = com.xebolax.lector.datos.TipoContenido.de(nuevo as String) ?: com.xebolax.lector.datos.TipoContenido.LIBRO
			true
		}
		findPreference<androidx.preference.EditTextPreference>("sinc_url")?.apply {
			setOnBindEditTextListener { it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
			val original = summary
			summaryProvider = Preference.SummaryProvider<androidx.preference.EditTextPreference> { p -> p.text?.takeIf { it.isNotBlank() } ?: original }
		}
		findPreference<androidx.preference.EditTextPreference>("sinc_usuario")?.summaryProvider =
			Preference.SummaryProvider<androidx.preference.EditTextPreference> { p -> p.text.orEmpty() }
		findPreference<androidx.preference.EditTextPreference>("sinc_clave")?.apply {
			setOnBindEditTextListener { it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
			summaryProvider = Preference.SummaryProvider<androidx.preference.EditTextPreference> { p ->
				if (p.text.isNullOrBlank()) "" else getString(R.string.aj_ia_clave_puesta)
			}
		}
		alPulsar("sinc_ahora") {
			val a = activity as? AppCompatActivity ?: return@alPulsar
			if (!com.xebolax.lector.sincronizacion.Sincronizador.configurado(a)) return@alPulsar a.avisar(getString(R.string.sinc_falta_url))
			lifecycleScope.launch {
				val texto = try {
					val r = a.conEspera(getString(R.string.aj_sinc_ahora), getString(R.string.sinc_sincronizando)) { com.xebolax.lector.sincronizacion.Sincronizador.sincronizar(a) }
					getString(R.string.sinc_hecho, r.actualizados, r.marcadores)
				} catch (e: kotlinx.coroutines.CancellationException) {
					throw e
				} catch (e: Exception) {
					getString(R.string.sinc_error, e.message.orEmpty())
				}
				findPreference<Preference>("sinc_ahora")?.summary = texto
				a.avisar(texto)
			}
		}
		actualizarVigiladas()
		alPulsar("carpetas_vigiladas") { carpetasVigiladas() }
	}
}
