import java.util.Properties

plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
}

// Servidor de actualizaciones: ahí van version.json y LectorUniversal_v<versión>.apk.
// Se puede cambiar en Ajustes; para compilar con otro: gradle assembleRelease -PurlActualizaciones=https://...
val urlActualizaciones = (project.findProperty("urlActualizaciones") as String?) ?: "https://xebolax.synology.me/LectorUniversal"
// Repositorio de GitHub cuyas versiones publicadas (releases) también puede usar el actualizador.
// Para compilar con otro: gradle assembleRelease -PrepoGithub=usuario/repositorio
val repoGithub = (project.findProperty("repoGithub") as String?) ?: "hxebolax/LectorUniversal"

// Firma propia guardada en la carpeta "firma" (la crea compilar.bat).
val propiedadesFirma = Properties().apply {
	val archivo = rootProject.file("firma/firma.properties")
	if (archivo.exists()) archivo.inputStream().use { load(it) }
}

android {
	namespace = "com.xebolax.lector"
	compileSdk = 35

	defaultConfig {
		applicationId = "com.xebolax.lector"
		minSdk = 24
		targetSdk = 35
		versionCode = 12
		versionName = "1.0.11"

		buildConfigField("String", "URL_ACTUALIZACIONES", "\"$urlActualizaciones\"")
		buildConfigField("String", "REPO_GITHUB", "\"$repoGithub\"")
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	// Idiomas de la interfaz (Android 13+ los ofrece en Ajustes del sistema > Idioma de la app)
	androidResources {
		generateLocaleConfig = true
	}

	signingConfigs {
		if (propiedadesFirma.isNotEmpty()) {
			create("propia") {
				storeFile = rootProject.file(propiedadesFirma.getProperty("archivo"))
				storePassword = propiedadesFirma.getProperty("clave")
				keyAlias = propiedadesFirma.getProperty("alias")
				keyPassword = propiedadesFirma.getProperty("clave")
			}
		}
	}

	buildTypes {
		debug {
			// Versión de pruebas: se instala junto a la normal
			applicationIdSuffix = ".pruebas"
		}
		release {
			isMinifyEnabled = false
			// Solo procesadores de móviles (las librerías nativas para emuladores x86 pesan unos 25 MB más)
			ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
			signingConfig = signingConfigs.findByName("propia") ?: signingConfigs.getByName("debug")
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}


	buildFeatures {
		viewBinding = true
		buildConfig = true
	}

	packaging {
		resources.excludes += listOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES")
	}

	testOptions {
		unitTests.isReturnDefaultValues = true
	}
}

dependencies {
	implementation("androidx.core:core-ktx:1.15.0")
	implementation("androidx.appcompat:appcompat:1.7.0")
	implementation("androidx.activity:activity-ktx:1.9.3")
	implementation("androidx.fragment:fragment-ktx:1.8.5")
	implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
	implementation("androidx.lifecycle:lifecycle-service:2.8.7")
	implementation("androidx.recyclerview:recyclerview:1.3.2")
	implementation("androidx.preference:preference-ktx:1.2.1")
	implementation("androidx.drawerlayout:drawerlayout:1.2.0")
	implementation("androidx.documentfile:documentfile:1.0.1")
	// Noticias y podcasts: pestañas y actualización en segundo plano
	implementation("androidx.viewpager2:viewpager2:1.1.0")
	implementation("androidx.work:work-runtime-ktx:2.9.1")
	implementation("com.google.android.material:material:1.12.0")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

	// Audiolibros y DAISY: reproductor con controles en la notificación y auriculares
	implementation("androidx.media3:media3-exoplayer:1.5.1")
	implementation("androidx.media3:media3-session:1.5.1")
	// Radio (y podcasts) que emiten en HLS (.m3u8)
	implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
	// Decodificador FFmpeg de reserva (audio MP2 de algunas radios en HLS, AC-3, E-AC-3…): el que publica Jellyfin para Media3
	implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

	// HTML, XHTML (EPUB, MOBI, DAISY) y XML
	implementation("org.jsoup:jsoup:1.18.3")
	// Markdown
	implementation("org.commonmark:commonmark:0.24.0")
	implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
	// Texto de los PDF
	implementation("com.tom-roush:pdfbox-android:2.0.27.0")

	// IA del propio móvil (Gemini Nano, donde lo hay): describir imágenes, resumir y responder
	implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
	// IA descargable dentro de la app (Gemma 4, como en la app de Windows): sin internet en cualquier móvil potente
	implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
	// Reconocimiento de texto (PDF escaneados, fotos) sin internet, dentro de la app (funciona siempre, sin esperar a Google Play)
	implementation("com.google.mlkit:text-recognition:16.0.1")
	// Escáner de documentos con la cámara (servicios de Google Play)
	implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
	// Escáner con guía de voz, sin servicios de Google: cámara propia
	implementation("androidx.camera:camera-core:1.4.1")
	implementation("androidx.camera:camera-camera2:1.4.1")
	implementation("androidx.camera:camera-lifecycle:1.4.1")
	implementation("androidx.camera:camera-view:1.4.1")
	// Idioma de cada párrafo, sin internet (dentro de la app)
	implementation("com.google.mlkit:language-id:17.0.6")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

	testImplementation("junit:junit:4.13.2")
	// Pruebas en el emulador o el móvil: abren los libros de muestra de cada formato
	androidTestImplementation("androidx.test:runner:1.6.2")
	androidTestImplementation("androidx.test:core:1.6.1")
	androidTestImplementation("androidx.test.ext:junit:1.2.1")
	// Comprobaciones de accesibilidad (las de Accessibility Scanner) en el recorrido con TalkBack
	androidTestImplementation("com.google.android.apps.common.testing.accessibility.framework:accessibility-test-framework:4.1.1")
}

kotlin {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
	}
}
