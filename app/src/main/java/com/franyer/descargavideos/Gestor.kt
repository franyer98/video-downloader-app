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

enum class Calidad { MEJOR, P720, P480, AUDIO }

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
}
