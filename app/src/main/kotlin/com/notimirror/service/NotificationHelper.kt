package com.notimirror.service

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
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

        // Call notification specific
        const val CHANNEL_INCOMING_CALL = "iphone_incoming_call"
        const val ACTION_ANSWER_CALL = "com.notimirror.ACTION_ANSWER_CALL"
        const val ACTION_DECLINE_CALL = "com.notimirror.ACTION_DECLINE_CALL"
        const val EXTRA_NOTIFICATION_UID = "notification_uid"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val createdChannels = mutableSetOf<String>()

    init {
        createChannelGroup()
        createIncomingCallChannel()
    }

    private fun createChannelGroup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val group = NotificationChannelGroup(CHANNEL_GROUP_ID, CHANNEL_GROUP_NAME)
            notificationManager.createNotificationChannelGroup(group)
        }
    }

    private fun createIncomingCallChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_INCOMING_CALL,
                "Incoming Calls from iPhone",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming call notifications from iPhone"
                group = CHANNEL_GROUP_ID
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000) // Ring pattern
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true) // Allow through Do Not Disturb for calls
            }
            notificationManager.createNotificationChannel(channel)
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

        // Check if this is an incoming or missed call
        when (notification.categoryId.name) {
            "IncomingCall" -> {
                showIncomingCallNotification(notification)
                return
            }
            "MissedCall" -> {
                showMissedCallNotification(notification)
                return
            }
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

    private fun showIncomingCallNotification(notification: IPhoneNotification) {
        // Vibrate for incoming call
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pattern = longArrayOf(0, 1000, 500, 1000, 500, 1000) // Ring pattern
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0)) // Repeat
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000), 0)
        }

        // Create intents for answer and decline actions
        val answerIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = ACTION_ANSWER_CALL
            putExtra(EXTRA_NOTIFICATION_UID, notification.uid)
        }
        val answerPendingIntent = PendingIntent.getBroadcast(
            context,
            notification.uid * 2,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = ACTION_DECLINE_CALL
            putExtra(EXTRA_NOTIFICATION_UID, notification.uid)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            notification.uid * 2 + 1,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Open app when notification is tapped
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notification.uid,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Extract caller info
        val callerName = when {
            notification.title.isNotBlank() -> notification.title
            notification.subtitle.isNotBlank() -> notification.subtitle
            else -> "Unknown Caller"
        }

        val phoneNumber = when {
            notification.message.isNotBlank() -> notification.message
            notification.subtitle.isNotBlank() && notification.subtitle != callerName -> notification.subtitle
            else -> ""
        }

        // Build the notification with call-specific styling
        val builder = NotificationCompat.Builder(context, CHANNEL_INCOMING_CALL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("Incoming call on iPhone")
            .setContentText(callerName)
            .setSubText(phoneNumber)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(contentPendingIntent, true) // Full screen intent for calls
            .setOngoing(true) // Cannot be dismissed by swipe
            .setAutoCancel(false)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setColor(context.getColor(android.R.color.holo_green_dark))

        // Add answer and decline actions
        if (notification.eventFlags.hasPositiveAction) {
            builder.addAction(
                android.R.drawable.stat_sys_phone_call,
                "Answer on iPhone",
                answerPendingIntent
            )
        }

        if (notification.eventFlags.hasNegativeAction) {
            builder.addAction(
                android.R.drawable.stat_sys_phone_call_forward,
                "Decline",
                declinePendingIntent
            )
        }

        // Use MessagingStyle for better Android Auto display
        val person = androidx.core.app.Person.Builder()
            .setName(callerName)
            .setImportant(true)
            .build()

        val messagingStyle = NotificationCompat.MessagingStyle(person)
            .setConversationTitle("Incoming Call")

        builder.setStyle(messagingStyle)

        // Add Car Extender for Android Auto
        val carExtender = NotificationCompat.CarExtender()
            .setColor(context.getColor(android.R.color.holo_green_dark))

        builder.extend(carExtender)

        // Show the notification
        with(NotificationManagerCompat.from(context)) {
            notify(NOTIFICATION_BASE_ID + notification.uid, builder.build())
        }

        // Store the notification UID for later vibration cancellation
        activeCallNotificationUid = notification.uid
    }

    private var activeCallNotificationUid: Int? = null

    private fun showMissedCallNotification(notification: IPhoneNotification) {
        // Extract caller info
        val callerName = when {
            notification.title.isNotBlank() -> notification.title
            notification.subtitle.isNotBlank() -> notification.subtitle
            else -> "Unknown Caller"
        }

        val phoneNumber = when {
            notification.message.isNotBlank() -> notification.message
            notification.subtitle.isNotBlank() && notification.subtitle != callerName -> notification.subtitle
            else -> ""
        }

        // Open app when notification is tapped
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notification.uid,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, getOrCreateChannelForApp("com.apple.mobilephone"))
            .setSmallIcon(android.R.drawable.stat_sys_phone_call_forward)
            .setContentTitle("Missed call on iPhone")
            .setContentText(callerName)
            .setSubText(phoneNumber)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setColor(context.getColor(android.R.color.holo_red_dark))

        // Add call back action if available
        if (notification.eventFlags.hasPositiveAction) {
            val callBackIntent = Intent(context, CallActionReceiver::class.java).apply {
                action = ACTION_ANSWER_CALL
                putExtra(EXTRA_NOTIFICATION_UID, notification.uid)
            }
            val callBackPendingIntent = PendingIntent.getBroadcast(
                context,
                notification.uid * 2,
                callBackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(
                android.R.drawable.stat_sys_phone_call,
                "Call Back",
                callBackPendingIntent
            )
        }

        // Show the notification
        with(NotificationManagerCompat.from(context)) {
            notify(NOTIFICATION_BASE_ID + notification.uid, builder.build())
        }
    }

    fun cancelNotification(uid: Int) {
        // Stop vibration if this was a call notification
        if (uid == activeCallNotificationUid) {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.cancel()
            activeCallNotificationUid = null
        }

        with(NotificationManagerCompat.from(context)) {
            cancel(NOTIFICATION_BASE_ID + uid)
        }
    }

    fun cancelAllNotifications() {
        // Stop any active call vibration
        activeCallNotificationUid?.let {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.cancel()
            activeCallNotificationUid = null
        }

        with(NotificationManagerCompat.from(context)) {
            cancelAll()
        }
    }
}

// Broadcast receiver to handle call actions
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationUid = intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_UID, -1)
        if (notificationUid == -1) return

        // Stop vibration
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.cancel()

        // Get the connection manager and repository from the app container
        val app = context.applicationContext as? com.notimirror.NotiMirrorApp
        val connectionManager = app?.container?.connectionManager
        val repository = app?.container?.repository

        when (intent.action) {
            NotificationHelper.ACTION_ANSWER_CALL -> {
                // Send PERFORM_NOTIFICATION_ACTION command to iPhone to answer
                connectionManager?.performNotificationAction(notificationUid, isPositive = true)
                repository?.markCallAsAnswered(notificationUid)
                NotificationManagerCompat.from(context).cancel(10000 + notificationUid)
            }
            NotificationHelper.ACTION_DECLINE_CALL -> {
                // Send PERFORM_NOTIFICATION_ACTION command to iPhone to decline
                connectionManager?.performNotificationAction(notificationUid, isPositive = false)
                repository?.markCallAsDeclined(notificationUid)
                NotificationManagerCompat.from(context).cancel(10000 + notificationUid)
            }
        }
    }
}