package com.xebolax.lector.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.xebolax.lector.R
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Carpeta
import com.xebolax.lector.datos.Estado
import com.xebolax.lector.datos.Libro
import com.xebolax.lector.datos.claveTipo
import com.xebolax.lector.formatos.Formato
import com.xebolax.lector.lectura.Abiertos
import com.xebolax.lector.lectura.LectorActivity
import com.xebolax.lector.modelo.Contenido
import com.xebolax.lector.voz.LectorVoz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/**
 * Base de datos y disco fuera del hilo principal; después, ya en él, lo que
 * se avisa. El trabajo se termina aunque se cierre la pantalla.
 */
internal fun <T> AppCompatActivity.enSegundoPlano(trabajo: () -> T, despues: (T) -> Unit = {}) {
	lifecycleScope.launch {
		val r = withContext(NonCancellable + Dispatchers.IO) { trabajo() }
		despues(r)
	}
}

/**
 * Acciones de un libro y de una carpeta, iguales en todas las listas: menú
 * al mantener pulsado y acciones de TalkBack.
 */
object AccionesLibro {

	/** Los estados de lectura, en el orden en que se ofrecen. */
	private val ESTADOS = listOf(Estado.NUEVO, Estado.QUIERO_LEER, Estado.LEYENDO, Estado.TERMINADO, Estado.ABANDONADO)

	/** Elegir el estado de lectura de uno o varios libros. */
	fun elegirEstado(a: AppCompatActivity, ids: List<Long>, actual: Int = -1, alTerminar: () -> Unit = {}) {
		val db = BaseDatos.obtener(a)
		val nombres = listOf(R.string.estado_nuevo, R.string.estado_quiero_leer, R.string.estado_leyendo, R.string.estado_terminado, R.string.estado_abandonado).map { a.getString(it) }
		a.elegirUno(a.getString(R.string.estado_de_lectura), nombres, ESTADOS.indexOf(actual)) { i ->
			val nuevo = ESTADOS[i]
			val hacer = {
				a.enSegundoPlano({ ids.forEach { db.cambiarEstado(it, nuevo) } }) {
					a.avisar(a.getString(R.string.estado_puesto, nombres[i]))
					alTerminar()
				}
			}
			// Volver a «sin empezar» borra por dónde ibas: se pregunta
			if (nuevo == Estado.NUEVO) a.confirmar(a.getString(R.string.confirmar_no_leido), a.getString(R.string.marcar_no_leido), hacer) else hacer()
		}
	}

	/** Colecciones de un libro: casillas con todas, y «Nueva colección…». */
	fun elegirColecciones(a: AppCompatActivity, l: Libro) = elegirColecciones(a, listOf(l.id), l.tituloVisible)

	fun elegirColecciones(a: AppCompatActivity, ids: List<Long>, titulo: String) {
		val db = BaseDatos.obtener(a)
		a.lifecycleScope.launch {
			// Las colecciones y las de cada libro, fuera del hilo principal
			val (todas, tiene) = withContext(Dispatchers.IO) {
				val todas = db.colecciones()
				val deCada = ids.map { db.coleccionesDe(it) }
				todas to todas.map { c -> deCada.all { c.id in it } }.toBooleanArray()
			}
			com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
				.setTitle(a.getString(R.string.colecciones_de, titulo))
				.apply {
					if (todas.isEmpty()) setMessage(R.string.sin_colecciones)
					else setMultiChoiceItems(todas.map { it.nombre }.toTypedArray(), tiene) { _, i, marcada -> tiene[i] = marcada }
				}
				.setPositiveButton(R.string.guardar) { _, _ ->
					a.enSegundoPlano({ todas.forEachIndexed { i, c -> ids.forEach { db.ponerEnColeccion(it, c.id, tiene[i]) } } }) {
						a.avisar(a.getString(R.string.guardado))
					}
				}
				.setNeutralButton(R.string.nueva_coleccion_puntos) { _, _ ->
					a.pedirTexto(a.getString(R.string.nueva_coleccion), a.getString(R.string.nombre)) { n ->
						if (n.isNotBlank()) a.enSegundoPlano({
							val id = db.crearColeccion(n)
							ids.forEach { db.ponerEnColeccion(it, id, true) }
						}) { a.avisar(a.getString(R.string.en_coleccion, n.trim())) }
					}
				}
				.setNegativeButton(R.string.cancelar, null)
				.show()
		}
	}

