package com.xebolax.lector.catalogo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xebolax.lector.R
import com.xebolax.lector.databinding.FragmentLibrosBinding
import com.xebolax.lector.databinding.ItemListaBinding
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.formatos.Formato
import com.xebolax.lector.lectura.Abiertos
import com.xebolax.lector.ui.ZonaAcciones
import com.xebolax.lector.ui.Accion
import com.xebolax.lector.ui.accionesAccesibles
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.conEspera
import com.xebolax.lector.ui.campoTexto
import com.xebolax.lector.ui.confirmar
import com.xebolax.lector.ui.dp
import com.xebolax.lector.ui.sinFocoPropio
import com.xebolax.lector.ui.elegir
import com.xebolax.lector.ui.elegirUno
import com.xebolax.lector.ui.mensaje
import com.xebolax.lector.ui.lectorActivo
import com.xebolax.lector.ui.pedirTexto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pestaña Catálogos de la Biblioteca: bibliotecas de libros en línea (OPDS:
 * Project Gutenberg, tu Calibre-Web o el COPS del Synology...). Primero la
 * lista de catálogos; al abrir uno, sus entradas, que llevan a otra lista o a
 * un libro para descargar y leer. Atrás vuelve a la lista anterior.
 */
class CatalogoFragment : Fragment() {

	companion object {
		private val DEFECTO = listOf(
			Fuente("Project Gutenberg", "https://www.gutenberg.org/ebooks.opds/"),
			Fuente("Project Gutenberg: libros en español", "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads&query=l.es"),
			// Bibliotecas para personas ciegas: su web, entrando con tu usuario (lo descargado va a la biblioteca)
			Fuente("Tiflolibros", "https://www.tiflolibros.tiflonexos.org/", web = true),
			// La ONCE: se entra por el ClubONCE (DNI y contraseña) y desde ahí, Libros y películas, Biblioteca Digital
			Fuente("Biblioteca Digital ONCE (ClubONCE)", "https://club.once.es/", web = true),
			// Internet Archive: solo los libros libres (los de préstamo llevan DRM y piden cuenta)
			Fuente("Internet Archive: libros libres", IA_LIBRES),
			Fuente("Internet Archive: libros libres en español", "$IA_LIBRES&facet_section=languages&facet_item=spanish"),
		)
		private const val IA_LIBRES = "https://archive.org/services/opds/catalog?type=browse&section=ebooks&item=open-access"

		/** Catálogos que dejaron de funcionar: el antiguo de Internet Archive y Standard Ebooks (solo para sus mecenas). */
		private val RETIRADOS = listOf("bookserver.archive.org", "standardebooks.org")
		private const val CLAVE = "fuentes_opds"
	}

	/** web: no es un catálogo OPDS sino la web de una biblioteca (se abre en el navegador de la app). */
	class Fuente(val nombre: String, val url: String, val usuario: String = "", val clave: String = "", val web: Boolean = false) {
		fun credenciales() = if (usuario.isBlank()) null else Feed.Credenciales(usuario, clave)
		fun json(): JSONObject = JSONObject().put("nombre", nombre).put("url", url).put("usuario", usuario).put("clave", clave).put("web", web)
	}

	/** Lo que se ve: la lista de catálogos o una página de entradas. */
	private sealed class Fila {
		class DeFuente(val fuente: Fuente) : Fila()
		class DeEntrada(val entrada: Feed.Entrada) : Fila()
		class Mas(val url: String) : Fila()
	}

	private class Nivel(val titulo: String, val url: String?, val filas: List<Fila>, val busqueda: String?)

	private var _b: FragmentLibrosBinding? = null
	private val b get() = _b!!
	private val pila = ArrayDeque<Nivel>()
	private var fuente: Fuente? = null
	private var filas: List<Fila> = emptyList()
	private var carga: Job? = null

	private val actividad get() = activity as? AppCompatActivity

	/** Atrás dentro de un catálogo sube un nivel (solo con la pestaña a la vista). */
	private val atras = object : OnBackPressedCallback(false) {
		override fun handleOnBackPressed() = subir()
	}

