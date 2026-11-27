package nl.tapo.deurbel.core

import org.json.JSONArray
import org.json.JSONObject

/** One recording segment on the doorbell's SD card (times in camera clock seconds). */
data class Recording(val startTime: Long, val endTime: Long, val type: Int)

/** A detection event (doorbell press, person, motion...). */
data class DetectionEvent(val startTime: Long, val endTime: Long, val type: String, val raw: String)

/**
 * Local control API of a Tapo camera/doorbell. Uses the TP-Link (cloud) account
 * password with the built-in "admin" user, like the Tapo app does on the LAN.
 */
class TapoClient(val host: String, private val cloudPassword: String, private val https: CameraHttps) {

    private val transport: Transport by lazy { chooseTransport() }
    private var userId: String? = null
    private var timeCorrection: Long? = null

    private fun chooseTransport(): Transport {
        val disc = TpapTransport.discover(host, https)
        val tpap = TpapTransport.tpapFromDiscover(disc)
        return if (tpap != null || (!TpapTransport.legacyOffered(disc) && TpapTransport.rejectsLegacy(host, https))) {
            TpapTransport(host, cloudPassword, https, tpap)
        } else {
            LegacyTransport(host, cloudPassword, https)
        }
    }

    val preferSha256: Boolean get() = transport.preferSha256

    fun execute(method: String, params: JSONObject?): JSONObject {
        val inner = JSONObject().put("method", method)
        if (params != null) inner.put("params", params)
        val req = JSONObject().put("method", "multipleRequest")
            .put("params", JSONObject().put("requests", JSONArray().put(inner)))
        val res = transport.send(req)
        val top = res.optInt("error_code", 0)
        if (top != 0) throw TapoException(top, describe(top))
        val r = res.optJSONObject("result")?.optJSONArray("responses")?.optJSONObject(0)
            ?: throw TapoException(null, "Leeg antwoord op $method")
        val code = r.optInt("error_code", 0)
        if (code != 0) throw TapoException(code, describe(code))
        return r.optJSONObject("result") ?: JSONObject()
    }

    fun close() = transport.close()

    // ---- device ----
    fun deviceInfo(): JSONObject =
        execute("getDeviceInfo", JSONObject().put("device_info", JSONObject().put("name", JSONArray().put("basic_info"))))
            .optJSONObject("device_info")?.optJSONObject("basic_info") ?: JSONObject()

    fun cameraClock(): Long =
        execute("getClockStatus", JSONObject().put("system", JSONObject().put("name", "clock_status")))
            .getJSONObject("system").getJSONObject("clock_status").getLong("seconds_from_1970")

    /** Seconds to add to a camera timestamp to get the phone's epoch time. */
    fun timeCorrection(): Long {
        timeCorrection?.let { return it }
        val c = System.currentTimeMillis() / 1000 - cameraClock()
        timeCorrection = c
        return c
    }

    fun audioConfig(): JSONObject = execute("getAudioConfig", JSONObject().put("method", "get")
        .put("audio_config", JSONObject().put("name", JSONArray().put("speaker").put("microphone").put("record_audio"))))

    fun userId(force: Boolean = false): String {
        if (!force) userId?.let { return it }
        val r = execute("getUserID", JSONObject().put("system", JSONObject().put("get_user_id", "null")))
        return r.get("user_id").toString().also { userId = it }
    }

    // ---- events ----
    fun events(startCam: Long, endCam: Long): List<DetectionEvent> {
        val r = execute("searchDetectionList", JSONObject().put("playback", JSONObject().put("search_detection_list",
            JSONObject().put("start_index", 0).put("channel", 0).put("start_time", startCam)
                .put("end_time", endCam).put("end_index", 999))))
        val list = r.optJSONObject("playback")?.optJSONArray("search_detection_list") ?: return emptyList()
        return flatten(list).map {
            DetectionEvent(
                it.optLong("start_time"), it.optLong("end_time"),
                it.optString("alarm_type", it.optString("event_type", it.optString("type", ""))),
                it.toString()
            )
        }
    }