	/**
	 * Elegir el tipo (libro, documento, audiolibro, página web) o dejar el
	 * que le toca por su formato (null).
	 */
	fun elegirTipo(a: AppCompatActivity, actual: String?, titulo: String = a.getString(R.string.cambiar_tipo), alElegir: (String) -> Unit) {
		val tipos = com.xebolax.lector.datos.Tipos.todos(a)
		// Automático (según el formato), cada tipo y, al final, crear uno nuevo
		val claves = listOf("") + tipos.map { it.clave }
		val nombres = listOf(a.getString(R.string.tipo_automatico)) + tipos.map { it.nombre } + a.getString(R.string.tipo_nuevo_puntos)
		a.elegirUno(titulo, nombres, if (actual == null) -1 else claves.indexOf(actual)) { i ->
			if (i < claves.size) alElegir(claves[i])
			else a.pedirTexto(a.getString(R.string.tipo_nuevo), a.getString(R.string.nombre)) { n ->
				if (n.isNotBlank()) alElegir(com.xebolax.lector.datos.Tipos.crearPropio(a, n).clave)
			}
		}
	}

	/** «Documentos», «Apuntes» o «automático, según el formato». */
	fun nombreTipo(a: android.content.Context, clave: String): String =
		if (clave.isBlank()) a.getString(R.string.tipo_automatico) else com.xebolax.lector.datos.Tipos.nombre(a, clave)

	/** La serie y el número de un libro (se rellenan solos si el libro los trae). */
	private fun editarSerie(a: AppCompatActivity, l: Libro) {
		a.pedirTexto(a.getString(R.string.serie), a.getString(R.string.serie_ayuda), if (l.serie.isBlank()) "" else
			if (l.numeroSerie > 0) "${l.serie}, ${l.numeroSerie.toString().removeSuffix(".0")}" else l.serie) { t ->
			val db = BaseDatos.obtener(a)
			a.enSegundoPlano({ if (t.isBlank()) db.ponerSerie(l.id, "", 0.0) else db.ponerSerieDeTexto(l.id, t) }) {
				a.avisar(a.getString(R.string.guardado))
			}
		}
	}

	fun acciones(a: AppCompatActivity, l: Libro): List<Accion> {
		val db = BaseDatos.obtener(a)
		val lista = mutableListOf<Accion>(
			a.getString(R.string.abrir) to { Abiertos.abrirPantalla(a, l) },
		)
		if (!l.audio) lista += a.getString(R.string.leer_en_voz_alta) to { leerEnVozAlta(a, l) }
		lista += a.getString(R.string.informacion) to { informacion(a, l) }
		lista += a.getString(R.string.mover_a_carpeta) to {
			elegirCarpeta(a, a.getString(R.string.mover_a_carpeta), null) { destino ->
				a.enSegundoPlano({
					db.moverLibro(l.id, destino)
					destino?.let { db.rutaCarpeta(it) }
				}) { ruta -> a.avisar(a.getString(R.string.movido_a, ruta ?: a.getString(R.string.biblioteca_raiz))) }
			}
		}
		lista += if (l.favorito) a.getString(R.string.quitar_favoritos) to {
			a.enSegundoPlano({ db.cambiarFavorito(l.id, false) }) { a.avisar(a.getString(R.string.quitado_favoritos)) }
		} else a.getString(R.string.anadir_favoritos) to {
			a.enSegundoPlano({ db.cambiarFavorito(l.id, true) }) { a.avisar(a.getString(R.string.anadido_favoritos)) }
		}
		if (l.estado != Estado.TERMINADO) lista += a.getString(R.string.marcar_terminado) to {
			a.enSegundoPlano({ db.cambiarEstado(l.id, Estado.TERMINADO) }) { a.avisar(a.getString(R.string.hecho)) }
		}
		if (l.estado != Estado.NUEVO) lista += a.getString(R.string.marcar_no_leido) to {
			a.confirmar(a.getString(R.string.confirmar_no_leido), a.getString(R.string.marcar_no_leido)) {
				a.enSegundoPlano({ db.cambiarEstado(l.id, Estado.NUEVO) }) { a.avisar(a.getString(R.string.hecho)) }
			}
		}
		lista += a.getString(R.string.estado_de_lectura) to { elegirEstado(a, listOf(l.id), l.estado) }
		lista += a.getString(R.string.colecciones_puntos) to { elegirColecciones(a, l) }
		lista += a.getString(R.string.serie_puntos) to { editarSerie(a, l) }
		lista += a.getString(R.string.cambiar_tipo) to {
			elegirTipo(a, l.tipo) { tipo ->
				a.enSegundoPlano({
					val sale = saleDeCarpeta(db, l, tipo)
					if (sale) db.moverLibro(l.id, null)
					db.cambiarTipo(l.id, tipo)
					sale
				}) { sale ->
					a.avisar(a.getString(R.string.tipo_cambiado, nombreTipo(a, tipo)) + if (sale) " " + a.getString(R.string.tipo_sale_de_carpeta) else "")
				}
			}
		}
		if (!l.audio) lista += a.getString(R.string.voz_de_este_libro) to { com.xebolax.lector.voz.VozPerfilActivity.abrirLibro(a, l.id, l.tituloVisible) }
		if (l.abierto > 0) lista += a.getString(R.string.quitar_de_leyendo) to {
			a.enSegundoPlano({ db.quitarDeRecientes(l.id) }) { a.avisar(a.getString(R.string.quitado_de_leyendo)) }
		}
		lista += a.getString(R.string.editar_titulo) to { editar(a, l) }
		lista += a.getString(R.string.compartir_archivo) to { compartir(a, l) }
		lista += a.getString(R.string.quitar_de_biblioteca) to {
			a.confirmar(a.getString(R.string.confirmar_quitar, l.tituloVisible), a.getString(R.string.quitar)) {
				pararSiSeUsa(listOf(l.id))
				a.enSegundoPlano({ quitar(a, l.id, l.uri) }) { a.avisar(a.getString(R.string.quitado_de_biblioteca)) }
			}
		}
		return lista
	}

