package com.franyer.descargavideos

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * Reproductor propio: pantalla completa; arrastra el dedo a la derecha para adelantar y a la izquierda para atrasar.
 * Sirve para la carpeta privada (sin capturas, se cierra al salir) y también para abrir
 * cualquier video desde la galería con "Abrir con → Descarga Videos".
 */
class ReproductorActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    /** true si viene de la carpeta privada (no de "Abrir con" desde la galería). */
    private var modoPrivado = true

    /** Posición donde empezó el arrastre y destino que se va calculando. */
    private var inicioArrastre = 0L
    private var destinoArrastre = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modoPrivado = intent.action != android.content.Intent.ACTION_VIEW
        if (modoPrivado) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_reproductor)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val uri: Uri? = if (modoPrivado) {
            val ruta = intent.getStringExtra("ruta")
            if (ruta == null || !Privado.sesionActiva) null else Uri.fromFile(File(ruta))
        } else intent.data
        if (uri == null) {
            finish()
            return
        }
        val playerView = findViewById<PlayerView>(R.id.playerView)
        player = ExoPlayer.Builder(this).build().also { p ->
                playerView.player = p
                p.setMediaItem(MediaItem.fromUri(uri))
                p.prepare()
                p.playWhenReady = true
            }
        val aviso = findViewById<android.widget.TextView>(R.id.tvArrastre)
        findViewById<ZonaToques>(R.id.zonaToques).apply {
            alEmpezar = {
                player?.let { p ->
                    inicioArrastre = p.currentPosition
                    destinoArrastre = inicioArrastre
                    // Mientras arrastras salta a cuadros clave: la imagen sigue al dedo sin trabarse
                    p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
                }
                playerView.hideController()
                aviso.visibility = android.view.View.VISIBLE
            }
            alArrastrar = { fraccion ->
                player?.let { p ->
                    val duracion = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    destinoArrastre = (inicioArrastre + (fraccion * ANCHO_PANTALLA_MS).toLong()).coerceIn(0, duracion)
                    p.seekTo(destinoArrastre)
                    val diferencia = destinoArrastre - inicioArrastre
                    val signo = if (diferencia >= 0) "+" else "−"
                    aviso.text = "$signo${tiempo(kotlin.math.abs(diferencia))}\n${tiempo(destinoArrastre)} / ${tiempo(p.duration)}"
                }
            }
            alSoltar = {
                player?.let { p ->
                    p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT)
                    p.seekTo(destinoArrastre)
                }
                aviso.visibility = android.view.View.GONE
            }
        }
    }

    /** 125000 → "2:05"; con horas "1:02:05". */
    private fun tiempo(ms: Long): String {
        if (ms < 0) return "--:--"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val seg = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, seg) else "%d:%02d".format(m, seg)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            // Saliste de la app (no con "atrás"): se cierra todo y la carpeta vuelve a bloquearse
            if (!isFinishing && modoPrivado) Privado.sesionActiva = false
            finish()
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        /** Arrastrar el dedo de lado a lado de la pantalla mueve 2 minutos. */
        private const val ANCHO_PANTALLA_MS = 120_000L
    }
}
