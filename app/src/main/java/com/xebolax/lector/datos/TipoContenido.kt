package com.xebolax.lector.datos

import android.content.Context
import androidx.preference.PreferenceManager
import com.xebolax.lector.R
import com.xebolax.lector.formatos.Formato
import org.json.JSONArray
import org.json.JSONObject

/**
 * Qué es cada cosa de la biblioteca, para verla por separado: libros,
 * documentos (apuntes, manuales, presentaciones…), audiolibros y páginas web.
 * Cada libro guarda el suyo si se eligió a mano; si no, se deduce del
 * formato y de dónde salió (ver [automatico]). Además de estos cuatro, el
 * usuario puede crear tipos propios (Tipos).
 */
enum class TipoContenido(val nombre: Int, val plural: Int, val icono: Int) {
	LIBRO(R.string.tipo_c_libro, R.string.tipo_libros, R.drawable.ic_libro),
	DOCUMENTO(R.string.tipo_documento, R.string.tipo_documentos, R.drawable.ic_documento),
	AUDIOLIBRO(R.string.tipo_audiolibro, R.string.tipo_audiolibros, R.drawable.ic_voz),
	WEB(R.string.tipo_c_web, R.string.tipo_webs, R.drawable.ic_globo);

	companion object {
		fun de(clave: String?): TipoContenido? = entries.firstOrNull { it.name == clave }

		/**
		 * Qué son los PDF, Word, RTF, texto, HTML, Markdown y CHM que no dicen
		 * nada más (Ajustes, Biblioteca). Se lee de los ajustes al arrancar la
		 * app y al cambiarlo, para no necesitar un Context en cada libro.
		 */
		@Volatile
		var ambiguosComo: TipoContenido = LIBRO

		/** Formatos que casi siempre son libros. */
		private val DE_LIBRO = setOf(Formato.EPUB.name, Formato.MOBI.name, Formato.FB2.name, Formato.COMIC.name, Formato.DAISY.name, Formato.BRAILLE.name)
		/** Formatos que casi siempre son documentos. */
		private val DE_DOCUMENTO = setOf(Formato.PPTX.name, Formato.XLSX.name)

		/** El tipo que le toca por su formato y por dónde se guardó. */
		fun automatico(formato: String, audio: Boolean, uri: String): TipoContenido = when {
			audio || formato == Formato.AUDIO.name || formato == Formato.LISTA.name -> AUDIOLIBRO
			formato in DE_LIBRO -> LIBRO
			formato in DE_DOCUMENTO -> DOCUMENTO
			// Páginas web guardadas, textos pegados y documentos escaneados
			"/files/web/" in uri -> WEB
			"/files/textos/" in uri || "/files/biblioteca/ocr/" in uri -> DOCUMENTO
			// Lo que se baja de un catálogo o de una biblioteca en línea es un libro
			"/files/biblioteca/descargas/" in uri -> LIBRO
			else -> ambiguosComo
		}
	}
}

/** El tipo fijo que le toca por su formato (aunque se le haya puesto uno propio). */
val Libro.tipoAutomatico: TipoContenido get() = TipoContenido.automatico(formato, audio, uri)

/** Clave del tipo del libro: la elegida a mano (fijo o propio) o la que le toca por su formato. */
val Libro.claveTipo: String get() = tipo.ifBlank { tipoAutomatico.name }

/** El tipo fijo del libro (para el icono); los de un tipo propio, el que les toca por su formato. */
val Libro.tipoContenido: TipoContenido get() = TipoContenido.de(tipo) ?: tipoAutomatico

/** Un tipo de la biblioteca: uno de los fijos o uno creado por el usuario. */
data class Tipo(val clave: String, val nombre: String, val nombreSingular: String, val fijo: TipoContenido?) {
	val icono: Int get() = fijo?.icono ?: R.drawable.ic_lista
	val propio: Boolean get() = fijo == null
}

/**
 * Los tipos (los cuatro fijos y los propios, como «Apuntes» o «Revistas») y
 * las pestañas de arriba de la Biblioteca: «Todo» y un tipo por pestaña, en el
 * orden elegido en Ajustes y solo los que se quieren ver. Las que no caben en
 * la pantalla van a la última, «Más».
 */
