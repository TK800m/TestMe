package nl.tapo.deurbel.core

/** G.711 A-law / µ-law codecs (8 kHz telephone audio used by Tapo cameras). */
object G711 {
    private val alawDecode = ShortArray(256) { decodeAlaw(it).toShort() }
    private val ulawDecode = ShortArray(256) { decodeUlaw(it).toShort() }

    private fun decodeAlaw(v: Int): Int {
        var a = v xor 0x55
        var t = (a and 0x0f) shl 4
        val seg = (a and 0x70) shr 4
        t = when (seg) {
            0 -> t + 8
            1 -> t + 0x108
            else -> (t + 0x108) shl (seg - 1)
        }
        return if (a and 0x80 != 0) t else -t
    }

    private fun decodeUlaw(v: Int): Int {
        val u = v.inv() and 0xff
        var t = ((u and 0x0f) shl 3) + 0x84
        t = t shl ((u and 0x70) shr 4)
        return if (u and 0x80 != 0) 0x84 - t else t - 0x84
    }

    fun decode(data: ByteArray, ulaw: Boolean): ShortArray {
        val table = if (ulaw) ulawDecode else alawDecode
        return ShortArray(data.size) { table[data[it].toInt() and 0xff] }
    }

    fun encodeAlaw(pcm: Short): Byte {
        var s = pcm.toInt()
        val mask: Int
        if (s >= 0) { mask = 0xD5 } else { mask = 0x55; s = -s - 1 }
        if (s > 32767) s = 32767
        val segEnd = intArrayOf(0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF)
        val v = s shr 3
        var seg = 0
        while (seg < 8 && v > segEnd[seg]) seg++
        if (seg >= 8) return (0x7F xor mask).toByte()
        var aval = seg shl 4
        aval = aval or if (seg < 2) (v shr 1) and 0x0F else (v shr seg) and 0x0F
        return (aval xor mask).toByte()
    }

    fun encodeAlaw(pcm: ShortArray, off: Int = 0, len: Int = pcm.size): ByteArray =
        ByteArray(len) { encodeAlaw(pcm[off + it]) }
}

/** Builds the MPEG-TS stream (PCMA, stream type 0x90) that the doorbell speaker expects. */
class TalkMuxer {
    private var counter = 0
    private var samples = 0L

    fun header(): ByteArray = psi(0, hex("00b00d0001c100000001f000")) + psi(0x1000, hex("02b0120001c10000fffff00090e100f000"))

