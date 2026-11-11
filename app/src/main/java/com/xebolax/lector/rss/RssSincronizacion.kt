package com.xebolax.lector.rss

import android.content.Context
import android.util.Base64
import com.xebolax.lector.BuildConfig
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xebolax.lector.R
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.conEspera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sincroniza las noticias con un servidor: las suscripciones, los artículos y
 * lo que se ha leído o marcado como favorito, en los dos sentidos.
 *
 * - API de Google Reader: FreshRSS (…/api/greader.php), The Old Reader,
 *   BazQux, y Miniflux si se activa allí.
 * - Miniflux (su API propia, con clave de API).
 * - Nextcloud News.
 *
 * Feedly e Inoreader exigen registrar la app con ellos; se puede pasar sus
 * fuentes con Exportar OPML allí e Importar OPML aquí.
 */
object RssSincronizacion {

	class FuenteRemota(val id: String, val url: String, val titulo: String, val sitio: String, val categoria: String)
	class ArticuloRemoto(val id: String, val fuente: String, val elemento: RssLector.Elemento, val leido: Boolean, val favorito: Boolean)

	interface Servicio {
		fun entrar()
		fun fuentes(): List<FuenteRemota>
		/** Artículos sin leer y favoritos (los recientes). */
		fun articulos(): List<ArticuloRemoto>
		/** Ids de los sin leer y de los favoritos; null si el servidor no los ha dado todos (entonces no se quita nada por faltar). */
		fun idsSinLeer(): Set<String>?
		fun idsFavoritos(): Set<String>?
		fun marcarLeidos(ids: List<String>, leido: Boolean)
		fun marcarFavoritos(ids: List<String>, si: Boolean)
		/** Se suscribe en el servidor; devuelve el id remoto de la fuente. */
		fun suscribir(url: String, titulo: String, categoria: String): String?
		fun borrar(remoto: String)
	}

	private val cerrojo = Mutex()

	fun configurado(c: Context) = Rss.prefs(c).getString("rss_sinc_tipo", "ninguno") != "ninguno" &&
		Rss.prefs(c).getString("rss_sinc_url", "").orEmpty().isNotBlank()

	private fun servicio(c: Context): Servicio? {
		val p = Rss.prefs(c)
		val url = p.getString("rss_sinc_url", "").orEmpty().trim().trimEnd('/')
		val usuario = p.getString("rss_sinc_usuario", "").orEmpty()
		val clave = com.xebolax.lector.datos.Secretos.leer(c, "rss_sinc_clave")
		if (url.isBlank()) return null
		return when (p.getString("rss_sinc_tipo", "ninguno")) {
			"greader" -> GoogleReader(url, usuario, clave)
			"miniflux" -> Miniflux(url, usuario, clave)
			"nextcloud" -> NextcloudNews(url, usuario, clave)
			else -> null
		}
	}

	/**
	 * avisos: los nuevos de las fuentes con «Avisar» (para la notificación).
	 * porSubir: fuentes de este móvil que aún no están en la cuenta y no se han
	 * subido porque no se ha decidido (se pregunta al sincronizar a mano).
	 */
	class Resultado(val fuentes: Int, val nuevos: Int, val avisos: List<Pair<Fuente, RssLector.Elemento>> = emptyList(), val porSubir: Int = 0)

	/** Artículos por página al pedirlos al servidor, y cuántos como mucho de cada lista. */
	private const val PAGINA = 300
	private const val TOPE = 1000

	/** ¿Subir a la cuenta las fuentes de este móvil? "si", "no" o vacío (sin decidir: no se sube nada). */
	private const val SUBIR = "rss_sinc_subir"
	/** Fuentes que no se suben nunca (el servidor las borró o no las reconoce). */
	private const val NO_SUBIR = "rss_sinc_no_subir"

