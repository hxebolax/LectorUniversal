package com.xebolax.lector.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.xebolax.lector.radio.Flujos

/**
 * De dónde lee el reproductor lo que viene de internet (radio, podcasts,
 * audio de las noticias), con lo que hace falta para que suene casi todo:
 * - agente de usuario propio (algunos servidores rechazan el de Android);
 * - redirecciones también entre http y https (radiojar, servidores que pasan a https);
 * - tiempos de espera holgados (15 s para conectar, 20 para leer);
 * - y, si el certificado de un https no vale, la misma dirección con http.
 * Los archivos del móvil (libros, descargas) se siguen leyendo igual.
 */
@UnstableApi
object FuenteRed {

	fun fabrica(c: Context): DataSource.Factory {
		val http = DefaultHttpDataSource.Factory()
			.setUserAgent(Flujos.agente)
			.setAllowCrossProtocolRedirects(true)
			.setConnectTimeoutMs(15_000)
			.setReadTimeoutMs(20_000)
		return DefaultDataSource.Factory(c, ReservaHttp.Fabrica(http))
	}

	/** Si un https falla por el certificado, se prueba con http (mismo servidor y puerto). */
	class ReservaHttp(private val base: HttpDataSource) : DataSource {
		override fun addTransferListener(oyente: TransferListener) = base.addTransferListener(oyente)

		override fun open(dataSpec: DataSpec): Long = try {
			base.open(dataSpec)
		} catch (e: HttpDataSource.HttpDataSourceException) {
			val u = dataSpec.uri
			val esCertificado = generateSequence<Throwable>(e) { it.cause }.any { it is javax.net.ssl.SSLException }
			if (u.scheme.equals("https", true) && esCertificado) {
				runCatching { base.close() }
				base.open(dataSpec.withUri(u.buildUpon().scheme("http").build()))
			} else throw e
		}

		override fun read(bufer: ByteArray, desde: Int, cuantos: Int): Int = base.read(bufer, desde, cuantos)
		override fun getUri(): Uri? = base.uri
		// Imprescindible: con las cabeceras llegan los títulos ICY de la canción que suena
		override fun getResponseHeaders(): Map<String, List<String>> = base.responseHeaders
		override fun close() = base.close()

		class Fabrica(private val f: HttpDataSource.Factory) : DataSource.Factory {
			override fun createDataSource(): DataSource = ReservaHttp(f.createDataSource())
		}
	}
}
