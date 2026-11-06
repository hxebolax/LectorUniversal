package com.xebolax.lector.radio

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xebolax.lector.R
import com.xebolax.lector.audio.ControlAudio
import com.xebolax.lector.ui.Accion
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.conEspera
import com.xebolax.lector.ui.copiar
import com.xebolax.lector.ui.elegir
import com.xebolax.lector.ui.mensaje
import kotlinx.coroutines.launch

/**
 * Lo que se puede hacer con una emisora, una canción o una grabación. Lo
 * usan las listas de la Radio (menú y acciones de TalkBack) y el reproductor,
 * para que todo se llame y funcione igual en todas partes.
 */
object RadioAcciones {

	/**
	 * Acciones de una emisora. Los textos cambian según el estado («Quitar de
	 * favoritas» si ya lo es, «Detener la grabación» si se está grabando…).
	 * extra: las propias de la lista (mover a carpeta, orden, quitar del historial).
	 */
	fun deEmisora(a: AppCompatActivity, e: Emisora, lista: List<Emisora> = emptyList(), extra: List<Accion> = emptyList(), conEscuchar: Boolean = true): List<Accion> {
		val db = RadioBaseDatos.obtener(a)
		val r = mutableListOf<Accion>()
		if (conEscuchar) r += if (Radio.actual?.uuid == e.uuid) a.getString(R.string.radio_ir_reproductor) to { abrirReproductor(a) }
		else a.getString(R.string.radio_escuchar) to { Radio.escuchar(a, e, lista) }
		r += if (db.esFavorita(e.uuid)) a.getString(R.string.radio_quitar_favorita) to { alternarFavorita(a, e) }
		else a.getString(R.string.radio_anadir_favorita) to { alternarFavorita(a, e) }
		r += extra
		r += if (ServicioGrabacion.grabandoEsta(e)) a.getString(R.string.radio_detener_grabacion) to {
			ServicioGrabacion.parar(a)
			a.avisar(a.getString(R.string.radio_grabacion_guardada))
		} else a.getString(R.string.radio_grabar) to { grabar(a, e) }
		r += a.getString(R.string.radio_despertar_con) to { (a as? RadioActivity)?.irA(RadioListaFragment.Modo.PROGRAMAR) { it.editarProgramacion(null, Programacion.DESPERTADOR, e) } ?: programarDesdeFuera(a, e) }
		r += a.getString(R.string.radio_canciones_emisora) to { canciones(a, e) }
		if (!e.propia) r += a.getString(R.string.radio_votar) to { votar(a, e) }
		r += a.getString(R.string.radio_info) to { informacion(a, e) }
		if (e.web.isNotBlank()) r += a.getString(R.string.abrir_navegador) to {
			runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.web))) }.onFailure { a.avisar(a.getString(R.string.no_disponible)) }
		}
		r += a.getString(R.string.compartir) to { Radio.compartir(a, Radio.textoCompartir(e)) }
		r += a.getString(R.string.radio_copiar_direccion) to { a.copiar(e.url) }
		r += a.getString(R.string.radio_acceso_directo) to {
			if (!Radio.accesoDirecto(a, e)) a.mensaje(a.getString(R.string.radio_acceso_directo), a.getString(R.string.radio_sin_accesos))
		}
		if (e.propia && db.esFavorita(e.uuid)) r += a.getString(R.string.radio_editar) to { (a as? RadioActivity)?.anadirAMano(editar = e) }
		return r
	}

	fun abrirReproductor(a: AppCompatActivity) = a.startActivity(Intent(a, RadioReproductorActivity::class.java))

	fun alternarFavorita(a: AppCompatActivity, e: Emisora) {
		val ahora = Radio.alternarFavorita(a, e)
		a.avisar(a.getString(if (ahora) R.string.radio_anadida_favoritas else R.string.radio_quitada_favoritas, e.nombre))
	}

	/** Despertador pedido desde fuera de la sección (el reproductor): se abre Radio en Programar. */
	private fun programarDesdeFuera(a: AppCompatActivity, e: Emisora) {
		a.startActivity(Intent(a, RadioActivity::class.java).setAction(RadioActivity.ACCION_PROGRAMAR).putExtra(RadioActivity.EXTRA_EMISORA, e.json().toString()))
	}

	/** Grabar: hasta pararla o unos minutos. Se graba aparte, aunque se escuche otra cosa. */
	fun grabar(a: AppCompatActivity, e: Emisora) {
		if (!ServicioGrabacion.sePuedeGrabar(e)) return a.mensaje(a.getString(R.string.radio_grabar), a.getString(R.string.radio_no_se_puede_grabar))
		val minutos = listOf(0, 15, 30, 60, 90, 120, 180)
		val nombres = minutos.map { if (it == 0) a.getString(R.string.radio_hasta_parar) else a.resources.getQuantityString(R.plurals.minutos_n, it, it) }
		a.elegir(a.getString(R.string.radio_grabar_x, e.nombre), nombres.mapIndexed { i, n ->
			n to {
				val otra = ServicioGrabacion.grabando
				ServicioGrabacion.grabar(a, e, minutos[i])
				a.avisar(if (otra != null) a.getString(R.string.radio_grabacion_cambiada, otra.nombre, e.nombre) else a.getString(R.string.radio_grabando_x, e.nombre))
			}
		})
	}

	/** Las canciones que han sonado en esta emisora (las que ella dice); al elegir una, sus acciones. */
	fun canciones(a: AppCompatActivity, e: Emisora) {
		val lista = RadioBaseDatos.obtener(a).canciones(e.uuid)
		if (lista.isEmpty()) return a.mensaje(a.getString(R.string.radio_canciones_emisora), a.getString(R.string.radio_sin_canciones_emisora))
		a.elegir(a.getString(R.string.radio_canciones_de, e.nombre), lista.map { c ->
			"${c.titulo}, ${cuando(c.fecha)}" to { a.elegir(c.titulo, deCancion(a, c.titulo)) }
		})
	}

	fun cuando(ms: Long): String = DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

	/** Acciones de una canción: buscarla, copiarla y compartirla. */
	fun deCancion(a: AppCompatActivity, titulo: String): List<Accion> = listOf(
		a.getString(R.string.radio_buscar_cancion) to { Radio.buscarCancion(a, titulo) },
		a.getString(R.string.radio_copiar_cancion) to { a.copiar(titulo) },
		a.getString(R.string.radio_compartir_cancion) to { Radio.compartir(a, titulo) },
	)

	fun votar(a: AppCompatActivity, e: Emisora) {
		a.lifecycleScope.launch {
			val r = runCatching { a.conEspera(a.getString(R.string.radio_votar), a.getString(R.string.radio_votando)) { RadioBrowser.votar(e.uuid) } }
				.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
			when {
				r.isFailure -> a.mensaje(a.getString(R.string.radio_votar), a.getString(R.string.radio_sin_directorio))
				r.getOrNull() == null -> a.avisar(a.getString(R.string.radio_votada, e.nombre))
				else -> a.mensaje(a.getString(R.string.radio_votar), a.getString(R.string.radio_voto_rechazado))
			}
		}
	}

	fun informacion(a: AppCompatActivity, e: Emisora) {
		val db = RadioBaseDatos.obtener(a)
		val escuchada = db.historial().firstOrNull { it.emisora.uuid == e.uuid }
		val lineas = listOfNotNull(
			e.paisLegible().takeIf { it.isNotBlank() }?.let { p -> a.getString(R.string.radio_info_pais, listOf(p, e.region).filter { it.isNotBlank() }.joinToString(", ")) },
			e.idioma.takeIf { it.isNotBlank() }?.let { a.getString(R.string.radio_info_idioma, it) },
			e.etiquetas.takeIf { it.isNotBlank() }?.let { a.getString(R.string.radio_info_generos, e.generos(10)) },
			e.calidad().takeIf { it.isNotBlank() }?.let { a.getString(R.string.radio_info_calidad, it) + if (e.hls) " (HLS)" else "" },
			if (!e.propia) a.getString(R.string.radio_info_votos, e.votos, e.clics) else a.getString(R.string.radio_info_propia),
			a.getString(R.string.radio_no_funciona).takeIf { !e.funciona },
			escuchada?.let { a.getString(R.string.radio_info_escuchada, it.veces, cuando(it.ultima), com.xebolax.lector.ui.tiempoCorto(it.segundos * 1000)) },
			e.web.takeIf { it.isNotBlank() }?.let { a.getString(R.string.radio_info_web, it) },
			a.getString(R.string.radio_info_direccion, e.url),
		)
		MaterialAlertDialogBuilder(a)
			.setTitle(e.nombre)
			.setMessage(lineas.joinToString("\n\n"))
			.setPositiveButton(R.string.aceptar, null)
			.show()
	}

	// --- Grabaciones ------------------------------------------------------------------------------

	fun escucharGrabacion(a: AppCompatActivity, g: Grabacion) {
		val f = java.io.File(g.archivo)
		if (!f.exists()) return a.mensaje(a.getString(R.string.radio_grabaciones), a.getString(R.string.radio_grabacion_no_esta))
		ControlAudio.externoPendiente = ControlAudio.Externo(
			id = -6_000_000_000L - g.id,
			titulo = f.nameWithoutExtension,
			autor = g.emisora,
			url = Uri.fromFile(f).toString(),
			posicionMs = 0,
		)
		a.startActivity(Intent(a, com.xebolax.lector.audio.ReproductorActivity::class.java).putExtra(com.xebolax.lector.audio.ReproductorActivity.EXTRA_EXTERNO, true))
	}

	fun compartirGrabacion(a: AppCompatActivity, g: Grabacion) {
		val f = java.io.File(g.archivo)
		if (!f.exists()) return a.mensaje(a.getString(R.string.radio_grabaciones), a.getString(R.string.radio_grabacion_no_esta))
		val uri = androidx.core.content.FileProvider.getUriForFile(a, a.packageName + ".archivos", f)
		a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/*").putExtra(Intent.EXTRA_STREAM, uri)
			.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), a.getString(R.string.compartir)))
	}

	fun guardarEnBiblioteca(a: AppCompatActivity, g: Grabacion) {
		val f = java.io.File(g.archivo)
		if (!f.exists()) return a.mensaje(a.getString(R.string.radio_grabaciones), a.getString(R.string.radio_grabacion_no_esta))
		a.lifecycleScope.launch {
			val r = com.xebolax.lector.biblioteca.Importador.anadirArchivos(a, listOf(Uri.fromFile(f)), null)
			a.avisar(a.getString(if (r.anadidos.isNotEmpty()) R.string.radio_grabacion_en_biblioteca else R.string.radio_grabacion_ya_en_biblioteca))
		}
	}
}
