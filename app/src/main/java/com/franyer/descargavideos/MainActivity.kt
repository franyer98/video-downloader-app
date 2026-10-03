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
import android.webkit.MimeTypeMap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: TextInputEditText
    private lateinit var rgCalidad: RadioGroup
    private lateinit var btnDescargar: MaterialButton
    private lateinit var btnCancelar: MaterialButton
    private lateinit var btnActualizar: MaterialButton
    private lateinit var progreso: LinearProgressIndicator
    private lateinit var tvEstado: TextView

    private val processId = "descarga"
    private var listo = false
    private var descargando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        rgCalidad = findViewById(R.id.rgCalidad)
        btnDescargar = findViewById(R.id.btnDescargar)
        btnCancelar = findViewById(R.id.btnCancelar)
        btnActualizar = findViewById(R.id.btnActualizar)
        progreso = findViewById(R.id.progreso)
        tvEstado = findViewById(R.id.tvEstado)

        findViewById<MaterialButton>(R.id.btnPegar).setOnClickListener { pegar() }
        btnDescargar.setOnClickListener { descargar() }
        btnCancelar.setOnClickListener {
            YoutubeDL.getInstance().destroyProcessById(processId)
            tvEstado.text = "Cancelado"
        }
        btnActualizar.setOnClickListener { actualizarMotor() }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
        }

        manejarCompartir(intent)
        inicializar()
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
                listo = true
                btnDescargar.isEnabled = true
                btnDescargar.text = "Descargar"
            } catch (e: Exception) {
                btnDescargar.text = "Error al iniciar"
                tvEstado.text = "No se pudo iniciar el motor: ${e.message}"
            }
        }
    }

    private fun manejarCompartir(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val texto = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val url = extraerUrl(texto) ?: return
            etUrl.setText(url)
            tvEstado.text = "Enlace recibido. Elige la calidad y toca Descargar."
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

    private fun descargar() {
        if (!listo || descargando) return
        val url = etUrl.text?.toString()?.trim().orEmpty()
        if (!url.startsWith("http")) {
            Toast.makeText(this, "Escribe o pega un enlace válido", Toast.LENGTH_SHORT).show()
            return
        }

        val carpetaTemp = File(cacheDir, "descargas").apply { deleteRecursively(); mkdirs() }
        val req = YoutubeDLRequest(url).apply {
            addOption("-o", carpetaTemp.absolutePath + "/%(title).80s.%(ext)s")
            addOption("--no-mtime")
            addOption("--no-playlist")
            addOption("--restrict-filenames")
            // aria2c: descarga con varias conexiones a la vez (más rápido)
            addOption("--downloader", "libaria2c.so")
            addOption("--downloader-args", "aria2c:\"-x 16 -s 16 -k 1M\"")
            when (rgCalidad.checkedRadioButtonId) {
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

        setDescargando(true)
        tvEstado.text = "Analizando enlace..."

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(req, processId) { prog, eta, _ ->
                        runOnUiThread {
                            if (prog >= 0) {
                                progreso.isIndeterminate = false
                                progreso.setProgressCompat(prog.toInt(), true)
                                tvEstado.text = "Descargando ${prog.toInt()}%" +
                                    if (eta > 0) " · faltan ${eta}s" else ""
                            }
                        }
                    }
                }
                tvEstado.text = "Guardando..."
                val guardados = withContext(Dispatchers.IO) { moverADescargas(carpetaTemp) }
                tvEstado.text = if (guardados.isNotEmpty())
                    "✅ Guardado en Descargas/DescargaVideos:\n" + guardados.joinToString("\n")
                else "No se encontró el archivo descargado."
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                tvEstado.text = when {
                    msg.contains("DRM", true) ->
                        "❌ Este sitio usa protección DRM y no se puede descargar."
                    msg.contains("Unsupported URL", true) ->
                        "❌ Este sitio no es compatible o el enlace no tiene video."
                    msg.contains("canceled", true) || msg.contains("cancel", true) ->
                        "Descarga cancelada."
                    else -> "❌ Error: " + msg.lines().lastOrNull { it.isNotBlank() }.orEmpty() +
                        "\n\nSi un sitio que antes funcionaba falla, toca \"Actualizar motor\"."
                }
            } finally {
                setDescargando(false)
                carpetaTemp.deleteRecursively()
            }
        }
    }

    private fun moverADescargas(carpeta: File): List<String> {
        val nombres = mutableListOf<String>()
        carpeta.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.forEach { f ->
            val ext = f.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
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
        if (!listo || descargando) return
        btnActualizar.isEnabled = false
        tvEstado.text = "Actualizando yt-dlp..."
        lifecycleScope.launch {
            try {
                val estado = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(
                        applicationContext, YoutubeDL.UpdateChannel._STABLE
                    )
                }
                tvEstado.text = "Motor actualizado: $estado"
            } catch (e: Exception) {
                tvEstado.text = "No se pudo actualizar: ${e.message}"
            } finally {
                btnActualizar.isEnabled = true
            }
        }
    }

    private fun setDescargando(activo: Boolean) {
        descargando = activo
        btnDescargar.isEnabled = !activo
        btnDescargar.text = if (activo) "Descargando..." else "Descargar"
        btnCancelar.visibility = if (activo) View.VISIBLE else View.GONE
        progreso.visibility = if (activo) View.VISIBLE else View.GONE
        if (activo) progreso.isIndeterminate = true
    }
}
