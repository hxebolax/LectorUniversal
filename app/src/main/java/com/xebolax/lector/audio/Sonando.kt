package com.xebolax.lector.audio

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.xebolax.lector.R
import com.xebolax.lector.databinding.VistaMiniReproductorBinding
import com.xebolax.lector.lectura.LectorActivity
import com.xebolax.lector.ui.etiquetaPulsar
import com.xebolax.lector.voz.LectorVoz

/**
 * Lo que está sonando en la app: la lectura en voz alta (un libro o una
 * noticia) o el reproductor de audio (audiolibro, episodio, audio de una
 * noticia o radio). Solo suena una cosa a la vez: la última que empezó.
 */
object Sonando {

	enum class Fuente { VOZ, AUDIO }

	/** La última que se puso a sonar (si las dos están cargadas y en pausa, manda esta). */
	@Volatile
	var ultima: Fuente? = null

	/** Lo que muestra el mini reproductor ahora (null: nada cargado). */
	fun actual(): Fuente? {
		val voz = LectorVoz.documento != null
		val audio = ControlAudio.libro != null
		return when {
			LectorVoz.leyendo -> Fuente.VOZ
			ControlAudio.reproduciendo -> Fuente.AUDIO
			ultima == Fuente.VOZ && voz -> Fuente.VOZ
			ultima == Fuente.AUDIO && audio -> Fuente.AUDIO
			audio -> Fuente.AUDIO
			voz -> Fuente.VOZ
			else -> null
		}
	}

	fun esRadio() = ControlAudio.externo?.directo == true

	/** La pantalla de lo que suena: el lector (voz) o el reproductor que corresponda. */
	fun pantalla(c: Context, f: Fuente): Intent? = when (f) {
		Fuente.VOZ -> LectorVoz.volver?.let { Intent(it) }
			?: LectorVoz.libro.takeIf { it > 0 }?.let { Intent(c, LectorActivity::class.java).putExtra(LectorActivity.EXTRA_LIBRO, it) }
		Fuente.AUDIO -> when {
			ControlAudio.libro == null -> null
			esRadio() -> Intent(c, com.xebolax.lector.radio.RadioReproductorActivity::class.java)
			ControlAudio.externo != null -> Intent(c, ReproductorActivity::class.java).putExtra(ReproductorActivity.EXTRA_EXTERNO, true)
			else -> Intent(c, ReproductorActivity::class.java).putExtra(LectorActivity.EXTRA_LIBRO, ControlAudio.idLibro)
		}
	}?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

	/** Título de lo que suena; en la radio, con la canción. */
	fun titulo(f: Fuente): String = when (f) {
		Fuente.VOZ -> LectorVoz.titulo
		Fuente.AUDIO -> {
			val l = ControlAudio.libro
			val t = l?.titulo.orEmpty()
			if (esRadio()) t else listOf(t, ControlAudio.capituloActual()?.titulo.orEmpty().takeIf { it.isNotBlank() && it != t && (l?.capitulos?.size ?: 0) > 0 }.orEmpty())
				.filter { it.isNotBlank() }.joinToString(" · ")
		}
	}

	/** «Leyendo en voz alta», «En pausa», «Radio en directo: canción», «Reconectando…». */
	fun estado(c: Context, f: Fuente): String = when (f) {
		Fuente.VOZ -> c.getString(if (LectorVoz.leyendo) R.string.mini_leyendo else R.string.mini_lectura_en_pausa)
		Fuente.AUDIO -> {
			val base = c.getString(if (ControlAudio.reproduciendo) (if (esRadio()) R.string.mini_en_directo else R.string.reproduciendo) else R.string.en_pausa)
			listOf(base, ControlAudio.problema.ifBlank { ControlAudio.cancion }).filter { it.isNotBlank() }.joinToString(": ")
		}
	}

	fun suena(f: Fuente) = if (f == Fuente.VOZ) LectorVoz.leyendo else ControlAudio.reproduciendo

	fun alternar(f: Fuente) = if (f == Fuente.VOZ) LectorVoz.alternar() else ControlAudio.alternar()

	fun detener(f: Fuente) = if (f == Fuente.VOZ) LectorVoz.detener() else ControlAudio.detener()
}

/**
 * Mini reproductor encima de las pestañas de las secciones (Biblioteca,
 * Podcasts, Noticias y Radio): qué suena (tocar vuelve a su pantalla),
 * Pausar o Reanudar y Detener. Sale solo si hay algo cargado y sigue igual al
 * cambiar de sección.
 */
class MiniReproductor @JvmOverloads constructor(c: Context, atributos: AttributeSet? = null) : LinearLayout(c, atributos) {

	private val b = VistaMiniReproductorBinding.inflate(LayoutInflater.from(c), this)
	private var fuente: Sonando.Fuente? = null

	private val oyenteAudio = ControlAudio.Oyente { post { actualizar() } }
	private val oyenteVoz = LectorVoz.Oyente { post { actualizar() } }

	init {
		orientation = VERTICAL
		isVisible = false
		b.abrir.setOnClickListener { fuente?.let { f -> Sonando.pantalla(context, f)?.let { context.startActivity(it) } } }
		b.abrir.etiquetaPulsar(c.getString(R.string.mini_volver))
		b.alternar.setOnClickListener {
			fuente?.let { Sonando.alternar(it) }
			actualizar()
		}
		b.detener.setOnClickListener {
			fuente?.let { Sonando.detener(it) }
			actualizar()
		}
	}

	/** Empieza a seguir lo que suena (en onStart de la pantalla). */
	fun empezar() {
		ControlAudio.iniciar(context)
		LectorVoz.iniciar(context)
		ControlAudio.registrar(oyenteAudio)
		LectorVoz.registrar(oyenteVoz)
		actualizar()
	}

	fun parar() {
		ControlAudio.desregistrar(oyenteAudio)
		LectorVoz.desregistrar(oyenteVoz)
	}

	private fun actualizar() {
		val f = Sonando.actual()
		fuente = f
		isVisible = f != null
		if (f == null) return
		val titulo = Sonando.titulo(f)
		val estado = Sonando.estado(context, f)
		b.titulo.text = titulo
		b.detalle.text = estado
		// Una sola parada: «Radio en directo: canción, Radio 3» (estado primero, como las filas de las listas)
		b.abrir.contentDescription = context.getString(R.string.mini_descripcion, estado, titulo)
		val suena = Sonando.suena(f)
		b.alternar.setImageResource(if (suena) R.drawable.ic_pausa else R.drawable.ic_reproducir)
		b.alternar.contentDescription = context.getString(if (suena) R.string.pausar else R.string.reanudar)
		androidx.core.view.ViewCompat.setTooltipText(b.alternar, b.alternar.contentDescription)
	}
}
