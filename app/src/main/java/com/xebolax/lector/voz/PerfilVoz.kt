package com.xebolax.lector.voz

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.R
import com.xebolax.lector.datos.Preferencias
import com.xebolax.lector.ui.conEspera
import com.xebolax.lector.ui.mensaje
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Ajustes de voz propios de una sección (Biblioteca, Compartidos, Noticias) o
 * de un libro. Lo que es null se hereda: un libro, de su sección; una
 * sección, de la voz general (Ajustes, General). Si no se cambia nada, todo
 * se lee con la voz general, y cambiarla les afecta.
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

	/**
	 * Este perfil encima de [base] (lo que hereda): lo propio manda y lo que no
	 * tiene sale de la base. Si cambia de motor sin elegir voz, la de la base
	 * (que es de otro motor) no sirve: la del motor por defecto.
	 */
	fun sobre(base: PerfilVoz) = PerfilVoz(
		motor = motor ?: base.motor,
		voz = voz ?: if (motor != null && motor != base.motor) null else base.voz,
		velocidad = velocidad ?: base.velocidad,
		tono = tono ?: base.tono,
		segunIdioma = segunIdioma ?: base.segunIdioma,
		pausaParrafos = pausaParrafos ?: base.pausaParrafos,
	)

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

/**
 * Dónde se guarda cada perfil: el de un libro (o de algo de Compartidos), en
 * sus ajustes propios; el de cada sección, en los ajustes de la app. Los ids
 * negativos son las secciones.
 */
object PerfilesVoz {
	/** Ids de las secciones (los libros tienen ids positivos). */
	const val NOTICIAS = -2L
	const val COMPARTIDOS = -3L
	const val BIBLIOTECA = -4L
	private val PREFS = mapOf(NOTICIAS to "voz_noticias", COMPARTIDOS to "voz_compartidos", BIBLIOTECA to "voz_biblioteca")

	fun esSeccion(id: Long) = id in PREFS

	private fun json(texto: String?): JSONObject = runCatching { JSONObject(texto.orEmpty()) }.getOrDefault(JSONObject())

	/** El perfil propio (solo lo que se ha cambiado en él). */
	fun de(c: Context, libro: Long): PerfilVoz = when {
		esSeccion(libro) -> PerfilVoz.leer(json(PreferenceManager.getDefaultSharedPreferences(c).getString(PREFS[libro], "")))
		libro > 0 -> PerfilVoz.leer(json(BaseDatos.obtener(c).libro(libro)?.ajustes))
		else -> PerfilVoz()
	}

	/** La sección de un libro: Compartidos si está allí; si no, la Biblioteca. */
	fun seccionDe(c: Context, libro: Long): Long =
		if (libro > 0 && BaseDatos.obtener(c).libro(libro)?.compartido == true) COMPARTIDOS else BIBLIOTECA

	/** Lo que hereda: una sección, de la voz general (nada propio); un libro, de su sección. */
	fun base(c: Context, libro: Long): PerfilVoz = if (esSeccion(libro)) PerfilVoz() else de(c, seccionDe(c, libro))

	/** La voz con la que se lee: la propia sobre la de su sección (y lo demás, la general). */
	fun efectivo(c: Context, libro: Long, noticia: Boolean = false): PerfilVoz = when {
		noticia -> de(c, NOTICIAS)
		esSeccion(libro) -> de(c, libro)
		else -> de(c, libro).sobre(de(c, seccionDe(c, libro)))
	}

