package com.xebolax.lector.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.xebolax.lector.R
import com.xebolax.lector.databinding.ActivityTextoBinding

/**
 * Un capítulo en un campo de texto de solo lectura: se puede mover el cursor
 * por caracteres, palabras o líneas, seleccionar con TalkBack o el teclado y
 * copiar. Es el "modo cursor" para quien prefiere leer así.
 */
class TextoActivity : AppCompatActivity() {

	companion object {
		const val EXTRA_TITULO = "titulo"
		const val EXTRA_CURSOR = "cursor"
		/** El texto se pasa en memoria (puede ser largo). */
		var texto: String = ""
	}

	private lateinit var b: ActivityTextoBinding

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityTextoBinding.inflate(layoutInflater)
		setContentView(b.root)
		configurarBarra(b.barra, intent.getStringExtra(EXTRA_TITULO) ?: getString(R.string.seleccionar_texto))
		b.texto.setText(texto)
		b.texto.keyListener = null
		b.texto.setTextIsSelectable(true)
		val cursor = intent.getIntExtra(EXTRA_CURSOR, 0).coerceIn(0, texto.length)
		b.texto.post {
			b.texto.requestFocus()
			b.texto.setSelection(cursor)
		}
		b.botonCopiarTodo.setOnClickListener { copiar(texto) }
		b.botonCopiarSeleccion.setOnClickListener {
			val ini = minOf(b.texto.selectionStart, b.texto.selectionEnd)
			val fin = maxOf(b.texto.selectionStart, b.texto.selectionEnd)
			if (fin > ini) copiar(texto.substring(ini, fin)) else avisar(getString(R.string.nada_seleccionado))
		}
	}
}
