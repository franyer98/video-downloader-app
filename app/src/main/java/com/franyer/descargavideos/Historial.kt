package com.franyer.descargavideos

import android.content.Context
import android.net.Uri

/**
 * Recuerda los videos ya descargados para no bajarlos dos veces.
 * Guarda el enlace normalizado y, cuando se conoce, el id del video en el sitio
 * (así detecta el mismo video aunque el enlace cambie un poco).
 */
object Historial {

    private const val PREFS = "historial"
    private const val CLAVE = "descargados"

    /** Enlace sin "www.", "m.", "#…" ni "/" final, para comparar. */
    fun normalizar(url: String): String = runCatching {
        val u = Uri.parse(url.trim())
        val host = u.host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.")
        val ruta = u.path.orEmpty().trimEnd('/')
        val query = u.query?.let { "?$it" }.orEmpty()
        "url:$host$ruta$query"
    }.getOrDefault("url:" + url.trim())

    /** Id del video en el sitio, por ejemplo "id:youtube:dQw4w9WgXcQ". */
    fun claveVideo(extractor: String?, id: String?): String? =
        if (extractor.isNullOrBlank() || id.isNullOrBlank()) null else "id:${extractor.lowercase()}:$id"

    private fun conjunto(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(CLAVE, emptySet()).orEmpty()

    fun yaDescargado(ctx: Context, vararg claves: String?): Boolean {
        val s = conjunto(ctx)
        return claves.any { it != null && it in s }
    }

    fun registrar(ctx: Context, vararg claves: String?) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nuevo = conjunto(ctx).toMutableSet().apply { claves.filterNotNull().forEach { add(it) } }
        prefs.edit().putStringSet(CLAVE, nuevo).apply()
    }
}
