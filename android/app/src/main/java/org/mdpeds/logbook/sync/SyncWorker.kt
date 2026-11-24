package org.mdpeds.logbook.sync

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
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.mdpeds.logbook.MainActivity
import org.mdpeds.logbook.R
import org.mdpeds.logbook.data.LogbookRepository
import org.mdpeds.logbook.data.SyncScheduler
import org.mdpeds.logbook.data.local.UserPreferences
import org.mdpeds.logbook.domain.model.AppNotification
import org.mdpeds.logbook.domain.model.BackendException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Uploads offline drafts and surfaces new in-app notifications as system notifications. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: LogbookRepository,
    private val notifier: Notifier,
    private val prefs: UserPreferences,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            if (repository.session.value == null && repository.restore() == null) return Result.success()
            val waiting = repository.syncAll()
            if (prefs.remindersEnabled.first()) runCatching { notifier.postNew(repository.backend.notifications()) }
            if (waiting > 0) Result.retry() else Result.success()
        } catch (e: BackendException) {
            if (e.isNetwork) Result.retry() else Result.success()
        }
    }
}

@Singleton
class WorkSyncScheduler @Inject constructor(@ApplicationContext private val context: Context) : SyncScheduler {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun scheduleSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, request)
    }

    /** Periodic sync + reminder check (monthly assessments due, review requests, returns). */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferences,
) {
    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = context.getString(R.string.notification_channel_description)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    suspend fun postNew(notifications: List<AppNotification>) {
        val seen = prefs.notifiedIds()
        val fresh = notifications.filter { it.readAt == null && it.id !in seen }
        if (fresh.isEmpty()) return
        if (canPost()) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN, "notifications")
            }
            val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val manager = NotificationManagerCompat.from(context)
            fresh.take(5).forEach { n ->
                val notification = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(n.title)
                    .setContentText(n.body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build()
                try {
                    manager.notify(n.id.hashCode(), notification)
                } catch (_: SecurityException) {
                }
            }
        }
        prefs.setNotifiedIds(seen + fresh.map { it.id })
    }

    companion object {
        const val CHANNEL = "logbook_updates"
    }
}
