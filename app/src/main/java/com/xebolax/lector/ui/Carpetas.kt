package com.xebolax.lector.ui

import android.content.Context
import com.xebolax.lector.R

/**
 * Carpetas de Noticias y Podcasts: elegir en cuál va una fuente o un podcast
 * (o escribir una nueva, que se crea) y mover elementos dentro de una lista
 * ordenada a mano.
 */

/**
 * Elegir carpeta: «Sin carpeta», las que hay (la actual marcada) y «Nueva
 * carpeta…», que pide el nombre. alElegir recibe el nombre ("" = sin carpeta);
 * si es una carpeta nueva, quien la recibe la crea.
 */
fun Context.elegirCarpeta(titulo: String, carpetas: List<String>, actual: String, alElegir: (String) -> Unit) {
	val nombres = listOf(getString(R.string.sin_carpeta)) + carpetas + getString(R.string.nueva_carpeta_puntos)
	val indice = if (actual.isBlank()) 0 else carpetas.indexOfFirst { it.equals(actual, ignoreCase = true) }.let { if (it < 0) -1 else it + 1 }
	elegirUno(titulo, nombres, indice) { i ->
		when {
			i == 0 -> alElegir("")
			i <= carpetas.size -> alElegir(carpetas[i - 1])
			else -> pedirNombreCarpeta(getString(R.string.nueva_carpeta)) { alElegir(it) }
		}
	}
}

/** Pide el nombre de una carpeta (nueva o al renombrar). */
fun Context.pedirNombreCarpeta(titulo: String, inicial: String = "", alAceptar: (String) -> Unit) {
	pedirTexto(titulo, getString(R.string.nombre_carpeta), inicial) { t -> if (t.isNotBlank()) alAceptar(t.trim()) }
}

/**
 * Acciones para mover un elemento en una lista ordenada a mano: subir, bajar,
 * poner el primero y el último (solo las que tienen sentido). guardar recibe
 * la lista con el nuevo orden; aviso, la posición nueva para decirla.
 */
fun <T> Context.accionesOrden(lista: List<T>, elemento: T, guardar: (List<T>) -> Unit): List<Accion> {
	val i = lista.indexOf(elemento)
	if (i < 0 || lista.size < 2) return emptyList()
	fun mover(a: Int) = {
		val nueva = lista.toMutableList().apply { removeAt(i); add(a.coerceIn(0, size), elemento) }
		guardar(nueva)
		(this as? android.app.Activity)?.avisar(getString(R.string.accion_posicion, nueva.indexOf(elemento) + 1, nueva.size), mostrar = false)
		Unit
	}
	val r = mutableListOf<Accion>()
	if (i > 0) {
		r += getString(R.string.accion_subir) to mover(i - 1)
		r += getString(R.string.accion_primera) to mover(0)
	}
	if (i < lista.size - 1) {
		r += getString(R.string.accion_bajar) to mover(i + 1)
		r += getString(R.string.accion_ultima) to mover(lista.size - 1)
	}
	return r
}
