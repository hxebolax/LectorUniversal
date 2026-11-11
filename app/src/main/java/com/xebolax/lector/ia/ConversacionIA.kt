package com.xebolax.lector.ia

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xebolax.lector.R
import com.xebolax.lector.ui.Avance
import com.xebolax.lector.ui.conEspera
import com.xebolax.lector.ui.copiar
import com.xebolax.lector.ui.pedirTexto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/**
 * Conversación con la IA en ventanas sencillas para TalkBack: se pregunta,
 * sale la respuesta (con Preguntar más, Copiar y Cerrar) y se puede seguir
 * preguntando sobre lo mismo (la imagen o el texto del libro se recuerdan).
 *
 * pregunta: lo que se envía la primera vez (puede llevar el texto del libro);
 * no se muestra.
 */
fun AppCompatActivity.conversarConIA(titulo: String, pregunta: String, imagen: File? = null) {
	val conversacion = mutableListOf(AsistenteIA.Mensaje(true, pregunta))
	pedirRespuesta(titulo, conversacion, imagen)
}

private fun AppCompatActivity.pedirRespuesta(titulo: String, conversacion: MutableList<AsistenteIA.Mensaje>, imagen: File?) {
	lifecycleScope.launch {
		// La ventana de espera de toda la app (con Cancelar); sus avisos («Cargando el modelo…») los dice TalkBack sin mover el foco
		val avance = Avance()
		val proveedor = AsistenteIA.elegir(this@pedirRespuesta)
		avance.decir(if (proveedor != null) getString(R.string.ia_pensando_con, AsistenteIA.nombre(this@pedirRespuesta, proveedor)) else getString(R.string.ia_pensando))
		try {
			val respuesta = conEspera(titulo, getString(R.string.ia_pensando), avance) {
				AsistenteIA.preguntar(this@pedirRespuesta, conversacion, imagen) { aviso -> avance.decir(aviso) }
			}
			conversacion += AsistenteIA.Mensaje(false, respuesta)
			mostrarRespuesta(titulo, conversacion, imagen, respuesta)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			MaterialAlertDialogBuilder(this@pedirRespuesta)
				.setTitle(titulo)
				.setMessage(e.message ?: getString(R.string.ia_sin_respuesta))
				.setPositiveButton(R.string.aceptar, null)
				.show()
		}
	}
}

private fun AppCompatActivity.mostrarRespuesta(titulo: String, conversacion: MutableList<AsistenteIA.Mensaje>, imagen: File?, respuesta: String) {
	var dialogo: AlertDialog? = null
	dialogo = MaterialAlertDialogBuilder(this)
		.setTitle(titulo)
		.setMessage(respuesta)
		.setPositiveButton(R.string.ia_preguntar_mas) { _, _ ->
			pedirTexto(getString(R.string.ia_preguntar_mas), getString(R.string.ia_tu_pregunta), multilinea = true) { texto ->
				if (texto.isBlank()) return@pedirTexto
				conversacion += AsistenteIA.Mensaje(true, texto)
				pedirRespuesta(titulo, conversacion, imagen)
			}
		}
		.setNeutralButton(R.string.copiar) { _, _ -> copiar(respuesta) }
		.setNegativeButton(R.string.cerrar, null)
		.show()
}
