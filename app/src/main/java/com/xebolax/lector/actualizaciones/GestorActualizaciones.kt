package com.xebolax.lector.actualizaciones

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.xebolax.lector.BuildConfig
import com.xebolax.lector.R
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.conEspera
import com.xebolax.lector.ui.mensaje
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Interfaz de las actualizaciones: comprobación al abrir la app (como mucho
 * cada pocas horas) o a mano desde Ajustes, aviso con las novedades,
 * descarga con progreso y apertura del instalador del sistema.
 *
 * Se crea al construir la actividad (registra el regreso desde el permiso
 * «Instalar aplicaciones desconocidas»).
 */
class GestorActualizaciones(private val actividad: AppCompatActivity) {

	private companion object {
		const val CADA_MS = 6 * 60 * 60 * 1000L
		const val CLAVE_ACTIVAS = "buscar_actualizaciones"
		const val CLAVE_ULTIMA = "ultima_comprobacion"
		const val CLAVE_OMITIDA = "version_omitida"
		const val CLAVE_ANTERIOR = "version_anterior"
	}

	// Perezoso: el gestor se crea antes de que la actividad tenga contexto
	private val p by lazy { PreferenceManager.getDefaultSharedPreferences(actividad) }
	private var comprobando = false
	private var descarga: Job? = null
	/** APK descargado que espera al permiso de instalar. */
	private var pendiente: File? = null

