package com.xebolax.lector.rss

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.xebolax.lector.R
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Reglas y alertas de Noticias (como las de SocialRSS): si un artículo nuevo
 * contiene alguna de las palabras (en el título, o también en el texto), de
 * todas las fuentes o de una, se hace algo con él: marcarlo como leído,
 * favorito o para más tarde, mandarlo a la papelera o avisar con una
 * notificación (alerta por palabra clave).
 */
object Reglas {

	enum class Accion(val nombre: Int) {
		AVISAR(R.string.rss_regla_avisar),
		FAVORITO(R.string.rss_regla_favorito),
		MAS_TARDE(R.string.rss_regla_mas_tarde),
		LEIDO(R.string.rss_regla_leido),
		PAPELERA(R.string.rss_regla_papelera),
	}

	class Regla(val palabras: String, val enTexto: Boolean, val fuente: Long, val accion: Accion, val activa: Boolean = true) {
		fun lista() = palabras.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
		fun json(): JSONObject = JSONObject().put("palabras", palabras).put("enTexto", enTexto).put("fuente", fuente).put("accion", accion.name).put("activa", activa)
	}

	fun lista(c: Context): List<Regla> = runCatching {
		val a = JSONArray(Rss.prefs(c).getString("rss_reglas", "[]"))
		(0 until a.length()).map { i ->
			val o = a.getJSONObject(i)
			Regla(o.optString("palabras"), o.optBoolean("enTexto"), o.optLong("fuente"),
				runCatching { Accion.valueOf(o.optString("accion")) }.getOrDefault(Accion.AVISAR), o.optBoolean("activa", true))
		}
	}.getOrDefault(emptyList())

	fun guardar(c: Context, lista: List<Regla>) {
		Rss.prefs(c).edit().putString("rss_reglas", JSONArray(lista.map { it.json() }).toString()).apply()
	}

	/** La palabra que hace saltar la regla en ese artículo, o null. */
	fun coincide(r: Regla, a: Articulo): String? {
		if (!r.activa || (r.fuente > 0 && r.fuente != a.fuente)) return null
		val donde = (a.titulo + " " + if (r.enTexto) a.resumen + " " + Jsoup.parse(a.contenido).text() else "").lowercase()
		return r.lista().firstOrNull { donde.contains(it) }
	}

	/** Aplica las reglas a los artículos nuevos. Devuelve cuántos ha tocado. */
	fun aplicar(c: Context, articulos: List<Articulo>): Int {
		val reglas = lista(c).filter { it.activa }
		if (reglas.isEmpty() || articulos.isEmpty()) return 0
		val db = RssBaseDatos.obtener(c)
		val alertas = ArrayList<Pair<String, Articulo>>()
		var tocados = 0
		for (a in articulos) {
			var tocado = false
			for (r in reglas) {
				val palabra = coincide(r, a) ?: continue
				tocado = true
				when (r.accion) {
					Accion.AVISAR -> alertas += palabra to a
					Accion.FAVORITO -> db.marcarFavorito(a.id, true)
					Accion.MAS_TARDE -> db.marcarMasTarde(a.id, true)
					Accion.LEIDO -> db.marcarLeidos(listOf(a.id), true)
					Accion.PAPELERA -> db.marcarPapelera(listOf(a.id), true)
				}
			}
			if (tocado) tocados++
		}
		if (alertas.isNotEmpty()) avisar(c, alertas)
		return tocados
	}

	private fun avisar(c: Context, alertas: List<Pair<String, Articulo>>) {
		val gestor = c.getSystemService(NotificationManager::class.java) ?: return
		if (Build.VERSION.SDK_INT >= 26) gestor.createNotificationChannel(NotificationChannel("alertas", c.getString(R.string.rss_alertas), NotificationManager.IMPORTANCE_HIGH))
		val abrir = PendingIntent.getActivity(c, 9, Intent(c, RssActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
		val estilo = NotificationCompat.InboxStyle()
		alertas.take(6).forEach { (palabra, a) -> estilo.addLine("$palabra: ${a.titulo}") }
		val titulo = c.resources.getQuantityString(R.plurals.rss_n_alertas, alertas.size, alertas.size)
		val n = NotificationCompat.Builder(c, "alertas")
			.setSmallIcon(R.drawable.ic_voz)
			.setContentTitle(titulo)
			.setContentText(alertas.joinToString(". ") { (palabra, a) -> "$palabra: ${a.titulo}" })
			.setStyle(estilo)
			.setContentIntent(abrir)
			.setAutoCancel(true)
			.build()
		runCatching { gestor.notify(9, n) }
	}
}
