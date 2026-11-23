package nl.tapo.deurbel.server

import nl.tapo.deurbel.core.Recording
import nl.tapo.deurbel.core.RecordingDownloader
import nl.tapo.deurbel.core.TalkSession
import nl.tapo.deurbel.core.TsDemuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Clips, text-to-speech and speaker playback (uses ffmpeg, piper / espeak-ng). */
class Media(private val dataDir: File, private val bell: Doorbell, private val store: Store) {
    private val clipDir = File(dataDir, "clips").apply { mkdirs() }
    private val voiceDir = File(dataDir, "voice").apply { mkdirs() }
    private val ttsDir = File(dataDir, "tts").apply { mkdirs() }
    private val locks = ConcurrentHashMap<Long, Any>()
    private val speakerLock = Any()

    // ---------------- clips ----------------
    fun cached(start: Long): File? = listOf("mp4", "ts").map { File(clipDir, "$start.$it") }.firstOrNull { it.length() > 0 }

    /** Fetches the recording from the SD card and converts it to MP4 (with AAC audio). */
    fun clip(rec: Recording): File = synchronized(locks.computeIfAbsent(rec.startTime) { Any() }) {
        cached(rec.startTime)?.let { return it }
        val ts = File(clipDir, "${rec.startTime}.part")
        RecordingDownloader.download(bell.client(), rec, ts)
        val mp4 = File(clipDir, "${rec.startTime}.mp4")
        try {
            toMp4(ts, mp4)
            ts.delete()
            mp4
        } catch (e: Exception) {
            Log.w("MP4-conversie mislukt: ${e.message}")
            mp4.delete()
            File(clipDir, "${rec.startTime}.ts").also { ts.renameTo(it) }
        }
    }

    private fun toMp4(ts: File, mp4: File) {
        val audio = File(clipDir, "${ts.nameWithoutExtension}.alaw")
        var firstVideo: Long? = null
        var firstAudio: Long? = null
        var hevc = false
        var ulaw = bell.audioUlaw
        audio.outputStream().buffered().use { out ->
            val d = TsDemuxer { st, _, pts, payload ->
                when (st) {
                    TsDemuxer.STREAM_PCMA, TsDemuxer.STREAM_PCMU -> {
                        if (firstAudio == null) firstAudio = pts
                        if (st == TsDemuxer.STREAM_PCMU) ulaw = true
                        out.write(payload)
                    }
                    0x1b -> if (firstVideo == null) firstVideo = pts
                    0x24 -> { hevc = true; if (firstVideo == null) firstVideo = pts }
                }
            }
            ts.inputStream().buffered().use { inp ->
                val buf = ByteArray(188 * 512)
                while (true) { val n = inp.read(buf); if (n <= 0) break; d.feed(buf.copyOf(n)) }
            }
            d.flush()
        }
        val cmd = mutableListOf("ffmpeg", "-y", "-loglevel", "error", "-f", "mpegts", "-i", ts.path)
        val hasAudio = audio.length() > 0
        if (hasAudio) {
            val offset = if (firstAudio != null && firstVideo != null) (firstAudio!! - firstVideo!!) / 90000.0 else 0.0
            cmd += listOf("-itsoffset", "%.3f".format(java.util.Locale.US, offset), "-f", if (ulaw) "mulaw" else "alaw",
                "-ar", bell.audioRate.toString(), "-i", audio.path)
        }
        cmd += listOf("-map", "0:v:0")
        if (hasAudio) cmd += listOf("-map", "1:a:0", "-c:a", "aac", "-b:a", "64k")
        cmd += listOf("-c:v", "copy")
        if (hevc) cmd += listOf("-tag:v", "hvc1")
        cmd += listOf("-movflags", "+faststart", mp4.path)
        try { run(cmd, 300) } finally { audio.delete() }
    }

    fun deleteClip(start: Long) {
        listOf("mp4", "ts", "part").forEach { File(clipDir, "$start.$it").delete() }
        store.putStringSet("hidden", store.stringSet("hidden") + start.toString())
    }

