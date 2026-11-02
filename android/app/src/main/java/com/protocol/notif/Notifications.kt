package com.protocol.notif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.protocol.R
import com.protocol.engine.Screen
import com.protocol.receiver.NotificationActionReceiver
import com.protocol.ui.MainActivity
import com.protocol.ui.StepActivity

object Notifications {
    const val CH_SERVICE = "service"
    const val CH_ALERT = "alert"
    const val CH_WAIT = "wait"

    const val ID_SERVICE = 1
    const val ID_ALERT = 2
    const val ID_STOPPED = 3
    private const val ID_WAIT_BASE = 100

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, ctx.getString(R.string.channel_service_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = ctx.getString(R.string.channel_service_desc)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, ctx.getString(R.string.channel_alert_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.channel_alert_desc)
            // Приложение беззвучное: без звука и вибрации на всех каналах.
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_WAIT, ctx.getString(R.string.channel_wait_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = ctx.getString(R.string.channel_wait_desc)
        })
    }

    fun service(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(mainIntent(ctx))
            .build()

    /**
     * Уведомление активного шага или процесса. Нельзя смахнуть.
     * Шаг (procId == null): полноэкранное, открывает экран шага.
     * Процесс: обычное, открывает главный экран, где процесс и идёт.
     */
    fun alert(ctx: Context, screen: Screen, procId: String?): Notification {
        val isStep = procId == null
        val open = Intent(ctx, if (isStep) StepActivity::class.java else MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(ctx, 10, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(screen.title)
            .setContentText(screen.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(screen.text))
            .setOngoing(true)
            .setAutoCancel(false)
            // Категория «будильник» и у процессов: в режиме «Не беспокоить» напоминания
            // обычно подавляются, а будильники показываются всплывающими.
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pi)
        if (isStep) b.setFullScreenIntent(pi, true)
        if (screen is Screen.YesNo) {
            b.addAction(0, ctx.getString(R.string.btn_yes), action(ctx, NotificationActionReceiver.ACTION_YES, procId, 11))
            b.addAction(0, ctx.getString(R.string.btn_no), action(ctx, NotificationActionReceiver.ACTION_NO, procId, 12))
        }
        return b.build()
    }

    fun wait(ctx: Context, procId: String, screen: Screen.Wait): Notification {
        val open = Intent(ctx, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val pi = PendingIntent.getActivity(ctx, waitId(procId), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, CH_WAIT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(screen.title)
            .setContentText(screen.text)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(0, screen.button, action(ctx, NotificationActionReceiver.ACTION_RESUME, procId, waitId(procId) + 1000))
            .build()
    }

    fun stopped(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.stopped_title))
            .setContentText(ctx.getString(R.string.notif_stopped_text))
            .setContentIntent(mainIntent(ctx))
            .build()

    fun waitId(procId: String): Int = ID_WAIT_BASE + (procId.hashCode() and 0x7fff)

    private fun mainIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun action(ctx: Context, action: String, procId: String?, req: Int): PendingIntent {
        val i = Intent(ctx, NotificationActionReceiver::class.java).setAction(action)
        procId?.let { i.putExtra(NotificationActionReceiver.EXTRA_PROC_ID, it) }
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