	private fun noSubir(c: Context): Set<Long> =
		Rss.prefs(c).getStringSet(NO_SUBIR, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

	private fun anadirNoSubir(c: Context, ids: Collection<Long>) {
		if (ids.isEmpty()) return
		Rss.prefs(c).edit().putStringSet(NO_SUBIR, (noSubir(c) + ids).map { it.toString() }.toSet()).apply()
	}

	/** Otra cuenta (o ninguna): se olvidan los enlaces con la anterior y lo decidido sobre subir fuentes. */
	fun olvidarCuenta(c: Context) {
		RssBaseDatos.obtener(c).olvidarRemotos()
		Rss.prefs(c).edit().remove(SUBIR).remove(NO_SUBIR).apply()
	}

	/**
	 * La dirección de una fuente sin lo que no la cambia: http o https, «www.»,
	 * mayúsculas del servidor y la barra final (para emparejarla con la del servidor).
	 */
	fun direccionNormal(url: String): String {
		val sin = url.trim().replaceFirst(Regex("(?i)^(https?|feed)://"), "")
		val corte = sin.indexOf('/').takeIf { it >= 0 } ?: sin.length
		return sin.substring(0, corte).lowercase().removePrefix("www.") + sin.substring(corte).trimEnd('/')
	}

	/** Sincroniza todo: primero envía lo cambiado aquí, luego trae fuentes, artículos y estados. */
	suspend fun sincronizar(c: Context): Resultado = cerrojo.withLock {
		withContext(Dispatchers.IO) {
			val s = servicio(c) ?: error("Sin cuenta")
			val db = RssBaseDatos.obtener(c)
			s.entrar()
			// 1. Lo cambiado en el móvil (solo se da por enviado lo que no ha vuelto a cambiar mientras tanto)
			val cambios = db.cambiosPendientes()
			s.marcarLeidos(cambios.filter { it.second }.map { it.first }, true)
			s.marcarLeidos(cambios.filter { !it.second }.map { it.first }, false)
			s.marcarFavoritos(cambios.filter { it.third }.map { it.first }, true)
			s.marcarFavoritos(cambios.filter { !it.third }.map { it.first }, false)
			db.cambiosEnviados(cambios)
			// 2. Fuentes: las del servidor se añaden aquí
			val remotas = s.fuentes()
			val locales = db.fuentes()
			val enlazadas = db.remotosDeFuentes()
			val idLocal = HashMap<String, Long>()
			val emparejadas = HashSet<Long>()
			for (r in remotas) {
				// Primero por su id en el servidor; si no, por la dirección (http o https, www o la barra final dan igual)
				val local = locales.firstOrNull { it.id !in emparejadas && enlazadas[it.id] == r.id }
					?: locales.firstOrNull { it.id !in emparejadas && direccionNormal(it.url) == direccionNormal(r.url) }
					?: db.fuentePorUrl(r.url)
				val id = local?.id ?: db.anadirFuente(r.url, r.titulo, r.sitio, r.categoria)
				// La carpeta del servidor solo la primera vez que se enlaza: después manda lo que se decida en el móvil
				// (si no, «Borrar solo la carpeta» o «Sin carpeta» se desharían en cada sincronización)
				if (local != null && enlazadas[local.id].isNullOrBlank() && local.categoria.isBlank() && r.categoria.isNotBlank()) db.ponerCategoria(id, r.categoria)
				db.ponerRemotoFuente(id, r.id)
				idLocal[r.id] = id
				emparejadas += id
			}
			// Las que estaban enlazadas y ya no vienen se borraron en el servidor: aquí también. Las que tienen
			// favoritos o artículos para más tarde se quedan, pausadas y sin enlazar, y no se vuelven a subir.
			// (Con la lista vacía no se borra nada: puede ser un fallo del servidor.)
			if (remotas.isNotEmpty()) {
				val conservadas = ArrayList<Long>()
				for (f in locales.filter { it.id !in emparejadas && !enlazadas[it.id].isNullOrBlank() }) {
					if (db.tieneGuardados(f.id)) {
						db.desenlazarFuente(f.id, pausada = true)
						conservadas += f.id
					} else db.borrarFuente(f.id)
				}
				anadirNoSubir(c, conservadas)
			}
			// Las de este móvil que no están en la cuenta: solo se suben si el usuario lo ha decidido (se le pregunta
			// al sincronizar a mano). Nunca las de ejemplo que no ha tocado ni las que el servidor borró o no reconoce.
			val vetadas = noSubir(c)
			val candidatas = db.fuentes().filter { f ->
				f.id !in emparejadas && enlazadas[f.id].isNullOrBlank() && f.id !in vetadas && !Rss.esEjemploSinTocar(c, f)
			}
			val decision = Rss.prefs(c).getString(SUBIR, "").orEmpty()
			val subidas = ArrayList<Pair<Fuente, String>>()
			if (decision == "si") for (f in candidatas) {
				runCatching { s.suscribir(f.url, f.titulo, f.categoria) }.getOrNull()?.let { subidas += f to it }
			}
			// Se enlazan solo cuando el servidor las da en su lista (con su id de verdad; FreshRSS devuelve otro al suscribirse)
			if (subidas.isNotEmpty()) runCatching { s.fuentes() }.getOrNull()?.let { segunda ->
				val primeras = remotas.map { it.id }.toSet()
				val sinCasar = ArrayList<Fuente>()
				for ((f, devuelto) in subidas) {
					val r = segunda.firstOrNull { it.id == devuelto && !idLocal.containsKey(it.id) }
						?: segunda.firstOrNull { !idLocal.containsKey(it.id) && direccionNormal(it.url) == direccionNormal(f.url) }
					if (r == null) sinCasar += f
					else {
						db.ponerRemotoFuente(f.id, r.id)
						idLocal[r.id] = f.id
						emparejadas += f.id
					}
				}
				// Una sola sin casar y una sola nueva en el servidor: son la misma (el servidor cambió la dirección)
				val nuevasEnServidor = segunda.filter { it.id !in primeras && !idLocal.containsKey(it.id) }
				if (sinCasar.size == 1 && nuevasEnServidor.size == 1) {
					val f = sinCasar.removeAt(0)
					val r = nuevasEnServidor[0]
					db.ponerRemotoFuente(f.id, r.id)
					idLocal[r.id] = f.id
					emparejadas += f.id
				}
				// Las demás siguen siendo de este móvil (se actualizan aquí) y no se vuelven a subir a ciegas
				anadirNoSubir(c, sinCasar.map { it.id })
			}
			// 3. Artículos y estados, con las opciones de cada fuente (silenciar, solo con, sin duplicados);
			// las pausadas no reciben artículos
			val articulos = s.articulos()
			val porId = db.fuentes().associateBy { it.id }
			var nuevos = 0
			val avisos = ArrayList<Pair<Fuente, RssLector.Elemento>>()
			for ((fuenteRemota, lista) in articulos.groupBy { it.fuente }) {
				val id = idLocal[fuenteRemota] ?: continue
				val f = porId[id]
				if (f != null && f.pausada) continue
				val admitir: (RssLector.Elemento) -> Boolean = if (f != null) Rss.filtro(db, f) else { _ -> true }
				val llegados = db.anadirRemotos(id, lista.map { it.elemento to Triple(it.id, it.leido, it.favorito) }, admitir)
				nuevos += llegados.size
				if (f != null && f.avisar) llegados.forEach { avisos += f to it }
			}
			// Las enlazadas quedan al día y sin error (Estado de las fuentes, Información de la fuente)
			for (id in emparejadas) if (porId[id]?.pausada == false) db.guardarActualizacion(id, null, null, null, null, "")
			// Lo que acaba de llegar sin leer o como favorito lo es seguro, aunque el servidor dé los ids en otro formato.
			// Si el servidor no ha dado todos sus ids, no se marca nada como leído (ni se quita de favoritos) por faltar.
			val sinLeerServidor = s.idsSinLeer()
			val favoritosServidor = s.idsFavoritos()
			val sinLeer = sinLeerServidor.orEmpty() + articulos.filter { !it.leido }.map { it.id }
			val favoritos = favoritosServidor.orEmpty() + articulos.filter { it.favorito }.map { it.id }
			if (sinLeerServidor != null) db.estadoRemoto(db.remotosSinLeer() - sinLeer, leido = true)
			db.estadoRemoto(sinLeer, leido = false)
			if (favoritosServidor != null) db.estadoRemoto(db.remotosFavoritos() - favoritos, favorito = false)
			db.estadoRemoto(favoritos, favorito = true)
			db.avisar()
			Rss.prefs(c).edit().putLong("rss_sinc_ultima", System.currentTimeMillis()).apply()
			Resultado(remotas.size, nuevos, avisos, porSubir = if (decision.isEmpty()) candidatas.size else 0)
		}
	}

	/**
	 * Tras sincronizar a mano: si hay fuentes de este móvil que aún no están en la
	 * cuenta y no se ha decidido, se pregunta si subirlas. Con «Subir», se suben
	 * ya (con la ventana de espera); con «No subir», no se vuelve a preguntar
	 * hasta que se cambie de cuenta.
	 */
	fun preguntarSubir(a: AppCompatActivity, r: Resultado) {
		if (r.porSubir <= 0) return
		MaterialAlertDialogBuilder(a)
			.setTitle(R.string.noticias_subir_titulo)
			.setMessage(a.resources.getQuantityString(R.plurals.noticias_subir_pregunta, r.porSubir, r.porSubir))
			.setPositiveButton(R.string.noticias_subir_si) { _, _ ->
				Rss.prefs(a).edit().putString(SUBIR, "si").apply()
				a.lifecycleScope.launch {
					val texto = try {
						val hecho = a.conEspera(a.getString(R.string.noticias_subir_titulo), a.getString(R.string.sinc_sincronizando)) { sincronizar(a) }
						Rss.prefs(a).edit().putString("rss_sinc_error", "").apply()
						a.getString(R.string.sinc_rss_hecho, hecho.fuentes, hecho.nuevos)
					} catch (e: kotlinx.coroutines.CancellationException) {
						throw e
					} catch (e: Exception) {
						a.getString(R.string.sinc_error, e.message ?: e.javaClass.simpleName)
					}
					a.avisar(texto)
				}
			}
			.setNegativeButton(R.string.noticias_subir_no) { _, _ -> Rss.prefs(a).edit().putString(SUBIR, "no").apply() }
			.show()
	}

	/** Miniflux descarga él mismo el artículo completo de la web (fetch-content). */
	fun textoDelServidor(c: Context, a: Articulo): String? {
		if (Rss.prefs(c).getString("rss_sinc_tipo", "") != "miniflux" || !configurado(c)) return null
		val remoto = RssBaseDatos.obtener(c).remotoDe(a.id).ifBlank { return null }
		val s = servicio(c) as? Miniflux ?: return null
		return runCatching { s.contenidoCompleto(remoto) }.getOrNull()?.takeIf { it.isNotBlank() }
	}

	/** Al borrar una fuente aquí, también se quita del servidor. */
	suspend fun borrarFuente(c: Context, remoto: String) = withContext(Dispatchers.IO) {
		if (remoto.isBlank()) return@withContext
		runCatching { servicio(c)?.apply { entrar(); borrar(remoto) } }
	}

	// --- HTTP ---------------------------------------------------------------------------

	private fun peticion(url: String, metodo: String = "GET", cuerpo: String? = null, tipo: String = "application/json",
		cabeceras: Map<String, String> = emptyMap()): String {
		val c = URL(url).openConnection() as HttpURLConnection
		try {
			c.requestMethod = metodo
			c.connectTimeout = 20_000
			c.readTimeout = 60_000
			c.setRequestProperty("User-Agent", "LectorUniversal/" + BuildConfig.VERSION_NAME)
			cabeceras.forEach { (k, v) -> c.setRequestProperty(k, v) }
			if (cuerpo != null) {
				c.doOutput = true
				c.setRequestProperty("Content-Type", tipo)
				c.outputStream.use { it.write(cuerpo.toByteArray()) }
			}
			val codigo = c.responseCode
			val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
			if (codigo == 401 || codigo == 403) error("Usuario o contraseña incorrectos ($codigo)")
			if (codigo !in 200..299) error("HTTP $codigo ${texto.take(200)}")
			return texto
		} finally {
			c.disconnect()
		}
	}

	private fun basica(usuario: String, clave: String) = "Basic " + Base64.encodeToString("$usuario:$clave".toByteArray(), Base64.NO_WRAP)
	private fun e(s: String) = URLEncoder.encode(s, "UTF-8")
	private fun texto(html: String) = Jsoup.parse(html).text()

	// --- API de Google Reader (FreshRSS, The Old Reader...) ---------------------------------

	private class GoogleReader(val base: String, val usuario: String, val clave: String) : Servicio {
		private var auth = ""
		private var token = ""
		private val cab get() = mapOf("Authorization" to "GoogleLogin auth=$auth")

		override fun entrar() {
			val r = peticion("$base/accounts/ClientLogin", "POST", "Email=${e(usuario)}&Passwd=${e(clave)}", "application/x-www-form-urlencoded")
			auth = r.lines().firstOrNull { it.startsWith("Auth=") }?.removePrefix("Auth=")?.trim() ?: error("El servidor no ha dado acceso")
			token = runCatching { peticion("$base/reader/api/0/token", cabeceras = cab).trim() }.getOrDefault("")
		}

		override fun fuentes(): List<FuenteRemota> {
			val a = JSONObject(peticion("$base/reader/api/0/subscription/list?output=json", cabeceras = cab)).optJSONArray("subscriptions") ?: JSONArray()
			return (0 until a.length()).mapNotNull { i ->
				val o = a.optJSONObject(i) ?: return@mapNotNull null
				val id = o.optString("id")
				val url = o.optString("url").ifBlank { id.removePrefix("feed/") }
				FuenteRemota(id, url, o.optString("title"), o.optString("htmlUrl"),
					o.optJSONArray("categories")?.optJSONObject(0)?.optString("label").orEmpty())
			}
		}

		/** Los artículos de una lista, página a página (con «continuation») hasta el tope. */
		private fun elementos(stream: String, excluirLeidos: Boolean): List<ArticuloRemoto> {
			val xt = if (excluirLeidos) "&xt=user/-/state/com.google/read" else ""
			val r = ArrayList<ArticuloRemoto>()
			var continuacion = ""
			val vistas = HashSet<String>()
			do {
				val c = if (continuacion.isNotEmpty()) "&c=${e(continuacion)}" else ""
				val o = JSONObject(peticion("$base/reader/api/0/stream/contents/$stream?n=$PAGINA$xt$c&output=json", cabeceras = cab))
				val items = o.optJSONArray("items") ?: JSONArray()
				r += pagina(items)
				continuacion = o.optString("continuation")
				// Una continuación repetida daría vueltas sin fin
			} while (continuacion.isNotEmpty() && vistas.add(continuacion) && items.length() > 0 && r.size < TOPE)
			return r
		}

		private fun pagina(items: JSONArray): List<ArticuloRemoto> {
			return (0 until items.length()).mapNotNull { i ->
				val it = items.optJSONObject(i) ?: return@mapNotNull null
				val categorias = (0 until (it.optJSONArray("categories")?.length() ?: 0)).map { k -> it.optJSONArray("categories")!!.optString(k) }
				val html = it.optJSONObject("content")?.optString("content").orEmpty().ifBlank { it.optJSONObject("summary")?.optString("content").orEmpty() }
				val enlace = it.optJSONArray("canonical")?.optJSONObject(0)?.optString("href").orEmpty()
					.ifBlank { it.optJSONArray("alternate")?.optJSONObject(0)?.optString("href").orEmpty() }
				val adjunto = it.optJSONArray("enclosure")?.optJSONObject(0)
				ArticuloRemoto(
					id = it.optString("id"),
					fuente = it.optJSONObject("origin")?.optString("streamId").orEmpty(),
					elemento = RssLector.Elemento(it.optString("id"), texto(it.optString("title")), enlace, it.optString("author"),
						it.optLong("published") * 1000, texto(html).take(600), html, adjunto?.optString("href").orEmpty(), adjunto?.optString("type").orEmpty(), ""),
					leido = categorias.any { c -> c.endsWith("/state/com.google/read") },
					favorito = categorias.any { c -> c.endsWith("/state/com.google/starred") },
				)
			}
		}

		override fun articulos() = elementos("user/-/state/com.google/reading-list", true) + elementos("user/-/state/com.google/starred", false)

		/**
		 * Los ids de una lista, página a página (con «continuation»), en el formato largo
		 * que usan los artículos. null si no se han podido traer todos (tope o continuación repetida).
		 */
		private fun ids(consulta: String): Set<String>? {
			val r = HashSet<String>()
			var continuacion = ""
			val vistas = HashSet<String>()
			while (true) {
				val c = if (continuacion.isNotEmpty()) "&c=${e(continuacion)}" else ""
				val o = JSONObject(peticion("$base/reader/api/0/stream/items/ids?$consulta&n=10000$c&output=json", cabeceras = cab))
				val a = o.optJSONArray("itemRefs") ?: JSONArray()
				for (i in 0 until a.length()) r += formasDelId(a.optJSONObject(i)?.optString("id").orEmpty())
				continuacion = o.optString("continuation")
				if (continuacion.isEmpty() || a.length() == 0) return r
				if (!vistas.add(continuacion) || r.size >= 200_000) return null
			}
		}

		/**
		 * Un id corto en el formato largo. Los decimales (FreshRSS; con signo en
		 * el Google Reader original) pasan a 16 cifras hexadecimales. Los que ya
		 * vienen en hexadecimal (The Old Reader) se dan en las dos formas, para
		 * que casen con los de los artículos sea cual sea el que use el servidor.
		 */
		private fun formasDelId(id: String): List<String> {
			val t = id.trim()
			if (t.isEmpty()) return emptyList()
			if (t.startsWith("tag:")) return listOf(t)
			val numero = when {
				Regex("-\\d+").matches(t) -> t.toLongOrNull()
				t.all { it.isDigit() } -> t.toULongOrNull()?.toLong()
				else -> null
			}
			val prefijo = "tag:google.com,2005:reader/item/"
			return if (numero != null) listOf(prefijo + "%016x".format(numero)) else listOf(prefijo + t.lowercase(), t)
		}

		override fun idsSinLeer() = ids("s=user/-/state/com.google/reading-list&xt=user/-/state/com.google/read")
		override fun idsFavoritos() = ids("s=user/-/state/com.google/starred")

		private fun etiqueta(ids: List<String>, etiqueta: String, poner: Boolean) {
			for (trozo in ids.chunked(200)) {
				val cuerpo = trozo.joinToString("&") { "i=${e(it)}" } + "&${if (poner) "a" else "r"}=${e(etiqueta)}&T=${e(token)}"
				peticion("$base/reader/api/0/edit-tag", "POST", cuerpo, "application/x-www-form-urlencoded", cab)
			}
		}

		override fun marcarLeidos(ids: List<String>, leido: Boolean) = etiqueta(ids, "user/-/state/com.google/read", leido)
		override fun marcarFavoritos(ids: List<String>, si: Boolean) = etiqueta(ids, "user/-/state/com.google/starred", si)

		override fun suscribir(url: String, titulo: String, categoria: String): String? {
			val id = "feed/$url"
			val cuerpo = "ac=subscribe&s=${e(id)}&t=${e(titulo)}" + (if (categoria.isNotBlank()) "&a=${e("user/-/label/$categoria")}" else "") + "&T=${e(token)}"
			peticion("$base/reader/api/0/subscription/edit", "POST", cuerpo, "application/x-www-form-urlencoded", cab)
			return id
		}

		override fun borrar(remoto: String) {
			peticion("$base/reader/api/0/subscription/edit", "POST", "ac=unsubscribe&s=${e(remoto)}&T=${e(token)}", "application/x-www-form-urlencoded", cab)
		}
	}

	// --- Miniflux ------------------------------------------------------------------------

	/** usuario vacío: la clave es una clave de API; si no, usuario y contraseña. */
	private class Miniflux(val base: String, val usuario: String, val clave: String) : Servicio {
		private val cab get() = if (usuario.isBlank()) mapOf("X-Auth-Token" to clave) else mapOf("Authorization" to basica(usuario, clave))
		private val api get() = if (base.endsWith("/v1")) base else "$base/v1"

		override fun entrar() {
			peticion("$api/me", cabeceras = cab)
		}

		override fun fuentes(): List<FuenteRemota> {
			val a = JSONArray(peticion("$api/feeds", cabeceras = cab))
			return (0 until a.length()).mapNotNull { i ->
				val o = a.optJSONObject(i) ?: return@mapNotNull null
				FuenteRemota(o.optLong("id").toString(), o.optString("feed_url"), o.optString("title"), o.optString("site_url"),
					o.optJSONObject("category")?.optString("title").orEmpty())
			}
		}

		/** Las entradas, página a página (offset) hasta el tope. */
		private fun entradas(consulta: String): List<ArticuloRemoto> {
			val r = ArrayList<ArticuloRemoto>()
			var vistas = 0
			do {
				val a = JSONObject(peticion("$api/entries?$consulta&limit=$PAGINA&offset=$vistas&order=published_at&direction=desc", cabeceras = cab))
					.optJSONArray("entries") ?: JSONArray()
				vistas += a.length()
				r += pagina(a)
			} while (a.length() >= PAGINA && r.size < TOPE)
			return r
		}

		private fun pagina(a: JSONArray): List<ArticuloRemoto> {
			return (0 until a.length()).mapNotNull { i ->
				val o = a.optJSONObject(i) ?: return@mapNotNull null
				val html = o.optString("content")
				val adjunto = o.optJSONArray("enclosures")?.optJSONObject(0)
				ArticuloRemoto(o.optLong("id").toString(), o.optLong("feed_id").toString(),
					RssLector.Elemento(o.optString("hash"), o.optString("title"), o.optString("url"), o.optString("author"),
						RssLector.fecha(o.optString("published_at")), texto(html).take(600), html, adjunto?.optString("url").orEmpty(), adjunto?.optString("mime_type").orEmpty(), ""),
					o.optString("status") == "read", o.optBoolean("starred"))
			}
		}

		override fun articulos() = entradas("status=unread") + entradas("starred=true")

		/** null si hay más de los que caben en una petición (total mayor que los recibidos). */
		private fun ids(consulta: String): Set<String>? {
			val o = JSONObject(peticion("$api/entries?$consulta&limit=10000", cabeceras = cab))
			val a = o.optJSONArray("entries") ?: JSONArray()
			if (o.optInt("total", a.length()) > a.length()) return null
			return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optLong("id")?.toString() }.toSet()
		}

		override fun idsSinLeer() = ids("status=unread")
		override fun idsFavoritos() = ids("starred=true")

		override fun marcarLeidos(ids: List<String>, leido: Boolean) {
			for (trozo in ids.chunked(300)) {
				val cuerpo = JSONObject().put("entry_ids", JSONArray(trozo.mapNotNull { it.toLongOrNull() })).put("status", if (leido) "read" else "unread")
				peticion("$api/entries", "PUT", cuerpo.toString(), cabeceras = cab)
			}
		}

		override fun marcarFavoritos(ids: List<String>, si: Boolean) {
			// Miniflux solo tiene "cambiar": se mira cada uno y se cambian los que no están como se pide
			// (la lista de todos los favoritos puede venir cortada y se cambiarían los que no toca)
			for (id in ids) {
				val ahora = runCatching { JSONObject(peticion("$api/entries/$id", cabeceras = cab)).optBoolean("starred") }.getOrNull() ?: continue
				if (ahora != si) peticion("$api/entries/$id/bookmark", "PUT", "", cabeceras = cab)
			}
		}

		override fun suscribir(url: String, titulo: String, categoria: String): String? {
			val r = JSONObject(peticion("$api/feeds", "POST", JSONObject().put("feed_url", url).toString(), cabeceras = cab))
			return r.optLong("feed_id").takeIf { it > 0 }?.toString()
		}

		override fun borrar(remoto: String) {
			peticion("$api/feeds/$remoto", "DELETE", cabeceras = cab)
		}

		fun contenidoCompleto(id: String): String =
			JSONObject(peticion("$api/entries/$id/fetch-content?update_content=false", cabeceras = cab)).optString("content")
	}

