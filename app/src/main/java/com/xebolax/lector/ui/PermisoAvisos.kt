package com.xebolax.lector.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xebolax.lector.R

/**
 * Permiso de avisos (notificaciones, Android 13 o más), pedido en contexto: la
 * primera vez que hace falta (leer o reproducir en segundo plano, activar los
 * avisos de Noticias, descargar en Podcasts), una sola vez y explicando antes
 * para qué. Si no se da, la app sigue funcionando y lo dice.
 */
object PermisoAvisos {

	/** Ya se pidió una vez: no se vuelve a preguntar (se puede dar en Ajustes del sistema). */
	private const val PEDIDO = "permiso_avisos_pedido"

	/** ¿Falta el permiso? (Antes de Android 13 no hace falta pedirlo.) */
	fun falta(c: Context): Boolean =
		Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

	/** Si falta y no se ha pedido aún, explica para qué es ([motivo]) y lo pide. */
	fun pedir(a: ComponentActivity, @StringRes motivo: Int) {
		if (!falta(a) || a.isFinishing || a.isDestroyed) return
		val prefs = PreferenceManager.getDefaultSharedPreferences(a)
		if (prefs.getBoolean(PEDIDO, false)) return
		prefs.edit().putBoolean(PEDIDO, true).apply()
		MaterialAlertDialogBuilder(a)
			.setTitle(R.string.cj_avisos_titulo)
			.setMessage(a.getString(motivo) + "\n\n" + a.getString(R.string.cj_avisos_si_no))
			.setPositiveButton(R.string.cj_avisos_permitir) { _, _ -> lanzar(a) }
			.setNegativeButton(R.string.ahora_no) { _, _ -> a.avisar(a.getString(R.string.cj_avisos_sin_permiso)) }
			.show()
	}

	/** La petición del sistema; se registra al momento (puede ser después de crear la pantalla) y se quita al responder. */
	private fun lanzar(a: ComponentActivity) {
		if (a.isFinishing || a.isDestroyed) return
		lateinit var lanzador: ActivityResultLauncher<String>
		lanzador = a.activityResultRegistry.register("permiso_avisos", ActivityResultContracts.RequestPermission()) { concedido ->
			runCatching { lanzador.unregister() }
			if (!concedido) a.avisar(a.getString(R.string.cj_avisos_sin_permiso))
		}
		try {
			lanzador.launch(Manifest.permission.POST_NOTIFICATIONS)
		} catch (_: Exception) {
			lanzador.unregister()
		}
	}
}
