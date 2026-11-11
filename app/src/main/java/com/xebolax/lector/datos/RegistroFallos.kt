package com.xebolax.lector.datos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xebolax.lector.BuildConfig
import com.xebolax.lector.R
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de fallos SOLO en el móvil (sin telemetría): si la app se cierra de
 * golpe, el error se guarda en un archivo dentro de la app. Al abrirla otra vez
 * se ofrece, una sola vez, enviarlo al autor: tú lo lees antes y lo mandas tú
 * con la app que quieras (correo, GitHub, Telegram…). Nunca se envía nada solo.
 * También está en Ajustes, General, «Informar de un problema».
 */
object RegistroFallos {

	private const val OFRECIDO = "fallo_ofrecido"

	private fun archivo(c: Context) = File(c.filesDir, "fallos/ultimo.txt")

	fun iniciar(c: Context) {
		val app = c.applicationContext
		val anterior = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { hilo, error ->
			runCatching {
				val f = archivo(app)
				f.parentFile?.mkdirs()
				val traza = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
				f.writeText(datos(app) + "\n" + app.getString(R.string.cj_fallo_hilo, hilo.name) + "\n\n" + traza.take(60_000))
				// Es un fallo nuevo: se ofrecerá al abrir la app
				PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(OFRECIDO, false).commit()
			}
			anterior?.uncaughtException(hilo, error)
		}
	}

	/** Versión de la app, de Android y el modelo del móvil (nada personal). */
	private fun datos(c: Context): String {
		val fecha = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
		return "Lector Universal ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
			"Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
			c.getString(R.string.cj_fallo_movil, "${Build.MANUFACTURER} ${Build.MODEL}") + "\n" +
			c.getString(R.string.cj_fallo_fecha, fecha) + "\n"
	}

	/** Al abrir la app: si la última vez se cerró por un fallo, ofrecer enviarlo (una sola vez). */
	fun ofrecerSiHay(a: Activity) {
		val prefs = PreferenceManager.getDefaultSharedPreferences(a)
		if (!archivo(a).exists() || prefs.getBoolean(OFRECIDO, true)) return
		prefs.edit().putBoolean(OFRECIDO, true).apply()
		MaterialAlertDialogBuilder(a)
			.setTitle(R.string.fallo_titulo)
			.setMessage(R.string.fallo_mensaje)
			.setPositiveButton(R.string.fallo_ver) { _, _ -> ver(a) }
			.setNegativeButton(R.string.ahora_no, null)
			.show()
	}

	/** Informar de un problema (Ajustes): con el último fallo si lo hay, o solo con los datos del móvil. */
	fun informar(a: Activity) = ver(a)

	private fun ver(a: Activity) {
		val f = archivo(a)
		val hayFallo = f.exists()
		val texto = if (hayFallo) f.readText() else datos(a) + "\n" + a.getString(R.string.fallo_sin_fallo)
		val dialogo = MaterialAlertDialogBuilder(a)
			.setTitle(R.string.fallo_informe)
			.setMessage(texto.take(4000))
			.setPositiveButton(R.string.fallo_enviar) { _, _ -> enviar(a, texto) }
			.setNegativeButton(R.string.cerrar, null)
		// «Borrar» solo si hay un fallo guardado que borrar
		if (hayFallo) dialogo.setNeutralButton(R.string.fallo_borrar) { _, _ -> f.delete() }
		dialogo.show()
	}

	/** Lo mandas tú: correo, GitHub o la app que elijas, y puedes añadir qué estabas haciendo. */
	private fun enviar(a: Activity, texto: String) {
		val cuerpo = a.getString(R.string.fallo_que_hacias) + "\n\n\n---\n" + texto + "\n---\n" +
			a.getString(R.string.fallo_github, "https://github.com/${BuildConfig.REPO_GITHUB}/issues")
		val i = Intent(Intent.ACTION_SEND).apply {
			type = "text/plain"
			putExtra(Intent.EXTRA_SUBJECT, a.getString(R.string.fallo_asunto, BuildConfig.VERSION_NAME))
			putExtra(Intent.EXTRA_TEXT, cuerpo)
		}
		a.startActivity(Intent.createChooser(i, a.getString(R.string.fallo_enviar)))
	}
}
