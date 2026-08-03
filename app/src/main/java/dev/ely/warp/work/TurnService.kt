package dev.ely.warp.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import dev.ely.warp.MainActivity
import dev.ely.warp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps Warp alive while it is working.
 *
 * Not a nicety. MIUI freezes and kills background apps hard: in one evening
 * Warp was killed twice mid-build and once left running with its socket dead.
 * Every one of those threw away a reply that had already been paid for, and one
 * of them interrupted a forty-second compile.
 *
 * A foreground service with a visible notification is the only thing Android
 * promises not to reclaim, and the notification is not a side effect — it is
 * the answer to §5d's *"it says it is working, but it is frozen"*. Once you
 * have left the app it is the only place Warp can tell you anything at all.
 *
 * **Why Warp needs this and ChatGPT does not:** their generation happens on a
 * server and the app is a viewer, so leaving costs nothing. Warp's model is
 * remote but its tool loop and its compiler run on this phone. If the process
 * stops, the work stops; there is nowhere else for it to continue.
 */
class TurnService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        // Posted immediately, before anything else: Android gives a service a
        // few seconds to show its notification and kills it outright if it does
        // not, which would make this the one component that fails exactly when
        // the phone is under pressure.
        startForeground(NOTIFICATION_ID, notification(Working.now.value ?: "Working"))

        if (watching == null) {
            watching = scope.launch {
                Working.now.collectLatest { line ->
                    if (line == null) {
                        // The work finished, so the promise Android was asked to
                        // keep is discharged. Holding the notification longer
                        // would be asking for an exemption we no longer need.
                        stopSelf()
                    } else {
                        manager().notify(NOTIFICATION_ID, notification(line))
                    }
                }
            }
        }

        // START_NOT_STICKY: if Android does kill us, there is nothing to resume.
        // The turn died with the process, and starting an empty service again
        // would only put a notification on screen with no work behind it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watching?.cancel()
        watching = null
        super.onDestroy()
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun notification(line: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Warp")
            .setContentText(line)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            // Not dismissable while the work runs: swiping it away would ask
            // Android to stop protecting a build that is still going.
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "warp.working"

        /**
         * Make the channel, which is also what asks for permission.
         *
         * Called at launch, not at the first turn. Android 13 shows the
         * notification prompt to an app like this one the first time it starts
         * an activity *after* a channel exists — so creating it inside the
         * service meant the very first piece of protected work ran with the
         * notification silently blocked, and the prompt only appeared on the way
         * back in. The protection was real; there was simply no way to see it.
         */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL,
                "Working",
                // LOW: no sound, no vibration, no heads-up. This appears every
                // time you ask for anything, and a channel that buzzes on every
                // message is a channel people turn off — which would take the
                // process protection with it.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description =
                    "Shown while Warp is thinking or compiling, so Android does not stop it."
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
        private const val NOTIFICATION_ID = 1

        /**
         * Start it, unless it is already going.
         *
         * Safe to call on every turn: Android treats a repeat start of a running
         * service as a no-op beyond another onStartCommand.
         */
        fun start(context: Context) {
            val intent = Intent(context.applicationContext, TurnService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            }
        }
    }
}
