package com.xebolax.lector.lectura

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import com.google.android.material.button.MaterialButton
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * Botón que TalkBack trata como un control ajustable (truco de Paperback):
 * deslizar hacia arriba o abajo con un dedo, o subir y bajar el volumen con
 * él enfocado, llama a alSubir/alBajar sin mover el foco. Sirve para cambiar
 * el elemento de navegación, la velocidad de la voz o moverse por el
 * elemento elegido. Tocarlo dos veces hace su acción normal (abrir la lista).
 *
 * TalkBack lo anuncia con su nombre, el valor actual (estado) y el papel
 * que se le dé (por ejemplo "selector"), no como "control deslizante".
 */
class ControlAjustable @JvmOverloads constructor(
	contexto: Context,
	atributos: AttributeSet? = null,
	estilo: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(contexto, atributos, estilo) {

	var alSubir: (() -> Unit)? = null
	var alBajar: (() -> Unit)? = null
	/** Lo que dice TalkBack como valor (por ejemplo "Encabezados"). */
	var valor: CharSequence? = null
	/** Papel que anuncia TalkBack en lugar de "control deslizante". */
	var rol: String? = null

	override fun getAccessibilityClassName(): CharSequence = SeekBar::class.java.name

	override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
		super.onInitializeAccessibilityNodeInfo(info)
		info.className = SeekBar::class.java.name
		val compat = AccessibilityNodeInfoCompat.wrap(info)
		// Valor siempre en el centro: se puede subir y bajar sin fin
		compat.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
			AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_INT, 0f, 2f, 1f,
		)
		compat.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
		compat.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
		if (android.os.Build.VERSION.SDK_INT >= 24) {
			compat.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_PROGRESS)
		}
		// Sin estado no diría "50 por ciento"
		compat.stateDescription = valor ?: "​"
		rol?.let { compat.roleDescription = it }
	}

	override fun performAccessibilityAction(accion: Int, argumentos: Bundle?): Boolean {
		when (accion) {
			AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
				alSubir?.invoke()
				return true
			}
			AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
				alBajar?.invoke()
				return true
			}
			android.R.id.accessibilityActionSetProgress -> {
				val valorNuevo = argumentos?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 1f) ?: 1f
				if (valorNuevo > 1f) alSubir?.invoke() else if (valorNuevo < 1f) alBajar?.invoke()
				return true
			}
		}
		return super.performAccessibilityAction(accion, argumentos)
	}
}
