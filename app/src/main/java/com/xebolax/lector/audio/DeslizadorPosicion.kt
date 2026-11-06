package com.xebolax.lector.audio

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.slider.Slider
import com.xebolax.lector.R

/**
 * Barra de posición del reproductor, accesible del todo: TalkBack la anuncia
 * como control deslizante con el tiempo («12 minutos de 45, 28 por ciento,
 * quedan 33 minutos») en lugar de un número, y al deslizar arriba o abajo (o
 * con las teclas de volumen) avanza o retrocede un 5 % y dice dónde ha
 * llegado. Por dentro es la barra normal, para quien la ve y la arrastra.
 */
class DeslizadorPosicion @JvmOverloads constructor(contexto: Context, atributos: AttributeSet? = null) : FrameLayout(contexto, atributos) {

	private val barra = Slider(contexto).apply {
		valueFrom = 0f
		valueTo = 1f
		labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
		// La accesibilidad la lleva este contenedor
		importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
	}

	var duracionMs = 0L
		private set
	var posicionMs = 0L
		private set

	/** El usuario la ha movido (arrastrando o con TalkBack): nueva posición en ms. */
	var alMover: ((Long) -> Unit)? = null
	/** Texto del estado para TalkBack: (posición, duración) → «12 minutos de 45...». */
	var describir: ((Long, Long) -> String)? = null

	init {
		addView(barra, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
		// La barra de dentro es solo visual: sin su ayudante de accesibilidad, TalkBack no la ve (diría «Valor, 0»)
		androidx.core.view.ViewCompat.setAccessibilityDelegate(barra, null)
		barra.isFocusable = false
		isFocusable = true
		importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
		contentDescription = contexto.getString(R.string.posicion_en_pista)
		barra.addOnChangeListener { _, valor, deUsuario ->
			if (deUsuario) {
				posicionMs = (valor * 1000).toLong()
				alMover?.invoke(posicionMs)
			}
		}
	}

	fun poner(posicion: Long, duracion: Long) {
		if (duracion <= 0) return
		duracionMs = duracion
		posicionMs = posicion.coerceIn(0, duracion)
		barra.valueTo = (duracion / 1000f).coerceAtLeast(1f)
		barra.value = (posicionMs / 1000f).coerceIn(0f, barra.valueTo)
	}

	private fun mover(destino: Long) {
		if (duracionMs <= 0) return
		posicionMs = destino.coerceIn(0, duracionMs)
		barra.value = (posicionMs / 1000f).coerceIn(0f, barra.valueTo)
		alMover?.invoke(posicionMs)
		// TalkBack dice el nuevo estado
		announceForAccessibility(describir?.invoke(posicionMs, duracionMs).orEmpty())
	}

	private val paso get() = (duracionMs / 20).coerceAtLeast(5_000L)

	override fun getAccessibilityClassName(): CharSequence = SeekBar::class.java.name

	override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
		super.onInitializeAccessibilityNodeInfo(info)
		info.className = SeekBar::class.java.name
		val compat = AccessibilityNodeInfoCompat.wrap(info)
		if (duracionMs > 0) {
			compat.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
				AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_FLOAT, 0f, duracionMs / 1000f, posicionMs / 1000f,
			)
			if (posicionMs < duracionMs) compat.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
			if (posicionMs > 0) compat.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
			if (android.os.Build.VERSION.SDK_INT >= 24) compat.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_PROGRESS)
		}
		compat.stateDescription = describir?.invoke(posicionMs, duracionMs) ?: ""
	}

	override fun performAccessibilityAction(accion: Int, argumentos: Bundle?): Boolean {
		when (accion) {
			AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { mover(posicionMs + paso); return true }
			AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { mover(posicionMs - paso); return true }
			android.R.id.accessibilityActionSetProgress -> {
				val segundos = argumentos?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) ?: return false
				mover((segundos * 1000).toLong())
				return true
			}
		}
		return super.performAccessibilityAction(accion, argumentos)
	}
}
