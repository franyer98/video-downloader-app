package com.franyer.descargavideos

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Reescribe un video con un punto de salto (keyframe) por segundo usando el
 * codificador por hardware del teléfono, para que adelantar/devolver sea instantáneo.
 */
object Optimizador {

    /** Por encima de esto (segundos entre keyframes) vale la pena reescribir. */
    const val UMBRAL_SEGUNDOS = 3.0

    /**
     * @param segundosEntreSaltos separación promedio entre keyframes; null si no se pudo medir.
     * @param inicioRaro true si el video arranca con tiempos negativos (típico de remuxes
     *   de streaming); esos archivos tardan en abrir y conviene reescribirlos.
     */
    data class InfoVideo(val alto: Int, val segundosEntreSaltos: Double?, val inicioRaro: Boolean) {
        val necesitaOptimizar
            get() = inicioRaro || segundosEntreSaltos == null || segundosEntreSaltos > UMBRAL_SEGUNDOS
    }

    /** Mide los primeros 90 s del video: alto, separación entre keyframes y tiempo inicial. */
    fun medir(f: File): InfoVideo? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            val pista = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return@runCatching null
            val alto = ex.getTrackFormat(pista).getInteger(MediaFormat.KEY_HEIGHT)
            ex.selectTrack(pista)

            // sampleTime devuelve -1 al final; otros negativos son tiempos reales (antes se cortaba ahí)
            val primeroUs = ex.sampleTime
            val limiteUs = (if (primeroUs == -1L) 0L else primeroUs) + 90_000_000L
            var saltos = 0
            var ultimoUs = primeroUs
            var muestras = 0
            while (true) {
                val t = ex.sampleTime
                if (t == -1L || t > limiteUs) break
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) saltos++
                ultimoUs = t
                muestras++
                if (!ex.advance()) break
            }
            val duracionUs = ultimoUs - primeroUs
            val segundos = if (saltos > 0 && muestras > 1 && duracionUs > 0) duracionUs / 1_000_000.0 / saltos else null
            InfoVideo(alto, segundos, inicioRaro = primeroUs < 0)
        } finally {
            ex.release()
        }
    }.getOrNull()

    /**
     * Reescribe [entrada] en [salida] informando el avance (0-100). Devuelve true si salió bien.
     * Media3 exige el hilo principal; el trabajo pesado lo hace el chip de video.
     */
    suspend fun reescribir(
        ctx: Context,
        entrada: File,
        salida: File,
        alto: Int,
        alProgresar: (Int) -> Unit,
    ): Boolean = withContext(Dispatchers.Main) {
        salida.delete()
        val holder = ProgressHolder()
        var t: Transformer? = null
        val sondeo = launch {
            while (isActive) {
                t?.let {
                    if (it.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) alProgresar(holder.progress)
                }
                delay(700)
            }
        }
        try {
            suspendCancellableCoroutine<Boolean> { cont ->
                val nuevo = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(ctx)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder().setiFrameIntervalSeconds(1f).build()
                            )
                            .build()
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            if (cont.isActive) cont.resume(false)
                        }
                    })
                    .build()
                t = nuevo
                val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(entrada)))
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(alto))))
                    .build()
                nuevo.start(item, salida.absolutePath)
                cont.invokeOnCancellation { nuevo.cancel() }
            } && salida.exists() && salida.length() > 0
        } finally {
            sondeo.cancel()
        }
    }.also { ok -> if (!ok) salida.delete() }
}
