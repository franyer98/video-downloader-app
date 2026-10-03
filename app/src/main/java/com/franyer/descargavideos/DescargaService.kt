package com.franyer.descargavideos

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Servicio en primer plano: las descargas siguen aunque cierres la app o apagues la pantalla.
 */
class DescargaService : Service() {

    companion object {
        private const val ACCION_AGREGAR = "agregar"
        private const val ACCION_CANCELAR = "cancelar"
        private const val ACCION_REINTENTAR = "reintentar"
        private const val CANAL = "descargas"
        private const val CANAL_FIN = "descargas_fin"
        private const val NOTIF_ID = 1
        private const val NOTIF_FIN_ID = 2

        fun agregar(ctx: Context, url: String, calidad: Calidad) = enviar(ctx,
            Intent(ctx, DescargaService::class.java).setAction(ACCION_AGREGAR)
                .putExtra("url", url).putExtra("calidad", calidad.name))

        fun cancelar(ctx: Context, id: String) = enviar(ctx,
            Intent(ctx, DescargaService::class.java).setAction(ACCION_CANCELAR).putExtra("id", id))

        fun reintentar(ctx: Context, id: String) = enviar(ctx,
            Intent(ctx, DescargaService::class.java).setAction(ACCION_REINTENTAR).putExtra("id", id))

        private fun enviar(ctx: Context, intent: Intent) =
            ContextCompat.startForegroundService(ctx, intent)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val semaforo = Semaphore(Gestor.MAX_SIMULTANEAS)
    private val trabajos = ConcurrentHashMap<String, Job>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var listasEnSesion = 0

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        crearCanales()
        iniciarPrimerPlano()
        tomarLocks()

        // Actualiza la notificación como máximo cada segundo
        scope.launch {
            Gestor.descargas.sample(1000).collect { lista ->
                if (lista.none { it.pendiente }) terminarServicio()
                else NotificationManagerCompat.from(this@DescargaService).notifySeguro(NOTIF_ID, notificacion(lista))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        iniciarPrimerPlano()
        when (intent?.action) {
            ACCION_AGREGAR -> {
                val url = intent.getStringExtra("url") ?: return START_NOT_STICKY
                val calidad = runCatching { Calidad.valueOf(intent.getStringExtra("calidad")!!) }
                    .getOrDefault(Calidad.MEJOR)
                lanzar(Gestor.nueva(url, calidad))
            }
            ACCION_CANCELAR -> intent.getStringExtra("id")?.let { cancelarDescarga(it) }
            ACCION_REINTENTAR -> intent.getStringExtra("id")?.let { id ->
                Gestor.obtener(id)?.let { d ->
                    Gestor.quitar(id)
                    lanzar(Gestor.nueva(d.url, d.calidad))
                }
            }
        }
        if (Gestor.descargas.value.none { it.pendiente }) terminarServicio()
        return START_NOT_STICKY
    }

    private fun lanzar(d: Descarga) {
        trabajos[d.id] = scope.launch {
            try {
                Gestor.asegurarMotor(applicationContext)
                semaforo.withPermit {
                    if (Gestor.obtener(d.id)?.estado == Estado.EN_COLA) ejecutar(d)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Gestor.actualizar(d.id) {
                    it.copy(estado = Estado.ERROR, mensaje = "❌ No se pudo iniciar el motor: ${e.message}")
                }
            } finally {
                trabajos.remove(d.id)
            }
        }
    }

    private fun cancelarDescarga(id: String) {
        val d = Gestor.obtener(id) ?: return
        Gestor.actualizar(id) { it.copy(estado = Estado.CANCELADO, mensaje = "Cancelado") }
        if (d.estado == Estado.DESCARGANDO) YoutubeDL.getInstance().destroyProcessById(id)
        else trabajos[id]?.cancel()
    }

    private suspend fun ejecutar(d: Descarga) {
        Gestor.actualizar(d.id) { it.copy(estado = Estado.DESCARGANDO, mensaje = "Analizando enlace...") }
        val carpetaTemp = File(cacheDir, d.id).apply { deleteRecursively(); mkdirs() }
        val carpetaMini = File(carpetaTemp, "miniatura").apply { mkdirs() }
        var miniaturaLista = false

        val req = YoutubeDLRequest(d.url).apply {
            addOption("-o", carpetaTemp.absolutePath + "/%(title).80s.%(ext)s")
            // Miniatura aparte (se baja antes que el video) para mostrarla en la lista
            addOption("--write-thumbnail")
            addOption("--output", "thumbnail:" + carpetaMini.absolutePath + "/mini.%(ext)s")
            addOption("--no-mtime")
            addOption("--no-playlist")
            addOption("--restrict-filenames")
            addOption("--newline")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            // Videos por fragmentos (HLS/DASH): baja varios pedazos a la vez
            addOption("--concurrent-fragments", Gestor.FRAGMENTOS_PARALELOS.toString())
            // Archivos directos: aria2c con varias conexiones
            addOption("--downloader", "libaria2c.so")
            addOption("--external-downloader", "m3u8,dash:native")
            addOption("--downloader-args", "aria2c:\"-x 16 -s 16 -k 1M\"")
            when (d.calidad) {
                Calidad.AUDIO -> {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                }
                Calidad.P720 -> {
                    addOption("-f", "bv*[height<=720]+ba/b[height<=720]/b")
                    addOption("--merge-output-format", "mp4")
                }
                Calidad.P480 -> {
                    addOption("-f", "bv*[height<=480]+ba/b[height<=480]/b")
                    addOption("--merge-output-format", "mp4")
                }
                Calidad.MEJOR -> {
                    addOption("-f", "bv*+ba/b")
                    addOption("--merge-output-format", "mp4")
                }
            }
        }

        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(req, d.id) { prog, eta, linea ->
                    if (!miniaturaLista) {
                        guardarMiniatura(d.id, carpetaMini)?.let { ruta ->
                            miniaturaLista = true
                            Gestor.actualizar(d.id) { it.copy(miniatura = ruta) }
                        }
                    }
                    val nombre = Regex("Destination: .*/(.+)$").find(linea)?.groupValues?.get(1)
                        ?.takeUnless { it.startsWith("mini.") }
                    val (total, vel) = Gestor.leerProgreso(linea)
                    Gestor.actualizar(d.id) {
                        if (it.estado != Estado.DESCARGANDO) it
                        else it.copy(
                            tamano = total ?: it.tamano,
                            velocidad = vel ?: it.velocidad,
                            titulo = nombre ?: it.titulo,
                            progreso = if (prog > 0) prog.toInt() else it.progreso,
                            eta = eta,
                            mensaje = if (prog > 0) "Descargando ${prog.toInt()}%" else it.mensaje,
                        )
                    }
                }
            }
            if (Gestor.obtener(d.id)?.estado != Estado.DESCARGANDO) return
            if (!miniaturaLista) guardarMiniatura(d.id, carpetaMini)?.let { ruta ->
                Gestor.actualizar(d.id) { it.copy(miniatura = ruta) }
            }
            Gestor.actualizar(d.id) { it.copy(mensaje = "Guardando...") }
            val guardados = kotlinx.coroutines.withContext(Dispatchers.IO) { moverADescargas(carpetaTemp) }
            if (guardados.isNotEmpty()) {
                listasEnSesion++
                Gestor.actualizar(d.id) {
                    it.copy(estado = Estado.LISTO, titulo = guardados.first().first, progreso = 100,
                        tamano = Gestor.formatoBytes(guardados.sumOf { g -> g.second }), velocidad = null,
                        mensaje = "✅ Guardado en Descargas/DescargaVideos")
                }
            } else {
                Gestor.actualizar(d.id) {
                    it.copy(estado = Estado.ERROR, mensaje = "❌ No se encontró el archivo descargado")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            YoutubeDL.getInstance().destroyProcessById(d.id)
            throw e
        } catch (e: Exception) {
            if (Gestor.obtener(d.id)?.estado != Estado.DESCARGANDO) return
            Gestor.actualizar(d.id) { it.copy(estado = Estado.ERROR, mensaje = mensajeError(e.message.orEmpty())) }
        } finally {
            kotlinx.coroutines.withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                carpetaTemp.deleteRecursively()
            }
        }
    }

    /** Copia la miniatura a una carpeta propia de la app (la temporal se borra al terminar). */
    private fun guardarMiniatura(id: String, carpeta: File): String? {
        val f = carpeta.listFiles()?.firstOrNull {
            it.isFile && it.length() > 0 && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")
        } ?: return null
        val destino = File(File(filesDir, "miniaturas").apply { mkdirs() }, "$id.${f.extension}")
        return runCatching { f.copyTo(destino, overwrite = true).absolutePath }.getOrNull()
    }

    private fun mensajeError(msg: String): String = when {
        msg.contains("DRM", true) -> "❌ Este sitio usa protección DRM y no se puede descargar"
        msg.contains("Unsupported URL", true) -> "❌ Sitio no compatible o el enlace no tiene video"
        msg.contains("429") -> "❌ El sitio frenó por demasiadas descargas a la vez. Reintenta en un rato."
        else -> "❌ " + (msg.lines().lastOrNull { it.isNotBlank() } ?: "Error desconocido") +
            "\nSi un sitio que antes servía falla, toca \"Actualizar motor\"."
    }

    private fun moverADescargas(carpeta: File): List<Pair<String, Long>> {
        val nombres = mutableListOf<Pair<String, Long>>()
        carpeta.listFiles()?.filter {
            it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".aria2") &&
                !it.name.endsWith(".ytdl") && !it.name.contains(".part-Frag")
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
                contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            } else {
                @Suppress("DEPRECATION")
                val destino = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "DescargaVideos"
                ).apply { mkdirs() }
                f.copyTo(File(destino, f.name), overwrite = true)
            }
            nombres.add(f.name to f.length())
        }
        return nombres
    }

    // ---------- Notificaciones y primer plano ----------

    private fun crearCanales() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CANAL, "Descargas en curso", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CANAL_FIN, "Descargas terminadas", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun abrirApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notificacion(lista: List<Descarga>): android.app.Notification {
        val activas = lista.filter { it.estado == Estado.DESCARGANDO }
        val enCola = lista.count { it.estado == Estado.EN_COLA }
        val conProgreso = activas.filter { it.progreso >= 0 }
        val promedio = if (conProgreso.isEmpty()) 0 else conProgreso.sumOf { it.progreso } / conProgreso.size
        val texto = buildString {
            append("${activas.size} descargando")
            if (enCola > 0) append(" · $enCola en cola")
        }
        val miniatura = activas.firstNotNullOfOrNull { it.miniatura }?.let { iconoNotificacion(it) }
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setLargeIcon(miniatura)
            .setContentTitle("Descarga Videos")
            .setContentText(texto)
            .setProgress(100, promedio, conProgreso.isEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrirApp())
            .build()
    }

    private var iconoRuta: String? = null
    private var iconoBmp: android.graphics.Bitmap? = null

    private fun iconoNotificacion(ruta: String): android.graphics.Bitmap? {
        if (ruta != iconoRuta) {
            iconoRuta = ruta
            iconoBmp = runCatching {
                android.graphics.BitmapFactory.decodeFile(ruta,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
            }.getOrNull()
        }
        return iconoBmp
    }

    private fun iniciarPrimerPlano() {
        val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notificacion(Gestor.descargas.value), tipo)
    }

    @SuppressLint("WakelockTimeout")
    private fun tomarLocks() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DescargaVideos:descargas")
            .apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DescargaVideos:wifi")
            .apply { acquire() }
    }

    private fun terminarServicio() {
        if (listasEnSesion > 0) {
            val n = listasEnSesion
            listasEnSesion = 0
            NotificationManagerCompat.from(this).notifySeguro(NOTIF_FIN_ID,
                NotificationCompat.Builder(this, CANAL_FIN)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Descargas terminadas")
                    .setContentText(if (n == 1) "1 archivo guardado en Descargas/DescargaVideos"
                        else "$n archivos guardados en Descargas/DescargaVideos")
                    .setAutoCancel(true)
                    .setContentIntent(abrirApp())
                    .build())
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        trabajos.keys.forEach { YoutubeDL.getInstance().destroyProcessById(it) }
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}

@SuppressLint("MissingPermission")
private fun NotificationManagerCompat.notifySeguro(id: Int, n: android.app.Notification) {
    if (areNotificationsEnabled()) notify(id, n)
}