	// --- Nextcloud News -----------------------------------------------------------------

	private class NextcloudNews(val base: String, val usuario: String, val clave: String) : Servicio {
		private val cab get() = mapOf("Authorization" to basica(usuario, clave))
		// Siempre la v1-3 (la que entiende este código), aunque la dirección escrita traiga otra (/api/v1-2)
		private val api get() = if (base.contains("/apps/news/api")) base.substringBefore("/apps/news/api") + "/apps/news/api/v1-3"
			else "$base/index.php/apps/news/api/v1-3"

		override fun entrar() {
			peticion("$api/version", cabeceras = cab)
		}

		override fun fuentes(): List<FuenteRemota> {
			val carpetas = JSONObject(peticion("$api/folders", cabeceras = cab)).optJSONArray("folders") ?: JSONArray()
			val nombres = (0 until carpetas.length()).mapNotNull { carpetas.optJSONObject(it) }.associate { it.optLong("id") to it.optString("name") }
			val a = JSONObject(peticion("$api/feeds", cabeceras = cab)).optJSONArray("feeds") ?: JSONArray()
			return (0 until a.length()).mapNotNull { i ->
				val o = a.optJSONObject(i) ?: return@mapNotNull null
				FuenteRemota(o.optLong("id").toString(), o.optString("url"), o.optString("title"), o.optString("link"), nombres[o.optLong("folderId")].orEmpty())
			}
		}