    // ---- recordings ----
    fun recordingDays(startDate: String, endDate: String): List<String> {
        val r = execute("searchDateWithVideo", JSONObject().put("playback", JSONObject().put("search_year_utility",
            JSONObject().put("channel", JSONArray().put(0)).put("end_date", endDate).put("start_date", startDate))))
        val list = r.optJSONObject("playback")?.optJSONArray("search_results") ?: return emptyList()
        return flatten(list).mapNotNull { it.optString("date").ifEmpty { null } }
    }

    fun recordingsOfDay(date: String): List<Recording> {
        fun call(): JSONObject = execute("searchVideoOfDay", JSONObject().put("playback", JSONObject().put("search_video_utility",
            JSONObject().put("channel", 0).put("date", date).put("end_index", 999999999)
                .put("id", userId()).put("start_index", 0))))
        val r = try { call() } catch (e: TapoException) {
            if (e.code == -71103 || e.code == -71105) { userId(force = true); call() } else throw e
        }
        val list = r.optJSONObject("playback")?.optJSONArray("search_video_results") ?: return emptyList()
        return flatten(list).map {
            Recording(it.optLong("startTime"), it.optLong("endTime"), it.optInt("vedio_type", it.optInt("video_type", 0)))
        }
    }

    // ---- chime / ring ----
    fun chimeRingPlan(): JSONObject? = try {
        execute("getChimeRingPlan", JSONObject().put("chime_ring_plan", JSONObject().put("name", "chn1_chime_ring_plan")))
            .optJSONObject("chime_ring_plan")?.optJSONObject("chn1_chime_ring_plan")
    } catch (e: TapoException) { null }

    fun setChimeEnabled(enabled: Boolean) {
        val current = chimeRingPlan()
        val p = JSONObject().put("enabled", if (enabled) "on" else "off")
        current?.optString("ring_plan_1")?.takeIf { it.isNotEmpty() }?.let { p.put("ring_plan_1", it) }
        execute("setChimeRingPlan", JSONObject().put("chime_ring_plan", JSONObject().put("chn1_chime_ring_plan", p)))
    }

    fun pairedChimes(): JSONObject? = try {
        execute("getChimeCtrlList", JSONObject().put("chime_ctrl", JSONObject().put("get_paired_device_list", JSONObject())))
    } catch (e: TapoException) { null }

    fun ringStatus(): JSONObject? = try {
        execute("getRingStatus", JSONObject().put("ring", JSONObject().put("name", "status")))
            .optJSONObject("ring")?.optJSONObject("status")
    } catch (e: TapoException) { null }

    fun setRingEnabled(enabled: Boolean) {
        execute("setRingStatus", JSONObject().put("ring", JSONObject().put("status",
            JSONObject().put("enabled", if (enabled) "on" else "off"))))
    }

    fun sdCard(): JSONArray? = try {
        execute("getSdCardStatus", JSONObject().put("harddisk_manage", JSONObject().put("table", JSONArray().put("hd_info"))))
            .optJSONObject("harddisk_manage")?.optJSONArray("hd_info")
    } catch (e: TapoException) { null }

    // ---- device kind ----
    /** True for doorbells (Tapo D-series), false for cameras. */
    fun isDoorbell(info: JSONObject = deviceInfo()): Boolean =
        info.optString("device_model").uppercase().startsWith("D") || info.optString("device_type").contains("DOORBELL", true)

    // ---- detection zones ----
    fun detectionZones(): DetectionZones {
        val r = execute("getDetectionRegion", JSONObject().put("motion_detection", JSONObject().put("table", JSONArray().put("region_info"))))
        return DetectionZones.parse(r.optJSONObject("motion_detection")?.opt("region_info"))
    }

    class ZoneSaveException(msg: String, val details: String) : Exception(msg)

