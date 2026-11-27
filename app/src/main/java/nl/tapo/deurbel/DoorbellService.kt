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
            val dev = intent.getStringExtra(EXTRA_DEV)
            getSystemService(NotificationManager::class.java).cancelAll()
            scope.launch {
                try {
                    Responses.list(this@DoorbellService).firstOrNull { it.id == id }?.let { Responses.sendToDoor(this@DoorbellService, it, dev) }
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

    private val lastPower = java.util.concurrent.ConcurrentHashMap<String, nl.tapo.deurbel.core.PowerInfo>()
    private val devStatus = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Warns when the battery is almost empty or a device stops getting mains/solar power. */
    private fun checkPower(d: Dev, c: nl.tapo.deurbel.core.TapoClient) {
        val p = try { c.power() } catch (_: Exception) { return }
        val before = lastPower[d.id]
        lastPower[d.id] = p
        getSharedPreferences("deurbel", MODE_PRIVATE).edit().putBoolean("dev.${d.id}.on_battery", p.hasBattery && p.external != true).apply()
        val idx = Prefs(this).devices.indexOfFirst { it.id == d.id }
        if (before?.external == true && p.external == false) {
            notifyInfo(300 + idx, "${d.name} krijgt geen stroom meer",
                "Draait nu op de batterij" + (p.percent?.let { " ($it%)" } ?: "") + ". Controleer de voeding.")
        }
        if (p.low && p.charging != true) {
            val sp = getSharedPreferences("deurbel", MODE_PRIVATE)
            if (System.currentTimeMillis() - sp.getLong("low_batt_${d.id}", 0) > 12 * 3600_000L) {
                sp.edit().putLong("low_batt_${d.id}", System.currentTimeMillis()).apply()
                notifyInfo(400 + idx, "Batterij ${d.name} bijna leeg", "Nog ${p.percent}%. Laad op of controleer de voeding.")
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

    /** One watcher per device; all stop when the service stops. */
    private suspend fun pollLoop() = kotlinx.coroutines.coroutineScope {
        Prefs(this@DoorbellService).devices.forEach { d -> launch { deviceLoop(d) } }
    }

    private fun onBattery(d: Dev) = lastPower[d.id]?.let { it.hasBattery && it.external != true } ?: false

    private suspend fun deviceLoop(d0: Dev) {
        var d = d0
        val seen = HashMap<String, Long>()
        var primed = false
        var lastPowerCheck = 0L
        var configured = false
        while (scope.isActive) {
            try {
                val c = Camera.client(this, d.id)
                if (!configured) {
                    configured = true
                    try {
                        val info = c.deviceInfo()
                        val kind = if (c.isDoorbell(info)) "doorbell" else "camera"
                        if (kind != d.kind) { d = d.copy(kind = kind); Prefs(this).updateDevice(d) }
                    } catch (_: Exception) {}
                    Camera.applyAutoUpdate(this, c)
                }
                val corr = c.timeCorrection()
                val camNow = System.currentTimeMillis() / 1000 - corr
                val prefs = Prefs(this, d.id)
                for (e in c.events(camNow - 300, camNow + 60)) {
                    val key = "${e.startTime}/${e.type}"
                    if (seen.containsKey(key)) continue
                    seen[key] = e.startTime
                    if (e.type.isNotEmpty()) {
                        if (e.type !in prefs.seenTypes) prefs.seenTypes = prefs.seenTypes + e.type
                        prefs.typeStats = nl.tapo.deurbel.core.EventTypes.record(prefs.typeStats, e.type, e.startTime + corr)
                    }
                    if (primed && (prefs.ringTypes.isEmpty() || e.type in prefs.ringTypes)) {
                        Prefs(this).addHistory(e.startTime + corr, d.id, e.type)
                        if (!Prefs(this).dndActive) notifyRing(d, e.startTime + corr, e.type)
                    }
                }
                seen.entries.removeIf { it.value < camNow - 900 }
                primed = true
                Dnd.checkExpired(this)
                val powerEvery = if (onBattery(d)) 30 * 60_000 else 5 * 60_000
                if (System.currentTimeMillis() - lastPowerCheck > powerEvery) { lastPowerCheck = System.currentTimeMillis(); checkPower(d, c) }
                devStatus[d.id] = "ok"
                updateStatus()
                // battery cameras sleep: asking every few seconds would drain them
                delay(if (onBattery(d)) 60_000 else POLL_MS)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                devStatus[d.id] = "${d.name}: ${Camera.friendly(e)}"
                updateStatus()
                if (e is nl.tapo.deurbel.core.TapoException) Camera.reset(d.id)
                delay(if (onBattery(d)) 120_000 else 10_000)
            }
        }
    }

    private fun updateStatus() {
        val devs = Prefs(this).devices
        val errors = devs.mapNotNull { devStatus[it.id]?.takeIf { s -> s != "ok" } }
        val batt = devs.mapNotNull { d -> lastPower[d.id]?.takeIf { it.hasBattery }?.let { "${d.name} ${it.percent}%" } }
        setStatus(when {
            errors.isNotEmpty() -> "Geen verbinding – " + errors.joinToString("; ")
            devs.size == 1 -> "Luistert naar ${devs[0].name}" + (batt.firstOrNull()?.let { " · batterij ${it.substringAfterLast(' ')}" } ?: "")
            else -> "Luistert naar ${devs.size} apparaten" + if (batt.isNotEmpty()) " · " + batt.joinToString(", ") else ""
        })
    }

    private fun setStatus(s: String) {
        if (s == lastStatus) return
        lastStatus = s
        getSystemService(NotificationManager::class.java).notify(NOTIF_STATUS, statusNotification(s))
    }

    private fun openApp(ring: Boolean, devId: String? = null): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_RING, ring).putExtra(MainActivity.EXTRA_DEV, devId)
        return PendingIntent.getActivity(this, if (ring) 1 + (devId?.removePrefix("d")?.toIntOrNull() ?: 0) else 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(this, DeurbelApp.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("Deurbel")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(false))
            .build()

    private fun notifyRing(d: Dev, whenSec: Long, type: String) {
        val label = Prefs(this, d.id).typeLabels.optString(type)
        val multi = Prefs(this).devices.size > 1
        val b = NotificationCompat.Builder(this, DeurbelApp.CH_RING)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle((if (multi) "${d.name}: " else "") + when (label) {
                "ring" -> "Er wordt aangebeld"
                "", "person" -> if (d.isDoorbell) "Er staat iemand voor de deur" else "Iemand gezien"
                else -> nl.tapo.deurbel.core.EventTypes.LABELS[label] ?: "Gebeurtenis"
            })
            .setContentText("Tik om te kijken")
            .setWhen(whenSec * 1000)
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(90_000)
            .setContentIntent(openApp(true, d.id))
            .setFullScreenIntent(openApp(true, d.id), true)
        Responses.list(this).take(2).forEachIndexed { i, r ->
            val pi = PendingIntent.getForegroundService(this, 10 + i,
                Intent(this, DoorbellService::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_ID, r.id).putExtra(EXTRA_DEV, d.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, r.text.take(25), pi)
        }
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_RING + Prefs(this).devices.indexOfFirst { it.id == d.id }, b.build())
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
        const val EXTRA_DEV = "dev"

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
