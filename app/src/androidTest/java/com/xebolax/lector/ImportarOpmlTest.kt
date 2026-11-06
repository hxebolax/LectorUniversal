package com.xebolax.lector

import android.app.UiAutomation
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xebolax.lector.rss.RssLector
import com.xebolax.lector.ui.ImportarOpmlActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Importar un OPML mezclado (noticias y podcasts) revisándolo antes, con TalkBack; y los atajos para descubrir fuentes. */
@RunWith(AndroidJUnit4::class)
class ImportarOpmlTest {

	private val instrumentacion = InstrumentationRegistry.getInstrumentation()
	private val app = instrumentacion.targetContext

	@Test
	fun atajosDeFuentes() {
		assertEquals("https://www.reddit.com/r/blind/.rss", RssLector.atajo("https://www.reddit.com/r/blind"))
		assertEquals("https://mastodon.social/@usuario.rss", RssLector.atajo("@usuario@mastodon.social"))
		assertEquals("https://www.youtube.com/feeds/videos.xml?channel_id=UC1234567890abcdefghijkl", RssLector.atajo("https://www.youtube.com/channel/UC1234567890abcdefghijkl"))
		assertEquals("https://bsky.app/profile/alguien.bsky.social/rss", RssLector.atajo("https://bsky.app/profile/alguien.bsky.social"))
		assertEquals(null, RssLector.atajo("https://www.elpais.com"))
	}

	@Test
	fun revisarAntesDeImportar() {
		val opml = """<?xml version="1.0"?><opml version="2.0"><head><title>Mis fuentes</title></head><body>
			<outline text="Tecnología"><outline type="rss" text="Blog de prueba" xmlUrl="https://noticias.invalid/feed"/></outline>
			<outline text="Charlas"><outline type="rss" text="Un podcast de prueba" xmlUrl="https://feeds.libsyn.invalid/podcast"/></outline>
			<outline type="rss" text="Otra fuente" xmlUrl="https://otra.invalid/rss"/>
			</body></opml>"""
		val ui = instrumentacion.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
		instrumentacion.runOnMainSync { ImportarOpmlActivity.abrir(app.applicationContext.let { c ->
			object : android.content.ContextWrapper(c) {
				override fun startActivity(i: android.content.Intent) = super.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
			}
		}, opml, podcasts = false) }
		// Se comprueban (no hay red para .invalid: se decide por pistas)
		Thread.sleep(9000)
		val r = ui.rootInActiveWindow
		val filas = mutableListOf<AccessibilityNodeInfo>()
		fun recoger(n: AccessibilityNodeInfo) {
			if (n.isCheckable && n.isClickable) filas += n
			for (i in 0 until n.childCount) n.getChild(i)?.let { recoger(it) }
		}
		r?.let { recoger(it) }
		for (f in filas) Log.i("ImportarOpml", "${f.contentDescription} | marcada=${f.isChecked} | acciones: ${f.actionList.mapNotNull { it.label }.joinToString()}")
		assertEquals(3, filas.size)
		// La del servicio de podcasts va a Podcasts; las demás, a Noticias, cada una en su carpeta
		val podcast = filas.first { it.contentDescription.contains("Un podcast de prueba") }
		assertEquals(true, podcast.contentDescription.contains(app.getString(R.string.podcasts)))
		val blog = filas.first { it.contentDescription.contains("Blog de prueba") }
		assertEquals(true, blog.contentDescription.contains("Tecnología"))
		// Doble toque: deja de importarse
		blog.performAction(AccessibilityNodeInfo.ACTION_CLICK)
		Thread.sleep(1200)
		blog.refresh()
		Log.i("ImportarOpml", "Tras el doble toque: ${blog.contentDescription} marcada=${blog.isChecked}")
		assertEquals(false, blog.isChecked)
	}
}
