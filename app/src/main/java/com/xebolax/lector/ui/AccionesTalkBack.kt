package com.xebolax.lector.ui

import android.content.Context
import android.view.View
import androidx.preference.PreferenceManager
import com.xebolax.lector.R

/**
 * Acciones de TalkBack personalizables: en cada zona de la app (libros,
 * párrafos, artículos, episodios...) el usuario elige qué acciones salen al
 * deslizar arriba o abajo y en qué orden (Ajustes, Acciones de TalkBack).
 * Lo que no se elige sigue en el menú de la fila (doble toque mantenido o
 * pulsación larga): solo se quita de las acciones rápidas.
 *
 * Las acciones se reconocen por su texto (cada entrada del catálogo dice con
 * qué textos aparece, por ejemplo «Añadir a favoritos» y «Quitar de
 * favoritos» son la misma entrada). Una acción que no está en el catálogo
 * (un enlace, una opción que depende del contenido) sale siempre, al final.
 */
enum class ZonaAcciones(val titulo: Int) {
	LIBRO(R.string.zona_libro),
	CARPETA(R.string.zona_carpeta),
	PARRAFO(R.string.zona_parrafo),
	MARCADOR(R.string.zona_marcador),
	CATALOGO(R.string.zona_catalogo),
	ARTICULO(R.string.zona_articulo),
	FUENTE(R.string.zona_fuente),
	CATEGORIA(R.string.zona_categoria),
	PODCAST(R.string.zona_podcast),
	EPISODIO(R.string.zona_episodio),
	CARPETA_PODCAST(R.string.zona_carpeta_podcast),
	EMISORA(R.string.zona_emisora),
	CARPETA_RADIO(R.string.zona_carpeta_radio),
	CANCION(R.string.zona_cancion),
	GRABACION(R.string.zona_grabacion),
	PROGRAMACION(R.string.zona_programacion),
	COMPARTIDO(R.string.zona_compartido),
}

/** Una acción del catálogo: clave guardada, textos con los que aparece, si sale sin tocar nada y su nombre en Ajustes. */
class EntradaAccion(val clave: String, val textos: List<Int>, val porDefecto: Boolean = true, val nombre: String? = null)

object AccionesTalkBack {

	private fun e(clave: String, vararg textos: Int) = EntradaAccion(clave, textos.toList())
	/** Entrada que no sale si el usuario no la añade (para no cambiar lo que había). */
	private fun o(clave: String, vararg textos: Int) = EntradaAccion(clave, textos.toList(), porDefecto = false)

	/** Párrafos del lector: las de siempre (con sus nombres de Ajustes), más enlaces e imágenes. */
	private val PARRAFO_CLAVES = listOf(
		"leer_aqui", "marcador", "nota", "copiar", "consultar", "siguiente_encabezado", "anterior_encabezado", "siguiente_elemento",
		"anterior_elemento", "indice", "buscar", "ir_a", "donde_estoy", "seleccionar", "compartir", "explicar", "traducir", "subrayar",
		"salir", "copiar_tramo", "marca_rapida",
	)
	private val PARRAFO_DEFECTO = setOf("leer_aqui", "marcador", "nota", "copiar", "siguiente_encabezado", "anterior_encabezado", "indice", "buscar", "ir_a", "salir")

