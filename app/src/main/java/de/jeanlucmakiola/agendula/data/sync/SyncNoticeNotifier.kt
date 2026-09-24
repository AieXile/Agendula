package de.jeanlucmakiola.agendula.data.sync

import android.Manifest
import android.annotation.SuppressLint
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
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.agendula.MainActivity
import de.jeanlucmakiola.agendula.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the user what a background sync destroyed or gave up on.
 *
 * ⚠️ A notification, and not only a row on the accounts screen. Sync runs on a
 * four-hour timer while the app is closed, so a surface the user has to go and
 * look at means the discarded edit is discovered — if ever — days later, next to
 * a task that quietly says something else than what they typed. The account
 * screen keeps the detail; this is what makes them go there.
 *
 * Its own channel, at `IMPORTANCE_LOW`: it is a report rather than an alarm, and
 * it must be silenceable without taking due-task reminders with it.
 */
@Singleton
class SyncNoticeNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // canPost() checks POST_NOTIFICATIONS before we ever call notify().
    @SuppressLint("MissingPermission")
    fun post(accountName: String, notices: List<SyncNotice>) {
        if (notices.isEmpty() || !canPost()) return
        ensureChannel()

        val discarded = notices.count { it.kind == SyncNotice.Kind.DISCARDED_EDIT }
        val quarantined = notices.size - discarded
        // ⚠️ A discarded edit outranks a quarantine even when there are more
        // quarantines, and the collapsed line says so. They are not equivalent:
        // an edit that lost is work already destroyed and unrecoverable, while a
        // quarantined task is a condition that persists and clears itself. The
        // big text below lists both, in full, whichever headline was chosen.
        val title = if (discarded > 0) {
            context.resources.getQuantityString(
                R.plurals.sync_notice_discarded_title, discarded, discarded,
            )
        } else {
            context.resources.getQuantityString(
                R.plurals.sync_notice_quarantined_title, quarantined, quarantined,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.sync_notice_body, accountName))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryOf(notices)))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    accountName.hashCode(),
                    MainActivity.openIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

        // Tagged by account, so a second account's news replaces nothing.
        NotificationManagerCompat.from(context).notify(accountName, NOTIFICATION_ID, notification)
    }

    /** "Sign in to <account> again", for a background sync the server refused. */
    // canPost() checks POST_NOTIFICATIONS before we ever call notify().
    @SuppressLint("MissingPermission")
    fun postSignIn(accountName: String, accountId: Long) {
        if (!canPost()) return
        ensureSignInChannel()
        val body = context.getString(R.string.sync_sign_in_body)
        val notification = NotificationCompat.Builder(context, SIGN_IN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.sync_sign_in_title, accountName))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    accountId.toInt(),
                    signInIntent(accountId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        NotificationManagerCompat.from(context).notify(accountName, SIGN_IN_NOTIFICATION_ID, notification)
    }

    /** The account syncs again, so the prompt has done its job. */
    fun cancelSignIn(accountName: String) {
        NotificationManagerCompat.from(context).cancel(accountName, SIGN_IN_NOTIFICATION_ID)
    }

    /**
     * Where tapping the sign-in prompt lands. Only opens the app for now; the
     * extra names the account for routing to Settings → Accounts → it.
     */
    private fun signInIntent(accountId: Long): Intent =
        MainActivity.openIntent(context).putExtra(EXTRA_SIGN_IN_ACCOUNT_ID, accountId)

    private fun ensureSignInChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(SIGN_IN_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                SIGN_IN_CHANNEL_ID,
                context.getString(R.string.sync_sign_in_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.sync_sign_in_channel_desc) },
        )
    }

    /**
     * The first few, by name.
     *
     * ⚠️ A count on its own is unactionable — "3 edits were replaced" leaves the
     * user to guess which three, across every list they own. The names are the
     * only part that makes the account screen worth opening.
     */
    private fun summaryOf(notices: List<SyncNotice>): String {
        val named = notices.take(SUMMARY_LIMIT).joinToString("\n") { notice ->
            val subject = notice.subject.ifBlank { context.getString(R.string.task_untitled) }
            context.getString(R.string.sync_notice_line, subject, notice.listName)
        }
        val rest = notices.size - SUMMARY_LIMIT
        return if (rest > 0) {
            named + "\n" + context.resources.getQuantityString(R.plurals.sync_notice_more, rest, rest)
        } else {
            named
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sync_notice_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.sync_notice_channel_desc) },
        )
    }

    /** Re-create the channel, if it exists, in the current language. */
    fun refreshChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) ensureChannel()
    }

    companion object {
        /** The account a sign-in notification is about, on the intent it opens. */
        const val EXTRA_SIGN_IN_ACCOUNT_ID = "de.jeanlucmakiola.agendula.extra.SIGN_IN_ACCOUNT_ID"

        private const val CHANNEL_ID = "sync_notices"
        private const val NOTIFICATION_ID = 2
        private const val SIGN_IN_CHANNEL_ID = "account_sign_in"
        private const val SIGN_IN_NOTIFICATION_ID = 3

        /** Enough to recognise the work; the screen has the rest. */
        private const val SUMMARY_LIMIT = 5
    }
}
