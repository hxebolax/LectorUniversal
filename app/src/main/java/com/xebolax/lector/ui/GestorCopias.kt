package com.xebolax.lector.ui

import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.format.Formatter
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.xebolax.lector.BuildConfig
import com.xebolax.lector.R
import com.xebolax.lector.copias.Cifrado
import com.xebolax.lector.copias.CopiaSeguridad
import com.xebolax.lector.copias.CopiaSeguridad.Parte
import com.xebolax.lector.datos.BaseDatos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gestor de copias de seguridad (Ajustes, Copias de seguridad):
 *
 * - Crear: se elige qué guardar (cada parte con lo que ocupa), si se protege con
 *   contraseña (obligatoria si van las claves) y dónde guardar el archivo.
 * - Restaurar: se abre la copia (con su contraseña si la tiene), se ve qué
 *   lleva, de cuándo y de qué móvil, se elige qué recuperar y se confirma. Antes
 *   se guarda cómo estaba todo, para poder deshacerlo. Al terminar, la app se
 *   reinicia.
 * - Deshacer la última restauración.
 * - Las copias antiguas en JSON (hasta la 1.0.9) se siguen pudiendo importar.
 *
 * Nada sale del móvil salvo el archivo que el usuario guarda donde elige.
 * Se crea con el fragmento de Ajustes (registra la elección de archivos).
 */
class GestorCopias(private val fragmento: Fragment) {

	private var partesElegidas: Set<Parte> = emptySet()
	private var contrasenaNueva: String? = null

	private val actividad get() = fragmento.activity as? AppCompatActivity
	private fun texto(id: Int, vararg args: Any) = fragmento.getString(id, *args)

	/** Por qué falló, en lenguaje claro (en el idioma de la app); nunca el texto técnico de la excepción. */
	private fun motivo(e: Exception): String = when {
		e is com.xebolax.lector.datos.ConTextoUsuario -> e.message.orEmpty()
		e is java.io.IOException && e.message?.contains("ENOSPC") == true -> texto(R.string.cj_sin_espacio)
		else -> ""
	}

