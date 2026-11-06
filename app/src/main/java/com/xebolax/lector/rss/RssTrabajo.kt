package com.xebolax.lector.rss

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xebolax.lector.R
import java.util.concurrent.TimeUnit

/**
 * Actualiza las noticias (y los podcasts) cada cierto tiempo aunque la app
 * esté cerrada (Ajustes, Noticias), y avisa de los artículos nuevos de las
 * fuentes que lo tengan activado.
 */
class RssTrabajo(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {

	companion object {
		private const val NOMBRE = "actualizar_noticias"
		private const val CANAL = "noticias"
		private const val GRUPO = "noticias_nuevas"

		/** Número del aviso de un artículo (para quitarlo al leerlo). */
		fun idAviso(articulo: Long) = 710_000 + (articulo % 1_000_000).toInt()

		fun quitarAviso(c: Context, articulo: Long) {
			c.getSystemService(NotificationManager::class.java)?.cancel(idAviso(articulo))
		}

		/** Programa (o quita) la actualización periódica según Ajustes. */
		fun programar(c: Context) {
			val p = Rss.prefs(c)
			val minutos = p.getString("rss_frecuencia", "180")?.toLongOrNull() ?: 180L
			val wm = WorkManager.getInstance(c)
			if (minutos <= 0) {
				wm.cancelUniqueWork(NOMBRE)
				return
			}
			val restricciones = Constraints.Builder()
				.setRequiredNetworkType(if (p.getBoolean("rss_solo_wifi", false)) NetworkType.UNMETERED else NetworkType.CONNECTED)
				.setRequiresBatteryNotLow(true)
				.build()
			val peticion = PeriodicWorkRequestBuilder<RssTrabajo>(minutos.coerceAtLeast(15), TimeUnit.MINUTES)
				.setConstraints(restricciones)
				.build()
			wm.enqueueUniquePeriodicWork(NOMBRE, ExistingPeriodicWorkPolicy.UPDATE, peticion)
		}
	}

	override suspend fun doWork(): Result {
		val c = applicationContext
		Rss.preparar(c)
		val r = runCatching { Rss.actualizarTodas(c) }.getOrElse { return Result.retry() }
		runCatching { com.xebolax.lector.podcast.Podcasts.actualizarTodos(c) }
		runCatching { Rss.completarPendientes(c) }
		if (r.avisos.isNotEmpty() && Rss.prefs(c).getBoolean("rss_avisos", true)) avisar(c, r.avisos)
		return Result.success()
	}

	/**
	 * Avisa de los artículos nuevos. Si son pocos (5 o menos), uno por artículo,
	 * agrupados, con su titular, su fuente y los botones «Marcar como leído» y
	 * «Añadir a favoritos»; al pulsarlo se abre el artículo. Si son más, un solo
	 * aviso con la lista que abre Noticias.
	 */
	private fun avisar(c: Context, avisos: List<Pair<Fuente, RssLector.Elemento>>) {
		val gestor = c.getSystemService(NotificationManager::class.java) ?: return
		if (Build.VERSION.SDK_INT >= 26) gestor.createNotificationChannel(NotificationChannel(CANAL, c.getString(R.string.noticias), NotificationManager.IMPORTANCE_DEFAULT))
		val abrir = PendingIntent.getActivity(c, 7, Intent(c, RssActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
		val titulo = c.resources.getQuantityString(R.plurals.rss_nuevos, avisos.size, avisos.size)
		val estilo = NotificationCompat.InboxStyle()
		avisos.take(6).forEach { (f, e) -> estilo.addLine("${f.titulo}: ${e.titulo}") }
		val db = RssBaseDatos.obtener(c)
		val sueltos = if (avisos.size <= 5) avisos.mapNotNull { (f, e) -> db.idArticulo(f.id, e.guid).takeIf { it > 0 }?.let { Triple(it, f, e) } } else emptyList()
		for ((id, f, e) in sueltos) {
			val abrirArticulo = PendingIntent.getActivity(c, (id * 3).toInt(),
				Intent(c, RssActivity::class.java).putExtra(RssActivity.EXTRA_ARTICULO, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
				PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
			val n = NotificationCompat.Builder(c, CANAL)
				.setSmallIcon(R.drawable.ic_lista)
				.setContentTitle(e.titulo)
				.setContentText(f.titulo)
				.setStyle(NotificationCompat.BigTextStyle().bigText(listOf(f.titulo, e.resumen.take(200)).filter { it.isNotBlank() }.joinToString(": ")))
				.setContentIntent(abrirArticulo)
				.setAutoCancel(true)
				.setGroup(GRUPO)
				.addAction(0, c.getString(R.string.rss_marcar_leido), RssReceptorAvisos.boton(c, RssReceptorAvisos.LEIDO, id, 1))
				.addAction(0, c.getString(R.string.rss_anadir_favorito), RssReceptorAvisos.boton(c, RssReceptorAvisos.FAVORITO, id, 2))
				.build()
			runCatching { gestor.notify(idAviso(id), n) }
		}
		val n = NotificationCompat.Builder(c, CANAL)
			.setSmallIcon(R.drawable.ic_lista)
			.setContentTitle(titulo)
			.setContentText(avisos.first().let { (f, e) -> "${f.titulo}: ${e.titulo}" })
			.setStyle(estilo)
			.setContentIntent(abrir)
			.setAutoCancel(true)
			.setGroup(GRUPO)
			.setGroupSummary(sueltos.isNotEmpty())
			.build()
		runCatching { gestor.notify(71, n) }
	}
}