	/**
	 * Al cambiar el tipo: ¿queda en una carpeta de otro tipo? Entonces no se
	 * vería en la pestaña de su tipo y sale a la raíz. Se compara con el tipo
	 * que tendrá de verdad («Automático» es el que le toca por su formato).
	 */
	fun saleDeCarpeta(db: BaseDatos, l: Libro, tipo: String): Boolean {
		val deCarpeta = l.carpeta?.let { db.carpeta(it) }?.tipo.orEmpty()
		return deCarpeta.isNotEmpty() && deCarpeta != l.copy(tipo = tipo).claveTipo
	}

	/** Antes de quitar o borrar: si eso se está leyendo en voz alta o está sonando, se para (en el hilo principal). */
	fun pararSiSeUsa(ids: Collection<Long>) {
		if (ids.any { LectorVoz.esActual(it) }) LectorVoz.detener()
		if (ids.any { com.xebolax.lector.audio.ControlAudio.esActual(it) }) com.xebolax.lector.audio.ControlAudio.detener()
	}

	/** Quita un libro de la biblioteca: las copias guardadas dentro de la app se borran; los archivos del dispositivo no se tocan. */
	fun quitar(c: android.content.Context, id: Long, uriLibro: String) {
		val uri = Uri.parse(uriLibro)
		if (uri.scheme == "file" && uri.path?.startsWith(c.filesDir.absolutePath) == true) runCatching { File(uri.path!!).delete() }
		BaseDatos.obtener(c).quitarLibro(id)
	}

	/** Empieza a leer en voz alta desde donde se quedó, sin abrir la pantalla de lectura. */
	fun leerEnVozAlta(a: AppCompatActivity, l: Libro) {
		a.lifecycleScope.launch {
			val contenido = runCatching { withContext(Dispatchers.IO) { Abiertos.abrir(a, l, Abiertos.opciones(a)) } }.getOrNull()
			val doc = (contenido as? Contenido.Texto)?.documento ?: return@launch a.avisar(a.getString(R.string.error_abrir, ""))
			val pos = l.posicion?.takeIf { !it.audio }
			LectorVoz.leerDesde(doc, l.id, doc.titulo.ifBlank { l.tituloVisible }, pos?.bloque ?: 0, pos?.caracter ?: 0)
			a.avisar(a.getString(R.string.leyendo_x, l.tituloVisible))
		}
	}

