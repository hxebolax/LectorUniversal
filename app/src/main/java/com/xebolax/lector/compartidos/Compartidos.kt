package com.xebolax.lector.compartidos

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.preference.PreferenceManager
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.datos.Libro
import com.xebolax.lector.formatos.Formato
import com.xebolax.lector.lectura.LectorActivity
import com.xebolax.lector.ui.PaginaWeb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Compartidos: lo que llega de otras apps o de internet para leerlo (un
 * enlace, un texto, un archivo) y que no tiene por qué quedarse en la
 * biblioteca. Se guarda aparte (columna «compartido» de la biblioteca) y,
 * si se quiere conservar, se pasa a la biblioteca con su tipo y su carpeta.
 * Los leídos se pueden borrar solos pasados unos días (Ajustes).
 */
object Compartidos {

	private fun prefs(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)

	/** ¿Lo que llega de otras apps va directamente a la biblioteca (como antes) en lugar de a Compartidos? */
	fun vaABiblioteca(c: Context) = prefs(c).getString("comp_destino", "compartidos") == "biblioteca"

	/** Qué hacer al recibir algo: abrir, abrir y leer en voz alta, o solo guardarlo. */
	fun alRecibir(c: Context): String = prefs(c).getString("comp_al_recibir", "abrir") ?: "abrir"

	/** Días tras los que se borran solos los leídos (0 = nunca). */
	private fun diasBorrar(c: Context) = prefs(c).getString("comp_borrar_leidos", "0")?.toIntOrNull() ?: 0

	/** Quita la puntuación pegada al final de un enlace; el «)» o «]» solo si no abre ninguno dentro (Pan_(alimento)). */
	private fun limpiarFinal(url: String): String {
		var u = url
		while (u.isNotEmpty()) {
			val c = u.last()
			u = when {
				c in ".,;:!?»\"'" -> u.dropLast(1)
				c == ')' && u.count { it == ')' } > u.count { it == '(' } -> u.dropLast(1)
				c == ']' && u.count { it == ']' } > u.count { it == '[' } -> u.dropLast(1)
				else -> return u
			}
		}
		return u
	}

	/** Una sola dirección web en el texto (con, como mucho, un título corto delante): es un enlace para leer. */
	fun enlaceDe(texto: String): String? {
		val t = texto.trim()
		val enlaces = Regex("""https?://\S+""").findAll(t).map { limpiarFinal(it.value) }.toList()
		if (enlaces.size != 1) return null
		val resto = t.replace(enlaces[0], "").trim()
		return enlaces[0].takeIf { resto.length <= 300 }
	}

	/** Guarda un texto compartido o pegado como documento de Compartidos. */
	suspend fun guardarTexto(c: Context, texto: String, titulo: String? = null): Long = withContext(Dispatchers.IO) {
		val nombre = titulo?.takeIf { it.isNotBlank() } ?: texto.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: "Texto"
		val carpeta = File(c.filesDir, "textos").apply { mkdirs() }
		val archivo = File(carpeta, "texto_${System.currentTimeMillis()}.txt")
		archivo.writeText(texto)
		BaseDatos.obtener(c).anadirCompartido(Uri.fromFile(archivo).toString(), archivo.name, nombre, Formato.TXT.name, false, archivo.length(), "")
	}

	/** Minutos de escucha de un artículo (unas 170 palabras por minuto), o 0 si no se sabe. */
	fun minutos(l: com.xebolax.lector.datos.Libro): Int {
		val palabras = runCatching { org.json.JSONObject(l.ajustes).optInt("palabras") }.getOrDefault(0)
		return if (palabras <= 0) 0 else ((palabras + 169) / 170).coerceAtLeast(1)
	}

	/** El orden de las listas de Compartidos (Ajustes, Compartidos). */
	fun ordenar(c: Context, lista: List<com.xebolax.lector.datos.Libro>): List<com.xebolax.lector.datos.Libro> =
		when (prefs(c).getString("comp_orden", "nuevos")) {
			"antiguos" -> lista.sortedBy { it.anadido }
			"cortos" -> lista.sortedWith(compareBy({ minutos(it).let { m -> if (m == 0) Int.MAX_VALUE else m } }, { -it.anadido }))
			"empezados" -> lista.sortedWith(compareBy({ if (it.estado == com.xebolax.lector.datos.Estado.LEYENDO) 0 else 1 }, { -it.anadido }))
			"titulo" -> lista.sortedBy { it.tituloVisible.lowercase() }
			else -> lista.sortedByDescending { it.anadido }
		}

