package com.xebolax.lector.catalogo

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.xebolax.lector.R
import com.xebolax.lector.biblioteca.Importador
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.lectura.Abiertos
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.conEspera
import com.xebolax.lector.ui.configurarBarra
import com.xebolax.lector.ui.mensaje
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * La web de una biblioteca para personas ciegas (Tiflolibros, Biblioteca
 * Digital ONCE) dentro de la app: entras tú con tu usuario y contraseña en su
 * web oficial (la app no los ve ni los guarda; la sesión se recuerda como en
 * el navegador) y lo que descargues se guarda en tu biblioteca y se puede
 * abrir al momento. Los ZIP con audiolibros o DAISY se abren como tales.
 */
class NavegadorBibliotecaActivity : AppCompatActivity() {

	companion object {
		private const val EXTRA_URL = "url"
		private const val EXTRA_TITULO = "titulo"

		fun abrir(c: Context, titulo: String, url: String) =
			c.startActivity(Intent(c, NavegadorBibliotecaActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITULO, titulo))
	}

	private lateinit var web: WebView
	/** Lo último que se pulsó en la web (texto del enlace o botón) y el título del libro de esa página: dan nombre a la descarga. */
	@Volatile private var enlacePulsado = ""
	@Volatile private var tituloPagina = ""

