package nl.tapo.deurbel

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/** Finds Tapo devices on the home Wi-Fi by looking for the media port (8800). */
object Scanner {
    suspend fun scan(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return@withContext emptyList()
        val addr = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()
            ?: return@withContext emptyList()
        val b = addr.address
        val prefix = "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}.${b[2].toInt() and 0xff}."
        val own = b[3].toInt() and 0xff
        val sem = Semaphore(48)
        (1..254).filter { it != own }.map { i ->
            async {
                sem.withPermit {
                    val ip = prefix + i
                    try {
                        Socket().use { it.connect(InetSocketAddress(ip, 8800), 400) }
                        ip
                    } catch (_: Exception) { null }
                }
            }
        }.awaitAll().filterNotNull()
    }
}