	/** Descarga una página web (o el documento al que lleva el enlace) y la guarda en Compartidos. */
	suspend fun guardarWeb(c: Context, url: String, forzar: Boolean = false): Long = PaginaWeb.guardar(c, url, compartido = true, forzar = forzar)

	/** Acciones de los accesos directos del icono de la app (mantener pulsado el icono). */
	const val ATAJO_COPIADO = "com.xebolax.lector.ATAJO_LEER_COPIADO"
	const val ATAJO_PENDIENTE = "com.xebolax.lector.ATAJO_LEER_PENDIENTE"
	const val ATAJO_WEB = "com.xebolax.lector.ATAJO_GUARDAR_WEB"

	/** Los accesos directos: Leer lo copiado, Leer lo pendiente y Guardar una página web. */
	fun ponerAtajos(c: Context) = runCatching {
		fun atajo(id: String, accion: String, texto: Int, icono: Int) = androidx.core.content.pm.ShortcutInfoCompat.Builder(c, id)
			.setShortLabel(c.getString(texto))
			.setLongLabel(c.getString(texto))
			.setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(c, icono))
			.setIntent(Intent(c, CompartidosActivity::class.java).setAction(accion))
			.build()
		androidx.core.content.pm.ShortcutManagerCompat.setDynamicShortcuts(c, listOf(
			atajo("copiado", ATAJO_COPIADO, com.xebolax.lector.R.string.atajo_leer_copiado, com.xebolax.lector.R.drawable.ic_compartido),
			atajo("pendiente", ATAJO_PENDIENTE, com.xebolax.lector.R.string.atajo_leer_pendiente, com.xebolax.lector.R.drawable.ic_voz),
			atajo("web", ATAJO_WEB, com.xebolax.lector.R.string.atajo_guardar_web, com.xebolax.lector.R.drawable.ic_anadir),
		))
	}

	/**
	 * Un archivo que llega de otra app. Si ya estaba (en la biblioteca o en
	 * Compartidos), se usa ese. Devuelve su id y si es nuevo.
	 */
	suspend fun guardarArchivo(c: Context, uri: Uri): Pair<Long, Boolean> = withContext(Dispatchers.IO) {
		val db = BaseDatos.obtener(c)
		db.libroPorUri(uri.toString())?.let { return@withContext it.id to false }
		val antes = db.todosConCompartidos().map { it.id }.toSet()
		val id = com.xebolax.lector.biblioteca.Importador.anadirExterno(c, uri)
		val nuevo = id !in antes
		if (nuevo) db.moverACompartidos(id)
		id to nuevo
	}

	/** Abre (y, si se eligió, empieza a leer en voz alta) lo recién recibido. */
	suspend fun abrir(c: Context, id: Long, leer: Boolean = alRecibir(c) == "leer") {
		val l = withContext(Dispatchers.IO) { BaseDatos.obtener(c).libro(id) } ?: return
		if (l.audio || !leer) return com.xebolax.lector.lectura.Abiertos.abrirPantalla(c, l)
		c.startActivity(Intent(c, LectorActivity::class.java).putExtra(LectorActivity.EXTRA_LIBRO, id).putExtra(LectorActivity.EXTRA_LEER_AL_ABRIR, true))
	}

	/** Lo borra de Compartidos (y la copia guardada dentro de la app; los archivos del dispositivo no se tocan). */
	fun borrar(c: Context, l: Libro) {
		val uri = Uri.parse(l.uri)
		if (uri.scheme == "file" && uri.path?.startsWith(c.filesDir.absolutePath) == true) runCatching { File(uri.path!!).delete() }
		BaseDatos.obtener(c).quitarLibro(l.id)
	}

	/** Borra los leídos hace más días de los elegidos en Ajustes. Devuelve cuántos. */
	suspend fun limpiar(c: Context): Int = withContext(Dispatchers.IO) {
		val dias = diasBorrar(c)
		if (dias <= 0) return@withContext 0
		val db = BaseDatos.obtener(c)
		// Nunca lo que se está leyendo en voz alta o escuchando, ni lo último abierto (puede estar en el lector)
		val ultimo = db.compartidos().filter { it.abierto > 0 }.maxByOrNull { it.abierto }?.id
		val viejos = db.compartidosLeidosAntesDe(System.currentTimeMillis() - dias * 86_400_000L).filter { l ->
			l.id != ultimo && !com.xebolax.lector.voz.LectorVoz.esActual(l.id) && !com.xebolax.lector.audio.ControlAudio.esActual(l.id)
		}
		viejos.forEach { borrar(c, it) }
		viejos.size
	}
}
