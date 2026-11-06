package com.xebolax.lector.ui

import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xebolax.lector.R
import com.xebolax.lector.databinding.ActivitySimpleBinding
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Pronunciacion
import com.xebolax.lector.voz.LectorVoz

/**
 * Diccionario de pronunciación de la voz: "Sr." → "señor", "XIX" → "diecinueve"...
 * Solo cambia lo que se dice, nunca el texto mostrado.
 */
class PronunciacionActivity : AppCompatActivity() {

	private lateinit var b: ActivitySimpleBinding
	private val db by lazy { BaseDatos.obtener(this) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivitySimpleBinding.inflate(layoutInflater)
		setContentView(b.root)
		configurarBarra(b.barra, getString(R.string.diccionario_pronunciacion))
		mostrar()
	}

	private fun mostrar() {
		b.contenido.removeAllViews()
		b.contenido.addView(MaterialButton(this).apply {
			text = getString(R.string.nueva_regla)
			minHeight = dp(this@PronunciacionActivity, 48)
			setOnClickListener { editar(null) }
		})
		b.contenido.addView(android.widget.TextView(this).apply {
			text = getString(R.string.pronunciacion_ayuda)
			setPadding(0, dp(this@PronunciacionActivity, 8), 0, dp(this@PronunciacionActivity, 8))
		})
		val reglas = db.pronunciaciones()
		if (reglas.isEmpty()) b.contenido.addView(android.widget.TextView(this).apply { text = getString(R.string.sin_reglas) })
		for (r in reglas) {
			val boton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
				val texto = getString(R.string.regla_x, r.buscar, r.reemplazar) + if (!r.activo) " (" + getString(R.string.desactivada) + ")" else ""
				text = texto
				isAllCaps = false
				minHeight = dp(this@PronunciacionActivity, 48)
				setOnClickListener { editar(r) }
				accionesAccesibles(listOf(
					getString(R.string.editar) to { editar(r) },
					getString(R.string.probar) to { LectorVoz.probar(Pronunciador(r).aplicar(r.buscar)) },
					getString(R.string.borrar) to { db.borrarPronunciacion(r.id); mostrar() },
				))
			}
			b.contenido.addView(boton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
		}
	}

	/** Pronunciador de una sola regla para probarla. */
	private class Pronunciador(val r: Pronunciacion) {
		fun aplicar(texto: String): String = runCatching {
			if (r.regex) Regex(r.buscar).replace(texto, r.reemplazar) else texto.replace(r.buscar, r.reemplazar, ignoreCase = !r.mayusculas)
		}.getOrDefault(texto)
	}

	private fun editar(r: Pronunciacion?) {
		val (contBuscar, buscar) = campoTexto(getString(R.string.texto_original), r?.buscar.orEmpty())
		val (contCambiar, cambiar) = campoTexto(getString(R.string.se_pronuncia), r?.reemplazar.orEmpty())
		val regex = CheckBox(this).apply { text = getString(R.string.expresion_regular); isChecked = r?.regex == true; minHeight = dp(context, 48) }
		val mayusculas = CheckBox(this).apply { text = getString(R.string.distinguir_mayusculas); isChecked = r?.mayusculas == true; minHeight = dp(context, 48) }
		val activa = CheckBox(this).apply { text = getString(R.string.activa); isChecked = r?.activo != false; minHeight = dp(context, 48) }
		val formulario = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			val m = dp(this@PronunciacionActivity, 20)
			setPadding(m, m / 2, m, 0)
			addView(contBuscar)
			addView(contCambiar)
			addView(regex)
			addView(mayusculas)
			addView(activa)
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(if (r == null) R.string.nueva_regla else R.string.editar)
			.setView(formulario)
			.setPositiveButton(R.string.guardar) { _, _ ->
				val texto = buscar.text.toString()
				if (texto.isBlank()) return@setPositiveButton
				if (regex.isChecked && runCatching { Regex(texto) }.isFailure) {
					avisar(getString(R.string.expresion_no_valida))
					return@setPositiveButton
				}
				db.guardarPronunciacion(Pronunciacion(r?.id ?: 0, texto, cambiar.text.toString(), regex.isChecked, mayusculas.isChecked, activa.isChecked, null))
				LectorVoz.ajustesCambiados()
				mostrar()
			}
			.setNeutralButton(R.string.probar) { _, _ ->
				LectorVoz.probar(Pronunciador(Pronunciacion(0, buscar.text.toString(), cambiar.text.toString(), regex.isChecked, mayusculas.isChecked, true, null)).aplicar(buscar.text.toString()))
			}
			.setNegativeButton(R.string.cancelar, null)
			.show()
	}
}
