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

/** Reproductor de la carpeta privada: pantalla completa, sin capturas, se cierra al salir. */
class ReproductorActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_reproductor)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val ruta = intent.getStringExtra("ruta")
        if (ruta == null || !Privado.sesionActiva) {
            finish()
            return
        }
        player = ExoPlayer.Builder(this).build().also { p ->
            findViewById<PlayerView>(R.id.playerView).player = p
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(ruta))))
            p.prepare()
            p.playWhenReady = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            // Saliste de la app (no con "atrás"): se cierra todo y la carpeta vuelve a bloquearse
            if (!isFinishing) Privado.sesionActiva = false
            finish()
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
