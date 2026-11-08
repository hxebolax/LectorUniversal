package com.xebolax.lector.datos

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceManager

/**
 * Claves y contraseñas de la app, apartadas del resto de ajustes:
 *
 * - las claves de la inteligencia artificial en la nube (Gemini, OpenAI, Claude),
 * - las contraseñas de las cuentas de sincronización (WebDAV, noticias y podcasts),
 * - las contraseñas de los catálogos y de los podcasts privados.
 *
 * Se guardan en un archivo de ajustes propio. La copia de seguridad de Android
 * (Google Drive y el paso a un móvil nuevo) solo lo lleva si el usuario lo elige
 * en Ajustes, Copias de seguridad: para eso hay dos archivos, uno que Android
 * copia y otro que no (res/xml/respaldo*.xml), y al cambiar la opción las claves
 * pasan de uno a otro. Las copias de seguridad de la propia app solo las
 * incluyen si se protegen con contraseña (copias/CopiaSeguridad.kt).
 */
object Secretos {

	/** Nunca va en la copia de seguridad de Android. */
	const val ARCHIVO_PRIVADO = "secretos"
	/** Va en la copia de seguridad de Android (si el usuario lo ha elegido). */
	const val ARCHIVO_CON_COPIA = "secretos_copia"
	/** Opción (en los ajustes normales): ¿las claves van en la copia de Android? */
	const val OPCION_COPIA_ANDROID = "copia_android_claves"

	/** Claves de los ajustes que son secretos (antes estaban en los ajustes normales). */
	val CLAVES_AJUSTES = listOf("ia_clave_gemini", "ia_clave_openai", "ia_clave_claude", "sinc_clave", "rss_sinc_clave", "pod_sinc_clave")

	/** Contraseña de un catálogo (por su dirección). */
	fun deCatalogo(url: String) = "catalogo:$url"

	/** Contraseña de un podcast privado (por la dirección de su fuente). */
	fun dePodcast(url: String) = "podcast:$url"

	private fun ajustes(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)

	fun enCopiaAndroid(c: Context) = ajustes(c).getBoolean(OPCION_COPIA_ANDROID, false)

	private fun archivo(c: Context, enCopia: Boolean): SharedPreferences =
		c.applicationContext.getSharedPreferences(if (enCopia) ARCHIVO_CON_COPIA else ARCHIVO_PRIVADO, Context.MODE_PRIVATE)

	private fun prefs(c: Context) = archivo(c, enCopiaAndroid(c))

	fun leer(c: Context, clave: String): String = prefs(c).getString(clave, "").orEmpty()

	fun guardar(c: Context, clave: String, valor: String) {
		val e = prefs(c).edit()
		if (valor.isBlank()) e.remove(clave) else e.putString(clave, valor)
		e.apply()
	}

	/** Todas las claves guardadas (para las copias de seguridad de la app). */
	fun todos(c: Context): Map<String, String> = prefs(c).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

	/** Pone exactamente estas claves (al restaurar una copia). */
	fun reemplazarTodos(c: Context, claves: Map<String, String>) {
		archivo(c, !enCopiaAndroid(c)).edit().clear().commit()
		val e = prefs(c).edit().clear()
		for ((k, v) in claves) if (v.isNotBlank()) e.putString(k, v)
		e.commit()
	}

	/** Si la opción cambió (al restaurar los ajustes), las claves pasan al archivo que toca. */
	fun reubicar(c: Context) {
		if (archivo(c, !enCopiaAndroid(c)).all.isNotEmpty()) cambiarCopiaAndroid(c, enCopiaAndroid(c))
	}

	/**
	 * Cambia si las claves van en la copia de seguridad de Android: las pasa al
	 * archivo que corresponde y borra el otro. Se llama antes de guardar la opción.
	 */
	fun cambiarCopiaAndroid(c: Context, si: Boolean) {
		val origen = archivo(c, !si)
		val destino = archivo(c, si)
		val e = destino.edit()
		for ((k, v) in origen.all) if (v is String) e.putString(k, v)
		e.commit()
		origen.edit().clear().commit()
	}

	/** Para los campos de Ajustes (EditTextPreference): leen y guardan aquí, no en los ajustes normales. */
	fun almacen(c: Context): PreferenceDataStore = object : PreferenceDataStore() {
		override fun getString(key: String, defValue: String?): String? = leer(c, key).ifEmpty { defValue }
		override fun putString(key: String, value: String?) = guardar(c, key, value.orEmpty())
	}

	/**
	 * Una sola vez (1.0.10): saca las claves y contraseñas de donde estaban (los
	 * ajustes normales, la lista de catálogos y la base de datos de podcasts).
	 */
	fun migrar(c: Context) {
		val p = ajustes(c)
		if (p.getBoolean("secretos_apartados", false)) return
		val e = p.edit()
		for (k in CLAVES_AJUSTES) {
			p.getString(k, null)?.takeIf { it.isNotBlank() }?.let { guardar(c, k, it) }
			e.remove(k)
		}
		// Catálogos: la contraseña sale de la lista guardada
		p.getString("fuentes_opds", null)?.let { texto ->
			runCatching {
				val lista = org.json.JSONArray(texto)
				for (i in 0 until lista.length()) {
					val o = lista.getJSONObject(i)
					val clave = o.optString("clave")
					if (clave.isNotBlank()) guardar(c, deCatalogo(o.optString("url")), clave)
					o.remove("clave")
				}
				e.putString("fuentes_opds", lista.toString())
			}
		}
		// Podcasts privados: la contraseña sale de la base de datos
		runCatching { com.xebolax.lector.podcast.PodcastsBaseDatos.obtener(c).apartarClaves() }
		e.putBoolean("secretos_apartados", true).commit()
	}
}
