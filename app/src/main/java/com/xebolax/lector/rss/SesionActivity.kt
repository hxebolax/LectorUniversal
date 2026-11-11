package com.xebolax.lector.rss

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import com.google.android.material.appbar.MaterialToolbar
import com.xebolax.lector.R
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.configurarBarra

/**
 * Entrar con tu cuenta en un medio en el que estás suscrito: se abre su web
 * aquí dentro, inicias sesión como siempre y la sesión queda guardada. Solo
 * se usa para descargar los artículos completos de ese medio.
 */
class SesionActivity : AppCompatActivity() {

	companion object {
		private const val EXTRA_DOMINIO = "dominio"

		fun abrir(c: Context, dominio: String) = c.startActivity(Intent(c, SesionActivity::class.java).putExtra(EXTRA_DOMINIO, dominio))

		/** Cierra la sesión de un medio (borra sus cookies). */
		fun cerrarSesion(dominio: String) {
			val gestor = CookieManager.getInstance()
			for (url in listOf("https://$dominio", "https://www.$dominio")) {
				val cookies = gestor.getCookie(url) ?: continue
				for (nombre in cookies.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }) {
					for (d in listOf("", "; Domain=.$dominio", "; Domain=$dominio")) gestor.setCookie(url, "$nombre=; Max-Age=0; Path=/$d")
				}
			}
			gestor.flush()
		}

		fun tieneSesion(dominio: String) = runCatching { !CookieManager.getInstance().getCookie("https://$dominio").isNullOrBlank() }.getOrDefault(false)
	}

	private lateinit var web: WebView

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val dominio = intent.getStringExtra(EXTRA_DOMINIO).orEmpty()
		val barra = MaterialToolbar(this)
		web = WebView(this)
		setContentView(LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			fitsSystemWindows = true
			addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
			addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
		})
		configurarBarra(barra, getString(R.string.rss_entrar_en, dominio))
		CookieManager.getInstance().setAcceptCookie(true)
		CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
		web.settings.javaScriptEnabled = true
		web.settings.domStorageEnabled = true
		web.webViewClient = WebViewClient()
		web.webChromeClient = WebChromeClient()
		// Atrás vuelve a la página anterior de la web; en la primera, sale
		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				if (web.canGoBack()) web.goBack() else terminar()
			}
		})
		// Al girar la pantalla se recupera la página en la que se estaba; si no se puede, se carga otra vez la del medio
		val restaurada = savedInstanceState?.let { web.restoreState(it) } != null
		if (!restaurada) web.loadUrl("https://$dominio")
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		web.saveState(outState)
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menu.add(Menu.NONE, 1, Menu.NONE, R.string.rss_sesion_lista).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		if (item.itemId == 1) {
			terminar()
			return true
		}
		return super.onOptionsItemSelected(item)
	}

	private fun terminar() {
		CookieManager.getInstance().flush()
		avisar(getString(R.string.rss_sesion_guardada))
		finish()
	}

	override fun onDestroy() {
		web.destroy()
		super.onDestroy()
	}
}
