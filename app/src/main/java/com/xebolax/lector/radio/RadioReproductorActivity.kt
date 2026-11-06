package com.xebolax.lector.radio

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.xebolax.lector.R
import com.xebolax.lector.audio.ControlAudio
import com.xebolax.lector.databinding.ActivityRadioReproductorBinding
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.configurarBarra
import com.xebolax.lector.ui.conTemporizadorAlMantener
import com.xebolax.lector.ui.elegir
import com.xebolax.lector.ui.elegirTemporizador
import com.xebolax.lector.ui.mensaje
import com.xebolax.lector.ui.nombreTemporizador

/**
 * Reproductor de la radio: la emisora, lo que suena (TalkBack lo dice al
 * cambiar de canción, si se quiere), emisora anterior y siguiente,
 * reproducir o pausar y lo que se puede hacer con la emisora. Si lo abre un
 * despertador, arriba están Apagar y Posponer (también con la pantalla bloqueada).
 */
class RadioReproductorActivity : AppCompatActivity() {

	companion object {
		const val EXTRA_DESPERTADOR = "despertador"
	}

	private lateinit var b: ActivityRadioReproductorBinding
	private val principal = Handler(Looper.getMainLooper())
	private var emisora: Emisora? = null
	private val oyente = ControlAudio.Oyente { principal.post { actualizar() } }
	private val oyenteDb = RadioBaseDatos.Oyente { principal.post { actualizar() } }

