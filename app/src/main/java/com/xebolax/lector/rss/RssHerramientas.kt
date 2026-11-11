package com.xebolax.lector.rss

import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xebolax.lector.R
import com.xebolax.lector.ia.conversarConIA
import com.xebolax.lector.ui.Accion
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.campoTexto
import com.xebolax.lector.ui.confirmar
import com.xebolax.lector.ui.dp
import com.xebolax.lector.ui.elegir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Herramientas de Noticias (menú Más), como en SocialRSS: reglas y alertas,
 * papelera, estado de las fuentes y resumen de los no leídos con la IA.
 */
object RssHerramientas {

	// --- Reglas y alertas --------------------------------------------------------------------

	fun reglas(a: AppCompatActivity) {
		a.lifecycleScope.launch {
			val fuentes = withContext(Dispatchers.IO) { RssBaseDatos.obtener(a).fuentes() }
			reglas(a, fuentes)
		}
	}

	private fun reglas(a: AppCompatActivity, todas: List<Fuente>) {
		val db = RssBaseDatos.obtener(a)
		val lista = Reglas.lista(a)
		val fuentes = todas.associateBy { it.id }
		val opciones = mutableListOf<Accion>(a.getString(R.string.rss_anadir_regla) to { editarRegla(a, null, todas) })
		lista.forEachIndexed { i, r ->
			val donde = fuentes[r.fuente]?.titulo ?: a.getString(R.string.rss_todas_fuentes)
			val texto = a.getString(R.string.rss_regla_resumen, a.getString(r.accion.nombre), r.palabras, donde) +
				if (r.activa) "" else ", " + a.getString(R.string.rss_regla_desactivada)
			opciones += texto to {
				a.elegir(texto, listOf<Accion>(
					a.getString(R.string.editar) to { editarRegla(a, i, todas) },
					a.getString(if (r.activa) R.string.rss_desactivar else R.string.rss_activar) to {
						Reglas.guardar(a, Reglas.lista(a).toMutableList().also { it[i] = Reglas.Regla(r.palabras, r.enTexto, r.fuente, r.accion, !r.activa) })
						a.avisar(a.getString(R.string.hecho))
					},
					a.getString(R.string.rss_probar_regla) to {
						a.lifecycleScope.launch {
							val n = withContext(Dispatchers.IO) { db.recientes().count { Reglas.coincide(r, it) != null } }
							a.avisar(a.resources.getQuantityString(R.plurals.rss_regla_coinciden, n, n), mostrar = true)
						}
						Unit
					},
					a.getString(R.string.borrar) to {
						a.confirmar(a.getString(R.string.rss_confirmar_borrar_regla), a.getString(R.string.borrar)) {
							Reglas.guardar(a, Reglas.lista(a).toMutableList().also { it.removeAt(i) })
						}
					},
				))
			}
		}
		a.elegir(a.getString(R.string.rss_reglas), opciones)
	}

	/** La etiqueta visible de un desplegable: TalkBack la dice con él (labelFor) y no aparte. */
	private fun etiqueta(a: AppCompatActivity, texto: Int, de: View) = TextView(a).apply {
		setText(texto)
		setPadding(0, dp(a, 12), 0, 0)
		labelFor = de.id
		importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
	}

