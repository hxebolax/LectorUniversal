package com.xebolax.lector.rss

import android.content.Context
import android.util.Base64
import com.xebolax.lector.BuildConfig
import kotlinx.coroutines.Dispatchers
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
		fun idsSinLeer(): Set<String>
		fun idsFavoritos(): Set<String>
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

	class Resultado(val fuentes: Int, val nuevos: Int)

	/** Sincroniza todo: primero envía lo cambiado aquí, luego trae fuentes, artículos y estados. */
	suspend fun sincronizar(c: Context): Resultado = cerrojo.withLock {
		withContext(Dispatchers.IO) {
			val s = servicio(c) ?: error("Sin cuenta")
			val db = RssBaseDatos.obtener(c)
			s.entrar()
			// 1. Lo cambiado en el móvil
			val cambios = db.cambiosPendientes()
			s.marcarLeidos(cambios.filter { it.second }.map { it.first }, true)
			s.marcarLeidos(cambios.filter { !it.second }.map { it.first }, false)
			s.marcarFavoritos(cambios.filter { it.third }.map { it.first }, true)
			s.marcarFavoritos(cambios.filter { !it.third }.map { it.first }, false)
			db.cambiosEnviados()
			// 2. Fuentes: las del servidor se añaden aquí; las de aquí que no están, se suben
			val remotas = s.fuentes()
			val locales = db.fuentes()
			val enlazadas = db.remotosDeFuentes()
			val idLocal = HashMap<String, Long>()
			for (r in remotas) {
				val local = locales.firstOrNull { it.url == r.url } ?: db.fuentePorUrl(r.url)
				val id = local?.id ?: db.anadirFuente(r.url, r.titulo, r.sitio, r.categoria)
				// La carpeta del servidor solo la primera vez que se enlaza: después manda lo que se decida en el móvil
				// (si no, «Borrar solo la carpeta» o «Sin carpeta» se desharían en cada sincronización)
				if (local != null && enlazadas[local.id].isNullOrBlank() && local.categoria.isBlank() && r.categoria.isNotBlank()) db.ponerCategoria(id, r.categoria)
				db.ponerRemotoFuente(id, r.id)
				idLocal[r.id] = id
			}
			for (f in db.fuentes().filter { f -> remotas.none { it.url == f.url } }) {
				runCatching { s.suscribir(f.url, f.titulo, f.categoria) }.getOrNull()?.let {
					db.ponerRemotoFuente(f.id, it)
					idLocal[it] = f.id
				}
			}
			// 3. Artículos y estados
			val articulos = s.articulos()
			var nuevos = 0
			for ((fuenteRemota, lista) in articulos.groupBy { it.fuente }) {
				val id = idLocal[fuenteRemota] ?: continue
				nuevos += db.anadirRemotos(id, lista.map { it.elemento to Triple(it.id, it.leido, it.favorito) })
			}
			val sinLeer = s.idsSinLeer()
			val favoritos = s.idsFavoritos()
			db.estadoRemoto(db.remotosSinLeer() - sinLeer, leido = true)
			db.estadoRemoto(sinLeer, leido = false)
			db.estadoRemoto(db.remotosFavoritos() - favoritos, favorito = false)
			db.estadoRemoto(favoritos, favorito = true)
			db.avisar()
			Rss.prefs(c).edit().putLong("rss_sinc_ultima", System.currentTimeMillis()).apply()
			Resultado(remotas.size, nuevos)
		}
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

		private fun elementos(stream: String, excluirLeidos: Boolean): List<ArticuloRemoto> {
			val xt = if (excluirLeidos) "&xt=user/-/state/com.google/read" else ""
			val o = JSONObject(peticion("$base/reader/api/0/stream/contents/$stream?n=300$xt&output=json", cabeceras = cab))
			val items = o.optJSONArray("items") ?: JSONArray()
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

		/** Los ids cortos (decimales) se pasan al formato largo que usan los artículos. */
		private fun ids(consulta: String): Set<String> {
			val o = JSONObject(peticion("$base/reader/api/0/stream/items/ids?$consulta&n=10000&output=json", cabeceras = cab))
			val a = o.optJSONArray("itemRefs") ?: JSONArray()
			return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.optString("id")?.toLongOrNull() }
				.map { "tag:google.com,2005:reader/item/" + "%016x".format(it) }.toSet()
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

		private fun entradas(consulta: String): List<ArticuloRemoto> {
			val a = JSONObject(peticion("$api/entries?$consulta&limit=300&order=published_at&direction=desc", cabeceras = cab)).optJSONArray("entries") ?: JSONArray()
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

		private fun ids(consulta: String): Set<String> {
			val a = JSONObject(peticion("$api/entries?$consulta&limit=10000", cabeceras = cab)).optJSONArray("entries") ?: JSONArray()
			return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optLong("id")?.toString() }.toSet()
		}

		override fun idsSinLeer() = ids("status=unread")
		override fun idsFavoritos() = ids("starred=true")

		override fun marcarLeidos(ids: List<String>, leido: Boolean) {
			for (trozo in ids.chunked(300)) {
				val cuerpo = JSONObject().put("entry_ids", JSONArray(trozo.map { it.toLong() })).put("status", if (leido) "read" else "unread")
				peticion("$api/entries", "PUT", cuerpo.toString(), cabeceras = cab)
			}
		}

		override fun marcarFavoritos(ids: List<String>, si: Boolean) {
			// Miniflux solo tiene "cambiar": se cambian los que no están como se pide
			val actuales = idsFavoritos()
			for (id in ids) if ((id in actuales) != si) peticion("$api/entries/$id/bookmark", "PUT", "", cabeceras = cab)
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
		private val api get() = if (base.contains("/apps/news/api")) base else "$base/index.php/apps/news/api/v1-3"

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

		private fun elementos(tipo: Int, leidos: Boolean): List<ArticuloRemoto> {
			val a = JSONObject(peticion("$api/items?type=$tipo&getRead=$leidos&batchSize=300", cabeceras = cab)).optJSONArray("items") ?: JSONArray()
			return (0 until a.length()).mapNotNull { i ->
				val o = a.optJSONObject(i) ?: return@mapNotNull null
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
				peticion("$api/items/$accion/multiple", "PUT", JSONObject().put("itemIds", JSONArray(trozo.map { it.toLong() })).toString(), cabeceras = cab)
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
