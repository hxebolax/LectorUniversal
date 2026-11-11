package com.xebolax.lector.ui

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import com.xebolax.lector.R

/**
 * Deslizador de Ajustes accesible: TalkBack dice el valor con su unidad
 * («10 segundos», «100 por ciento», «18 puntos») en vez de un porcentaje del
 * recorrido, y al deslizar arriba o abajo cambia exactamente un paso (de 5
 * en 5, de 10 en 10...), siempre en múltiplos del paso, no un 5 % del recorrido.
 */
class DeslizadorAjuste @JvmOverloads constructor(contexto: Context, atributos: AttributeSet? = null) : SeekBarPreference(contexto, atributos) {

	private val paso get() = seekBarIncrement.coerceAtLeast(1)

	/** El valor dicho con su unidad, según el ajuste. */
	fun texto(v: Int): String {
		val c = context
		return when (key) {
			"velocidad_audio", "velocidad_voz", "tono_voz", "interlineado", "vp_velocidad", "vp_tono" -> c.getString(R.string.valor_por_ciento, v)
			"salto_atras", "salto_adelante", "retroceso_reanudar" -> c.resources.getQuantityString(R.plurals.valor_segundos, v, v)
			"tamano_letra" -> c.resources.getQuantityString(R.plurals.valor_puntos, v, v)
			"pausa_parrafos", "pausa_frases", "vp_pausa" -> c.getString(R.string.valor_milisegundos, v)
			else -> v.toString()
		}
	}

	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)
		val barra = holder.findViewById(androidx.preference.R.id.seekbar) as? SeekBar ?: return
		// Una sola parada para TalkBack, la barra: «20 puntos, Tamaño de letra, control deslizante»
		// (la fila, el título y el número sin unidad no se leen aparte; la descripción va como pista)
		holder.itemView.isFocusable = false
		holder.itemView.isClickable = false
		for (id in listOf(android.R.id.title, android.R.id.summary, androidx.preference.R.id.seekbar_value)) {
			holder.findViewById(id)?.let {
				it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
				(it as? android.widget.TextView)?.labelFor = View.NO_ID
			}
		}
		barra.contentDescription = title
		ViewCompat.setAccessibilityDelegate(barra, object : AccessibilityDelegateCompat() {
			override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
				super.onInitializeAccessibilityNodeInfo(host, info)
				info.stateDescription = texto(value)
				summary?.takeIf { it.isNotBlank() }?.let { info.hintText = it }
			}

			override fun performAccessibilityAction(host: View, accion: Int, argumentos: Bundle?): Boolean {
				val nuevo = when (accion) {
					AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> (value / paso + 1) * paso
					AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> ((value - 1) / paso) * paso
					else -> return super.performAccessibilityAction(host, accion, argumentos)
				}.coerceIn(min, max)
				if (nuevo != value && callChangeListener(nuevo)) {
					value = nuevo
					host.announceForAccessibility(texto(nuevo))
				}
				return true
			}
		})
	}
}