	fun entradas(c: Context, zona: ZonaAcciones): List<EntradaAccion> = when (zona) {
		ZonaAcciones.LIBRO -> listOf(
			e("abrir", R.string.abrir), e("voz", R.string.leer_en_voz_alta), e("info", R.string.informacion), e("mover", R.string.mover_a_carpeta),
			e("favorito", R.string.anadir_favoritos, R.string.quitar_favoritos), e("terminado", R.string.marcar_terminado),
			e("no_leido", R.string.marcar_no_leido), e("quitar_leyendo", R.string.quitar_de_leyendo), e("editar", R.string.editar_titulo),
			e("compartir", R.string.compartir_archivo), e("quitar", R.string.quitar_de_biblioteca),
			o("estado", R.string.estado_de_lectura), o("colecciones", R.string.colecciones_puntos), o("serie", R.string.serie_puntos),
			o("tipo", R.string.cambiar_tipo), o("voz_libro", R.string.voz_de_este_libro),
		)
		ZonaAcciones.CARPETA -> listOf(
			e("abrir", R.string.abrir), e("renombrar", R.string.renombrar), e("subcarpeta", R.string.nueva_subcarpeta),
			e("mover", R.string.mover_carpeta), e("borrar", R.string.borrar_carpeta), o("tipo", R.string.cambiar_tipo_carpeta),
		)
		ZonaAcciones.COMPARTIDO -> listOf(
			e("abrir", R.string.abrir), e("voz", R.string.leer_en_voz_alta), e("biblioteca", R.string.comp_mover_biblioteca),
			e("leido", R.string.comp_marcar_leido, R.string.comp_marcar_no_leido), e("navegador", R.string.abrir_navegador),
			e("compartir", R.string.compartir), e("borrar", R.string.borrar), o("renombrar", R.string.renombrar),
			o("copiar", R.string.comp_copiar_enlace), o("info", R.string.informacion),
		)
		ZonaAcciones.PARRAFO -> {
			val nombres = c.resources.getStringArray(R.array.acciones_nombres)
			val valores = c.resources.getStringArray(R.array.acciones_valores)
			listOf(
				EntradaAccion("enlaces", listOf(R.string.abrir_enlace_x)),
				EntradaAccion("describir", listOf(R.string.describir_imagen, R.string.describir_pagina_n)),
			) + PARRAFO_CLAVES.map { clave ->
				EntradaAccion(clave, emptyList(), clave in PARRAFO_DEFECTO, nombres.getOrNull(valores.indexOf(clave)))
			}
		}
		ZonaAcciones.MARCADOR -> listOf(e("ir", R.string.ir_al_marcador), e("editar", R.string.editar_nota), e("copiar", R.string.copiar), e("borrar", R.string.borrar))
		ZonaAcciones.CATALOGO -> listOf(e("abrir", R.string.abrir), e("editar", R.string.editar_catalogo), e("borrar", R.string.borrar), e("resumen", R.string.ver_resumen))
		ZonaAcciones.ARTICULO -> listOf(
			e("abrir", R.string.abrir), e("escuchar", R.string.rss_escuchar), e("leido", R.string.rss_marcar_leido, R.string.rss_marcar_no_leido),
			e("favorito", R.string.rss_anadir_favorito, R.string.rss_quitar_favorito), e("mas_tarde", R.string.rss_mas_tarde, R.string.rss_quitar_mas_tarde),
			e("completo", R.string.rss_descargar_completo), e("navegador", R.string.abrir_navegador), e("compartir", R.string.compartir),
			e("biblioteca", R.string.rss_guardar_biblioteca, R.string.rss_abrir_en_biblioteca), e("papelera", R.string.rss_a_la_papelera),
			e("debajo", R.string.rss_marcar_debajo), e("ir_fuente", R.string.rss_ir_fuente),
		)
		ZonaAcciones.FUENTE -> listOf(
			e("opciones", R.string.rss_opciones_fuente), o("abrir", R.string.abrir), o("actualizar", R.string.rss_actualizar),
			o("todo_leido", R.string.rss_marcar_todo_leido), o("editar", R.string.rss_editar_fuente), o("info", R.string.rss_info_fuente),
			o("navegador", R.string.abrir_navegador), o("borrar", R.string.rss_borrar_fuente), o("mover", R.string.mover_a_carpeta),
			o("orden", R.string.accion_subir, R.string.accion_bajar, R.string.accion_primera, R.string.accion_ultima),
		)
		ZonaAcciones.CATEGORIA -> listOf(
			e("abrir", R.string.abrir), e("leer_todo", R.string.rss_leer_todo), e("todo_leido", R.string.rss_marcar_todo_leido), e("renombrar", R.string.renombrar),
			e("borrar", R.string.borrar_carpeta), e("orden", R.string.accion_subir, R.string.accion_bajar, R.string.accion_primera, R.string.accion_ultima),
		)
		ZonaAcciones.PODCAST -> listOf(
			e("opciones", R.string.pod_opciones_podcast), e("ficha", R.string.pod_ver_ficha), e("suscribir", R.string.pod_suscribirse, R.string.pod_ver_suscrito),
			e("compartir", R.string.compartir), o("abrir", R.string.abrir), o("actualizar", R.string.rss_actualizar), o("ajustes", R.string.pod_ajustes_podcast),
			o("escuchado", R.string.pod_marcar_todo_escuchado), o("info", R.string.pod_info), o("navegador", R.string.abrir_navegador),
			o("borrar", R.string.pod_borrar_suscripcion), o("mover", R.string.mover_a_carpeta),
			o("orden", R.string.accion_subir, R.string.accion_bajar, R.string.accion_primera, R.string.accion_ultima),
		)
		ZonaAcciones.CARPETA_PODCAST -> listOf(
			e("abrir", R.string.abrir), e("actualizar", R.string.pod_actualizar_carpeta), e("escuchado", R.string.pod_marcar_todo_escuchado),
			e("renombrar", R.string.renombrar), e("borrar", R.string.borrar_carpeta),
			e("orden", R.string.accion_subir, R.string.accion_bajar, R.string.accion_primera, R.string.accion_ultima),
		)
		ZonaAcciones.EPISODIO -> listOf(
			e("escuchar", R.string.pod_escuchar), e("escuchar_sin", R.string.pod_escuchar_sin_suscribir),
			e("mover_cola", R.string.pod_subir_cola, R.string.pod_bajar_cola, R.string.pod_al_principio, R.string.pod_al_final),
			e("cola", R.string.pod_anadir_cola, R.string.pod_quitar_cola), e("cola_principio", R.string.pod_anadir_cola_principio),
			e("descarga", R.string.pod_descargar, R.string.pod_borrar_descarga, R.string.pod_cancelar_descarga, R.string.pod_reintentar_descarga),
			e("escuchado", R.string.pod_marcar_escuchado, R.string.pod_marcar_no_escuchado), e("novedades", R.string.pod_quitar_novedades),
			e("favorito", R.string.rss_anadir_favorito, R.string.rss_quitar_favorito), e("notas", R.string.pod_notas),
			e("transcripcion", R.string.pod_transcripcion), e("compartir", R.string.compartir), e("navegador", R.string.abrir_navegador),
			e("capitulos", R.string.pod_ver_capitulos), e("desde_aqui", R.string.pod_escuchar_desde_aqui),
			e("anteriores", R.string.pod_marcar_anteriores), e("posteriores", R.string.pod_marcar_posteriores),
			e("ir_podcast", R.string.pod_ir_podcast), e("ficha", R.string.pod_ver_ficha),
		)
		ZonaAcciones.EMISORA -> listOf(
			e("escuchar", R.string.radio_escuchar, R.string.radio_ir_reproductor), e("favorita", R.string.radio_anadir_favorita, R.string.radio_quitar_favorita),
			e("mover", R.string.mover_a_carpeta), e("quitar_historial", R.string.radio_quitar_historial),
			e("grabar", R.string.radio_grabar, R.string.radio_detener_grabacion), e("despertar", R.string.radio_despertar_con),
			e("canciones", R.string.radio_canciones_emisora), e("info", R.string.radio_info), e("compartir", R.string.compartir),
			o("orden", R.string.accion_subir, R.string.accion_bajar, R.string.accion_primera, R.string.accion_ultima),
			o("votar", R.string.radio_votar), o("navegador", R.string.abrir_navegador), o("copiar", R.string.radio_copiar_direccion),
			o("acceso", R.string.radio_acceso_directo), o("editar", R.string.radio_editar),
		)
		ZonaAcciones.CARPETA_RADIO -> listOf(
			e("abrir", R.string.abrir), e("escuchar", R.string.radio_escuchar_carpeta), e("renombrar", R.string.renombrar),
			e("borrar", R.string.borrar_carpeta), e("orden", R.string.accion_subir, R.string.accion_bajar, R.string.accion_primera, R.string.accion_ultima),
		)
		ZonaAcciones.CANCION -> listOf(
			e("buscar", R.string.radio_buscar_cancion), e("copiar", R.string.radio_copiar_cancion), e("compartir", R.string.radio_compartir_cancion),
			e("emisora", R.string.radio_escuchar_x), e("borrar", R.string.borrar),
		)
		ZonaAcciones.GRABACION -> listOf(
			e("escuchar", R.string.radio_escuchar), e("biblioteca", R.string.radio_guardar_biblioteca), e("compartir", R.string.compartir),
			e("renombrar", R.string.renombrar), e("borrar", R.string.borrar),
		)
		ZonaAcciones.PROGRAMACION -> listOf(
			e("activar", R.string.radio_activar, R.string.radio_desactivar), e("editar", R.string.radio_editar_programacion),
			e("probar", R.string.radio_probar_despertador), e("borrar", R.string.borrar),
		)
	}

