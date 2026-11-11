package com.xebolax.lector.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.appcompat.app.AppCompatActivity
import com.xebolax.lector.R
import com.xebolax.lector.compartidos.Compartidos
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Estado
import com.xebolax.lector.datos.Libro
import com.xebolax.lector.datos.Tipos
import com.xebolax.lector.datos.claveTipo
import com.xebolax.lector.formatos.Formato
import com.xebolax.lector.lectura.Abiertos
import java.util.Date

/**
 * Acciones de lo que hay en Compartidos (menú al mantener pulsado y acciones
 * de TalkBack): abrir, leer en voz alta, pasarlo a la biblioteca, marcarlo
 * como leído, abrir el enlace original, compartir, renombrar y borrar.
 */
object AccionesCompartido {

	fun acciones(a: AppCompatActivity, l: Libro): List<Accion> {
		val db = BaseDatos.obtener(a)
		val web = l.origen.takeIf { it.startsWith("http") }
		val lista = mutableListOf<Accion>(a.getString(R.string.abrir) to { Abiertos.abrirPantalla(a, l) })
		if (!l.audio) lista += a.getString(R.string.leer_en_voz_alta) to { AccionesLibro.leerEnVozAlta(a, l) }
		// Su propia voz (lo que no cambie sigue a la voz de Compartidos)
		if (!l.audio) lista += a.getString(R.string.voz_de_esto) to { com.xebolax.lector.voz.VozPerfilActivity.abrirLibro(a, l.id, l.tituloVisible) }
		lista += a.getString(R.string.comp_mover_biblioteca) to { moverABiblioteca(a, l) }
		lista += if (l.estado == Estado.TERMINADO) a.getString(R.string.comp_marcar_no_leido) to {
			db.cambiarEstado(l.id, Estado.NUEVO)
			a.avisar(a.getString(R.string.comp_marcado_no_leido))
		} else a.getString(R.string.comp_marcar_leido) to {
			db.cambiarEstado(l.id, Estado.TERMINADO)
			a.avisar(a.getString(R.string.comp_marcado_leido))
		}
		if (web != null) {
			lista += a.getString(R.string.abrir_navegador) to {
				runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web))) }.onFailure { a.avisar(a.getString(R.string.no_disponible)) }
			}
			lista += a.getString(R.string.compartir) to {
				a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, web)
					.putExtra(Intent.EXTRA_SUBJECT, l.tituloVisible), a.getString(R.string.compartir)))
			}
			lista += a.getString(R.string.comp_copiar_enlace) to {
				(a.getSystemService(AppCompatActivity.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(l.tituloVisible, web))
				a.avisar(a.getString(R.string.comp_enlace_copiado))
			}
		} else lista += a.getString(R.string.compartir) to { AccionesLibro.compartir(a, l) }
		lista += a.getString(R.string.renombrar) to {
			a.pedirTexto(a.getString(R.string.renombrar), a.getString(R.string.titulo), l.tituloVisible) { t ->
				if (t.isNotBlank()) {
					db.renombrar(l.id, t.trim(), l.autor)
					a.avisar(a.getString(R.string.guardado))
				}
			}
		}
		lista += a.getString(R.string.informacion) to { informacion(a, l) }
		lista += a.getString(R.string.borrar) to {
			a.confirmar(a.getString(R.string.comp_confirmar_borrar, l.tituloVisible), a.getString(R.string.borrar)) {
				Compartidos.borrar(a, l)
				a.avisar(a.getString(R.string.comp_borrado))
			}
		}
		return lista
	}

	/** Pasarlo a la biblioteca: se elige el tipo (con el que le toca ya marcado) y la carpeta. */
	fun moverABiblioteca(a: AppCompatActivity, l: Libro) {
		val db = BaseDatos.obtener(a)
		AccionesLibro.elegirTipo(a, l.claveTipo, a.getString(R.string.comp_que_es)) { tipo ->
			AccionesLibro.elegirCarpeta(a, a.getString(R.string.comp_a_que_carpeta), null, tipo.ifBlank { l.claveTipo }) { carpeta ->
				// Si la carpeta es de un tipo, manda la carpeta
				val final = carpeta?.let { db.carpeta(it)?.tipo }?.takeIf { it.isNotEmpty() } ?: tipo
				db.moverABiblioteca(l.id, final, carpeta)
				val donde = listOf(AccionesLibro.nombreTipo(a, final.ifBlank { l.claveTipo })) +
					listOfNotNull(carpeta?.let { db.rutaCarpeta(it) }?.takeIf { it.isNotBlank() })
				a.avisar(a.getString(R.string.comp_movido_biblioteca, donde.joinToString(" › ")))
			}
		}
	}

	private fun informacion(a: AppCompatActivity, l: Libro) {
		val fecha = { t: Long -> if (t > 0) DateFormat.getMediumDateFormat(a).format(Date(t)) + " " + DateFormat.getTimeFormat(a).format(Date(t)) else "—" }
		val formato = runCatching { Formato.valueOf(l.formato).nombre }.getOrDefault(l.formato)
		val texto = buildString {
			appendLine(a.getString(R.string.info_titulo, l.tituloVisible))
			appendLine(a.getString(R.string.comp_info_tipo, Tipos.de(a, l.claveTipo)?.nombreSingular ?: Tipos.nombre(a, l.claveTipo)))
			appendLine(a.getString(R.string.info_formato, formato))
			if (l.origen.startsWith("http")) appendLine(a.getString(R.string.comp_info_origen, l.origen))
			if (l.tamano > 0) appendLine(a.getString(R.string.info_tamano, tamanoLegible(l.tamano)))
			appendLine(a.getString(R.string.info_progreso, l.progreso.toInt()))
			appendLine(a.getString(R.string.comp_info_recibido, fecha(l.anadido)))
			if (l.abierto > 0) appendLine(a.getString(R.string.info_abierto, fecha(l.abierto)))
		}
		a.mensaje(a.getString(R.string.informacion), texto.trim())
	}
}
