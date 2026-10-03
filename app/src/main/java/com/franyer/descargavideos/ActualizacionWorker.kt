package com.franyer.descargavideos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Revisa en segundo plano cada pocas horas. Si hay versión nueva, la descarga
 * y avisa con una notificación: un toque y se instala.
 */
class ActualizacionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val nueva = Actualizador.buscar(applicationContext) ?: return Result.success()
        avisar(applicationContext, nueva)
        return Result.success()
    }

    companion object {
        private const val CANAL = "actualizaciones"
        private const val NOTIF_ID = 3

        fun programar(ctx: Context) {
            val pedido = PeriodicWorkRequestBuilder<ActualizacionWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "buscar_actualizacion", ExistingPeriodicWorkPolicy.KEEP, pedido
            )
        }

        /** Notificación "Nueva versión lista": al tocarla abre el instalador directamente. */
        fun avisar(ctx: Context, nueva: Actualizador.Nueva) {
            val prefs = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
            if (prefs.getLong("version_avisada", 0) == nueva.versionCode) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CANAL, "Actualizaciones de la app", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val abrir = PendingIntent.getActivity(
                ctx, 10,
                Intent(ctx, InstalarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Descarga Videos ${nueva.versionName} lista")
                .setContentText("Toca para instalar la actualización")
                .setAutoCancel(true)
                .setContentIntent(abrir)
                .build()
            val nm = NotificationManagerCompat.from(ctx)
            if (nm.areNotificationsEnabled()) {
                @Suppress("MissingPermission")
                nm.notify(NOTIF_ID, n)
                prefs.edit().putLong("version_avisada", nueva.versionCode).apply()
            }
        }
    }
}
