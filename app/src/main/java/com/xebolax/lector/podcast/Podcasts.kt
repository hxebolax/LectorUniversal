package com.xebolax.lector.podcast

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.xebolax.lector.R
import com.xebolax.lector.audio.ControlAudio
import com.xebolax.lector.modelo.MarcaAudio
import com.xebolax.lector.rss.RssLector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLEncoder

/**
 * El cliente de podcasts: suscripciones, actualización, búsqueda (iTunes) y
 * los más escuchados en España, descargas, cola, reproducción con el
 * reproductor de la app (velocidad propia, saltar intro y final, capítulos,
 * seguir la cola), transcripciones y OPML.
 */
object Podcasts {

	fun prefs(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)

	/** Categorías de Apple Podcasts (para los más escuchados). */
	val CATEGORIAS = listOf(
		0 to R.string.pod_cat_todas, 1489 to R.string.pod_cat_noticias, 1324 to R.string.pod_cat_sociedad, 1487 to R.string.pod_cat_historia,
		1303 to R.string.pod_cat_humor, 1488 to R.string.pod_cat_crimen, 1533 to R.string.pod_cat_ciencia, 1318 to R.string.pod_cat_tecnologia,
		1304 to R.string.pod_cat_educacion, 1321 to R.string.pod_cat_negocios, 1512 to R.string.pod_cat_salud, 1545 to R.string.pod_cat_deportes,
		1301 to R.string.pod_cat_arte, 1483 to R.string.pod_cat_ficcion, 1310 to R.string.pod_cat_musica, 1309 to R.string.pod_cat_cine,
		1305 to R.string.pod_cat_infantil, 1314 to R.string.pod_cat_religion, 1502 to R.string.pod_cat_ocio,
	)

	class Sugerencia(val id: Long, val titulo: String, val autor: String, val url: String, val descripcion: String, val episodios: Int, val genero: String)

	// --- Descubrir -----------------------------------------------------------------------

	/** Busca podcasts en Apple Podcasts (sin cuenta). */
	suspend fun buscar(texto: String): List<Sugerencia> = withContext(Dispatchers.IO) {
		val pais = pais()
		val url = "https://itunes.apple.com/search?media=podcast&limit=50&country=$pais&term=" + URLEncoder.encode(texto, "UTF-8")
		val r = JSONObject(RssLector.descargarTexto(url)).optJSONArray("results") ?: JSONArray()
		(0 until r.length()).mapNotNull { i ->
			val o = r.optJSONObject(i) ?: return@mapNotNull null
			val feed = o.optString("feedUrl")
			if (feed.isBlank()) return@mapNotNull null
			Sugerencia(o.optLong("collectionId"), o.optString("collectionName"), o.optString("artistName"), feed, "", o.optInt("trackCount"), o.optString("primaryGenreName"))
		}
	}

	private fun pais() = java.util.Locale.getDefault().country.lowercase().ifBlank { "es" }.let { if (it.length == 2) it else "es" }

	/** Los más escuchados (del país del móvil) de una categoría (0 = todas). La dirección del RSS se busca al suscribirse. */
	suspend fun masEscuchados(categoria: Int): List<Sugerencia> = withContext(Dispatchers.IO) {
		val genero = if (categoria > 0) "genre=$categoria/" else ""
		val url = "https://itunes.apple.com/${pais()}/rss/toppodcasts/limit=100/${genero}explicit=true/json"
		val feed = JSONObject(RssLector.descargarTexto(url)).optJSONObject("feed") ?: return@withContext emptyList()
		// Con un solo resultado "entry" es un objeto, no una lista
		val entradas = feed.optJSONArray("entry") ?: feed.optJSONObject("entry")?.let { JSONArray().put(it) } ?: JSONArray()
		(0 until entradas.length()).mapNotNull { i ->
			val e = entradas.optJSONObject(i) ?: return@mapNotNull null
			fun etiqueta(k: String) = e.optJSONObject(k)?.optString("label").orEmpty()
			val id = e.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id")?.toLongOrNull() ?: return@mapNotNull null
			Sugerencia(id, etiqueta("im:name"), etiqueta("im:artist"), "", etiqueta("summary"), 0,
				e.optJSONObject("category")?.optJSONObject("attributes")?.optString("label").orEmpty())
		}
	}

	/** Dirección del RSS de un podcast de Apple (por su id). */
	suspend fun direccionDe(idApple: Long): String? = withContext(Dispatchers.IO) {
		val r = JSONObject(RssLector.descargarTexto("https://itunes.apple.com/lookup?id=$idApple&country=${pais()}")).optJSONArray("results")
		r?.optJSONObject(0)?.optString("feedUrl")?.takeIf { it.isNotBlank() }
	}

	// --- Suscribirse y actualizar ------------------------------------------------------------

