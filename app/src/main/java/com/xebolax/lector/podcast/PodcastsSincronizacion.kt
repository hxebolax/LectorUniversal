package com.xebolax.lector.podcast

import android.content.Context
import android.util.Base64
import com.xebolax.lector.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Sincroniza los podcasts con gpodder.net (o un servidor compatible) o con la
 * app gPodder Sync de Nextcloud: las suscripciones y por dónde va cada
 * episodio o si ya se escuchó, igual que AntennaPod, Kasts o gPodder.
 */
object PodcastsSincronizacion {

	class Cambios(val anadir: List<String>, val quitar: List<String>, val marca: Long)
	class Accion(val podcast: String, val episodio: String, val posicion: Long, val total: Long, val cuando: Long)

	private interface Servicio {
		fun entrar()
		fun suscripciones(desde: Long): Cambios
		/** Envía altas y bajas; devuelve la nueva marca de tiempo. */
		fun enviarSuscripciones(anadir: List<String>, quitar: List<String>): Long
		fun acciones(desde: Long): Pair<List<Accion>, Long>
		fun enviarAcciones(lista: JSONArray)
	}

	private val cerrojo = Mutex()

	fun configurado(c: Context): Boolean {
		val p = Podcasts.prefs(c)
		return p.getString("pod_sinc_tipo", "ninguno") != "ninguno" && p.getString("pod_sinc_usuario", "").orEmpty().isNotBlank()
	}

	private fun servicio(c: Context): Servicio? {
		val p = Podcasts.prefs(c)
		val usuario = p.getString("pod_sinc_usuario", "").orEmpty().trim()
		val clave = com.xebolax.lector.datos.Secretos.leer(c, "pod_sinc_clave")
		val url = p.getString("pod_sinc_url", "").orEmpty().trim().trimEnd('/')
		return when (p.getString("pod_sinc_tipo", "ninguno")) {
			"gpodder" -> {
				var dispositivo = p.getString("pod_sinc_dispositivo", "").orEmpty()
				if (dispositivo.isBlank()) {
					dispositivo = "lectoruniversal-" + UUID.randomUUID().toString().take(8)
					p.edit().putString("pod_sinc_dispositivo", dispositivo).apply()
				}
				Gpodder(url.ifBlank { "https://gpodder.net" }, usuario, clave, dispositivo)
			}
			"nextcloud" -> if (url.isBlank()) null else Nextcloud(url, usuario, clave)
			else -> null
		}
	}

	/** Para empezar de cero (al cambiar de cuenta). */
	fun olvidar(c: Context) {
		Podcasts.prefs(c).edit().remove("pod_sinc_marca_subs").remove("pod_sinc_marca_acciones").remove("pod_sinc_conocidas").apply()
	}

	class Resultado(val anadidos: Int, val quitados: Int, val acciones: Int)

	suspend fun sincronizar(c: Context): Resultado = cerrojo.withLock {
		withContext(Dispatchers.IO) {
			val s = servicio(c) ?: error("Sin cuenta")
			val p = Podcasts.prefs(c)
			val db = PodcastsBaseDatos.obtener(c)
			s.entrar()
			// 1. Suscripciones: las altas y bajas de aquí desde la última vez, y luego las del servidor
			val conocidas = p.getStringSet("pod_sinc_conocidas", null)
			val locales = db.podcasts().map { it.url }.toSet()
			val primeraVez = conocidas == null
			val remotos = s.suscripciones(if (primeraVez) 0 else p.getLong("pod_sinc_marca_subs", 0))
			var anadidos = 0
			var quitados = 0
			for (url in remotos.anadir) if (url !in locales) {
				runCatching { Podcasts.suscribir(c, url) }.onSuccess { anadidos++ }
			}
			if (!primeraVez) for (url in remotos.quitar) db.podcastPorUrl(url)?.let {
				db.borrarPodcast(it.id)
				quitados++
			}
			val ahora = db.podcasts().map { it.url }.toSet()
			val subir = if (primeraVez) ahora - remotos.anadir.toSet() else (ahora - conocidas!!) - remotos.anadir.toSet()
			val bajar = if (primeraVez) emptySet() else (conocidas!! - locales) - remotos.quitar.toSet()
			var marca = remotos.marca
			if (subir.isNotEmpty() || bajar.isNotEmpty()) marca = s.enviarSuscripciones(subir.toList(), bajar.toList())
			p.edit().putLong("pod_sinc_marca_subs", marca).putStringSet("pod_sinc_conocidas", db.podcasts().map { it.url }.toSet()).apply()
			// 2. Lo escuchado aquí
			val pendientes = db.pendientesDeEnviar()
			val urls = db.podcasts().associate { it.id to it.url }
			val lista = JSONArray()
			for (e in pendientes) {
				val total = e.duracion / 1000
				val posicion = if (e.terminado) total else e.posicion / 1000
				if (posicion <= 0 && !e.terminado) continue
				lista.put(JSONObject()
					.put("podcast", urls[e.podcast].orEmpty())
					.put("episode", e.audio)
					.put("guid", e.guid)
					.put("action", "play")
					.put("started", 0)
					.put("position", posicion)
					.put("total", total.coerceAtLeast(posicion))
					// Marcado como escuchado: ahora (escuchado es la última vez que sonó, para el historial)
					.put("timestamp", fecha(if (e.terminado || e.escuchado <= 0) System.currentTimeMillis() else e.escuchado)))
			}
			for (t in 0 until lista.length() step 500) {
				val trozo = JSONArray()
				for (i in t until minOf(t + 500, lista.length())) trozo.put(lista.get(i))
				s.enviarAcciones(trozo)
			}
			db.enviados(pendientes.map { it.id })
			// 3. Lo escuchado en otros dispositivos
			val (acciones, marcaAcciones) = s.acciones(p.getLong("pod_sinc_marca_acciones", 0))
			var aplicadas = 0
			for (a in acciones.sortedBy { it.cuando }) {
				val e = db.episodioPorAudio(a.episodio) ?: continue
				if (a.cuando < e.escuchado) continue
				val terminado = a.total > 0 && a.posicion >= a.total - 5
				db.aplicarRemoto(e.id, a.posicion * 1000, terminado, a.cuando)
				aplicadas++
			}
			if (aplicadas > 0) db.renumerar()
			p.edit().putLong("pod_sinc_marca_acciones", marcaAcciones).putLong("pod_sinc_ultima", System.currentTimeMillis()).apply()
			db.avisar()
			Resultado(anadidos, quitados, aplicadas)
		}
	}

