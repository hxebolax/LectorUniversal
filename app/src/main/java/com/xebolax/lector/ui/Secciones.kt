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
 * Podcasts y Noticias. Todas igual: «Abrir menú» arriba a la izquierda (el
 * menú lateral, para ir de una a otra y a lo general) y sus pestañas abajo,
 * todas a la vista.
 */
enum class Seccion(val menu: Int) {
	BIBLIOTECA(R.id.lateral_biblioteca),
	PODCASTS(R.id.lateral_podcasts),
	NOTICIAS(R.id.lateral_noticias),
}

/**
 * Pone el contenido dentro del menú lateral. inicio: qué hacer al elegir la
 * sección en la que ya se está (volver a su primera pestaña).
 */
fun AppCompatActivity.ponerMenuLateral(contenido: View, barra: MaterialToolbar, seccion: Seccion, actualizaciones: GestorActualizaciones, inicio: () -> Unit): DrawerLayout {
	val cajon = DrawerLayout(this).apply { id = R.id.menu_lateral_cajon }
	cajon.addView(contenido, DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
	var alElegir: (Int) -> Unit = {}
	val (navegacion, actual) = contenidoMenuLateral(seccion) { alElegir(it) }
	cajon.addView(navegacion, DrawerLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
		gravity = Gravity.START
	})
	barra.setNavigationIcon(R.drawable.ic_menu)
	barra.navigationContentDescription = getString(R.string.abrir_menu)
	barra.setNavigationOnClickListener { cajon.openDrawer(GravityCompat.START) }
	// Atrás con el menú abierto lo cierra: se registra al abrirlo para ir antes que los de las listas
	var cerrar: OnBackPressedCallback? = null
	cajon.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
		override fun onDrawerOpened(vista: View) {
			// Con TalkBack, el foco entra directamente en el menú, en la sección en la que estás
			if (lectorActivo()) actual?.post { actual.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
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
	for (i in 0 until menu.size()) {
		val item = menu.getItem(i)
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

/**
 * Cambia de sección. La Biblioteca queda siempre debajo: al salir de Podcasts
 * o de Noticias con Atrás se vuelve a ella, y no se van apilando secciones.
 */
fun AppCompatActivity.irASeccion(destino: Seccion, desde: Seccion) {
	when (destino) {
		Seccion.BIBLIOTECA -> startActivity(Intent(this, PrincipalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
		Seccion.PODCASTS -> com.xebolax.lector.podcast.PodcastActivity.abrir(this)
		Seccion.NOTICIAS -> com.xebolax.lector.rss.RssActivity.abrir(this)
	}
	if (desde != Seccion.BIBLIOTECA) {
		finish()
		// Sin animación de cierre: se nota como un cambio de pantalla, no como volver atrás
		@Suppress("DEPRECATION")
		overridePendingTransition(0, 0)
	}
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
 * en otra pestaña vuelve a la primera y en la primera sale de la sección.
 */
fun AppCompatActivity.ponerPestanas(barra: MaterialToolbar, paginas: ViewPager2, navegacion: BottomNavigationView,
	pestanas: List<Pair<Int, Int>>, tituloSeccion: String) {
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
			if (paginas.currentItem != 0) paginas.setCurrentItem(0, false)
			else {
				isEnabled = false
				onBackPressedDispatcher.onBackPressed()
				isEnabled = true
			}
		}
	})
}
