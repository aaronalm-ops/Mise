package app.mise

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.mise.data.Prefs
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * The weekly "plan next week" nudge. An inexact alarm (no special permission), re-armed after
 * each reminder, after a restart and after an update. Tapping it opens next week's plan.
 */
object Reminder {
    private const val CHANNEL = "plan"
    const val OPEN_EXTRA = "open"

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx)
        am.cancel(pi)
        if (!Prefs.reminderOn) return
        am.setWindow(AlarmManager.RTC_WAKEUP, next(Prefs.reminderDay, Prefs.reminderHour), 10 * 60_000L, pi)
    }

    fun next(day: DayOfWeek, hour: Int, now: ZonedDateTime = ZonedDateTime.now()): Long {
        var t = now.with(TemporalAdjusters.nextOrSame(day)).withHour(hour).withMinute(0).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusWeeks(1)
        return t.toInstant().toEpochMilli()
    }

    fun show(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Planning reminder", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(ctx, MainActivity::class.java).apply {
            putExtra(OPEN_EXTRA, "plan-next")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tap = PendingIntent.getActivity(ctx, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Plan next week")
            .setContentText("Five minutes now, and the list builds itself.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        Reminder.show(context)
        Reminder.schedule(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        Reminder.schedule(context)
    }
}
