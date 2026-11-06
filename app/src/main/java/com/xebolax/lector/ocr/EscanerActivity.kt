package com.xebolax.lector.ocr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.xebolax.lector.R
import com.xebolax.lector.ui.avisar
import com.xebolax.lector.ui.configurarBarra
import com.xebolax.lector.ui.lectorActivo
import com.xebolax.lector.ui.mensaje
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Escanear hojas con la cámara y guía de voz, sin servicios de Google: mira
 * varias veces por segundo lo que ve la cámara y dice si el texto se sale
 * por un lado, si hay que acercar o alejar el móvil o si no se ve texto.
 * Cuando la hoja está entera y quieta, la fotografía sola (o con «Capturar
 * ahora»), reconoce su texto (sin internet) y espera la siguiente hoja.
 * «Terminar» devuelve las páginas reconocidas.
 */
class EscanerActivity : AppCompatActivity() {

	companion object {
		/** Las páginas reconocidas, para quien abrió el escáner (se pasan en memoria). */
		var paginas: List<String> = emptyList()
	}

	private enum class Guia(val texto: Int) {
		SIN_TEXTO(R.string.esc_sin_texto),
		IZQUIERDA(R.string.esc_izquierda),
		DERECHA(R.string.esc_derecha),
		ARRIBA(R.string.esc_arriba),
		ABAJO(R.string.esc_abajo),
		ALEJAR(R.string.esc_alejar),
		ACERCAR(R.string.esc_acercar),
		BIEN(R.string.esc_bien),
	}