	private val permisoInstalar = actividad.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		val apk = pendiente ?: return@registerForActivityResult
		if (puedeInstalar()) instalar(apk)
		else actividad.mensaje(actividad.getString(R.string.actualizacion), actividad.getString(R.string.actualizacion_sin_permiso))
	}

	/** Al abrir la app: aviso si se acaba de actualizar y comprobación automática. */
	fun alIniciar() {
		val anterior = p.getInt(CLAVE_ANTERIOR, 0)
		if (anterior in 1 until BuildConfig.VERSION_CODE) {
			actividad.avisar(actividad.getString(R.string.actualizada_a, BuildConfig.VERSION_NAME))
			Actualizador.carpeta(actividad).listFiles()?.forEach { it.delete() }
		}
		p.edit().putInt(CLAVE_ANTERIOR, BuildConfig.VERSION_CODE).apply()

		if (!p.getBoolean(CLAVE_ACTIVAS, true)) return
		if (System.currentTimeMillis() - p.getLong(CLAVE_ULTIMA, 0L) < CADA_MS) return
		actividad.lifecycleScope.launch {
			val v = runCatching { Actualizador.versionPublicada(actividad) }.getOrNull() ?: return@launch
			p.edit().putLong(CLAVE_ULTIMA, System.currentTimeMillis()).apply()
			if (Actualizador.esNueva(v) && v.version != p.getString(CLAVE_OMITIDA, null)) ofrecer(v, automatica = true)
		}
	}

	/** «Buscar actualizaciones ahora» (Ajustes). */
	fun comprobarAMano() {
		if (comprobando || descarga?.isActive == true) return
		comprobando = true
		actividad.lifecycleScope.launch {
			try {
				// Ventana de espera: la respuesta del servidor puede tardar
				val v = actividad.conEspera(actividad.getString(R.string.buscar_actualizaciones), actividad.getString(R.string.buscando_actualizaciones)) {
					Actualizador.versionPublicada(actividad)
				}
				p.edit().putLong(CLAVE_ULTIMA, System.currentTimeMillis()).apply()
				if (Actualizador.esNueva(v)) ofrecer(v, automatica = false)
				else actividad.mensaje(
					actividad.getString(R.string.actualizacion),
					actividad.getString(R.string.sin_actualizaciones, BuildConfig.VERSION_NAME),
				)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				// Sin conexión: se dice claro, sin el texto técnico
				val motivo = if (e is java.io.IOException) actividad.getString(R.string.sin_conexion_servidor) else e.message.orEmpty()
				actividad.mensaje(actividad.getString(R.string.actualizacion), actividad.getString(R.string.error_comprobar_actualizacion, motivo))
			} finally {
				comprobando = false
			}
		}
	}

	private fun tamano(bytes: Long) = Formatter.formatShortFileSize(actividad, bytes)

	private fun ofrecer(v: VersionPublicada, automatica: Boolean) {
		if (actividad.isFinishing) return
		val texto = buildString {
			append(actividad.getString(R.string.actualizacion_texto, BuildConfig.VERSION_NAME, v.version))
			if (v.tamano > 0) append(" ").append(actividad.getString(R.string.actualizacion_tamano, tamano(v.tamano)))
			if (v.origen.isNotBlank()) append(" ").append(actividad.getString(R.string.actualizacion_origen, v.origen))
			if (v.notas.isNotBlank()) append("\n\n").append(actividad.getString(R.string.novedades)).append("\n").append(v.notas)
		}
		val dialogo = MaterialAlertDialogBuilder(actividad)
			.setTitle(actividad.getString(R.string.actualizacion_disponible, v.version))
			.setMessage(texto)
			.setPositiveButton(R.string.actualizar) { _, _ -> descargar(v) }
			.setNegativeButton(R.string.mas_tarde, null)
		if (automatica) dialogo.setNeutralButton(R.string.omitir_version) { _, _ -> p.edit().putString(CLAVE_OMITIDA, v.version).apply() }
		dialogo.show()
	}

	private fun descargar(v: VersionPublicada) {
		val margen = (20 * actividad.resources.displayMetrics.density).toInt()
		val estado = TextView(actividad).apply {
			text = actividad.getString(R.string.descargando_actualizacion_inicio)
			// El porcentaje cambia sin parar: no se anuncia (TalkBack lo lee al tocarlo)
			accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
			setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
		}
		val barra = LinearProgressIndicator(actividad).apply {
			max = 100
			isIndeterminate = true
			importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
		}
		val vista = LinearLayout(actividad).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(margen, margen / 2, margen, 0)
			addView(estado)
			addView(barra, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = margen / 2 })
		}
		val dialogo: AlertDialog = MaterialAlertDialogBuilder(actividad)
			.setTitle(actividad.getString(R.string.descargando_version, v.version))
			.setView(vista)
			.setCancelable(false)
			.setNegativeButton(R.string.cancelar) { _, _ -> descarga?.cancel() }
			.show()
		actividad.avisar(actividad.getString(R.string.descargando_version, v.version), mostrar = false)

		var ultimoRefresco = 0L
		descarga = actividad.lifecycleScope.launch {
			try {
				val apk = Actualizador.descargar(actividad, v) { leidos, total ->
					val ahora = SystemClock.uptimeMillis()
					if (ahora - ultimoRefresco >= 300 || leidos == total) {
						ultimoRefresco = ahora
						actividad.runOnUiThread {
							if (total > 0) {
								val pct = (leidos * 100 / total).toInt()
								barra.isIndeterminate = false
								barra.progress = pct
								estado.text = actividad.getString(R.string.descargando_actualizacion, pct, tamano(leidos), tamano(total))
							} else {
								estado.text = tamano(leidos)
							}
						}
					}
				}
				dialogo.dismiss()
				actividad.avisar(actividad.getString(R.string.actualizacion_descargada))
				prepararInstalacion(apk)
			} catch (e: CancellationException) {
				dialogo.dismiss()
				actividad.avisar(actividad.getString(R.string.actualizacion_cancelada))
				throw e
			} catch (e: Exception) {
				dialogo.dismiss()
				actividad.mensaje(actividad.getString(R.string.actualizacion), actividad.getString(R.string.error_descargar_actualizacion, e.message))
			}
		}
	}

	private fun puedeInstalar() =
		Build.VERSION.SDK_INT < Build.VERSION_CODES.O || actividad.packageManager.canRequestPackageInstalls()

	/** Android 8+ pide permitir a Lector Universal instalar aplicaciones (una sola vez). */
	private fun prepararInstalacion(apk: File) {
		if (puedeInstalar()) return instalar(apk)
		pendiente = apk
		MaterialAlertDialogBuilder(actividad)
			.setTitle(R.string.actualizacion)
			.setMessage(R.string.actualizacion_pedir_permiso)
			.setPositiveButton(R.string.abrir_ajustes) { _, _ ->
				permisoInstalar.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${actividad.packageName}")))
			}
			.setNegativeButton(R.string.cancelar, null)
			.show()
	}

	private fun instalar(apk: File) {
		pendiente = null
		val uri = FileProvider.getUriForFile(actividad, "${actividad.packageName}.archivos", apk)
		val intent = Intent(Intent.ACTION_VIEW)
			.setDataAndType(uri, "application/vnd.android.package-archive")
			.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
		try {
			actividad.startActivity(intent)
		} catch (e: Exception) {
			actividad.mensaje(actividad.getString(R.string.actualizacion), actividad.getString(R.string.error_descargar_actualizacion, e.message))
		}
	}
}
