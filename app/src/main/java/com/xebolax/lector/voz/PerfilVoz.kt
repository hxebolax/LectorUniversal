package com.xebolax.lector.voz

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Preferencias
import org.json.JSONObject
import java.util.Locale

/**
 * Ajustes de voz propios de un libro o de las noticias. Lo que es null usa
 * el de Ajustes (la voz general): si no se cambia nada, el libro se lee
 * como diga Ajustes, y cambiar Ajustes le afecta.
 */
data class PerfilVoz(
	val motor: String? = null,
	val voz: String? = null,
	/** Velocidad en porcentaje (100 = normal). */
	val velocidad: Int? = null,
	val tono: Int? = null,
	/** Cambiar de voz si un párrafo está en otro idioma. */
	val segunIdioma: Boolean? = null,
	/** Pausa entre párrafos en milisegundos. */
	val pausaParrafos: Int? = null,
) {
	val vacio: Boolean get() = this == PerfilVoz()

	/** Lo escribe en el JSON (conservando lo que no es de la voz, como la velocidad del audio). */
	fun escribir(o: JSONObject): JSONObject {
		fun poner(clave: String, valor: Any?) {
			if (valor == null) o.remove(clave) else o.put(clave, valor)
		}
		poner("motor", motor)
		poner("voz", voz)
		poner("velocidad", velocidad)
		poner("tono", tono)
		poner("segun_idioma", segunIdioma)
		poner("pausa_parrafos", pausaParrafos)
		return o
	}

	// Los efectivos: los propios o, si no hay, los de Ajustes
	fun motorEfectivo(p: Preferencias): String = motor ?: p.motorVoz
	fun vozEfectiva(p: Preferencias): String = voz ?: p.voz
	fun velocidadEfectiva(p: Preferencias): Int = velocidad ?: p.velocidadVoz
	fun tonoEfectivo(p: Preferencias): Int = tono ?: p.tonoVoz
	fun segunIdiomaEfectivo(p: Preferencias): Boolean = segunIdioma ?: p.vozSegunIdioma
	fun pausaEfectiva(p: Preferencias): Int = pausaParrafos ?: p.pausaParrafos

	companion object {
		fun leer(o: JSONObject?): PerfilVoz {
			o ?: return PerfilVoz()
			fun texto(k: String) = o.optString(k, "").takeIf { it.isNotBlank() }
			fun entero(k: String) = if (o.has(k)) o.optInt(k, 0).takeIf { it > 0 || (k == "pausa_parrafos" && o.has(k)) } else null
			return PerfilVoz(
				motor = texto("motor"),
				voz = texto("voz"),
				velocidad = entero("velocidad"),
				tono = entero("tono"),
				segunIdioma = if (o.has("segun_idioma")) o.optBoolean("segun_idioma") else null,
				pausaParrafos = entero("pausa_parrafos"),
			)
		}
	}
}

/** Dónde se guarda cada perfil: en los ajustes propios del libro o, el de las noticias, en los ajustes. */
object PerfilesVoz {
	/** Id de libro que representa las noticias (los artículos no tienen id). */
	const val NOTICIAS = -2L
	private const val PREF_NOTICIAS = "voz_noticias"

	private fun json(texto: String?): JSONObject = runCatching { JSONObject(texto.orEmpty()) }.getOrDefault(JSONObject())

	fun de(c: Context, libro: Long): PerfilVoz = when {
		libro == NOTICIAS -> PerfilVoz.leer(json(PreferenceManager.getDefaultSharedPreferences(c).getString(PREF_NOTICIAS, "")))
		libro > 0 -> PerfilVoz.leer(json(BaseDatos.obtener(c).libro(libro)?.ajustes))
		else -> PerfilVoz()
	}

	fun guardar(c: Context, libro: Long, perfil: PerfilVoz) {
		when {
			libro == NOTICIAS -> {
				val p = PreferenceManager.getDefaultSharedPreferences(c)
				p.edit().putString(PREF_NOTICIAS, perfil.escribir(json(p.getString(PREF_NOTICIAS, ""))).toString()).apply()
			}
			libro > 0 -> {
				val db = BaseDatos.obtener(c)
				db.guardarAjustesLibro(libro, perfil.escribir(json(db.libro(libro)?.ajustes)))
			}
		}
	}
}

/**
 * Las voces de un motor concreto y la prueba de una voz, con un motor
 * aparte del de la lectura (para no cambiar el motor del libro que suena
 * al mirar las voces de otro).
 */
object VocesMotor {
	private val principal = Handler(Looper.getMainLooper())

	/** Con un motor (vacío = el del sistema) recién arrancado; se apaga al terminar. */
	private fun conMotor(c: Context, motor: String, hacer: (TextToSpeech?) -> Unit) {
		var tts: TextToSpeech? = null
		var hecho = false
		tts = TextToSpeech(c.applicationContext, { estado ->
			principal.post {
				if (hecho) return@post
				hecho = true
				hacer(if (estado == TextToSpeech.SUCCESS) tts else null)
			}
		}, motor.ifBlank { null })
		// Si el motor no responde, se sigue sin él
		principal.postDelayed({
			if (!hecho) {
				hecho = true
				hacer(null)
				runCatching { tts?.shutdown() }
			}
		}, 6000)
	}

	/** Voces instaladas de ese motor, ordenadas por idioma. */
	fun voces(c: Context, motor: String, alObtener: (List<Voice>) -> Unit) = conMotor(c, motor) { t ->
		val lista = runCatching { t?.voices?.toList() }.getOrNull().orEmpty()
			.filter { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
			.sortedWith(compareBy({ it.locale.getDisplayName(Locale.getDefault()) }, { it.name }))
		runCatching { t?.shutdown() }
		alObtener(lista)
	}

	/** Dice el texto con ese perfil (lo que no tenga, como en Ajustes). */
	fun probar(c: Context, perfil: PerfilVoz, texto: String) {
		val p = Preferencias(c)
		conMotor(c, perfil.motorEfectivo(p)) { t ->
			t ?: return@conMotor
			t.setSpeechRate(perfil.velocidadEfectiva(p) / 100f)
			t.setPitch(perfil.tonoEfectivo(p) / 100f)
			val nombre = perfil.vozEfectiva(p)
			runCatching { t.voices?.firstOrNull { it.name == nombre } }.getOrNull()?.let { t.voice = it }
			t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
				override fun onStart(id: String?) {}
				override fun onDone(id: String?) { principal.post { runCatching { t.shutdown() } } }
				@Deprecated("Deprecated in Java")
				override fun onError(id: String?) { principal.post { runCatching { t.shutdown() } } }
			})
			t.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "prueba_perfil")
		}
	}
}
