package com.xebolax.lector.modelo

/**
 * Ayuda a los lectores de formatos a montar un Documento:
 *
 * - Añaden bloques con bloque()/parrafo()/encabezado()...
 * - Registran anclas (id de un elemento) con ancla("capitulo2.xhtml#nota3").
 *   Apuntan al bloque que se añada a continuación.
 * - Los enlaces internos se guardan con su destino original
 *   ("capitulo2.xhtml#nota3", "#nota3"...) y construir() los cambia por
 *   "#<bloque>". Los que no se encuentran se quedan como texto sin enlace.
 * - El índice y las páginas también pueden apuntar a anclas (indiceAncla,
 *   paginaAncla) y se resuelven al construir.
 */
class ConstructorDocumento {

	private companion object {
		val VIÑETA = Regex("^[•·▪◦‣●○■□➢►✓\\-–*]\\s+")
		val NUMERO = Regex("^(\\d{1,3}|[a-z])[.)]\\s+\\S")
	}

	var titulo = ""
	var autor = ""
	var idioma = ""
	val metadatos = LinkedHashMap<String, String>()
	val avisos = mutableListOf<String>()

	private class BloquePendiente(
		var tipo: TipoBloque,
		val texto: String,
		var nivel: Int,
		val enlaces: List<Enlace>,
		var marcas: Int,
		val idioma: String?,
	)

	private val bloques = ArrayList<BloquePendiente>()
	private val anclas = HashMap<String, Int>()
	private val indice = mutableListOf<Triple<String, Int, Any>>()
	private val paginas = mutableListOf<Pair<String, Any>>()
	private val imagenes = HashMap<Int, String>()
	private var marcasPendientes = 0

	/** Número de bloques añadidos (índice del siguiente). */
	val tamano: Int get() = bloques.size

	fun estaVacio() = bloques.isEmpty()

	/** El siguiente bloque llevará estas marcas (inicio de sección, de página...). */
	fun marcarSiguiente(marca: Int) {
		marcasPendientes = marcasPendientes or marca
	}

	/** Añade un ancla que apunta al siguiente bloque. */
	fun ancla(id: String) {
		if (id.isNotEmpty() && id !in anclas) anclas[id] = bloques.size
	}

	/** Ancla que apunta a un bloque concreto. */
	fun anclaEn(id: String, bloque: Int) {
		if (id.isNotEmpty() && id !in anclas) anclas[id] = bloque
	}

	fun tieneAncla(id: String) = id in anclas

	fun bloque(
		tipo: TipoBloque,
		texto: String,
		nivel: Int = 0,
		enlaces: List<Enlace> = emptyList(),
		marcas: Int = 0,
		idioma: String? = null,
	): Int {
		val limpio = Textos.limpiar(texto)
		if (limpio.isBlank() && tipo != TipoBloque.SEPARADOR) {
			return -1
		}
		val (textoFinal, enlacesFinales) = if (limpio.length != texto.length || enlaces.isNotEmpty()) {
			Textos.recortarConEnlaces(texto, enlaces)
		} else limpio to enlaces
		val primero = bloques.size
		if (dividirParrafos > 0 && textoFinal.length > dividirParrafos &&
			(tipo == TipoBloque.PARRAFO || tipo == TipoBloque.CITA || tipo == TipoBloque.NOTA)
		) {
			// Párrafo largo: varios bloques de frases completas (cada deslizamiento lee menos)
			var desde = 0
			var hasta = 0
			val trozos = ArrayList<IntRange>()
			for (frase in Segmentador.frases(textoFinal)) {
				if (frase.last + 1 - desde > dividirParrafos && hasta > desde) {
					trozos += desde until hasta
					desde = frase.first
				}
				hasta = frase.last + 1
			}
			if (hasta > desde) trozos += desde until hasta
			for ((i, trozo) in trozos.withIndex()) {
				val propios = enlacesFinales.filter { it.inicio >= trozo.first && it.fin <= trozo.last + 1 }
					.map { Enlace(it.inicio - trozo.first, it.fin - trozo.first, it.destino) }
				bloques += BloquePendiente(
					tipo, textoFinal.substring(trozo.first, trozo.last + 1), nivel, propios,
					if (i == 0) marcas or marcasPendientes else marcas and Marca.LLAMADA_NOTA, idioma,
				)
			}
		} else {
			bloques += BloquePendiente(tipo, textoFinal, nivel, enlacesFinales, marcas or marcasPendientes, idioma)
		}
		marcasPendientes = 0
		return primero
	}

