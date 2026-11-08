package com.xebolax.lector.sincronizacion

import android.content.Context
import android.util.Base64
import androidx.preference.PreferenceManager
import com.xebolax.lector.BuildConfig
import com.xebolax.lector.R
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Libro
import com.xebolax.lector.modelo.Posicion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sincroniza por dónde vas en cada libro, su progreso, su estado y sus
 * marcadores entre tus móviles y tablets, con un servidor WebDAV (por ejemplo
 * el paquete «WebDAV Server» de tu Synology, o Nextcloud).
 *
 * Todo va en un solo archivo de texto (lector-universal-sincronizacion.json)
 * en la carpeta que indiques. Cada libro se reconoce por el nombre y el
 * tamaño de su archivo, así que tiene que ser el mismo archivo en los dos
 * sitios. Gana la posición más reciente; los marcadores se juntan (los que se
 * borran en un sitio no se borran en el otro). El formato es sencillo para
 * que otra app (la de Windows) pueda usarlo también.
 */
object Sincronizador {

	private const val ARCHIVO = "lector-universal-sincronizacion.json"
	private val cerrojo = Mutex()

	class Resultado(val libros: Int, val actualizados: Int, val marcadores: Int)

	private fun prefs(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)

	fun configurado(c: Context) = prefs(c).getString("sinc_url", "").orEmpty().isNotBlank()

	private fun direccion(c: Context): String {
		val base = prefs(c).getString("sinc_url", "").orEmpty().trim()
		return if (base.endsWith(".json")) base else base.trimEnd('/') + "/" + ARCHIVO
	}

	private fun clave(l: Libro) = "${l.nombre}|${l.tamano}"

	/** Sincroniza si está configurado y han pasado al menos 'minimo' ms desde la última vez. */
	suspend fun siToca(c: Context, minimo: Long = 5 * 60_000L) {
		if (!configurado(c)) return
		val ultima = prefs(c).getLong("sinc_ultima", 0)
		if (System.currentTimeMillis() - ultima < minimo) return
		runCatching { sincronizar(c) }
	}

	suspend fun sincronizar(c: Context): Resultado = cerrojo.withLock {
		withContext(Dispatchers.IO) {
			val url = direccion(c)
			val db = BaseDatos.obtener(c)
			val remoto = descargar(c, url)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
			val librosRemotos = remoto.optJSONObject("libros") ?: JSONObject()
			var actualizados = 0
			var marcadoresNuevos = 0
			val locales = db.todos().filter { !it.audio || it.tamano > 0 }
			for (l in locales) {
				val k = clave(l)
				val r = librosRemotos.optJSONObject(k)
				if (r != null) {
					// La posición más reciente gana
					val cuando = r.optLong("cuando", 0)
					val pos = Posicion.leer(r.optString("posicion"))
					if (pos != null && cuando > l.abierto + 1000) {
						db.aplicarSincronizacion(l.id, pos, r.optDouble("progreso", l.progreso), r.optInt("estado", l.estado), cuando)
						actualizados++
					}
					// Marcadores: se añaden los que faltan aquí
					val existentes = db.marcadores(l.id).map { "${it.posicion}|${it.creado}" }.toHashSet()
					val lista = r.optJSONArray("marcadores") ?: JSONArray()
					for (i in 0 until lista.length()) {
						val m = lista.optJSONObject(i) ?: continue
						val mp = Posicion.leer(m.optString("posicion")) ?: continue
						val creado = m.optLong("creado")
						if ("$mp|$creado" in existentes) continue
						db.anadirMarcadorSincronizado(l.id, mp, m.optString("extracto"), m.optString("nota"), m.optString("lugar"), creado)
						marcadoresNuevos++
					}
				}
			}
			// Lo de aquí se sube (los libros que solo están en el otro sitio se conservan)
			for (l in db.todos()) {
				if (l.posicion == null && db.marcadores(l.id).isEmpty()) continue
				val k = clave(l)
				val anterior = librosRemotos.optJSONObject(k)
				val cuandoRemoto = anterior?.optLong("cuando", 0) ?: 0
				val entrada = if (anterior != null && cuandoRemoto > l.abierto + 1000) anterior else JSONObject()
					.put("titulo", l.tituloVisible)
					.put("posicion", l.posicion?.toString().orEmpty())
					.put("progreso", l.progreso)
					.put("estado", l.estado)
					.put("cuando", l.abierto)
				val marcadores = JSONArray()
				val vistos = HashSet<String>()
				for (m in db.marcadores(l.id)) {
					vistos += "${m.posicion}|${m.creado}"
					marcadores.put(JSONObject().put("posicion", m.posicion.toString()).put("extracto", m.extracto).put("nota", m.nota)
						.put("lugar", m.lugar).put("creado", m.creado))
				}
				anterior?.optJSONArray("marcadores")?.let { a ->
					for (i in 0 until a.length()) {
						val m = a.optJSONObject(i) ?: continue
						if ("${m.optString("posicion")}|${m.optLong("creado")}" !in vistos) marcadores.put(m)
					}
				}
				entrada.put("marcadores", marcadores)
				librosRemotos.put(k, entrada)
			}
			val salida = JSONObject()
				.put("app", "LectorUniversal")
				.put("version", 1)
				.put("actualizado", System.currentTimeMillis())
				.put("dispositivo", android.os.Build.MODEL)
				.put("libros", librosRemotos)
			subir(c, url, salida.toString(1))
			prefs(c).edit().putLong("sinc_ultima", System.currentTimeMillis()).apply()
			Resultado(librosRemotos.length(), actualizados, marcadoresNuevos)
		}
	}

	private fun conexion(c: Context, url: String, metodo: String): HttpURLConnection {
		val conexion = URL(url).openConnection() as HttpURLConnection
		conexion.requestMethod = metodo
		conexion.connectTimeout = 20_000
		conexion.readTimeout = 30_000
		conexion.setRequestProperty("User-Agent", "LectorUniversal/${BuildConfig.VERSION_NAME}")
		val usuario = prefs(c).getString("sinc_usuario", "").orEmpty()
		val clave = com.xebolax.lector.datos.Secretos.leer(c, "sinc_clave")
		if (usuario.isNotBlank()) {
			conexion.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("$usuario:$clave".toByteArray(), Base64.NO_WRAP))
		}
		return conexion
	}

	private fun descargar(c: Context, url: String): String? {
		val conexion = conexion(c, url, "GET")
		try {
			return when (val codigo = conexion.responseCode) {
				200 -> conexion.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
				404 -> null
				401, 403 -> throw IllegalStateException(c.getString(R.string.sinc_error_clave))
				else -> throw IllegalStateException("HTTP $codigo")
			}
		} finally {
			conexion.disconnect()
		}
	}

	private fun subir(c: Context, url: String, texto: String) {
		val conexion = conexion(c, url, "PUT")
		try {
			conexion.doOutput = true
			conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
			conexion.outputStream.use { it.write(texto.toByteArray()) }
			when (val codigo = conexion.responseCode) {
				in 200..299 -> Unit
				401, 403 -> throw IllegalStateException(c.getString(R.string.sinc_error_clave))
				409 -> throw IllegalStateException(c.getString(R.string.sinc_error_carpeta))
				else -> throw IllegalStateException("HTTP $codigo")
			}
		} finally {
			conexion.disconnect()
		}
	}
}
