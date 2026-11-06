package com.xebolax.lector.lectura

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import com.xebolax.lector.datos.Preferencias

/**
 * Colores y letra del texto según Ajustes > Aspecto. "app" sigue el tema
 * claro u oscuro del sistema; "negro" es pantalla negra con texto apenas
 * visible (ahorra batería y da privacidad al escuchar con TalkBack).
 */
class Apariencia(
	val fondo: Int,
	val texto: Int,
	val enlace: Int,
	val resaltado: Int,
	val tamano: Float,
	val interlineado: Float,
	val margen: Int,
	val espacio: Int,
	val tipo: Typeface,
	val justificar: Boolean,
	/** Espacio entre letras (en ems; 0 = normal). Ayuda con baja visión y dislexia. */
	val letras: Float = 0f,
) {
	companion object {
		fun de(contexto: Context): Apariencia {
			val p = Preferencias(contexto)
			val oscuro = (contexto.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
			val (fondo, texto, enlace, resaltado) = when (p.temaLectura) {
				"claro" -> listOf(0xFFFFFFFF, 0xFF1A1A1A, 0xFF0B57D0, 0xFFFFE082)
				"oscuro" -> listOf(0xFF121212, 0xFFE6E6E6, 0xFF8AB4F8, 0xFF5C4A00)
				"sepia" -> listOf(0xFFF4ECD8, 0xFF3B2F1E, 0xFF7A3E00, 0xFFE0C28A)
				"amarillo" -> listOf(0xFF000000, 0xFFFFFF00, 0xFF00FFFF, 0xFF0000AA)
				"contraste" -> listOf(0xFFFFFFFF, 0xFF000000, 0xFF0000CC, 0xFFFFFF00)
				"blanco_negro" -> listOf(0xFF000000, 0xFFFFFFFF, 0xFF66CCFF, 0xFF444444)
				"negro" -> listOf(0xFF000000, 0xFF202020, 0xFF202020, 0xFF101010)
				else -> if (oscuro) listOf(0xFF121212, 0xFFE6E6E6, 0xFF8AB4F8, 0xFF5C4A00)
				else listOf(0xFFFFFBFE, 0xFF1C1B1F, 0xFF0B57D0, 0xFFFFE082)
			}.map { it.toInt() }
			val base = when (p.fuente) {
				"serif" -> Typeface.SERIF
				"sans" -> Typeface.SANS_SERIF
				"mono" -> Typeface.MONOSPACE
				"condensada" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
				"ligera" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
				else -> Typeface.DEFAULT
			}
			return Apariencia(
				fondo = fondo,
				texto = texto,
				enlace = enlace,
				resaltado = resaltado,
				tamano = p.tamanoLetra.toFloat(),
				interlineado = p.interlineado / 100f,
				margen = p.margen,
				espacio = p.espacioParrafos,
				tipo = if (p.negrita) Typeface.create(base, Typeface.BOLD) else base,
				justificar = p.justificar,
				letras = p.espacioLetras / 100f,
			)
		}

		/** ¿El color es oscuro? (para los iconos de la barra de estado). */
		fun esOscuro(color: Int): Boolean =
			(Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 < 128
	}
}
