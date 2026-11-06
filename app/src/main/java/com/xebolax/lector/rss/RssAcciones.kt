package com.xebolax.lector.rss

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xebolax.lector.R
import com.xebolax.lector.lectura.LectorActivity
import com.xebolax.lector.ui.Accion
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.mensaje
import com.xebolax.lector.ui.conEspera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Abrir un artículo y sus acciones, iguales en todas las pestañas y en el
 * lector. Los artículos se leen con el lector de la app pero NO van a la
 * biblioteca, salvo con «Guardar en la biblioteca».
 */
object RssAcciones {

	const val EXTRA_ARTICULO = "rss_articulo"
	const val EXTRA_LISTA = "rss_lista"

	/** Abre un artículo en el lector (o en el navegador si la fuente lo pide). lista: para Siguiente y Anterior. */
	fun abrir(a: AppCompatActivity, articulo: Articulo, lista: LongArray = longArrayOf(articulo.id)) {
		val db = RssBaseDatos.obtener(a)
		val fuente = db.fuente(articulo.fuente)
		if (Rss.prefs(a).getBoolean("rss_marcar_al_abrir", true) && !articulo.leido) db.marcarLeidos(listOf(articulo.id))
		RssTrabajo.quitarAviso(a, articulo.id)
		if (fuente?.enNavegador == true && articulo.enlace.isNotBlank()) {
			abrirNavegador(a, articulo.enlace)
			return
		}
		a.lifecycleScope.launch {
			var contenido = articulo.contenido
			if (Rss.hayQueCompletar(a, articulo, fuente)) {
				val r = a.conEspera(articulo.titulo, a.getString(R.string.rss_descargando_articulo)) { Rss.completar(a, articulo) }
				if (r.resultado == ArticuloCompleto.Resultado.COMPLETO) contenido = r.html
				// Solo para suscriptores: se dice, y se lee lo que trae la fuente
				else if (r.resultado == ArticuloCompleto.Resultado.SUSCRIPTORES) a.avisar(Rss.motivo(a, r), mostrar = false)
			}
			val uri = withContext(Dispatchers.IO) { Rss.archivo(a, "articulo_${articulo.id}", Rss.html(a, articulo, contenido)) }
			a.startActivity(
				Intent(a, LectorActivity::class.java)
					.putExtra(LectorActivity.EXTRA_URI, uri.toString())
					.putExtra(LectorActivity.EXTRA_TITULO, articulo.titulo)
					.putExtra(EXTRA_ARTICULO, articulo.id)
					.putExtra(EXTRA_LISTA, lista)
					.putExtra(LectorActivity.EXTRA_LEER_AL_ABRIR, Rss.prefs(a).getBoolean("rss_leer_al_abrir", false))
			)
		}
	}

	/** Lee seguidos en voz alta varios artículos (por ejemplo, todos los no leídos). */
	fun leerSeguidos(a: AppCompatActivity, titulo: String, lista: List<Articulo>, marcarLeidos: Boolean = true) {
		if (lista.isEmpty()) return a.avisar(a.getString(R.string.rss_nada_que_leer))
		a.lifecycleScope.launch {
			val uri = withContext(Dispatchers.IO) { Rss.archivo(a, "seguidos", Rss.htmlVarios(a, titulo, lista)) }
			// No se marcan todos al empezar: el lector marca como leídos los que se han llegado a escuchar
			a.startActivity(
				Intent(a, LectorActivity::class.java)
					.putExtra(LectorActivity.EXTRA_URI, uri.toString())
					.putExtra(LectorActivity.EXTRA_TITULO, titulo)
					.putExtra(LectorActivity.EXTRA_LEER_AL_ABRIR, true)
					.apply { if (marcarLeidos) putExtra(LectorActivity.EXTRA_SEGUIDOS, lista.map { it.id }.toLongArray()) }
			)
		}
	}

	/** Esquema de los enlaces de los titulares a su artículo: lectoruniversal-articulo:<id>. */
	const val ESQUEMA_ARTICULO = "lectoruniversal-articulo:"

	/** Lee seguidos solo los titulares (con su fuente y fecha); cada titular es un enlace a su artículo. */
	fun leerTitulares(a: AppCompatActivity, titulo: String, lista: List<Articulo>) {
		if (lista.isEmpty()) return a.avisar(a.getString(R.string.rss_nada_que_leer))
		a.lifecycleScope.launch {
			val uri = withContext(Dispatchers.IO) { Rss.archivo(a, "titulares", Rss.htmlTitulares(a, titulo, lista)) }
			a.startActivity(
				Intent(a, LectorActivity::class.java)
					.putExtra(LectorActivity.EXTRA_URI, uri.toString())
					.putExtra(LectorActivity.EXTRA_TITULO, titulo)
					.putExtra(LectorActivity.EXTRA_LEER_AL_ABRIR, true)
					.putExtra(EXTRA_LISTA, lista.map { it.id }.toLongArray())
			)
		}
	}

	fun abrirNavegador(a: AppCompatActivity, url: String) {
		runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
			.onFailure { a.avisar(a.getString(R.string.no_disponible)) }
	}

