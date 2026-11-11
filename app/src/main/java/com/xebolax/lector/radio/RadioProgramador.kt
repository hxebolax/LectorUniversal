package com.xebolax.lector.radio

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xebolax.lector.R
import com.xebolax.lector.audio.ControlAudio
import java.text.DateFormat
import java.util.Calendar

/**
 * Despertadores con radio y grabaciones programadas: alarmas exactas del
 * sistema (las de un despertador, que Android respeta aunque el móvil esté
 * en reposo). Se vuelven a poner al reiniciar el móvil o cambiar la hora.
 */
object RadioProgramador {

	private const val ACCION_PROGRAMADA = "com.xebolax.lector.radio.PROGRAMADA"
	const val ACCION_APAGAR = "com.xebolax.lector.radio.APAGAR"
	const val ACCION_POSPONER = "com.xebolax.lector.radio.POSPONER"
	/** Cancelar el despertador pospuesto (botón de su aviso). */
	const val ACCION_CANCELAR_POSPUESTO = "com.xebolax.lector.radio.CANCELAR_POSPUESTO"
	/** Aviso «Pospuesto hasta las…» (canal propio, sin sonido). */
	private const val CANAL_POSPUESTO = "radio_pospuesto"
	private const val NOTIFICACION_POSPUESTO = 7305
	private const val EXTRA_ID = "id"
	private const val CANAL = "radio_despertador"
	const val NOTIFICACION = 7302
	/** Código de las alarmas pospuestas (no choca con los ids de las programaciones). */
	private const val POSPUESTA = 900_000

	/** Días: lunes = 1 … domingo = 64. */
	const val LABORABLES = 1 or 2 or 4 or 8 or 16
	const val FIN_DE_SEMANA = 32 or 64
	const val TODOS = 127

	/** Bit del día de la semana de un Calendar (domingo = 1 en Calendar). */
	fun bit(diaSemana: Int) = if (diaSemana == Calendar.SUNDAY) 64 else 1 shl (diaSemana - Calendar.MONDAY)

	/** Cuándo toca la próxima vez (ms), o null si no está activa. */
	fun proxima(p: Programacion, ahora: Long = System.currentTimeMillis()): Long? {
		if (!p.activa) return null
		val cal = Calendar.getInstance().apply {
			timeInMillis = ahora
			set(Calendar.HOUR_OF_DAY, p.hora)
			set(Calendar.MINUTE, p.minuto)
			set(Calendar.SECOND, 0)
			set(Calendar.MILLISECOND, 0)
		}
		if (cal.timeInMillis <= ahora) cal.add(Calendar.DAY_OF_YEAR, 1)
		if (p.dias == 0) return cal.timeInMillis
		repeat(8) {
			if (p.dias and bit(cal.get(Calendar.DAY_OF_WEEK)) != 0) return cal.timeInMillis
			cal.add(Calendar.DAY_OF_YEAR, 1)
		}
		return null
	}

	private fun alarmas(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

	private fun pendiente(c: Context, codigo: Int, id: Long, crear: Boolean): PendingIntent? =
		PendingIntent.getBroadcast(c, codigo, Intent(c, RadioReceptorAlarma::class.java).setAction(ACCION_PROGRAMADA).putExtra(EXTRA_ID, id),
			PendingIntent.FLAG_IMMUTABLE or if (crear) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)

	private fun poner(c: Context, cuando: Long, pi: PendingIntent) {
		val am = alarmas(c)
		val mostrar = PendingIntent.getActivity(c, 4, Intent(c, RadioActivity::class.java).setAction(RadioActivity.ACCION_PROGRAMAR), PendingIntent.FLAG_IMMUTABLE)
		val exacta = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
		if (exacta) am.setAlarmClock(AlarmManager.AlarmClockInfo(cuando, mostrar), pi)
		else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi)
	}

