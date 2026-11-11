package com.xebolax.lector.rss

import java.net.URI

/**
 * Groups.io, donde están muchas listas de correo de la comunidad ciega: la
 * dirección de un grupo (o de sus temas) se convierte en su RSS
 * (/g/<grupo>/rss), y cada mensaje llega como un artículo con su texto.
 * Idea de BlindRSS para Android (licencia MIT). Las páginas de los temas
 * están protegidas contra programas (piden JavaScript y una comprobación del
 * navegador): no se leen rascando la web, solo con el RSS que ofrece el grupo.
 */
object GruposIo {

	private val GRUPO = Regex("""^/g/([\w.\-]+)(?:/(?:topics|messages|rss|search|topic/.*|message/.*))?/?$""")

	private fun host(url: String) = runCatching { URI(url.trim()).host?.lowercase().orEmpty() }.getOrDefault("")
	private fun ruta(url: String) = runCatching { URI(url.trim()).path.orEmpty() }.getOrDefault("")

	fun es(url: String): Boolean = host(url).let { it == "groups.io" || it.endsWith(".groups.io") }

	/** La fuente RSS del grupo de esa dirección (null si no es de un grupo). */
	fun fuenteDeGrupo(url: String): String? {
		if (!es(url)) return null
		val m = GRUPO.find(ruta(url)) ?: return null
		return "https://${host(url)}/g/${m.groupValues[1]}/rss"
	}
}
