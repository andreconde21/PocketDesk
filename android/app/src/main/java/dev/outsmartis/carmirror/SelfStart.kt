package dev.outsmartis.carmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread

private const val TAG = "PocketDeskStart"
private const val CHANNEL = "selfstart"
private const val NOTE_PAIR = 7101
private const val NOTE_START = 7102
private const val KEY_CODE = "code"

/** At boot: if we're paired and the helper isn't running, start it once Wi-Fi is up. */
class SelfStartBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!SelfAdb.isPaired(ctx) || !SelfAdb.prefs(ctx).getBoolean("autoStart", true)) return
        Log.i(TAG, "${intent.action}: starting helper")
        runCatching { ctx.startForegroundService(Intent(ctx, SelfStartService::class.java)) }
            .onFailure { Log.w(TAG, "can't start service: $it") }
    }
}

/** Waits for Wi-Fi (up to 15 min), starts the helper through our own ADB client, then stops. */
class SelfStartService : Service() {
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channel(this)
        startForeground(NOTE_START, note(this, "Starting desktop helper…", "Waiting for Wi-Fi"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (running) return START_NOT_STICKY
        running = true
        thread(name = "selfstart") {
            val deadline = System.currentTimeMillis() + 15 * 60_000L
            // restore wireless debugging to how the user had it, even across retries
            val wasOn = SelfAdb.wirelessDebuggingOn(this)
            var result: Result<Unit> = Result.failure(IllegalStateException("no Wi-Fi"))
            while (System.currentTimeMillis() < deadline) {
                if (ShizukuBridge.service != null) { result = Result.success(Unit); break }
                if (onWifi(this)) {
                    result = SelfAdb.startHelper(this, wasOn)
                    if (result.isSuccess) {
                        // give SelfHost a moment to hand over its binder
                        repeat(10) { if (ShizukuBridge.service == null) Thread.sleep(500) }
                        break
                    }
                    Log.w(TAG, "start failed: ${result.exceptionOrNull()}")
                }
                Thread.sleep(5_000)
            }
            result.onFailure {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTE_START, note(this, "Desktop helper didn't start", it.message ?: it.toString()))
            }
            running = false
            stopForeground(if (result.isSuccess) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        fun onWifi(ctx: Context): Boolean {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }
    }
}

/**
 * Pairing without leaving the Wireless debugging screen: a notification with a reply field.
 * The user opens "Pair device with pairing code", pulls down the shade and types the code.
 */
object PairingNotification {
    fun show(ctx: Context) {
        channel(ctx)
        val reply = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, PairingReplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = Notification.Action.Builder(null, "Enter pairing code", reply)
            .addRemoteInput(RemoteInput.Builder(KEY_CODE).setLabel("6-digit code").build())
            .build()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Pair PocketDesk")
            .setContentText("Wireless debugging → Pair device with pairing code, then type the code here")
            .addAction(action)
            .setOngoing(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTE_PAIR, n)
    }

    fun update(ctx: Context, text: String, done: Boolean) {
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Pair PocketDesk").setContentText(text).setOngoing(!done).build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTE_PAIR, n)
    }
}

class PairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.trim()
        if (code.isNullOrEmpty()) return
        val app = ctx.applicationContext
        val pending = goAsync()
        PairingNotification.update(app, "Pairing…", done = false)
        thread(name = "pair") {
            try {
                val port = SelfAdb.findPairingPort(app)
                check(port > 0) { "pairing dialog not found; keep it open while you type the code" }
                SelfAdb.pair(app, port, code)
                val granted = SelfAdb.canToggleWirelessDebugging(app)
                val started = SelfAdb.startHelper(app)
                PairingNotification.update(
                    app,
                    "Paired. Auto-start ${if (granted) "on" else "unavailable"}; helper " +
                        (started.exceptionOrNull()?.let { "not started: ${it.message}" } ?: "started"),
                    done = true,
                )
            } catch (e: Exception) {
                Log.w(TAG, "pairing failed", e)
                PairingNotification.update(app, "Pairing failed: ${e.message}", done = true)
                PairingNotification.show(app)
            } finally {
                pending.finish()
            }
        }
    }
}

private fun channel(ctx: Context) {
    ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Desktop helper", NotificationManager.IMPORTANCE_DEFAULT),
    )
}

private fun note(ctx: Context, title: String, text: String): Notification =
    Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle(title).setContentText(text).build()
