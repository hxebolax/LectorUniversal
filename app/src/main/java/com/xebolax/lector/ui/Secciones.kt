package com.xebolax.lector.ui

import android.content.Intent
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.xebolax.lector.BuildConfig
import com.xebolax.lector.R
import com.xebolax.lector.actualizaciones.GestorActualizaciones

/**
 * Las secciones de la app, como si fueran apps dentro de la app: Biblioteca,
 * Podcasts, Noticias y Radio. Todas igual: «Abrir menú» arriba a la izquierda (el
 * menú lateral, para ir de una a otra y a lo general) y sus pestañas abajo,
 * todas a la vista. La Portada es la pantalla de inicio: lo de cada sección a
 * mano, en el orden elegido en Ajustes.
 */
enum class Seccion(val menu: Int, val nombre: Int, val icono: Int) {
	PORTADA(R.id.lateral_portada, R.string.portada, R.drawable.ic_portada),
	BIBLIOTECA(R.id.lateral_biblioteca, R.string.seccion_biblioteca, R.drawable.ic_biblioteca),
	COMPARTIDOS(R.id.lateral_compartidos, R.string.compartidos, R.drawable.ic_compartido),
	PODCASTS(R.id.lateral_podcasts, R.string.podcasts, R.drawable.ic_voz),
	NOTICIAS(R.id.lateral_noticias, R.string.noticias, R.drawable.ic_rss),
	RADIO(R.id.lateral_radio, R.string.radio, R.drawable.ic_radio),
}

/**
 * El orden de las secciones (menú lateral, Portada y Estadísticas) y cuáles
 * salen en la Portada; se eligen en Ajustes, Portada y secciones.
 */
object OrdenSecciones {
	private const val CLAVE_ORDEN = "orden_secciones"
	private const val CLAVE_OCULTAS = "portada_ocultas"
	/** Las que se ordenan (la Portada va siempre la primera). */
	val ORDENABLES = Seccion.entries - Seccion.PORTADA

	private fun prefs(c: android.content.Context) = androidx.preference.PreferenceManager.getDefaultSharedPreferences(c)

	fun orden(c: android.content.Context): List<Seccion> {
		val guardado = prefs(c).getString(CLAVE_ORDEN, "").orEmpty().split(',').mapNotNull { n -> ORDENABLES.firstOrNull { it.name == n } }.distinct()
		// Una sección nueva (que no estaba en el orden guardado) va detrás de la que la precede en la lista de secciones
		val orden = guardado.toMutableList()
		for (s in ORDENABLES) if (s !in orden) {
			val anterior = ORDENABLES.getOrNull(ORDENABLES.indexOf(s) - 1)
			val donde = orden.indexOf(anterior)
			if (donde >= 0) orden.add(donde + 1, s) else orden.add(s)
		}
		return orden
	}

	fun guardar(c: android.content.Context, orden: List<Seccion>) = prefs(c).edit().putString(CLAVE_ORDEN, orden.joinToString(",") { it.name }).apply()

	fun enPortada(c: android.content.Context, s: Seccion) = s.name !in prefs(c).getStringSet(CLAVE_OCULTAS, emptySet()).orEmpty()

	fun ponerEnPortada(c: android.content.Context, s: Seccion, si: Boolean) {
		val ocultas = prefs(c).getStringSet(CLAVE_OCULTAS, emptySet()).orEmpty().toMutableSet()
		if (si) ocultas -= s.name else ocultas += s.name
		prefs(c).edit().putStringSet(CLAVE_OCULTAS, ocultas).apply()
	}

	/** Elementos de cada sección en la Portada. */
	fun elementos(c: android.content.Context) = prefs(c).getString("portada_elementos", "3")?.toIntOrNull() ?: 3
}

/**
 * El mini reproductor de la sección sigue lo que suena mientras la pantalla
 * está a la vista (se registra en onStart y se suelta en onStop).
 */
fun AppCompatActivity.seguirMiniReproductor(mini: com.xebolax.lector.audio.MiniReproductor) {
	lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
		override fun onStart(owner: androidx.lifecycle.LifecycleOwner) = mini.empezar()
		override fun onStop(owner: androidx.lifecycle.LifecycleOwner) = mini.parar()
	})
}