	/** Cómo se llama la entrada en Ajustes: su nombre o sus textos («Añadir a favoritos / Quitar de favoritos»). */
	fun nombre(c: Context, e: EntradaAccion): String = e.nombre ?: e.textos.map { plantilla(c, it).replace(PARAMETRO, "…") }.distinct().joinToString(" / ")

	private fun preferencia(zona: ZonaAcciones) = "acciones_zona_${zona.name.lowercase()}"

	/** Las claves elegidas en esa zona, en su orden. */
	fun elegidas(c: Context, zona: ZonaAcciones): List<String> {
		val p = PreferenceManager.getDefaultSharedPreferences(c)
		p.getString(preferencia(zona), null)?.let { guardado -> return guardado.split(',').filter { it.isNotBlank() } }
		// Las de los párrafos se elegían antes en Ajustes, Navegación (sin orden): se respetan
		if (zona == ZonaAcciones.PARRAFO) p.getStringSet("acciones_parrafo", null)?.let { antes ->
			return listOf("enlaces", "describir") + PARRAFO_CLAVES.filter { it in antes }
		}
		return entradas(c, zona).filter { it.porDefecto }.map { it.clave }
	}

	fun guardar(c: Context, zona: ZonaAcciones, claves: List<String>) {
		PreferenceManager.getDefaultSharedPreferences(c).edit().putString(preferencia(zona), claves.joinToString(",")).apply()
	}

