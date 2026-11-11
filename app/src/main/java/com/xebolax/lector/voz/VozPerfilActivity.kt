package com.xebolax.lector.voz

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.xebolax.lector.R
import com.xebolax.lector.databinding.ActivityAjustesBinding
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.ui.DeslizadorAjuste
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.configurarBarra
import com.xebolax.lector.ui.confirmar
import java.util.Locale

/**
 * Voz propia de una sección (Biblioteca, Compartidos, Noticias) o de un
 * libro: motor (también ElevenLabs), voz, velocidad, tono, cambio de voz
 * según el idioma y pausa entre párrafos. Todo empieza «Como en…» lo que
 * hereda (una sección, la voz general; un libro, la de su sección): solo lo
 * que se cambia aquí es propio. Los cambios se aplican al momento, también si
 * se está leyendo.
 */
class VozPerfilActivity : AppCompatActivity() {

	companion object {
		private const val EXTRA_LIBRO = "libro"
		private const val EXTRA_TITULO = "titulo"

		fun abrirLibro(c: Context, libro: Long, titulo: String) =
			c.startActivity(Intent(c, VozPerfilActivity::class.java).putExtra(EXTRA_LIBRO, libro).putExtra(EXTRA_TITULO, titulo))

		fun abrirNoticias(c: Context) = abrirSeccion(c, PerfilesVoz.NOTICIAS)

		/** La voz de una sección: PerfilesVoz.BIBLIOTECA, COMPARTIDOS o NOTICIAS. */
		fun abrirSeccion(c: Context, seccion: Long) =
			c.startActivity(Intent(c, VozPerfilActivity::class.java).putExtra(EXTRA_LIBRO, seccion))
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val b = ActivityAjustesBinding.inflate(layoutInflater)
		setContentView(b.root)
		val libro = intent.getLongExtra(EXTRA_LIBRO, PerfilesVoz.NOTICIAS)
		val titulo = when (libro) {
			PerfilesVoz.NOTICIAS -> getString(R.string.voz_noticias_titulo)
			PerfilesVoz.BIBLIOTECA -> getString(R.string.voz_biblioteca_titulo)
			PerfilesVoz.COMPARTIDOS -> getString(R.string.voz_compartidos_titulo)
			else -> getString(R.string.voz_libro_titulo, intent.getStringExtra(EXTRA_TITULO).orEmpty())
		}
		configurarBarra(b.barra, titulo)
		if (savedInstanceState == null) {
			supportFragmentManager.beginTransaction().replace(R.id.contenedor, VozPerfilFragment().apply { arguments = bundleOf(EXTRA_LIBRO to libro) }).commit()
		}
	}
}

class VozPerfilFragment : PreferenceFragmentCompat() {

	private var libro = PerfilesVoz.NOTICIAS
	private lateinit var almacen: Almacen
	/** Idioma por el que se filtra la lista de voces (vacío = todos). */
	private var filtroIdioma = ""

