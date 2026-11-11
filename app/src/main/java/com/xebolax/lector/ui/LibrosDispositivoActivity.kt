package com.xebolax.lector.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xebolax.lector.R
import com.xebolax.lector.biblioteca.BuscadorDispositivo
import com.xebolax.lector.biblioteca.Importador
import com.xebolax.lector.databinding.ActivityListaBinding
import com.xebolax.lector.databinding.ItemLibroBinding
import com.xebolax.lector.datos.BaseDatos
import com.xebolax.lector.lectura.Abiertos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Libros del dispositivo: todos los EPUB y PDF (o todos los libros y
 * documentos, o también el audio) que hay en el móvil, estén donde estén,
 * para abrirlos o añadirlos a la biblioteca de una vez. Es opcional: solo
 * al entrar aquí se pide el permiso de acceso a los archivos, explicando
 * para qué es; sin él, la app funciona igual con el selector de Android.
 */
class LibrosDispositivoActivity : AppCompatActivity() {

	private lateinit var b: ActivityListaBinding
	private var todos: List<BuscadorDispositivo.Encontrado> = emptyList()
	private var visibles: List<BuscadorDispositivo.Encontrado> = emptyList()
	/** Rutas de lo que ya está en la biblioteca (o en Compartidos). */
	private var enBiblioteca: Map<String, Long> = emptyMap()
	private var buscado = false
	private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

	private var alcance: BuscadorDispositivo.Alcance
		get() = runCatching { BuscadorDispositivo.Alcance.valueOf(prefs.getString("disp_alcance", "EPUB_PDF")!!) }.getOrDefault(BuscadorDispositivo.Alcance.EPUB_PDF)
		set(v) = prefs.edit().putString("disp_alcance", v.name).apply()

