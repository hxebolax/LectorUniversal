package com.xebolax.lector.catalogo

import java.net.HttpURLConnection
import java.net.URL

/**
 * Abrir una dirección siguiendo las redirecciones a mano (como en Feed): las
 * cookies de una sesión se piden para cada dirección, así solo van a su
 * propio servidor y nunca a otro al que se redirija. Java, solo, las
 * mandaría tal cual al servidor nuevo (y no pasa de http a https).
 */
object Redirecciones {

	/**
	 * preparar: tiempos y cabeceras de cada conexión (sin las cookies).
	 * cookies: las de esa dirección, o null. Devuelve la conexión ya con la
	 * respuesta final (su url es la dirección a la que se llegó).
	 */
	fun abrir(url: String, preparar: (HttpURLConnection) -> Unit, cookies: (String) -> String?): HttpURLConnection {
		var actual = url
		repeat(8) {
			val c = URL(actual).openConnection() as HttpURLConnection
			c.instanceFollowRedirects = false
			preparar(c)
			cookies(actual)?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Cookie", it) }
			val codigo = c.responseCode
			val nueva = if (codigo in 300..399) c.getHeaderField("Location") else null
			// Sin «Location» no es una redirección que se pueda seguir: la respuesta tal cual
			if (nueva == null) return c
			c.disconnect()
			actual = URL(URL(actual), nueva).toString()
		}
		error(com.xebolax.lector.LectorApp.texto(com.xebolax.lector.R.string.cat_error_redirecciones))
	}
}