	fun informacion(a: AppCompatActivity, l: Libro) {
		val db = BaseDatos.obtener(a)
		val fecha = { t: Long -> if (t > 0) DateFormat.getMediumDateFormat(a).format(Date(t)) + " " + DateFormat.getTimeFormat(a).format(Date(t)) else "—" }
		val formato = Formato.nombreDe(a, l.formato)
		// La carpeta y los marcadores, de la base de datos fuera del hilo principal
		a.enSegundoPlano({ db.rutaCarpeta(l.carpeta) to db.marcadores(l.id).size }) { (ruta, marcas) ->
			val texto = buildString {
				appendLine(a.getString(R.string.info_titulo, l.tituloVisible))
				if (l.autor.isNotBlank()) appendLine(a.getString(R.string.info_autor, l.autor))
				appendLine(a.getString(R.string.info_formato, formato))
				if (l.tamano > 0) appendLine(a.getString(R.string.info_tamano, tamanoLegible(l.tamano)))
				appendLine(a.getString(R.string.info_archivo, l.nombre))
				appendLine(a.getString(R.string.info_carpeta, ruta.ifBlank { a.getString(R.string.biblioteca_raiz) }))
				appendLine(a.getString(R.string.info_progreso, l.progreso.toInt()))
				if (l.segundos > 0) appendLine(a.getString(R.string.info_tiempo_leido, a.tiempoHablado(l.segundos * 1000, conSegundos = false)))
				appendLine(a.getString(R.string.info_anadido, fecha(l.anadido)))
				appendLine(a.getString(R.string.info_abierto, fecha(l.abierto)))
				if (marcas > 0) appendLine(a.getString(R.string.info_marcadores, marcas))
			}
			a.mensaje(a.getString(R.string.informacion), texto.trim())
		}
	}

	fun editar(a: AppCompatActivity, l: Libro) {
		val (contTitulo, campoTitulo) = a.campoTexto(a.getString(R.string.titulo), l.tituloVisible)
		val (contAutor, campoAutor) = a.campoTexto(a.getString(R.string.autor), l.autor)
		val formulario = android.widget.LinearLayout(a).apply {
			orientation = android.widget.LinearLayout.VERTICAL
			val m = dp(a, 20)
			setPadding(m, m / 2, m, 0)
			addView(contTitulo)
			addView(contAutor)
		}
		com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
			.setTitle(R.string.editar_titulo)
			.setView(formulario)
			.setPositiveButton(R.string.guardar) { _, _ ->
				val titulo = campoTitulo.text.toString().trim()
				val autor = campoAutor.text.toString().trim()
				a.enSegundoPlano({ BaseDatos.obtener(a).renombrar(l.id, titulo, autor) }) { a.avisar(a.getString(R.string.guardado)) }
			}
			.setNegativeButton(R.string.cancelar, null)
			.show()
	}

	fun compartir(a: AppCompatActivity, l: Libro) {
		val uri = Uri.parse(l.uri)
		val compartible = when (uri.scheme) {
			"file" -> runCatching { FileProvider.getUriForFile(a, a.packageName + ".archivos", File(uri.path!!)) }.getOrNull()
			else -> if (android.provider.DocumentsContract.isTreeUri(uri)) null else uri
		} ?: return a.avisar(a.getString(R.string.no_se_puede_compartir))
		val intent = Intent(Intent.ACTION_SEND)
			.setType(a.contentResolver.getType(compartible) ?: "application/octet-stream")
			.putExtra(Intent.EXTRA_STREAM, compartible)
			.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		a.startActivity(Intent.createChooser(intent, a.getString(R.string.compartir_archivo)))
	}

	/** Elegir carpeta de destino (o crear una nueva). excluir: carpeta que no se puede elegir (al mover carpetas). */
	fun elegirCarpeta(a: AppCompatActivity, titulo: String, excluir: Long?, tipoNueva: String = "", alElegir: (Long?) -> Unit) {
		val db = BaseDatos.obtener(a)
		// Las carpetas con su ruta, de la base de datos fuera del hilo principal
		a.enSegundoPlano({
			val carpetas = db.todasLasCarpetas()
			val porId = carpetas.associateBy { it.id }
			fun ruta(c: Carpeta): String {
				val nombres = ArrayList<String>()
				var actual: Carpeta? = c
				var vueltas = 0
				while (actual != null && vueltas++ < 30) {
					nombres.add(0, actual.nombre)
					actual = actual.padre?.let { porId[it] }
				}
				return nombres.joinToString(" › ")
			}
			carpetas.filter { it.id != excluir }.map { it to ruta(it) }.sortedBy { it.second.lowercase() }
		}) { todas ->
			val opciones = mutableListOf<Accion>(
				a.getString(R.string.biblioteca_raiz) to { alElegir(null) },
				a.getString(R.string.nueva_carpeta) to {
					a.pedirTexto(a.getString(R.string.nueva_carpeta), a.getString(R.string.nombre)) { nombre ->
						// Una carpeta nueva al pasar algo a un tipo (Documentos, Apuntes…) es de ese tipo
						if (nombre.isNotBlank()) a.enSegundoPlano({ db.crearCarpeta(nombre, null, tipoNueva) }) { alElegir(it) }
					}
				},
			)
			todas.forEach { (c: Carpeta, ruta) -> opciones += ruta to { alElegir(c.id) } }
			a.elegir(titulo, opciones)
		}
	}