		/** Los artículos, página a página: cada página pide los más antiguos que el último recibido (offset), hasta el tope. */
		private fun elementos(tipo: Int, leidos: Boolean): List<ArticuloRemoto> {
			val r = ArrayList<ArticuloRemoto>()
			val vistos = HashSet<Long>()
			var desde = 0L
			do {
				val a = JSONObject(peticion("$api/items?type=$tipo&getRead=$leidos&batchSize=$PAGINA&offset=$desde", cabeceras = cab))
					.optJSONArray("items") ?: JSONArray()
				var menor = Long.MAX_VALUE
				val nuevos = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { o ->
					val id = o.optLong("id")
					if (id > 0) menor = minOf(menor, id)
					vistos.add(id)
				}
				r += pagina(nuevos)
				val seguir = a.length() >= PAGINA && nuevos.isNotEmpty() && menor != Long.MAX_VALUE
				desde = menor
			} while (seguir && r.size < TOPE)
			return r
		}

		private fun pagina(lista: List<JSONObject>): List<ArticuloRemoto> {
			return lista.map { o ->
				val html = o.optString("body")
				ArticuloRemoto(o.optLong("id").toString(), o.optLong("feedId").toString(),
					RssLector.Elemento(o.optString("guidHash"), o.optString("title"), o.optString("url"), o.optString("author"),
						o.optLong("pubDate") * 1000, texto(html).take(600), html, o.optString("enclosureLink").takeIf { it != "null" }.orEmpty(),
						o.optString("enclosureMime").takeIf { it != "null" }.orEmpty(), ""),
					!o.optBoolean("unread"), o.optBoolean("starred"))
			}
		}

