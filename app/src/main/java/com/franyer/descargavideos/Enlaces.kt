package com.franyer.descargavideos

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/**
 * Convierte enlaces cortos de compartir en el enlace real del video, que el motor sí reconoce.
 * Ej.: facebook.com/share/r/1DrGoefAfx/ → facebook.com/reel/123456789
 */
object Enlaces {

    private const val NAVEGADOR =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"

    private fun esFacebook(host: String) =
        host.endsWith("facebook.com") || host == "fb.watch" || host.endsWith(".fb.watch") || host == "fb.me"

    /** Enlaces que hay que seguir para llegar al video. */
    private fun necesitaResolver(u: Uri): Boolean {
        val host = u.host.orEmpty().lowercase()
        val ruta = u.path.orEmpty()
        return (host.endsWith("facebook.com") && (ruta.startsWith("/share/") || ruta.startsWith("/story.php"))) ||
            host == "fb.watch" || host.endsWith(".fb.watch") || host == "fb.me"
    }

    /** Devuelve el enlace real; si no puede resolverlo, devuelve el mismo. Corre en segundo plano. */
    fun resolver(url: String): String {
        val original = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return url
        if (!necesitaResolver(original)) return url
        return runCatching {
            var actual = url.trim()
            for (paso in 1..8) {
                val con = (URL(actual).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", NAVEGADOR)
                    setRequestProperty("Accept-Language", "es-CO,es;q=0.9,en;q=0.8")
                }
                val codigo = con.responseCode
                val destino = con.getHeaderField("Location")
                if (codigo in 200..299) {
                    // A veces Facebook no redirige sino que entrega la página: el video viene en og:url
                    val html = con.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(400_000)
                        val n = r.read(buf)
                        if (n > 0) String(buf, 0, n) else ""
                    }
                    con.disconnect()
                    Regex("""(?:og:url|canonical)"[^>]*?(?:content|href)="([^"]+)"""").find(html)
                        ?.groupValues?.get(1)?.replace("&amp;", "&")
                        ?.let { limpio(it) }?.let { return it }
                    break
                }
                con.disconnect()
                // Sin más redirecciones: llegamos al final
                if (codigo !in 300..399 || destino.isNullOrBlank()) break
                actual = URL(URL(actual), destino).toString()
                limpio(actual)?.let { return it }
            }
            limpio(actual) ?: url
        }.getOrDefault(url)
    }

    /**
     * Si el enlace ya apunta a un video de Facebook, lo deja limpio (sin rastreo).
     * Si Facebook mandó a la página de inicio de sesión, saca el video del parámetro "next".
     */
    private fun limpio(enlace: String): String? {
        val u = Uri.parse(enlace)
        val host = u.host.orEmpty().lowercase()
        if (!esFacebook(host)) return null
        u.getQueryParameter("next")?.let { siguiente -> return limpio(siguiente) }
        val ruta = u.path.orEmpty()
        Regex("""/reel/(\d+)""").find(ruta)?.let { return "https://www.facebook.com/reel/${it.groupValues[1]}" }
        Regex("""/videos/(?:[^/]+/)?(\d+)""").find(ruta)?.let { return "https://www.facebook.com/watch/?v=${it.groupValues[1]}" }
        if (ruta.startsWith("/watch")) u.getQueryParameter("v")?.let { return "https://www.facebook.com/watch/?v=$it" }
        if (ruta.startsWith("/share/") || ruta.contains("login")) return null
        return enlace
    }
}