    /** Wraps up to ~65k bytes of A-law audio (8 kHz) into TS packets. */
    fun packets(audio: ByteArray): ByteArray {
        val pts = (samples * 90000 / 8000) and ((1L shl 33) - 1)
        val ts = byteArrayOf(
            (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte(),
            ((pts shr 22) and 0xFF).toByte(),
            (1 or ((pts shr 14) and 0xFE).toInt()).toByte(),
            ((pts shr 7) and 0xFF).toByte(),
            (1 or ((pts shl 1) and 0xFE).toInt()).toByte(),
        )
        val len = audio.size + 8
        var payload = byteArrayOf(0, 0, 1, 0xC0.toByte(), (len shr 8).toByte(), len.toByte(), 0x80.toByte(), 0x80.toByte(), 5) + ts + audio
        val out = java.io.ByteArrayOutputStream()
        var first = true
        while (payload.isNotEmpty()) {
            val chunk = payload.copyOf(minOf(184, payload.size))
            payload = payload.copyOfRange(chunk.size, payload.size)
            val flags = if (chunk.size == 184) 0x10 else 0x30
            out.write(0x47); out.write(if (first) 0x41 else 0x01); out.write(0); out.write(flags or counter)
            if (chunk.size < 184) {
                val padding = 183 - chunk.size
                out.write(padding)
                if (padding > 0) { out.write(0); repeat(padding - 1) { out.write(0xff) } }
            }
            out.write(chunk)
            counter = (counter + 1) and 0x0F
            first = false
        }
        samples += audio.size
        return out.toByteArray()
    }

    companion object {
        fun crc32Mpeg(data: ByteArray): Int {
            var crc = -1
            for (b in data) {
                crc = crc xor ((b.toInt() and 0xff) shl 24)
                repeat(8) { crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }

        private fun psi(pid: Int, section: ByteArray): ByteArray {
            val crc = crc32Mpeg(section)
            val p = byteArrayOf(0x47, (0x40 or (pid shr 8)).toByte(), (pid and 0xff).toByte(), 0x10, 0) + section +
                byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
            return p + ByteArray(188 - p.size) { 0xff.toByte() }
        }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * Minimal MPEG-TS demuxer: reports each complete PES packet with its stream type
 * and PTS (90 kHz). Used to pull the G.711 audio (type 0x90) out of Tapo streams.
 */
class TsDemuxer(private val onPes: (streamType: Int, pid: Int, pts: Long?, payload: ByteArray) -> Unit) {
    private val pending = java.io.ByteArrayOutputStream()
    private var pmtPid = -1
    private val streamTypes = HashMap<Int, Int>()
    private val pes = HashMap<Int, java.io.ByteArrayOutputStream>()

    fun feed(data: ByteArray) {
        pending.write(data)
        val buf = pending.toByteArray()
        var i = 0
        while (i + 188 <= buf.size) {
            if (buf[i] != 0x47.toByte()) { i++; continue }
            packet(buf, i)
            i += 188
        }
        pending.reset()
        pending.write(buf, i, buf.size - i)
    }

    fun flush() {
        for ((pid, b) in pes) emit(pid, b.toByteArray())
        pes.clear()
    }

    private fun packet(b: ByteArray, o: Int) {
        val pusi = b[o + 1].toInt() and 0x40 != 0
        val pid = ((b[o + 1].toInt() and 0x1f) shl 8) or (b[o + 2].toInt() and 0xff)
        val afc = (b[o + 3].toInt() shr 4) and 3
        var p = o + 4
        if (afc == 2 || afc == 0) return
        if (afc == 3) p += 1 + (b[p].toInt() and 0xff)
        if (p >= o + 188) return
        val end = o + 188
        if (pid == 0) {
            if (pusi) p += 1 + (b[p].toInt() and 0xff)
            val sectionLen = ((b[p + 1].toInt() and 0x0f) shl 8) or (b[p + 2].toInt() and 0xff)
            var q = p + 8
            val last = minOf(p + 3 + sectionLen - 4, end)
            while (q + 4 <= last) {
                val program = ((b[q].toInt() and 0xff) shl 8) or (b[q + 1].toInt() and 0xff)
                val ppid = ((b[q + 2].toInt() and 0x1f) shl 8) or (b[q + 3].toInt() and 0xff)
                if (program != 0) pmtPid = ppid
                q += 4
            }
            return
        }
        if (pid == pmtPid) {
            if (pusi) p += 1 + (b[p].toInt() and 0xff)
            val sectionLen = ((b[p + 1].toInt() and 0x0f) shl 8) or (b[p + 2].toInt() and 0xff)
            val progInfoLen = ((b[p + 10].toInt() and 0x0f) shl 8) or (b[p + 11].toInt() and 0xff)
            var q = p + 12 + progInfoLen
            val last = minOf(p + 3 + sectionLen - 4, end)
            while (q + 5 <= last) {
                val st = b[q].toInt() and 0xff
                val epid = ((b[q + 1].toInt() and 0x1f) shl 8) or (b[q + 2].toInt() and 0xff)
                val esInfo = ((b[q + 3].toInt() and 0x0f) shl 8) or (b[q + 4].toInt() and 0xff)
                streamTypes[epid] = st
                q += 5 + esInfo
            }
            return
        }
        if (!streamTypes.containsKey(pid)) return
        if (pusi) {
            pes.remove(pid)?.let { emit(pid, it.toByteArray()) }
            pes[pid] = java.io.ByteArrayOutputStream()
        }
        pes[pid]?.write(b, p, end - p)
    }

    private fun emit(pid: Int, d: ByteArray) {
        if (d.size < 9 || d[0].toInt() != 0 || d[1].toInt() != 0 || d[2].toInt() != 1) return
        val flags = d[7].toInt() and 0xff
        val hlen = d[8].toInt() and 0xff
        var pts: Long? = null
        if (flags and 0x80 != 0 && d.size >= 14) {
            pts = (((d[9].toLong() and 0x0e) shl 29) or ((d[10].toLong() and 0xff) shl 22) or
                ((d[11].toLong() and 0xfe) shl 14) or ((d[12].toLong() and 0xff) shl 7) or ((d[13].toLong() and 0xfe) shr 1))
        }
        val start = 9 + hlen
        if (start > d.size) return
        var payload = d.copyOfRange(start, d.size)
        val pesLen = ((d[4].toInt() and 0xff) shl 8) or (d[5].toInt() and 0xff)
        if (pesLen > 0 && 6 + pesLen <= d.size) payload = d.copyOfRange(start, 6 + pesLen)
        onPes(streamTypes[pid] ?: 0, pid, pts, payload)
    }

    companion object {
        const val STREAM_PCMA = 0x90
        const val STREAM_PCMU = 0x91
    }
}