	fun restablecer(c: Context, zona: ZonaAcciones) {
		val editor = PreferenceManager.getDefaultSharedPreferences(c).edit().remove(preferencia(zona))
		if (zona == ZonaAcciones.PARRAFO) editor.remove("acciones_parrafo")
		editor.apply()
	}

	private val PARAMETRO = Regex("""%(\d+\$)?[sd]""")

	/** El texto de la cadena sin rellenar ("Abrir enlace: %1$s"). */
	private fun plantilla(c: Context, id: Int): String = c.resources.getText(id).toString()

	private val reconocedores = HashMap<String, List<Pair<Regex, String>>>()

	/** Para reconocer de qué entrada es cada texto (las cadenas con datos, por su parte fija). */
	private fun reconocedor(c: Context, zona: ZonaAcciones): List<Pair<Regex, String>> {
		val idioma = c.resources.configuration.locales[0].toLanguageTag()
		return reconocedores.getOrPut("$idioma/${zona.name}") {
			entradas(c, zona).flatMap { e ->
				e.textos.map { id ->
					val partes = plantilla(c, id).split(PARAMETRO).map { Regex.escape(it) }
					Regex(partes.joinToString(".*"), RegexOption.DOT_MATCHES_ALL) to e.clave
				}
			}
		}
	}

	fun claveDe(c: Context, zona: ZonaAcciones, texto: String): String? =
		reconocedor(c, zona).firstOrNull { it.first.matches(texto) }?.second

	/** Deja solo las acciones elegidas en esa zona, en el orden elegido; las desconocidas, al final. */
	fun aplicar(c: Context, zona: ZonaAcciones, acciones: List<Accion>): List<Accion> {
		val orden = elegidas(c, zona)
		val elegidas = ArrayList<Pair<Int, Accion>>()
		val otras = ArrayList<Accion>()
		for (a in acciones) {
			val clave = claveDe(c, zona, a.first)
			if (clave == null) otras += a
			else orden.indexOf(clave).takeIf { it >= 0 }?.let { elegidas += it to a }
		}
		// sortedBy es estable: las de una misma entrada (subir y bajar en la cola) siguen en su orden
		return elegidas.sortedBy { it.first }.map { it.second } + otras
	}
}

/** Pone las acciones de TalkBack de una fila según lo que el usuario eligió para esa zona. */
fun View.accionesAccesibles(acciones: List<Accion>, zona: ZonaAcciones?) =
	accionesAccesibles(if (zona == null) acciones else AccionesTalkBack.aplicar(context, zona, acciones))
