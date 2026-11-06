package com.xebolax.lector.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import com.xebolax.lector.R
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.TipoBloque

/**
 * Sonidos cortos que avisan sin voz (se pueden quitar en Ajustes): pasar por
 * un marcador o una nota (sonidos de Paperback), llegar al final de los
 * elementos, terminar una búsqueda y error.
 */
object Sonidos {

	enum class Tipo { MARCADOR, NOTA, LIMITE, LISTO, ERROR }

	/** Sonidos de los elementos del texto (se generan una vez: tonos cortos). */
	private enum class Elemento(val clave: String, val notas: List<Pair<Double, Int>>) {
		CAPITULO("capitulo", listOf(523.0 to 70, 784.0 to 110)),
		ENCABEZADO("encabezado", listOf(880.0 to 60)),
		ENLACE("enlace", listOf(1318.0 to 35, 0.0 to 30, 1318.0 to 35)),
		TABLA("tabla", listOf(392.0 to 50, 523.0 to 50)),
		LISTA("lista", listOf(659.0 to 45)),
		IMAGEN("imagen", listOf(988.0 to 40, 740.0 to 60)),
		CITA("cita", listOf(587.0 to 90)),
		NOTA_PIE("nota", listOf(1175.0 to 40, 880.0 to 40)),
	}

	private val idsElementos = HashMap<Elemento, Int>()

	private var pool: SoundPool? = null
	private val ids = HashMap<Tipo, Int>()
	@Volatile
	private var generador: ToneGenerator? = null

	private fun cargar(contexto: Context): SoundPool {
		pool?.let { return it }
		val p = SoundPool.Builder()
			.setMaxStreams(2)
			.setAudioAttributes(
				AudioAttributes.Builder()
					.setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
					.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
					.build()
			)
			.build()
		ids[Tipo.MARCADOR] = p.load(contexto, R.raw.marcador, 1)
		ids[Tipo.NOTA] = p.load(contexto, R.raw.nota, 1)
		val carpeta = java.io.File(contexto.cacheDir, "sonidos").apply { mkdirs() }
		for (e in Elemento.entries) {
			val archivo = java.io.File(carpeta, "${e.clave}.wav")
			if (!archivo.exists()) runCatching { archivo.writeBytes(tono(e.notas)) }
			idsElementos[e] = p.load(archivo.path, 1)
		}
		pool = p
		return p
	}

	/** WAV (16 bits, 22050 Hz) con unas notas seguidas; frecuencia 0 = silencio. */
	private fun tono(notas: List<Pair<Double, Int>>): ByteArray {
		val frecuencia = 22050
		val muestras = ArrayList<Short>()
		for ((hz, ms) in notas) {
			val n = frecuencia * ms / 1000
			for (i in 0 until n) {
				// Subida y bajada suaves para que no chasquee
				val envolvente = minOf(1.0, i / 200.0, (n - i) / 400.0)
				val valor = if (hz == 0.0) 0.0 else Math.sin(2 * Math.PI * hz * i / frecuencia) * envolvente * 0.5
				muestras += (valor * Short.MAX_VALUE).toInt().toShort()
			}
		}
		val datos = muestras.size * 2
		val b = java.nio.ByteBuffer.allocate(44 + datos).order(java.nio.ByteOrder.LITTLE_ENDIAN)
		b.put("RIFF".toByteArray()).putInt(36 + datos).put("WAVE".toByteArray())
		b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(frecuencia).putInt(frecuencia * 2).putShort(2).putShort(16)
		b.put("data".toByteArray()).putInt(datos)
		for (m in muestras) b.putShort(m)
		return b.array()
	}

	/** Al llegar a un bloque: el sonido de su tipo, si está elegido en Ajustes. */
	fun alLlegar(contexto: Context, doc: com.xebolax.lector.modelo.Documento, bloque: Int) {
		val elegidos = Preferencias(contexto).sonidosElementos
		if (elegidos.isEmpty()) return
		val b = doc.bloques.getOrNull(bloque) ?: return
		val esCapitulo = b.tiene(Marca.INICIO_SECCION) || doc.indice.any { it.bloque == bloque }
		val e = when {
			esCapitulo && "capitulo" in elegidos -> Elemento.CAPITULO
			b.tipo == TipoBloque.ENCABEZADO -> Elemento.ENCABEZADO
			b.tipo == TipoBloque.IMAGEN -> Elemento.IMAGEN
			b.tipo == TipoBloque.FILA_TABLA && b.tiene(Marca.ENCABEZADO_TABLA) -> Elemento.TABLA
			b.tipo == TipoBloque.ELEMENTO_LISTA && b.tiene(Marca.INICIO_LISTA) -> Elemento.LISTA
			b.tipo == TipoBloque.CITA -> Elemento.CITA
			b.tipo == TipoBloque.NOTA -> Elemento.NOTA_PIE
			b.enlaces.isNotEmpty() -> Elemento.ENLACE
			else -> return
		}
		if (e.clave !in elegidos) return
		runCatching { idsElementos[e]?.let { cargar(contexto).play(it, 0.7f, 0.7f, 1, 0, 1f) } ?: cargar(contexto) }
	}

	fun sonar(contexto: Context, tipo: Tipo) {
		if (!Preferencias(contexto).sonidos) return
		runCatching {
			when (tipo) {
				Tipo.MARCADOR, Tipo.NOTA -> ids[tipo]?.let { cargar(contexto).play(it, 0.8f, 0.8f, 1, 0, 1f) }
					?: cargar(contexto)
				else -> {
					val g = generador ?: ToneGenerator(AudioManager.STREAM_MUSIC, 60).also { generador = it }
					when (tipo) {
						Tipo.LIMITE -> g.startTone(ToneGenerator.TONE_PROP_NACK, 120)
						Tipo.LISTO -> g.startTone(ToneGenerator.TONE_PROP_ACK, 120)
						else -> g.startTone(ToneGenerator.TONE_PROP_NACK, 250)
					}
				}
			}
		}
	}

	/** Precarga los sonidos (el primero tardaría en sonar). */
	fun preparar(contexto: Context) {
		runCatching { cargar(contexto.applicationContext) }
	}
}
