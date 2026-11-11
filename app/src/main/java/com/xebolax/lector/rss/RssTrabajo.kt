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
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xebolax.lector.R
import java.util.concurrent.TimeUnit

/**
 * Actualiza las noticias y los podcasts cada cierto tiempo aunque la app esté
 * cerrada, cada uno con su frecuencia (Ajustes, Noticias y Ajustes, Podcasts),
 * y avisa de los artículos nuevos de las fuentes que lo tengan activado.
 *
 * Android no permite tareas periódicas de menos de 15 minutos: para 5 o 10
 * minutos (o una personalizada corta) se usa una tarea de una vez que, al
 * terminar, se vuelve a programar. Con el ahorro de batería, Android puede
 * retrasarlas un poco.
 */
class RssTrabajo(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {

	companion object {
		private const val NOMBRE = "actualizar_noticias"
		private const val NOMBRE_PODCASTS = "actualizar_podcasts"
		private const val QUE = "que"
		const val NOTICIAS = "noticias"
		const val PODCASTS = "podcasts"
		private const val CANAL = "noticias"
		private const val GRUPO = "noticias_nuevas"

		/** Número del aviso de un artículo (para quitarlo al leerlo). */
		fun idAviso(articulo: Long) = 710_000 + (articulo % 1_000_000).toInt()

		fun quitarAviso(c: Context, articulo: Long) {
			c.getSystemService(NotificationManager::class.java)?.cancel(idAviso(articulo))
		}

		/** Mínimo de una frecuencia personalizada (menos gastaría batería sin ganar nada). */
		const val MINIMO = 5L

		/**
		 * Cada cuántos minutos se actualizan las noticias o los podcasts (0 = solo a mano).
		 * Los podcasts, si nunca se ha elegido, siguen a las noticias (como antes, que era una sola).
		 */
		fun minutos(c: Context, que: String): Long {
			val p = Rss.prefs(c)
			val pre = if (que == PODCASTS) "pod" else "rss"
			val valor = if (que == PODCASTS) p.getString("pod_frecuencia", null) ?: p.getString("rss_frecuencia", "180") else p.getString("rss_frecuencia", "180")
			val m = valor?.toLongOrNull() ?: 180L
			// Personalizada: los minutos escritos
			if (m < 0) return p.getString("${pre}_frecuencia_minutos", "")?.trim()?.toLongOrNull()?.coerceIn(MINIMO, 7L * 24 * 60) ?: 60L
			return m
		}

		/** Programa (o quita) la actualización de las noticias y la de los podcasts según Ajustes. */
		fun programar(c: Context) {
			programar(c, NOTICIAS)
			programar(c, PODCASTS)
		}

		/** desdeTrabajo: la llama la propia tarea al acabar (la siguiente va detrás de ella, sin cancelarla). */
		private fun programar(c: Context, que: String, desdeTrabajo: Boolean = false) {
			val p = Rss.prefs(c)
			val nombre = if (que == PODCASTS) NOMBRE_PODCASTS else NOMBRE
			val minutos = minutos(c, que)
			val wm = WorkManager.getInstance(c)
			if (minutos <= 0) {
				wm.cancelUniqueWork(nombre)
				return
			}
			// Las noticias, solo con wifi si se eligió; los podcasts comprueban sus fuentes con cualquier red
			// (las descargas de episodios tienen su propio «solo con wifi»)
			val soloWifi = que == NOTICIAS && p.getBoolean("rss_solo_wifi", false)
			val restricciones = Constraints.Builder()
				.setRequiredNetworkType(if (soloWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
				.setRequiresBatteryNotLow(true)
				.build()
			val datos = workDataOf(QUE to que)
			if (minutos < 15) {
				// Menos de 15 minutos: una vez, con espera; al terminar se programa la siguiente
				val una = OneTimeWorkRequestBuilder<RssTrabajo>()
					.setInitialDelay(minutos, TimeUnit.MINUTES)
					.setConstraints(restricciones)
					.setInputData(datos)
					.build()
				wm.enqueueUniqueWork(nombre, if (desdeTrabajo) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, una)
			} else {
				val peticion = PeriodicWorkRequestBuilder<RssTrabajo>(minutos, TimeUnit.MINUTES)
					.setConstraints(restricciones)
					.setInputData(datos)
					.build()
				wm.enqueueUniquePeriodicWork(nombre, ExistingPeriodicWorkPolicy.UPDATE, peticion)
			}
		}
	}

	override suspend fun doWork(): Result {
		val c = applicationContext
		val que = inputData.getString(QUE) ?: NOTICIAS
		try {
			if (que == PODCASTS) {
				runCatching { com.xebolax.lector.podcast.Podcasts.actualizarTodos(c) }
				return Result.success()
			}
			Rss.preparar(c)
			val r = runCatching { Rss.actualizarTodas(c) }.getOrElse { return Result.retry() }
			runCatching { Rss.completarPendientes(c) }
			if (r.avisos.isNotEmpty() && Rss.prefs(c).getBoolean("rss_avisos", true)) avisar(c, r.avisos)
			return Result.success()
		} finally {
			// Frecuencia corta (tarea de una vez): se programa la siguiente
			if (minutos(c, que) in 1 until 15) programar(c, que, desdeTrabajo = true)
		}
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