	private val guardarEn = fragmento.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
		uri?.let { guardar(it) }
	}

	private val abrirDe = fragmento.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		uri?.let { abrir(it, null) }
	}

	// --- Crear ---------------------------------------------------------------------------------

	fun crear() {
		val a = actividad ?: return
		a.lifecycleScope.launch {
			val tamanos = withContext(Dispatchers.IO) { CopiaSeguridad.tamanos(a) }
			// Solo lo que tiene algo que guardar (la biblioteca y los ajustes siempre)
			val disponibles = Parte.entries.filter { it == Parte.BIBLIOTECA || it == Parte.AJUSTES || (tamanos[it] ?: 0L) > 0 }
			elegirPartes(texto(R.string.copia_crear), texto(R.string.copia_elige_guardar), disponibles, tamanos, texto(R.string.siguiente)) { elegidas ->
				partesElegidas = elegidas
				pedirContrasenaNueva(Parte.CLAVES in elegidas) { contrasena ->
					contrasenaNueva = contrasena
					val fecha = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(Date())
					guardarEn.launch(texto(R.string.copia_nombre_archivo, fecha) + "." + CopiaSeguridad.EXTENSION)
				}
			}
		}
	}

	private fun guardar(uri: Uri) {
		val a = actividad ?: return
		val partes = partesElegidas
		val contrasena = contrasenaNueva
		contrasenaNueva = null
		a.lifecycleScope.launch {
			val avance = Avance()
			try {
				a.conEspera(texto(R.string.copia_crear), texto(R.string.copia_guardando_inicio), avance) {
					withContext(Dispatchers.IO) {
						a.contentResolver.openOutputStream(uri, "wt")?.buffered()?.use { salida ->
							CopiaSeguridad.crear(a, salida, partes, contrasena, { avance.decir(it) })
						} ?: error(texto(R.string.copia_no_se_puede_escribir))
					}
				}
				val tamano = withContext(Dispatchers.IO) {
					runCatching { a.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
				}
				Sonidos.sonar(a, Sonidos.Tipo.LISTO)
				a.mensaje(texto(R.string.copia_crear), buildString {
					append(texto(R.string.copia_guardada, Formatter.formatShortFileSize(a, tamano)))
					append("\n\n").append(texto(R.string.copia_lleva, partes.sortedBy { it.ordinal }.joinToString(", ") { texto(it.nombre) }))
					append("\n\n").append(texto(if (contrasena != null) R.string.copia_con_contrasena else R.string.copia_sin_contrasena_aviso))
				})
			} catch (e: CancellationException) {
				borrarDocumento(uri)
				throw e
			} catch (e: Exception) {
				borrarDocumento(uri)
				a.mensaje(texto(R.string.copia_crear), texto(R.string.copia_error_guardar, motivo(e)))
			}
		}
	}

	/** Si la copia no se termina, no se deja un archivo a medias. */
	private fun borrarDocumento(uri: Uri) {
		val a = actividad ?: return
		runCatching { android.provider.DocumentsContract.deleteDocument(a.contentResolver, uri) }
	}

	/**
	 * La contraseña de una copia nueva, escrita dos veces. Obligatoria si la copia
	 * lleva claves; si no, se puede guardar sin ella.
	 */
	private fun pedirContrasenaNueva(obligatoria: Boolean, alTerminar: (String?) -> Unit) {
		val a = actividad ?: return
		val m = dp(a, 24)
		val (cUna, una) = a.campoTexto(texto(R.string.copia_contrasena))
		val (cOtra, otra) = a.campoTexto(texto(R.string.copia_repetir_contrasena))
		// Cada «Mostrar» dice de qué campo es (dos botones iguales no se distinguen con TalkBack)
		for ((caja, campo, mostrar) in listOf(Triple(cUna, una, R.string.copia_mostrar_contrasena), Triple(cOtra, otra, R.string.copia_mostrar_repetida))) {
			campo.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
			caja.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
			caja.endIconContentDescription = texto(mostrar)
		}
		val vista = LinearLayout(a).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(m, m / 2, m, 0)
			addView(TextView(a).apply {
				text = texto(if (obligatoria) R.string.copia_contrasena_obligatoria else R.string.copia_contrasena_opcional)
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
				setPadding(0, 0, 0, m / 2)
			})
			addView(cUna)
			addView(cOtra)
		}
		val constructor = MaterialAlertDialogBuilder(a)
			.setTitle(R.string.copia_proteger)
			.setView(ScrollView(a).apply { isFocusable = false; addView(vista) })
			.setPositiveButton(R.string.guardar, null)
			.setNegativeButton(R.string.cancelar, null)
		if (!obligatoria) constructor.setNeutralButton(R.string.copia_sin_contrasena) { _, _ -> alTerminar(null) }
		val dialogo = constructor.show()
		// Guardar comprueba antes de cerrar: si algo no vale, el error se dice en su campo
		dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
			val clave = una.text.toString()
			(cUna as? TextInputLayout)?.error = null
			(cOtra as? TextInputLayout)?.error = null
			when {
				clave.length < 8 -> {
					(cUna as? TextInputLayout)?.error = texto(R.string.copia_contrasena_corta)
					una.requestFocus()
				}
				clave != otra.text.toString() -> {
					(cOtra as? TextInputLayout)?.error = texto(R.string.copia_contrasenas_distintas)
					otra.requestFocus()
				}
				else -> {
					dialogo.dismiss()
					alTerminar(clave)
				}
			}
		}
	}

	// --- Elegir partes -------------------------------------------------------------------------

	/**
	 * Lista de casillas, una por parte: su nombre, qué lleva y lo que ocupa. Cada
	 * casilla es una sola parada de TalkBack que dice todo y si está marcada.
	 */
	private fun elegirPartes(titulo: String, explicacion: String, partes: List<Parte>, tamanos: Map<Parte, Long>, boton: String, alElegir: (Set<Parte>) -> Unit) {
		val a = actividad ?: return
		val m = dp(a, 24)
		val lista = LinearLayout(a).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(m, m / 2, m, 0)
			addView(TextView(a).apply {
				text = explicacion
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
				setPadding(0, 0, 0, m / 2)
			})
		}
		val casillas = partes.map { p ->
			val detalle = buildString {
				append(texto(p.descripcion))
				val t = tamanos[p] ?: 0L
				if (t > 0 && p != Parte.AJUSTES && p != Parte.CLAVES) append(" · ").append(Formatter.formatShortFileSize(a, t))
			}
			val rotulo = SpannableStringBuilder(texto(p.nombre)).append("\n").also { s ->
				val desde = s.length
				s.append(detalle)
				s.setSpan(RelativeSizeSpan(0.85f), desde, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			}
			p to MaterialCheckBox(a).apply {
				text = rotulo
				isChecked = true
				minHeight = dp(a, 56)
				setPadding(dp(a, 8), dp(a, 8), 0, dp(a, 8))
				lista.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
			}
		}
		val dialogo = MaterialAlertDialogBuilder(a)
			.setTitle(titulo)
			.setView(ScrollView(a).apply { isFocusable = false; addView(lista) })
			.setPositiveButton(boton, null)
			.setNegativeButton(R.string.cancelar, null)
			.show()
		dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
			val elegidas = casillas.filter { it.second.isChecked }.map { it.first }.toSet()
			if (elegidas.isEmpty()) return@setOnClickListener a.avisar(texto(R.string.copia_elige_algo), mostrar = true)
			dialogo.dismiss()
			alElegir(elegidas)
		}
	}

	// --- Restaurar -----------------------------------------------------------------------------

	fun restaurar() = abrirDe.launch(arrayOf("*/*"))

	private fun pedirContrasena(alEscribir: (String) -> Unit) {
		val a = actividad ?: return
		val m = dp(a, 24)
		val (caja, campo) = a.campoTexto(texto(R.string.copia_contrasena))
		campo.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
		(caja as? TextInputLayout)?.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
		MaterialAlertDialogBuilder(a)
			.setTitle(R.string.copia_protegida)
			.setMessage(R.string.copia_escribe_contrasena)
			.setView(LinearLayout(a).apply { setPadding(m, 0, m, 0); addView(caja) })
			.setPositiveButton(R.string.abrir) { _, _ -> alEscribir(campo.text.toString()) }
			.setNegativeButton(R.string.cancelar, null)
			.show()
		campo.requestFocus()
	}

	/** Abre una copia: la antigua en JSON, o la nueva (pidiendo la contraseña si la tiene). */
	private fun abrir(uri: Uri, contrasena: String?) {
		val a = actividad ?: return
		a.lifecycleScope.launch {
			val tipo = withContext(Dispatchers.IO) {
				runCatching {
					a.contentResolver.openInputStream(uri)?.use { e ->
						if (CopiaSeguridad.esCopia(e)) "nueva" else null
					} ?: a.contentResolver.openInputStream(uri)?.use { e -> if (e.read().toChar() == '{') "json" else null }
				}.getOrNull()
			}
			when (tipo) {
				"json" -> return@launch importarAntigua(uri)
				null -> return@launch a.mensaje(texto(R.string.copia_restaurar), texto(R.string.copia_no_es_copia))
			}
			val info = try {
				a.conEspera(texto(R.string.copia_restaurar), texto(if (contrasena != null) R.string.copia_comprobando_contrasena else R.string.copia_abriendo)) {
					withContext(Dispatchers.IO) { a.contentResolver.openInputStream(uri)!!.buffered().use { CopiaSeguridad.info(it, contrasena) } }
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: CopiaSeguridad.NecesitaContrasena) {
				return@launch pedirContrasena { abrir(uri, it) }
			} catch (e: Cifrado.ContrasenaIncorrecta) {
				return@launch a.mensaje(texto(R.string.copia_restaurar), texto(R.string.copia_contrasena_incorrecta)) { pedirContrasena { abrir(uri, it) } }
			} catch (e: Exception) {
				return@launch a.mensaje(texto(R.string.copia_restaurar), texto(R.string.copia_error_abrir, motivo(e)))
			}
			mostrarInfo(uri, info, contrasena)
		}
	}

	private fun descripcion(info: CopiaSeguridad.Info): String {
		val a = actividad ?: return ""
		val fecha = DateUtils.formatDateTime(a, info.fecha, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME)
		return buildString {
			append(texto(R.string.copia_info, fecha, info.dispositivo.ifBlank { "?" }, info.version))
			append(" ").append(texto(if (info.cifrada) R.string.copia_info_cifrada else R.string.copia_info_sin_cifrar))
			append("\n\n").append(texto(R.string.copia_lleva, info.partes.joinToString(", ") { texto(it.nombre) }))
			if (info.versionCode > BuildConfig.VERSION_CODE) append("\n\n").append(texto(R.string.copia_mas_nueva))
		}
	}

	private fun mostrarInfo(uri: Uri, info: CopiaSeguridad.Info, contrasena: String?) {
		val a = actividad ?: return
		MaterialAlertDialogBuilder(a)
			.setTitle(R.string.copia_restaurar)
			.setMessage(descripcion(info))
			.setPositiveButton(R.string.copia_elegir_que) { _, _ ->
				elegirPartes(texto(R.string.copia_restaurar), texto(R.string.copia_elige_restaurar), info.partes, info.tamanos, texto(R.string.siguiente)) { elegidas ->
					confirmarYRestaurar(uri, contrasena, elegidas, deshacer = false)
				}
			}
			.setNegativeButton(R.string.cancelar, null)
			.show()
	}

	private fun confirmarYRestaurar(uri: Uri, contrasena: String?, partes: Set<Parte>, deshacer: Boolean) {
		val a = actividad ?: return
		val lista = partes.sortedBy { it.ordinal }.joinToString(", ") { texto(it.nombre) }
		MaterialAlertDialogBuilder(a)
			.setTitle(if (deshacer) R.string.copia_deshacer else R.string.copia_restaurar)
			.setMessage(texto(if (deshacer) R.string.copia_confirmar_deshacer else R.string.copia_confirmar_restaurar, lista))
			.setPositiveButton(if (deshacer) R.string.copia_deshacer_boton else R.string.copia_restaurar_boton) { _, _ -> ejecutar(uri, contrasena, partes, deshacer) }
			.setNegativeButton(R.string.cancelar, null)
			.show()
	}

	private fun ejecutar(uri: Uri, contrasena: String?, partes: Set<Parte>, deshacer: Boolean) {
		val a = actividad ?: return
		// Nada debe sonar ni escribir mientras se sustituyen los datos
		com.xebolax.lector.audio.ControlAudio.detener()
		com.xebolax.lector.voz.LectorVoz.detener()
		a.lifecycleScope.launch {
			val avance = Avance()
			try {
				a.conEspera(texto(if (deshacer) R.string.copia_deshacer else R.string.copia_restaurar), texto(R.string.copia_comprobando), avance) {
					withContext(Dispatchers.IO) {
						// Antes, cómo está todo ahora (para poder deshacerlo)
						if (!deshacer) {
							avance.decir(texto(R.string.copia_guardando_actual))
							CopiaSeguridad.guardarCopiaAnterior(a)
						}
						a.contentResolver.openInputStream(uri)!!.buffered().use { CopiaSeguridad.restaurar(a, it, contrasena, partes) { t -> avance.decir(t) } }
						if (deshacer) CopiaSeguridad.copiaAnterior(a).delete()
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Cifrado.ContrasenaIncorrecta) {
				return@launch a.mensaje(texto(R.string.copia_restaurar), texto(R.string.copia_contrasena_incorrecta))
			} catch (e: Exception) {
				return@launch a.mensaje(texto(R.string.copia_restaurar), texto(R.string.copia_error_restaurar, motivo(e)))
			}
			Sonidos.sonar(a, Sonidos.Tipo.LISTO)
			a.mensaje(texto(if (deshacer) R.string.copia_deshacer else R.string.copia_restaurar), texto(R.string.copia_restaurada)) { reiniciar(a, Parte.AJUSTES in partes) }
		}
	}

	/** Vuelve a abrir la app desde la Portada, con los datos restaurados (y su idioma). */
	private fun reiniciar(a: AppCompatActivity, ajustes: Boolean) {
		if (ajustes) {
			val idioma = PreferenceManager.getDefaultSharedPreferences(a).getString("idioma_app", null)
			if (idioma != null) AppCompatDelegate.setApplicationLocales(if (idioma.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(idioma))
		}
		a.startActivity(Intent(a, PortadaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
		Runtime.getRuntime().exit(0)
	}

	// --- Deshacer ------------------------------------------------------------------------------

	fun hayQueDeshacer(): Boolean = actividad?.let { CopiaSeguridad.copiaAnterior(it).exists() } ?: false

	fun fechaDeshacer(): String {
		val a = actividad ?: return ""
		return DateUtils.formatDateTime(a, CopiaSeguridad.copiaAnterior(a).lastModified(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME)
	}

	fun deshacer() {
		val a = actividad ?: return
		val f = CopiaSeguridad.copiaAnterior(a)
		if (!f.exists()) return a.avisar(texto(R.string.copia_nada_que_deshacer))
		val uri = Uri.fromFile(f)
		a.lifecycleScope.launch {
			val info = runCatching { withContext(Dispatchers.IO) { f.inputStream().buffered().use { CopiaSeguridad.info(it, null) } } }.getOrNull()
				?: return@launch a.mensaje(texto(R.string.copia_deshacer), texto(R.string.copia_nada_que_deshacer))
			confirmarYRestaurar(uri, null, info.partes.toSet(), deshacer = true)
		}
	}

	// --- Copias antiguas (JSON, hasta la 1.0.9) ------------------------------------------------

	private fun importarAntigua(uri: Uri) {
		val a = actividad ?: return
		a.confirmar(texto(R.string.confirmar_importar), texto(R.string.importar)) {
			a.lifecycleScope.launch {
				val ok = a.conEspera(texto(R.string.copia_restaurar)) {
					withContext(Dispatchers.IO) {
						runCatching {
							val texto = a.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("vacío")
							val copia = JSONObject(texto)
							BaseDatos.obtener(a).importar(copia.getJSONObject("biblioteca"))
							copia.optString("noticias_opml").takeIf { it.isNotBlank() }?.let { com.xebolax.lector.rss.Rss.importarOpml(a, it) }
							copia.optString("podcasts_opml").takeIf { it.isNotBlank() }?.let { opml ->
								val db = com.xebolax.lector.podcast.PodcastsBaseDatos.obtener(a)
								com.xebolax.lector.podcast.Podcasts.leerOpml(a, opml).forEach { (titulo, url, carpeta) -> db.anadirPodcast(url, titulo.ifBlank { url }, carpeta = carpeta) }
							}
							copia.optJSONObject("ajustes")?.let { o ->
								val editor = PreferenceManager.getDefaultSharedPreferences(a).edit()
								for (clave in o.keys()) {
									when (val v = o.get(clave)) {
										is Boolean -> editor.putBoolean(clave, v)
										is Int -> editor.putInt(clave, v)
										is Long -> editor.putLong(clave, v)
										is Double -> editor.putFloat(clave, v.toFloat())
										is org.json.JSONArray -> editor.putStringSet(clave, List(v.length()) { v.getString(it) }.toSet())
										else -> editor.putString(clave, v.toString())
									}
								}
								editor.apply()
							}
						}.isSuccess
					}
				}
				a.avisar(texto(if (ok) R.string.copia_importada else R.string.error_importar))
			}
		}
	}
}