/**
 * Pone el contenido dentro del menú lateral. inicio: qué hacer al elegir la
 * sección en la que ya se está (volver a su primera pestaña).
 */
fun AppCompatActivity.ponerMenuLateral(contenido: View, barra: MaterialToolbar, seccion: Seccion, actualizaciones: GestorActualizaciones, inicio: () -> Unit): DrawerLayout {
	val cajon = DrawerLayout(this).apply { id = R.id.menu_lateral_cajon }
	cajon.addView(contenido, DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
	var alElegir: (Int) -> Unit = {}
	var orden = OrdenSecciones.orden(this)
	var (navegacion, actual) = contenidoMenuLateral(seccion) { alElegir(it) }
	fun parametros() = DrawerLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.START }
	cajon.addView(navegacion, parametros())
	// Si el orden de las secciones cambia en Ajustes, el menú se rehace al volver
	lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
		override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
			val nuevo = OrdenSecciones.orden(this@ponerMenuLateral)
			if (nuevo == orden) return
			orden = nuevo
			cajon.removeView(navegacion)
			contenidoMenuLateral(seccion) { alElegir(it) }.let { (n, a) ->
				navegacion = n
				actual = a
			}
			cajon.addView(navegacion, parametros())
		}
	})
	barra.setNavigationIcon(R.drawable.ic_menu)
	barra.navigationContentDescription = getString(R.string.abrir_menu)
	barra.setNavigationOnClickListener { cajon.openDrawer(GravityCompat.START) }
	// Atrás con el menú abierto lo cierra: se registra al abrirlo para ir antes que los de las listas
	var cerrar: OnBackPressedCallback? = null
	cajon.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
		override fun onDrawerOpened(vista: View) {
			// Con TalkBack, el foco entra directamente en el menú, en la sección en la que estás
			if (lectorActivo()) actual?.let { fila -> fila.post { fila.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) } }
			cerrar?.remove()
			cerrar = object : OnBackPressedCallback(true) {
				override fun handleOnBackPressed() = cajon.closeDrawer(GravityCompat.START)
			}.also { onBackPressedDispatcher.addCallback(this@ponerMenuLateral, it) }
		}

		override fun onDrawerClosed(vista: View) {
			cerrar?.remove()
			cerrar = null
		}
	})
	cajon.setDrawerTitle(GravityCompat.START, getString(R.string.menu_lateral))
	alElegir = { id ->
		cajon.closeDrawer(GravityCompat.START)
		val destino = Seccion.entries.firstOrNull { it.menu == id }
		when {
			destino == seccion -> inicio()
			destino != null -> irASeccion(destino, desde = seccion)
			id == R.id.lateral_estadisticas -> startActivity(Intent(this, EstadisticasActivity::class.java))
			id == R.id.lateral_ajustes -> startActivity(Intent(this, AjustesActivity::class.java))
			id == R.id.lateral_manual -> AccionesLibro.abrirManual(this)
			id == R.id.lateral_acerca -> acercaDe(actualizaciones)
		}
	}
	return cajon
}

/**
 * El contenido del menú lateral, como el cajón de navegación de Material 3:
 * arriba las secciones (la actual con el indicador y «seleccionado»; las
 * demás sin estado, porque no son opciones marcables) y, tras un separador,
 * lo general (acciones: nunca quedan seleccionadas). Cada fila es un solo
 * elemento de TalkBack de 56 dp que se activa con un doble toque.
 * Devuelve el contenido y la fila de la sección actual.
 */
