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

	/** Una sola dirección web en el texto (con, como mucho, un título corto delante): es un enlace para leer. */
	fun enlaceDe(texto: String): String? {
		val t = texto.trim()
		val enlaces = Regex("""https?://\S+""").findAll(t).map { it.value.trimEnd('.', ',', ')', ']', '»', '"') }.toList()
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

	/** Descarga una página web (o el documento al que lleva el enlace) y la guarda en Compartidos. */
	suspend fun guardarWeb(c: Context, url: String): Long = PaginaWeb.guardar(c, url, compartido = true)

	/** Un archivo que llega de otra app. Si ya estaba en la biblioteca, se usa ese. */
	suspend fun guardarArchivo(c: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
		val db = BaseDatos.obtener(c)
		db.libroPorUri(uri.toString())?.let { return@withContext it.id }
		val antes = db.todosConCompartidos().map { it.id }.toSet()
		val id = com.xebolax.lector.biblioteca.Importador.anadirExterno(c, uri)
		if (id !in antes) db.moverACompartidos(id)
		id
	}

	/** Abre (y, si se eligió, empieza a leer en voz alta) lo recién recibido. */
	fun abrir(c: Context, id: Long, leer: Boolean = alRecibir(c) == "leer") {
		val l = BaseDatos.obtener(c).libro(id) ?: return
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
		val viejos = BaseDatos.obtener(c).compartidosLeidosAntesDe(System.currentTimeMillis() - dias * 86_400_000L)
		viejos.forEach { borrar(c, it) }
		viejos.size
	}
}
