package com.xebolax.lector.datos

import androidx.annotation.StringRes
import com.xebolax.lector.LectorApp
import java.io.IOException

/**
 * Error con un texto para la persona usuaria. Su mensaje (e.message) sale de
 * los recursos y se traduce al leerlo, en el idioma de la app: se puede
 * mostrar tal cual.
 */
interface ConTextoUsuario

/** El texto de un recurso en el idioma de la app; sin la app arrancada (pruebas unitarias), su número. */
internal fun textoUsuario(@StringRes recurso: Int, argumentos: Array<out Any>): String = try {
	LectorApp.texto(recurso, *argumentos)
} catch (e: RuntimeException) {
	"#$recurso ${argumentos.joinToString()}".trim()
}

/** Fallo de entrada y salida (red, archivos) con un texto para la persona usuaria. */
open class FalloIo(@StringRes private val recurso: Int, private vararg val argumentos: Any) : IOException(), ConTextoUsuario {
	override val message: String get() = textoUsuario(recurso, argumentos)
}

/** Fallo que no es de red ni de archivos (datos no válidos, comprobaciones) con un texto para la persona usuaria. */
open class FalloEstado(@StringRes private val recurso: Int, private vararg val argumentos: Any) : IllegalStateException(), ConTextoUsuario {
	override val message: String get() = textoUsuario(recurso, argumentos)
}
