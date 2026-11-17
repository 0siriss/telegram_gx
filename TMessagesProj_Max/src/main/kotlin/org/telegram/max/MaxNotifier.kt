package org.telegram.max

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.telegram.messenger.ApplicationLoader

/**
 * Уведомления о входящих MAX. Настоящий push для нашей сборки невозможен (сервер шлёт
 * их через Firebase-проект официального приложения), поэтому уведомление показываем
 * сами, пока живо соединение — его держит переключатель удержания в настройках GramBas.
 * Своё звено уведомлений, чтобы их можно было отключить отдельно от Telegram.
 */
object MaxNotifier {

    private const val CHANNEL = "max_messages"
    private var channelReady = false

    fun show(chatId: Long, message: MaxMessage) {
        try {
            val context = ApplicationLoader.applicationContext ?: return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(manager)
            val chat = MaxClient.chatById(chatId)
            val title = chat?.title ?: "MAX"
            val author = MaxContacts.get(message.senderId)?.name
            val text = if (chat != null && !chat.isDialog && author != null) "$author: ${message.displayText}" else message.displayText

            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent().setPackage(context.packageName)
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val pending = PendingIntent.getActivity(context, chatId.toInt(), launch, flags)

            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(context, CHANNEL)
            } else {
                android.app.Notification.Builder(context)
            }
            builder
                .setSmallIcon(org.telegram.messenger.R.drawable.notification)
                .setContentTitle("MAX · $title")
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setWhen(message.time)
                .setShowWhen(true)
            manager.notify("max", chatId.toInt(), builder.build())
        } catch (e: Throwable) {
            MaxLog.e("уведомление MAX не показано", e)
        }
    }

    /** Убирает уведомление чата, когда его открыли. */
    fun clear(chatId: Long) {
        try {
            val context = ApplicationLoader.applicationContext ?: return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel("max", chatId.toInt())
        } catch (ignored: Throwable) {
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (channelReady || Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL, "Сообщения MAX", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Входящие из раздела MAX, пока приложение на связи"
        manager.createNotificationChannel(channel)
        channelReady = true
    }
}