    /**
     * Saves the zones. Tapo firmware has no single "set" for zones: the Tapo app adds new
     * regions ("addDetectionRegion" / add_md_regions) and removes old ones. To never leave the
     * doorbell without zones, the new zones are added first and the old ones removed after.
     * Every attempt is logged so problems can be diagnosed.
     */
    fun setDetectionZones(z: DetectionZones, old: DetectionZones) {
        val log = StringBuilder()
        fun attempt(label: String, block: () -> JSONObject): Boolean = try {
            val r = block(); log.append("$label → ok\n"); true
        } catch (e: TapoException) { log.append("$label → ${e.code ?: e.message}\n"); false }
        val md = { o: JSONObject -> JSONObject().put("motion_detection", o) }
        val regions = z.toRegionInfo()
        val plain = JSONArray((0 until regions.length()).map { i ->
            regions.getJSONObject(i).let { o -> o.keys().asSequence().toList().let { k -> if (k.size == 1 && o.optJSONObject(k[0]) != null) o.getJSONObject(k[0]) else o } }
        })

        // 1. some firmware accepts a full replacement
        if (attempt("setDetectionRegion") { execute("setDetectionRegion", md(JSONObject().put("region_info", regions))) }) return

        // 2. add the new zones (all at once, else one by one)
        var added = attempt("addDetectionRegion[]") { execute("addDetectionRegion", md(JSONObject().put("add_md_regions", plain))) }
        if (!added && plain.length() > 0) {
            added = (0 until plain.length()).all { i ->
                attempt("addDetectionRegion #${i + 1}") { execute("addDetectionRegion", md(JSONObject().put("add_md_regions", plain.getJSONObject(i)))) }
            }
        }
        if (!added && plain.length() > 0) {
            throw ZoneSaveException("De deurbel accepteerde de nieuwe zone niet. Er is niets gewijzigd.", log.toString())
        }

        // 3. remove the old zones (they keep the first positions)
        if (old.zones.isEmpty()) return
        val n = old.zones.size
        val idx = JSONArray((1..n).map { it.toString() })
        val names = JSONArray(old.zones.mapIndexed { i, zz -> zz.wrapKeyOrNull() ?: "region_info_${i + 1}" })
        val removed = attempt("deleteDetectionRegion index") { execute("deleteDetectionRegion", md(JSONObject().put("delete_md_regions", JSONObject().put("index", idx)))) } ||
            attempt("deleteDetectionRegion name") { execute("deleteDetectionRegion", md(JSONObject().put("delete", JSONObject().put("name", names)))) } ||
            attempt("deleteDetectionRegion list") { execute("deleteDetectionRegion", md(JSONObject().put("delete_md_regions", idx))) }
        if (!removed) throw ZoneSaveException(
            "De nieuwe zone is toegevoegd, maar de oude kon niet worden verwijderd. Verwijder de oude zone in de Tapo-app.", log.toString())
    }

    // ---- recording (energy / storage) ----
    /** True when every day is set to record on events only (":2"), false when it records continuously somewhere. */
    fun recordsEventsOnly(): Boolean? = try {
        val plan = execute("getRecordPlan", JSONObject().put("record_plan", JSONObject().put("name", JSONArray().put("chn1_channel"))))
            .optJSONObject("record_plan")?.optJSONObject("chn1_channel")
        plan?.let { p -> DAYS.none { (p.optString(it)).contains(":1") } }
    } catch (e: TapoException) { null }

    /** Records only when something happens (saves SD-card space and energy). */
    fun setRecordEventsOnly(eventsOnly: Boolean) {
        val plan = JSONObject().put("enabled", "on")
        val day = JSONArray().put(if (eventsOnly) "0000-2400:2" else "0000-2400:1").toString()
        DAYS.forEach { plan.put(it, day) }
        execute("setRecordPlan", JSONObject().put("record_plan", JSONObject().put("chn1_channel", plan)))
    }

    // ---- battery / power ----
    /**
     * Battery and power information. Different Tapo models report this in different
     * places, so everything is collected and interpreted by [PowerInfo.from].
     */
    fun power(): PowerInfo {
        val parts = JSONObject()
        try { parts.put("basic_info", deviceInfo()) } catch (_: Exception) {}
        fun opt(name: String, method: String, params: JSONObject, vararg path: String) = try {
            var o: JSONObject? = execute(method, params)
            for (k in path) o = o?.optJSONObject(k)
            o?.let { parts.put(name, it) }
        } catch (_: TapoException) { }
        opt("battery_status", "getBatteryStatus", JSONObject().put("battery", JSONObject().put("name", "status")), "battery", "status")
        opt("power_mode", "getPowerMode", JSONObject().put("battery", JSONObject().put("name", "power")), "battery", "power")
        return PowerInfo.from(parts)
    }

