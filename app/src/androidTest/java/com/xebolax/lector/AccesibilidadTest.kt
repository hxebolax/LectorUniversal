package com.xebolax.lector

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.Parameters
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
import com.google.android.apps.common.testing.accessibility.framework.utils.contrast.BitmapImage
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.formatos.Formato
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Recorre todas las pantallas de la app con TalkBack activo, como quien
 * desliza el dedo de elemento en elemento: pone el foco de accesibilidad en
 * cada uno (TalkBack lo lee de verdad; con su registro detallado se ve qué
 * dice) y pasa las comprobaciones del Accessibility Test Framework de Google
 * (las de Accessibility Scanner: etiquetas, tamaño de los botones, contraste,
 * duplicados...). Todo queda en el registro con las etiquetas "Recorrido" y
 * "ATF". No falla: es un informe para revisar.
 *
 * Se pasa con TalkBack encendido en el emulador:
 * gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.xebolax.lector.AccesibilidadTest
 */
@RunWith(AndroidJUnit4::class)
class AccesibilidadTest {

	private val instrumentacion = InstrumentationRegistry.getInstrumentation()
	private lateinit var app: Context
	private lateinit var es: Context
	private lateinit var ui: UiAutomation
	private var libro = 0L
	private var audiolibro = 0L

	@Before
	fun preparar() {
		app = instrumentacion.targetContext
		ui = instrumentacion.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
		// La app en español, como la usa el usuario, y sin el aviso de permiso de notificaciones
		ui.executeShellCommand("cmd locale set-app-locales ${app.packageName} --locales es-ES").close()
		if (android.os.Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
		esperar(1500)
		es = app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag("es-ES")) })
		// Sin suprimir TalkBack: tiene que seguir hablando mientras se recorre
		ui = instrumentacion.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
		val carpeta = File(app.filesDir, "muestras_a11y").apply { mkdirs() }
		fun copiar(nombre: String): File = File(carpeta, nombre).also { f ->
			if (!f.exists()) instrumentacion.context.assets.open("muestras/$nombre").use { e -> f.outputStream().use { e.copyTo(it) } }
		}
		val db = BaseDatos.obtener(app)
		val epub = copiar("vencejo.epub")
		val m4b = copiar("prueba.m4b")
		libro = db.libroPorUri(Uri.fromFile(epub).toString())?.id
			?: db.anadirLibro(Uri.fromFile(epub).toString(), epub.name, "El vencejo", "Autor de prueba", Formato.EPUB.name, false, epub.length(), null)
		audiolibro = db.libroPorUri(Uri.fromFile(m4b).toString())?.id
			?: db.anadirLibro(Uri.fromFile(m4b).toString(), m4b.name, "Audiolibro de prueba", "Narrador", Formato.deExtension("m4b").name, true, m4b.length(), null)
	}

	// --- Utilidades ----------------------------------------------------------------------------

	private fun esperar(ms: Long) = Thread.sleep(ms)

	private fun abrir(intent: Intent, espera: Long = 4000) {
		app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
		esperar(espera)
	}

	private fun raiz(): AccessibilityNodeInfo? {
		repeat(10) {
			ui.rootInActiveWindow?.let { return it }
			esperar(300)
		}
		return null
	}

	/** Toca el primer elemento con ese texto o descripción (como un doble toque de TalkBack). */
	private fun tocar(texto: String, otro: String? = null): Boolean {
		val r = raiz() ?: return false
		val candidatos = (r.findAccessibilityNodeInfosByText(texto) + (otro?.let { r.findAccessibilityNodeInfosByText(it) } ?: emptyList()))
			.filter { it.isVisibleToUser }
		// Primero el que se llama exactamente así (la pestaña «Sin leer», no el botón «Ver: Sin leer»)
		fun nombre(n: AccessibilityNodeInfo) = (n.contentDescription ?: n.text)?.toString()?.trim()
		val nodo = candidatos.firstOrNull { nombre(it) == texto || nombre(it) == otro } ?: candidatos.firstOrNull() ?: return false
		var n: AccessibilityNodeInfo? = nodo
		while (n != null && !n.isClickable) n = n.parent
		val ok = (n ?: nodo).performAction(AccessibilityNodeInfo.ACTION_CLICK)
		esperar(2500)
		return ok
	}

	private fun texto(n: AccessibilityNodeInfo): String {
		val partes = listOfNotNull(
			n.contentDescription?.toString()?.takeIf { it.isNotBlank() } ?: n.text?.toString()?.takeIf { it.isNotBlank() },
			if (android.os.Build.VERSION.SDK_INT >= 30) n.stateDescription?.toString()?.takeIf { it.isNotBlank() } else null,
		)
		return partes.joinToString(", ")
	}

	/** ¿TalkBack se pararía en este elemento al deslizar? (tiene acción, o texto sin un padre con acción) */
	private fun enfocable(n: AccessibilityNodeInfo): Boolean {
		if (!n.isVisibleToUser) return false
		// TalkBack no se para en los contenedores con desplazamiento que tienen elementos dentro
		val clase = n.className?.toString().orEmpty()
		if ((n.isScrollable || clase.endsWith("ScrollView") || clase.endsWith("RecyclerView")) && n.childCount > 0) return false
		if (n.isClickable || n.isLongClickable || n.isFocusable || (android.os.Build.VERSION.SDK_INT >= 28 && n.isScreenReaderFocusable)) return true
		if (n.text.isNullOrBlank() && n.contentDescription.isNullOrBlank()) return false
		var p = n.parent
		while (p != null) {
			// Un ScrollView es «enfocable» (para el teclado), pero TalkBack sí visita los textos de dentro
			if (!p.isScrollable && (p.isClickable || p.isFocusable || (android.os.Build.VERSION.SDK_INT >= 28 && p.isScreenReaderFocusable))) return false
			p = p.parent
		}
		return true
	}

	private fun recoger(n: AccessibilityNodeInfo, lista: MutableList<AccessibilityNodeInfo>) {
		// Lo que TalkBack no visita (una vista marcada como no importante dentro de un control propio) no cuenta
		if (enfocable(n) && n.isImportantForAccessibility) lista += n
		for (i in 0 until n.childCount) n.getChild(i)?.let { recoger(it, lista) }
	}

	/** Recorre la pantalla con el foco de TalkBack y pasa las comprobaciones de accesibilidad. */
	private fun revisar(pantalla: String, maximo: Int = 45) {
		val r = raiz() ?: run { Log.w("Recorrido", "$pantalla | SIN VENTANA"); return }
		Log.i("Recorrido", "=== $pantalla ===")
		val nodos = mutableListOf<AccessibilityNodeInfo>()
		recoger(r, nodos)
		nodos.take(maximo).forEachIndexed { i, n ->
			val clase = n.className?.toString()?.substringAfterLast('.').orEmpty()
			val sinNombre = texto(n).isBlank()
			Log.i("Recorrido", "$pantalla | ${i + 1} | $clase | ${if (sinNombre) "¡SIN NOMBRE!" else texto(n)}" +
				(if (n.isClickable) " | pulsable" else "") + (if (n.actionList.size > 6) " | ${n.actionList.size} acciones" else ""))
			n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
			esperar(700)
			// Deslizadores: deslizar arriba como con TalkBack, ver qué valor dice y volver
			if (clase == "SeekBar" && n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) {
				val antes = texto(n)
				n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
				esperar(900)
				n.refresh()
				Log.i("Recorrido", "$pantalla | DESLIZADOR | $antes  →  ${texto(n)}")
				n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
				esperar(600)
			}
		}
		if (nodos.size > maximo) Log.i("Recorrido", "$pantalla | ... ${nodos.size - maximo} más")
		comprobar(pantalla, r)
	}

	private fun comprobar(pantalla: String, r: AccessibilityNodeInfo) {
		runCatching {
			val jerarquia = AccessibilityHierarchyAndroid.newBuilder(r, app).build()
			val parametros = Parameters()
			ui.takeScreenshot()?.let { parametros.putScreenCapture(BitmapImage(it)) }
			val comprobaciones = AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(AccessibilityCheckPreset.LATEST)
			for (c in comprobaciones) {
				for (res in c.runCheckOnHierarchy(jerarquia, null, parametros)) {
					if (res.type != AccessibilityCheckResultType.ERROR && res.type != AccessibilityCheckResultType.WARNING) continue
					val e = res.element
					val quien = listOfNotNull(e?.contentDescription?.toString(), e?.text?.toString(), e?.className?.toString()?.substringAfterLast('.'))
						.firstOrNull { it.isNotBlank() }.orEmpty()
					Log.w("ATF", "$pantalla | ${res.type} | ${c.javaClass.simpleName} | $quien | ${res.getMessage(Locale.getDefault())}")
				}
			}
		}.onFailure { Log.e("ATF", "$pantalla | no se pudo comprobar: $it") }
	}

	private fun intent(clase: Class<*>) = Intent(app, clase)

	private fun idPestana(nombre: String) = when (nombre) {
		es.getString(R.string.pestana_biblioteca) -> R.string.pestana_biblioteca
		es.getString(R.string.pestana_marcadores) -> R.string.pestana_marcadores
		else -> R.string.pestana_catalogos
	}

	// --- El recorrido ----------------------------------------------------------------------------

	@Test
	fun recorrerTodo() {
		val principal = intent(com.xebolax.lector.ui.BibliotecaActivity::class.java).setAction(Intent.ACTION_MAIN)
		abrir(principal)
		revisar("Biblioteca: Leyendo")
		for (pestana in listOf(es.getString(R.string.pestana_biblioteca), es.getString(R.string.pestana_marcadores), es.getString(R.string.pestana_catalogos))) {
			tocar(pestana, app.getString(idPestana(pestana)))
			revisar("Biblioteca: $pestana")
		}
		tocar(es.getString(R.string.abrir_menu), app.getString(R.string.abrir_menu))
		revisar("Menú lateral")

		abrir(intent(com.xebolax.lector.podcast.PodcastActivity::class.java), 6000)
		for (m in com.xebolax.lector.podcast.PodcastListaFragment.PESTANAS) {
			tocar(es.getString(m.nombre), app.getString(m.nombre))
			revisar("Podcasts: ${es.getString(m.nombre)}")
		}
		abrir(intent(com.xebolax.lector.rss.RssActivity::class.java), 8000)
		for (m in com.xebolax.lector.rss.RssListaFragment.Modo.entries) {
			tocar(es.getString(m.nombre), app.getString(m.nombre))
			revisar("Noticias: ${es.getString(m.nombre)}")
		}

		// Ajustes, con sus pestañas abajo: cada pestaña y, en General y Biblioteca, cada apartado
		val pestanasAjustes = listOf(R.string.aj_pestana_general, R.string.aj_pestana_biblioteca, R.string.aj_pestana_podcasts, R.string.aj_pestana_noticias, R.string.aj_pestana_radio)
		for (p in pestanasAjustes) {
			abrir(intent(com.xebolax.lector.ui.AjustesActivity::class.java), 2500)
			tocar(es.getString(p), app.getString(p))
			esperar(1500)
			revisar("Ajustes: pestaña ${es.getString(p)}", 60)
			if (p != R.string.aj_pestana_general && p != R.string.aj_pestana_biblioteca) continue
			// Cada apartado, entrando como el usuario: tocándolo en la lista
			val apartados = mutableListOf<String>()
			raiz()?.let { r ->
				val filas = mutableListOf<AccessibilityNodeInfo>()
				fun buscar(n: AccessibilityNodeInfo) {
					if (n.isClickable && n.className?.toString()?.endsWith("LinearLayout") == true) filas += n
					for (k in 0 until n.childCount) n.getChild(k)?.let { buscar(it) }
				}
				buscar(r)
				for (f in filas) {
					fun primerTexto(n: AccessibilityNodeInfo): String? {
						n.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
						for (k in 0 until n.childCount) n.getChild(k)?.let { h -> primerTexto(h)?.let { return it } }
						return null
					}
					primerTexto(f)?.let { apartados += it }
				}
			}
			for (apartado in apartados) {
				abrir(intent(com.xebolax.lector.ui.AjustesActivity::class.java), 2500)
				tocar(es.getString(p), app.getString(p))
				esperar(1200)
				tocar(apartado)
				revisar("Ajustes: $apartado", 60)
			}
		}

		// Compartidos y sus pestañas
		abrir(intent(com.xebolax.lector.compartidos.CompartidosActivity::class.java), 3000)
		for (m in com.xebolax.lector.compartidos.CompartidosFragment.Modo.entries) {
			tocar(es.getString(m.nombre), app.getString(m.nombre))
			revisar("Compartidos: ${es.getString(m.nombre)}")
		}
		// Pestañas y tipos de la Biblioteca, y la voz propia de un libro y de las noticias
		abrir(intent(com.xebolax.lector.ui.PestanasBibliotecaActivity::class.java))
		revisar("Pestañas y tipos")
		com.xebolax.lector.voz.VozPerfilActivity.abrirLibro(app.applicationContext.let { c ->
			object : android.content.ContextWrapper(c) {
				override fun startActivity(i: Intent) = super.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
			}
		}, libro, "Libro de prueba")
		esperar(4000)
		revisar("Voz de un libro", 30)
		com.xebolax.lector.voz.VozPerfilActivity.abrirNoticias(app.applicationContext.let { c ->
			object : android.content.ContextWrapper(c) {
				override fun startActivity(i: Intent) = super.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
			}
		})
		esperar(4000)
		revisar("Voz de las noticias", 30)

		abrir(intent(com.xebolax.lector.ui.AccionesTalkBackActivity::class.java))
		revisar("Acciones de TalkBack")
		abrir(intent(com.xebolax.lector.ui.AccionesTalkBackActivity::class.java).putExtra(com.xebolax.lector.ui.AccionesTalkBackActivity.EXTRA_ZONA, com.xebolax.lector.ui.ZonaAcciones.EPISODIO.name))
		revisar("Acciones de TalkBack: episodios", 25)
		abrir(intent(com.xebolax.lector.lectura.LectorActivity::class.java).putExtra(com.xebolax.lector.lectura.LectorActivity.EXTRA_LIBRO, libro), 8000)
		revisar("Lector (EPUB)", 30)
		abrir(intent(com.xebolax.lector.audio.ReproductorActivity::class.java).putExtra(com.xebolax.lector.lectura.LectorActivity.EXTRA_LIBRO, audiolibro), 6000)
		revisar("Reproductor (audiolibro)")
		abrir(intent(com.xebolax.lector.ui.EstadisticasActivity::class.java))
		revisar("Estadísticas")
		com.xebolax.lector.podcast.PodcastFichaActivity.abrir(app.applicationContext.let { c ->
			object : android.content.ContextWrapper(c) {
				override fun startActivity(i: Intent) = super.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
			}
		}, "https://feeds.npr.org/510289/podcast.xml", "Planet Money")
		esperar(9000)
		revisar("Ficha de podcast", 25)
	}

	/** La Radio: sus pestañas, una lista del directorio, el reproductor y el mini reproductor en las otras secciones. */
	@Test
	fun radio() {
		abrir(intent(com.xebolax.lector.radio.RadioActivity::class.java), 5000)
		for (m in com.xebolax.lector.radio.RadioListaFragment.PESTANAS) {
			tocar(es.getString(m.nombre), app.getString(m.nombre))
			revisar("Radio: ${es.getString(m.nombre)}")
		}
		tocar(es.getString(com.xebolax.lector.radio.RadioListaFragment.Modo.EXPLORAR.nombre), app.getString(com.xebolax.lector.radio.RadioListaFragment.Modo.EXPLORAR.nombre))
		tocar(es.getString(R.string.radio_mas_escuchadas), app.getString(R.string.radio_mas_escuchadas))
		esperar(9000)
		revisar("Radio: las más escuchadas", 20)
		// Una emisora en directo: el reproductor de radio
		val emisora = com.xebolax.lector.radio.Emisora(uuid = "prueba-bbc", nombre = "BBC World Service",
			url = "http://stream.live.vc.bbcmedia.co.uk/bbc_world_service", pais = "United Kingdom", codigoPais = "GB", etiquetas = "news,talk", codec = "MP3", bitrate = 56)
		InstrumentationRegistry.getInstrumentation().runOnMainSync {
			com.xebolax.lector.radio.Radio.escuchar(app.applicationContext.let { c ->
				object : android.content.ContextWrapper(c) {
					override fun startActivity(i: Intent) = super.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
				}
			}, emisora, abrirPantalla = true)
		}
		esperar(9000)
		revisar("Radio: reproductor")
		// El mini reproductor, encima de las pestañas de las otras secciones
		abrir(intent(com.xebolax.lector.rss.RssActivity::class.java), 5000)
		revisar("Noticias con la radio sonando", 60)
		InstrumentationRegistry.getInstrumentation().runOnMainSync { com.xebolax.lector.audio.ControlAudio.detener() }
	}

	/** La actividad que está delante (para comprobar adónde lleva Atrás). */
	private fun delante(): String {
		val salida = ui.executeShellCommand("dumpsys activity activities").let { d ->
			android.os.ParcelFileDescriptor.AutoCloseInputStream(d).bufferedReader().use { it.readText() }
		}
		return Regex("topResumedActivity=.*?/([^ }]+)").find(salida)?.groupValues?.get(1).orEmpty()
	}

	/**
	 * La Portada: un encabezado por sección (en el orden de Ajustes), Atrás desde
	 * la pantalla principal de una sección vuelve a ella y, desde ella, sale; el
	 * orden de las secciones se cambia con las acciones de TalkBack; y las
	 * estadísticas de todas las secciones.
	 */
	@Test
	fun portada() {
		com.xebolax.lector.ui.OrdenSecciones.guardar(app, com.xebolax.lector.ui.OrdenSecciones.ORDENABLES)
		BaseDatos.obtener(app).marcarAbierto(libro)
		com.xebolax.lector.radio.RadioBaseDatos.obtener(app).apuntarEscuchada(com.xebolax.lector.radio.Emisora(uuid = "prueba-portada", nombre = "Emisora de prueba",
			url = "http://127.0.0.1/radio", pais = "Spain", codigoPais = "ES", etiquetas = "news"))
		abrir(intent(com.xebolax.lector.ui.PortadaActivity::class.java).setAction(Intent.ACTION_MAIN), 4000)
		revisar("Portada", 40)
		val nodos = mutableListOf<AccessibilityNodeInfo>().also { l -> raiz()?.let { recoger(it, l) } }
		val encabezados = nodos.filter { it.isHeading }.map { nombre(it) }
		Log.i("Portada", "Encabezados: $encabezados")
		org.junit.Assert.assertEquals(5, encabezados.size)
		org.junit.Assert.assertTrue(encabezados[0], encabezados[0].startsWith(es.getString(R.string.seccion_biblioteca)) || encabezados[0].startsWith("Library"))
		org.junit.Assert.assertTrue(nodos.any { nombre(it).startsWith("El vencejo") })
		org.junit.Assert.assertTrue(nodos.any { nombre(it).startsWith("Emisora de prueba") })

		// Biblioteca desde su encabezado; Atrás vuelve a la Portada
		tocar(encabezados[0])
		org.junit.Assert.assertTrue(delante(), delante().endsWith("BibliotecaActivity"))
		atras()
		esperar(2000)
		org.junit.Assert.assertTrue(delante(), delante().endsWith("PortadaActivity") || delante().endsWith("PrincipalActivity"))
		// Noticias desde el menú lateral; Atrás vuelve a la Portada
		tocar(es.getString(R.string.abrir_menu), app.getString(R.string.abrir_menu))
		tocar(es.getString(R.string.noticias), app.getString(R.string.noticias))
		org.junit.Assert.assertTrue(delante(), delante().endsWith("RssActivity"))
		atras()
		esperar(2000)
		org.junit.Assert.assertTrue(delante(), delante().endsWith("PortadaActivity") || delante().endsWith("PrincipalActivity"))

		// Orden de las secciones: Podcasts baja una posición con su acción de TalkBack
		abrir(intent(com.xebolax.lector.ui.SeccionesActivity::class.java))
		revisar("Ajustes: orden de las secciones")
		val podcasts = elemento(es.getString(R.string.podcasts))
		val bajar = podcasts?.actionList?.firstOrNull { it.label?.toString() == es.getString(R.string.accion_bajar) || it.label?.toString() == "Move down one position" }
		org.junit.Assert.assertNotNull("Podcasts sin la acción Bajar", bajar)
		podcasts!!.performAction(bajar!!.id)
		esperar(1500)
		org.junit.Assert.assertEquals(com.xebolax.lector.ui.Seccion.PODCASTS, com.xebolax.lector.ui.OrdenSecciones.orden(app)[3])
		// Doble toque: la quita de la Portada (y otra vez la vuelve a poner)
		elemento(es.getString(R.string.podcasts))?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
		esperar(1000)
		org.junit.Assert.assertFalse(com.xebolax.lector.ui.OrdenSecciones.enPortada(app, com.xebolax.lector.ui.Seccion.PODCASTS))
		abrir(intent(com.xebolax.lector.ui.PortadaActivity::class.java).setAction(Intent.ACTION_MAIN), 4000)
		val sinPodcasts = mutableListOf<AccessibilityNodeInfo>().also { l -> raiz()?.let { recoger(it, l) } }.filter { it.isHeading }.map { nombre(it) }
		Log.i("Portada", "Sin Podcasts: $sinPodcasts")
		org.junit.Assert.assertEquals(4, sinPodcasts.size)
		com.xebolax.lector.ui.OrdenSecciones.ponerEnPortada(app, com.xebolax.lector.ui.Seccion.PODCASTS, true)
		com.xebolax.lector.ui.OrdenSecciones.guardar(app, com.xebolax.lector.ui.OrdenSecciones.ORDENABLES)

		// El tiempo escuchando la radio cuenta en sus estadísticas
		val radioAntes = com.xebolax.lector.datos.Actividad.desde(app, com.xebolax.lector.datos.Actividad.RADIO, BaseDatos.hoy())
		InstrumentationRegistry.getInstrumentation().runOnMainSync {
			com.xebolax.lector.radio.Radio.escuchar(app, com.xebolax.lector.radio.Emisora(uuid = "prueba-bbc", nombre = "BBC World Service",
				url = "http://stream.live.vc.bbcmedia.co.uk/bbc_world_service", pais = "United Kingdom", codigoPais = "GB", codec = "MP3", bitrate = 56), abrirPantalla = false)
		}
		esperar(12000)
		InstrumentationRegistry.getInstrumentation().runOnMainSync { com.xebolax.lector.audio.ControlAudio.detener() }
		esperar(2000)
		val radioDespues = com.xebolax.lector.datos.Actividad.desde(app, com.xebolax.lector.datos.Actividad.RADIO, BaseDatos.hoy())
		Log.i("Portada", "Radio: antes $radioAntes, después $radioDespues")
		org.junit.Assert.assertTrue("No contó el tiempo de radio: $radioAntes -> $radioDespues", radioDespues.first > radioAntes.first && radioDespues.second > radioAntes.second)

		abrir(intent(com.xebolax.lector.ui.EstadisticasActivity::class.java))
		revisar("Estadísticas de todas las secciones", 80)
		val titulos = mutableListOf<AccessibilityNodeInfo>().also { l -> raiz()?.let { recoger(it, l) } }.filter { it.isHeading }.map { nombre(it) }
		Log.i("Portada", "Estadísticas: $titulos")
	}

	/** Copias de seguridad: el apartado, elegir qué guardar y la contraseña (con sus errores). */
	@Test
	fun copias() {
		abrir(intent(com.xebolax.lector.ui.AjustesActivity::class.java).putExtra(com.xebolax.lector.ui.AjustesActivity.EXTRA_APARTADO, "copias"), 3000)
		revisar("Ajustes: copias de seguridad")
		tocar(es.getString(R.string.copia_crear), app.getString(R.string.copia_crear))
		esperar(1500)
		revisar("Copia: elegir qué guardar", 30)
		val casillas = mutableListOf<AccessibilityNodeInfo>().also { l -> raiz()?.let { recoger(it, l) } }.filter { it.isCheckable }
		Log.i("Copias", "Casillas: ${casillas.map { "${nombre(it)} marcada=${it.isChecked}" }}")
		org.junit.Assert.assertTrue(casillas.size >= 2)
		tocar(es.getString(R.string.siguiente), app.getString(R.string.siguiente))
		esperar(1000)
		revisar("Copia: contraseña", 20)
		// Contraseñas distintas: el error sale en el segundo campo y no se cierra
		val campos = mutableListOf<AccessibilityNodeInfo>().also { l -> raiz()?.let { recoger(it, l) } }.filter { it.isEditable }
		org.junit.Assert.assertEquals(2, campos.size)
		campos[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "contraseña larga") })
		campos[1].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "otra distinta") })
		tocar(es.getString(R.string.guardar), app.getString(R.string.guardar))
		esperar(1000)
		val error = raiz()?.findAccessibilityNodeInfosByText(es.getString(R.string.copia_contrasenas_distintas))?.isNotEmpty() == true ||
			raiz()?.findAccessibilityNodeInfosByText(app.getString(R.string.copia_contrasenas_distintas))?.isNotEmpty() == true
		Log.i("Copias", "Error de contraseñas distintas visible: $error")
		org.junit.Assert.assertTrue(error)
		revisar("Copia: contraseña con error", 20)
		atras()
	}

	// --- El foco vuelve a su sitio al cerrar ventanas ------------------------------------------

	private fun conFoco(): AccessibilityNodeInfo? = raiz()?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

	/** El elemento pulsable visible que se llama exactamente así (o, si no hay, el que lo contiene). */
	private fun elemento(texto: String): AccessibilityNodeInfo? {
		val todos = raiz()?.findAccessibilityNodeInfosByText(texto)?.filter { it.isVisibleToUser }.orEmpty()
		val n = todos.firstOrNull { (it.contentDescription ?: it.text)?.toString()?.trim() == texto } ?: todos.firstOrNull() ?: return null
		var p: AccessibilityNodeInfo? = n
		while (p != null && !p.isClickable && !p.isLongClickable) p = p.parent
		return p ?: n
	}

	/** Pone el foco en el elemento, abre algo encima, lo cierra y mira si el foco ha vuelto al mismo sitio. */
	private fun vuelta(prueba: String, n: AccessibilityNodeInfo?, abrirlo: (AccessibilityNodeInfo) -> Unit, cerrarlo: () -> Unit) {
		if (n == null) { Log.w("Foco", "$prueba | NO ENCONTRADO"); return }
		n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
		esperar(1000)
		val antes = nombre(n)
		abrirlo(n)
		esperar(2500)
		val encima = raiz()?.let { r -> mutableListOf<AccessibilityNodeInfo>().also { recoger(r, it) }.take(4).joinToString(" / ") { nombre(it) } }
		// Qué eventos llegan a TalkBack al cerrar (cambios de ventana, foco...)
		ui.setOnAccessibilityEventListener { e ->
			if (e.eventType != android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
				Log.i("Eventos", "$prueba | ${android.view.accessibility.AccessibilityEvent.eventTypeToString(e.eventType)} | ${e.className} | ${e.text} | cambios=${e.contentChangeTypes} ventanas=${e.windowChanges}")
		}
		val accionesAntes = n.actionList.size
		cerrarlo()
		esperar(2500)
		ui.setOnAccessibilityEventListener(null)
		val despues = conFoco()
		Log.i("Foco", "$prueba | acciones antes=$accionesAntes después=${despues?.actionList?.size}")
		val bien = despues != null && (despues == n || nombre(despues) == antes)
		Log.i("Foco", "$prueba | ${if (bien) "BIEN" else "MAL"} | antes: $antes | encima: $encima | después: ${despues?.let { nombre(it) } ?: "nada"}")
	}

	/** Lo que diría TalkBack: su texto o, en una fila, el de lo que tiene dentro. */
	private fun nombre(n: AccessibilityNodeInfo): String = texto(n).ifBlank {
		(0 until n.childCount).mapNotNull { n.getChild(it)?.let { h -> nombre(h) } }.filter { it.isNotBlank() }.joinToString(", ")
	}

	private fun atras() {
		ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
	}

	/** Espera (hasta el máximo) a que aparezca un botón con ese texto y lo pulsa. */
	private fun pulsarCuandoSalga(texto: String, otro: String, maximo: Long = 45000) {
		val fin = System.currentTimeMillis() + maximo
		while (System.currentTimeMillis() < fin) {
			(elemento(texto) ?: elemento(otro))?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return }
			esperar(500)
		}
		Log.w("Foco", "no salió el botón $texto")
	}

	/** Solo la Biblioteca (para comprobar rápido los avisos del Accessibility Test Framework). */
	@Test
	fun soloBiblioteca() {
		abrir(intent(com.xebolax.lector.ui.BibliotecaActivity::class.java).setAction(Intent.ACTION_MAIN))
		tocar(es.getString(R.string.pestana_biblioteca), app.getString(R.string.pestana_biblioteca))
		revisar("Biblioteca: Biblioteca")
		raiz()?.findAccessibilityNodeInfosByText(es.getString(R.string.buscar_en_biblioteca))?.firstOrNull()?.let { n ->
			val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
			Log.i("Recorrido", "Campo de búsqueda: ${r.height() / app.resources.displayMetrics.density} dp de alto")
		}
	}

	/** Qué expone a TalkBack cada elemento del menú lateral (y si un solo doble toque lo activa). */
	@Test
	fun menuLateral() {
		abrir(intent(com.xebolax.lector.ui.BibliotecaActivity::class.java).setAction(Intent.ACTION_MAIN))
		tocar(es.getString(R.string.abrir_menu), app.getString(R.string.abrir_menu))
		esperar(1000)
		Log.i("Menu", "Foco al abrir: ${conFoco()?.let { "${nombre(it)} seleccionado=${it.isSelected}" }}")
		ui.executeShellCommand("screencap -p /sdcard/Download/menu_lateral.png").close()
		val r = raiz() ?: return
		val nodos = mutableListOf<AccessibilityNodeInfo>()
		recoger(r, nodos)
		for (n in nodos) {
			val info = n.collectionItemInfo
			Log.i("Menu", "${n.className?.toString()?.substringAfterLast('.')} | ${nombre(n)} | pulsable=${n.isClickable} marcable=${n.isCheckable} marcado=${n.isChecked} " +
				"seleccionado=${n.isSelected} enfocable=${n.isFocusable} item=${info?.let { "fila ${it.rowIndex} sel=${it.isSelected}" }} hijos=${n.childCount}")
		}
		// Un solo doble toque en Podcasts tiene que llevar a Podcasts
		val podcasts = nodos.firstOrNull { nombre(it) == es.getString(R.string.podcasts) || nombre(it) == app.getString(R.string.podcasts) }
		podcasts?.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
		esperar(1000)
		val ok = podcasts?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
		esperar(4000)
		val pantalla = mutableListOf<AccessibilityNodeInfo>().also { l -> raiz()?.let { recoger(it, l) } }.take(6).joinToString(" / ") { nombre(it) }
		Log.i("Menu", "Doble toque en Podcasts: clic=$ok | ahora: $pantalla")
		tocar(es.getString(R.string.abrir_menu), app.getString(R.string.abrir_menu))
		esperar(1000)
		Log.i("Menu", "En Podcasts, foco al abrir: ${conFoco()?.let { "${nombre(it)} seleccionado=${it.isSelected}" }}")
	}

	@Test
	fun focoVuelveTrasVentanas() {
		abrir(intent(com.xebolax.lector.ui.BibliotecaActivity::class.java).setAction(Intent.ACTION_MAIN))
		tocar(es.getString(R.string.pestana_biblioteca), app.getString(R.string.pestana_biblioteca))
		// Las acciones de un libro (pulsación larga) y volver
		vuelta("Libro: acciones", elemento("El vencejo"), { it.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }, ::atras)
		// El menú lateral y volver
		vuelta("Menú lateral", elemento(es.getString(R.string.abrir_menu)) ?: elemento(app.getString(R.string.abrir_menu)), { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }, ::atras)
		// Buscar actualizaciones: ventana de espera, luego el resultado, Aceptar y el foco en el botón
		abrir(intent(com.xebolax.lector.ui.AjustesActivity::class.java), 2500)
		tocar(es.getString(R.string.aj_general), app.getString(R.string.aj_general))
		vuelta("Buscar actualizaciones (espera + resultado)", elemento(es.getString(R.string.buscar_actualizaciones)) ?: elemento(app.getString(R.string.buscar_actualizaciones)),
			{ it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }, { pulsarCuandoSalga(es.getString(R.string.aceptar), app.getString(R.string.aceptar)) })
		// En el lector: las acciones de un párrafo y volver
		abrir(intent(com.xebolax.lector.lectura.LectorActivity::class.java).putExtra(com.xebolax.lector.lectura.LectorActivity.EXTRA_LIBRO, libro), 8000)
		val parrafo = raiz()?.let { r -> mutableListOf<AccessibilityNodeInfo>().also { recoger(r, it) }.filter { it.isLongClickable }.getOrNull(3) }
		vuelta("Lector: acciones del párrafo", parrafo, { it.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }, ::atras)
	}
}
