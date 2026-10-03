package com.franyer.descargavideos

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: TextInputEditText
    private lateinit var rgCalidad: RadioGroup
    private lateinit var btnDescargar: MaterialButton
    private lateinit var btnActualizar: MaterialButton
    private lateinit var btnLimpiar: MaterialButton
    private lateinit var contenedor: LinearLayout
    private lateinit var tvResumen: TextView
    private lateinit var tvVacio: TextView

    /** Vistas ya dibujadas, por id de descarga. */
    private val vistas = mutableMapOf<String, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        rgCalidad = findViewById(R.id.rgCalidad)
        btnDescargar = findViewById(R.id.btnDescargar)
        btnActualizar = findViewById(R.id.btnActualizar)
        btnLimpiar = findViewById(R.id.btnLimpiar)
        contenedor = findViewById(R.id.contenedor)
        tvResumen = findViewById(R.id.tvResumen)
        tvVacio = findViewById(R.id.tvVacio)

        btnDescargar.isEnabled = true
        btnDescargar.text = "Descargar"
        findViewById<MaterialButton>(R.id.btnPegar).setOnClickListener { pegar() }
        btnDescargar.setOnClickListener { agregarDesdeCampo() }
        btnActualizar.setOnClickListener { actualizarMotor() }
        btnLimpiar.setOnClickListener { Gestor.limpiarTerminadas() }

        pedirPermisos()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Gestor.descargas.collect { dibujar(it) }
            }
        }

        manejarCompartir(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        manejarCompartir(intent)
    }

    private fun pedirPermisos() {
        val faltan = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (faltan.isNotEmpty()) ActivityCompat.requestPermissions(this, faltan.toTypedArray(), 1)
    }

    private fun calidadElegida(): Calidad = when (rgCalidad.checkedRadioButtonId) {
        R.id.rb720 -> Calidad.P720
        R.id.rb480 -> Calidad.P480
        R.id.rbAudio -> Calidad.AUDIO
        else -> Calidad.MEJOR
    }

    /** Un enlace compartido desde otra app se agrega de una vez. */
    private fun manejarCompartir(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val url = intent.getStringExtra(Intent.EXTRA_TEXT)?.let { extraerUrl(it) } ?: return
            DescargaService.agregar(this, url, calidadElegida())
            intent.action = null
            Toast.makeText(this, "Agregado a descargas", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extraerUrl(texto: String): String? = Regex("https?://\\S+").find(texto)?.value

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
        DescargaService.agregar(this, url, calidadElegida())
        etUrl.setText("")
    }

    private fun dibujar(lista: List<Descarga>) {
        // Quita las que ya no están
        val ids = lista.map { it.id }.toSet()
        vistas.keys.filter { it !in ids }.forEach { id -> contenedor.removeView(vistas.remove(id)) }

        // Agrega o actualiza, manteniendo el orden (la más nueva arriba)
        lista.forEachIndexed { i, d ->
            val v = vistas.getOrPut(d.id) {
                layoutInflater.inflate(R.layout.item_descarga, contenedor, false).also { contenedor.addView(it, i) }
            }
            pintar(v, d)
        }

        val activas = lista.count { it.estado == Estado.DESCARGANDO }
        val enCola = lista.count { it.estado == Estado.EN_COLA }
        val listas = lista.count { it.estado == Estado.LISTO }
        tvResumen.text = buildString {
            append("Descargas")
            if (activas > 0) append(" · $activas en curso")
            if (enCola > 0) append(" · $enCola en cola")
            if (listas > 0) append(" · $listas listas")
        }
        tvVacio.visibility = if (lista.isEmpty()) View.VISIBLE else View.GONE
        btnLimpiar.visibility = if (lista.any { !it.pendiente }) View.VISIBLE else View.GONE
    }

    private fun pintar(v: View, d: Descarga) {
        v.findViewById<TextView>(R.id.tvTitulo).text = d.titulo
        val progreso = v.findViewById<LinearProgressIndicator>(R.id.progreso)
        val tvEstado = v.findViewById<TextView>(R.id.tvEstado)
        val btn = v.findViewById<MaterialButton>(R.id.btnAccion)

        tvEstado.text = if (d.estado == Estado.DESCARGANDO && d.progreso > 0 && d.eta > 0)
            "${d.mensaje} · faltan ${formatoTiempo(d.eta)}" else d.mensaje

        if (d.pendiente) {
            progreso.visibility = View.VISIBLE
            if (d.progreso > 0) {
                progreso.isIndeterminate = false
                progreso.setProgressCompat(d.progreso, true)
            } else if (!progreso.isIndeterminate) {
                progreso.visibility = View.INVISIBLE
                progreso.isIndeterminate = true
                progreso.visibility = View.VISIBLE
            }
        } else {
            progreso.visibility = View.GONE
        }

        when (d.estado) {
            Estado.EN_COLA, Estado.DESCARGANDO -> {
                btn.text = "Cancelar"
                btn.setOnClickListener { DescargaService.cancelar(this, d.id) }
            }
            Estado.ERROR, Estado.CANCELADO -> {
                btn.text = "Reintentar"
                btn.setOnClickListener { DescargaService.reintentar(this, d.id) }
            }
            Estado.LISTO -> {
                btn.text = "Quitar"
                btn.setOnClickListener { Gestor.quitar(d.id) }
            }
        }
    }

    private fun formatoTiempo(seg: Long): String =
        if (seg >= 60) "${seg / 60}m ${seg % 60}s" else "${seg}s"

    private fun actualizarMotor() {
        if (Gestor.descargas.value.any { it.pendiente }) {
            Toast.makeText(this, "Espera a que terminen las descargas", Toast.LENGTH_SHORT).show()
            return
        }
        btnActualizar.isEnabled = false
        btnActualizar.text = "Actualizando..."
        lifecycleScope.launch {
            try {
                val estado = withContext(Dispatchers.IO) {
                    Gestor.asegurarMotor(applicationContext)
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel._STABLE)
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