	private fun editarRegla(a: AppCompatActivity, indice: Int?, fuentes: List<Fuente>) {
		val original = indice?.let { Reglas.lista(a).getOrNull(it) }
		val (cPalabras, palabras) = a.campoTexto(a.getString(R.string.rss_regla_palabras), original?.palabras.orEmpty())
		fun desplegable(nombres: List<String>, actual: Int, descripcion: Int) = Spinner(a).apply {
			id = View.generateViewId()
			adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, nombres)
			setSelection(actual.coerceIn(0, nombres.size - 1))
			minimumHeight = dp(a, 48)
			// Nombre y valor: «Dónde buscar, en el título, desplegable»
			val nombre = a.getString(descripcion)
			androidx.core.view.ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
				override fun onInitializeAccessibilityNodeInfo(v: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
					super.onInitializeAccessibilityNodeInfo(v, info)
					info.hintText = nombre
					info.text = (v as Spinner).selectedItem?.toString()
				}
			})
		}
		val donde = desplegable(listOf(a.getString(R.string.rss_regla_en_titulo), a.getString(R.string.rss_regla_en_texto)), if (original?.enTexto == true) 1 else 0, R.string.rss_regla_donde)
		val fuente = desplegable(listOf(a.getString(R.string.rss_todas_fuentes)) + fuentes.map { it.titulo },
			fuentes.indexOfFirst { it.id == original?.fuente } + 1, R.string.rss_regla_fuente)
		val accion = desplegable(Reglas.Accion.entries.map { a.getString(it.nombre) }, original?.accion?.ordinal ?: 0, R.string.rss_regla_accion)
		val formulario = LinearLayout(a).apply {
			orientation = LinearLayout.VERTICAL
			val m = dp(a, 20)
			setPadding(m, m / 2, m, 0)
			addView(cPalabras)
			addView(etiqueta(a, R.string.rss_regla_donde, donde)); addView(donde)
			addView(etiqueta(a, R.string.rss_regla_fuente, fuente)); addView(fuente)
			addView(etiqueta(a, R.string.rss_regla_accion, accion)); addView(accion)
		}
		MaterialAlertDialogBuilder(a)
			.setTitle(if (original == null) R.string.rss_anadir_regla else R.string.editar)
			.setView(formulario)
			.setPositiveButton(R.string.guardar) { _, _ ->
				val texto = palabras.text.toString().trim()
				if (texto.isBlank()) return@setPositiveButton
				val regla = Reglas.Regla(texto, donde.selectedItemPosition == 1, if (fuente.selectedItemPosition == 0) 0 else fuentes[fuente.selectedItemPosition - 1].id,
					Reglas.Accion.entries[accion.selectedItemPosition], original?.activa ?: true)
				val lista = Reglas.lista(a).toMutableList()
				if (indice != null && indice < lista.size) lista[indice] = regla else lista += regla
				Reglas.guardar(a, lista)
				a.avisar(a.getString(R.string.rss_regla_guardada))
			}
			.setNegativeButton(R.string.cancelar, null)
			.show()
	}

	// --- Papelera --------------------------------------------------------------------------

	fun papelera(a: AppCompatActivity) {
		val db = RssBaseDatos.obtener(a)
		a.lifecycleScope.launch {
			val lista = withContext(Dispatchers.IO) { db.papelera() }
			papelera(a, db, lista)
		}
	}

	private fun papelera(a: AppCompatActivity, db: RssBaseDatos, lista: List<Articulo>) {
		if (lista.isEmpty()) return a.avisar(a.getString(R.string.rss_papelera_vacia))
		val opciones = mutableListOf<Accion>(a.getString(R.string.rss_vaciar_papelera) to {
			a.confirmar(a.resources.getQuantityString(R.plurals.rss_confirmar_vaciar_papelera, lista.size, lista.size), a.getString(R.string.rss_vaciar_papelera)) {
				RssAcciones.enFondo(a, { db.vaciarPapelera() }) { a.avisar(a.getString(R.string.rss_papelera_vaciada)) }
			}
		}, a.getString(R.string.rss_restaurar_todo) to {
			RssAcciones.enFondo(a, { db.marcarPapelera(lista.map { it.id }, false) }) { a.avisar(a.getString(R.string.rss_restaurados)) }
		})
		for (art in lista) opciones += "${art.titulo}, ${art.nombreFuente}" to {
			a.elegir(art.titulo, listOf(
				a.getString(R.string.rss_restaurar) to {
					RssAcciones.enFondo(a, { db.marcarPapelera(listOf(art.id), false) }) { a.avisar(a.getString(R.string.rss_restaurados)) }
				},
				a.getString(R.string.abrir) to { RssAcciones.abrir(a, art) },
			))
		}
		a.elegir(a.resources.getQuantityString(R.plurals.rss_papelera_n, lista.size, lista.size), opciones)
	}

	// --- Estado de las fuentes ---------------------------------------------------------------

	/** Las fuentes que fallan, las que no publican desde hace tiempo y las pausadas, primero. */
	fun estadoFuentes(a: AppCompatActivity) {
		val db = RssBaseDatos.obtener(a)
		a.lifecycleScope.launch {
			// El último artículo de cada fuente (de todos los que tiene, no solo de los más recientes de todas)
			val (ultimas, fuentes) = withContext(Dispatchers.IO) { db.ultimasFechas() to db.fuentes() }
			estadoFuentes(a, ultimas, fuentes)
		}
	}

	private fun estadoFuentes(a: AppCompatActivity, ultimas: Map<Long, Long>, fuentes: List<Fuente>) {
		val mes = System.currentTimeMillis() - 30 * 86_400_000L
		val filas = fuentes.map { f ->
			val ultimo = ultimas[f.id] ?: 0L
			val (orden, estado) = when {
				f.error.isNotBlank() -> 0 to a.getString(R.string.rss_estado_error, f.error)
				f.pausada -> 2 to a.getString(R.string.rss_estado_pausada)
				ultimo in 1 until mes -> 1 to a.getString(R.string.rss_estado_inactiva, Rss.fechaLegible(ultimo))
				f.total == 0 -> 1 to a.getString(R.string.rss_estado_vacia)
				else -> 3 to a.getString(R.string.rss_estado_bien, Rss.fechaLegible(f.ultima).ifBlank { "—" })
			}
			Triple(orden, f, estado)
		}.sortedWith(compareBy({ it.first }, { it.second.titulo.lowercase() }))
		val mal = filas.count { it.first < 2 }
		a.elegir(a.resources.getQuantityString(R.plurals.rss_estado_titulo, mal, mal), filas.map { (_, f, estado) ->
			"${f.titulo}: $estado" to { (a as? RssActivity)?.abrirFuenteDesdeHerramientas(f.id) ?: Unit }
		})
	}

	// --- Resumen con IA ------------------------------------------------------------------------

	fun resumirNoLeidos(a: AppCompatActivity) {
		a.lifecycleScope.launch {
			// La consulta y el texto de cada artículo, fuera del hilo principal
			val texto = withContext(Dispatchers.IO) {
				RssBaseDatos.obtener(a).sinLeer(limite = 40).joinToString("\n") { art ->
					"- ${art.titulo} (${art.nombreFuente}): " + Jsoup.parse(art.resumen.ifBlank { art.contenido }).text().take(240)
				}
			}
			if (texto.isEmpty()) return@launch a.avisar(a.getString(R.string.rss_nada_que_leer))
			a.conversarConIA(a.getString(R.string.rss_resumir_no_leidos), a.getString(R.string.rss_pedir_resumen_no_leidos, texto))
		}
	}
}
