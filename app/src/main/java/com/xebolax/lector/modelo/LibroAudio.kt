package com.xebolax.lector.modelo

/**
 * Audiolibro (m4b, mp3, carpeta de pistas) o libro DAISY con audio.
 *
 * - pistas: los archivos de audio en orden.
 * - capitulos: puntos de navegación con nivel (en DAISY, los encabezados
 *   h1-h6; en m4b/mp3, los capítulos; en una carpeta, cada pista).
 * - paginas: páginas impresas (DAISY).
 * - frases: en DAISY con texto, cada fragmento sincronizado (par del SMIL)
 *   con su bloque del texto, para seguir la lectura en pantalla.
 * - texto: el texto completo si el libro lo tiene (DAISY texto y audio).
 */
class Pista(val uri: String, val titulo: String, val duracionMs: Long = 0)

class MarcaAudio(val titulo: String, val nivel: Int, val pista: Int, val ms: Long)

class FraseAudio(val pista: Int, val inicioMs: Long, val finMs: Long, val bloque: Int)

class LibroAudio(
	val titulo: String,
	val autor: String = "",
	val narrador: String = "",
	val pistas: List<Pista>,
	val capitulos: List<MarcaAudio> = emptyList(),
	val paginas: List<MarcaAudio> = emptyList(),
	val frases: List<FraseAudio> = emptyList(),
	val texto: Documento? = null,
	val metadatos: Map<String, String> = emptyMap(),
	val avisos: List<String> = emptyList(),
) {
	/** Niveles de capítulo que existen (para navegar por nivel, como en los reproductores DAISY). */
	val niveles: List<Int> by lazy { capitulos.map { it.nivel }.distinct().sorted() }

	/** Duración total si se conoce la de todas las pistas (0 si no). */
	val duracionTotal: Long get() = if (pistas.all { it.duracionMs > 0 }) pistas.sumOf { it.duracionMs } else 0L

	/** Milisegundos desde el principio del libro hasta el inicio de una pista. */
	fun inicioDePista(pista: Int): Long = pistas.take(pista).sumOf { it.duracionMs }

	/** Capítulo en el que está una posición. */
	fun capituloEn(pista: Int, ms: Long): MarcaAudio? =
		capitulos.lastOrNull { it.pista < pista || (it.pista == pista && it.ms <= ms + 300) }

	fun paginaEn(pista: Int, ms: Long): MarcaAudio? =
		paginas.lastOrNull { it.pista < pista || (it.pista == pista && it.ms <= ms + 300) }

	/** Frase sincronizada que suena en una posición (búsqueda binaria). */
	fun fraseEn(pista: Int, ms: Long): Int {
		if (frases.isEmpty()) return -1
		var bajo = 0
		var alto = frases.size - 1
		var r = -1
		while (bajo <= alto) {
			val medio = (bajo + alto) / 2
			val f = frases[medio]
			if (f.pista < pista || (f.pista == pista && f.inicioMs <= ms)) {
				r = medio
				bajo = medio + 1
			} else alto = medio - 1
		}
		return r
	}
}

/** Resultado de abrir un archivo: texto o audio. */
sealed class Contenido {
	abstract val titulo: String
	abstract val autor: String

	class Texto(val documento: Documento) : Contenido() {
		override val titulo get() = documento.titulo
		override val autor get() = documento.autor
	}

	class Audio(val libro: LibroAudio) : Contenido() {
		override val titulo get() = libro.titulo
		override val autor get() = libro.autor
	}
}
