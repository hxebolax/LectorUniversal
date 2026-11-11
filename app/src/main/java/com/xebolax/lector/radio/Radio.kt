package com.xebolax.lector.radio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xebolax.lector.LectorApp
import com.xebolax.lector.R
import com.xebolax.lector.audio.ControlAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * La radio: escuchar una emisora (con el reproductor común, en directo),
 * pasar a la siguiente o a la anterior de la lista desde la que se escuchó,
 * la canción que suena, favoritas, votos, listas M3U y accesos directos.
 */
object Radio {

	fun prefs(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)

	/** La emisora cargada en el reproductor (aunque esté en pausa). */
	val actual: Emisora? get() = emisora?.takeIf { e -> ControlAudio.externo?.directo == true && ControlAudio.idLibro == e.idAudio }
	private var emisora: Emisora? = null

	/** Lista desde la que se escuchó (para Siguiente y Anterior); si está vacía, las favoritas. */
	private var lista: List<Emisora> = emptyList()

	fun suena(e: Emisora) = actual?.uuid == e.uuid && ControlAudio.reproduciendo

	// --- Tiempo escuchado (para «Las que más escuchas») ---------------------------------------

	private var desde = 0L
	private var contando: String? = null
	private var oyendo = false

	private fun contarTiempo(c: Context) {
		val a = actual
		val ahora = SystemClock.elapsedRealtime()
		val sigue = a != null && ControlAudio.reproduciendo
		contando?.let { uuid ->
			if (desde > 0 && (!sigue || a?.uuid != uuid)) {
				RadioBaseDatos.obtener(c).sumarTiempo(uuid, (ahora - desde) / 1000)
				desde = 0
				contando = null
			}
		}
		if (sigue && contando == null) {
			contando = a!!.uuid
			desde = ahora
		}
	}

	private fun oir(c: Context) {
		if (oyendo) return
		oyendo = true
		val app = c.applicationContext
		ControlAudio.registrar { contarTiempo(app) }
	}

	// --- Escuchar ----------------------------------------------------------------------------

	private fun conDatosMoviles(c: Context): Boolean {
		val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
		return cm.isActiveNetworkMetered
	}

	/**
	 * Escucha una emisora. lista: las de la pantalla desde la que se escucha
	 * (Siguiente y Anterior se mueven por ella). Si el usuario lo eligió en
	 * Ajustes, se abre el reproductor; si no, se sigue en la lista con el mini
	 * reproductor abajo.
	 */
	fun escuchar(c: Context, e: Emisora, enLista: List<Emisora> = emptyList(), abrirPantalla: Boolean? = null, despertador: Boolean = false) {
		if (c is Activity && !despertador && prefs(c).getBoolean("radio_avisar_datos", false) && conDatosMoviles(c)) {
			MaterialAlertDialogBuilder(c)
				.setTitle(e.nombre)
				.setMessage(R.string.radio_aviso_datos)
				.setPositiveButton(R.string.radio_escuchar) { _, _ -> empezar(c, e, enLista, abrirPantalla, despertador) }
				.setNegativeButton(R.string.cancelar, null)
				.show()
			return
		}
		empezar(c, e, enLista, abrirPantalla, despertador)
	}

	private fun empezar(c: Context, e: Emisora, enLista: List<Emisora>, abrirPantalla: Boolean?, despertador: Boolean) {
		val app = c.applicationContext
		oir(app)
		if (enLista.isNotEmpty()) lista = enLista.distinctBy { it.uuid }
		emisora = e
		val db = RadioBaseDatos.obtener(app)
		val ext = ControlAudio.Externo(
			id = e.idAudio,
			titulo = e.nombre,
			autor = listOf(e.paisLegible(), e.generos(2)).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { app.getString(R.string.radio) },
			url = e.url,
			posicionMs = 0,
			directo = true,
			tipo = if (e.hls || e.url.substringBefore('?').endsWith(".m3u8", true)) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
			alPasar = { delante -> pasar(app, delante) },
			alCambiarCancion = { titulo -> if (prefs(app).getBoolean("radio_guardar_canciones", true)) db.apuntarCancion(titulo, e) },
			despertador = despertador,
		)
		val volver = abrirPantalla ?: prefs(app).getBoolean("radio_abrir_reproductor", false)
		ControlAudio.conectar(app) {
			ControlAudio.cargarExterno(ext)
			ControlAudio.reproducir()
			if (despertador) ControlAudio.subirPocoAPoco(if (prefs(app).getBoolean("radio_despertador_gradual", true)) 60 else 0)
		}
		db.apuntarEscuchada(e)
		com.xebolax.lector.datos.Actividad.sumarLuego(app, com.xebolax.lector.datos.Actividad.RADIO, elementos = 1)
		if (!e.propia) LectorApp.ambito.launch { RadioBrowser.contarClic(e.uuid) }
		if (volver) c.startActivity(Intent(c, RadioReproductorActivity::class.java).addFlags(if (c is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
	}

	/** Las emisoras por las que se mueven Siguiente y Anterior. */
	fun listaActual(c: Context): List<Emisora> = lista.ifEmpty { RadioBaseDatos.obtener(c).favoritas().map { it.emisora } }

	/** Emisora siguiente o anterior (de la lista desde la que se escuchó, o de las favoritas). Devuelve la nueva. */
	fun pasar(c: Context, delante: Boolean): Emisora? {
		val l = listaActual(c)
		if (l.isEmpty()) return null
		val i = l.indexOfFirst { it.uuid == (actual ?: emisora)?.uuid }
		val destino = if (i < 0) l.first() else l[(i + if (delante) 1 else -1 + l.size) % l.size]
		escuchar(c, destino, abrirPantalla = false)
		return destino
	}

	// --- Favoritas --------------------------------------------------------------------------

	/** Añade o quita de favoritas. Devuelve true si ahora es favorita. */
	fun alternarFavorita(c: Context, e: Emisora): Boolean {
		val db = RadioBaseDatos.obtener(c)
		return if (db.esFavorita(e.uuid)) {
			db.quitarFavorita(e.uuid)
			false
		} else {
			db.anadirFavorita(e)
			true
		}
	}

	/** Pone al día los datos de las favoritas e historial (votos, dirección) como mucho una vez al día. */
	suspend fun ponerAlDia(c: Context, forzar: Boolean = false): Int {
		val p = prefs(c)
		if (!forzar && System.currentTimeMillis() - p.getLong("radio_puesta_al_dia", 0) < 24 * 3600_000L) return 0
		val db = RadioBaseDatos.obtener(c)
		val uuids = (db.favoritas().map { it.emisora } + db.historial().map { it.emisora }).filter { !it.propia }.map { it.uuid }.distinct()
		val nuevas = RadioBrowser.porUuid(uuids)
		db.actualizarDatos(nuevas)
		p.edit().putLong("radio_puesta_al_dia", System.currentTimeMillis()).apply()
		return nuevas.size
	}

	// --- Compartir, canciones y accesos directos -----------------------------------------------

	fun textoCompartir(e: Emisora): String = listOf(e.nombre, e.web.ifBlank { e.url }).filter { it.isNotBlank() }.joinToString("\n")

	fun compartir(c: Context, texto: String) {
		c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), c.getString(R.string.compartir)))
	}