	/** Lee y guarda en el perfil (no en los ajustes generales); cada cambio se aplica a la lectura. */
	private inner class Almacen : PreferenceDataStore() {
		var perfil = PerfilesVoz.de(requireContext(), libro)
		/** Lo que hereda (la voz general, o la de su sección si es un libro). */
		val base = PerfilesVoz.base(requireContext(), libro)
		/** Con lo heredado: lo que de verdad se usará. */
		val efectivo get() = perfil.sobre(base)
		private val p get() = Preferencias(requireContext())

		private fun guardar(nuevo: PerfilVoz) {
			perfil = nuevo
			PerfilesVoz.guardar(requireContext(), libro, nuevo)
			LectorVoz.ajustesLibroCambiados(libro)
		}

		override fun getString(clave: String, defecto: String?): String? = when (clave) {
			"vp_motor" -> perfil.motor.orEmpty()
			"vp_voz" -> perfil.voz.orEmpty()
			"vp_idioma" -> filtroIdioma
			"vp_segun_idioma" -> when (perfil.segunIdioma) { null -> ""; true -> "si"; false -> "no" }
			else -> defecto
		}

		override fun putString(clave: String, valor: String?) {
			val v = valor.orEmpty()
			when (clave) {
				// Otro motor: la voz elegida era del anterior
				"vp_motor" -> guardar(perfil.copy(motor = v.ifBlank { null }, voz = null))
				"vp_voz" -> guardar(perfil.copy(voz = v.ifBlank { null }))
				"vp_idioma" -> filtroIdioma = v
				"vp_segun_idioma" -> guardar(perfil.copy(segunIdioma = when (v) { "si" -> true; "no" -> false; else -> null }))
			}
		}

		override fun getBoolean(clave: String, defecto: Boolean): Boolean = when (clave) {
			"vp_velocidad_propia" -> perfil.velocidad != null
			"vp_tono_propio" -> perfil.tono != null
			"vp_pausa_propia" -> perfil.pausaParrafos != null
			else -> defecto
		}

		override fun putBoolean(clave: String, valor: Boolean) {
			// Al activarlo, empieza con el valor de Ajustes; al quitarlo, vuelve a seguir a Ajustes
			when (clave) {
				"vp_velocidad_propia" -> guardar(perfil.copy(velocidad = if (valor) base.velocidadEfectiva(p) else null))
				"vp_tono_propio" -> guardar(perfil.copy(tono = if (valor) base.tonoEfectivo(p) else null))
				"vp_pausa_propia" -> guardar(perfil.copy(pausaParrafos = if (valor) base.pausaEfectiva(p) else null))
			}
		}

		override fun getInt(clave: String, defecto: Int): Int = when (clave) {
			"vp_velocidad" -> efectivo.velocidadEfectiva(p)
			"vp_tono" -> efectivo.tonoEfectivo(p)
			"vp_pausa" -> efectivo.pausaEfectiva(p)
			else -> defecto
		}

		override fun putInt(clave: String, valor: Int) {
			when (clave) {
				"vp_velocidad" -> guardar(perfil.copy(velocidad = valor))
				"vp_tono" -> guardar(perfil.copy(tono = valor))
				"vp_pausa" -> guardar(perfil.copy(pausaParrafos = valor))
			}
		}
	}

	override fun onCreatePreferences(estado: Bundle?, raiz: String?) {
		libro = arguments?.getLong("libro", PerfilesVoz.NOTICIAS) ?: PerfilesVoz.NOTICIAS
		almacen = Almacen()
		preferenceManager.preferenceDataStore = almacen
		setPreferencesFromResource(R.xml.ajustes_perfil_voz, raiz)
		findPreference<Preference>("vp_explicacion")?.summary = getString(when (libro) {
			PerfilesVoz.NOTICIAS -> R.string.voz_explicacion_noticias
			PerfilesVoz.BIBLIOTECA -> R.string.voz_explicacion_biblioteca
			PerfilesVoz.COMPARTIDOS -> R.string.voz_explicacion_compartidos
			else -> if (PerfilesVoz.seccionDe(requireContext(), libro) == PerfilesVoz.COMPARTIDOS) R.string.voz_explicacion_compartido else R.string.voz_explicacion_libro
		})
		configurar()
	}

