package nl.tapo.deurbel.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Motion-detection zones of a camera. Points are normalised (0..1). The camera's own
 * format (numbered x1/y1… keys, string or number values) is kept, so a zone is written
 * back exactly the way the camera reported it; only the coordinates change.
 */
class DetectionZones(val zones: List<Zone>, private val template: JSONObject?, private val wrapped: Boolean) {

    class Zone(val points: List<Pair<Double, Double>>, internal val raw: JSONObject?, internal val wrapKey: String?) {
        /** Two points = rectangle (opposite corners), more = polygon. */
        val isRectangle get() = points.size == 2
        fun moved(newPoints: List<Pair<Double, Double>>) = Zone(newPoints, raw, wrapKey)
    }

    fun withZones(z: List<Zone>) = DetectionZones(z, template, wrapped)

    /** The region_info array to send back to the camera. */
    fun toRegionInfo(): JSONArray {
        val arr = JSONArray()
        zones.forEachIndexed { i, z ->
            val base = z.raw?.let { JSONObject(it.toString()) } ?: template?.let { JSONObject(it.toString()) } ?: JSONObject()
            val asString = base.keys().asSequence().firstOrNull { it.matches(Regex("[xy]\\d+")) }?.let { base.opt(it) is String } ?: true
            // drop old coordinates, write the new ones
            base.keys().asSequence().toList().filter { it.matches(Regex("[xy]\\d+")) }.forEach { base.remove(it) }
            z.points.forEachIndexed { n, (x, y) ->
                val xv = (x.coerceIn(0.0, 1.0) * SCALE).toInt(); val yv = (y.coerceIn(0.0, 1.0) * SCALE).toInt()
                base.put("x${n + 1}", if (asString) xv.toString() else xv)
                base.put("y${n + 1}", if (asString) yv.toString() else yv)
            }
            if (base.has("pt_num") || z.points.size > 2) base.put("pt_num", if (asString) z.points.size.toString() else z.points.size)
            arr.put(if (wrapped) JSONObject().put(z.wrapKey ?: "region_info_${i + 1}", base) else base)
        }
        return arr
    }

    companion object {
        const val SCALE = 10000.0

        fun parse(regionInfo: Any?): DetectionZones {
            val list = when (regionInfo) {
                is JSONArray -> (0 until regionInfo.length()).mapNotNull { regionInfo.optJSONObject(it) }
                is JSONObject -> listOf(regionInfo)
                else -> emptyList()
            }
            var wrapped = false
            val zones = list.mapNotNull { o ->
                val keys = o.keys().asSequence().toList()
                val (wrapKey, obj) = if (keys.size == 1 && o.optJSONObject(keys[0]) != null) { wrapped = true; keys[0] to o.getJSONObject(keys[0]) } else null to o
                val idx = obj.keys().asSequence().mapNotNull { Regex("x(\\d+)").matchEntire(it)?.groupValues?.get(1)?.toInt() }.sorted().toList()
                val pts = idx.mapNotNull { n ->
                    val x = obj.opt("x$n")?.toString()?.toDoubleOrNull(); val y = obj.opt("y$n")?.toString()?.toDoubleOrNull()
                    if (x == null || y == null) null else (x / SCALE) to (y / SCALE)
                }
                if (pts.size >= 2) Zone(pts, obj, wrapKey) else null
            }
            return DetectionZones(zones, zones.firstOrNull()?.raw, wrapped)
        }

        /** A new rectangle covering the middle of the image. */
        fun newZone() = Zone(listOf(0.2 to 0.35, 0.8 to 0.95), null, null)
    }
}
