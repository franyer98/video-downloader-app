package com.franyer.descargavideos

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    companion object {
        /** Cuántas descargas corren al mismo tiempo; las demás esperan en cola. */
        const val MAX_SIMULTANEAS = 3
    }

    private enum class Estado { EN_COLA, DESCARGANDO, LISTO, ERROR, CANCELADO }

    private inner class Tarea(val id: String, val url: String, val calidad: Int, val vista: View) {
        val tvTitulo: TextView = vista.findViewById(R.id.tvTitulo)
        val tvEstado: TextView = vista.findViewById(R.id.tvEstado)
        val progreso: LinearProgressIndicator = vista.findViewById(R.id.progreso)
        val btnAccion: MaterialButton = vista.findViewById(R.id.btnAccion)
        var estado = Estado.EN_COLA
        var job: Job? = null
    }

    private lateinit var etUrl: TextInputEditText
    private lateinit var rgCalidad: RadioGroup
    private lateinit var btnDescargar: MaterialButton
    private lateinit var btnActualizar: MaterialButton
    private lateinit var contenedor: LinearLayout
    private lateinit var tvResumen: TextView
    private lateinit var tvVacio: TextView

    private val semaforo = Semaphore(MAX_SIMULTANEAS)
    private val contador = AtomicInteger(0)
    private val tareas = mutableListOf<Tarea>()
    private val motorListo = CompletableDeferred<Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        rgCalidad = findViewById(R.id.rgCalidad)
        btnDescargar = findViewById(R.id.btnDescargar)
        btnActualizar = findViewById(R.id.btnActualizar)
        contenedor = findViewById(R.id.contenedor)
        tvResumen = findViewById(R.id.tvResumen)
        tvVacio = findViewById(R.id.tvVacio)

        findViewById<MaterialButton>(R.id.btnPegar).setOnClickListener { pegar() }
        btnDescargar.setOnClickListener { agregarDesdeCampo() }
        btnActualizar.setOnClickListener { actualizarMotor() }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
        }

        inicializar()
        manejarCompartir(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        manejarCompartir(intent)
    }

    private fun inicializar() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(applicationContext)
                    FFmpeg.getInstance().init(applicationContext)
                    Aria2c.getInstance().init(applicationContext)
                }
                btnDescargar.isEnabled = true
                btnDescargar.text = "Descargar"
                motorListo.complete(true)
            } catch (e: Exception) {
                btnDescargar.text = "Error al iniciar"
                Toast.makeText(this@MainActivity, "No se pudo iniciar: ${e.message}", Toast.LENGTH_LONG).show()
                motorListo.complete(false)
            }
        }
    }

    /** Un enlace compartido desde otra app empieza a descargarse de una vez. */
    private fun manejarCompartir(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val texto = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val url = extraerUrl(texto) ?: return
            agregarDescarga(url)
            Toast.makeText(this, "Agregado a descargas", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extraerUrl(texto: String): String? =
        Regex("https?://\\S+").find(texto)?.value

    private fun pegar() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val texto = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        val url = texto?.let { extraerUrl(it) }
        if (url != null) etUrl.setText(url)
        else Toast.makeText(this, "No hay un enlace copiado", Toast.LENGTH_SHORT).show()
    }

    private fun agregarDesdeCampo() {
        val url = etUrl.text?.toString()?.let { extraerUrl(it) }
        if (url == null) {
            Toast.makeText(this, "Escribe o pega un enlace válido", Toast.LENGTH_SHORT).show()
            return
        }
        agregarDescarga(url)
        etUrl.setText("")
    }

    private fun agregarDescarga(url: String) {
        val vista = layoutInflater.inflate(R.layout.item_descarga, contenedor, false)
        val tarea = Tarea("descarga-${contador.incrementAndGet()}", url, rgCalidad.checkedRadioButtonId, vista)
        tarea.tvTitulo.text = url
        tarea.tvEstado.text = "En cola"
        tarea.btnAccion.setOnClickListener { accion(tarea) }

        contenedor.addView(vista, 0)
        tareas.add(tarea)
        actualizarResumen()

        tarea.job = lifecycleScope.launch {
            if (!motorListo.await()) {
                terminar(tarea, Estado.ERROR, "El motor no se pudo iniciar")
                return@launch
            }
            semaforo.withPermit { ejecutar(tarea) }
        }
    }

    private suspend fun ejecutar(tarea: Tarea) {
        tarea.estado = Estado.DESCARGANDO
        tarea.tvEstado.text = "Analizando enlace..."
        actualizarResumen()

        val carpetaTemp = File(cacheDir, tarea.id).apply { deleteRecursively(); mkdirs() }
        val req = YoutubeDLRequest(tarea.url).apply {
            addOption("-o", carpetaTemp.absolutePath + "/%(title).80s.%(ext)s")
            addOption("--no-mtime")
            addOption("--no-playlist")
            addOption("--restrict-filenames")
            addOption("--newline")
            // aria2c: cada descarga usa varias conexiones a la vez
            addOption("--downloader", "libaria2c.so")
            addOption("--downloader-args", "aria2c:\"-x 8 -s 8 -k 1M\"")
            when (tarea.calidad) {
                R.id.rbAudio -> {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                }
                R.id.rb720 -> {
                    addOption("-f", "bv*[height<=720]+ba/b[height<=720]/b")
                    addOption("--merge-output-format", "mp4")
                }
                R.id.rb480 -> {
                    addOption("-f", "bv*[height<=480]+ba/b[height<=480]/b")
                    addOption("--merge-output-format", "mp4")
                }
                else -> {
                    addOption("-f", "bv*+ba/b")
                    addOption("--merge-output-format", "mp4")
                }
            }
        }

        try {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(req, tarea.id) { prog, eta, linea ->
                    runOnUiThread {
                        if (tarea.estado != Estado.DESCARGANDO) return@runOnUiThread
                        Regex("Destination: .*/(.+)$").find(linea)?.groupValues?.get(1)?.let {
                            tarea.tvTitulo.text = it
                        }
                        if (prog > 0) {
                            tarea.progreso.isIndeterminate = false
                            tarea.progreso.setProgressCompat(prog.toInt(), true)
                            tarea.tvEstado.text = "Descargando ${prog.toInt()}%" +
                                if (eta > 0) " · faltan ${formatoTiempo(eta)}" else ""
                        }
                    }
                }
            }
            if (tarea.estado == Estado.CANCELADO) return
            tarea.tvEstado.text = "Guardando..."
            val guardados = withContext(Dispatchers.IO) { moverADescargas(carpetaTemp) }
            if (guardados.isNotEmpty()) {
                tarea.tvTitulo.text = guardados.first()
                terminar(tarea, Estado.LISTO, "✅ Guardado en Descargas/DescargaVideos")
            } else {
                terminar(tarea, Estado.ERROR, "❌ No se encontró el archivo descargado")
            }
        } catch (e: CancellationException) {
            YoutubeDL.getInstance().destroyProcessById(tarea.id)
            throw e
        } catch (e: Exception) {
            if (tarea.estado == Estado.CANCELADO) return
            terminar(tarea, Estado.ERROR, mensajeError(e.message.orEmpty()))
        } finally {
            withContext(Dispatchers.IO) { carpetaTemp.deleteRecursively() }
        }
    }

    private fun accion(tarea: Tarea) {
        when (tarea.estado) {
            Estado.EN_COLA -> {
                tarea.job?.cancel()
                terminar(tarea, Estado.CANCELADO, "Cancelado")
            }
            Estado.DESCARGANDO -> {
                tarea.estado = Estado.CANCELADO
                YoutubeDL.getInstance().destroyProcessById(tarea.id)
                terminar(tarea, Estado.CANCELADO, "Cancelado")
            }
            Estado.ERROR, Estado.CANCELADO -> {
                // Reintentar: se vuelve a poner en cola con la misma calidad
                quitar(tarea)
                rgCalidad.check(tarea.calidad)
                agregarDescarga(tarea.url)
            }
            Estado.LISTO -> quitar(tarea)
        }
    }

    private fun terminar(tarea: Tarea, estado: Estado, mensaje: String) {
        tarea.estado = estado
        tarea.tvEstado.text = mensaje
        tarea.progreso.visibility = View.GONE
        tarea.btnAccion.text = when (estado) {
            Estado.ERROR, Estado.CANCELADO -> "Reintentar"
            else -> "Quitar"
        }
        actualizarResumen()
    }

    private fun quitar(tarea: Tarea) {
        contenedor.removeView(tarea.vista)
        tareas.remove(tarea)
        actualizarResumen()
    }

    private fun actualizarResumen() {
        val activas = tareas.count { it.estado == Estado.DESCARGANDO }
        val enCola = tareas.count { it.estado == Estado.EN_COLA }
        tvResumen.text = when {
            activas + enCola == 0 -> "Descargas"
            enCola == 0 -> "Descargas · $activas en curso"
            else -> "Descargas · $activas en curso, $enCola en cola"
        }
        tvVacio.visibility = if (tareas.isEmpty()) View.VISIBLE else View.GONE
        // Mantiene la pantalla encendida mientras haya descargas pendientes
        if (activas + enCola > 0) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun mensajeError(msg: String): String = when {
        msg.contains("DRM", true) -> "❌ Este sitio usa protección DRM y no se puede descargar"
        msg.contains("Unsupported URL", true) -> "❌ Sitio no compatible o el enlace no tiene video"
        else -> "❌ " + (msg.lines().lastOrNull { it.isNotBlank() } ?: "Error desconocido") +
            "\nSi un sitio que antes servía falla, toca \"Actualizar motor\"."
    }

    private fun formatoTiempo(seg: Long): String =
        if (seg >= 60) "${seg / 60}m ${seg % 60}s" else "${seg}s"

    private fun moverADescargas(carpeta: File): List<String> {
        val nombres = mutableListOf<String>()
        carpeta.listFiles()?.filter {
            it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".aria2") && !it.name.endsWith(".ytdl")
        }?.forEach { f ->
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
                ?: "application/octet-stream"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val valores = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DescargaVideos")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                    ?: return@forEach
                contentResolver.openOutputStream(uri)?.use { out ->
                    f.inputStream().use { it.copyTo(out) }
                }
            } else {
                val destino = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "DescargaVideos"
                ).apply { mkdirs() }
                f.copyTo(File(destino, f.name), overwrite = true)
            }
            nombres.add(f.name)
        }
        return nombres
    }

    private fun actualizarMotor() {
        if (tareas.any { it.estado == Estado.DESCARGANDO || it.estado == Estado.EN_COLA }) {
            Toast.makeText(this, "Espera a que terminen las descargas", Toast.LENGTH_SHORT).show()
            return
        }
        btnActualizar.isEnabled = false
        btnActualizar.text = "Actualizando..."
        lifecycleScope.launch {
            try {
                val estado = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(
                        applicationContext, YoutubeDL.UpdateChannel._STABLE
                    )
                }
                Toast.makeText(this@MainActivity, "Motor actualizado: $estado", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "No se pudo actualizar: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btnActualizar.isEnabled = true
                btnActualizar.text = "Actualizar motor (si un sitio deja de funcionar)"
            }
        }
    }
}