    // ---- firmware ----
    /** Asks the doorbell to check TP-Link for new firmware; returns the camera's upgrade_info. */
    fun checkFirmware(): JSONObject {
        val req = JSONObject().put("method", "multipleRequest").put("params", JSONObject().put("requests", JSONArray()
            .put(JSONObject().put("method", "checkFirmwareVersionByCloud").put("params", JSONObject().put("cloud_config", JSONObject().put("check_fw_version", "null"))))
            .put(JSONObject().put("method", "getCloudConfig").put("params", JSONObject().put("cloud_config", JSONObject().put("name", JSONArray().put("upgrade_info")))))))
        val res = transport.send(req)
        val responses = res.optJSONObject("result")?.optJSONArray("responses") ?: throw TapoException(res.optInt("error_code"), "Geen antwoord")
        for (i in 0 until responses.length()) {
            val r = responses.optJSONObject(i) ?: continue
            r.optJSONObject("result")?.optJSONObject("cloud_config")?.optJSONObject("upgrade_info")?.let { return it }
        }
        val code = (0 until responses.length()).map { responses.optJSONObject(it)?.optInt("error_code", 0) ?: 0 }.firstOrNull { it != 0 }
        throw TapoException(code, code?.let { describe(it) } ?: "Geen firmware-informatie ontvangen")
    }

    /** Starts downloading and installing the new firmware (the doorbell restarts afterwards). */
    fun startFirmwareUpgrade() {
        val res = transport.send(JSONObject().put("method", "do").put("cloud_config", JSONObject().put("fw_download", "null")))
        val r = res.optJSONObject("result")?.optJSONArray("responses")?.optJSONObject(0) ?: res
        val code = r.optInt("error_code", 0)
        if (code != 0) throw TapoException(code, if (code == -1) "Geen nieuwe firmware beschikbaar" else describe(code))
    }

    fun firmwareStatus(): JSONObject? = try {
        execute("getFirmwareUpdateStatus", JSONObject().put("cloud_config", JSONObject().put("name", "upgrade_status")))
            .optJSONObject("cloud_config")?.optJSONObject("upgrade_status")
    } catch (e: TapoException) { null }

    fun autoUpgrade(): Boolean? = try {
        execute("getFirmwareAutoUpgradeConfig", JSONObject().put("auto_upgrade", JSONObject().put("name", JSONArray().put("common"))))
            .optJSONObject("auto_upgrade")?.optJSONObject("common")?.optString("enabled")?.let { it == "on" }
    } catch (e: TapoException) { null }

    /** Lets the device install updates itself at [time] ("HH:mm", local time of the device). */
    fun setAutoUpgrade(on: Boolean, time: String? = null) {
        val p = JSONObject().put("enabled", if (on) "on" else "off").put("random_range", 120)
        if (time != null) p.put("time", time)
        execute("setFirmwareAutoUpgradeConfig", JSONObject().put("auto_upgrade", JSONObject().put("common", p)))
    }

    fun mediaSession(): MediaSession = MediaSession(host, cloudPassword, preferSha256)

    companion object {
        private val DAYS = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

        /** True when upgrade_info describes a newer firmware than [current]. */
        fun firmwareAvailable(info: JSONObject, current: String): Boolean {
            val version = info.optString("version")
            val type = info.optString("type", "0")
            return version.isNotBlank() && type != "0" && !current.startsWith(version.substringBefore(" Build").trim())
        }

        /** Unwraps lists like [{"search_video_results_0": {...}}, ...]. */
        fun flatten(arr: JSONArray): List<JSONObject> = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val keys = o.keys().asSequence().toList()
            if (keys.size == 1 && o.optJSONObject(keys[0]) != null) o.getJSONObject(keys[0]) else o
        }

        fun describe(code: Int): String = when (code) {
            -40401 -> "Sessie verlopen"
            -40404 -> "Deurbel tijdelijk geblokkeerd na te veel pogingen"
            -40411, -40209, -40418 -> "Onjuist wachtwoord"
            -64324 -> "Privacymodus staat aan"
            -52409, -71114 -> "Geen SD-kaart gevonden"
            -71115 -> "SD-kaart wordt geladen"
            -52411 -> "Speaker is al in gebruik"
            -52405, -52407, -52417, -52435 -> "Deurbel is bezet (te veel verbindingen)"
            -40105, -40106, -40210 -> "Functie niet ondersteund door deze deurbel"
            else -> "Fout $code van de deurbel"
        }
    }
}