	/** Se suscribe y trae los episodios (los antiguos quedan como escuchados salvo los 3 últimos). */
	suspend fun suscribir(c: Context, url: String, titulo: String = "", autor: String = "", carpeta: String = "", usuario: String = "", clave: String = ""): Long {
		val db = PodcastsBaseDatos.obtener(c)
		db.podcastPorUrl(url)?.let { return it.id }
		// Con usuario y contraseña, primero se comprueba que el servidor los acepta
		if (usuario.isNotBlank()) withContext(Dispatchers.IO) { PodcastLector.descargar(url, usuario = usuario, clave = clave) }
		val id = db.anadirPodcast(url, titulo.ifBlank { url }, autor, carpeta = carpeta)
		if (usuario.isNotBlank()) db.credenciales(id, usuario, clave)
		db.podcast(id)?.let { runCatching { actualizar(c, it) } }
		return id
	}

	class Resultado(val nuevos: Int, val errores: Int, val avisos: List<Pair<Podcast, Long>>)

	/** ¿Pasa el episodio el filtro de su podcast? (alguna palabra de «incluir», ninguna de «excluir», y los minutos mínimos) */
	fun pasaFiltro(p: Podcast, e: PodcastLector.Elemento): Boolean {
		fun palabras(t: String) = t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
		val texto = e.titulo
		val incluir = palabras(p.filtroIncluir)
		if (incluir.isNotEmpty() && incluir.none { texto.contains(it, ignoreCase = true) }) return false
		if (palabras(p.filtroExcluir).any { texto.contains(it, ignoreCase = true) }) return false
		if (p.duracionMin > 0 && e.duracion > 0 && e.duracion < p.duracionMin * 60_000L) return false
		return true
	}

	/** Actualiza un podcast. Devuelve los ids de los episodios nuevos. */
	suspend fun actualizar(c: Context, p: Podcast): List<Long> = withContext(Dispatchers.IO) {
		val db = PodcastsBaseDatos.obtener(c)
		try {
			val r = PodcastLector.descargar(p.url, p.etag, p.modificado, p.usuario, p.clave)
			val canal = r.canal
			if (canal == null) {
				db.guardarActualizacion(p.id, r.etag, r.modificado, null, "")
				return@withContext emptyList()
			}
			val primeraVez = p.ultima == 0L
			// Los que no pasan el filtro del podcast (palabras, duración) entran sin escuchar: ni Novedades, ni cola, ni descarga
			val (pasan, noPasan) = canal.elementos.sortedByDescending { it.fecha }.partition { pasaFiltro(p, it) }
			if (noPasan.isNotEmpty()) db.anadirEpisodios(p.id, noPasan, 0)
			val ordenados = pasan
			val nuevos = if (primeraVez) {
				// La primera vez: los 3 más recientes a Novedades, el resto sin escuchar (y no molestan)
				db.anadirEpisodios(p.id, ordenados.drop(3), 0)
				db.anadirEpisodios(p.id, ordenados.take(3), -1)
			} else {
				val destino = when (p.nuevos.takeIf { it > 0 } ?: (prefs(c).getString("pod_nuevos", "1")?.toIntOrNull() ?: 1)) {
					2 -> 0
					3 -> 0
					else -> -1
				}
				db.anadirEpisodios(p.id, ordenados, destino).also { ids ->
					if ((p.nuevos.takeIf { it > 0 } ?: prefs(c).getString("pod_nuevos", "1")?.toIntOrNull() ?: 1) == 2) db.anadirACola(ids.reversed())
				}
			}
			db.guardarActualizacion(p.id, r.etag, r.modificado, canal, "")
			// Descarga automática de los nuevos
			val auto = when (p.autoDescargar) {
				1 -> true
				2 -> false
				else -> prefs(c).getBoolean("pod_auto_descargar", false)
			}
			if (auto && !primeraVez) nuevos.forEach { id -> db.episodio(id)?.let { descargar(c, it) } }
			nuevos
		} catch (e: Exception) {
			db.guardarActualizacion(p.id, null, null, null, e.message ?: e.javaClass.simpleName)
			throw e
		}
	}

