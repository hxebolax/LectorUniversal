package com.xebolax.lector.ui

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.xebolax.lector.R

/**
 * Pestañas que siempre se ven enteras: cada una con el ancho que necesita su
 * nombre, ninguna fuera de la pantalla y nunca hay que desplazarlas. Si no
 * caben (pantalla estrecha, letra grande o muchas pestañas), las últimas pasan
 * a una pestaña final, «Más», que abre la lista de las que faltan. Cuando la
 * elegida es una de ellas, «Más» queda seleccionada y dice cuál es («Más:
 * Páginas web»). El sitio que sobra se reparte entre todas.
 *
 * Arriba (tipos de la Biblioteca) solo llevan nombre; abajo (Ajustes) llevan
 * icono y nombre, compactas como las pestañas de las secciones.
 *
 * Cada pestaña es UNA parada de TalkBack: su nombre (o «Más: X») está en la
 * propia pestaña y el texto de dentro no se lee aparte.
 *
 * Se recalcula sola al cambiar el ancho (al girar el móvil o con letra grande).
 */
class Pestanas(contexto: Context, private val abajo: Boolean = false) :
	TabLayout(if (abajo) ContextThemeWrapper(contexto, R.style.TemaPestanasAbajo) else contexto) {

	/** Una pestaña: clave, nombre e icono (0 si no tiene). */
	data class Pestana(val clave: String, val nombre: String, val icono: Int = 0)

	private var pestanas: List<Pestana> = emptyList()
	private var elegida: String = ""
	private var alElegir: (String) -> Unit = {}
	/** Cuántas caben (sin contar «Más»); las demás van en «Más». */
	private var caben = 0
	private var ancho = 0
	/** Si al dibujarlas no cupieron (la medida se quedó corta), cuántas como mucho. */
	private var tope = Int.MAX_VALUE
	private var cambiando = false

	init {
		// Cada una con su ancho; como solo se ponen las que caben, nunca se desplazan
		tabMode = MODE_SCROLLABLE
		tabGravity = GRAVITY_START
		isHorizontalScrollBarEnabled = false
		minimumHeight = dp(context, if (abajo) 64 else 48)
		addOnTabSelectedListener(object : OnTabSelectedListener {
			override fun onTabSelected(t: Tab) = tocada(t)
			override fun onTabUnselected(t: Tab) {}
			// «Más» otra vez: vuelve a mostrar la lista
			override fun onTabReselected(t: Tab) {
				if (!cambiando && t.tag == MAS) mostrarMas()
			}
		})
	}

	fun poner(lista: List<Pestana>, actual: String, alElegir: (String) -> Unit) {
		pestanas = lista
		elegida = if (lista.any { it.clave == actual }) actual else lista.firstOrNull()?.clave.orEmpty()
		this.alElegir = alElegir
		ancho = 0
		tope = Int.MAX_VALUE
		requestLayout()
		if (width > 0) rehacer(width)
	}

	/** Lo mismo con parejas (clave, nombre), sin iconos. */
	fun ponerNombres(lista: List<Pair<String, String>>, actual: String, alElegir: (String) -> Unit) =
		poner(lista.map { Pestana(it.first, it.second) }, actual, alElegir)

	/** Cambia la elegida desde fuera (sin avisar). */
	fun elegir(clave: String) {
		elegida = clave
		rehacer(width, forzar = true)
	}

	val actual: String get() = elegida

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		if (w != oldw) {
			tope = Int.MAX_VALUE
			post { rehacer(w) }
		}
	}

	override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
		super.onLayout(changed, l, t, r, b)
		// Seguro: si la medida se quedó corta y no caben, una menos (nunca hay que desplazarlas)
		val tira = getChildAt(0) ?: return
		if (!cambiando && width > 0 && tira.width > width + 1 && caben > 1) {
			tope = caben - 1
			post { rehacer(width, forzar = true) }
		}
	}

	private val medida by lazy {
		TextView(context).apply {
			setTextAppearance(if (abajo) R.style.TextoPestanaAbajo else com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
		}
	}

	private fun dpf(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

	/** Ancho que necesita un nombre (la palabra más larga no se puede partir) más el relleno de la pestaña. */
	private fun necesita(nombre: String): Float {
		val pinta = medida.paint
		val palabra = nombre.split(' ').maxByOrNull { pinta.measureText(it) } ?: nombre
		// Nombre de dos palabras: cabe en dos líneas; de una, en una
		val linea = maxOf(pinta.measureText(palabra), pinta.measureText(nombre) / 2f)
		return if (abajo) linea * 1.04f + dpf(8f) else linea * 1.1f + dpf(28f)
	}

	/** Cuántas caben una detrás de otra (las demás, en «Más»). */
	private fun calcular(w: Int): Int {
		if (pestanas.isEmpty() || w <= 0) return pestanas.size
		val minimo = dpf(if (abajo) 48f else 72f)
		val anchos = pestanas.map { maxOf(necesita(it.nombre), minimo) }
		if (anchos.sum() <= w && pestanas.size <= tope) return pestanas.size
		val mas = maxOf(necesita(context.getString(R.string.pestana_mas)), minimo)
		for (k in minOf(pestanas.size - 1, tope) downTo 1) {
			if (anchos.take(k).sum() + mas <= w) return k
		}
		return 1
	}

	private fun rehacer(w: Int, forzar: Boolean = false) {
		if (pestanas.isEmpty()) return
		val nuevas = calcular(w)
		if (!forzar && nuevas == caben && w == ancho && tabCount > 0) return
		ancho = w
		caben = nuevas
		cambiando = true
		removeAllTabs()
		val visibles = pestanas.take(caben)
		val sobran = pestanas.drop(caben)
		visibles.forEach { p ->
			val t = newTab().setText(p.nombre).setTag(p.clave)
			if (abajo && p.icono != 0) t.setIcon(p.icono)
			t.contentDescription = p.nombre
			addTab(t, p.clave == elegida)
		}
		if (sobran.isNotEmpty()) {
			val dentro = sobran.firstOrNull { it.clave == elegida }
			val mas = newTab().setText(R.string.pestana_mas).setTag(MAS)
			if (abajo) mas.setIcon(R.drawable.ic_mas)
			// TalkBack dice cuál está elegida dentro de «Más»
			mas.contentDescription = dentro?.let { context.getString(R.string.pestana_mas_x, it.nombre) } ?: context.getString(R.string.pestana_mas_otras)
			addTab(mas, dentro != null)
		}
		for (i in 0 until tabCount) getTabAt(i)?.view?.let { v ->
			// Una sola parada: el nombre lo dice la pestaña; el texto y el icono de dentro no se leen aparte
			// (si no, TalkBack paraba otra vez en el texto «Más», que no hace nada)
			for (j in 0 until v.childCount) v.getChildAt(j).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
			// El sitio que sobra se reparte entre todas (nunca más del que hay: no se desplazan)
			(v.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
				lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
				lp.weight = 1f
				v.layoutParams = lp
			}
		}
		cambiando = false
	}

	private fun tocada(t: Tab) {
		if (cambiando) return
		val clave = t.tag as? String ?: return
		if (clave == MAS) return mostrarMas()
		if (clave == elegida) return
		elegida = clave
		rehacer(width, forzar = true)
		alElegir(clave)
	}

	/** Las pestañas que no caben, para elegir una. Si se cancela, vuelve a la que estaba. */
	private fun mostrarMas() {
		val sobran = pestanas.drop(caben)
		if (sobran.isEmpty()) return
		var elegido = false
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.pestana_mas_titulo)
			.setSingleChoiceItems(sobran.map { it.nombre }.toTypedArray(), sobran.indexOfFirst { it.clave == elegida }) { d, i ->
				elegido = true
				d.dismiss()
				val clave = sobran[i].clave
				if (clave != elegida) {
					elegida = clave
					rehacer(width, forzar = true)
					alElegir(clave)
				} else rehacer(width, forzar = true)
			}
			.setNegativeButton(R.string.cancelar, null)
			.setOnDismissListener { if (!elegido) rehacer(width, forzar = true) }
			.show()
	}

	private companion object {
		const val MAS = "\u0000mas"
	}
}
