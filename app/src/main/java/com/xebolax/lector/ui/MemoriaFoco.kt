package com.xebolax.lector.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Devuelve el foco de TalkBack a donde estaba al cerrarse un diálogo, un menú
 * o una pantalla que se abrió encima, en TODA la app: cuando la ventana de una
 * pantalla pierde el foco se apunta qué elemento tenía el foco de
 * accesibilidad (y, si está en una lista, qué fila era), y cuando lo recupera
 * se vuelve a poner ahí. Sirve también para diálogos encadenados (espera →
 * resultado) y si la lista se ha vuelto a dibujar mientras tanto.
 *
 * Además, al cerrarse un diálogo o un menú Android vuelve a anunciar el
 * título de la pantalla (un cambio de ventana): TalkBack lo lee como coletilla
 * y reinicia su control de lectura (deja de moverse por las acciones y
 * deletrea). Como la pantalla no ha cambiado, ese aviso se descarta.
 */
object MemoriaFoco : Application.ActivityLifecycleCallbacks {

	private class Recuerdo(
		val vista: WeakReference<View>,
		val lista: WeakReference<RecyclerView>?,
		val posicion: Int,
		val id: Long,
		val cuando: Long,
	)

	private val recuerdos = WeakHashMap<Activity, Recuerdo>()
	private val pendientes = WeakHashMap<Activity, Runnable>()
	private val principal = Handler(Looper.getMainLooper())
	/** Pantallas que se han ido al fondo (otra pantalla encima) desde que perdieron el foco. */
	private val detenidas = WeakHashMap<Activity, Boolean>()
	/** Cuándo volvió el foco a la pantalla tras cerrarse un diálogo o menú encima. */
	private val vueltas = WeakHashMap<Activity, Long>()

	override fun onActivityCreated(a: Activity, estado: Bundle?) {
		val decor = a.window.decorView
		decor.viewTreeObserver.addOnWindowFocusChangeListener { tiene ->
			if (tiene) {
				// Solo un diálogo o menú encima (la pantalla no se fue al fondo): sin repetir su título
				if (detenidas.remove(a) != true) vueltas[a] = SystemClock.elapsedRealtime()
				restaurar(a)
			} else {
				detenidas.remove(a)
				capturar(a)
			}
		}
		decor.accessibilityDelegate = object : View.AccessibilityDelegate() {
			private fun repetido(tipo: Int): Boolean {
				if (tipo != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
				val vuelta = vueltas.remove(a) ?: return false
				return SystemClock.elapsedRealtime() - vuelta < 1000
			}

			override fun sendAccessibilityEvent(host: View, tipo: Int) {
				if (!repetido(tipo)) super.sendAccessibilityEvent(host, tipo)
			}

			override fun sendAccessibilityEventUnchecked(host: View, evento: AccessibilityEvent) {
				if (!repetido(evento.eventType)) super.sendAccessibilityEventUnchecked(host, evento)
			}
		}
	}

	/** El elemento con el foco de TalkBack en esa pantalla (o null). */
	private fun conFoco(v: View): View? {
		if (!v.isShown) return null
		if (v is ViewGroup) for (i in 0 until v.childCount) conFoco(v.getChildAt(i))?.let { return it }
		return if (runCatching { v.createAccessibilityNodeInfo().isAccessibilityFocused }.getOrDefault(false)) v else null
	}

	private fun capturar(a: Activity) {
		pendientes.remove(a)?.let { principal.removeCallbacks(it) }
		if (!a.lectorActivo()) return
		val v = conFoco(a.window.decorView) ?: return // si no hay, se conserva lo anterior (diálogos encadenados)
		// ¿Está dentro de una lista? Se apunta la fila (por si la lista se redibuja)
		var hijo: View = v
		var padre = v.parent
		while (padre is View && padre !is RecyclerView) {
			hijo = padre
			padre = padre.parent
		}
		val lista = padre as? RecyclerView
		val posicion = lista?.getChildAdapterPosition(hijo) ?: RecyclerView.NO_POSITION
		val id = if (lista?.adapter?.hasStableIds() == true && posicion != RecyclerView.NO_POSITION) lista.adapter!!.getItemId(posicion) else RecyclerView.NO_ID
		recuerdos[a] = Recuerdo(WeakReference(v), lista?.let { WeakReference(it) }, posicion, id, SystemClock.elapsedRealtime())
	}

	private fun restaurar(a: Activity) {
		val r = recuerdos[a] ?: return
		if (!a.lectorActivo() || SystemClock.elapsedRealtime() - r.cuando > 10 * 60_000L) return
		// Un poco después: TalkBack mueve el foco al volver a la pantalla y aquí se corrige
		val tarea = Runnable {
			pendientes.remove(a)
			val destino = destino(r) ?: return@Runnable
			if (!runCatching { destino.createAccessibilityNodeInfo().isAccessibilityFocused }.getOrDefault(false)) {
				destino.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
			}
		}
		pendientes.remove(a)?.let { principal.removeCallbacks(it) }
		pendientes[a] = tarea
		principal.postDelayed(tarea, 450)
	}

	/** La misma vista si sigue ahí y es la misma fila; si la lista se redibujó, la fila que era. */
	private fun destino(r: Recuerdo): View? {
		val lista = r.lista?.get()
		val vista = r.vista.get()
		if (lista == null) return vista?.takeIf { it.isAttachedToWindow && it.isShown }
		if (!lista.isAttachedToWindow) return null
		val adaptador = lista.adapter ?: return null
		// Primero por id (las filas pueden haber cambiado de sitio), luego por posición
		val posicion = if (r.id != RecyclerView.NO_ID) (0 until adaptador.itemCount).firstOrNull { adaptador.getItemId(it) == r.id } ?: r.posicion else r.posicion
		if (posicion == RecyclerView.NO_POSITION || posicion >= adaptador.itemCount) return null
		val fila = lista.findViewHolderForAdapterPosition(posicion)?.itemView ?: run {
			lista.scrollToPosition(posicion)
			return null
		}
		// Si la vista recordada sigue dentro de esa fila (un botón de la fila, por ejemplo), esa; si no, la fila
		return vista?.takeIf { it.isAttachedToWindow && it.isShown && esDescendiente(it, fila) } ?: fila
	}

	private fun esDescendiente(v: View, de: View): Boolean {
		var p: Any? = v
		while (p is View) {
			if (p === de) return true
			p = p.parent
		}
		return false
	}

	override fun onActivityDestroyed(a: Activity) {
		recuerdos.remove(a)
		detenidas.remove(a)
		vueltas.remove(a)
		pendientes.remove(a)?.let { principal.removeCallbacks(it) }
	}

	override fun onActivityStarted(a: Activity) {}
	override fun onActivityResumed(a: Activity) {}
	override fun onActivityPaused(a: Activity) {}
	override fun onActivityStopped(a: Activity) {
		detenidas[a] = true
	}
	override fun onActivitySaveInstanceState(a: Activity, estado: Bundle) {}
}
