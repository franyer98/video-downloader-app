package com.franyer98.videodownloader

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.franyer98.videodownloader.databinding.ActivityMainBinding
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentVideoInfo: VideoInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicializa yt-dlp y ffmpeg (se actualiza el binario en background la 1ra vez)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error al iniciar yt-dlp: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.recyclerFormats.layoutManager = LinearLayoutManager(this)

        binding.btnAnalyze.setOnClickListener {
            val url = binding.editUrl.text.toString().trim()
            if (url.isNotEmpty()) analyzeUrl(url)
        }

        // Si la app se abrió desde "Compartir" un link del navegador
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedUrl = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedUrl.isNullOrBlank()) {
                binding.editUrl.setText(sharedUrl)
                analyzeUrl(sharedUrl)
            }
        }
    }

    private fun analyzeUrl(url: String) {
        setLoading(true, "Analizando enlace...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val info = YoutubeDL.getInstance().getInfo(url)
                currentVideoInfo = info

                val formats = info.formats?.mapNotNull { f ->
                    val note = f.formatNote ?: f.resolution ?: "N/A"
                    val ext = f.ext ?: ""
                    val id = f.formatId ?: return@mapNotNull null
                    VideoFormat(id, "$note · $ext (id: $id)")
                } ?: emptyList()

                withContext(Dispatchers.Main) {
                    setLoading(false, "Encontrado: ${info.title ?: url}")
                    binding.recyclerFormats.adapter = FormatsAdapter(formats.reversed()) { format ->
                        downloadFormat(url, format)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setLoading(false, "Error: ${e.message}")
                }
            }
        }
    }

    private fun downloadFormat(url: String, format: VideoFormat) {
        setLoading(true, "Descargando (${format.label})...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val outputDir = File(
                    getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    "VideoDownloader"
                ).apply { mkdirs() }

                val request = YoutubeDLRequest(url).apply {
                    addOption("-f", format.formatId)
                    addOption("-o", "${outputDir.absolutePath}/%(title)s.%(ext)s")
                }

                YoutubeDL.getInstance().execute(request) { progress, _, line ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.progressBar.progress = progress.toInt()
                        binding.txtStatus.text = line
                    }
                }

                withContext(Dispatchers.Main) {
                    setLoading(false, "Descarga completa. Guardado en Movies/VideoDownloader")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setLoading(false, "Error al descargar: ${e.message}")
                }
            }
        }
    }

    private fun setLoading(loading: Boolean, message: String) {
        binding.progressBar.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        binding.txtStatus.text = message
        binding.btnAnalyze.isEnabled = !loading
    }
}
