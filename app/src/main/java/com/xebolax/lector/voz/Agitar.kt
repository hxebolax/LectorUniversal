package com.xebolax.lector.voz

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.xebolax.lector.datos.Preferencias
import kotlin.math.sqrt

/**
 * Detecta cuando se agita el móvil (para prolongar el temporizador de
 * apagado sin buscar la pantalla, como en los reproductores de audiolibros).
 * Solo escucha el acelerómetro mientras hace falta.
 */
class Agitar(private val contexto: Context) : SensorEventListener {

	private var alAgitar: (() -> Unit)? = null
	private var ultimo = 0L
	private var escuchando = false
	private val sensores by lazy { contexto.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

	fun empezar(accion: () -> Unit) {
		alAgitar = accion
		if (escuchando || !Preferencias(contexto).agitarTemporizador) return
		val acelerometro = sensores.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
		escuchando = sensores.registerListener(this, acelerometro, SensorManager.SENSOR_DELAY_UI)
	}

	fun parar() {
		if (escuchando) sensores.unregisterListener(this)
		escuchando = false
		alAgitar = null
	}

	override fun onSensorChanged(evento: SensorEvent) {
		val (x, y, z) = Triple(evento.values[0], evento.values[1], evento.values[2])
		val fuerza = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
		val ahora = SystemClock.elapsedRealtime()
		if (fuerza > 2.6f && ahora - ultimo > 1500) {
			ultimo = ahora
			vibrar()
			alAgitar?.invoke()
		}
	}

	override fun onAccuracyChanged(sensor: Sensor?, precision: Int) {}

	/** Vibración corta: confirma sin voz que se ha prolongado. */
	private fun vibrar() {
		runCatching {
			@Suppress("DEPRECATION")
			val v = contexto.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
			if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
			else @Suppress("DEPRECATION") v.vibrate(120)
		}
	}
}