	fun compartir(a: AppCompatActivity, articulo: Articulo) {
		val texto = listOf(articulo.titulo, articulo.enlace).filter { it.isNotBlank() }.joinToString("\n")
		a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), a.getString(R.string.compartir)))
	}

	/** El libro de la biblioteca si el artículo ya se guardó (y sigue ahí). */
	fun enBiblioteca(a: AppCompatActivity, articulo: Articulo): Long? =
		articulo.enBiblioteca.takeIf { it > 0 && com.xebolax.lector.datos.BaseDatos.obtener(a).libro(it) != null }

	/** Guarda el artículo (completo si se puede) en la biblioteca principal; si ya estaba, lo abre. */
	fun guardarEnBiblioteca(a: AppCompatActivity, articulo: Articulo, alTerminar: () -> Unit = {}) {
		enBiblioteca(a, articulo)?.let { return com.xebolax.lector.lectura.Abiertos.abrirPantalla(a, it) }
		a.lifecycleScope.launch {
			a.conEspera(a.getString(R.string.rss_guardar_biblioteca)) {
				val contenido = if (Rss.hayQueCompletar(a, articulo, RssBaseDatos.obtener(a).fuente(articulo.fuente)))
					Rss.completar(a, articulo).takeIf { it.resultado == ArticuloCompleto.Resultado.COMPLETO }?.html ?: articulo.contenido
				else articulo.contenido
				withContext(Dispatchers.IO) { Rss.guardarEnBiblioteca(a, articulo, contenido) }
			}
			a.avisar(a.getString(R.string.rss_guardado_biblioteca))
			alTerminar()
		}
	}

	/** Pide otra vez el artículo completo a su web y dice cómo ha ido. */
	fun descargarCompleto(a: AppCompatActivity, articulo: Articulo, alCambiar: () -> Unit = {}) {
		a.lifecycleScope.launch {
			val r = a.conEspera(a.getString(R.string.rss_descargar_completo), a.getString(R.string.rss_descargando_articulo)) { Rss.completar(a, articulo.copy(completo = false)) }
			// El resultado en una ventana: se lee entero y el foco vuelve al artículo
			a.mensaje(a.getString(R.string.rss_descargar_completo), Rss.motivo(a, r))
			alCambiar()
		}
	}

	/** Escuchar el audio de un artículo (podcast) con el reproductor de la app, sin añadirlo a la biblioteca. */
	fun escuchar(a: AppCompatActivity, articulo: Articulo) {
		if (!articulo.esPodcast) return
		a.startActivity(
			Intent(a, com.xebolax.lector.audio.ReproductorActivity::class.java)
				.putExtra(com.xebolax.lector.audio.ReproductorActivity.EXTRA_AUDIO_URL, articulo.adjunto)
				.putExtra(com.xebolax.lector.audio.ReproductorActivity.EXTRA_AUDIO_TITULO, articulo.titulo)
				.putExtra(com.xebolax.lector.audio.ReproductorActivity.EXTRA_AUDIO_AUTOR, articulo.nombreFuente)
				.putExtra(EXTRA_ARTICULO, articulo.id)
		)
	}

	/** Acciones de un artículo (menú al mantener pulsado y acciones de TalkBack). */
	fun acciones(a: AppCompatActivity, articulo: Articulo, lista: () -> LongArray, alCambiar: () -> Unit = {}): List<Accion> {
		val db = RssBaseDatos.obtener(a)
		val r = mutableListOf<Accion>(
			a.getString(R.string.abrir) to { abrir(a, articulo, lista()) },
		)
		if (articulo.esPodcast) r += a.getString(R.string.rss_escuchar) to { escuchar(a, articulo) }
		r += (if (articulo.leido) a.getString(R.string.rss_marcar_no_leido) else a.getString(R.string.rss_marcar_leido)) to {
			db.marcarLeidos(listOf(articulo.id), !articulo.leido)
			a.avisar(a.getString(if (articulo.leido) R.string.rss_marcado_no_leido else R.string.rss_marcado_leido))
			alCambiar()
		}
		r += (if (articulo.favorito) a.getString(R.string.rss_quitar_favorito) else a.getString(R.string.rss_anadir_favorito)) to {
			db.marcarFavorito(articulo.id, !articulo.favorito)
			a.avisar(a.getString(if (articulo.favorito) R.string.rss_quitado_favorito else R.string.rss_anadido_favorito))
			alCambiar()
		}
		r += (if (articulo.masTarde) a.getString(R.string.rss_quitar_mas_tarde) else a.getString(R.string.rss_mas_tarde)) to {
			db.marcarMasTarde(articulo.id, !articulo.masTarde)
			a.avisar(a.getString(if (articulo.masTarde) R.string.rss_quitado_mas_tarde else R.string.rss_anadido_mas_tarde))
			alCambiar()
		}
		if (articulo.enlace.isNotBlank() && !articulo.esPodcast) r += a.getString(R.string.rss_descargar_completo) to { descargarCompleto(a, articulo, alCambiar) }
		if (articulo.enlace.isNotBlank()) r += a.getString(R.string.abrir_navegador) to { abrirNavegador(a, articulo.enlace) }
		r += a.getString(R.string.compartir) to { compartir(a, articulo) }
		// Si ya está en la biblioteca, la acción es abrirlo (no volver a guardarlo)
		r += (if (enBiblioteca(a, articulo) != null) a.getString(R.string.rss_abrir_en_biblioteca) else a.getString(R.string.rss_guardar_biblioteca)) to {
			guardarEnBiblioteca(a, articulo, alCambiar)
		}
		r += a.getString(R.string.rss_a_la_papelera) to {
			db.marcarPapelera(listOf(articulo.id), true)
			a.avisar(a.getString(R.string.rss_enviado_papelera))
			alCambiar()
		}
		return r
	}
}