object Tipos {
	/** La pestaña «Todo» (no es un tipo). */
	const val TODO = "TODO"
	private const val PREFIJO_PROPIO = "P:"
	private const val PREF_PROPIOS = "tipos_propios"
	private const val PREF_ORDEN = "biblioteca_pestanas"
	private const val PREF_OCULTAS = "biblioteca_pestanas_ocultas"

	private fun prefs(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)

	fun esPropio(clave: String) = clave.startsWith(PREFIJO_PROPIO)

	fun fijos(c: Context): List<Tipo> = TipoContenido.entries.map { Tipo(it.name, c.getString(it.plural), c.getString(it.nombre), it) }

	fun propios(c: Context): List<Tipo> = runCatching {
		val a = JSONArray(prefs(c).getString(PREF_PROPIOS, "[]"))
		(0 until a.length()).mapNotNull { i ->
			val o = a.optJSONObject(i) ?: return@mapNotNull null
			val nombre = o.optString("nombre").takeIf { it.isNotBlank() } ?: return@mapNotNull null
			Tipo(PREFIJO_PROPIO + o.optLong("id"), nombre, nombre, null)
		}
	}.getOrDefault(emptyList())

	private fun guardarPropios(c: Context, lista: List<Tipo>) {
		val a = JSONArray()
		lista.forEach { t -> a.put(JSONObject().put("id", t.clave.removePrefix(PREFIJO_PROPIO).toLongOrNull() ?: 0).put("nombre", t.nombre)) }
		prefs(c).edit().putString(PREF_PROPIOS, a.toString()).apply()
	}

	/** Todos los tipos: los fijos y después los propios. */
	fun todos(c: Context): List<Tipo> = fijos(c) + propios(c)

	fun de(c: Context, clave: String?): Tipo? = clave?.let { k -> todos(c).firstOrNull { it.clave == k } }

	/** Nombre de una pestaña («Todo», «Documentos», «Apuntes»). */
	fun nombre(c: Context, clave: String): String = if (clave == TODO) c.getString(R.string.tipo_todo) else de(c, clave)?.nombre ?: clave

	/** Crea un tipo propio (o devuelve el que ya se llama así). */
	fun crearPropio(c: Context, nombre: String): Tipo {
		val n = nombre.trim()
		todos(c).firstOrNull { it.nombre.equals(n, ignoreCase = true) }?.let { return it }
		val nuevo = Tipo(PREFIJO_PROPIO + System.currentTimeMillis(), n, n, null)
		guardarPropios(c, propios(c) + nuevo)
		return nuevo
	}

	fun renombrarPropio(c: Context, clave: String, nombre: String) {
		if (nombre.isBlank()) return
		guardarPropios(c, propios(c).map { if (it.clave == clave) it.copy(nombre = nombre.trim(), nombreSingular = nombre.trim()) else it })
	}

	/** Borra un tipo propio: lo que tenía vuelve al tipo que le toca por su formato. */
	fun borrarPropio(c: Context, clave: String) {
		guardarPropios(c, propios(c).filter { it.clave != clave })
		BaseDatos.obtener(c).olvidarTipo(clave)
		guardarOrden(c, orden(c) - clave)
		prefs(c).edit().putStringSet(PREF_OCULTAS, ocultas(c) - clave).apply()
	}

	/** Todas las pestañas («Todo» y cada tipo) en el orden elegido; las nuevas, al final. */
	fun orden(c: Context): List<String> {
		val existen = listOf(TODO) + todos(c).map { it.clave }
		val guardado = prefs(c).getString(PREF_ORDEN, "").orEmpty().split(',').filter { it in existen }.distinct()
		return guardado + (existen - guardado.toSet())
	}

	fun guardarOrden(c: Context, orden: List<String>) = prefs(c).edit().putString(PREF_ORDEN, orden.joinToString(",")).apply()

	fun ocultas(c: Context): Set<String> = prefs(c).getStringSet(PREF_OCULTAS, emptySet()).orEmpty()

	fun visible(c: Context, clave: String) = clave !in ocultas(c)

	fun ponerVisible(c: Context, clave: String, si: Boolean) {
		val o = ocultas(c).toMutableSet()
		if (si) o -= clave else o += clave
		prefs(c).edit().putStringSet(PREF_OCULTAS, o).apply()
	}

	/** Las pestañas que se ven, en su orden (como mínimo una: «Todo»). */
	fun visibles(c: Context): List<String> = orden(c).filter { visible(c, it) }.ifEmpty { listOf(TODO) }
}
