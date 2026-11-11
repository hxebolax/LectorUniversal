package com.xebolax.lector.ui

import androidx.annotation.StringRes
import androidx.preference.ListPreference
import com.xebolax.lector.R

/**
 * Lista de Ajustes que se llena después (motores, idiomas, voces). Sin opciones,
 * abrirla cierra la app; así que, mientras se carga, tiene solo su opción por
 * defecto (valor vacío), no se puede abrir y dice «Cargando…». El valor
 * guardado no se toca: al llenarse, sigue elegido.
 */
fun cargandoLista(lista: ListPreference?, @StringRes porDefecto: Int) {
	lista ?: return
	val c = lista.context
	lista.entries = arrayOf(c.getString(porDefecto))
	lista.entryValues = arrayOf("")
	lista.isEnabled = false
	lista.summary = c.getString(R.string.cargando)
}