	private lateinit var vista: PreviewView
	private lateinit var guia: TextView
	private lateinit var terminar: MaterialButton
	private lateinit var linterna: MaterialButton
	private var camara: Camera? = null
	private var captura: ImageCapture? = null
	private val hilo = Executors.newSingleThreadExecutor()
	private val reconocedor by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
	private var voz: TextToSpeech? = null
	private val pitido by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull() }

	private val reconocidas = mutableListOf<String>()
	private var ultimaGuia: Guia? = null
	private var ultimoAviso = 0L
	private var bienSeguidos = 0
	private var capturando = false
	/** Palabras de la última hoja capturada: no se vuelve a capturar hasta que la hoja cambie. */
	private var palabrasUltima: Set<String> = emptySet()
	private var analizando = false

	private val permiso = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
		if (ok) arrancar() else mensaje(getString(R.string.escanear_documento), getString(R.string.esc_sin_permiso)) { finish() }
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		paginas = emptyList()
		val dp = resources.displayMetrics.density
		val raiz = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			fitsSystemWindows = true
		}
		val barra = MaterialToolbar(this)
		raiz.addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		vista = PreviewView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
		raiz.addView(FrameLayout(this).apply { addView(vista) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
		// La guía: con TalkBack se lee sola al cambiar (región viva); sin TalkBack, se dice con la voz del sistema
		guia = TextView(this).apply {
			setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
			gravity = Gravity.CENTER
			setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
			text = getString(R.string.esc_preparando)
			ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
		}
		raiz.addView(guia, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		val botones = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (8 * dp).toInt())
		}
		fun boton(texto: Int, accion: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
			setText(texto)
			setOnClickListener { accion() }
			botones.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), 0) })
		}
		boton(R.string.esc_capturar) { capturar(manual = true) }
		linterna = boton(R.string.esc_linterna) { alternarLinterna() }
		terminar = boton(R.string.esc_terminar) { terminar() }
		raiz.addView(botones, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		setContentView(raiz)
		configurarBarra(barra, getString(R.string.esc_titulo))
		ponerTerminar()
		if (!lectorActivo()) voz = TextToSpeech(this) { estado -> if (estado == TextToSpeech.SUCCESS) voz?.language = Locale.getDefault() }
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) arrancar()
		else permiso.launch(Manifest.permission.CAMERA)
	}

	private fun arrancar() {
		val futuro = ProcessCameraProvider.getInstance(this)
		futuro.addListener({
			val proveedor = futuro.get()
			val vistaPrevia = Preview.Builder().build().also { it.surfaceProvider = vista.surfaceProvider }
			captura = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
			val analisis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
			analisis.setAnalyzer(hilo) { imagen -> analizar(imagen) }
			runCatching {
				proveedor.unbindAll()
				camara = proveedor.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, vistaPrevia, captura, analisis)
				decir(getString(R.string.esc_empieza))
			}.onFailure { mensaje(getString(R.string.escanear_documento), getString(R.string.esc_sin_camara, it.message.orEmpty())) { finish() } }
		}, ContextCompat.getMainExecutor(this))
	}

	/** Mira dónde está el texto en la imagen y decide qué decir (o captura si está bien). */
	@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
	private fun analizar(imagen: ImageProxy) {
		val media = imagen.image
		if (media == null || analizando || capturando) {
			imagen.close()
			return
		}
		analizando = true
		val giro = imagen.imageInfo.rotationDegrees
		val (ancho, alto) = if (giro == 90 || giro == 270) imagen.height to imagen.width else imagen.width to imagen.height
		reconocedor.process(InputImage.fromMediaImage(media, giro))
			.addOnSuccessListener { texto ->
				val cajas = texto.textBlocks.mapNotNull { it.boundingBox }
				val letras = texto.text.count { it.isLetterOrDigit() }
				val g = if (cajas.isEmpty() || letras < 12) Guia.SIN_TEXTO else guiaPara(union(cajas), ancho, alto)
				val palabras = palabras(texto.text)
				runOnUiThread { alGuiar(g, palabras) }
			}
			.addOnCompleteListener {
				analizando = false
				imagen.close()
			}
	}

	private fun union(cajas: List<Rect>) = Rect(cajas[0]).apply { cajas.drop(1).forEach { union(it) } }

	private fun guiaPara(u: Rect, ancho: Int, alto: Int): Guia {
		val mx = ancho * 0.03f
		val my = alto * 0.03f
		val izq = u.left < mx
		val der = u.right > ancho - mx
		val arr = u.top < my
		val aba = u.bottom > alto - my
		return when {
			(izq && der) || (arr && aba) -> Guia.ALEJAR
			izq -> Guia.IZQUIERDA
			der -> Guia.DERECHA
			arr -> Guia.ARRIBA
			aba -> Guia.ABAJO
			u.width().toFloat() * u.height() / (ancho.toFloat() * alto) < 0.18f -> Guia.ACERCAR
			else -> Guia.BIEN
		}
	}

	private fun palabras(t: String) = Regex("""\p{L}{4,}""").findAll(t.lowercase()).map { it.value }.toSet()

	private fun parecido(a: Set<String>, b: Set<String>): Float = if (a.isEmpty() || b.isEmpty()) 0f else a.intersect(b).size.toFloat() / a.union(b).size

	private fun alGuiar(g: Guia, palabras: Set<String>) {
		if (capturando) return
		// La misma hoja que ya se capturó: se dice y no se repite la foto
		val repetida = g == Guia.BIEN && parecido(palabras, palabrasUltima) > 0.6f
		bienSeguidos = if (g == Guia.BIEN && !repetida) bienSeguidos + 1 else 0
		if (bienSeguidos >= 3) {
			bienSeguidos = 0
			capturar(manual = false)
			return
		}
		val ahora = SystemClock.elapsedRealtime()
		val texto = if (repetida) getString(R.string.esc_ya_capturada) else getString(g.texto)
		// Sin repetir lo mismo a cada momento: al cambiar, o cada pocos segundos
		if (g != ultimaGuia || ahora - ultimoAviso > 4000) {
			ultimaGuia = g
			ultimoAviso = ahora
			decir(texto)
		}
	}

	private fun decir(texto: String) {
		guia.text = texto
		voz?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "guia")
	}

	private fun capturar(manual: Boolean) {
		val c = captura ?: return
		if (capturando) return
		capturando = true
		pitido?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
		decir(getString(R.string.esc_capturando))
		c.takePicture(hilo, object : ImageCapture.OnImageCapturedCallback() {
			override fun onCaptureSuccess(imagen: ImageProxy) {
				val mapa = runCatching { girar(imagen.toBitmap(), imagen.imageInfo.rotationDegrees) }.getOrNull()
				imagen.close()
				lifecycleScope.launch {
					val texto = mapa?.let { runCatching { Ocr.reconocer(this@EscanerActivity, it) }.getOrNull() }.orEmpty().trim()
					if (texto.count { it.isLetterOrDigit() } < 12) {
						decir(getString(R.string.esc_no_entendido))
					} else {
						reconocidas += texto
						palabrasUltima = palabras(texto)
						pitido?.startTone(ToneGenerator.TONE_PROP_ACK, 200)
						decir(getString(R.string.esc_capturada, reconocidas.size))
						ponerTerminar()
					}
					ultimaGuia = null
					capturando = false
				}
			}

			override fun onError(e: ImageCaptureException) {
				runOnUiThread {
					decir(getString(R.string.esc_error_foto))
					capturando = false
				}
			}
		})
		if (manual) avisar(getString(R.string.esc_capturando), mostrar = false)
	}

	private fun girar(b: Bitmap, grados: Int): Bitmap =
		if (grados == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(grados.toFloat()) }, true)

	private fun alternarLinterna() {
		val c = camara ?: return
		if (!c.cameraInfo.hasFlashUnit()) return avisar(getString(R.string.esc_sin_linterna))
		val encendida = c.cameraInfo.torchState.value == androidx.camera.core.TorchState.ON
		c.cameraControl.enableTorch(!encendida)
		linterna.setText(if (encendida) R.string.esc_linterna else R.string.esc_apagar_linterna)
		avisar(getString(if (encendida) R.string.esc_linterna_apagada else R.string.esc_linterna_encendida))
	}

	private fun ponerTerminar() {
		terminar.text = if (reconocidas.isEmpty()) getString(R.string.esc_terminar)
		else resources.getQuantityString(R.plurals.esc_terminar_n, reconocidas.size, reconocidas.size)
	}

	private fun terminar() {
		paginas = reconocidas.toList()
		setResult(if (paginas.isEmpty()) RESULT_CANCELED else RESULT_OK)
		finish()
	}

	override fun onDestroy() {
		voz?.shutdown()
		pitido?.release()
		reconocedor.close()
		hilo.shutdown()
		super.onDestroy()
	}
}
