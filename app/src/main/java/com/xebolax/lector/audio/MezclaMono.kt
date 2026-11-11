package com.xebolax.lector.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Sonido en mono (Ajustes, Audiolibros y DAISY): junta los dos canales en
 * uno, para quien lleva un solo auricular (para oír la calle) o tiene peor
 * un oído y se perdería lo que solo suena por un lado. Se activa y se quita
 * al momento, sin cortar lo que suena. Vale para audiolibros, podcasts y radio.
 */
@UnstableApi
class MezclaMono : BaseAudioProcessor() {

	companion object {
		/** Lo comparten todos los reproductores (uno por cada vez que se crea el servicio de audio). */
		@Volatile
		var activo = false
	}

	override fun onConfigure(formato: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
		// Solo PCM de 16 bits (lo normal); con otro formato no hace nada
		if (formato.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
		return formato
	}

	override fun queueInput(entrada: ByteBuffer) {
		if (!entrada.hasRemaining()) return
		val salida = replaceOutputBuffer(entrada.remaining())
		if (activo && inputAudioFormat.channelCount == 2) {
			while (entrada.remaining() >= 4) {
				val izquierda = entrada.getShort().toInt()
				val derecha = entrada.getShort().toInt()
				val m = ((izquierda + derecha) / 2).toShort()
				salida.putShort(m)
				salida.putShort(m)
			}
		}
		salida.put(entrada)
		salida.flip()
	}
}
