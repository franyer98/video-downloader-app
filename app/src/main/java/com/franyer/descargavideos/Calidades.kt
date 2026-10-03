package com.franyer.descargavideos

import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo

/** Una opción de calidad que el usuario puede elegir para un video. */
data class OpcionCalidad(
    val etiqueta: String,
    val calidad: Calidad,
    /** Selector de formato para yt-dlp; null = el mejor disponible. */
    val formato: String? = null,
)

/** Arma la lista de calidades disponibles a partir de la información del video. */
object Calidades {

    private val reAlto = Regex("""(\d{3,4})p""")

    /** Alto del formato: el campo height o, si viene vacío, el "1080p" del id o la nota. */
    private fun alto(f: VideoFormat): Int =
        f.height.takeIf { it > 0 }
            ?: listOfNotNull(f.formatId, f.formatNote, f.format)
                .firstNotNullOfOrNull { reAlto.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: 0

    private fun esSoloAudio(f: VideoFormat) = f.vcodec == "none"
    private fun tieneAudio(f: VideoFormat) = f.acodec != null && f.acodec != "none"

    /** Peso en bytes: el informado, el aproximado o estimado por bitrate × duración. */
    private fun peso(f: VideoFormat, duracion: Int): Long = when {
        f.fileSize > 0 -> f.fileSize
        f.fileSizeApproximate > 0 -> f.fileSizeApproximate
        f.tbr > 0 && duracion > 0 -> f.tbr.toLong() * 1000 / 8 * duracion
        else -> 0
    }

    fun opciones(info: VideoInfo): List<OpcionCalidad> {
        val formatos = info.formats.orEmpty()
        val duracion = info.duration

        val mejorAudio = formatos.filter(::esSoloAudio).maxByOrNull { maxOf(it.abr, it.tbr) }
        val pesoAudio = mejorAudio?.let { peso(it, duracion) } ?: 0

        // El mejor formato por cada resolución, prefiriendo H.264
        val porAlto = formatos
            .filter { !esSoloAudio(it) && alto(it) > 0 && !it.formatId.isNullOrBlank() }
            .groupBy(::alto)
            .mapValues { (_, lista) ->
                lista.maxWith(compareBy<VideoFormat> { it.vcodec?.startsWith("avc") == true }.thenBy { it.tbr })
            }
            .toSortedMap(compareByDescending { it })

        val opciones = mutableListOf<OpcionCalidad>()
        porAlto.entries.forEachIndexed { i, (h, f) ->
            val conAudio = tieneAudio(f)
            val total = peso(f, duracion) + if (conAudio) 0 else pesoAudio
            val etiqueta = buildString {
                append("${h}p")
                if (total > 0) append(" · ~${Gestor.formatoBytes(total)}")
                if (i == 0) append("  (máxima)")
            }
            val id = f.formatId!!
            val selector = if (conAudio) id else "$id+ba/$id"
            opciones += OpcionCalidad(etiqueta, Calidad.MEJOR, selector)
        }

        if (opciones.isEmpty()) opciones += OpcionCalidad("Mejor disponible", Calidad.MEJOR)

        opciones += OpcionCalidad(
            "Solo audio (MP3)" + if (pesoAudio > 0) " · ~${Gestor.formatoBytes(pesoAudio)}" else "",
            Calidad.AUDIO,
        )
        return opciones
    }
}
