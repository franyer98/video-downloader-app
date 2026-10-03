package com.franyer.descargavideos

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Carpeta privada: pide huella o PIN, bloquea capturas y se cierra al salir de la app. */
class PrivadoActivity : AppCompatActivity() {

    private lateinit var lista: LinearLayout
    private lateinit var tvResumen: TextView
    private lateinit var tvVacio: TextView
    private var abriendoReproductor = false
    /** El PIN del teléfono abre otra pantalla; mientras tanto no hay que cerrar. */
    private var autenticando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sin capturas de pantalla y oculto en la vista de apps recientes
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_privado)
        lista = findViewById(R.id.lista)
        tvResumen = findViewById(R.id.tvResumen)
        tvVacio = findViewById(R.id.tvVacio)
        lista.visibility = View.INVISIBLE

        if (Privado.sesionActiva) mostrar() else pedirIdentidad()
    }

    private fun pedirIdentidad() {
        val permitidos = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(permitidos) != BiometricManager.BIOMETRIC_SUCCESS) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Configura un bloqueo")
                .setMessage("Para proteger la carpeta privada, el teléfono necesita una huella, PIN o patrón. Configúralo en Ajustes → Seguridad.")
                .setPositiveButton("Entendido") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    autenticando = false
                    Privado.sesionActiva = true
                    mostrar()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    autenticando = false
                    finish()
                }
            })
        autenticando = true
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Carpeta privada")
                .setSubtitle("Usa tu huella o el PIN del teléfono")
                .setAllowedAuthenticators(permitidos)
                .build()
        )
    }

    override fun onResume() {
        super.onResume()
        abriendoReproductor = false
        // Si se cerró la sesión (por ejemplo, saliste de la app desde el reproductor), vuelve a bloquear
        if (!Privado.sesionActiva && lista.visibility == View.VISIBLE) finish()
        else if (Privado.sesionActiva) mostrar()
    }

    override fun onStop() {
        super.onStop()
        if (!abriendoReproductor && !autenticando && !isChangingConfigurations) {
            Privado.sesionActiva = false
            finish()
        }
    }

    private fun mostrar() {
        lista.visibility = View.VISIBLE
        val videos = Privado.videos(this)
        val total = videos.sumOf { it.length() }
        tvResumen.text = if (videos.isEmpty()) "Vacía"
            else "${videos.size} video${if (videos.size == 1) "" else "s"} · ${Gestor.formatoBytes(total)}"
        tvVacio.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE

        lista.removeAllViews()
        videos.forEach { video ->
            val v = layoutInflater.inflate(R.layout.item_privado, lista, false)
            v.findViewById<TextView>(R.id.tvNombre).text = video.nameWithoutExtension.replace('_', ' ')
            v.findViewById<TextView>(R.id.tvInfo).text = Gestor.formatoBytes(video.length())
            val iv = v.findViewById<ImageView>(R.id.ivMini)
            lifecycleScope.launch {
                val mini = withContext(Dispatchers.IO) { Privado.miniatura(this@PrivadoActivity, video) }
                val bmp = mini?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it.absolutePath) } }
                if (bmp != null) {
                    iv.setImageBitmap(bmp)
                    iv.background = null
                }
            }
            v.setOnClickListener { reproducir(video) }
            v.setOnLongClickListener { opciones(video); true }
            lista.addView(v)
        }
    }

    private fun reproducir(video: File) {
        abriendoReproductor = true
        startActivity(Intent(this, ReproductorActivity::class.java).putExtra("ruta", video.absolutePath))
    }

    private fun opciones(video: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(video.nameWithoutExtension.replace('_', ' '))
            .setItems(arrayOf("Reproducir", "Pasar a la galería", "Eliminar")) { _, i ->
                when (i) {
                    0 -> reproducir(video)
                    1 -> lifecycleScope.launch {
                        Toast.makeText(this@PrivadoActivity, "Moviendo a la galería…", Toast.LENGTH_SHORT).show()
                        val ok = withContext(Dispatchers.IO) { Privado.pasarAGaleria(this@PrivadoActivity, video) }
                        Toast.makeText(this@PrivadoActivity,
                            if (ok) "Movido a Películas/DescargaVideos" else "No se pudo mover", Toast.LENGTH_SHORT).show()
                        mostrar()
                    }
                    2 -> MaterialAlertDialogBuilder(this)
                        .setTitle("¿Eliminar este video?")
                        .setMessage("Se borra para siempre.")
                        .setPositiveButton("Eliminar") { _, _ ->
                            Privado.eliminar(this, video)
                            mostrar()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
            .show()
    }
}
