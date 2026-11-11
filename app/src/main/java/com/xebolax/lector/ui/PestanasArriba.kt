package com.xebolax.lector.ui

import android.content.Context
import android.util.TypedValue
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.xebolax.lector.R

/**
 * Pestañas de arriba que siempre se ven enteras: cada una con el ancho que
 * necesita su nombre y ninguna fuera de la pantalla (nunca hay que desplazarlas). Si no caben (pantalla estrecha, letra grande
 * o muchas pestañas), las últimas pasan a una pestaña final, «Más», que abre
 * la lista de las que faltan. Cuando la elegida es una de ellas, «Más» queda
 * seleccionada y dice cuál es («Más: Páginas web»).
 *
 * Se recalcula sola al cambiar el ancho (al girar el móvil).
 */
class PestanasArriba(contexto: Context) : TabLayout(contexto) {

	/** Las pestañas (clave y nombre) y la elegida. */
	private var pestanas: List<Pair<String, String>> = emptyList()
	private var elegida: String = ""
	private var alElegir: (String) -> Unit = {}
	/** Cuántas caben (sin contar «Más»); las demás van en «Más». */
	private var caben = 0
	private var ancho = 0
	private var cambiando = false

	init {
		// Cada una con su ancho; como solo se ponen las que caben, nunca se desplazan
		tabMode = MODE_SCROLLABLE
		tabGravity = GRAVITY_START
		isHorizontalScrollBarEnabled = false
		minimumHeight = dp(contexto, 48)
		addOnTabSelectedListener(object : OnTabSelectedListener {
			override fun onTabSelected(t: Tab) = tocada(t)
			override fun onTabUnselected(t: Tab) {}
			// «Más» otra vez: vuelve a mostrar la lista
			override fun onTabReselected(t: Tab) {
				if (!cambiando && t.tag == MAS) mostrarMas()
			}
		})
	}

	fun poner(lista: List<Pair<String, String>>, actual: String, alElegir: (String) -> Unit) {
		pestanas = lista
		elegida = if (lista.any { it.first == actual }) actual else lista.firstOrNull()?.first.orEmpty()
		this.alElegir = alElegir
		ancho = 0
		requestLayout()
		if (width > 0) rehacer(width)
	}

	/** Cambia la elegida desde fuera (sin avisar). */
	fun elegir(clave: String) {
		elegida = clave
		rehacer(width, forzar = true)
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		if (w != oldw) post { rehacer(w) }
	}

	/** Ancho que necesita un nombre (la palabra más larga no se puede partir) más el relleno de la pestaña. */
	private fun necesita(nombre: String): Float {
		val medida = TextView(context).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall) }
		val palabra = nombre.split(' ').maxByOrNull { medida.paint.measureText(it) } ?: nombre
		// Nombre de dos palabras: cabe en dos líneas; de una, en una
		val linea = maxOf(medida.paint.measureText(palabra), medida.paint.measureText(nombre) / 2f)
		return linea * 1.1f + TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics)
	}

	/** Cuántas caben una detrás de otra (las demás, en «Más»). Cada pestaña mide al menos 72 dp (lo mínimo de Material). */
	private fun calcular(w: Int): Int {
		if (pestanas.isEmpty() || w <= 0) return pestanas.size
		val minimo = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 72f, resources.displayMetrics)
		val anchos = pestanas.map { maxOf(necesita(it.second), minimo) }
		if (anchos.sum() <= w) return pestanas.size
		val mas = maxOf(necesita(context.getString(R.string.pestana_mas)), minimo)
		for (k in pestanas.size - 1 downTo 1) {
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
		visibles.forEach { (clave, nombre) -> addTab(newTab().setText(nombre).setTag(clave), clave == elegida) }
		if (sobran.isNotEmpty()) {
			val dentro = sobran.firstOrNull { it.first == elegida }
			val mas = newTab().setText(R.string.pestana_mas).setTag(MAS)
			// TalkBack dice cuál está elegida dentro de «Más»
			mas.contentDescription = dentro?.let { context.getString(R.string.pestana_mas_x, it.second) } ?: context.getString(R.string.pestana_mas_otras)
			addTab(mas, dentro != null)
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
			.setSingleChoiceItems(sobran.map { it.second }.toTypedArray(), sobran.indexOfFirst { it.first == elegida }) { d, i ->
				elegido = true
				d.dismiss()
				val clave = sobran[i].first
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
