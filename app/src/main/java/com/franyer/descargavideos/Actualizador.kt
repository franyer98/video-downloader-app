package com.franyer.descargavideos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Busca versiones nuevas en GitHub Releases, baja el APK en segundo plano
 * y lo deja listo para instalar con un toque.
 */
object Actualizador {
    private const val BASE = "https://github.com/franyer98/video-downloader-app/releases/download/latest"
    private const val URL_VERSION = "$BASE/version.json"
    private const val URL_APK = "$BASE/DescargaVideos.apk"

    data class Nueva(val versionCode: Long, val versionName: String, val apk: File)

    fun versionActual(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    /** Devuelve la versión nueva ya descargada, o null si estás al día o falla la red. */
    suspend fun buscar(ctx: Context): Nueva? = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(leerTexto(URL_VERSION))
            val codigo = json.getLong("versionCode")
            val nombre = json.optString("versionName", codigo.toString())
            if (codigo <= versionActual(ctx)) return@runCatching null

            val carpeta = File(ctx.cacheDir, "actualizacion").apply { mkdirs() }
            val apk = File(carpeta, "DescargaVideos-$codigo.apk")
            if (!apk.exists() || apk.length() < 1_000_000) {
                carpeta.listFiles()?.forEach { it.delete() }
                val temp = File(carpeta, "bajando.tmp")
                abrir(URL_APK).inputStream.use { entrada ->
                    temp.outputStream().use { entrada.copyTo(it) }
                }
                temp.renameTo(apk)
            }
            Nueva(codigo, nombre, apk)
        }.getOrNull()
    }

    /** Abre el instalador de Android. Si falta el permiso, abre el ajuste para darlo. */
    fun instalar(ctx: Context, nueva: Nueva) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.archivos", nueva.apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun abrir(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Cache-Control", "no-cache")
            if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode")
        }

    private fun leerTexto(url: String) = abrir(url).inputStream.bufferedReader().use { it.readText() }
}
