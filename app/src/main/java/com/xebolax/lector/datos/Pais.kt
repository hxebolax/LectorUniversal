package com.xebolax.lector.datos

import android.content.Context
import android.telephony.TelephonyManager
import androidx.preference.PreferenceManager
import java.util.Locale

/**
 * El país del usuario, para los catálogos que van por país (los podcasts más
 * escuchados de Apple, las radios de tu país).
 *
 * Antes se sacaba solo del idioma del móvil, y muchos móviles en español de
 * América están en «Español (Estados Unidos)»: salían los podcasts de Estados
 * Unidos, todos en inglés. Ahora se mira primero el país de la tarjeta SIM y
 * de la red móvil (sin pedir permisos), después el del idioma y, si se
 * elige en Ajustes, el que diga el usuario.
 */
object Pais {

	private lateinit var app: Context

	fun iniciar(c: Context) {
		app = c.applicationContext
	}

	/** El detectado (código de dos letras en mayúsculas). */
	fun detectado(c: Context = app): String {
		val tm = c.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
		val telefono = listOfNotNull(
			runCatching { tm?.simCountryIso }.getOrNull(),
			runCatching { tm?.networkCountryIso }.getOrNull(),
		).map { it.trim().uppercase(Locale.ROOT) }.firstOrNull { it.length == 2 }
		return telefono ?: Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.ROOT) ?: "ES"
	}

	/** El de los podcasts: el elegido en Descubrir o el detectado. */
	fun podcasts(c: Context = app): String =
		PreferenceManager.getDefaultSharedPreferences(c).getString("pod_pais", "").orEmpty().takeIf { it.length == 2 } ?: detectado(c)

	fun ponerPodcasts(c: Context, codigo: String) = PreferenceManager.getDefaultSharedPreferences(c).edit().putString("pod_pais", codigo).apply()

	/** Nombre del país en el idioma de la app («México»). */
	fun nombre(codigo: String): String = Locale("", codigo).displayCountry.ifBlank { codigo }

	/** Todos los países (código y nombre), por su nombre. */
	fun todos(): List<Pair<String, String>> = Locale.getISOCountries().map { it to nombre(it) }
		.sortedWith(compareBy(java.text.Collator.getInstance()) { it.second })
}