	/** Párrafos más largos que esto (caracteres) se parten por frases; 0 = no partir. */
	var dividirParrafos = 0

	fun parrafo(texto: String, enlaces: List<Enlace> = emptyList()) = bloque(TipoBloque.PARRAFO, texto, enlaces = enlaces)

	fun encabezado(texto: String, nivel: Int, enlaces: List<Enlace> = emptyList()) =
		bloque(TipoBloque.ENCABEZADO, texto, nivel.coerceIn(1, 6), enlaces)

	fun separador() = bloque(TipoBloque.SEPARADOR, "—————")

	/** La imagen de un bloque ya añadido (ruta dentro del libro, dirección web o "data:"). */
	fun imagen(bloque: Int, recurso: String) {
		if (bloque in bloques.indices && recurso.isNotBlank() && bloque !in imagenes) imagenes[bloque] = recurso
	}

	/** Añade una marca a un bloque ya añadido. */
	fun anadirMarca(bloque: Int, marca: Int) {
		if (bloque in bloques.indices) bloques[bloque].marcas = bloques[bloque].marcas or marca
	}

	fun tipoDe(bloque: Int): TipoBloque? = bloques.getOrNull(bloque)?.tipo

	fun textoDe(bloque: Int): String? = bloques.getOrNull(bloque)?.texto

	/** Convierte un bloque en encabezado (libros sin h1-h6: se usan los títulos del índice). */
	fun cambiarAEncabezado(bloque: Int, nivel: Int) {
		val b = bloques.getOrNull(bloque) ?: return
		if (b.tipo == TipoBloque.ENCABEZADO || b.texto.length > 200) return
		b.tipo = TipoBloque.ENCABEZADO
		b.nivel = nivel.coerceIn(1, 6)
	}

	/** ¿Hay algún encabezado? */
	fun tieneEncabezados() = bloques.any { it.tipo == TipoBloque.ENCABEZADO }

	/** Entrada del índice que apunta a un bloque. */
	fun indice(titulo: String, nivel: Int, bloque: Int = bloques.size) {
		if (titulo.isNotBlank()) indice += Triple(Textos.limpiar(titulo), nivel, bloque)
	}

	/** Entrada del índice que apunta a un ancla (se resuelve al construir). */
	fun indiceAncla(titulo: String, nivel: Int, ancla: String) {
		if (titulo.isNotBlank()) indice += Triple(Textos.limpiar(titulo), nivel, ancla)
	}

	fun pagina(etiqueta: String, bloque: Int = bloques.size) {
		paginas += etiqueta.trim() to bloque
		if (bloque == bloques.size) marcarSiguiente(Marca.PAGINA) else anadirMarca(bloque, Marca.PAGINA)
	}

	fun paginaAncla(etiqueta: String, ancla: String) {
		paginas += etiqueta.trim() to ancla
	}

	/**
	 * Resuelve un destino de enlace. Prueba el destino tal cual y, si lleva
	 * archivo y fragmento, el fragmento solo y el archivo solo (inicio del archivo).
	 */
	fun resolver(destino: String): Int? {
		anclas[destino]?.let { return it }
		val almohadilla = destino.indexOf('#')
		if (almohadilla >= 0) {
			val fragmento = destino.substring(almohadilla + 1)
			val archivo = destino.substring(0, almohadilla)
			if (fragmento.isNotEmpty()) anclas["#$fragmento"]?.let { return it }
			if (archivo.isNotEmpty()) anclas[archivo]?.let { return it }
		} else {
			anclas["#$destino"]?.let { return it }
		}
		return null
	}

