package nl.tapo.deurbel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager

class DeurbelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        val ring = NotificationChannel(CH_RING, "Aanbellen", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Melding als er iemand voor de deur staat"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val status = NotificationChannel(CH_STATUS, "Verbinding met deurbel", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Vaste melding zolang de app luistert naar de deurbel"
            setShowBadge(false)
        }
        nm.createNotificationChannels(listOf(ring, status))
    }

    companion object {
        const val CH_RING = "ring"
        const val CH_STATUS = "status"
        lateinit var instance: DeurbelApp
            private set
    }
}