	// --- HTTP ---------------------------------------------------------------------------

	private fun formato() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
	private fun fecha(ms: Long) = formato().format(ms)
	private fun leerFecha(s: String) = runCatching { formato().parse(s.take(19))!!.time }.getOrDefault(0L)

	private fun peticion(url: String, usuario: String, clave: String, metodo: String = "GET", cuerpo: String? = null): String {
		val c = URL(url).openConnection() as HttpURLConnection
		try {
			c.requestMethod = metodo
			c.connectTimeout = 20_000
			c.readTimeout = 60_000
			c.setRequestProperty("User-Agent", "LectorUniversal/" + BuildConfig.VERSION_NAME)
			c.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("$usuario:$clave".toByteArray(), Base64.NO_WRAP))
			if (cuerpo != null) {
				c.doOutput = true
				c.setRequestProperty("Content-Type", "application/json")
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

	private fun lista(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.filter { it.isNotBlank() }

	private fun leerAcciones(o: JSONObject): Pair<List<Accion>, Long> {
		val a = o.optJSONArray("actions") ?: JSONArray()
		val acciones = (0 until a.length()).mapNotNull { i ->
			val x = a.optJSONObject(i) ?: return@mapNotNull null
			if (!x.optString("action").equals("play", true)) return@mapNotNull null
			Accion(x.optString("podcast"), x.optString("episode"), x.optLong("position"), x.optLong("total"), leerFecha(x.optString("timestamp")))
		}
		return acciones to o.optLong("timestamp")
	}

	// --- gpodder.net (API 2) --------------------------------------------------------------

	private class Gpodder(val base: String, val usuario: String, val clave: String, val dispositivo: String) : Servicio {
		private val u get() = URLEncoder.encode(usuario, "UTF-8")

		override fun entrar() {
			// Da de alta (o actualiza) este dispositivo
			peticion("$base/api/2/devices/$u/$dispositivo.json", usuario, clave, "POST",
				JSONObject().put("caption", "Lector Universal").put("type", "mobile").toString())
		}

		override fun suscripciones(desde: Long): Cambios {
			val o = JSONObject(peticion("$base/api/2/subscriptions/$u/$dispositivo.json?since=$desde", usuario, clave))
			return Cambios(lista(o.optJSONArray("add")), lista(o.optJSONArray("remove")), o.optLong("timestamp"))
		}

		override fun enviarSuscripciones(anadir: List<String>, quitar: List<String>): Long {
			val o = JSONObject(peticion("$base/api/2/subscriptions/$u/$dispositivo.json", usuario, clave, "POST",
				JSONObject().put("add", JSONArray(anadir)).put("remove", JSONArray(quitar)).toString()))
			return o.optLong("timestamp")
		}

		override fun acciones(desde: Long) =
			leerAcciones(JSONObject(peticion("$base/api/2/episodes/$u.json?since=$desde&aggregated=true", usuario, clave)))

		override fun enviarAcciones(lista: JSONArray) {
			for (i in 0 until lista.length()) lista.optJSONObject(i)?.put("device", dispositivo)
			peticion("$base/api/2/episodes/$u.json", usuario, clave, "POST", lista.toString())
		}
	}

	// --- Nextcloud (app gPodder Sync) ---------------------------------------------------

	private class Nextcloud(val base: String, val usuario: String, val clave: String) : Servicio {
		private val api get() = (if (base.contains("/index.php")) base.substringBefore("/index.php") else base) + "/index.php/apps/gpoddersync"

		override fun entrar() {}

		override fun suscripciones(desde: Long): Cambios {
			val o = JSONObject(peticion("$api/subscriptions?since=$desde", usuario, clave))
			return Cambios(lista(o.optJSONArray("add")), lista(o.optJSONArray("remove")), o.optLong("timestamp"))
		}

		override fun enviarSuscripciones(anadir: List<String>, quitar: List<String>): Long {
			val o = JSONObject(peticion("$api/subscription_change/create", usuario, clave, "POST",
				JSONObject().put("add", JSONArray(anadir)).put("remove", JSONArray(quitar)).toString()))
			return o.optLong("timestamp")
		}

		override fun acciones(desde: Long) = leerAcciones(JSONObject(peticion("$api/episode_action?since=$desde", usuario, clave)))

		override fun enviarAcciones(lista: JSONArray) {
			peticion("$api/episode_action/create", usuario, clave, "POST", lista.toString())
		}
	}
}
