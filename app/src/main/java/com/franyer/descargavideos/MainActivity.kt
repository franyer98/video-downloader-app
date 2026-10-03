package com.franyer.descargavideos

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: TextInputEditText
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

        val swPrivado = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swPrivado)
        swPrivado.isChecked = Privado.activado(this)
        swPrivado.setOnCheckedChangeListener { _, si -> Privado.activar(this, si) }
        findViewById<MaterialButton>(R.id.btnPrivado).setOnClickListener {
            startActivity(Intent(this, PrivadoActivity::class.java))
        }

        pedirPermisos()
        findViewById<TextView>(R.id.tvVersion).text = "Versión " +
            packageManager.getPackageInfo(packageName, 0).versionName

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Gestor.descargas.collect { dibujar(it) }
            }
        }

        manejarCompartir(intent)
        buscarActualizaciones()
    }

    /** Revisa si hay versión nueva de la app y actualiza yt-dlp en silencio una vez al día. */
    private fun buscarActualizaciones() {
        lifecycleScope.launch {
            Actualizador.buscar(applicationContext)?.let { nueva ->
                if (!isFinishing) MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Nueva versión ${nueva.versionName}")
                    .setMessage("Ya se descargó. Toca Instalar para actualizar; tus descargas en curso no se pierden si esperas a que terminen.")
                    .setPositiveButton("Instalar") { _, _ -> Actualizador.instalar(this@MainActivity, nueva) }
                    .setNegativeButton("Luego", null)
                    .show()
            }

            val prefs = getSharedPreferences("ajustes", MODE_PRIVATE)
            val ahora = System.currentTimeMillis()
            if (ahora - prefs.getLong("motor_actualizado", 0) > 24 * 60 * 60 * 1000L &&
                Gestor.descargas.value.none { it.pendiente }
            ) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        Gestor.asegurarMotor(applicationContext)
                        YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel._STABLE)
                    }
                    prefs.edit().putLong("motor_actualizado", ahora).apply()
                }
            }
        }
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

    /** Un enlace compartido desde otra app abre el selector de calidad. */
    private fun manejarCompartir(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val url = intent.getStringExtra(Intent.EXTRA_TEXT)?.let { extraerUrl(it) } ?: return
            intent.action = null
            elegirCalidad(url)
        }
    }

    /** Analiza el enlace y muestra las calidades disponibles con su peso aproximado. */
    /** Avisa y devuelve true si el video ya se descargó o ya está en descarga. */
    private fun bloquearDuplicado(url: String, clave: String?): Boolean {
        val mensaje = when {
            Gestor.pendienteIgual(url, clave) != null -> "Este video ya se está descargando."
            Historial.yaDescargado(this, Historial.normalizar(url), clave) -> "Ya descargaste este video antes."
            else -> return false
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Video repetido")
            .setMessage(mensaje)
            .setPositiveButton("Entendido", null)
            .show()
        return true
    }

    private fun elegirCalidad(url: String) {
        // Revisión rápida por enlace, antes de analizar
        if (bloquearDuplicado(url, null)) return
        val progreso = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            setPadding(64, 24, 64, 0)
        }
        var trabajo: kotlinx.coroutines.Job? = null
        val espera = MaterialAlertDialogBuilder(this)
            .setTitle("Buscando calidades…")
            .setMessage("Analizando el enlace, tarda unos segundos.")
            .setView(progreso)
            .setNegativeButton("Cancelar") { _, _ -> trabajo?.cancel() }
            .setOnCancelListener { trabajo?.cancel() }
            .show()

        trabajo = lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) {
                runCatching {
                    Gestor.asegurarMotor(applicationContext)
                    YoutubeDL.getInstance().getInfo(url)
                }
            }
            espera.dismiss()
            if (isFinishing) return@launch

            resultado.onSuccess { info ->
                // Revisión por id del video: detecta el mismo video aunque el enlace sea distinto
                val clave = Historial.claveVideo(info.extractorKey ?: info.extractor, info.id)
                if (bloquearDuplicado(url, clave)) return@onSuccess
                val opciones = Calidades.opciones(info)
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(info.title ?: "Elige la calidad")
                    .setItems(opciones.map { it.etiqueta }.toTypedArray()) { _, i ->
                        val o = opciones[i]
                        DescargaService.agregar(this@MainActivity, url, o.calidad, o.formato, Privado.activado(this@MainActivity), clave)
                        Toast.makeText(this@MainActivity, "Agregado a descargas", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) return@onFailure
                val detalle = e.message.orEmpty().lines().lastOrNull { it.isNotBlank() } ?: "error desconocido"
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("No se pudieron leer las calidades")
                    .setMessage("$detalle\n\n¿Intento descargarlo igual en la mejor calidad?")
                    .setPositiveButton("Descargar igual") { _, _ ->
                        DescargaService.agregar(this@MainActivity, url, Calidad.MEJOR, privado = Privado.activado(this@MainActivity))
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
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
        etUrl.setText("")
        elegirCalidad(url)
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
        // Las privadas no muestran nombre ni imagen en la lista
        v.findViewById<TextView>(R.id.tvTitulo).text = if (d.privado) "🔒 Video privado" else d.titulo
        if (!d.privado) pintarMiniatura(v.findViewById(R.id.ivMiniatura), d.miniatura)
        val progreso = v.findViewById<LinearProgressIndicator>(R.id.progreso)
        val tvEstado = v.findViewById<TextView>(R.id.tvEstado)
        val btn = v.findViewById<MaterialButton>(R.id.btnAccion)

        tvEstado.text = textoEstado(d)

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

    /** Carga la miniatura solo cuando cambia, reducida para no gastar memoria. */
    private fun pintarMiniatura(iv: ImageView, ruta: String?) {
        if (ruta == null || iv.tag == ruta) return
        iv.tag = ruta
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(ruta, limites)
                    var muestra = 1
                    while (limites.outWidth / (muestra * 2) >= 320) muestra *= 2
                    BitmapFactory.decodeFile(ruta, BitmapFactory.Options().apply { inSampleSize = muestra })
                }.getOrNull()
            }
            if (bmp != null && iv.tag == ruta) {
                iv.setImageBitmap(bmp)
                iv.background = null
            }
        }
    }

    /** Ej.: "41% · 50.6 MB de 123.4 MB · 2.3 MB/s · faltan 45s" */
    private fun textoEstado(d: Descarga): String = when {
        d.estado == Estado.DESCARGANDO && d.progreso > 0 -> buildList {
            add("${d.progreso}%")
            d.tamano?.let { total ->
                val num = total.substringBefore(" ").toDoubleOrNull()
                val unidad = total.substringAfter(" ", "")
                if (num != null && unidad.isNotEmpty())
                    add(String.format("%.1f %s de %s", num * d.progreso / 100.0, unidad, total))
                else add(total)
            }
            d.velocidad?.let { add(it) }
            if (d.eta > 0) add("faltan ${formatoTiempo(d.eta)}")
        }.joinToString(" · ")
        d.estado == Estado.LISTO && d.privado -> listOfNotNull(d.mensaje, d.tamano).joinToString(" · ")
        d.estado == Estado.LISTO -> listOfNotNull(
            listOfNotNull(d.mensaje, d.tamano).joinToString(" · "), d.detalle
        ).joinToString("\n")
        else -> d.mensaje
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
