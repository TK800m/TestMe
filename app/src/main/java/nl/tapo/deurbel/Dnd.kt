package nl.tapo.deurbel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Niet storen": no notifications, doorbell chimes silent, restored automatically afterwards. */
object Dnd {
    suspend fun set(ctx: Context, untilMs: Long) = withContext(Dispatchers.IO) {
        val p = Prefs(ctx)
        val wasActive = p.dndActive
        val sp = ctx.getSharedPreferences("deurbel", Context.MODE_PRIVATE)
        if (untilMs > System.currentTimeMillis()) {
            if (!wasActive) p.devices.filter { it.isDoorbell }.forEach { d ->
                val c = try { Camera.client(ctx, d.id) } catch (_: Exception) { return@forEach }
                val on = try { c.chimeRingPlan()?.optString("enabled") == "on" } catch (_: Exception) { false }
                sp.edit().putBoolean("dev.${d.id}.dnd_chime_prev", on).apply()
                if (on) try { c.setChimeEnabled(false) } catch (_: Exception) {}
            }
            p.dndUntil = untilMs
        } else {
            p.dndUntil = 0
            if (wasActive || untilMs < 0) restoreChimes(ctx)
        }
    }

    private fun restoreChimes(ctx: Context) {
        val p = Prefs(ctx)
        val sp = ctx.getSharedPreferences("deurbel", Context.MODE_PRIVATE)
        p.devices.filter { it.isDoorbell && sp.getBoolean("dev.${it.id}.dnd_chime_prev", false) }.forEach { d ->
            try { Camera.client(ctx, d.id).setChimeEnabled(true) } catch (_: Exception) {}
            sp.edit().putBoolean("dev.${d.id}.dnd_chime_prev", false).apply()
        }
    }

    /** Called regularly by the background service. */
    fun checkExpired(ctx: Context) {
        val p = Prefs(ctx)
        if (p.dndUntil in 1..System.currentTimeMillis()) { p.dndUntil = 0; restoreChimes(ctx) }
    }
}
