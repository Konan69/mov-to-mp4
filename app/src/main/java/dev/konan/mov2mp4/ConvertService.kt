package dev.konan.mov2mp4

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.konan.mov2mp4.data.Converter
import dev.konan.mov2mp4.data.Job
import dev.konan.mov2mp4.data.JobState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps the process alive while files are converting, so a long video finishes
 * even when the user goes back to the app they came from.
 */
class ConvertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastShown = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTIFICATION, build(Converter.jobs.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        scope.launch {
            Converter.jobs.collectLatest { jobs ->
                if (jobs.none { it.isActive }) {
                    announce(jobs)
                    stopSelf()
                } else {
                    val key = progressKey(jobs)
                    if (key != lastShown) {
                        lastShown = key
                        manager.notify(NOTIFICATION, build(jobs))
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun current(jobs: List<Job>) =
        jobs.lastOrNull { it.state is JobState.Converting } ?: jobs.lastOrNull { it.isActive }

    private fun percent(job: Job?) = ((job?.state as? JobState.Converting)?.fraction ?: 0f).times(100).roundToInt()

    // Only repaint the notification when something a person can see has changed.
    private fun progressKey(jobs: List<Job>): Int {
        val job = current(jobs)
        return (job?.id?.toInt() ?: 0) * 1000 + percent(job) + jobs.count { it.isActive } * 100_000
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(jobs: List<Job>): Notification {
        val job = current(jobs)
        val waiting = jobs.count { it.isActive } - 1
        val converting = job?.state is JobState.Converting
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_convert)
            .setContentTitle(job?.name ?: getString(R.string.app_name))
            .setContentText(
                when {
                    !converting -> getString(R.string.notif_preparing)
                    waiting > 0 -> getString(R.string.notif_progress_more, percent(job), waiting)
                    else -> getString(R.string.notif_progress, percent(job))
                }
            )
            .setProgress(100, percent(job), !converting)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .build()
    }

    /** When the queue empties while the app is out of sight, say so once. */
    private fun announce(jobs: List<Job>) {
        if (App.visible) return
        val done = jobs.count { it.state is JobState.Done }
        val failed = jobs.count { it.state is JobState.Failed }
        if (done + failed == 0) return
        val text = when {
            failed == 0 -> resources.getQuantityString(R.plurals.notif_done, done, done)
            done == 0 -> resources.getQuantityString(R.plurals.notif_failed, failed, failed)
            else -> getString(R.string.notif_mixed, done, failed)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_convert)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(RESULT, notification) }
    }

    companion object {
        private const val CHANNEL = "conversion"
        private const val NOTIFICATION = 1
        private const val RESULT = 2

        fun start(context: Context) {
            // Refused when the app is in the background; the conversion still runs.
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ConvertService::class.java))
            }.onFailure { Log.w("ConvertService", "not started: ${it.message}") }
        }
    }
}
