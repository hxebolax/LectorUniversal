package com.xebolax.lector.ui

import android.content.Context
import android.content.DialogInterface
import android.text.InputType
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.xebolax.lector.R
import java.util.Locale

/**
 * Una hora escrita a mano, como HH:MM: «23:00», «23.00», «23,00», «23h00»,
 * «2300», «730», «7:30» o «23» dan «23:00» o «07:30». Null si no es una hora válida.
 */
fun normalizarHora(texto: String): String? {
	val limpio = texto.trim().lowercase(Locale.ROOT).removeSuffix("h").trim()
	val partes = Regex("""^(\d{1,2})\s*[:.,h]\s*(\d{2})$""").find(limpio)?.destructured?.let { (h, m) -> h to m }
		?: Regex("""^(\d{1,2})(\d{2})$""").find(limpio)?.destructured?.let { (h, m) -> h to m }
		?: Regex("""^(\d{1,2})$""").find(limpio)?.destructured?.let { (h) -> h to "0" }
		?: return null
	val h = partes.first.toIntOrNull() ?: return null
	val m = partes.second.toIntOrNull() ?: return null
	if (h !in 0..23 || m !in 0..59) return null
	return String.format(Locale.ROOT, "%02d:%02d", h, m)
}

/**
 * Pide una hora (HH:MM). Si lo escrito no es una hora, el error se dice en el
 * campo (TalkBack lo lee), el diálogo no se cierra y lo escrito no se pierde.
 * alAceptar recibe la hora ya normalizada («23:00»).
 */
fun Context.pedirHora(titulo: String, inicial: String, conError: Boolean = false, alAceptar: (String) -> Unit) {
	val campo = TextInputEditText(this).apply {
		setText(inicial)
		setSelection(inicial.length)
		inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
	}
	val contenedor = TextInputLayout(this).apply {
		hint = getString(R.string.cj_hora_pista)
		addView(campo)
	}
	val marco = FrameLayout(this).apply {
		val margen = dp(this@pedirHora, 20)
		setPadding(margen, margen / 2, margen, 0)
		addView(contenedor)
	}
	val dialogo = MaterialAlertDialogBuilder(this)
		.setTitle(titulo)
		.setView(marco)
		.setPositiveButton(R.string.aceptar, null)
		.setNegativeButton(R.string.cancelar, null)
		.create()
	fun aceptar() {
		val hora = normalizarHora(campo.text?.toString().orEmpty())
		if (hora == null) {
			contenedor.error = getString(R.string.cj_hora_no_valida)
			campo.requestFocus()
		} else {
			alAceptar(hora)
			dialogo.dismiss()
		}
	}
	campo.setOnEditorActionListener { _, _, _ ->
		aceptar()
		true
	}
	dialogo.setOnShowListener {
		// Aceptar comprueba la hora antes de cerrar
		dialogo.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener { aceptar() }
		if (conError) contenedor.error = getString(R.string.cj_hora_no_valida)
		campo.requestFocus()
	}
	dialogo.show()
}