	fun accionesCarpeta(a: AppCompatActivity, c: Carpeta, alAbrir: () -> Unit): List<Accion> {
		val db = BaseDatos.obtener(a)
		return listOf(
			a.getString(R.string.abrir) to alAbrir,
			// Sus documentos, uno tras otro en voz alta
			a.getString(R.string.seguido_carpeta) to {
				a.enSegundoPlano({ db.deCarpeta(c.id) }) { com.xebolax.lector.voz.ListaLectura.empezar(a, it) }
			},
			a.getString(R.string.renombrar) to {
				a.pedirTexto(a.getString(R.string.renombrar), a.getString(R.string.nombre), c.nombre) { nombre ->
					if (nombre.isNotBlank()) a.enSegundoPlano({ db.renombrarCarpeta(c.id, nombre) }) {
						a.avisar(a.getString(R.string.carpeta_renombrada, nombre.trim()))
					}
				}
			},
			a.getString(R.string.nueva_subcarpeta) to {
				a.pedirTexto(a.getString(R.string.nueva_subcarpeta), a.getString(R.string.nombre)) { nombre ->
					if (nombre.isNotBlank()) a.enSegundoPlano({ db.crearCarpeta(nombre, c.id) }) {
						a.avisar(a.getString(R.string.carpeta_creada, nombre.trim()))
					}
				}
			},
			a.getString(R.string.cambiar_tipo_carpeta) to {
				elegirTipo(a, c.tipo) { tipo ->
					a.enSegundoPlano({
						// La carpeta pasa a ser de ese tipo (o de cualquiera) y todo lo que tiene, también
						db.cambiarTipoCarpeta(c.id, tipo)
						// Lo de la carpeta y de sus subcarpetas
						val ids = mutableListOf<Long>()
						fun recoger(id: Long) {
							ids += db.deCarpeta(id).map { it.id }
							// Las subcarpetas también pasan a ese tipo (si no, quedarían ocultas en la pestaña nueva)
							db.carpetas(id).forEach {
								db.cambiarTipoCarpeta(it.id, tipo)
								recoger(it.id)
							}
						}
						recoger(c.id)
						db.cambiarTipo(ids, tipo)
						ids.size
					}) { n -> a.avisar(a.resources.getQuantityString(R.plurals.tipo_cambiado_n, n, n, nombreTipo(a, tipo))) }
				}
			},
			a.getString(R.string.mover_carpeta) to {
				elegirCarpeta(a, a.getString(R.string.mover_carpeta), c.id) { destino ->
					// null: no se puede mover dentro de sí misma; si no, la ruta de destino («» es la raíz)
					a.enSegundoPlano({ if (!db.moverCarpeta(c.id, destino)) null else destino?.let { db.rutaCarpeta(it) }.orEmpty() }) { ruta ->
						if (ruta == null) a.avisar(a.getString(R.string.no_mover_dentro))
						else a.avisar(a.getString(R.string.movido_a, ruta.ifEmpty { a.getString(R.string.biblioteca_raiz) }))
					}
				}
			},
			a.getString(R.string.borrar_carpeta) to {
				a.confirmar(a.getString(R.string.confirmar_borrar_carpeta, c.nombre), a.getString(R.string.borrar)) {
					a.enSegundoPlano({ db.borrarCarpeta(c.id) }) { a.avisar(a.getString(R.string.carpeta_borrada)) }
				}
			},
		)
	}

	/** Abre el manual de uso en el propio lector (es accesible igual que un libro). */
	fun abrirManual(a: AppCompatActivity, archivo: String = "manual") {
		val idioma = if (a.resources.configuration.locales[0].language == "es") "es" else "en"
		val nombre = "$archivo-$idioma.html"
		val destino = File(a.filesDir, "ayuda/$nombre")
		destino.parentFile?.mkdirs()
		runCatching {
			a.assets.open(nombre).use { e -> destino.outputStream().use { e.copyTo(it) } }
		}
		Abiertos.olvidar()
		a.startActivity(
			Intent(a, LectorActivity::class.java)
				.putExtra(LectorActivity.EXTRA_URI, Uri.fromFile(destino).toString())
				.putExtra(LectorActivity.EXTRA_TITULO, a.getString(if (archivo == "manual") R.string.manual else R.string.novedades))
		)
	}
}
