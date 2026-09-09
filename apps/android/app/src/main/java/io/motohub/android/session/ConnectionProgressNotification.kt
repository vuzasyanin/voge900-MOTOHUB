// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

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
import io.motohub.android.R
import io.motohub.android.i18n.motoHubText

/**
 * The "MOTO-HUB is still looking" notice, shown for as long as a connection attempt is running.
 *
 * Connecting to a dash is the one thing this app does that can take a minute and a half while
 * showing nothing but a spinner, and the sweep it ends in dies with the activity. A rider log
 * (samsung SM-S948B, 2026-08-23) has the whole shape of it: the hotspot came up, the search
 * started, and forty-four seconds later the app was closed - long before the sweep could reach a
 * verdict - because nothing anywhere said work was in progress or that leaving would end it.
 *
 * Deliberately not a foreground service. Making the search outlive the app is a larger change
 * with its own consent question; telling the rider what is happening is not, and it is what was
 * missing. [setTimeoutAfter] is the safety net for the one case this cannot clean up after: a
 * process killed outright leaves no chance to cancel the notification, and an "in progress"
 * notice that outlives the work is worse than none.
 */
object ConnectionProgressNotification {

    fun show(context: Context, ssid: String, searching: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                motoHubText("MOTO-HUB connection progress"),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (searching) {
                    motoHubText("Looking for %1\$s", ssid)
                } else {
                    motoHubText("Connecting to %1\$s", ssid)
                }
            )
            .setContentText(motoHubText("Keep MOTO-HUB open - closing it stops the search."))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(TIMEOUT_MILLIS)
            .setContentIntent(openApp(context))
            .build()
        // Checked rather than caught: from Android 13 notify() is a silent no-op without the
        // runtime grant, and a rider who declined notifications is owed no exception either way.
        // The version guard is not decoration - the permission does not exist before 13, where
        // checkSelfPermission answers DENIED for it and would switch this notice off entirely.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // Some OEM builds still throw when the channel itself is blocked, and that is not a
        // reason to fail a connect.
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun openApp(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            PendingIntent.getActivity(
                context,
                0,
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

    private const val CHANNEL_ID = "connection_progress_v1"
    private const val NOTIFICATION_ID = 4300
    /** Longer than the worst case (two 15s discovery windows plus a 253-address sweep). */
    private const val TIMEOUT_MILLIS = 180_000L
}
