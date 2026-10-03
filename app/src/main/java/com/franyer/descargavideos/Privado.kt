package com.franyer.descargavideos

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Carpeta privada: vive en el almacenamiento interno de la app, así que no la ven
 * la galería, el gestor de archivos ni otras apps. Se abre con huella o PIN.
 */
object Privado {

    /** true mientras la carpeta está desbloqueada; se cierra al salir de la app. */
    @Volatile var sesionActiva = false

    fun carpeta(ctx: Context) = File(ctx.filesDir, "privado").apply { mkdirs() }
    private fun carpetaMiniaturas(ctx: Context) = File(ctx.filesDir, "privado_mini").apply { mkdirs() }

    fun activado(ctx: Context) =
        ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getBoolean("guardar_privado", false)

    fun activar(ctx: Context, si: Boolean) =
        ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).edit().putBoolean("guardar_privado", si).apply()

    fun videos(ctx: Context): List<File> =
        carpeta(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

    /** Mueve los archivos terminados de la carpeta temporal a la privada. */
    fun guardar(ctx: Context, temp: File): List<Pair<String, Long>> {
        val destino = carpeta(ctx)
        return temp.listFiles()?.filter {
            it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".aria2") &&
                !it.name.endsWith(".ytdl") && !it.name.contains(".part-Frag")
        }?.map { f ->
            var final = File(destino, f.name)
            var n = 1
            while (final.exists()) final = File(destino, "${f.nameWithoutExtension}_${n++}.${f.extension}")
            if (!f.renameTo(final)) {
                f.copyTo(final)
                f.delete()
            }
            final.name to final.length()
        }.orEmpty()
    }

    /** Cuadro del video para la lista, guardado también dentro de la app. */
    fun miniatura(ctx: Context, video: File): File? {
        val mini = File(carpetaMiniaturas(ctx), video.name + ".jpg")
        if (mini.exists()) return mini
        return runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(video.absolutePath)
                val bmp = r.getFrameAtTime(5_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
                val ancho = 320
                val escalado = Bitmap.createScaledBitmap(bmp, ancho, ancho * bmp.height / bmp.width, true)
                mini.outputStream().use { escalado.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                mini
            } finally {
                r.release()
            }
        }.getOrNull()
    }

    /**
     * Copia un video elegido en el selector a la carpeta privada y borra el original.
     * Devuelve null si falló la copia; true/false indica si se pudo borrar el original.
     */
    fun importar(ctx: Context, uri: android.net.Uri): Boolean? {
        val nombre = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: "video_${System.currentTimeMillis()}.mp4"
        val base = File(nombre).nameWithoutExtension
        val ext = File(nombre).extension.ifBlank { "mp4" }
        var destino = File(carpeta(ctx), "$base.$ext")
        var n = 1
        while (destino.exists()) destino = File(carpeta(ctx), "${base}_${n++}.$ext")

        val copiado = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { entrada ->
                destino.outputStream().use { entrada.copyTo(it) }
            } ?: error("sin acceso")
            true
        }.getOrDefault(false)
        if (!copiado || destino.length() == 0L) {
            destino.delete()
            return null
        }
        return runCatching { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
            .getOrDefault(false)
    }

    fun eliminar(ctx: Context, video: File) {
        video.delete()
        File(carpetaMiniaturas(ctx), video.name + ".jpg").delete()
    }

    /** Copia el video a la galería (Películas/DescargaVideos) y lo quita de la carpeta privada. */
    fun pasarAGaleria(ctx: Context, video: File): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val valores = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, video.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DescargaVideos")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), valores
            ) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { out -> video.inputStream().use { it.copyTo(out) } }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else {
            @Suppress("DEPRECATION")
            val destino = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "DescargaVideos")
                .apply { mkdirs() }
            val final = video.copyTo(File(destino, video.name), overwrite = true)
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(final.absolutePath), arrayOf("video/mp4"), null)
        }
        eliminar(ctx, video)
        true
    }.getOrDefault(false)
}