	/** Pone (o quita) las alarmas de todas las programaciones. */
	fun programar(c: Context) {
		val am = alarmas(c)
		for (p in RadioBaseDatos.obtener(c).programaciones()) {
			val cuando = proxima(p)
			if (cuando == null) pendiente(c, p.id.toInt(), p.id, crear = false)?.let { am.cancel(it); it.cancel() }
			else poner(c, cuando, pendiente(c, p.id.toInt(), p.id, crear = true)!!)
		}
		// El despertador pospuesto también (al reiniciar el móvil se pierden todas las alarmas)
		val pospuesto = pospuestoHasta(c)
		if (pospuesto > 0) {
			poner(c, pospuesto, pendientePospuesto(c))
			avisoPospuesto(c, pospuesto)
		}
	}

	/** Cuándo sonará el despertador pospuesto (ms), o 0 si no hay ninguno. */
	fun pospuestoHasta(c: Context): Long = Radio.prefs(c).getLong("radio_pospuesta_cuando", 0).takeIf { it > System.currentTimeMillis() } ?: 0L

	/** Aviso «Pospuesto hasta las 7:40» con Cancelar (si no, no había forma de quitarlo). */
	private fun avisoPospuesto(c: Context, cuando: Long) {
		if (Build.VERSION.SDK_INT >= 26) {
			c.getSystemService(NotificationManager::class.java).createNotificationChannel(
				NotificationChannel(CANAL_POSPUESTO, c.getString(R.string.audio_canal_pospuesto), NotificationManager.IMPORTANCE_LOW))
		}
		val cancelar = PendingIntent.getBroadcast(c, 9, Intent(c, RadioReceptorAlarma::class.java).setAction(ACCION_CANCELAR_POSPUESTO),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
		val abrir = PendingIntent.getActivity(c, 4, Intent(c, RadioActivity::class.java).setAction(RadioActivity.ACCION_PROGRAMAR), PendingIntent.FLAG_IMMUTABLE)
		val n = NotificationCompat.Builder(c, CANAL_POSPUESTO)
			.setSmallIcon(R.drawable.ic_alarma)
			.setContentTitle(c.getString(R.string.radio_despertador))
			.setContentText(c.getString(R.string.audio_pospuesto_hasta, DateFormat.getTimeInstance(DateFormat.SHORT).format(cuando)))
			.setContentIntent(abrir)
			.setOnlyAlertOnce(true)
			// Fijo hasta que suene o se cancele
			.setOngoing(true)
			.addAction(R.drawable.ic_detener, c.getString(R.string.cancelar), cancelar)
			.build()
		runCatching { NotificationManagerCompat.from(c).notify(NOTIFICACION_POSPUESTO, n) }
	}

	/** Cancela el despertador pospuesto: quita la alarma, sus datos y el aviso. */
	fun cancelarPospuesto(c: Context) {
		alarmas(c).cancel(pendientePospuesto(c))
		Radio.prefs(c).edit().remove("radio_pospuesta").remove("radio_pospuesta_cuando").apply()
		NotificationManagerCompat.from(c).cancel(NOTIFICACION_POSPUESTO)
		// La pestaña Programar lo dice en su línea de estado: se pone al día
		RadioBaseDatos.obtener(c).avisar()
	}

	private fun pendientePospuesto(c: Context): PendingIntent =
		PendingIntent.getBroadcast(c, POSPUESTA, Intent(c, RadioReceptorAlarma::class.java).setAction(ACCION_PROGRAMADA).putExtra(EXTRA_ID, -1L),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

	fun quitar(c: Context, id: Long) {
		pendiente(c, id.toInt(), id, crear = false)?.let { alarmas(c).cancel(it); it.cancel() }
	}

	/** «mañana a las 7:30», «el lunes a las 7:30». */
	fun cuandoLegible(c: Context, ms: Long): String {
		val hora = DateFormat.getTimeInstance(DateFormat.SHORT).format(ms)
		fun medianoche(t: Long) = Calendar.getInstance().apply {
			timeInMillis = t
			set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
		}.timeInMillis
		// Redondeado: los cambios de hora de verano no descuadran el día
		val diferencia = Math.round((medianoche(ms) - medianoche(System.currentTimeMillis())) / 86_400_000.0).toInt()
		return when (diferencia) {
			0 -> c.getString(R.string.radio_hoy_a_las, hora)
			1 -> c.getString(R.string.radio_manana_a_las, hora)
			else -> c.getString(R.string.radio_dia_a_las, java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(ms), hora)
		}
	}

	/** «De lunes a viernes», «Todos los días», «Una vez», «lunes, miércoles». */
	fun diasLegibles(c: Context, dias: Int): String = when (dias) {
		0 -> c.getString(R.string.radio_una_vez)
		TODOS -> c.getString(R.string.radio_todos_los_dias)
		LABORABLES -> c.getString(R.string.radio_laborables)
		FIN_DE_SEMANA -> c.getString(R.string.radio_fin_de_semana)
		else -> {
			val nombres = java.text.DateFormatSymbols.getInstance().weekdays
			val orden = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
			orden.filter { dias and bit(it) != 0 }.joinToString(", ") { nombres[it] }
		}
	}

	// --- Al sonar ---------------------------------------------------------------------------------

	private fun canal(c: Context) {
		if (Build.VERSION.SDK_INT < 26) return
		val ch = NotificationChannel(CANAL, c.getString(R.string.radio_canal_despertador), NotificationManager.IMPORTANCE_HIGH).apply {
			setSound(null, null)
			setBypassDnd(true)
		}
		c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
	}

	/** Suena un despertador: empieza la emisora y sale el aviso con Apagar y Posponer (y la pantalla, si está bloqueado). */
	fun despertar(c: Context, p: Programacion) {
		// Sus minutos, para cuando se posponga
		Radio.prefs(c).edit().putInt("radio_despertador_duracion", p.duracion).apply()
		Radio.escuchar(c, p.emisora, abrirPantalla = false, despertador = true)
		ControlAudio.conectar(c) {
			// Para solo al cabo de los minutos elegidos (con bajada de volumen al final)
			if (p.duracion > 0) ControlAudio.temporizador(p.duracion)
			vigilarDespertador(c)
		}
		canal(c)
		val pantalla = Intent(c, RadioReproductorActivity::class.java).putExtra(RadioReproductorActivity.EXTRA_DESPERTADOR, true)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
		val abrir = PendingIntent.getActivity(c, 5, pantalla, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
		fun accion(accion: String, codigo: Int) = PendingIntent.getBroadcast(c, codigo, Intent(c, RadioReceptorAlarma::class.java).setAction(accion),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
		val minutos = posponerMinutos(c)
		val n = NotificationCompat.Builder(c, CANAL)
			.setSmallIcon(R.drawable.ic_alarma)
			.setContentTitle(c.getString(R.string.radio_despertador))
			.setContentText(p.emisora.nombre)
			.setCategory(NotificationCompat.CATEGORY_ALARM)
			.setPriority(NotificationCompat.PRIORITY_MAX)
			.setContentIntent(abrir)
			.setFullScreenIntent(abrir, true)
			.setOngoing(true)
			.addAction(R.drawable.ic_detener, c.getString(R.string.radio_apagar), accion(ACCION_APAGAR, 6))
			.addAction(R.drawable.ic_alarma, c.getString(R.string.radio_posponer_n, minutos), accion(ACCION_POSPONER, 7))
			.build()
		runCatching { NotificationManagerCompat.from(c).notify(NOTIFICACION, n) }
	}

	fun posponerMinutos(c: Context) = Radio.prefs(c).getString("radio_posponer", "10")?.toIntOrNull() ?: 10

	/** ¿Suena todavía el despertador? (no lo que se escuche después: un audiolibro, otra emisora) */
	private fun suenaDespertador() = ControlAudio.externo?.despertador == true

	/** Quita el aviso del despertador en cuanto deja de sonar (apagado, en pausa, terminado u otra cosa en el reproductor). */
	private var vigilante: ControlAudio.Oyente? = null

	private fun vigilarDespertador(c: Context) {
		vigilante?.let { ControlAudio.desregistrar(it) }
		val app = c.applicationContext
		var visto = false
		val o = object : ControlAudio.Oyente {
			override fun alCambiar() {
				if (suenaDespertador() && ControlAudio.reproduciendo) {
					visto = true
					return
				}
				if (!visto) return
				NotificationManagerCompat.from(app).cancel(NOTIFICACION)
				ControlAudio.desregistrar(this)
				if (vigilante === this) vigilante = null
			}
		}
		vigilante = o
		ControlAudio.registrar(o)
	}

	fun apagar(c: Context) {
		// Solo si sigue sonando el despertador: el aviso olvidado no para lo que suene horas después
		if (suenaDespertador()) ControlAudio.detener()
		NotificationManagerCompat.from(c).cancel(NOTIFICACION)
	}

	/** Calla y vuelve a sonar al cabo de unos minutos (con la misma emisora). */
	fun posponer(c: Context) {
		NotificationManagerCompat.from(c).cancel(NOTIFICACION)
		if (!suenaDespertador()) return
		val e = Radio.actual
		ControlAudio.detener()
		e ?: return
		val cuando = System.currentTimeMillis() + posponerMinutos(c) * 60_000L
		Radio.prefs(c).edit().putString("radio_pospuesta", e.json().toString()).putLong("radio_pospuesta_cuando", cuando).apply()
		poner(c, cuando, pendientePospuesto(c))
		avisoPospuesto(c, cuando)
		RadioBaseDatos.obtener(c).avisar()
	}

	/** Ha llegado la hora de una programación. */
	internal fun alSonar(c: Context, id: Long) {
		if (id == -1L) {
			// Despertador pospuesto (con los minutos del despertador original)
			val pr = Radio.prefs(c)
			pr.edit().remove("radio_pospuesta_cuando").apply()
			NotificationManagerCompat.from(c).cancel(NOTIFICACION_POSPUESTO)
			// La línea de estado de Programar deja de decir «pospuesto»
			RadioBaseDatos.obtener(c).avisar()
			val e = Emisora.deJson(pr.getString("radio_pospuesta", "").orEmpty()) ?: return
			despertar(c, Programacion(0, Programacion.DESPERTADOR, e, 0, 0, 0, pr.getInt("radio_despertador_duracion", 30), true))
			return
		}
		val db = RadioBaseDatos.obtener(c)
		val p = db.programacion(id) ?: return
		if (!p.activa) return
		// Las de una sola vez se desactivan al sonar
		if (p.dias == 0) db.activarProgramacion(p.id, false)
		programar(c)
		if (p.esDespertador) despertar(c, p)
		else ServicioGrabacion.grabar(c, p.emisora, if (p.duracion > 0) p.duracion else 60)
	}
}

/** Recibe las alarmas (despertador, grabación) y los botones Apagar y Posponer. */
class RadioReceptorAlarma : BroadcastReceiver() {
	override fun onReceive(c: Context, i: Intent) {
		val app = c.applicationContext
		when (i.action) {
			RadioProgramador.ACCION_APAGAR -> RadioProgramador.apagar(app)
			RadioProgramador.ACCION_POSPONER -> RadioProgramador.posponer(app)
			RadioProgramador.ACCION_CANCELAR_POSPUESTO -> RadioProgramador.cancelarPospuesto(app)
			else -> {
				// Se espera un poco a que conecte con el reproductor antes de soltar el aviso
				val pendiente = goAsync()
				RadioProgramador.alSonar(app, i.getLongExtra("id", 0))
				android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ pendiente.finish() }, 8_000)
			}
		}
	}
}

/** Al reiniciar el móvil, actualizar la app o cambiar la hora: se vuelven a poner las alarmas. */
class RadioReceptorArranque : BroadcastReceiver() {
	override fun onReceive(c: Context, i: Intent) {
		RadioProgramador.programar(c.applicationContext)
	}
}
