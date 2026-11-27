package nl.tapo.deurbel.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ZonesTest {
    @Test fun rectangleRoundTripKeepsFormat() {
        val info = JSONArray("""[{"region_info_1":{"x1":"1000","y1":"2000","x2":"9000","y2":"9500"}}]""")
        val z = DetectionZones.parse(info)
        assertEquals(1, z.zones.size); assertEquals(true, z.zones[0].isRectangle)
        assertEquals(0.1, z.zones[0].points[0].first, 1e-9)
        val moved = z.withZones(listOf(z.zones[0].moved(listOf(0.2 to 0.3, 0.8 to 0.9))))
        val out = moved.toRegionInfo().getJSONObject(0).getJSONObject("region_info_1")
        assertEquals("2000", out.get("x1")); assertEquals("9000", out.get("y2"))
    }

    @Test fun polygonWithNumbers() {
        val info = JSONObject("""{"pt_num":4,"x1":500,"y1":9900,"x2":2000,"y2":6000,"x3":7000,"y3":6000,"x4":8200,"y4":9800}""")
        val z = DetectionZones.parse(info)
        assertEquals(4, z.zones[0].points.size)
        val out = z.toRegionInfo().getJSONObject(0)
        assertEquals(500, out.get("x1")); assertEquals(4, out.get("pt_num"))
    }
}
