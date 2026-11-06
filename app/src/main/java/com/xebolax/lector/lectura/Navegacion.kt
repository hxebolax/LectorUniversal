package com.xebolax.lector.lectura

import androidx.annotation.StringRes
import com.xebolax.lector.R
import com.xebolax.lector.modelo.Bloque
import com.xebolax.lector.modelo.Documento
import com.xebolax.lector.modelo.Marca
import com.xebolax.lector.modelo.TipoBloque

/**
 * Elementos por los que se puede navegar con los botones Anterior/Siguiente,
 * las teclas de volumen, el teclado o las acciones de TalkBack. Cada uno
 * tiene sus textos completos ("Siguiente encabezado") para que se puedan
 * traducir bien.
 */
enum class Elemento(
	@StringRes val nombre: Int,
	@StringRes val siguiente: Int,
	@StringRes val anterior: Int,
	/** Tecla rápida con teclado físico (Mayúsculas = hacia atrás), como en Paperback y el Visor Universal. */
	val tecla: Char? = null,
) {
	PARRAFO(R.string.el_parrafos, R.string.sig_parrafo, R.string.ant_parrafo),
	ENCABEZADO(R.string.el_encabezados, R.string.sig_encabezado, R.string.ant_encabezado, 'h'),
	NIVEL_1(R.string.el_nivel_1, R.string.sig_nivel_1, R.string.ant_nivel_1, '1'),
	NIVEL_2(R.string.el_nivel_2, R.string.sig_nivel_2, R.string.ant_nivel_2, '2'),
	NIVEL_3(R.string.el_nivel_3, R.string.sig_nivel_3, R.string.ant_nivel_3, '3'),
	NIVEL_4(R.string.el_nivel_4, R.string.sig_nivel_4, R.string.ant_nivel_4, '4'),
	NIVEL_5(R.string.el_nivel_5, R.string.sig_nivel_5, R.string.ant_nivel_5, '5'),
	NIVEL_6(R.string.el_nivel_6, R.string.sig_nivel_6, R.string.ant_nivel_6, '6'),
	CAPITULO(R.string.el_capitulos, R.string.sig_capitulo, R.string.ant_capitulo, 'c'),
	PAGINA(R.string.el_paginas, R.string.sig_pagina, R.string.ant_pagina, 'p'),
	ENLACE(R.string.el_enlaces, R.string.sig_enlace, R.string.ant_enlace, 'k'),
	LISTA(R.string.el_listas, R.string.sig_lista, R.string.ant_lista, 'l'),
	ELEMENTO_LISTA(R.string.el_elementos_lista, R.string.sig_elemento_lista, R.string.ant_elemento_lista, 'i'),
	TABLA(R.string.el_tablas, R.string.sig_tabla, R.string.ant_tabla, 't'),
	CITA(R.string.el_citas, R.string.sig_cita, R.string.ant_cita, 'q'),
	IMAGEN(R.string.el_imagenes, R.string.sig_imagen, R.string.ant_imagen, 'g'),
	NOTA(R.string.el_notas, R.string.sig_nota, R.string.ant_nota, 'o'),
	FORMULA(R.string.el_formulas, R.string.sig_formula, R.string.ant_formula, 'm'),
	CODIGO(R.string.el_codigo, R.string.sig_codigo, R.string.ant_codigo, 'x'),
	SEPARADOR(R.string.el_separadores, R.string.sig_separador, R.string.ant_separador, 's'),
	MARCADOR(R.string.el_marcadores, R.string.sig_marcador, R.string.ant_marcador, 'b'),
	BUSQUEDA(R.string.el_resultados, R.string.sig_resultado, R.string.ant_resultado),
	DIEZ_POR_CIENTO(R.string.el_diez_por_ciento, R.string.sig_diez_por_ciento, R.string.ant_diez_por_ciento);

	companion object {
		fun deTecla(c: Char): Elemento? = entries.firstOrNull { it.tecla == c.lowercaseChar() }
		fun nivel(n: Int): Elemento = entries[NIVEL_1.ordinal + (n - 1).coerceIn(0, 5)]
	}
}

/**
 * Búsqueda del siguiente o anterior elemento de un tipo en un documento.
 * marcadores y resultados son índices de bloque (los da la pantalla).
 */
/** Bloques que forman un grupo del que se puede salir de una vez: filas de tabla, elementos de lista y citas. */
private val CONTENEDORES = setOf(TipoBloque.FILA_TABLA, TipoBloque.ELEMENTO_LISTA, TipoBloque.CITA)

class Navegador(private val doc: Documento) {

	var marcadores: List<Int> = emptyList()
	var resultados: List<Int> = emptyList()

	/** Primeros bloques de los capítulos: entradas del índice, inicios de sección o encabezados del nivel más alto. */
	private val capitulos: IntArray by lazy {
		val desdeIndice = doc.indice.map { it.bloque }.distinct().sorted()
		val lista = when {
			desdeIndice.size >= 2 -> desdeIndice
			doc.bloques.count { it.tiene(Marca.INICIO_SECCION) } >= 2 ->
				doc.bloques.indices.filter { doc.bloques[it].tiene(Marca.INICIO_SECCION) }
			else -> {
				val minimo = doc.bloques.filter { it.tipo == TipoBloque.ENCABEZADO }.minOfOrNull { it.nivel } ?: 0
				doc.bloques.indices.filter { doc.bloques[it].tipo == TipoBloque.ENCABEZADO && doc.bloques[it].nivel == minimo }
			}
		}
		lista.toIntArray()
	}

	private val paginas: IntArray by lazy { doc.paginas.map { it.bloque }.distinct().toIntArray() }