	override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		androidx.core.view.ViewCompat.setAccessibilityPaneTitle(view, requireActivity().title)
	}

	private fun configurar() {
		val c = requireContext()
		val p = Preferencias(c)
		val motor = findPreference<ListPreference>("vp_motor")
		val idioma = findPreference<ListPreference>("vp_idioma")
		val voz = findPreference<ListPreference>("vp_voz")
		val segun = findPreference<ListPreference>("vp_segun_idioma")
		val base = almacen.base
		// De dónde hereda: una sección, de la voz general; un libro, de su sección
		val deDonde = getString(when {
			PerfilesVoz.esSeccion(libro) -> R.string.voz_general_nombre
			PerfilesVoz.seccionDe(c, libro) == PerfilesVoz.COMPARTIDOS -> R.string.voz_de_compartidos_nombre
			else -> R.string.voz_de_biblioteca_nombre
		})
		fun comoEn(valor: String) = getString(R.string.como_en_x, deDonde, valor)

		// Motores: «Como en … (el que hereda)», los instalados y ElevenLabs si hay clave
		LectorVoz.motores { lista ->
			if (!isAdded) return@motores
			val general = lista.firstOrNull { it.first == base.motorEfectivo(p) }?.second ?: getString(R.string.motor_por_defecto)
			motor?.entries = (listOf(comoEn(general)) + lista.map { it.second }).toTypedArray()
			motor?.entryValues = (listOf("") + lista.map { it.first }).toTypedArray()
			motor?.value = almacen.perfil.motor.orEmpty()
			motor?.summary = motor?.entry
		}

		fun cargarVoces() {
			// Las voces del motor que se usará (el propio o el heredado), con un motor aparte
			VocesMotor.voces(c, almacen.efectivo.motorEfectivo(p)) { voces ->
				if (!isAdded) return@voces
				val idiomas = voces.map { it.locale.language }.distinct().sortedBy { Locale(it).displayLanguage }
				idioma?.entries = (listOf(getString(R.string.todos_los_idiomas)) + idiomas.map { Locale(it).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { ch -> ch.uppercase() } }).toTypedArray()
				idioma?.entryValues = (listOf("") + idiomas).toTypedArray()
				idioma?.value = filtroIdioma
				idioma?.summary = idioma?.entry
				val visibles = voces.filter { filtroIdioma.isEmpty() || it.locale.language == filtroIdioma || it.name == almacen.perfil.voz }
				// La heredada, si es de este mismo motor
				val heredada = if (almacen.perfil.motor == null || almacen.perfil.motor == base.motor) base.vozEfectiva(p) else ""
				val general = voces.firstOrNull { it.name == heredada }?.let { VocesMotor.etiqueta(c, it) } ?: getString(R.string.voz_por_defecto)
				voz?.entries = (listOf(comoEn(general)) + visibles.map { v -> VocesMotor.etiqueta(c, v) }).toTypedArray()
				voz?.entryValues = (listOf("") + visibles.map { it.name }).toTypedArray()
				voz?.value = almacen.perfil.voz.orEmpty()
				voz?.summary = voz?.entry
			}
		}
		cargarVoces()
		motor?.setOnPreferenceChangeListener { pref, _ ->
			view?.post {
				pref.summary = (pref as ListPreference).entry
				cargarVoces()
			}
			true
		}
		idioma?.setOnPreferenceChangeListener { _, _ ->
			view?.post { cargarVoces() }
			true
		}
		voz?.setOnPreferenceChangeListener { pref, _ ->
			view?.post { pref.summary = (pref as ListPreference).entry }
			true
		}

		val general = getString(if (base.segunIdiomaEfectivo(p)) R.string.si else R.string.no)
		segun?.entries = arrayOf(comoEn(general), getString(R.string.si), getString(R.string.no))
		segun?.entryValues = arrayOf("", "si", "no")
		segun?.value = almacen.getString("vp_segun_idioma", "")
		segun?.summary = segun?.entry
		segun?.setOnPreferenceChangeListener { pref, _ ->
			view?.post { pref.summary = (pref as ListPreference).entry }
			true
		}

		// Cada deslizador solo se ve con su «propio» activado (si no, sigue a Ajustes)
		for ((interruptor, deslizador) in listOf("vp_velocidad_propia" to "vp_velocidad", "vp_tono_propio" to "vp_tono", "vp_pausa_propia" to "vp_pausa")) {
			val s = findPreference<SwitchPreferenceCompat>(interruptor) ?: continue
			val d = findPreference<DeslizadorAjuste>(deslizador) ?: continue
			d.isVisible = s.isChecked
			s.summaryOff = comoEn(d.texto(almacen.getInt(deslizador, 0)))
			s.setOnPreferenceChangeListener { _, nuevo ->
				view?.post {
					d.value = almacen.getInt(deslizador, d.value)
					d.isVisible = nuevo as Boolean
				}
				true
			}
		}

		findPreference<Preference>("vp_probar")?.setOnPreferenceClickListener {
			VocesMotor.probar(c, almacen.efectivo, getString(R.string.prueba_voz_texto))
			true
		}
		findPreference<Preference>("vp_restablecer")?.setOnPreferenceClickListener {
			c.confirmar(getString(R.string.vp_restablecer_pregunta), getString(R.string.vp_restablecer)) {
				val antes = PerfilesVoz.de(c, libro)
				PerfilesVoz.guardar(c, libro, PerfilVoz())
				LectorVoz.ajustesLibroCambiados(libro)
				if (antes != PerfilVoz()) (activity as? AppCompatActivity)?.avisar(getString(R.string.vp_restablecido))
				// Se rehace la pantalla con los valores de Ajustes
				parentFragmentManager.beginTransaction().replace(R.id.contenedor, VozPerfilFragment().apply { arguments = this@VozPerfilFragment.arguments }).commit()
			}
			true
		}
	}
}
