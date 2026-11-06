package com.xebolax.lector.rss

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.xebolax.lector.R

/** Botones del aviso de un artículo nuevo: marcarlo como leído o añadirlo a favoritos sin abrir la app. */
class RssReceptorAvisos : BroadcastReceiver() {

	companion object {
		const val LEIDO = "com.xebolax.lector.rss.LEIDO"
		const val FAVORITO = "com.xebolax.lector.rss.FAVORITO"
		private const val EXTRA_ID = "id"

		fun boton(c: Context, accion: String, articulo: Long, n: Int): PendingIntent = PendingIntent.getBroadcast(
			c, (articulo * 3 + n).toInt(),
			Intent(c, RssReceptorAvisos::class.java).setAction(accion).putExtra(EXTRA_ID, articulo),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
	}

	override fun onReceive(c: Context, i: Intent) {
		val id = i.getLongExtra(EXTRA_ID, 0L).takeIf { it > 0 } ?: return
		val db = RssBaseDatos.obtener(c)
		val texto = when (i.action) {
			LEIDO -> { db.marcarLeidos(listOf(id)); R.string.rss_marcado_leido }
			FAVORITO -> { db.marcarFavorito(id, true); R.string.rss_anadido_favoritos }
			else -> return
		}
		// Hecho: se quita su aviso y se confirma (TalkBack lee el mensaje)
		RssTrabajo.quitarAviso(c, id)
		Toast.makeText(c, texto, Toast.LENGTH_SHORT).show()
	}
}