private fun AppCompatActivity.contenidoMenuLateral(seccion: Seccion, alElegir: (Int) -> Unit): Pair<View, View?> {
	val dp = resources.displayMetrics.density
	val menu = android.widget.PopupMenu(this, View(this)).menu.also { menuInflater.inflate(R.menu.menu_lateral, it) }
	// La Portada, las secciones en el orden de Ajustes y, después, lo general
	val elementos = listOf(menu.findItem(R.id.lateral_portada)) + OrdenSecciones.orden(this).map { menu.findItem(it.menu) } +
		(0 until menu.size()).map { menu.getItem(it) }.filter { it.groupId != R.id.grupo_secciones }
	val lista = android.widget.LinearLayout(this).apply {
		orientation = android.widget.LinearLayout.VERTICAL
		minimumWidth = (300 * dp).toInt()
		setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
	}
	// Título del menú (encabezado), como el «headline» del cajón de Material
	lista.addView(TextView(this).apply {
		text = getString(R.string.app_name)
		setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
		setTextColor(MaterialColors.getColor(lista, com.google.android.material.R.attr.colorOnSurfaceVariant))
		setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
		ViewCompat.setAccessibilityHeading(this, true)
	})
	var actual: View? = null
	var grupoAnterior = -1
	for (item in elementos) {
		if (grupoAnterior != -1 && item.groupId != grupoAnterior) {
			lista.addView(View(this).apply {
				setBackgroundColor(MaterialColors.getColor(lista, com.google.android.material.R.attr.colorOutlineVariant))
				importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
			}, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp.toInt().coerceAtLeast(1)).apply {
				setMargins((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
			})
		}
		grupoAnterior = item.groupId
		val esActual = item.itemId == seccion.menu
		val fila = TextView(this).apply {
			text = item.title
			minHeight = (56 * dp).toInt()
			gravity = Gravity.CENTER_VERTICAL
			setPadding((16 * dp).toInt(), 0, (24 * dp).toInt(), 0)
			compoundDrawablePadding = (12 * dp).toInt()
			setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
			val color = MaterialColors.getColor(lista, if (esActual) com.google.android.material.R.attr.colorOnSecondaryContainer else com.google.android.material.R.attr.colorOnSurfaceVariant)
			setTextColor(color)
			item.icon?.mutate()?.let { icono ->
				icono.setTint(color)
				icono.setBounds(0, 0, (24 * dp).toInt(), (24 * dp).toInt())
				setCompoundDrawablesRelative(icono, null, null, null)
			}
			if (esActual) setTypeface(typeface, android.graphics.Typeface.BOLD)
			// Indicador de la sección activa (píldora) y respuesta al toque
			val forma = com.google.android.material.shape.MaterialShapeDrawable(
				com.google.android.material.shape.ShapeAppearanceModel.builder().setAllCornerSizes(28 * dp).build()
			).apply {
				fillColor = android.content.res.ColorStateList.valueOf(
					if (esActual) MaterialColors.getColor(lista, com.google.android.material.R.attr.colorSecondaryContainer) else android.graphics.Color.TRANSPARENT
				)
			}
			val mascara = com.google.android.material.shape.MaterialShapeDrawable(forma.shapeAppearanceModel).apply {
				fillColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
			}
			background = android.graphics.drawable.RippleDrawable(
				android.content.res.ColorStateList.valueOf(MaterialColors.getColor(lista, android.R.attr.colorControlHighlight)), forma, mascara,
			)
			isClickable = true
			isFocusable = true
			// Solo la sección actual dice «seleccionado»
			isSelected = esActual
			setOnClickListener { alElegir(item.itemId) }
		}
		if (esActual) actual = fila
		lista.addView(fila, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
	}
	val contenido = android.widget.ScrollView(this).apply {
		id = R.id.menu_lateral_navegacion
		fitsSystemWindows = true
		isFillViewport = true
		layoutParams = ViewGroup.LayoutParams((300 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
		setBackgroundColor(MaterialColors.getColor(lista, com.google.android.material.R.attr.colorSurfaceContainerLow))
		addView(lista)
	}
	return contenido to actual
}

/** Abre una sección (desde la Portada, el menú lateral o un acceso directo). */
fun android.content.Context.abrirSeccion(destino: Seccion) {
	when (destino) {
		Seccion.PORTADA -> startActivity(Intent(this, PortadaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
		Seccion.BIBLIOTECA -> startActivity(Intent(this, BibliotecaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
		Seccion.COMPARTIDOS -> com.xebolax.lector.compartidos.CompartidosActivity.abrir(this)
		Seccion.PODCASTS -> com.xebolax.lector.podcast.PodcastActivity.abrir(this)
		Seccion.NOTICIAS -> com.xebolax.lector.rss.RssActivity.abrir(this)
		Seccion.RADIO -> com.xebolax.lector.radio.RadioActivity.abrir(this)
	}
}

/**
 * Cambia de sección. La Portada queda siempre debajo: al salir de una sección
 * con Atrás se vuelve a ella, y no se van apilando secciones.
 */
fun AppCompatActivity.irASeccion(destino: Seccion, desde: Seccion) {
	if (destino == Seccion.PORTADA) return volverAPortada()
	abrirSeccion(destino)
	if (desde != Seccion.PORTADA) {
		finish()
		// Sin animación de cierre: se nota como un cambio de pantalla, no como volver atrás
		@Suppress("DEPRECATION")
		overridePendingTransition(0, 0)
	}
}

/**
 * Atrás en la pantalla principal de una sección: a la Portada (la que hay
 * debajo o, si la sección se abrió desde fuera, una nueva). Desde la Portada,
 * Atrás ya sale de la app.
 */
fun AppCompatActivity.volverAPortada() {
	val intent = Intent(this, PortadaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
	if (isTaskRoot) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
	startActivity(intent)
	finish()
}

/** Acerca de, con las novedades y la búsqueda de actualizaciones. */
fun AppCompatActivity.acercaDe(actualizaciones: GestorActualizaciones) {
	MaterialAlertDialogBuilder(this)
		.setTitle(R.string.acerca_de)
		.setMessage(getString(R.string.acerca_de_texto, BuildConfig.VERSION_NAME))
		.setPositiveButton(R.string.buscar_actualizaciones) { _, _ -> actualizaciones.comprobarAMano() }
		.setNeutralButton(R.string.novedades) { _, _ -> AccionesLibro.abrirManual(this, "novedades") }
		.setNegativeButton(R.string.cerrar, null)
		.show()
}

/**
 * Pestañas abajo para las páginas de una sección (Podcasts y Noticias), como
 * en la Biblioteca: todas a la vista, sin deslizar. El título de arriba es el
 * de la sección en la primera pestaña y el de la pestaña en las demás; Atrás
 * en otra pestaña vuelve a la primera y en la primera, a la Portada.
 */
fun AppCompatActivity.ponerPestanas(barra: MaterialToolbar, paginas: ViewPager2, navegacion: BottomNavigationView,
	pestanas: List<Pair<Int, Int>>, tituloSeccion: String, inicio: () -> Int = { 0 }) {
	navegacion.menu.clear()
	pestanas.forEachIndexed { i, (nombre, icono) -> navegacion.menu.add(Menu.NONE, i, i, nombre).setIcon(icono) }
	// Deslizar de lado se confunde con los gestos de TalkBack: se cambia de pestaña con las de abajo
	paginas.isUserInputEnabled = false
	// El contenedor de páginas no es un elemento para TalkBack (diría «Página 1 de 5»): ya están las pestañas
	paginas.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
	(paginas.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
	navegacion.setOnItemSelectedListener { item ->
		paginas.setCurrentItem(item.itemId, false)
		true
	}
	navegacion.setOnItemReselectedListener { }
	paginas.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
		override fun onPageSelected(posicion: Int) {
			if (navegacion.selectedItemId != posicion) navegacion.selectedItemId = posicion
			val nombre = getString(pestanas[posicion].first)
			barra.title = if (posicion == 0) tituloSeccion else nombre
			title = barra.title
			ViewCompat.setAccessibilityPaneTitle(paginas, getString(R.string.pestana_de, nombre))
			invalidateOptionsMenu()
		}
	})
	onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
		override fun handleOnBackPressed() {
			// La pestaña con la que se abrió la sección es su pantalla principal
			if (paginas.currentItem != inicio()) paginas.setCurrentItem(inicio(), false) else volverAPortada()
		}
	})
}
