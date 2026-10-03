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
    /** El chip de video atiende una reescritura a la vez. */
    private val semaforoOptimizar = Semaphore(1)
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
            if (d.calidad != Calidad.AUDIO) {
                // H.264 + AAC: se reproduce y genera miniatura en cualquier teléfono
                addOption("-S", "vcodec:h264,acodec:m4a")
                // Contenedor MP4 real (los sitios por streaming entregan MPEG-TS)
                addOption("--remux-video", "mp4")
                addOption("--merge-output-format", "mp4")
                // Índice del MP4 al inicio: el video abre al instante
                addOption("--postprocessor-args", "Merger+ffmpeg_o:-movflags +faststart")
                addOption("--ppa", "FixupM3u8+ffmpeg_o:-movflags +faststart")
                addOption("--ppa", "VideoRemuxer+ffmpeg_o:-movflags +faststart")
            }
            fun video(alto: Int) =
                "bv*[vcodec^=avc][height<=$alto]+ba/b[vcodec^=avc][height<=$alto]/bv*[height<=$alto]+ba/b[height<=$alto]/b"
            when (d.calidad) {
                Calidad.AUDIO -> {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                }
                // "Mejor" con tope de 1080p: más alto traba el teléfono y no se nota en pantalla
                Calidad.MEJOR -> addOption("-f", video(1080))
                Calidad.P720 -> addOption("-f", video(720))
                Calidad.P480 -> addOption("-f", video(480))
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
            Gestor.actualizar(d.id) { it.copy(mensaje = "Revisando archivo...") }
            val principal = carpetaTemp.listFiles()?.filter { it.isFile }?.maxByOrNull { it.length() }
            if (principal != null && d.calidad != Calidad.AUDIO) optimizarSiHaceFalta(d, principal, carpetaTemp)
            if (Gestor.obtener(d.id)?.estado != Estado.DESCARGANDO) return
            val detalle = kotlinx.coroutines.withContext(Dispatchers.IO) {
                carpetaTemp.listFiles()?.filter { it.isFile }?.maxByOrNull { it.length() }?.let { analizar(it) }
            }
            Gestor.actualizar(d.id) { it.copy(mensaje = "Guardando...", detalle = detalle) }
            val guardados = kotlinx.coroutines.withContext(Dispatchers.IO) { moverADescargas(carpetaTemp) }
            if (guardados.isNotEmpty()) {
                listasEnSesion++
                Gestor.actualizar(d.id) {
                    it.copy(estado = Estado.LISTO, titulo = guardados.first().first, progreso = 100,
                        tamano = Gestor.formatoBytes(guardados.sumOf { g -> g.second }), velocidad = null,
                        mensaje = if (d.calidad == Calidad.AUDIO) "✅ Guardado en Música/DescargaVideos" else "✅ Guardado en la galería (Películas/DescargaVideos)")
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

    /**
     * Si el video trae pocos puntos de salto, lo reescribe con uno por segundo
     * para que adelantar/devolver sea instantáneo. Si algo falla, deja el original.
     */
    private suspend fun optimizarSiHaceFalta(d: Descarga, archivo: File, carpeta: File) {
        val info = kotlinx.coroutines.withContext(Dispatchers.IO) { Optimizador.medir(archivo) } ?: return
        val separacion = info.segundosEntreSaltos ?: return
        if (separacion <= Optimizador.UMBRAL_SEGUNDOS) return

        Gestor.actualizar(d.id) { it.copy(mensaje = "En cola para optimizar...", progreso = -1, velocidad = null) }
        semaforoOptimizar.withPermit {
            if (Gestor.obtener(d.id)?.estado != Estado.DESCARGANDO) return
            val salida = File(File(carpeta, "optimizado").apply { mkdirs() }, archivo.name)
            val ok = Optimizador.reescribir(applicationContext, archivo, salida, info.alto) { pct ->
                Gestor.actualizar(d.id) {
                    it.copy(progreso = pct, eta = 0, mensaje = "Optimizando para adelantar $pct%")
                }
            }
            if (ok) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    archivo.delete()
                    salida.renameTo(archivo)
                }
            } else {
                Gestor.actualizar(d.id) { it.copy(mensaje = "No se pudo optimizar; se guarda el original") }
            }
        }
    }

    /** Formato real del archivo: contenedor, códec y resolución. */
    private fun analizar(f: File): String = runCatching {
        val cabecera = ByteArray(12)
        f.inputStream().use { it.read(cabecera) }
        val contenedor = when {
            cabecera[0] == 0x47.toByte() -> "⚠️ MPEG-TS"
            String(cabecera, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "MP4"
            else -> f.extension.uppercase()
        }
        val partes = mutableListOf(contenedor)
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                val codec = when {
                    mime.contains("avc") -> "H.264"
                    mime.contains("hevc") -> "⚠️ H.265"
                    mime.contains("vp9") -> "⚠️ VP9"
                    mime.contains("av01") -> "⚠️ AV1"
                    mime.contains("mp4a") -> "AAC"
                    mime.contains("mpeg") && mime.startsWith("audio") -> "MP3"
                    else -> mime.substringAfter("/")
                }
                partes += if (mime.startsWith("video/"))
                    "$codec ${fmt.getInteger(android.media.MediaFormat.KEY_WIDTH)}×${fmt.getInteger(android.media.MediaFormat.KEY_HEIGHT)}"
                else codec
            }
        } finally {
            ex.release()
        }
        Optimizador.medir(f)?.segundosEntreSaltos?.let { seg ->
            partes += if (seg > Optimizador.UMBRAL_SEGUNDOS) "⚠️ salto c/%.0fs".format(seg) else "salto c/%.0fs".format(seg)
        }
        partes.joinToString(" · ")
    }.getOrDefault("formato desconocido")

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
                val (coleccion, carpetaPublica) = when {
                    mime.startsWith("video/") ->
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
                    mime.startsWith("audio/") ->
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
                }
                val valores = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$carpetaPublica/DescargaVideos")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(coleccion, valores) ?: return@forEach
                contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                // Al quitar "pendiente", la galería lo escanea y crea la miniatura
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } else {
                @Suppress("DEPRECATION")
                val tipo = when {
                    mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
                    mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
                    else -> Environment.DIRECTORY_DOWNLOADS
                }
                val destino = File(Environment.getExternalStoragePublicDirectory(tipo), "DescargaVideos")
                    .apply { mkdirs() }
                val final = f.copyTo(File(destino, f.name), overwrite = true)
                android.media.MediaScannerConnection.scanFile(this, arrayOf(final.absolutePath), arrayOf(mime), null)
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
                    .setContentText(if (n == 1) "1 archivo guardado en la galería"
                        else "$n archivos guardados en la galería")
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
