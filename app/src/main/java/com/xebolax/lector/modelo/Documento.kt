package com.xebolax.lector.modelo

/**
 * Modelo común de todos los documentos de texto, como en Paperback y en el
 * Visor Universal: una lista plana de bloques (párrafos, encabezados,
 * elementos de lista, filas de tabla...) más el índice y las páginas, que
 * apuntan a bloques. La pantalla de lectura muestra un bloque por fila, así
 * TalkBack avanza párrafo a párrafo y navegar es buscar el siguiente bloque
 * de un tipo.
 */
enum class TipoBloque {
	PARRAFO,
	ENCABEZADO,
	ELEMENTO_LISTA,
	CITA,
	/** Una fila de una tabla, con las celdas ya unidas ("Columna: valor; ..."). */
	FILA_TABLA,
	/** Imagen con su texto alternativo (o el pie de figura). */
	IMAGEN,
	CODIGO,
	SEPARADOR,
	/** Texto de una nota al pie o al final. */
	NOTA,
}

/** Enlace dentro del texto de un bloque. destino: "#123" (bloque 123) o una dirección externa. */
class Enlace(val inicio: Int, val fin: Int, val destino: String) {
	val interno: Boolean get() = destino.startsWith("#")
	val bloqueDestino: Int get() = destino.substring(1).toIntOrNull() ?: -1
}

class Bloque(
	val tipo: TipoBloque,
	val texto: String,
	/** Nivel del encabezado (1-6) o profundidad de la lista (1...). */
	val nivel: Int = 0,
	val enlaces: List<Enlace> = emptyList(),
	/** Banderas Marca.* */
	val marcas: Int = 0,
	/** Idioma del bloque si es distinto del documento (para la voz). */
	val idioma: String? = null,
) {
	fun tiene(marca: Int) = marcas and marca != 0
}

object Marca {
	const val INICIO_LISTA = 1
	const val INICIO_TABLA = 2
	const val INICIO_CITA = 4
	/** El bloque contiene una llamada a nota. */
	const val LLAMADA_NOTA = 8
	/** Empieza un capítulo o sección del libro (archivo del EPUB, sección del FB2...). */
	const val INICIO_SECCION = 16
	/** Empieza una página (ver Documento.paginas). */
	const val PAGINA = 32
	const val FORMULA = 64
	/** Fila de encabezado de una tabla. */
	const val ENCABEZADO_TABLA = 128
	const val FIGURA = 256
}

/** Entrada del índice (tabla de contenidos). */
class EntradaIndice(val titulo: String, val nivel: Int, val bloque: Int)

/** Página impresa o del PDF: etiqueta ("12", "xiv") y primer bloque. */
class Pagina(val etiqueta: String, val bloque: Int)

class Documento(
	val titulo: String,
	val autor: String = "",
	val idioma: String = "",
	val bloques: List<Bloque>,
	val indice: List<EntradaIndice> = emptyList(),
	val paginas: List<Pagina> = emptyList(),
	/** Editorial, fecha, descripción, ISBN, accesibilidad... (nombre visible → valor). */
	val metadatos: Map<String, String> = emptyMap(),
	/** Avisos para el lector: DRM, PDF sin texto, contenido con destellos... */
	val avisos: List<String> = emptyList(),
	/**
	 * Imagen de cada bloque que la tiene (bloque → dónde está): ruta dentro del
	 * libro ("OEBPS/img/a.jpg"), dirección web o "data:". Para describirla.
	 */
	val imagenes: Map<Int, String> = emptyMap(),
) {
	/** Número total de caracteres (para porcentajes y estadísticas). */
	val caracteres: Long by lazy { bloques.sumOf { it.texto.length.toLong() } }

	/** Carácter acumulado al principio de cada bloque (para ir a un porcentaje). */
	val inicioBloque: LongArray by lazy {
		val r = LongArray(bloques.size + 1)
		for (i in bloques.indices) r[i + 1] = r[i] + bloques[i].texto.length
		r
	}

	fun porcentaje(bloque: Int, caracter: Int = 0): Double {
		if (caracteres == 0L || bloques.isEmpty()) return 0.0
		val i = bloque.coerceIn(0, bloques.size - 1)
		return ((inicioBloque[i] + caracter).toDouble() / caracteres * 100.0).coerceIn(0.0, 100.0)
	}

	/** Bloque en el que cae un porcentaje (0-100). */
	fun bloqueDePorcentaje(porcentaje: Double): Int {
		if (bloques.isEmpty()) return 0
		val objetivo = (caracteres * porcentaje.coerceIn(0.0, 100.0) / 100.0).toLong()
		var bajo = 0
		var alto = bloques.size - 1
		while (bajo < alto) {
			val medio = (bajo + alto + 1) / 2
			if (inicioBloque[medio] <= objetivo) bajo = medio else alto = medio - 1
		}
		return bajo
	}

	/** Página en la que está un bloque (la última que empieza antes o en él). */
	fun paginaDe(bloque: Int): Pagina? {
		if (paginas.isEmpty()) return null
		var bajo = 0
		var alto = paginas.size - 1
		var r: Pagina? = null
		while (bajo <= alto) {
			val medio = (bajo + alto) / 2
			if (paginas[medio].bloque <= bloque) {
				r = paginas[medio]
				bajo = medio + 1
			} else alto = medio - 1
		}
		return r
	}

	/** Entrada del índice en la que está un bloque. */
	fun capituloDe(bloque: Int): EntradaIndice? = indice.lastOrNull { it.bloque <= bloque }
}
