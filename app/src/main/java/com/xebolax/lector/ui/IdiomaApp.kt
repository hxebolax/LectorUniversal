package com.xebolax.lector.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate

/**
 * Un contexto con el idioma elegido en la app (o el del sistema si se sigue al
 * sistema), para textos que salen fuera de las pantallas, como los accesos
 * directos del icono: el de la aplicación puede seguir aún con el idioma anterior.
 */
fun contextoConIdiomaDeLaApp(c: Context): Context {
	val app = c.applicationContext
	val elegidos = AppCompatDelegate.getApplicationLocales()
	val config = Configuration(app.resources.configuration)
	config.setLocales(if (elegidos.isEmpty()) Resources.getSystem().configuration.locales else LocaleList.forLanguageTags(elegidos.toLanguageTags()))
	return app.createConfigurationContext(config)
}