	/** Android 10 y anteriores: el permiso de leer la memoria. */
	private val permisoLeer = registerForActivityResult(ActivityResultContracts.RequestPermission()) { si ->
		if (si) buscar() else sinPermiso()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		b = ActivityListaBinding.inflate(layoutInflater)
		setContentView(b.root)
		configurarBarra(b.barra, getString(R.string.disp_titulo))
		b.filtroContenedor.hint = getString(R.string.disp_filtrar)
		b.filtro.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
			override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
			override fun afterTextChanged(s: Editable?) = filtrar()
		})
		b.recycler.layoutManager = LinearLayoutManager(this)
		b.recycler.sinFocoPropio()
		b.recycler.sinAnimarCambios()
		b.recycler.adapter = Adaptador().also { it.setHasStableIds(true) }
		ViewCompat.setAccessibilityPaneTitle(b.recycler, getString(R.string.disp_titulo))
		if (BuscadorDispositivo.permitido(this)) buscar() else pedirPermiso()
	}

	override fun onResume() {
		super.onResume()
		// Volviendo de los ajustes de Android con el permiso ya dado
		if (!buscado && BuscadorDispositivo.permitido(this) && todos.isEmpty() && b.estado.text.toString() == getString(R.string.disp_sin_permiso)) buscar()
	}

	private fun sinPermiso() {
		b.estado.text = getString(R.string.disp_sin_permiso)
	}

	/** Explica para qué es el permiso y lleva a darlo. */
	private fun pedirPermiso() {
		sinPermiso()
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.disp_titulo)
			.setMessage(R.string.disp_permiso_explicacion)
			.setPositiveButton(R.string.disp_dar_permiso) { _, _ ->
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
					val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
					runCatching { startActivity(intent) }.onFailure {
						runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }.onFailure { avisar(getString(R.string.no_disponible)) }
					}
				} else permisoLeer.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
			}
			.setNegativeButton(R.string.cancelar, null)
			.show()
	}

	private fun buscar() {
		buscado = true
		val avance = Avance()
		lifecycleScope.launch {
			val r = try {
				conEspera(getString(R.string.disp_titulo), getString(R.string.disp_buscando), avance) {
					BuscadorDispositivo.buscar(this@LibrosDispositivoActivity, alcance) { n -> avance.decir(resources.getQuantityString(R.plurals.disp_encontrados, n, n)) }
				}
			} catch (e: kotlinx.coroutines.CancellationException) {
				// Cancelado: se queda lo que había
				if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.CREATED)) throw e
				b.estado.text = getString(R.string.disp_cancelado)
				return@launch
			}
			todos = r
			actualizarBiblioteca()
			filtrar()
		}
	}

	private suspend fun actualizarBiblioteca() {
		enBiblioteca = withContext(Dispatchers.IO) {
			val todos = BaseDatos.obtener(this@LibrosDispositivoActivity).todosConCompartidos()
			compartidos = todos.filter { it.compartido }.map { it.id }.toSet()
			todos.mapNotNull { l -> Uri.parse(l.uri).takeIf { it.scheme == "file" }?.path?.let { it to l.id } }.toMap()
		}
	}

	private fun ya(e: BuscadorDispositivo.Encontrado) = enBiblioteca[e.archivo.path]
	private var compartidos: Set<Long> = emptySet()
	private fun enCompartidos(e: BuscadorDispositivo.Encontrado) = ya(e)?.let { it in compartidos } == true

	private fun filtrar() {
		val t = b.filtro.text?.toString()?.trim().orEmpty()
		visibles = if (t.isBlank()) todos else todos.filter { it.nombre.contains(t, ignoreCase = true) || it.archivo.parent.orEmpty().contains(t, ignoreCase = true) }
		@Suppress("NotifyDataSetChanged")
		b.recycler.adapter?.notifyDataSetChanged()
		val n = visibles.size
		val estado = when {
			todos.isEmpty() -> getString(R.string.disp_nada, getString(alcance.nombre))
			else -> resources.getQuantityString(R.plurals.disp_encontrados, n, n) + ", " + getString(alcance.nombre)
		}
		if (b.estado.text.toString() != estado) b.estado.text = estado
	}

	/** Añade a la biblioteca (a una carpeta) y devuelve su id; si ya estaba, el que tenía. */
	private suspend fun anadir(lista: List<BuscadorDispositivo.Encontrado>, carpeta: Long?): List<Long> {
		val r = Importador.anadirArchivos(this, lista.map { Uri.fromFile(it.archivo) }, carpeta)
		TareasInicio.completar(this, r.anadidos)
		actualizarBiblioteca()
		filtrar()
		return r.anadidos
	}

	private fun abrir(e: BuscadorDispositivo.Encontrado) {
		ya(e)?.let { return Abiertos.abrirPantalla(this, it) }
		lifecycleScope.launch {
			val id = anadir(listOf(e), null).firstOrNull() ?: ya(e) ?: return@launch
			Abiertos.abrirPantalla(this@LibrosDispositivoActivity, id)
		}
	}

	private fun acciones(e: BuscadorDispositivo.Encontrado): List<Accion> {
		val id = ya(e)
		val r = mutableListOf<Accion>()
		r += if (id != null) getString(R.string.disp_ya_abrir) to { Abiertos.abrirPantalla(this, id) }
		else getString(R.string.disp_abrir_anadir) to { abrir(e) }
		if (id == null) {
			r += getString(R.string.disp_anadir) to { lifecycleScope.launch { anadir(listOf(e), null); avisar(getString(R.string.disp_anadido, e.nombre)) } }
			r += getString(R.string.disp_anadir_carpeta) to {
				AccionesLibro.elegirCarpeta(this, getString(R.string.mover_a_carpeta), null) { carpeta ->
					lifecycleScope.launch { anadir(listOf(e), carpeta); avisar(getString(R.string.disp_anadido, e.nombre)) }
				}
			}
		}
		r += getString(R.string.informacion) to {
			mensaje(getString(R.string.informacion), listOf(
				getString(R.string.info_archivo, e.nombre),
				getString(R.string.disp_ruta, e.archivo.parent.orEmpty()),
				getString(R.string.info_formato, e.formato.nombre),
				getString(R.string.info_tamano, tamanoLegible(e.archivo.length())),
			).joinToString("\n"))
		}
		return r
	}

	// --- Menú ------------------------------------------------------------------------------------

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menu.add(Menu.NONE, 1, Menu.NONE, R.string.disp_anadir_todos).setIcon(R.drawable.ic_anadir).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
		menu.add(Menu.NONE, 2, Menu.NONE, R.string.disp_que_buscar)
		menu.add(Menu.NONE, 3, Menu.NONE, R.string.disp_buscar_otra_vez)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			1 -> {
				val faltan = visibles.filter { ya(it) == null }
				if (faltan.isEmpty()) return avisar(getString(R.string.disp_todos_ya)).let { true }
				confirmar(resources.getQuantityString(R.plurals.disp_confirmar_todos, faltan.size, faltan.size), getString(R.string.disp_anadir)) {
					lifecycleScope.launch {
						val ids = conEspera(getString(R.string.disp_anadir_todos), getString(R.string.anadiendo_libros)) { anadir(faltan, null) }
						avisar(resources.getQuantityString(R.plurals.libros_anadidos, ids.size, ids.size), mostrar = true)
					}
				}
			}
			2 -> {
				val opciones = BuscadorDispositivo.Alcance.entries
				elegirUno(getString(R.string.disp_que_buscar), opciones.map { getString(it.nombre) }, opciones.indexOf(alcance)) { i ->
					alcance = opciones[i]
					if (BuscadorDispositivo.permitido(this)) buscar() else pedirPermiso()
				}
			}
			3 -> if (BuscadorDispositivo.permitido(this)) buscar() else pedirPermiso()
			else -> return super.onOptionsItemSelected(item)
		}
		return true
	}

	// --- Lista -----------------------------------------------------------------------------------

	private class Vista(val b: ItemLibroBinding) : RecyclerView.ViewHolder(b.root)

	private inner class Adaptador : RecyclerView.Adapter<Vista>() {
		override fun getItemCount() = visibles.size
		override fun getItemId(posicion: Int) = visibles[posicion].archivo.path.hashCode().toLong()

		override fun onCreateViewHolder(padre: ViewGroup, tipo: Int) = Vista(ItemLibroBinding.inflate(LayoutInflater.from(padre.context), padre, false))

		override fun onBindViewHolder(v: Vista, posicion: Int) {
			val e = visibles[posicion]
			val b = v.b
			val titulo = e.nombre.substringBeforeLast('.')
			// «En tu biblioteca» primero (el estado), después el formato, la carpeta y el tamaño
			val carpeta = e.archivo.parentFile?.name.orEmpty()
			val partes = listOfNotNull(
				(if (enCompartidos(e)) getString(R.string.disp_en_compartidos) else getString(R.string.disp_en_biblioteca)).takeIf { ya(e) != null },
				e.formato.nombre,
				carpeta.takeIf { it.isNotBlank() }?.let { getString(R.string.en_carpeta, it) },
				tamanoLegible(e.archivo.length()),
			)
			b.icono.setImageResource(if (e.formato.audio) R.drawable.ic_voz else if (e.formato == com.xebolax.lector.formatos.Formato.EPUB) R.drawable.ic_libro else R.drawable.ic_documento)
			b.titulo.text = titulo
			b.detalle.text = partes.joinToString(" · ")
			b.progreso.visibility = android.view.View.GONE
			b.root.contentDescription = (listOfNotNull(partes.firstOrNull().takeIf { ya(e) != null }, titulo) + partes.drop(if (ya(e) != null) 1 else 0)).joinToString(", ")
			b.root.setOnClickListener { abrir(e) }
			val lista = acciones(e)
			b.root.accionesAccesibles(lista)
			b.root.setOnLongClickListener {
				elegir(titulo, lista)
				true
			}
		}
	}
}