	/**
	 * Listas hechas a mano (TXT, PDF, RTF, FB2...): dos o más párrafos seguidos
	 * que empiezan por una viñeta ("• ", "- ", "· ") o por "1. ", "2) "... pasan
	 * a ser elementos de lista. La viñeta se quita (TalkBack ya dice que es un
	 * elemento de lista); los números se conservan.
	 */
	private fun reconocerListas() {
		fun viñeta(t: String) = VIÑETA.find(t)
		fun numero(t: String) = NUMERO.find(t)
		var i = 0
		while (i < bloques.size) {
			val b = bloques[i]
			val esViñeta = b.tipo == TipoBloque.PARRAFO && viñeta(b.texto) != null
			val esNumero = b.tipo == TipoBloque.PARRAFO && numero(b.texto) != null
			if (!esViñeta && !esNumero) {
				i++
				continue
			}
			var fin = i
			while (fin + 1 < bloques.size && bloques[fin + 1].tipo == TipoBloque.PARRAFO &&
				(if (esViñeta) viñeta(bloques[fin + 1].texto) != null else numero(bloques[fin + 1].texto) != null)
			) fin++
			if (fin > i) {
				for (k in i..fin) {
					val p = bloques[k]
					val quitar = if (esViñeta) viñeta(p.texto)!!.value.length else 0
					val enlaces = if (quitar == 0) p.enlaces else p.enlaces.mapNotNull {
						if (it.fin - quitar <= 0) null else Enlace((it.inicio - quitar).coerceAtLeast(0), it.fin - quitar, it.destino)
					}
					bloques[k] = BloquePendiente(
						TipoBloque.ELEMENTO_LISTA, p.texto.substring(quitar), 1, enlaces,
						if (k == i) p.marcas or Marca.INICIO_LISTA else p.marcas, p.idioma,
					)
				}
			}
			i = fin + 1
		}
	}

	fun construir(): Documento {
		reconocerListas()
		val total = bloques.size
		val limite = (total - 1).coerceAtLeast(0)
		val finales = ArrayList<Bloque>(total)
		for (b in bloques) {
			val enlaces = b.enlaces.mapNotNull { e ->
				when {
					e.destino.startsWith("http://") || e.destino.startsWith("https://") ||
						e.destino.startsWith("mailto:") || e.destino.startsWith("tel:") -> e
					else -> resolver(e.destino)?.let { Enlace(e.inicio, e.fin, "#${it.coerceIn(0, limite)}") }
				}
			}
			finales += Bloque(b.tipo, b.texto, b.nivel, enlaces, b.marcas, b.idioma)
		}
		val indiceFinal = indice.mapNotNull { (titulo, nivel, destino) ->
			val bloque = when (destino) {
				is Int -> destino
				is String -> resolver(destino)
				else -> null
			} ?: return@mapNotNull null
			EntradaIndice(titulo, nivel.coerceAtLeast(1), bloque.coerceIn(0, limite))
		}
		val paginasFinales = paginas.mapNotNull { (etiqueta, destino) ->
			val bloque = when (destino) {
				is Int -> destino
				is String -> resolver(destino)
				else -> null
			} ?: return@mapNotNull null
			Pagina(etiqueta, bloque.coerceIn(0, limite))
		}.sortedBy { it.bloque }
		// Sin índice: se hace con los encabezados
		val indiceUsado = indiceFinal.ifEmpty {
			finales.mapIndexedNotNull { i, b -> if (b.tipo == TipoBloque.ENCABEZADO) EntradaIndice(b.texto, b.nivel, i) else null }
		}
		return Documento(
			titulo = titulo.ifBlank { "" },
			autor = autor,
			idioma = idioma,
			bloques = finales,
			indice = indiceUsado,
			paginas = paginasFinales,
			metadatos = metadatos.filterValues { it.isNotBlank() },
			avisos = avisos.toList(),
			imagenes = imagenes.toMap(),
		)
	}
}
