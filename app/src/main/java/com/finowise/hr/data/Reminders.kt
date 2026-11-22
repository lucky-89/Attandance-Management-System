package com.finowise.hr.data

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
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.finowise.hr.HrApp
import com.finowise.hr.MainActivity
import com.finowise.hr.R
import java.util.concurrent.TimeUnit

/**
 * The two reminders — "you have not punched in" and "you are still punched
 * in" — at the times HR set, repeating at HR's interval until the thing is
 * done. Worked out from the signed-in person's own record every time, so a
 * punch silences them without anything having to be told.
 */
object Reminders {
    private const val CHANNEL = "attendance"

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Attendance reminders", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "Punch-in and punch-out reminders"
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("attendance-reminders", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun check(ctx: Context) {
        val app = ctx.applicationContext as HrApp
        val user = app.session.userId ?: return
        val r = app.service.rules(user)
        val due = r.nudgeDue() ?: return
        val repeatMs = r.cfg.reminders.repeatMinutes.coerceAtLeast(1) * 60_000L
        val key = due.key + "_" + r.today
        val now = System.currentTimeMillis()
        if (now - app.session.reminderShown(key) < repeatMs - 60_000) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(due.title)
            .setContentText(due.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(due.body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(if (due.key == "in") 1 else 2, n) } catch (_: SecurityException) { return }
        app.session.setReminderShown(key, now)
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result { Reminders.check(applicationContext); return Result.success() }
}