	/** Lo que la página cuenta a la app al pulsar un enlace o un botón. */
	private inner class Puente {
		@android.webkit.JavascriptInterface
		fun pulsado(texto: String?, titulo: String?) {
			enlacePulsado = texto.orEmpty().trim().take(80)
			tituloPagina = titulo.orEmpty().trim().take(150)
		}
	}
	private lateinit var progreso: LinearProgressIndicator
	private val inicio get() = intent.getStringExtra(EXTRA_URL).orEmpty()
	private val nombre get() = intent.getStringExtra(EXTRA_TITULO).orEmpty()

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val raiz = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			fitsSystemWindows = true
		}
		val barra = MaterialToolbar(this)
		raiz.addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		// La barra de carga se ve, pero TalkBack no se para en ella (la carga se anuncia al terminar)
		progreso = LinearProgressIndicator(this).apply {
			isIndeterminate = false
			importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
		}
		raiz.addView(progreso, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		web = WebView(this)
		raiz.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
		setContentView(raiz)
		configurarBarra(barra, nombre)
		ViewCompat.setAccessibilityPaneTitle(web, nombre)

		CookieManager.getInstance().setAcceptCookie(true)
		CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
		web.settings.apply {
			javaScriptEnabled = true
			domStorageEnabled = true
			// La letra de la web sigue el tamaño de letra del sistema
			textZoom = (resources.configuration.fontScale * 100).toInt()
			builtInZoomControls = true
			displayZoomControls = false
		}
		web.addJavascriptInterface(Puente(), "LectorUniversal")
		web.webViewClient = object : WebViewClient() {
			override fun onPageFinished(vista: WebView, url: String) {
				// Al pulsar un enlace o botón se apunta su texto y el título del libro (el primer encabezado de la página)
				vista.evaluateJavascript(
					"""(function(){if(window.__lu)return;window.__lu=1;document.addEventListener('click',function(e){
						var a=e.target.closest('a,button,input[type=submit],input[type=button]');if(!a)return;
						var t=(a.innerText||a.value||a.title||a.getAttribute('aria-label')||'').trim();
						var h=document.querySelector('h1,h2');LectorUniversal.pulsado(t,(h&&h.innerText)||document.title);},true);})();""",
					null,
				)
				progreso.isVisible = false
				CookieManager.getInstance().flush()
				// Se dice qué página se ha abierto (con TalkBack, sin mover el foco)
				vista.title?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { avisar(it, mostrar = false) }
			}
		}
		web.webChromeClient = object : WebChromeClient() {
			override fun onProgressChanged(vista: WebView, nuevo: Int) {
				progreso.isVisible = nuevo < 100
				progreso.setProgressCompat(nuevo, true)
			}
		}
		web.setDownloadListener { url, agente, disposicion, tipo, _ -> descargar(url, agente, disposicion, tipo) }
		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() = if (web.canGoBack()) web.goBack() else finish()
		})
		if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(inicio)
	}

	override fun onSaveInstanceState(estado: Bundle) {
		super.onSaveInstanceState(estado)
		web.saveState(estado)
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menu.add(Menu.NONE, 1, Menu.NONE, R.string.recargar)
		menu.add(Menu.NONE, 2, Menu.NONE, R.string.ir_al_inicio)
		menu.add(Menu.NONE, 3, Menu.NONE, R.string.abrir_navegador)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			1 -> web.reload()
			2 -> web.loadUrl(inicio)
			3 -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web.url ?: inicio))) }
			else -> return super.onOptionsItemSelected(item)
		}
		return true
	}

	/** Descarga con la sesión de la web (sus cookies), guarda en la biblioteca y ofrece abrirlo. */
	private fun descargar(url: String, agente: String, disposicion: String?, tipo: String?) {
		if (!url.startsWith("http")) return mensaje(nombre, getString(R.string.web_descarga_no_compatible))
		val pagina = web.url
		// Nombre provisional (para la ventana de espera); el definitivo sale de la respuesta del servidor
		val provisional = NombreDescarga.elegir(disposicion, url, tipo, tituloPagina.ifBlank { web.title }, enlacePulsado, nombre)
		val titulo = tituloPagina.ifBlank { web.title.orEmpty() }
		val enlace = enlacePulsado
		lifecycleScope.launch {
			try {
				val f = conEspera(nombre, getString(R.string.descargando_libro, provisional)) {
					withContext(Dispatchers.IO) { bajar(url, agente, pagina, disposicion, tipo, titulo, enlace) }
				}
				val r = Importador.anadirArchivos(this@NavegadorBibliotecaActivity, listOf(Uri.fromFile(f)), null)
				val id = r.anadidos.firstOrNull() ?: BaseDatos.obtener(this@NavegadorBibliotecaActivity).libroPorUri(Uri.fromFile(f).toString())?.id
					?: return@launch mensaje(nombre, getString(R.string.web_no_anadido))
				// Título, autor y tipo reales (audiolibro, DAISY) por detrás
				if (id in r.nuevos) com.xebolax.lector.LectorApp.ambito.launch { Importador.completarDatos(applicationContext, listOf(id)) }
				MaterialAlertDialogBuilder(this@NavegadorBibliotecaActivity)
					.setTitle(R.string.web_descargado)
					.setMessage(getString(R.string.web_descargado_texto, f.name))
					.setPositiveButton(R.string.abrir) { _, _ -> Abiertos.abrirPantalla(this@NavegadorBibliotecaActivity, id) }
					.setNegativeButton(R.string.web_seguir_aqui, null)
					.show()
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				mensaje(nombre, getString(R.string.web_error_descarga, e.message.orEmpty()))
			}
		}
	}

	/**
	 * La descarga en sí, con las cookies de la sesión. El nombre se toma de la respuesta del servidor
	 * (o del título del libro y la clase de descarga) y la extensión se comprueba con el contenido.
	 * Si llega una página en lugar de un archivo, se dice.
	 */
	private suspend fun bajar(url: String, agente: String, pagina: String?, disposicion: String?, tipo: String?, titulo: String, enlace: String): File {
		val carpeta = File(filesDir, "biblioteca/descargas").apply { mkdirs() }
		// Las redirecciones, a mano: las cookies de la sesión solo van al servidor de cada dirección
		val c = Redirecciones.abrir(url, { con ->
			con.connectTimeout = 20_000
			con.readTimeout = 60_000
			con.setRequestProperty("User-Agent", agente)
			pagina?.let { con.setRequestProperty("Referer", it) }
		}) { direccion -> CookieManager.getInstance().getCookie(direccion) }
		var temporal: File? = null
		try {
			if (c.responseCode !in 200..299) error(getString(R.string.web_servidor_respondio, c.responseCode))
			if (c.contentType.orEmpty().startsWith("text/html")) error(getString(R.string.web_no_es_archivo))
			// El nombre que da el servidor en esta respuesta (o el del navegador, o el título del libro)
			var nombreArchivo = NombreDescarga.elegir(c.getHeaderField("Content-Disposition") ?: disposicion, c.url.toString(), c.contentType ?: tipo, titulo, enlace, nombre)
			val parte = File(carpeta, "descarga_${System.currentTimeMillis()}.parte")
			temporal = parte
			c.inputStream.use { entrada -> parte.outputStream().use { salida ->
				val b = ByteArray(64 * 1024)
				while (true) {
					kotlin.coroutines.coroutineContext.ensureActive()
					val leidos = entrada.read(b)
					if (leidos < 0) break
					salida.write(b, 0, leidos)
				}
			} }
			// Si el nombre no trae extensión (o trae una que no es de libro), la del contenido: ZIP, PDF, MP3...
			val porContenido = parte.inputStream().use { e -> ByteArray(8).also { e.read(it) } }.let { NombreDescarga.extensionDeContenido(it) }
			val actual = nombreArchivo.substringAfterLast('.', "").lowercase()
			if (porContenido.isNotEmpty() && (actual.isEmpty() || actual in setOf("bin", "dat", "tmp", "download", "aspx", "php"))) {
				nombreArchivo = (if (actual.isEmpty()) nombreArchivo else nombreArchivo.substringBeforeLast('.')) + ".$porContenido"
			}
			// Sin pisar otro archivo con el mismo nombre: «Libro (2).zip»
			var destino = File(carpeta, nombreArchivo)
			var n = 2
			while (destino.exists()) {
				destino = File(carpeta, nombreArchivo.substringBeforeLast('.') + " ($n)" + nombreArchivo.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" })
				n++
			}
			if (!parte.renameTo(destino)) error(getString(R.string.web_no_anadido))
			return destino
		} finally {
			c.disconnect()
			// Cancelado o con error: no queda el «.parte» en el disco (si se guardó, ya no existe con ese nombre)
			temporal?.delete()
		}
	}

	override fun onDestroy() {
		web.destroy()
		super.onDestroy()
	}
}
