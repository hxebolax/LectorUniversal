package com.xebolax.lector.datos

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.xebolax.lector.BuildConfig
import com.xebolax.lector.formatos.OpcionesLectura
import org.json.JSONArray

/**
 * Ajustes de la app con valores por defecto. Las claves son las mismas que
 * las de las pantallas de Ajustes (res/xml/ajustes_*.xml).
 */
class Preferencias(private val contexto: Context) {

	val p: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(contexto.applicationContext)

	private fun entero(clave: String, defecto: Int): Int =
		runCatching { p.getInt(clave, defecto) }.getOrElse { p.getString(clave, null)?.toIntOrNull() ?: defecto }

	private fun texto(clave: String, defecto: String) = p.getString(clave, defecto) ?: defecto

	// --- General
	val temaApp get() = texto("tema_app", "sistema")
	val abrirUltimo get() = p.getBoolean("abrir_ultimo", false)
	val sonidos get() = p.getBoolean("sonidos", true)
	val vibracion get() = p.getBoolean("vibracion", false)
	val buscarActualizaciones get() = p.getBoolean("buscar_actualizaciones", true)
	val urlActualizaciones get() = texto("url_actualizaciones", BuildConfig.URL_ACTUALIZACIONES).trim().trimEnd('/')
		.ifBlank { BuildConfig.URL_ACTUALIZACIONES }
	/** De dónde se actualiza: servidor (propio), github, ambos (la más nueva de los dos) o personalizada. */
	val fuenteActualizaciones get() = texto("fuente_actualizaciones", "ambos")
	/** Fuente personalizada: una carpeta web con version.json o un repositorio de GitHub. */
	val urlPersonalizada get() = texto("url_personalizada", "").trim().trimEnd('/')

	// --- Aspecto del texto
	val temaLectura get() = texto("tema_lectura", "app")
	val tamanoLetra get() = entero("tamano_letra", 20)
	val fuente get() = texto("fuente", "sistema")
	val interlineado get() = entero("interlineado", 130)
	/** Espacio entre letras en centésimas de em (0 = normal). */
	val espacioLetras get() = entero("espacio_letras", 0)
	val margen get() = entero("margen", 16)
	val espacioParrafos get() = entero("espacio_parrafos", 8)
	val justificar get() = p.getBoolean("justificar", false)
	val negrita get() = p.getBoolean("negrita", false)
	val mantenerPantalla get() = p.getBoolean("mantener_pantalla", true)
	val orientacion get() = texto("orientacion", "sistema")
	val ocultarBarras get() = p.getBoolean("ocultar_barras", false)

	// --- Navegación
	val teclasVolumen get() = texto("teclas_volumen", "nada")
	/** Filas de botones en la pantalla de lectura (lo que no cabe va al menú Más; 4 = todas las necesarias). */
	val filasBotonera get() = entero("filas_botonera", 2).coerceIn(1, 4)
	/** Botones de lectura que van al menú Más aunque quepan (nombres de recurso: boton_ia...). */
	val botonesOcultos: Set<String> get() = p.getStringSet("botones_ocultos", null) ?: emptySet()
	val elementoNavegacion get() = texto("elemento_navegacion", "ENCABEZADO")
	val barraNavegacion get() = p.getBoolean("barra_navegacion", true)
	val toqueParrafo get() = texto("toque_parrafo", "menu")
	val anunciarSaltos get() = p.getBoolean("anunciar_saltos", true)
	val navegacionCircular get() = p.getBoolean("navegacion_circular", false)
	val sonidoMarcadores get() = p.getBoolean("sonido_marcadores", true)
	val anunciarNivel get() = p.getBoolean("anunciar_nivel", true)
	val acciones: Set<String> get() = p.getStringSet("acciones_parrafo", null) ?: ACCIONES_POR_DEFECTO
	/** Sonidos al llegar a un elemento (encabezado, enlace, tabla...); vacío = ninguno. */
	val sonidosElementos: Set<String> get() = p.getStringSet("sonidos_elementos", null) ?: emptySet()
	/** Qué se anuncia de cada párrafo además del texto (tipo, página, marcador, búsqueda). */
	val detalleParrafos: Set<String> get() = p.getStringSet("detalle_parrafos", null) ?: setOf("tipo", "pagina", "marcador", "busqueda")
	val textoSeleccionable get() = p.getBoolean("texto_seleccionable", false)

	// --- Conversión de documentos
	val opcionesLectura: OpcionesLectura
		get() = OpcionesLectura(
			tablasConEncabezados = p.getBoolean("tablas_encabezados", true),
			pdfUnirLineas = p.getBoolean("pdf_unir_lineas", true),
			txtLineaAParrafo = p.getBoolean("txt_linea_parrafo", false),
			dividirParrafos = texto("dividir_parrafos", "0").toIntOrNull() ?: 0,
			leerImagenes = p.getBoolean("leer_imagenes", true),
			brailleComoTexto = p.getBoolean("braille_texto", true),
		)

