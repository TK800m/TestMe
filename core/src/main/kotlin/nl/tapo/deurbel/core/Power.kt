package nl.tapo.deurbel.core

import org.json.JSONObject

/** Interpreted battery/power state of the doorbell. */
data class PowerInfo(
    val percent: Int?,          // null = no battery reported
    val charging: Boolean?,     // null = unknown
    val full: Boolean,
    val external: Boolean?,     // true = mains/adapter/doorbell wires connected, false = running on battery
    val raw: JSONObject,
) {
    val hasBattery get() = percent != null

    /** Short Dutch description, e.g. "85% · laadt op". */
    fun label(): String = when {
        percent == null && external != false -> "Netvoeding"
        percent == null -> "Onbekend"
        full -> "$percent% · vol"
        charging == true -> "$percent% · laadt op"
        external == true -> "$percent% · netvoeding"
        external == false -> "$percent% · op batterij"
        else -> "$percent%"
    }

    val low get() = percent != null && percent <= LOW

    fun toJson(): JSONObject = JSONObject()
        .put("percent", percent ?: JSONObject.NULL)
        .put("charging", charging ?: JSONObject.NULL)
        .put("full", full)
        .put("external", external ?: JSONObject.NULL)
        .put("low", low)
        .put("label", label())
        .put("raw", raw)

    companion object {
        const val LOW = 20
        private val percentKeys = listOf("battery_percent", "percent", "battery_percentage", "percentage", "battery_level", "capacity", "electricity")
        private val chargingKeys = listOf("charging", "is_charging", "charge_status", "charging_status", "battery_charging")
        private val sourceKeys = listOf("power", "power_mode", "power_source", "power_supply", "supply")

        private fun all(o: JSONObject): Sequence<Pair<String, Any>> = sequence {
            for (k in o.keys()) {
                val v = o.get(k)
                if (v is JSONObject) yieldAll(all(v)) else yield(k.lowercase() to v)
            }
        }

        fun from(parts: JSONObject): PowerInfo {
            val entries = all(parts).toList()
            fun first(keys: List<String>) = keys.firstNotNullOfOrNull { k -> entries.firstOrNull { it.first == k }?.second }
            val percent = first(percentKeys)?.toString()?.trim()?.removeSuffix("%")?.toDoubleOrNull()?.toInt()?.takeIf { it in 0..100 }
            val chargeVal = first(chargingKeys)?.toString()?.lowercase()
            val full = chargeVal?.contains("full") == true || (percent == 100 && chargeVal != null && chargeVal != "off" && chargeVal != "false")
            val charging = when {
                chargeVal == null -> null
                chargeVal.contains("full") -> false
                chargeVal in setOf("on", "true", "1", "charging", "yes") || chargeVal.startsWith("charging") -> true
                else -> false
            }
            val src = first(sourceKeys)?.toString()?.lowercase()
            val external = when {
                src == null -> if (charging == true || full) true else null
                listOf("battery", "bat").any { src == it || src.startsWith("battery") } -> charging == true || full
                listOf("adapter", "dc", "ac", "wire", "wired", "mains", "usb", "solar", "power", "external", "transformer").any { src.contains(it) } -> true
                else -> null
            }
            return PowerInfo(percent, charging, full, external, parts)
        }
    }
}