    /** Keeps the clip cache below ~2 GB. */
    fun trimCache(maxBytes: Long = 2L shl 30) {
        val files = clipDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= maxBytes) break; total -= f.length(); f.delete() }
    }

    // ---------------- audio ----------------
    fun voiceFile(id: String) = File(voiceDir, "$id.pcm")
    fun hasVoice(id: String) = voiceFile(id).length() > 1600

    /** Converts an uploaded recording (webm/mp4/wav from the browser) to 8 kHz PCM. */
    fun saveVoice(id: String, upload: ByteArray) {
        val tmp = File.createTempFile("voice", ".bin", voiceDir)
        try {
            tmp.writeBytes(upload)
            val pcm = toPcm8k(tmp)
            writePcm(voiceFile(id), normalize(trimSilence(pcm)))
        } finally { tmp.delete() }
    }

    private fun ttsFile(r: Reply) = File(ttsDir, "${r.id}_${Integer.toHexString(r.text.hashCode())}.pcm")

    fun pcmFor(r: Reply): ShortArray {
        if (r.useVoice && hasVoice(r.id)) return readPcm(voiceFile(r.id))
        val f = ttsFile(r)
        if (!f.exists()) {
            val wav = File.createTempFile("tts", ".wav", ttsDir)
            try {
                synthesize(r.text, wav)
                writePcm(f, normalize(toPcm8k(wav)))
            } finally { wav.delete() }
        }
        return readPcm(f)
    }

    /** Dutch speech: piper (natural voice) if installed, otherwise espeak-ng. */
    private fun synthesize(text: String, wav: File) {
        val piper = File("/opt/piper/piper")
        val voice = File("/opt/voices/nl.onnx")
        if (piper.canExecute() && voice.exists()) {
            try {
                val p = ProcessBuilder(piper.path, "--model", voice.path, "--output_file", wav.path)
                    .redirectErrorStream(true).start()
                p.outputStream.use { it.write(text.toByteArray()) }
                p.inputStream.readBytes()
                if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0 && wav.length() > 44) return
            } catch (e: Exception) { Log.w("piper mislukt: ${e.message}") }
        }
        run(listOf("espeak-ng", "-v", "nl", "-s", "150", "-w", wav.path, text), 60)
    }

    fun preview(r: Reply): ByteArray = wav8k(pcmFor(r))

    /** Speaks the reply through the doorbell speaker. */
    fun playAtDoor(r: Reply) {
        val pcm = pcmFor(r)
        synchronized(speakerLock) {
            TalkSession(bell.client()).use { it.playPcm(pcm) }
        }
    }

    private fun toPcm8k(input: File): ShortArray {
        val out = File.createTempFile("pcm", ".raw", ttsDir)
        try {
            run(listOf("ffmpeg", "-y", "-loglevel", "error", "-i", input.path, "-ac", "1", "-ar", "8000", "-f", "s16le", out.path), 120)
            return readPcm(out)
        } finally { out.delete() }
    }

    private fun run(cmd: List<String>, timeoutSec: Long) {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); throw IllegalStateException("${cmd[0]} duurde te lang") }
        if (p.exitValue() != 0) throw IllegalStateException("${cmd[0]} mislukt: ${output.take(300)}")
    }

    companion object {
        fun readPcm(f: File): ShortArray {
            val b = f.readBytes(); val s = ShortArray(b.size / 2)
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s); return s
        }

        fun writePcm(f: File, pcm: ShortArray) {
            val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(pcm); f.writeBytes(bb.array())
        }

        fun normalize(pcm: ShortArray, target: Double = 0.9): ShortArray {
            val peak = pcm.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            if (peak < 200) return pcm
            val g = minOf(8.0, target * 32767 / peak)
            return ShortArray(pcm.size) { (pcm[it] * g).toInt().coerceIn(-32768, 32767).toShort() }
        }

        fun trimSilence(pcm: ShortArray, threshold: Int = 600): ShortArray {
            val first = pcm.indexOfFirst { kotlin.math.abs(it.toInt()) > threshold }
            val last = pcm.indexOfLast { kotlin.math.abs(it.toInt()) > threshold }
            if (first < 0) return pcm
            return pcm.copyOfRange(maxOf(0, first - 800), minOf(pcm.size, last + 1600))
        }

        fun wav8k(pcm: ShortArray): ByteArray {
            val data = pcm.size * 2
            val bb = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            bb.put("data".toByteArray()).putInt(data)
            pcm.forEach { bb.putShort(it) }
            return bb.array()
        }
    }
}
