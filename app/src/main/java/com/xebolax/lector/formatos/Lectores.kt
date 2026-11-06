package com.xebolax.lector.formatos

import com.xebolax.lector.modelo.ConstructorDocumento
import com.xebolax.lector.modelo.Contenido

/** Constructor de documento con las opciones de lectura aplicadas. */
fun ContextoLectura.nuevoConstructor(): ConstructorDocumento =
	ConstructorDocumento().apply { dividirParrafos = opciones.dividirParrafos }

/**
 * Punto de entrada para abrir cualquier archivo: reconoce el formato y
 * llama a su lector. Usa la caché de documentos ya convertidos.
 */
object Lectores {

	fun lectorDe(formato: Formato): LectorFormato? = when (formato) {
		Formato.EPUB -> LectorEpub
		Formato.HTML -> LectorHtml
		Formato.TXT -> LectorTexto
		Formato.MARKDOWN -> LectorMarkdown
		Formato.DOCX -> LectorDocx
		Formato.DOC -> LectorDocAntiguo
		Formato.ODT -> LectorOdt
		Formato.RTF -> LectorRtf
		Formato.FB2 -> LectorFb2
		Formato.PPTX -> LectorPresentacion
		Formato.XLSX -> LectorHoja
		Formato.PDF -> LectorPdf
		Formato.MOBI -> LectorMobi
		Formato.CHM -> LectorChm
		Formato.DAISY -> LectorDaisy
		Formato.AUDIO -> LectorAudio
		Formato.LISTA -> LectorListaAudio
		Formato.CARPETA -> LectorCarpeta
		Formato.COMIC -> LectorComic
		Formato.ZIP -> LectorZip
		Formato.DESCONOCIDO -> LectorTexto
	}

	/** Abre (o recupera de la caché) el contenido de una fuente. Se llama fuera del hilo principal. */
	fun abrir(fuente: Fuente, ctx: ContextoLectura, formato: Formato = Formato.reconocer(fuente)): Contenido {
		val clave = CacheContenido.clave(fuente, formato, ctx.opciones)
		CacheContenido.cargar(ctx.app, clave)?.let { return it }
		val lector = lectorDe(formato) ?: throw ExcepcionFormato("Formato no compatible.")
		val contenido = try {
			lector.leer(fuente, ctx)
		} catch (e: ExcepcionFormato) {
			throw e
		} catch (e: InterruptedException) {
			throw e
		} catch (e: OutOfMemoryError) {
			throw ExcepcionFormato("El documento es demasiado grande para la memoria del dispositivo.", e)
		} catch (e: Exception) {
			throw ExcepcionFormato("No se pudo leer el archivo: ${e.message ?: e.javaClass.simpleName}", e)
		}
		// Con contraseña no se guarda en la caché (sería una copia sin proteger)
		if (ctx.opciones.clave == null) runCatching { CacheContenido.guardar(ctx.app, clave, contenido) }
		return contenido
	}
}