	/** Busca la canción en internet (el buscador del móvil o el navegador). */
	fun buscarCancion(c: Context, titulo: String) {
		val buscar = Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, titulo)
		val ok = runCatching { c.startActivity(buscar) }.isSuccess
		if (!ok) runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(titulo)))) }
	}

	/** Acceso directo en la pantalla de inicio que empieza a escuchar la emisora. */
	fun accesoDirecto(c: Context, e: Emisora): Boolean {
		if (!ShortcutManagerCompat.isRequestPinShortcutSupported(c)) return false
		val i = Intent(c, RadioActivity::class.java).setAction(RadioActivity.ACCION_ESCUCHAR)
			.putExtra(RadioActivity.EXTRA_EMISORA, e.json().toString())
			.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		val info = ShortcutInfoCompat.Builder(c, "radio_" + e.uuid.hashCode())
			.setShortLabel(e.nombre.take(24))
			.setLongLabel(c.getString(R.string.radio_escuchar_x, e.nombre).take(48))
			.setIcon(IconCompat.createWithResource(c, R.mipmap.ic_launcher))
			.setIntent(i)
			.build()
		return ShortcutManagerCompat.requestPinShortcut(c, info, null)
	}

	// --- Emisoras propias y listas M3U/PLS -------------------------------------------------------

	/**
	 * La dirección del sonido de una emisora añadida a mano: si es una lista
	 * (.pls, .m3u), la primera dirección que trae.
	 */
	suspend fun resolver(url: String): String = withContext(Dispatchers.IO) {
		val limpia = url.trim()
		// Por su contenido (listas .pls, .m3u, .asx, .xspf, aunque no lo digan en la extensión)
		runCatching { Flujos.sondear(limpia).takeIf { !it.web }?.url }.getOrNull() ?: limpia
	}

	/** Las favoritas como lista M3U (la entienden casi todos los reproductores y apps de radio). */
	fun exportarM3u(c: Context): String = buildString {
		append("#EXTM3U\n")
		for (f in RadioBaseDatos.obtener(c).favoritas()) {
			append("#EXTINF:-1")
			if (f.carpeta.isNotBlank()) append(" group-title=\"").append(f.carpeta.replace("\"", "'")).append('"')
			append(',').append(f.emisora.nombre.replace('\n', ' ')).append('\n')
			append(f.emisora.url).append('\n')
		}
	}

	/** Lee una lista M3U o PLS y devuelve sus emisoras (como propias). */
	fun leerLista(texto: String): List<Pair<Emisora, String>> {
		val r = mutableListOf<Pair<Emisora, String>>()
		if (texto.contains("[playlist]", ignoreCase = true)) {
			val claves = texto.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim().lowercase() to it[1].trim() } }.toMap()
			for ((k, v) in claves) {
				if (!k.startsWith("file") || !v.startsWith("http", true)) continue
				val n = k.removePrefix("file")
				r += Emisora.propia(claves["title$n"].orEmpty(), v) to ""
			}
			return r
		}
		var nombre = ""
		var grupo = ""
		for (linea in texto.lines().map { it.trim() }) {
			when {
				linea.startsWith("#EXTINF", true) -> {
					nombre = linea.substringAfter(',', "").trim()
					grupo = Regex("""group-title="([^"]*)"""").find(linea)?.groupValues?.get(1).orEmpty()
				}
				linea.startsWith("http", true) -> {
					r += Emisora.propia(nombre, linea) to grupo
					nombre = ""
					grupo = ""
				}
			}
		}
		return r
	}

	// --- Grabaciones ------------------------------------------------------------------------------

	/** Carpeta de las grabaciones (en la memoria de la app: se ven con un cable USB en Android/data). */
	fun carpetaGrabaciones(c: Context): File =
		File(c.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(c.filesDir, "musica"), "Radio").apply { mkdirs() }
}
