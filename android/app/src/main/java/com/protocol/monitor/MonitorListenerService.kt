package com.protocol.monitor

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.protocol.engine.Signal
import com.protocol.service.ReminderService

/**
 * Служба чтения уведомлений. ARCHITECTURE 4.4. Логики нет: фильтр по пакету,
 * разбор MessagingStyle, передача сигнала сервису. Решает движок.
 * Систему просят включить доступ руками: Настройки, Доступ к уведомлениям.
 */
class MonitorListenerService : NotificationListenerService() {

    companion object {
        /** Пакет → источник в конфиге. */
        val SOURCES = mapOf(
            "com.whatsapp" to "whatsapp",
            "com.whatsapp.w4b" to "whatsapp",
            "com.viber.voip" to "viber",
        )

        fun enabled(ctx: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

        fun component(ctx: Context) = ComponentName(ctx, MonitorListenerService::class.java)
    }

    private lateinit var inbox: SignalInbox

    override fun onCreate() {
        super.onCreate()
        inbox = SignalInbox(this)
    }

    /** При подключении забрать то, что ещё висит в шторке: добор после простоя. */
    override fun onListenerConnected() {
        val list = try { activeNotifications?.toList() } catch (_: Exception) { null } ?: return
        var any = false
        for (sbn in list) any = handle(sbn) || any
        if (any) poke()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (handle(sbn)) poke()
    }

    /** Разобрать уведомление в сигналы и положить в очередь. true: что-то положено. */
    private fun handle(sbn: StatusBarNotification): Boolean {
        val source = SOURCES[sbn.packageName] ?: return false
        val n = sbn.notification ?: return false
        // Сводки «N сообщений из M чатов» содержимого не несут.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val signals = parse(source, n)
        for (s in signals) inbox.append(s)
        return signals.isNotEmpty()
    }

    private fun parse(source: String, n: Notification): List<Signal> {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null) {
            // Беседа: заголовок группы; у личного чата заголовка нет, тогда имя собеседника.
            val chat = style.conversationTitle?.toString()?.trim().orEmpty()
                .ifEmpty { n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty() }
            if (chat.isEmpty()) return emptyList()
            return style.messages.mapNotNull { m ->
                val text = m.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) null
                else Signal(source, chat, m.person?.name?.toString()?.trim().orEmpty(), text, m.timestamp)
            }
        }
        // Запасной путь без MessagingStyle: заголовок как беседа, текст «Отправитель: сообщение».
        val extras = n.extras ?: return emptyList()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() || text.isEmpty()) return emptyList()
        val sep = text.indexOf(": ")
        val sender = if (sep in 1..40) text.substring(0, sep) else ""
        val body = if (sep in 1..40) text.substring(sep + 2) else text
        return listOf(Signal(source, title, sender, body, n.`when`))
    }

    /**
     * Попросить сервис разобрать очередь. Только если он уже живёт в этом процессе:
     * поднимать его из фона система может запретить, а очередь и так дождётся
     * ближайшего старта по будильнику или открытию приложения.
     */
    private fun poke() {
        if (!ReminderService.alive) return
        try {
            startService(ReminderService.intent(this, ReminderService.ACTION_SIGNALS))
        } catch (_: Exception) { }
    }
}
