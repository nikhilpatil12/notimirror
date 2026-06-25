package com.notimirror.service

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.notimirror.MainActivity
import com.notimirror.R
import com.notimirror.data.IPhoneNotification
import com.notimirror.utils.AppNameFormatter

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_GROUP_ID = "iphone_apps"
        const val CHANNEL_GROUP_NAME = "iPhone Apps"
        private const val CHANNEL_PREFIX = "iphone_"
        private const val NOTIFICATION_BASE_ID = 10000
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val createdChannels = mutableSetOf<String>()

    init {
        createChannelGroup()
    }

    private fun createChannelGroup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val group = NotificationChannelGroup(CHANNEL_GROUP_ID, CHANNEL_GROUP_NAME)
            notificationManager.createNotificationChannelGroup(group)
        }
    }

    private fun getOrCreateChannelForApp(appIdentifier: String): String {
        val channelId = CHANNEL_PREFIX + appIdentifier.replace(".", "_")

        if (channelId !in createdChannels && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Extract and format app name from bundle ID
            val appName = AppNameFormatter.format(appIdentifier)

            // Determine if this is a messaging app that should have higher importance
            val isMessagingApp = appIdentifier in listOf(
                "com.apple.MobileSMS",
                "net.whatsapp.WhatsApp",
                "com.facebook.Messenger",
                "ph.telegra.Telegraph",
                "com.viber.Viber",
                "com.discord.Discord",
                "com.slack.Slack"
            )

            val channel = NotificationChannel(
                channelId,
                appName,
                if (isMessagingApp) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications from $appName on iPhone"
                group = CHANNEL_GROUP_ID
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }

            notificationManager.createNotificationChannel(channel)
            createdChannels.add(channelId)
        }

        return channelId
    }

    fun showNotification(notification: IPhoneNotification) {
        // Check if we have permission to post notifications
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Get or create a channel for this specific app
        val channelId = getOrCreateChannelForApp(notification.appIdentifier)

        // Build the notification title
        val title = when {
            notification.title.isNotBlank() -> notification.title
            notification.appIdentifier.isNotBlank() -> notification.appIdentifier.substringAfterLast(".")
            else -> "iPhone Notification"
        }

        // Build the notification text
        val text = when {
            notification.message.isNotBlank() -> notification.message
            notification.subtitle.isNotBlank() -> notification.subtitle
            else -> "New notification from iPhone"
        }

        // Select appropriate icon based on category
        val icon = when (notification.categoryId.name) {
            "IncomingCall", "MissedCall" -> android.R.drawable.stat_sys_phone_call
            "Email" -> android.R.drawable.sym_action_email
            "Social" -> android.R.drawable.ic_menu_share
            else -> R.drawable.ic_notification
        }

        // Determine if this is a messaging notification for Android Auto
        val isMessagingApp = notification.appIdentifier in listOf(
            "com.apple.MobileSMS",
            "net.whatsapp.WhatsApp",
            "com.facebook.Messenger",
            "ph.telegra.Telegraph",
            "com.viber.Viber",
            "com.discord.Discord",
            "com.slack.Slack",
            "com.burbn.instagram",
            "com.toyopagroup.picaboo",
            "com.atebits.Tweetie2",
            "com.linkedin.LinkedIn"
        ) || notification.categoryId.name == "Social"

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(
                if (isMessagingApp || notification.eventFlags.isImportant)
                    NotificationCompat.PRIORITY_HIGH
                else
                    NotificationCompat.PRIORITY_DEFAULT
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // Show on lock screen
            .setCategory(
                if (isMessagingApp) NotificationCompat.CATEGORY_MESSAGE
                else when (notification.categoryId.name) {
                    "IncomingCall", "MissedCall" -> NotificationCompat.CATEGORY_CALL
                    "Email" -> NotificationCompat.CATEGORY_EMAIL
                    "Schedule" -> NotificationCompat.CATEGORY_EVENT
                    else -> NotificationCompat.CATEGORY_STATUS
                }
            )

        // Add subtitle as subtext if available
        if (notification.subtitle.isNotBlank() && notification.message.isNotBlank()) {
            builder.setSubText(notification.subtitle)
        }

        // Use BigTextStyle if the message is long
        if (text.length > 50) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }

        // Mark as silent if flagged
        if (notification.eventFlags.isSilent) {
            builder.setSilent(true)
        }

        // Add Android Auto support for messaging notifications
        if (isMessagingApp) {
            // Create a Car Extender for Android Auto compatibility
            val carExtender = NotificationCompat.CarExtender()
                .setColor(context.getColor(android.R.color.holo_blue_dark))

            builder.extend(carExtender)

            // For messaging apps, create a MessagingStyle for better Android Auto display
            val person = androidx.core.app.Person.Builder()
                .setName(AppNameFormatter.format(notification.appIdentifier))
                .build()

            val messagingStyle = NotificationCompat.MessagingStyle(person)
                .addMessage(text, System.currentTimeMillis(), person)

            if (notification.subtitle.isNotBlank()) {
                messagingStyle.setConversationTitle(notification.subtitle)
            }

            builder.setStyle(messagingStyle)
        }

        with(NotificationManagerCompat.from(context)) {
            notify(NOTIFICATION_BASE_ID + notification.uid, builder.build())
        }
    }

    fun cancelNotification(uid: Int) {
        with(NotificationManagerCompat.from(context)) {
            cancel(NOTIFICATION_BASE_ID + uid)
        }
    }

    fun cancelAllNotifications() {
        with(NotificationManagerCompat.from(context)) {
            cancelAll()
        }
    }
}