		override fun articulos() = elementos(3, false) + elementos(2, true)

		private fun ids(tipo: Int, leidos: Boolean): Set<String> {
			val a = JSONObject(peticion("$api/items?type=$tipo&getRead=$leidos&batchSize=-1", cabeceras = cab)).optJSONArray("items") ?: JSONArray()
			return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optLong("id")?.toString() }.toSet()
		}

		override fun idsSinLeer() = ids(3, false)
		override fun idsFavoritos() = ids(2, true)

		private fun multiple(accion: String, ids: List<String>) {
			for (trozo in ids.chunked(300)) {
				peticion("$api/items/$accion/multiple", "PUT", JSONObject().put("itemIds", JSONArray(trozo.mapNotNull { it.toLongOrNull() })).toString(), cabeceras = cab)
			}
		}

		override fun marcarLeidos(ids: List<String>, leido: Boolean) = multiple(if (leido) "read" else "unread", ids)
		override fun marcarFavoritos(ids: List<String>, si: Boolean) = multiple(if (si) "star" else "unstar", ids)

		override fun suscribir(url: String, titulo: String, categoria: String): String? {
			val r = JSONObject(peticion("$api/feeds", "POST", JSONObject().put("url", url).put("folderId", 0).toString(), cabeceras = cab))
			return r.optJSONArray("feeds")?.optJSONObject(0)?.optLong("id")?.toString()
		}

		override fun borrar(remoto: String) {
			peticion("$api/feeds/$remoto", "DELETE", cabeceras = cab)
		}
	}
}