	fun coincide(e: Elemento, i: Int): Boolean {
		val b: Bloque = doc.bloques.getOrNull(i) ?: return false
		val previo = doc.bloques.getOrNull(i - 1)
		return when (e) {
			Elemento.PARRAFO -> true
			Elemento.ENCABEZADO -> b.tipo == TipoBloque.ENCABEZADO
			Elemento.NIVEL_1, Elemento.NIVEL_2, Elemento.NIVEL_3, Elemento.NIVEL_4, Elemento.NIVEL_5, Elemento.NIVEL_6 ->
				b.tipo == TipoBloque.ENCABEZADO && b.nivel == e.ordinal - Elemento.NIVEL_1.ordinal + 1
			Elemento.ENLACE -> b.enlaces.isNotEmpty()
			Elemento.LISTA -> b.tiene(Marca.INICIO_LISTA) || (b.tipo == TipoBloque.ELEMENTO_LISTA && previo?.tipo != TipoBloque.ELEMENTO_LISTA)
			Elemento.ELEMENTO_LISTA -> b.tipo == TipoBloque.ELEMENTO_LISTA
			Elemento.TABLA -> b.tiene(Marca.INICIO_TABLA)
			Elemento.CITA -> b.tipo == TipoBloque.CITA && (b.tiene(Marca.INICIO_CITA) || previo?.tipo != TipoBloque.CITA)
			Elemento.IMAGEN -> b.tipo == TipoBloque.IMAGEN || b.tiene(Marca.FIGURA)
			Elemento.NOTA -> b.tiene(Marca.LLAMADA_NOTA)
			Elemento.FORMULA -> b.tiene(Marca.FORMULA)
			// Un bloque de código seguido cuenta como uno (se salta al siguiente bloque de código, no a la línea siguiente)
			Elemento.CODIGO -> b.tipo == TipoBloque.CODIGO && previo?.tipo != TipoBloque.CODIGO
			Elemento.SEPARADOR -> b.tipo == TipoBloque.SEPARADOR
			else -> false
		}
	}

	/** Índice del siguiente (o anterior) elemento desde el bloque actual; -1 si no hay. */
	fun buscar(e: Elemento, desde: Int, haciaDelante: Boolean, circular: Boolean = false): Int {
		val r = buscarSinVuelta(e, desde, haciaDelante)
		if (r >= 0 || !circular) return r
		// Vuelta: desde el otro extremo
		return buscarSinVuelta(e, if (haciaDelante) -1 else doc.bloques.size, haciaDelante)
	}

	private fun buscarSinVuelta(e: Elemento, desde: Int, haciaDelante: Boolean): Int {
		val n = doc.bloques.size
		fun enLista(lista: IntArray): Int = if (haciaDelante) lista.firstOrNull { it > desde } ?: -1 else lista.lastOrNull { it < desde } ?: -1
		return when (e) {
			Elemento.CAPITULO -> enLista(capitulos)
			Elemento.PAGINA -> enLista(paginas)
			Elemento.MARCADOR -> enLista(marcadores.distinct().sorted().toIntArray())
			Elemento.BUSQUEDA -> enLista(resultados.distinct().sorted().toIntArray())
			Elemento.DIEZ_POR_CIENTO -> {
				val actual = doc.porcentaje(desde.coerceAtLeast(0))
				val objetivo = if (haciaDelante) (actual / 10).toInt() * 10 + 10.0 else Math.ceil(actual / 10 - 1).coerceAtLeast(0.0) * 10
				if (objetivo > 100 || (!haciaDelante && desde <= 0)) -1
				else doc.bloqueDePorcentaje(objetivo).let { if (it == desde) (if (haciaDelante) it + 1 else it - 1).takeIf { x -> x in 0 until n } ?: -1 else it }
			}
			else -> {
				if (haciaDelante) {
					var i = desde + 1
					while (i < n) {
						if (coincide(e, i)) return i
						i++
					}
				} else {
					var i = desde - 1
					while (i >= 0) {
						if (coincide(e, i)) return i
						i--
					}
				}
				-1
			}
		}
	}

	/** Cuántos hay de cada elemento (para ofrecer solo los que existen). */
	/**
	 * Fin de la tabla, lista o cita en la que está el bloque: el primer bloque después de ella
	 * (-1 si el bloque no está en una o no hay nada después).
	 */
	fun finContenedor(i: Int): Int {
		val tipo = doc.bloques.getOrNull(i)?.tipo ?: return -1
		if (tipo !in CONTENEDORES) return -1
		var j = i + 1
		while (j < doc.bloques.size && doc.bloques[j].tipo == tipo && !(tipo == TipoBloque.FILA_TABLA && doc.bloques[j].tiene(Marca.INICIO_TABLA))) j++
		return if (j < doc.bloques.size) j else -1
	}

	/** Principio de la tabla, lista o cita en la que está el bloque. */
	fun inicioContenedor(i: Int): Int {
		val tipo = doc.bloques.getOrNull(i)?.tipo ?: return -1
		if (tipo !in CONTENEDORES) return -1
		var j = i
		while (j > 0 && doc.bloques[j - 1].tipo == tipo && !(tipo == TipoBloque.FILA_TABLA && doc.bloques[j].tiene(Marca.INICIO_TABLA))) j--
		return j
	}

	fun contar(e: Elemento): Int = when (e) {
		Elemento.PARRAFO, Elemento.DIEZ_POR_CIENTO -> doc.bloques.size
		Elemento.CAPITULO -> capitulos.size
		Elemento.PAGINA -> paginas.size
		Elemento.MARCADOR -> marcadores.size
		Elemento.BUSQUEDA -> resultados.size
		else -> doc.bloques.indices.count { coincide(e, it) }
	}
}
