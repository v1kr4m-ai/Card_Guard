package com.cursedpirate.cardguard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Daily: notify for cards with a balance due in 0..5 days. Stops once outstanding hits 0. */
class ReminderWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33) return Result.success()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("due", "Due dates", NotificationManager.IMPORTANCE_DEFAULT))
        val today = LocalDate.now()
        for (c in AppDb.get(ctx).dao().allCards()) {
            if (c.dueDay == 0 || c.outstanding <= 0) continue
            val days = daysUntilDue(today, c.dueDay)
            if (days !in 0..5) continue
            val text = if (days == 0) "Due today" else "Due in $days day${if (days > 1) "s" else ""}"
            nm.notify(c.id.toInt(), NotificationCompat.Builder(ctx, "due")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("${c.bank} •••• ${c.last4}")
                .setContentText("$text — ₹${"%,.0f".format(c.outstanding)} outstanding")
                .setAutoCancel(true).build())
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "due-reminders", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build())
    }
}

fun daysUntilDue(today: LocalDate, dueDay: Int): Int {
    var d = today.withDayOfMonth(dueDay.coerceAtMost(today.lengthOfMonth()))
    if (d.isBefore(today)) { val n = today.plusMonths(1); d = n.withDayOfMonth(dueDay.coerceAtMost(n.lengthOfMonth())) }
    return java.time.temporal.ChronoUnit.DAYS.between(today, d).toInt()
}
