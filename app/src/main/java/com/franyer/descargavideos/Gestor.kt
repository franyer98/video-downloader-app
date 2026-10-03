package com.franyer.descargavideos

import android.content.Context
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

enum class Calidad { MEJOR, AUDIO }

enum class Estado { EN_COLA, DESCARGANDO, LISTO, ERROR, CANCELADO }

data class Descarga(
    val id: String,
    val url: String,
    val calidad: Calidad,
    val titulo: String = url,
    val estado: Estado = Estado.EN_COLA,
    val progreso: Int = -1,
    val eta: Long = 0,
    val mensaje: String = "En cola",
    /** Ruta local de la miniatura del video, cuando ya se bajó. */
    val miniatura: String? = null,
    /** Peso total del archivo, p. ej. "123.4 MB" (estimado mientras descarga). */
    val tamano: String? = null,
    /** Velocidad actual, p. ej. "2.3 MB/s". */
    val velocidad: String? = null,
    /** Formato real del archivo terminado, p. ej. "MP4 · H.264 1920×1080 · AAC". */
    val detalle: String? = null,
) {
    val pendiente get() = estado == Estado.EN_COLA || estado == Estado.DESCARGANDO
}

/** Estado compartido entre la pantalla y el servicio de descargas. */
object Gestor {
    /** Descargas que corren al mismo tiempo; las demás esperan en cola. */
    const val MAX_SIMULTANEAS = 10

    /** Fragmentos que cada descarga baja en paralelo (videos HLS/DASH). */
    const val FRAGMENTOS_PARALELOS = 8

    private val _descargas = MutableStateFlow<List<Descarga>>(emptyList())
    val descargas: StateFlow<List<Descarga>> = _descargas

    private val contador = AtomicInteger(0)
    private val mutexMotor = Mutex()
    @Volatile private var motorIniciado = false

    suspend fun asegurarMotor(ctx: Context) = mutexMotor.withLock {
        if (!motorIniciado) {
            val app = ctx.applicationContext
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            Aria2c.getInstance().init(app)
            motorIniciado = true
        }
    }

    fun nueva(url: String, calidad: Calidad): Descarga {
        val d = Descarga("descarga-${contador.incrementAndGet()}", url, calidad)
        _descargas.update { listOf(d) + it }
        return d
    }

    fun obtener(id: String): Descarga? = _descargas.value.firstOrNull { it.id == id }

    fun actualizar(id: String, cambio: (Descarga) -> Descarga) {
        _descargas.update { lista -> lista.map { if (it.id == id) cambio(it) else it } }
    }

    fun quitar(id: String) {
        _descargas.update { lista -> lista.filterNot { it.id == id } }
    }

    fun limpiarTerminadas() {
        _descargas.update { lista -> lista.filter { it.pendiente || it.estado == Estado.ERROR } }
    }

    private val reTotalYtdlp = Regex("""of\s+~?\s*([\d.]+\s*[KMGT]?i?B)\b""")
    private val reVelYtdlp = Regex("""at\s+([\d.]+\s*[KMGT]?i?B/s)""")
    private val reAria = Regex("""([\d.]+[KMGT]?i?B)/([\d.]+[KMGT]?i?B)\(""")
    private val reVelAria = Regex("""DL:([\d.]+[KMGT]?i?B)""")

    /** Saca peso total y velocidad de una línea de progreso de yt-dlp o aria2c. */
    fun leerProgreso(linea: String): Pair<String?, String?> {
        val total = reTotalYtdlp.find(linea)?.groupValues?.get(1)
            ?: reAria.find(linea)?.groupValues?.get(2)
        val vel = reVelYtdlp.find(linea)?.groupValues?.get(1)
            ?: reVelAria.find(linea)?.groupValues?.get(1)?.let { "$it/s" }
        return limpiar(total) to limpiar(vel)
    }

    private fun limpiar(t: String?) = t?.replace("iB", "B")?.replace(" ", "")?.replace(Regex("([\\d.]+)"), "$1 ")?.trim()

    fun formatoBytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.2f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format("%.1f MB", b / (1L shl 20).toDouble())
        else -> String.format("%.0f KB", b / 1024.0)
    }
}