	suspend fun actualizarTodos(c: Context, avisar: Boolean = true): Resultado = coroutineScope {
		val db = PodcastsBaseDatos.obtener(c)
		var errores = 0
		// Suscripciones y lo escuchado en otros dispositivos (gpodder.net, Nextcloud)
		if (PodcastsSincronizacion.configurado(c)) {
			runCatching { PodcastsSincronizacion.sincronizar(c) }
				.onSuccess { prefs(c).edit().putString("pod_sinc_error", "").apply() }
				.onFailure { errores++; prefs(c).edit().putString("pod_sinc_error", it.message ?: it.javaClass.simpleName).apply() }
		}
		val lista = db.podcasts().filter { !it.pausado }
		val limite = Semaphore(4)
		var nuevos = 0
		val avisos = ArrayList<Pair<Podcast, Long>>()
		lista.map { p ->
			async(Dispatchers.IO) {
				limite.withPermit {
					val r = runCatching { actualizar(c, p) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
					val ids = r.getOrDefault(emptyList())
					// Los contadores se tocan desde varios podcasts a la vez: dentro del bloqueo
					synchronized(avisos) {
						if (r.isFailure) errores++
						nuevos += ids.size
						if (p.avisar) ids.forEach { avisos += p to it }
					}
				}
			}
		}.awaitAll()
		// Comprobar descargas y borrar los antiguos toca disco y base de datos: fuera del hilo principal
		withContext(Dispatchers.IO) {
			comprobarDescargas(c)
			conservarUltimos(c)
		}
		if (avisar && avisos.isNotEmpty()) notificar(c, avisos)
		prefs(c).edit().putLong("pod_ultima", System.currentTimeMillis()).apply()
		Resultado(nuevos, errores, avisos)
	}

	private fun notificar(c: Context, avisos: List<Pair<Podcast, Long>>) {
		val gestor = c.getSystemService(NotificationManager::class.java) ?: return
		if (Build.VERSION.SDK_INT >= 26) gestor.createNotificationChannel(NotificationChannel("podcasts", c.getString(R.string.podcasts), NotificationManager.IMPORTANCE_DEFAULT))
		val db = PodcastsBaseDatos.obtener(c)
		val lineas = avisos.mapNotNull { (p, id) -> db.episodio(id)?.let { "${p.titulo}: ${it.titulo}" } }
		if (lineas.isEmpty()) return
		val abrir = PendingIntent.getActivity(c, 8, Intent(c, PodcastActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
		val estilo = NotificationCompat.InboxStyle()
		lineas.take(6).forEach { estilo.addLine(it) }
		val n = NotificationCompat.Builder(c, "podcasts")
			.setSmallIcon(R.drawable.ic_voz)
			.setContentTitle(c.resources.getQuantityString(R.plurals.pod_nuevos, lineas.size, lineas.size))
			.setContentText(lineas.first())
			.setStyle(estilo)
			.setContentIntent(abrir)
			.setAutoCancel(true)
			.build()
		runCatching { gestor.notify(72, n) }
	}

	// --- Descargas ---------------------------------------------------------------------------

	const val ESPERANDO_WIFI = -2

	private fun carpeta(c: Context) = (c.getExternalFilesDir("podcasts") ?: File(c.filesDir, "podcasts")).apply { mkdirs() }

	/** ¿Una descarga se quedaría esperando al Wi-Fi? (solo Wi-Fi activado y ahora con datos móviles) */
	fun esperaWifi(c: Context): Boolean = prefs(c).getBoolean("pod_solo_wifi", true) &&
		(c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered

	/** datosMoviles: el usuario ha pedido descargar ya aunque esté con datos móviles. */
	fun descargar(c: Context, e: Episodio, datosMoviles: Boolean = false) {
		if (e.descargado || e.descarga != 0L) return
		val extension = e.audio.substringBefore('?').substringAfterLast('.', "mp3").take(4).filter { it.isLetterOrDigit() }.ifBlank { "mp3" }
		val destino = File(carpeta(c), "${e.id}.$extension")
		destino.delete()
		val peticion = DownloadManager.Request(Uri.parse(e.audio))
			.setTitle(e.titulo)
			.setDescription(e.nombrePodcast.ifBlank { c.getString(R.string.podcasts) })
			.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
			.setDestinationUri(Uri.fromFile(destino))
			.setAllowedOverMetered(datosMoviles || !prefs(c).getBoolean("pod_solo_wifi", true))
			.setAllowedOverRoaming(false)
			.addRequestHeader("User-Agent", "LectorUniversal/" + com.xebolax.lector.BuildConfig.VERSION_NAME)
		PodcastsBaseDatos.obtener(c).podcast(e.podcast)?.takeIf { it.usuario.isNotBlank() }?.let { p ->
			peticion.addRequestHeader("Authorization", "Basic " + android.util.Base64.encodeToString("${p.usuario}:${p.clave}".toByteArray(), android.util.Base64.NO_WRAP))
		}
		val id = (c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(peticion)
		PodcastsBaseDatos.obtener(c).guardarDescarga(e.id, "", id)
	}

	/** Mira cómo van las descargas: las terminadas pasan a "descargado". Devuelve los títulos terminados ahora. */
	/** Lo que ha pasado con las descargas: las terminadas y las que han fallado ya tres veces. */
	class EstadoDescargas(val terminadas: List<String>, val fallidas: List<String>)

	fun comprobarDescargas(c: Context): EstadoDescargas {
		val db = PodcastsBaseDatos.obtener(c)
		val gestor = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
		val terminados = ArrayList<String>()
		val fallidas = ArrayList<String>()
		for (e in db.descargados().filter { it.descarga != 0L }) {
			gestor.query(DownloadManager.Query().setFilterById(e.descarga))?.use { cur ->
				if (!cur.moveToFirst()) {
					db.guardarDescarga(e.id, "", 0)
					return@use
				}
				when (cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
					DownloadManager.STATUS_SUCCESSFUL -> {
						val local = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)).orEmpty()
						db.guardarDescarga(e.id, Uri.parse(local).path.orEmpty(), 0)
						db.fallosDescarga(e.id, 0)
						terminados += e.titulo
					}
					// Falló: se reintenta (hasta 3 veces); después se dice y el episodio queda como «descarga fallida»
					DownloadManager.STATUS_FAILED -> {
						db.guardarDescarga(e.id, "", 0)
						val fallos = e.fallos + 1
						db.fallosDescarga(e.id, fallos)
						if (fallos < 3) db.episodio(e.id)?.let { descargar(c, it) } else fallidas += e.titulo
					}
				}
			}
		}
		return EstadoDescargas(terminados, fallidas)
	}

	/** Progreso de una descarga en marcha (0-100), ESPERANDO_WIFI o -1. */
	fun progresoDescarga(c: Context, e: Episodio): Int {
		if (e.descarga == 0L) return -1
		val gestor = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
		return gestor.query(DownloadManager.Query().setFilterById(e.descarga))?.use { cur ->
			if (!cur.moveToFirst()) return@use -1
			val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
			val bajado = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
			// Sin empezar (Android la deja pendiente o en pausa) mientras no haya Wi-Fi
			val estado = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
			if (bajado == 0L && (estado == DownloadManager.STATUS_PENDING || estado == DownloadManager.STATUS_PAUSED) && esperaWifi(c)) return@use ESPERANDO_WIFI
			if (total > 0) (bajado * 100 / total).toInt() else 0
		} ?: -1
	}

	fun borrarDescarga(c: Context, e: Episodio) {
		if (e.descarga != 0L) runCatching { (c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(e.descarga) }
		if (e.archivo.isNotBlank()) File(e.archivo).delete()
		PodcastsBaseDatos.obtener(c).guardarDescarga(e.id, "", 0)
	}

	/** Borra las descargas de los episodios ya escuchados (salvo favoritos). Devuelve cuántas. */
	fun borrarEscuchados(c: Context): Int {
		val lista = PodcastsBaseDatos.obtener(c).descargados().filter { it.terminado && !it.favorito && it.descargado }
		lista.forEach { borrarDescarga(c, it) }
		return lista.size
	}

	// --- Escuchar --------------------------------------------------------------------------

	/**
	 * Prepara el episodio en el reproductor de la app: desde donde se quedó,
	 * con la velocidad y los saltos del podcast, y al terminar lo marca como
	 * escuchado y sigue con la cola.
	 */
	suspend fun preparar(c: Context, e: Episodio): ControlAudio.Externo {
		val db = PodcastsBaseDatos.obtener(c)
		val p = db.podcast(e.podcast)
		val capitulos = if (e.capitulos.isNotBlank()) capitulos(e.capitulos) else emptyList()
		val umbral = (prefs(c).getString("pod_marcar_escuchado", "30")?.toLongOrNull() ?: 30L) * 1000
		val fuente = if (e.descargado && File(e.archivo).exists()) Uri.fromFile(File(e.archivo)).toString() else e.audio
		return ControlAudio.Externo(
			id = -1_000_000_000L - e.id,
			titulo = e.titulo,
			autor = e.nombrePodcast.ifBlank { p?.titulo.orEmpty() },
			url = fuente,
			posicionMs = if (e.terminado) 0 else e.posicion,
			capitulos = capitulos,
			velocidad = p?.velocidad?.takeIf { it > 0 } ?: prefs(c).getString("pod_velocidad_lista", "0")?.toIntOrNull()?.takeIf { it > 0 },
			saltarInicio = p?.saltarInicio ?: 0,
			saltarFinal = p?.saltarFinal ?: 0,
			alGuardar = { ms, dur ->
				db.guardarPosicion(e.id, ms, dur)
				// Casi al final: ya cuenta como escuchado
				if (dur > 0 && ms > 0 && ms >= dur - umbral && umbral > 0) db.marcarEscuchados(listOf(e.id), true)
			},
			alTerminar = {
				com.xebolax.lector.datos.Actividad.sumarLuego(c, com.xebolax.lector.datos.Actividad.PODCASTS, elementos = 1)
				alTerminar(c, e)
			},
			alCambiarVelocidad = { v -> if (p != null) db.guardarVelocidad(p.id, v) },
		)
	}

	/** Da el episodio por escuchado y pasa al siguiente de la cola (desde el reproductor). Devuelve false si no hay siguiente. */
	suspend fun terminarYSeguir(c: Context, e: Episodio): Boolean {
		val db = PodcastsBaseDatos.obtener(c)
		val siguiente = db.cola().firstOrNull { it.id != e.id } ?: return false
		db.marcarEscuchados(listOf(e.id), true)
		val ext = preparar(c, siguiente)
		ControlAudio.cargarExterno(ext)
		ControlAudio.reproducir()
		buscarCapitulosIncrustados(c.applicationContext, ext)
		return true
	}

	private fun alTerminar(c: Context, e: Episodio) {
		val db = PodcastsBaseDatos.obtener(c)
		val siguiente = db.cola().firstOrNull { it.id != e.id }
		db.marcarEscuchados(listOf(e.id), true)
		if (prefs(c).getBoolean("pod_borrar_al_terminar", false)) db.episodio(e.id)?.takeIf { !it.favorito }?.let { borrarDescarga(c, it) }
		// Temporizador «al final del episodio» o «tras N episodios»: aquí se para
		if (ControlAudio.pararTrasEpisodio()) return
		if (siguiente != null && prefs(c).getBoolean("pod_seguir_cola", true)) {
			kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
				runCatching {
					val ext = preparar(c, siguiente)
					ControlAudio.cargarExterno(ext)
					ControlAudio.reproducir()
					buscarCapitulosIncrustados(c.applicationContext, ext)
				}
			}
		}
	}

	/** Capítulos de Podcasting 2.0 (JSON). */
	private suspend fun capitulos(url: String): List<MarcaAudio> = withContext(Dispatchers.IO) {
		runCatching {
			val a = JSONObject(RssLector.descargarTexto(url)).optJSONArray("chapters") ?: return@runCatching emptyList()
			(0 until a.length()).mapNotNull { i ->
				val o = a.optJSONObject(i) ?: return@mapNotNull null
				if (o.has("toc") && !o.optBoolean("toc", true)) return@mapNotNull null
				MarcaAudio(o.optString("title"), 1, 0, (o.optDouble("startTime", 0.0) * 1000).toLong())
			}.sortedBy { it.ms }
		}.getOrDefault(emptyList())
	}

	/**
	 * Capítulos incrustados en el audio (ID3 en MP3, Nero o QuickTime en M4A),
	 * para episodios sin capítulos de Podcasting 2.0. Se leen en segundo plano
	 * (por partes si el episodio no está descargado) y se añaden al llegar.
	 */
	private fun buscarCapitulosIncrustados(c: Context, ext: ControlAudio.Externo) {
		if (ext.capitulos.isNotEmpty()) return
		kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
			val lista = runCatching { com.xebolax.lector.formatos.CapitulosAudio.leer(c, Uri.parse(ext.url)) }.getOrDefault(emptyList())
			if (lista.size < 2) return@launch
			val marcas = lista.map { (titulo, ms) -> MarcaAudio(titulo, 1, 0, ms) }
			withContext(Dispatchers.Main) { ControlAudio.ponerCapitulos(ext.id, marcas) }
		}
	}

	/** Abre la pantalla del reproductor con un episodio. */
	suspend fun escuchar(c: Context, e: Episodio) {
		val db = PodcastsBaseDatos.obtener(c)
		// Sale de Novedades al escucharlo
		if (e.nuevo) db.quitarDeNovedades(listOf(e.id))
		val ext = preparar(c, e)
		buscarCapitulosIncrustados(c.applicationContext, ext)
		ControlAudio.externoPendiente = ext
		c.startActivity(Intent(c, com.xebolax.lector.audio.ReproductorActivity::class.java)
			.putExtra(com.xebolax.lector.audio.ReproductorActivity.EXTRA_EXTERNO, true)
			.addFlags(if (c is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
	}

	// --- Textos: notas del episodio y transcripción ------------------------------------------

	private fun escapar(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

	/** Esquema de los enlaces a un momento de un episodio: lectoruniversal-tiempo:<episodio>:<ms>. */
	const val ESQUEMA_TIEMPO = "lectoruniversal-tiempo:"

	private val MARCA_TIEMPO = Regex("""\b(?:(\d{1,2}):)?([0-5]?\d):([0-5]\d)\b""")

	/** Convierte las marcas de tiempo del texto (no de los enlaces ya hechos) en enlaces a ese momento. */
	private fun enlazarTiempos(html: String, episodio: Long, duracion: Long): String {
		val doc = Jsoup.parseBodyFragment(html)
		for (nodo in doc.body().select("*").flatMap { it.textNodes() }) {
			if (generateSequence(nodo.parent()) { it.parent() }.any { it.nodeName() == "a" }) continue
			val texto = nodo.wholeText
			if (!MARCA_TIEMPO.containsMatchIn(texto)) continue
			val nuevo = MARCA_TIEMPO.replace(escapar(texto)) { m ->
				val h = m.groupValues[1].toLongOrNull() ?: 0
				val ms = ((h * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000
				if (duracion > 0 && ms > duracion) m.value else "<a href=\"$ESQUEMA_TIEMPO$episodio:$ms\">${m.value}</a>"
			}
			nodo.before(nuevo)
			nodo.remove()
		}
		return doc.body().html()
	}

	fun htmlNotas(c: Context, e: Episodio): String = buildString {
		append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(escapar(e.titulo)).append("</title></head><body>")
		append("<h1>").append(escapar(e.titulo)).append("</h1>")
		val datos = listOf(e.nombrePodcast, com.xebolax.lector.rss.Rss.fechaLegible(e.fecha),
			if (e.duracion > 0) com.xebolax.lector.ui.tiempoCorto(e.duracion) else "").filter { it.isNotBlank() }.joinToString(" · ")
		if (datos.isNotBlank()) append("<p>").append(escapar(datos)).append("</p>")
		val descripcion = if (e.descripcion.contains('<')) e.descripcion else "<p>" + escapar(e.descripcion).replace("\n", "<br>") + "</p>"
		append(runCatching { enlazarTiempos(descripcion, e.id, e.duracion) }.getOrDefault(descripcion))
		if (e.enlace.isNotBlank()) append("<p><a href=\"").append(escapar(e.enlace)).append("\">").append(escapar(c.getString(R.string.pod_enlace_episodio))).append("</a></p>")
		append("</body></html>")
	}

	/** Notas de un episodio de un podcast al que no se está suscrito (desde su ficha). */
	fun htmlNotas(c: Context, podcast: String, e: PodcastLector.Elemento): String = buildString {
		append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(escapar(e.titulo)).append("</title></head><body>")
		append("<h1>").append(escapar(e.titulo)).append("</h1>")
		val datos = listOf(podcast, com.xebolax.lector.rss.Rss.fechaLegible(e.fecha),
			if (e.duracion > 0) com.xebolax.lector.ui.tiempoCorto(e.duracion) else "").filter { it.isNotBlank() }.joinToString(" · ")
		if (datos.isNotBlank()) append("<p>").append(escapar(datos)).append("</p>")
		append(if (e.descripcion.contains('<')) e.descripcion else "<p>" + escapar(e.descripcion).replace("\n", "<br>") + "</p>")
		if (e.enlace.isNotBlank()) append("<p><a href=\"").append(escapar(e.enlace)).append("\">").append(escapar(c.getString(R.string.pod_enlace_episodio))).append("</a></p>")
		append("</body></html>")
	}

	/** Capítulos de un episodio sin reproducirlo: los de Podcasting 2.0 o los que van dentro del audio. */
	suspend fun capitulosDe(c: Context, e: Episodio): List<Pair<String, Long>> = withContext(Dispatchers.IO) {
		if (e.capitulos.isNotBlank()) capitulos(e.capitulos).map { it.titulo to it.ms }.takeIf { it.isNotEmpty() }?.let { return@withContext it }
		val fuente = if (e.descargado && File(e.archivo).exists()) Uri.fromFile(File(e.archivo)) else Uri.parse(e.audio)
		runCatching { com.xebolax.lector.formatos.CapitulosAudio.leer(c, fuente) }.getOrDefault(emptyList())
	}

	/** El podcast al que ya estás suscrito para esa sugerencia (por su dirección o, si no la trae, por su nombre). */
	fun suscritoA(c: Context, s: Sugerencia): Podcast? {
		val db = PodcastsBaseDatos.obtener(c)
		if (s.url.isNotBlank()) db.podcastPorUrl(s.url)?.let { return it }
		return db.podcasts().firstOrNull { it.titulo.trim().equals(s.titulo.trim(), ignoreCase = true) }
	}

	/** Un episodio encontrado al buscar episodios (de cualquier podcast). */
	class EpisodioEncontrado(val elemento: PodcastLector.Elemento, val podcast: String, val urlPodcast: String, val idPodcast: Long)

	/** Busca episodios de cualquier podcast (Apple Podcasts), aunque no estés suscrito. */
	suspend fun buscarEpisodios(texto: String): List<EpisodioEncontrado> = withContext(Dispatchers.IO) {
		val url = "https://itunes.apple.com/search?media=podcast&entity=podcastEpisode&limit=50&country=${pais()}&term=" + URLEncoder.encode(texto, "UTF-8")
		val r = JSONObject(RssLector.descargarTexto(url)).optJSONArray("results") ?: JSONArray()
		(0 until r.length()).mapNotNull { i ->
			val o = r.optJSONObject(i) ?: return@mapNotNull null
			val audio = o.optString("episodeUrl").ifBlank { return@mapNotNull null }
			val e = PodcastLector.Elemento(
				guid = o.optString("episodeGuid").ifBlank { audio },
				titulo = o.optString("trackName"),
				descripcion = o.optString("description").ifBlank { o.optString("shortDescription") },
				fecha = RssLector.fecha(o.optString("releaseDate")),
				audio = audio,
				tipo = o.optString("episodeContentType"),
				tamano = 0,
				duracion = o.optLong("trackTimeMillis"),
				enlace = o.optString("trackViewUrl"),
				temporada = 0, numero = 0, capitulos = "", transcripcion = "", tipoTranscripcion = "",
			)
			EpisodioEncontrado(e, o.optString("collectionName"), o.optString("feedUrl"), o.optLong("collectionId"))
		}
	}

	/**
	 * Conservar como mucho N episodios descargados por podcast (Ajustes,
	 * Podcasts): se borran las descargas más antiguas, salvo favoritos y
	 * los que están en la cola.
	 */
	fun conservarUltimos(c: Context) {
		val n = prefs(c).getString("pod_conservar", "0")?.toIntOrNull() ?: 0
		if (n <= 0) return
		val db = PodcastsBaseDatos.obtener(c)
		db.descargados().filter { it.descargado }.groupBy { it.podcast }.values.forEach { lista ->
			lista.sortedByDescending { it.fecha }.drop(n).filter { !it.favorito && !it.enCola }.forEach { borrarDescarga(c, it) }
		}
	}

	/** Escucha un episodio sin suscribirse (desde la ficha de un podcast): no se guarda nada. */
	suspend fun escucharSinSuscribir(c: Context, podcast: String, e: PodcastLector.Elemento) {
		val ext = ControlAudio.Externo(
			id = -3_000_000_000L - (e.audio.hashCode().toLong() and 0x7fffffff),
			titulo = e.titulo,
			autor = podcast,
			url = e.audio,
			posicionMs = 0,
			capitulos = if (e.capitulos.isNotBlank()) capitulos(e.capitulos) else emptyList(),
			alGuardar = { _, _ -> },
		)
		buscarCapitulosIncrustados(c.applicationContext, ext)
		ControlAudio.externoPendiente = ext
		c.startActivity(Intent(c, com.xebolax.lector.audio.ReproductorActivity::class.java)
			.putExtra(com.xebolax.lector.audio.ReproductorActivity.EXTRA_EXTERNO, true)
			.addFlags(if (c is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
	}

	/** La transcripción como texto (de VTT, SRT, JSON o HTML), con la hora de cada parte. */
	/** Transcripción como HTML, y el momento (ms) en que empieza cada párrafo (el primero es el bloque 1, tras el título). */
	class Transcripcion(val html: String, val tiempos: List<Long>) {
		/** El bloque del párrafo que suena en ese momento. */
		fun bloqueEn(ms: Long): Int = 1 + (tiempos.indexOfLast { it in 0..ms }).coerceAtLeast(0)
	}

	suspend fun htmlTranscripcion(c: Context, e: Episodio): String = transcripcion(c, e).html

	/** De un episodio suscrito: cada párrafo empieza con su hora, que es un enlace a ese momento del episodio. */
	suspend fun transcripcion(c: Context, e: Episodio): Transcripcion = transcripcion(c, e.titulo, e.transcripcion, e.tipoTranscripcion, e.id)

	suspend fun htmlTranscripcion(c: Context, tituloEpisodio: String, url: String, tipo: String): String = transcripcion(c, tituloEpisodio, url, tipo, 0).html

	/** «01:02:03», «02:03» o «02:03.500» en milisegundos (-1 si no es una hora). */
	private fun aMs(h: String): Long {
		val p = h.trim().substringBefore('.').substringBefore(',').split(':').map { it.toLongOrNull() ?: return -1 }
		return when (p.size) {
			3 -> (p[0] * 3600 + p[1] * 60 + p[2]) * 1000
			2 -> (p[0] * 60 + p[1]) * 1000
			else -> -1
		}
	}

	private suspend fun transcripcion(c: Context, tituloEpisodio: String, url: String, tipo: String, episodio: Long): Transcripcion = withContext(Dispatchers.IO) {
		val e = object {
			val titulo = tituloEpisodio
			val tipoTranscripcion = tipo
		}
		val bruto = RssLector.descargarTexto(url)
		// (hora, quién habla, texto)
		val partes: List<Triple<String, String, String>> = when {
			e.tipoTranscripcion.contains("json") || bruto.trimStart().startsWith("{") -> {
				val segmentos = JSONObject(bruto).optJSONArray("segments") ?: JSONArray()
				(0 until segmentos.length()).mapNotNull { i ->
					val s = segmentos.optJSONObject(i) ?: return@mapNotNull null
					val ms = (s.optDouble("startTime", 0.0) * 1000).toLong()
					Triple(String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60), s.optString("speaker"), s.optString("body"))
				}
			}
			e.tipoTranscripcion.contains("html") || bruto.contains("<p") -> listOf(Triple("", "", Jsoup.parse(bruto).text()))
			else -> {
				// VTT o SRT: bloques separados por líneas en blanco; la línea con "-->" da la hora
				// y en VTT <v Nombre> dice quién habla
				val voz = Regex("<v(?:\\.[^ >]*)?\\s+([^>]+)>")
				bruto.replace("\r", "").split(Regex("\n\\s*\n")).mapNotNull { bloque ->
					val lineas = bloque.lines().filter { it.isNotBlank() && it.trim() != "WEBVTT" }
					val tiempo = lineas.firstOrNull { it.contains("-->") } ?: return@mapNotNull null
					val texto = lineas.dropWhile { !it.contains("-->") }.drop(1)
					val quien = texto.firstNotNullOfOrNull { voz.find(it)?.groupValues?.get(1)?.trim() }.orEmpty()
					val limpio = texto.joinToString(" ") { Jsoup.parse(it).text() }
					if (limpio.isBlank()) null else Triple(tiempo.substringBefore("-->").trim().substringBefore('.').substringBefore(','), quien, limpio)
				}
			}
		}
		// Se juntan las frases en párrafos (de un minuto más o menos); cada cambio de voz empieza uno nuevo
		val tiempos = mutableListOf<Long>()
		val html = buildString {
			append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(escapar(e.titulo)).append("</title></head><body>")
			append("<h1>").append(escapar(c.getString(R.string.pod_transcripcion_de, e.titulo))).append("</h1>")
			var actual = StringBuilder()
			var hora = ""
			var voz = ""
			fun cerrar() {
				if (actual.isBlank()) return
				val ms = aMs(hora)
				tiempos += ms
				// La hora es un enlace a ese momento del episodio (si es de un podcast suscrito)
				append("<p>").append(when {
					hora.isBlank() -> ""
					episodio > 0 && ms >= 0 -> "<a href=\"$ESQUEMA_TIEMPO$episodio:$ms\">${escapar(com.xebolax.lector.ui.tiempoCorto(ms))}</a> "
					else -> "[${escapar(hora)}] "
				})
				if (voz.isNotBlank()) append("<strong>").append(escapar(voz)).append(":</strong> ")
				append(escapar(actual.toString().trim())).append("</p>")
				actual = StringBuilder()
			}
			for ((h, quien, t) in partes) {
				if (quien.isNotBlank() && quien != voz) {
					cerrar()
					voz = quien
				}
				if (actual.isEmpty()) hora = h
				actual.append(t).append(' ')
				if (actual.length > 600) cerrar()
			}
			cerrar()
			append("</body></html>")
		}
		Transcripcion(html, tiempos)
	}

	/** Abre un HTML (notas o transcripción) en el lector, sin añadirlo a la biblioteca. */
	fun abrirTexto(c: Context, nombre: String, titulo: String, html: String, bloque: Int = -1) {
		val uri = com.xebolax.lector.rss.Rss.archivo(c, nombre, html)
		c.startActivity(Intent(c, com.xebolax.lector.lectura.LectorActivity::class.java)
			.putExtra(com.xebolax.lector.lectura.LectorActivity.EXTRA_URI, uri.toString())
			.putExtra(com.xebolax.lector.lectura.LectorActivity.EXTRA_TITULO, titulo)
			.apply { if (bloque >= 0) putExtra(com.xebolax.lector.lectura.LectorActivity.EXTRA_BLOQUE, bloque) }
			.addFlags(if (c is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
	}

	// --- OPML ------------------------------------------------------------------------------

	fun exportarOpml(c: Context): String {
		fun e(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
		return buildString {
			appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
			appendLine("<opml version=\"2.0\"><head><title>Podcasts de Lector Universal</title></head><body>")
			val db = PodcastsBaseDatos.obtener(c)
			val todos = db.podcasts()
			fun podcast(p: Podcast) = "<outline type=\"rss\" text=\"${e(p.titulo)}\" title=\"${e(p.titulo)}\" xmlUrl=\"${e(p.url)}\" htmlUrl=\"${e(p.sitio)}\"/>"
			// Las carpetas en su orden (también las vacías); después los podcasts sin carpeta
			for (carpeta in db.carpetas()) {
				appendLine("<outline text=\"${e(carpeta)}\" title=\"${e(carpeta)}\">")
				todos.filter { it.carpeta.equals(carpeta, ignoreCase = true) }.forEach { appendLine(podcast(it)) }
				appendLine("</outline>")
			}
			todos.filter { p -> p.carpeta.isBlank() || db.carpetas().none { it.equals(p.carpeta, ignoreCase = true) } }.forEach { appendLine(podcast(it)) }
			appendLine("</body></opml>")
		}
	}

	/**
	 * Importa un OPML (de AntennaPod, Pocket Casts, Spotify...): crea sus
	 * carpetas (en su orden) y devuelve los podcasts nuevos (nombre, dirección,
	 * carpeta).
	 */
	fun leerOpml(c: Context, texto: String): List<Triple<String, String, String>> {
		val db = PodcastsBaseDatos.obtener(c)
		val doc = Jsoup.parse(texto, "", Parser.xmlParser())
		doc.select("body > outline:not([xmlUrl])").forEach { o -> db.crearCarpeta(o.attr("title").ifBlank { o.attr("text") }) }
		return doc.select("outline[xmlUrl]").map {
			val padre = it.parent()?.takeIf { p -> p.tagName() == "outline" && !p.hasAttr("xmlUrl") }
			Triple(it.attr("title").ifBlank { it.attr("text") }, it.attr("xmlUrl").trim(), padre?.attr("title")?.ifBlank { padre.attr("text") }.orEmpty())
		}.filter { it.second.isNotBlank() && db.podcastPorUrl(it.second) == null }
	}
}