	override fun onCreateView(inflador: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
		_b = FragmentLibrosBinding.inflate(inflador, contenedor, false)
		return b.root
	}

	override fun onViewCreated(vista: View, estado: Bundle?) {
		b.recycler.layoutManager = LinearLayoutManager(requireContext())
		b.recycler.sinFocoPropio()
		b.recycler.adapter = Adaptador()
		requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, atras)
		mostrarFuentes(enfocar = false)
	}

	override fun onHiddenChanged(oculto: Boolean) {
		super.onHiddenChanged(oculto)
		atras.isEnabled = !oculto && fuente != null
	}

	override fun onDestroyView() {
		carga?.cancel()
		_b = null
		super.onDestroyView()
	}

	/** Título de la barra: el del catálogo o la página abiertos. */
	fun titulo(): String? = pila.lastOrNull()?.titulo ?: fuente?.nombre

	private fun ponerTitulo() {
		if (isHidden) return
		val t = titulo() ?: getString(R.string.pestana_catalogos)
		actividad?.supportActionBar?.title = t
		actividad?.title = t
	}

	private fun subir() {
		carga?.cancel()
		if (pila.size > 1) {
			pila.removeLast()
			mostrar(pila.last())
		} else {
			pila.clear()
			mostrarFuentes()
		}
	}

	// --- Cabecera ------------------------------------------------------------------------

	private fun boton(texto: String, accion: () -> Unit) = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
		text = texto
		minHeight = dp(requireContext(), 48)
		setOnClickListener { accion() }
	}

	private fun construirCabecera() {
		val c = b.cabecera
		c.removeAllViews()
		val fila = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
		fun anadir(v: View) = fila.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		if (fuente == null) anadir(boton(getString(R.string.anadir_catalogo)) { editarFuente(null) })
		else {
			anadir(boton(getString(R.string.subir)) { subir() })
			if (pila.lastOrNull()?.busqueda != null) anadir(boton(getString(R.string.buscar)) { buscar() })
		}
		c.addView(fila)
	}

	// --- Catálogos -------------------------------------------------------------------------

	private fun prefs() = PreferenceManager.getDefaultSharedPreferences(requireContext())

	private fun fuentes(): List<Fuente> {
		val texto = prefs().getString(CLAVE, null) ?: return DEFECTO
		val guardadas = runCatching {
			val a = JSONArray(texto)
			List(a.length()) { i ->
				val o = a.getJSONObject(i)
				val url = o.optString("url")
				// La contraseña está con las demás claves (Secretos), no en la lista
				val clave = o.optString("clave").ifEmpty { com.xebolax.lector.datos.Secretos.leer(requireContext(), com.xebolax.lector.datos.Secretos.deCatalogo(url)) }
				Fuente(o.optString("nombre"), url, o.optString("usuario"), clave, o.optBoolean("web"))
			}
		}.getOrDefault(emptyList())
		// Las bibliotecas web (Tiflolibros, ONCE) se añaden una vez a las listas ya guardadas
		if (!prefs().getBoolean("bibliotecas_web_puestas", false)) {
			prefs().edit().putBoolean("bibliotecas_web_puestas", true).apply()
			val nuevas = DEFECTO.filter { d -> d.web && guardadas.none { it.url == d.url } }
			if (nuevas.isNotEmpty()) {
				val todas = nuevas + guardadas
				guardarFuentes(todas)
				return fuentes()
			}
		}
		// Los catálogos retirados se cambian por los que funcionan (una sola vez)
		if (guardadas.any { f -> RETIRADOS.any { it in f.url } }) {
			val arregladas = guardadas.filterNot { f -> RETIRADOS.any { it in f.url } } + DEFECTO.filter { d -> d.url.startsWith(IA_LIBRES) && guardadas.none { it.url == d.url } }
			guardarFuentes(arregladas)
			return arregladas
		}
		return guardadas
	}

	private fun guardarFuentes(lista: List<Fuente>) {
		// Las contraseñas van aparte, con las demás claves (para que no salgan en las copias sin querer)
		lista.forEach { com.xebolax.lector.datos.Secretos.guardar(requireContext(), com.xebolax.lector.datos.Secretos.deCatalogo(it.url), it.clave) }
		prefs().edit().putString(CLAVE, JSONArray(lista.map { it.json().apply { remove("clave") } }).toString()).apply()
	}

	private fun mostrarFuentes(enfocar: Boolean = true) {
		fuente = null
		atras.isEnabled = false
		filas = fuentes().map { Fila.DeFuente(it) }
		construirCabecera()
		ponerTitulo()
		refrescar(getString(R.string.fuentes_opds_ayuda), enfocar = enfocar)
	}

	/** Qué es lo que se añade: se averigua al guardar, un catálogo OPDS o la web de una biblioteca. */
	private enum class Tipo { AVERIGUAR, OPDS, WEB }

	/**
	 * Añadir o editar un catálogo o una biblioteca web. Si no se dice qué es,
	 * al guardar se comprueba la dirección: si es un catálogo OPDS, se lista en
	 * la app; si es una página web, se abre en el navegador de la app (como
	 * Tiflolibros), donde se entra con el usuario de esa web y lo descargado
	 * va a la biblioteca. Usuario y contraseña solo sirven para los catálogos.
	 */
	private fun editarFuente(original: Fuente?) {
		val c = requireContext()
		val (cNombre, nombre) = c.campoTexto(getString(R.string.nombre), original?.nombre.orEmpty())
		val (cUrl, url) = c.campoTexto(getString(R.string.direccion_web), original?.url ?: "https://")
		val (cUsuario, usuario) = c.campoTexto(getString(R.string.usuario_opcional), original?.usuario.orEmpty())
		val (cClave, clave) = c.campoTexto(getString(R.string.clave_opcional), original?.clave.orEmpty())
		url.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
		clave.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
		var tipo = when {
			original == null -> Tipo.AVERIGUAR
			original.web -> Tipo.WEB
			else -> Tipo.OPDS
		}
		val nombresTipo = listOf(getString(R.string.tipo_averiguar), getString(R.string.tipo_opds), getString(R.string.tipo_web))
		val botonTipo = MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { minHeight = dp(c, 48) }
		fun ponerTipo() {
			botonTipo.text = getString(R.string.etiqueta_valor, getString(R.string.tipo_catalogo), nombresTipo[tipo.ordinal])
			// En una biblioteca web se entra en su propia página: aquí no hacen falta usuario ni contraseña
			cUsuario.isVisible = tipo != Tipo.WEB
			cClave.isVisible = tipo != Tipo.WEB
		}
		ponerTipo()
		botonTipo.setOnClickListener {
			c.elegirUno(getString(R.string.tipo_catalogo), nombresTipo, tipo.ordinal) { i ->
				tipo = Tipo.entries[i]
				ponerTipo()
			}
		}
		val formulario = LinearLayout(c).apply {
			orientation = LinearLayout.VERTICAL
			val m = dp(c, 20)
			setPadding(m, m / 2, m, 0)
			addView(cNombre)
			addView(cUrl)
			addView(botonTipo)
			addView(cUsuario)
			addView(cClave)
		}
		MaterialAlertDialogBuilder(c)
			.setTitle(if (original == null) getString(R.string.anadir_catalogo) else getString(R.string.etiqueta_valor, getString(R.string.editar_catalogo), original.nombre))
			.setView(android.widget.ScrollView(c).apply { addView(formulario) })
			.setPositiveButton(R.string.guardar) { _, _ ->
				val u = url.text.toString().trim().let { if (it.isNotEmpty() && !it.contains("://")) "https://$it" else it }
				if (u.length < 10) return@setPositiveButton
				val n = nombre.text.toString().trim()
				val credenciales = if (tipo == Tipo.WEB) Feed.Credenciales("", "") to false else Feed.Credenciales(usuario.text.toString().trim(), clave.text.toString()) to true
				fun guardar(web: Boolean, titulo: String) {
					val (cred, conClave) = credenciales
					val nueva = Fuente(n.ifBlank { titulo.ifBlank { Uri.parse(u).host ?: u } }, u,
						if (conClave && !web) cred.usuario else "", if (conClave && !web) cred.clave else "", web)
					val lista = fuentes().toMutableList()
					val i = original?.let { o -> lista.indexOfFirst { it.url == o.url && it.nombre == o.nombre } } ?: -1
					if (i >= 0) lista[i] = nueva else lista += nueva
					guardarFuentes(lista)
					mostrarFuentes()
					if (web && original?.web != true) actividad?.mensaje(nueva.nombre, getString(R.string.biblioteca_web_anadida, nueva.nombre))
				}
				when (tipo) {
					Tipo.OPDS -> guardar(false, "")
					Tipo.WEB -> guardar(true, "")
					Tipo.AVERIGUAR -> averiguarTipo(u, credenciales.first.takeIf { it.usuario.isNotBlank() }) { web, titulo -> guardar(web, titulo) }
				}
			}
			.setNegativeButton(R.string.cancelar, null)
			.show()
	}

	/**
	 * ¿Es un catálogo OPDS o la web de una biblioteca? Se descarga la dirección:
	 * si se lee como catálogo, catálogo; si no, web (con el título de la página
	 * como nombre). Si no se puede abrir, se pregunta.
	 */
	private fun averiguarTipo(url: String, credenciales: Feed.Credenciales?, alSaber: (web: Boolean, titulo: String) -> Unit) {
		val a = actividad ?: return
		a.lifecycleScope.launch {
			val texto = try {
				a.conEspera(getString(R.string.anadir_catalogo), getString(R.string.comprobando_direccion)) {
					withContext(Dispatchers.IO) { Feed.descargarTexto(url, credenciales) }
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				// No se pudo abrir: que lo diga quien la conoce
				a.elegir(a.getString(R.string.no_se_pudo_comprobar, e.message.orEmpty()), listOf(
					a.getString(R.string.tipo_web) to { alSaber(true, "") },
					a.getString(R.string.tipo_opds) to { alSaber(false, "") },
				))
				return@launch
			}
			val catalogo = withContext(Dispatchers.Default) { runCatching { Feed.leer(texto, url) }.getOrNull() }
			// La pestaña se cerró mientras se comprobaba: no hay dónde guardarlo
			if (!isAdded) return@launch
			if (catalogo != null) alSaber(false, catalogo.titulo)
			else alSaber(true, withContext(Dispatchers.Default) { runCatching { nombreDeTitulo(org.jsoup.Jsoup.parse(texto).title()) }.getOrDefault("") })
		}
	}

	/** «Biblioteca X | Inicio» o «Inicio - Biblioteca X» → «Biblioteca X»: la parte más larga que no sea «Inicio». */
	private fun nombreDeTitulo(titulo: String): String =
		titulo.split(" | ", " - ", " – ", " — ", " · ").map { it.trim() }
			.filter { it.isNotEmpty() && it.lowercase() !in setOf("inicio", "portada", "home", "bienvenido", "bienvenida", "welcome") }
			.maxByOrNull { it.length } ?: titulo.trim()

	// --- Entradas --------------------------------------------------------------------

	private fun cargar(titulo: String, url: String, nuevaPila: Boolean = true, anadir: List<Fila> = emptyList()) {
		val f = fuente ?: return
		carga?.cancel()
		b.estado.text = getString(R.string.cargando)
		actividad?.avisar(getString(R.string.cargando), mostrar = false)
		carga = viewLifecycleOwner.lifecycleScope.launch {
			try {
				val pagina = withContext(Dispatchers.IO) { Feed.leer(Feed.descargarTexto(url, f.credenciales()), url) }
				val nuevas = anadir + pagina.entradas.map { Fila.DeEntrada(it) } + listOfNotNull(pagina.siguiente?.let { Fila.Mas(it) })
				val nivel = Nivel(pagina.titulo.ifBlank { titulo }, url, nuevas, pagina.busqueda ?: pila.lastOrNull()?.busqueda)
				if (!nuevaPila) pila.removeLastOrNull()
				pila.addLast(nivel)
				mostrar(nivel, enfocarEn = anadir.size)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				if (_b == null) return@launch
				// No se pudo abrir el catálogo: se sigue en la lista de catálogos
				if (pila.isEmpty()) fuente = null
				b.estado.text = getString(R.string.error_catalogo, e.message.orEmpty())
				actividad?.avisar(getString(R.string.error_catalogo, e.message.orEmpty()))
			}
		}
	}

	private fun mostrar(nivel: Nivel, enfocarEn: Int = 0) {
		if (_b == null) return
		filas = nivel.filas
		atras.isEnabled = !isHidden
		construirCabecera()
		ponerTitulo()
		val n = filas.count { it is Fila.DeEntrada }
		refrescar(if (n == 0) getString(R.string.catalogo_vacio) else nivel.titulo + ", " + resources.getQuantityString(R.plurals.elementos, n, n), enfocarEn)
	}

	private fun buscar() {
		val plantilla = pila.lastOrNull()?.busqueda ?: return
		val f = fuente ?: return
		val a = actividad ?: return
		a.pedirTexto(getString(R.string.buscar), getString(R.string.buscar_libros)) { texto ->
			if (texto.isBlank()) return@pedirTexto
			viewLifecycleOwner.lifecycleScope.launch {
				val url = runCatching { withContext(Dispatchers.IO) { Feed.direccionBusqueda(plantilla, texto, f.credenciales()) } }.getOrNull()
					?: return@launch a.avisar(getString(R.string.error_catalogo, ""))
				cargar(getString(R.string.resultados_busqueda, texto), url)
			}
		}
	}

	private fun alElegir(fila: Fila) {
		when (fila) {
			is Fila.DeFuente -> if (fila.fuente.web) {
				actividad?.let { NavegadorBibliotecaActivity.abrir(it, fila.fuente.nombre, fila.fuente.url) }
			} else {
				fuente = fila.fuente
				pila.clear()
				cargar(fila.fuente.nombre, fila.fuente.url)
			}
			is Fila.Mas -> {
				// La página siguiente se añade a la actual
				val actual = pila.lastOrNull() ?: return
				cargar(actual.titulo, fila.url, nuevaPila = false, anadir = actual.filas.filter { it !is Fila.Mas })
			}
			is Fila.DeEntrada -> {
				val e = fila.entrada
				when {
					e.navegacion != null && e.descargas.isEmpty() -> cargar(e.titulo, e.navegacion)
					e.descargas.isNotEmpty() -> libro(e)
					e.enlace != null -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.enlace))) }
					else -> verResumen(e)
				}
			}
		}
	}

	private fun verResumen(e: Feed.Entrada) {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(e.titulo)
			.setMessage(listOf(e.autor, e.fecha, e.resumen).filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { e.titulo })
			.setPositiveButton(R.string.aceptar, null)
			.show()
	}

	/** El libro ya descargado de este catálogo (está en la biblioteca), o null. */
	private fun yaDescargado(e: Feed.Entrada): Long? {
		val a = actividad ?: return null
		val base = nombreBase(e)
		val db = BaseDatos.obtener(a)
		return File(a.filesDir, "biblioteca/descargas").listFiles().orEmpty()
			.filter { it.name.startsWith("$base.") && !it.name.endsWith(".parte") }
			.firstNotNullOfOrNull { db.libroPorUri(Uri.fromFile(it).toString())?.id }
	}

	/** Nombre del archivo de un libro del catálogo: título y una huella de título y autor (dos libros con el mismo título no se pisan). */
	private fun nombreBase(e: Feed.Entrada): String {
		val titulo = e.titulo.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70).ifBlank { "libro" }
		return titulo + " " + com.xebolax.lector.formatos.Fuente.hash(e.titulo + "\u0000" + e.autor).take(6)
	}

	/** Libro del catálogo: información y descargar para leerlo (o abrirlo, si ya lo tienes). */
	private fun libro(e: Feed.Entrada) {
		val mejor = Feed.mejorDescarga(e.descargas) ?: return verResumen(e)
		// Si ya se descargó: se mira en el disco y en la base de datos fuera del hilo principal
		viewLifecycleOwner.lifecycleScope.launch {
			val ya = withContext(Dispatchers.IO) { yaDescargado(e) }
			if (_b != null) libro(e, mejor, ya)
		}
	}

	private fun libro(e: Feed.Entrada, mejor: Feed.Enlace, ya: Long?) {
		ya?.let { id ->
			val texto = listOf(getString(R.string.catalogo_ya_lo_tienes), e.autor, e.resumen.take(1500)).filter { it.isNotBlank() }.joinToString("\n\n")
			MaterialAlertDialogBuilder(requireContext())
				.setTitle(e.titulo)
				.setMessage(texto)
				.setPositiveButton(R.string.abrir) { _, _ -> actividad?.let { Abiertos.abrirPantalla(it, id) } }
				.setNeutralButton(R.string.descargar_otra_vez) { _, _ -> descargar(e, mejor) }
				.setNegativeButton(R.string.cancelar, null)
				.show()
			return
		}
		val texto = listOf(e.autor, e.resumen.take(1500)).filter { it.isNotBlank() }.joinToString("\n\n")
		val dialogo = MaterialAlertDialogBuilder(requireContext())
			.setTitle(e.titulo)
			.setMessage(texto.ifBlank { e.titulo })
			.setPositiveButton(R.string.descargar_y_leer) { _, _ -> descargar(e, mejor) }
			.setNegativeButton(R.string.cancelar, null)
		if (e.descargas.size > 1) dialogo.setNeutralButton(R.string.otro_formato) { _, _ ->
			requireContext().elegir(getString(R.string.otro_formato), e.descargas.map { d ->
				(d.titulo.ifBlank { Feed.extension(d.tipo, d.url).uppercase() }) to { descargar(e, d) }
			})
		}
		dialogo.show()
	}

	private fun descargar(e: Feed.Entrada, d: Feed.Enlace) {
		val f = fuente ?: return
		val a = actividad ?: return
		a.lifecycleScope.launch {
			try {
				val id = a.conEspera(getString(R.string.descargar_y_leer), getString(R.string.descargando_libro, e.titulo)) { withContext(Dispatchers.IO) {
					val carpeta = File(a.filesDir, "biblioteca/descargas").apply { mkdirs() }
					val base = nombreBase(e)
					val temporal = File(carpeta, "$base.parte")
					// Internet Archive: el archivo real se busca en su lista de archivos (su enlace directo falla)
					val real = Feed.archivoInternetArchive(d) ?: d
					val destino = try {
						val tipo = Feed.descargarArchivo(real.url, temporal, f.credenciales()).orEmpty()
						var ext = Feed.extension(real.tipo.ifBlank { tipo }, real.url)
						// Si el tipo no lo dice, lo dice el contenido
						if (Formato.deExtension(ext) == Formato.DESCONOCIDO) {
							val cabecera = temporal.inputStream().use { val b = ByteArray(8); val n = it.read(b); b.copyOf(maxOf(n, 0)) }
							com.xebolax.lector.catalogo.NombreDescarga.extensionDeContenido(cabecera).takeIf { it.isNotEmpty() }?.let { ext = it }
						}
						val destino = File(carpeta, "$base.$ext")
						destino.delete()
						if (!temporal.renameTo(destino)) throw java.io.IOException(a.getString(R.string.error_guardar_archivo))
						destino
					} finally {
						temporal.delete()
					}
					// Cancelado justo al terminar: no se añade, y el archivo no se queda en el disco (salvo que ya fuera de un libro)
					if (!isActive) {
						if (BaseDatos.obtener(a).libroPorUri(Uri.fromFile(destino).toString()) == null) destino.delete()
						throw CancellationException()
					}
					val formato = Formato.deExtension(destino.extension).takeIf { it != Formato.DESCONOCIDO } ?: Formato.EPUB
					BaseDatos.obtener(a).anadirLibro(
						Uri.fromFile(destino).toString(), destino.name, e.titulo, e.autor, formato.name, formato.audio, destino.length(), null,
					)
				} }
				a.avisar(a.getString(R.string.libro_descargado, e.titulo))
				Abiertos.abrirPantalla(a, id)
			} catch (ex: kotlinx.coroutines.CancellationException) {
				throw ex
			} catch (ex: Exception) {
				a.avisar(a.getString(R.string.error_descarga, ex.message.orEmpty()), mostrar = true)
			}
		}
	}

	// --- Lista ---------------------------------------------------------------------------

	private fun refrescar(estado: String, enfocarEn: Int = 0, enfocar: Boolean = true) {
		b.estado.text = estado
		@Suppress("NotifyDataSetChanged")
		b.recycler.adapter?.notifyDataSetChanged()
		(b.recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(enfocarEn, 0)
		if (!enfocar) return
		b.recycler.postDelayed({
			val v = _b?.recycler?.findViewHolderForAdapterPosition(enfocarEn)?.itemView ?: return@postDelayed
			if (requireContext().lectorActivo()) v.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
		}, 150)
	}

	private inner class Vista(val f: ItemListaBinding) : RecyclerView.ViewHolder(f.root)

	private inner class Adaptador : RecyclerView.Adapter<Vista>() {
		// Ids estables: al cambiar la lista, TalkBack no pierde el foco
		init { setHasStableIds(true) }
		override fun getItemId(posicion: Int): Long = when (val f = filas[posicion]) {
			is Fila.DeFuente -> ("f" + f.fuente.url + f.fuente.nombre).hashCode().toLong()
			is Fila.DeEntrada -> ("e" + f.entrada.titulo + f.entrada.autor + f.entrada.descargas.firstOrNull()?.url.orEmpty() + f.entrada.navegacion.orEmpty()).hashCode().toLong() * 31 + posicion
			is Fila.Mas -> ("m" + f.url).hashCode().toLong()
		}
		override fun getItemCount() = filas.size

		override fun onCreateViewHolder(padre: ViewGroup, tipo: Int) = Vista(ItemListaBinding.inflate(LayoutInflater.from(padre.context), padre, false))

		override fun onBindViewHolder(v: Vista, posicion: Int) {
			val fila = filas[posicion]
			val (texto, detalle) = when (fila) {
				is Fila.DeFuente -> fila.fuente.nombre to if (fila.fuente.web) getString(R.string.biblioteca_web_detalle) else Uri.parse(fila.fuente.url).host.orEmpty()
				is Fila.Mas -> getString(R.string.mas_resultados) to ""
				is Fila.DeEntrada -> {
					val e = fila.entrada
					val tipo = when {
						e.descargas.isNotEmpty() -> getString(R.string.tipo_libro)
						e.navegacion != null -> getString(R.string.tipo_lista)
						else -> ""
					}
					e.titulo to listOf(e.autor, e.fecha, tipo, e.resumen.take(140)).filter { it.isNotBlank() }.joinToString(" · ")
				}
			}
			v.f.texto.text = texto
			v.f.detalle.text = detalle
			v.f.detalle.isVisible = detalle.isNotBlank()
			v.f.root.contentDescription = listOf(texto, detalle).filter { it.isNotBlank() }.joinToString(", ")
			v.f.root.setOnClickListener { alElegir(fila) }
			val acciones: List<Accion> = when (fila) {
				is Fila.DeFuente -> listOf(
					getString(R.string.abrir) to { alElegir(fila) },
					getString(R.string.editar_catalogo) to { editarFuente(fila.fuente) },
					getString(R.string.borrar) to {
						actividad?.confirmar(getString(R.string.confirmar_borrar_fuente, fila.fuente.nombre), getString(R.string.borrar)) {
							guardarFuentes(fuentes().filterNot { it.url == fila.fuente.url && it.nombre == fila.fuente.nombre })
							mostrarFuentes()
						}
					},
				)
				is Fila.DeEntrada -> listOfNotNull(
					(getString(R.string.ver_resumen) to { verResumen(fila.entrada) }).takeIf { fila.entrada.resumen.isNotBlank() },
				)
				else -> emptyList()
			}
			v.f.root.accionesAccesibles(acciones, ZonaAcciones.CATALOGO)
			v.f.root.setOnLongClickListener {
				if (acciones.isEmpty()) false else {
					requireContext().elegir(texto.take(60), acciones)
					true
				}
			}
		}
	}
}