	// --- Voz
	val motorVoz get() = texto("motor_voz", "")
	val voz get() = texto("voz", "")
	/** Velocidad en porcentaje (100 = normal). */
	val velocidadVoz get() = entero("velocidad_voz", 100)
	val tonoVoz get() = entero("tono_voz", 100)
	val pausaParrafos get() = entero("pausa_parrafos", 0)
	val pausaFrases get() = entero("pausa_frases", 0)
	val resaltarFrase get() = p.getBoolean("resaltar_frase", true)
	val seguirLectura get() = p.getBoolean("seguir_lectura", true)
	val vozSegunIdioma get() = p.getBoolean("voz_segun_idioma", true)
	/** Detectar el idioma de los párrafos que el libro no marca (para cambiar de voz). */
	val detectarIdioma get() = p.getBoolean("detectar_idioma", false)
	val saltarNotas get() = p.getBoolean("saltar_notas", false)
	/** Seguir al volver a conectar los auriculares (si se pausó al quitarlos hace poco). */
	val reanudarAlConectar get() = p.getBoolean("reanudar_al_conectar", true)
	/** Temporizador automático: al reproducir dentro de una franja horaria (por ejemplo, por la noche). */
	val temporizadorAuto get() = p.getBoolean("temporizador_auto", false)
	val temporizadorAutoDesde get() = p.getString("temporizador_auto_desde", "23:00") ?: "23:00"
	val temporizadorAutoHasta get() = p.getString("temporizador_auto_hasta", "07:00") ?: "07:00"
	val temporizadorAutoMinutos get() = p.getString("temporizador_auto_minutos", "30")?.toIntOrNull() ?: 30
	/** Al leer en voz alta, saltar direcciones web, lo que va entre paréntesis y entre corchetes. */
	val saltarUrls get() = p.getBoolean("saltar_urls", false)
	val saltarParentesis get() = p.getBoolean("saltar_parentesis", false)
	val saltarCorchetes get() = p.getBoolean("saltar_corchetes", false)
	val leerPaginas get() = p.getBoolean("leer_paginas", false)
	val destacarEncabezados get() = p.getBoolean("destacar_encabezados", true)
	val pronunciacionActiva get() = p.getBoolean("pronunciacion_activa", true)
	val minutosTemporizador get() = entero("minutos_temporizador", 30)
	/** Cuando habla TalkBack: "bajar" (suena más bajo), "pausar" (el libro calla y sigue después) o "seguir". */
	val talkbackAlHablar get() = texto("talkback_al_hablar", "bajar")
	/** Volumen del libro (en %) mientras habla TalkBack, con "bajar". */
	val volumenConTalkBack get() = entero("volumen_talkback", 25).coerceIn(5, 90)

	/** Cambios de valores por defecto entre versiones (una sola vez). */
	fun migrar() {
		if (p.getInt("migracion", 0) < 1) {
			// 1.0.4: bajar el volumen mientras habla TalkBack pasa a ser lo normal
			p.edit().remove("talkback_al_hablar").putInt("migracion", 1).apply()
		}
	}
	/** Agitar el móvil prolonga el temporizador (o reanuda si acaba de parar). */
	val agitarTemporizador get() = p.getBoolean("agitar_temporizador", true)
	val continuarSiguiente get() = p.getBoolean("continuar_siguiente", false)

	// --- Inteligencia artificial
	/** "auto" (el móvil si puede, si no la primera clave), "dispositivo", "gemini", "openai", "claude" o "ninguno". */
	val iaProveedor get() = texto("ia_proveedor", "auto")
	// Las claves están aparte, con las demás contraseñas (Secretos)
	val iaClaveGemini get() = Secretos.leer(contexto, "ia_clave_gemini").trim()
	val iaClaveOpenai get() = Secretos.leer(contexto, "ia_clave_openai").trim()
	val iaClaveClaude get() = Secretos.leer(contexto, "ia_clave_claude").trim()
	val iaModeloGemini get() = texto("ia_modelo_gemini", "").trim().ifBlank { com.xebolax.lector.ia.AsistenteIA.MODELO_GEMINI }
	val iaModeloOpenai get() = texto("ia_modelo_openai", "").trim().ifBlank { com.xebolax.lector.ia.AsistenteIA.MODELO_OPENAI }
	val iaModeloClaude get() = texto("ia_modelo_claude", "").trim().ifBlank { com.xebolax.lector.ia.AsistenteIA.MODELO_CLAUDE }

	// --- Audio
	val velocidadAudio get() = entero("velocidad_audio", 100)
	val saltoAtras get() = entero("salto_atras", 10)
	val saltoAdelante get() = entero("salto_adelante", 30)
	val retrocesoReanudar get() = entero("retroceso_reanudar", 2)
	val saltarSilencios get() = p.getBoolean("saltar_silencios", false)
	val refuerzoVolumen get() = entero("refuerzo_volumen", 0)
	/** Qué hacen "siguiente" y "anterior" de los auriculares en audiolibros: capitulo o salto. */
	val botonesAuriculares get() = texto("botones_auriculares", "capitulo")
	val reproducirAlAbrir get() = p.getBoolean("reproducir_al_abrir", false)

	// --- Biblioteca
	var ordenBiblioteca: Orden
		get() = runCatching { Orden.valueOf(texto("orden_biblioteca", Orden.TITULO.name)) }.getOrDefault(Orden.TITULO)
		set(valor) = p.edit().putString("orden_biblioteca", valor.name).apply()

	/** Carpetas del dispositivo que se revisan para añadir libros nuevos (direcciones del selector). */
	var carpetasVigiladas: List<String>
		get() = runCatching { JSONArray(texto("carpetas_vigiladas", "[]")).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
		set(valor) = p.edit().putString("carpetas_vigiladas", JSONArray(valor).toString()).apply()

	/** Historial de búsquedas en los libros (las más recientes primero). */
	var historialBusquedas: List<String>
		get() = runCatching { JSONArray(texto("historial_busquedas", "[]")).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
		set(valor) = p.edit().putString("historial_busquedas", JSONArray(valor.distinct().take(30)).toString()).apply()

	companion object {
		val ACCIONES_POR_DEFECTO = setOf(
			"leer_aqui", "marcador", "nota", "copiar", "siguiente_encabezado", "anterior_encabezado", "indice", "buscar", "ir_a",
		)
	}
}
