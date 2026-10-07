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
 * Reproductor propio: pantalla completa y doble toque a los lados para adelantar o atrasar 10 s.
 * Sirve para la carpeta privada (sin capturas, se cierra al salir) y también para abrir
 * cualquier video desde la galería con "Abrir con → Descarga Videos".
 */
class ReproductorActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    /** true si viene de la carpeta privada (no de "Abrir con" desde la galería). */
    private var modoPrivado = true

    private val manejador = android.os.Handler(android.os.Looper.getMainLooper())
    private var acumulado = 0
    private var ladoAcumulado = 0
    private val ocultarAviso = Runnable {
        findViewById<android.view.View>(R.id.tvSaltoAtras).visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.tvSaltoAdelante).visibility = android.view.View.GONE
        acumulado = 0
        ladoAcumulado = 0
    }

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
        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(SALTO_MS)
            .setSeekForwardIncrementMs(SALTO_MS)
            .build().also { p ->
                playerView.player = p
                p.setMediaItem(MediaItem.fromUri(uri))
                p.prepare()
                p.playWhenReady = true
            }
        findViewById<ZonaToques>(R.id.zonaToques).alSaltar = { dir ->
            saltar(dir)
            playerView.hideController()
        }
    }

    /** Salta 10 s y muestra cuánto lleva acumulado ("⏪ 20 s"). */
    private fun saltar(dir: Int) {
        val p = player ?: return
        val duracion = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + dir * SALTO_MS).coerceIn(0, duracion))

        acumulado = if (dir == ladoAcumulado) acumulado + (SALTO_MS / 1000).toInt() else (SALTO_MS / 1000).toInt()
        ladoAcumulado = dir
        val atras = findViewById<android.widget.TextView>(R.id.tvSaltoAtras)
        val adelante = findViewById<android.widget.TextView>(R.id.tvSaltoAdelante)
        if (dir < 0) {
            atras.text = "⏪ $acumulado s"
            atras.visibility = android.view.View.VISIBLE
            adelante.visibility = android.view.View.GONE
        } else {
            adelante.text = "$acumulado s ⏩"
            adelante.visibility = android.view.View.VISIBLE
            atras.visibility = android.view.View.GONE
        }
        manejador.removeCallbacks(ocultarAviso)
        manejador.postDelayed(ocultarAviso, 800)
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
        manejador.removeCallbacks(ocultarAviso)
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val SALTO_MS = 10_000L
    }
}