	fun guardar(c: Context, libro: Long, perfil: PerfilVoz) {
		when {
			esSeccion(libro) -> {
				val p = PreferenceManager.getDefaultSharedPreferences(c)
				p.edit().putString(PREFS[libro], perfil.escribir(json(p.getString(PREFS[libro], ""))).toString()).apply()
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
				val ok = estado == TextToSpeech.SUCCESS
				hacer(if (ok) tts else null)
				// Si no arranca, se apaga igualmente (si no, queda enganchado al servicio del motor)
				if (!ok) runCatching { tts?.shutdown() }
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

	/** Marca de las voces de ElevenLabs (su nombre y descripción van en las «features» de la Voice). */
	private const val NUBE = "elevenlabs:"

	/** Cómo se dice una voz en las listas: «Español (España) · nombre · sin conexión», o «Nombre, descripción · ElevenLabs». */
	fun etiqueta(c: Context, v: Voice): String {
		v.features?.firstOrNull { it.startsWith(NUBE) }?.let { return it.removePrefix(NUBE) + " · " + c.getString(R.string.el_nombre) }
		val calidad = if (v.isNetworkConnectionRequired) c.getString(R.string.voz_en_linea) else c.getString(R.string.voz_local)
		return "${v.locale.getDisplayName(Locale.getDefault())} · ${v.name} · $calidad"
	}

	/** Las voces de ElevenLabs ya pedidas (con la clave con que se pidieron): cambiar el filtro no vuelve a ir a la red. */
	@Volatile private var vocesNube: Pair<String, List<Voice>>? = null

	/** Voces de ese motor, ordenadas por idioma (con ElevenLabs, las de la cuenta del usuario). */
	fun voces(c: Context, motor: String, alObtener: (List<Voice>) -> Unit) {
		if (ElevenLabs.esMotor(motor)) return vocesDeLaNube(c, alObtener)
		vocesDelMotor(c, motor, alObtener)
	}

	/**
	 * Las voces de la cuenta de ElevenLabs. Van por red: desde una pantalla,
	 * con la ventana de espera (con Cancelar) y, si fallan, se dice por qué.
	 */
	private fun vocesDeLaNube(c: Context, alObtener: (List<Voice>) -> Unit) {
		val app = c.applicationContext
		val clave = ElevenLabs.clave(app)
		vocesNube?.takeIf { it.first == clave }?.let { return alObtener(it.second) }
		fun pedir(): List<Voice> = ElevenLabs.voces(app).map { v ->
			// Hablan muchos idiomas: se ponen en el de la app para que salgan con el filtro de idioma
			val nombre = if (v.descripcion.isBlank()) v.nombre else "${v.nombre}, ${v.descripcion}"
			Voice(v.id, Locale(Locale.getDefault().language), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, true, setOf(NUBE + nombre))
		}.also { if (it.isNotEmpty()) vocesNube = clave to it }
		val a = c as? androidx.appcompat.app.AppCompatActivity
		if (a == null) {
			Thread {
				val lista = runCatching { pedir() }.getOrDefault(emptyList())
				principal.post { alObtener(lista) }
			}.start()
			return
		}
		a.lifecycleScope.launch {
			val lista = try {
				a.conEspera(a.getString(R.string.el_nombre), a.getString(R.string.lectura_el_voces_cargando)) {
					withContext(Dispatchers.IO) { pedir() }
				}
			} catch (e: CancellationException) {
				// Cancelado con el botón: la lista queda solo con «por defecto»; si se cerró la pantalla, se para
				ensureActive()
				emptyList()
			} catch (e: Exception) {
				a.mensaje(a.getString(R.string.el_nombre), a.getString(R.string.lectura_el_voces_error, ElevenLabs.mensaje(a, e)))
				emptyList()
			}
			alObtener(lista)
		}
	}

	private fun vocesDelMotor(c: Context, motor: String, alObtener: (List<Voice>) -> Unit) = conMotor(c, motor) { t ->
		// Pedir las voces es una llamada al motor que puede tardar (un motor ocupado o actualizándose):
		// fuera del hilo principal, para que la pantalla nunca se quede colgada
		Thread {
			val lista = runCatching { t?.voices?.toList() }.getOrNull().orEmpty()
				.filter { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
				.sortedWith(compareBy({ it.locale.getDisplayName(Locale.getDefault()) }, { it.name }))
			runCatching { t?.shutdown() }
			principal.post { alObtener(lista) }
		}.start()
	}

	// --- Pruebas: solo una a la vez y con el turno de audio (la lectura se atenúa o se calla, como con TalkBack) ---

	private var motorPrueba: TextToSpeech? = null
	private var reproductorPrueba: android.media.MediaPlayer? = null
	/** Cada prueba nueva invalida las anteriores que aún no han empezado a sonar. */
	private var numeroPrueba = 0
	private var focoPrueba: Any? = null

	private fun audio(c: Context) = c.applicationContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

	/** Para lo que esté sonando de una prueba anterior y suelta su turno de audio. */
	private fun pararPrueba(c: Context) {
		motorPrueba?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
		motorPrueba = null
		reproductorPrueba?.let { runCatching { it.release() } }
		reproductorPrueba = null
		soltarFoco(c)
	}

	private fun pedirFoco(c: Context) {
		soltarFoco(c)
		val am = audio(c)
		if (android.os.Build.VERSION.SDK_INT >= 26) {
			val peticion = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
				.setAudioAttributes(
					android.media.AudioAttributes.Builder()
						.setUsage(android.media.AudioAttributes.USAGE_MEDIA)
						.setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
						.build()
				)
				.setOnAudioFocusChangeListener { }
				.build()
			focoPrueba = peticion
			runCatching { am.requestAudioFocus(peticion) }
		} else {
			@Suppress("DEPRECATION")
			runCatching { am.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) }
			focoPrueba = true
		}
	}

	private fun soltarFoco(c: Context) {
		val foco = focoPrueba ?: return
		focoPrueba = null
		val am = audio(c)
		if (android.os.Build.VERSION.SDK_INT >= 26 && foco is android.media.AudioFocusRequest) runCatching { am.abandonAudioFocusRequest(foco) }
		else @Suppress("DEPRECATION") runCatching { am.abandonAudioFocus(null) }
	}

	/** Un motor de prueba que, al terminar (o fallar o pararse), se apaga y suelta el turno de audio. */
	private fun oyentePrueba(c: Context, t: TextToSpeech) = object : android.speech.tts.UtteranceProgressListener() {
		private fun fin() {
			principal.post {
				runCatching { t.shutdown() }
				if (motorPrueba === t) {
					motorPrueba = null
					soltarFoco(c)
				}
			}
		}
		override fun onStart(id: String?) {}
		override fun onDone(id: String?) = fin()
		@Deprecated("Deprecated in Java")
		override fun onError(id: String?) = fin()
		override fun onError(id: String?, codigo: Int) = fin()
		override fun onStop(id: String?, interrumpida: Boolean) = fin()
	}

	/** Dice el texto con ese perfil (lo que no tenga, como en Ajustes). Para la prueba anterior si aún suena. */
	fun probar(c: Context, perfil: PerfilVoz, texto: String) {
		val app = c.applicationContext
		val p = Preferencias(app)
		pararPrueba(app)
		val numero = ++numeroPrueba
		if (ElevenLabs.esMotor(perfil.motorEfectivo(p))) return probarNube(app, perfil, texto, numero)
		conMotor(app, perfil.motorEfectivo(p)) { t ->
			t ?: return@conMotor
			// Otra prueba empezó mientras arrancaba este motor: no suena
			if (numero != numeroPrueba) {
				runCatching { t.shutdown() }
				return@conMotor
			}
			motorPrueba = t
			t.setSpeechRate(perfil.velocidadEfectiva(p) / 100f)
			t.setPitch(perfil.tonoEfectivo(p) / 100f)
			val nombre = perfil.vozEfectiva(p)
			t.setOnUtteranceProgressListener(oyentePrueba(app, t))
			pedirFoco(app)
			// Buscar la voz también es una llamada al motor: fuera del hilo principal
			Thread {
				if (nombre.isNotBlank()) runCatching { t.voices?.firstOrNull { it.name == nombre } }.getOrNull()?.let { runCatching { t.voice = it } }
				runCatching { t.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "prueba_perfil") }
			}.start()
		}
	}

	/** Prueba con ElevenLabs (a la velocidad y el tono elegidos); si falla, la voz del móvil dice por qué. */
	private fun probarNube(app: Context, perfil: PerfilVoz, texto: String, numero: Int) {
		val p = Preferencias(app)
		ElevenLabs.probar(app, texto, perfil.vozEfectiva(p)) { archivo, error ->
			principal.post {
				if (numero != numeroPrueba) return@post
				if (archivo != null) {
					val mp = android.media.MediaPlayer()
					reproductorPrueba = mp
					fun soltar() {
						runCatching { mp.release() }
						if (reproductorPrueba === mp) {
							reproductorPrueba = null
							soltarFoco(app)
						}
					}
					mp.setOnCompletionListener { soltar() }
					mp.setOnErrorListener { _, _, _ -> soltar(); true }
					try {
						mp.setDataSource(archivo.path)
						mp.prepare()
						mp.playbackParams = mp.playbackParams.setSpeed(perfil.velocidadEfectiva(p) / 100f).setPitch(perfil.tonoEfectivo(p) / 100f)
						pedirFoco(app)
						mp.start()
					} catch (e: Exception) {
						soltar()
					}
				} else conMotor(app, "") { t ->
					t ?: return@conMotor
					if (numero != numeroPrueba) {
						runCatching { t.shutdown() }
						return@conMotor
					}
					motorPrueba = t
					t.setOnUtteranceProgressListener(oyentePrueba(app, t))
					pedirFoco(app)
					t.speak(ElevenLabs.mensaje(app, error ?: Exception()), TextToSpeech.QUEUE_FLUSH, null, "error_nube")
				}
			}
		}
	}
}
