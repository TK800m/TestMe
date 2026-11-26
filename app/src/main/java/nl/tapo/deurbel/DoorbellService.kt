package nl.tapo.deurbel

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.tapo.deurbel.ui.MainActivity

/**
 * Keeps listening to the doorbell on the home network and shows a ringing
 * notification when someone is at the door. Also plays quick replies from
 * the notification buttons.
 */
class DoorbellService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastStatus = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_STATUS, statusNotification(lastStatus.ifEmpty { "Verbinden met deurbel…" }),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val prefs = Prefs(this)
        if (!prefs.notifications || !prefs.configured) {
            if (intent?.action != ACTION_REPLY) { stopSelf(); return START_NOT_STICKY }
        }
        if (intent?.action == ACTION_REPLY) {
            val id = intent.getStringExtra(EXTRA_ID)
            getSystemService(NotificationManager::class.java).cancel(NOTIF_RING)
            scope.launch {
                try {
                    Responses.list(this@DoorbellService).firstOrNull { it.id == id }?.let { Responses.sendToDoor(this@DoorbellService, it) }
                } catch (e: Exception) {
                    setStatus("Antwoord versturen mislukt: ${Camera.friendly(e)}")
                }
                if (!Prefs(this@DoorbellService).notifications) stopSelf()
            }
        }
        if (prefs.notifications && job == null) {
            acquireLocks()
            job = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "deurbel:listen").apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "deurbel:wifi").apply { setReferenceCounted(false); acquire() }
        }
    }

    private var lastPower: nl.tapo.deurbel.core.PowerInfo? = null
    private var lastPowerCheck = 0L

    /** Warns when the battery is almost empty or the doorbell stops getting mains power. */
    private fun checkPower(c: nl.tapo.deurbel.core.TapoClient) {
        val p = try { c.power() } catch (_: Exception) { return }
        val before = lastPower
        lastPower = p
        if (before?.external == true && p.external == false) {
            notifyInfo(3, "Deurbel krijgt geen stroom meer",
                "De deurbel draait nu op de batterij" + (p.percent?.let { " ($it%)" } ?: "") + ". Controleer de netvoeding.")
        }
        if (p.low && p.charging != true) {
            val sp = getSharedPreferences("deurbel", MODE_PRIVATE)
            if (System.currentTimeMillis() - sp.getLong("low_batt", 0) > 12 * 3600_000L) {
                sp.edit().putLong("low_batt", System.currentTimeMillis()).apply()
                notifyInfo(4, "Batterij deurbel bijna leeg", "Nog ${p.percent}%. Laad de deurbel op of controleer de netvoeding.")
            }
        }
    }

    private fun notifyInfo(id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(this, DeurbelApp.CH_RING)
            .setSmallIcon(R.drawable.ic_stat_bell).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSilent(true).setAutoCancel(true).setContentIntent(openApp(false)).build()
        try { getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) { }
    }

    private suspend fun pollLoop() {
        val seen = HashMap<String, Long>()
        var primed = false
        while (scope.isActive) {
            try {
                val c = Camera.client(this)
                val corr = c.timeCorrection()
                val camNow = System.currentTimeMillis() / 1000 - corr
                val events = c.events(camNow - 300, camNow + 60)
                val prefs = Prefs(this)
                for (e in events) {
                    val key = "${e.startTime}/${e.type}"
                    if (seen.containsKey(key)) continue
                    seen[key] = e.startTime
                    if (e.type.isNotEmpty() && e.type !in prefs.seenTypes) prefs.seenTypes = prefs.seenTypes + e.type
                    if (e.type.isNotEmpty()) prefs.typeStats = nl.tapo.deurbel.core.EventTypes.record(prefs.typeStats, e.type, e.startTime + corr)
                    if (primed && (prefs.ringTypes.isEmpty() || e.type in prefs.ringTypes)) {
                        notifyRing(e.startTime + corr, e.type)
                    }
                }
                seen.entries.removeIf { it.value < camNow - 900 }
                primed = true
                if (System.currentTimeMillis() - lastPowerCheck > 5 * 60_000) { lastPowerCheck = System.currentTimeMillis(); checkPower(c) }
                setStatus("Luistert naar de deurbel" + (lastPower?.takeIf { it.hasBattery }?.let { " · batterij ${it.percent}%" } ?: ""))
                delay(POLL_MS)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                setStatus("Geen verbinding: ${Camera.friendly(e)}")
                if (e is nl.tapo.deurbel.core.TapoException) Camera.reset()
                delay(10_000)
            }
        }
    }

    private fun setStatus(s: String) {
        if (s == lastStatus) return
        lastStatus = s
        getSystemService(NotificationManager::class.java).notify(NOTIF_STATUS, statusNotification(s))
    }

    private fun openApp(ring: Boolean): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_RING, ring)
        return PendingIntent.getActivity(this, if (ring) 1 else 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(this, DeurbelApp.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("Deurbel")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(false))
            .build()

    private fun notifyRing(whenSec: Long, type: String) {
        val label = Prefs(this).typeLabels.optString(type)
        val b = NotificationCompat.Builder(this, DeurbelApp.CH_RING)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(when (label) {
                "ring" -> "Er wordt aangebeld"
                "", "person" -> "Er staat iemand voor de deur"
                else -> nl.tapo.deurbel.core.EventTypes.LABELS[label] ?: "Er staat iemand voor de deur"
            })
            .setContentText("Tik om te kijken wie het is")
            .setWhen(whenSec * 1000)
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(90_000)
            .setContentIntent(openApp(true))
            .setFullScreenIntent(openApp(true), true)
        Responses.list(this).take(2).forEachIndexed { i, r ->
            val pi = PendingIntent.getForegroundService(this, 10 + i,
                Intent(this, DoorbellService::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_ID, r.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, r.text.take(25), pi)
        }
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_RING, b.build())
        } catch (_: SecurityException) { }
    }

    override fun onDestroy() {
        scope.cancel()
        job = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_STATUS = 1
        private const val NOTIF_RING = 2
        private const val POLL_MS = 3000L
        const val ACTION_REPLY = "nl.tapo.deurbel.REPLY"
        const val EXTRA_ID = "id"

        /** Starts or stops listening according to the settings. */
        fun sync(context: Context) {
            val p = Prefs(context)
            val i = Intent(context, DoorbellService::class.java)
            if (p.notifications && p.configured) {
                try { context.startForegroundService(i) } catch (_: Exception) { }
            } else {
                context.stopService(i)
            }
        }

        fun restart(context: Context) {
            context.stopService(Intent(context, DoorbellService::class.java))
            sync(context)
        }
    }
}
