package nl.tapo.deurbel.core

import org.json.JSONObject

/**
 * Human-readable names for the doorbell's event types. The doorbell only reports
 * numbers (which differ per model), so the user teaches the app which number is
 * the doorbell button, and can name the others.
 */
object EventTypes {
    /** Label keys with their Dutch names, in the order shown to the user. */
    val LABELS = linkedMapOf(
        "ring" to "Aanbellen",
        "person" to "Persoon gezien",
        "motion" to "Beweging",
        "package" to "Pakket",
        "vehicle" to "Voertuig",
        "pet" to "Dier",
        "other" to "Overig",
    )

    fun name(type: String, labels: JSONObject): String =
        labels.optString(type).takeIf { it.isNotEmpty() }?.let { LABELS[it] } ?: "Onbekende gebeurtenis ($type)"

    /** Per-type statistics: {"10": {"n": 5, "last": 1700000000}}. */
    fun record(stats: JSONObject, type: String, epochSec: Long): JSONObject {
        val o = stats.optJSONObject(type) ?: JSONObject()
        o.put("n", o.optInt("n") + 1)
        o.put("last", maxOf(o.optLong("last"), epochSec))
        stats.put(type, o)
        return stats
    }

    /**
     * Picks the doorbell-button type from the types seen while the user pressed the bell:
     * walking up to the door also causes motion/person events, which happen far more
     * often, so the rarest candidate is the button.
     */
    fun pickRing(candidates: Collection<String>, stats: JSONObject, labels: JSONObject): String? {
        val notOther = candidates.filter { labels.optString(it) !in setOf("motion", "person", "vehicle", "pet", "package") }
        val pool = notOther.ifEmpty { candidates.toList() }
        return pool.minByOrNull { stats.optJSONObject(it)?.optInt("n") ?: 0 }
    }

    /** "5× · laatst vandaag 16:40" */
    fun describe(stats: JSONObject, type: String, now: Long = System.currentTimeMillis() / 1000): String {
        val o = stats.optJSONObject(type) ?: return "Nog niet gezien sinds de installatie"
        val last = o.optLong("last")
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = last * 1000 }
        val today = java.util.Calendar.getInstance()
        val hhmm = String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        val day = when {
            cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) -> "vandaag"
            now - last < 2 * 86400 -> "gisteren"
            else -> String.format("%d-%d", cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.MONTH) + 1)
        }
        return "${o.optInt("n")}× gezien · laatst $day $hhmm"
    }
}