	/** El temporizador cuenta los minutos: se refresca cada 20 s (sin regiones vivas que cambien cada segundo). */
	private val refresco = object : Runnable {
		override fun run() {
			actualizar()
			principal.postDelayed(this, 20_000)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityRadioReproductorBinding.inflate(layoutInflater)
		setContentView(b.root)
		configurarBarra(b.barra, getString(R.string.radio))
		ViewCompat.setAccessibilityHeading(b.nombre, true)
		configurarControles()
		despertador(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		despertador(intent)
	}

	/** Abierto por un despertador: se ve sobre la pantalla de bloqueo, con Apagar y Posponer. */
	private fun despertador(i: Intent) {
		val es = i.getBooleanExtra(EXTRA_DESPERTADOR, false)
		b.panelDespertador.isVisible = es
		if (!es) return
		if (android.os.Build.VERSION.SDK_INT >= 27) {
			setShowWhenLocked(true)
			setTurnScreenOn(true)
		} else {
			@Suppress("DEPRECATION")
			window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
		}
		b.botonPosponer.text = getString(R.string.radio_posponer_n, RadioProgramador.posponerMinutos(this))
		b.botonApagar.post { b.botonApagar.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
	}

	override fun onStart() {
		super.onStart()
		ControlAudio.registrar(oyente)
		RadioBaseDatos.obtener(this).suscribir(oyenteDb)
		ControlAudio.conectar(this) { actualizar() }
		principal.post(refresco)
		// Si no suena la radio (se detuvo), no hay nada que mostrar
		if (ControlAudio.libro == null && !b.panelDespertador.isVisible) principal.postDelayed({ if (Radio.actual == null) finish() }, 1500)
	}

	override fun onStop() {
		ControlAudio.desregistrar(oyente)
		RadioBaseDatos.obtener(this).desuscribir(oyenteDb)
		principal.removeCallbacks(refresco)
		super.onStop()
	}

	private fun configurarControles() {
		b.botonReproducir.setOnClickListener {
			ControlAudio.alternar()
			if (ControlAudio.temporizadorAutomatico) {
				ControlAudio.temporizadorAutomatico = false
				avisar(getString(R.string.temporizador_automatico, nombreTemporizador(ControlAudio.minutosTemporizador())))
			}
			actualizar()
		}
		b.botonReproducir.conTemporizadorAlMantener { temporizador(conQuitar = false) }
		b.botonAnterior.setOnClickListener { pasar(false) }
		b.botonSiguiente.setOnClickListener { pasar(true) }
		b.botonFavorita.setOnClickListener { emisora?.let { RadioAcciones.alternarFavorita(this, it); actualizar() } }
		b.botonTemporizador.setOnClickListener { temporizador(conQuitar = true) }
		b.botonGrabar.setOnClickListener {
			val e = emisora ?: return@setOnClickListener
			if (ServicioGrabacion.grabandoEsta(e)) {
				ServicioGrabacion.parar(this)
				avisar(getString(R.string.radio_grabacion_guardada))
				principal.postDelayed({ actualizar() }, 500)
			} else RadioAcciones.grabar(this, e)
		}
		b.botonBuscarCancion.setOnClickListener {
			val c = ControlAudio.cancion
			if (c.isBlank()) mensaje(getString(R.string.radio_buscar_cancion), getString(R.string.radio_sin_cancion))
			else elegir(c, RadioAcciones.deCancion(this, c))
		}
		b.botonCanciones.setOnClickListener { emisora?.let { RadioAcciones.canciones(this, it) } }
		b.botonEcualizador.setOnClickListener { ecualizador() }
		b.botonEcualizador.isVisible = intentEcualizador().resolveActivity(packageManager) != null
		b.botonMas.setOnClickListener {
			val e = emisora ?: return@setOnClickListener
			// Lo que no tiene botón propio (sin repetir los de arriba)
			val ya = setOf(getString(R.string.radio_ir_reproductor), getString(R.string.radio_escuchar), getString(R.string.radio_anadir_favorita),
				getString(R.string.radio_quitar_favorita), getString(R.string.radio_grabar), getString(R.string.radio_detener_grabacion), getString(R.string.radio_canciones_emisora))
			elegir(e.nombre, RadioAcciones.deEmisora(this, e, conEscuchar = false).filter { it.first !in ya } +
				(getString(R.string.radio_detener_radio) to {
					ControlAudio.detener()
					finish()
				}))
		}
		b.botonApagar.setOnClickListener {
			RadioProgramador.apagar(this)
			finish()
		}
		b.botonPosponer.setOnClickListener {
			RadioProgramador.posponer(this)
			avisar(getString(R.string.radio_pospuesto, RadioProgramador.posponerMinutos(this)))
			finish()
		}
	}

	private fun pasar(delante: Boolean) {
		val nueva = Radio.pasar(this, delante) ?: return avisar(getString(R.string.radio_sin_mas_emisoras))
		// El nombre de la emisora nueva lo dice el título (encabezado) y el estado
		avisar(nueva.nombre, mostrar = false)
	}

	private fun temporizador(conQuitar: Boolean) {
		elegirTemporizador(getString(if (conQuitar) R.string.temporizador else R.string.reproducir_con_temporizador), conQuitar = conQuitar, conCapitulo = false) { minutos ->
			if (!ControlAudio.reproduciendo && minutos != 0) ControlAudio.reproducir()
			ControlAudio.temporizador(minutos)
			avisar(if (minutos == 0) getString(R.string.temporizador_quitado) else getString(R.string.temporizador_puesto, nombreTemporizador(minutos)))
			actualizar()
		}
	}

	private fun intentEcualizador() = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
		.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, ControlAudio.jugador?.audioSessionId ?: 0)
		.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
		.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)

	private fun ecualizador() {
		runCatching { startActivity(intentEcualizador()) }.onFailure { mensaje(getString(R.string.radio_ecualizador), getString(R.string.radio_sin_ecualizador)) }
	}

	private var ultimoTitulo = ""

	private fun actualizar() {
		val e = Radio.actual
		emisora = e
		val nombre = e?.nombre ?: getString(R.string.radio)
		if (nombre != ultimoTitulo) {
			ultimoTitulo = nombre
			supportActionBar?.title = nombre
			title = nombre
		}
		b.nombre.text = nombre
		val suena = ControlAudio.reproduciendo
		val estado = when {
			e == null -> getString(R.string.radio_nada_sonando)
			ControlAudio.problema.isNotBlank() -> ControlAudio.problema
			suena && ControlAudio.jugador?.playbackState == androidx.media3.common.Player.STATE_BUFFERING -> getString(R.string.radio_conectando)
			suena -> getString(R.string.mini_en_directo)
			else -> getString(R.string.en_pausa)
		}
		if (b.estado.text.toString() != estado) b.estado.text = estado
		val cancion = ControlAudio.cancion.takeIf { e != null && it.isNotBlank() }?.let { getString(R.string.radio_suena_x, it) }.orEmpty()
		// Que TalkBack diga la canción al cambiar es opcional (Ajustes, Radio)
		b.cancion.accessibilityLiveRegion = if (Radio.prefs(this).getBoolean("radio_decir_cancion", true)) android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE else android.view.View.ACCESSIBILITY_LIVE_REGION_NONE
		if (b.cancion.text.toString() != cancion) b.cancion.text = cancion
		b.cancion.isVisible = cancion.isNotBlank()
		b.datos.text = e?.let { listOf(it.paisLegible(), it.generos(), it.calidad()).filter { s -> s.isNotBlank() }.joinToString(" · ") }.orEmpty()
		b.datos.isVisible = b.datos.text.isNotBlank()
		val extra = listOfNotNull(
			ControlAudio.minutosTemporizador().takeIf { it != 0 }?.let { getString(R.string.temporizador_x, nombreTemporizador(it)) },
			ServicioGrabacion.grabando?.let { getString(R.string.radio_grabando_x, it.nombre) },
		).joinToString(". ")
		b.extra.text = extra
		b.extra.isVisible = extra.isNotBlank()

		b.botonReproducir.setIconResource(if (suena) R.drawable.ic_pausa else R.drawable.ic_reproducir)
		b.botonReproducir.contentDescription = getString(if (suena) R.string.pausar else R.string.reproducir)
		ViewCompat.setTooltipText(b.botonReproducir, b.botonReproducir.contentDescription)
		val activo = e != null
		listOf(b.botonFavorita, b.botonGrabar, b.botonCanciones, b.botonMas, b.botonTemporizador, b.botonBuscarCancion).forEach { it.isEnabled = activo }
		if (e != null) {
			b.botonFavorita.text = getString(if (RadioBaseDatos.obtener(this).esFavorita(e.uuid)) R.string.radio_quitar_favorita else R.string.radio_anadir_favorita)
			b.botonGrabar.text = getString(if (ServicioGrabacion.grabandoEsta(e)) R.string.radio_detener_grabacion else R.string.radio_grabar)
		}
		val minutos = ControlAudio.minutosTemporizador()
		b.botonTemporizador.text = if (minutos != 0) getString(R.string.temporizador_x, nombreTemporizador(minutos)) else getString(R.string.temporizador)
	}

	override fun onKeyDown(codigo: Int, evento: KeyEvent): Boolean {
		// Teclado físico: espacio reproduce o pausa; Ctrl + flechas cambian de emisora
		when {
			codigo == KeyEvent.KEYCODE_SPACE && evento.repeatCount == 0 -> b.botonReproducir.performClick()
			codigo == KeyEvent.KEYCODE_DPAD_RIGHT && evento.isCtrlPressed -> pasar(true)
			codigo == KeyEvent.KEYCODE_DPAD_LEFT && evento.isCtrlPressed -> pasar(false)
			else -> return super.onKeyDown(codigo, evento)
		}
		return true
	}
}
