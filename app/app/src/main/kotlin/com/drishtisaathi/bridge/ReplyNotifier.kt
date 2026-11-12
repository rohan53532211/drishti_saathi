package com.drishtisaathi.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.drishtisaathi.MainActivity
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SBridge

/**
 * "Reply received" notification (with the default sound) when the optometrist's reply arrives. It names only the
 * case number: no advice text, no customer details on the lock screen. Tapping it opens that session's summary.
 */
object ReplyNotifier {
    private const val CHANNEL = "optometrist_replies"
    const val EXTRA_SESSION = "openSessionId"

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun ensureChannel(context: Context, lang: Lang) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // IMPORTANCE_HIGH: heads-up with the default notification sound.
        nm.createNotificationChannel(NotificationChannel(CHANNEL, SBridge.notifChannel[lang], NotificationManager.IMPORTANCE_HIGH))
    }

    fun replyReceived(context: Context, lang: Lang, sessionId: Long, caseId: String, needsInPersonExam: Boolean) {
        if (!canNotify(context)) return
        ensureChannel(context, lang)
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SESSION, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, sessionId.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = (if (needsInPersonExam) SBridge.notifInPerson else SBridge.notifText).format(caseId)[lang]
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(SBridge.notifTitle[lang])
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(("reply$sessionId").hashCode(), n)
        } catch (_: SecurityException) {
            // permission withdrawn between the check and the call: the card on the summary still shows the reply
        }
    }
}
