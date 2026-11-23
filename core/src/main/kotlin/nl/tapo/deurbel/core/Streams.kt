package nl.tapo.deurbel.core

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Live MPEG-TS stream from the doorbell. Video bytes are read with [read]; audio is decoded to PCM. */
class LiveStream(
    client: TapoClient,
    hd: Boolean,
    private val ulaw: Boolean = false,
    @Volatile var onAudio: ((ShortArray) -> Unit)? = null,
) : Closeable {
    private val media = client.mediaSession()
    private val queue = LinkedBlockingQueue<ByteArray>(4096)
    private var current: ByteArray? = null
    private var pos = 0
    @Volatile private var closed = false
    @Volatile private var error: Exception? = null
    private val demux = TsDemuxer { st, _, _, payload ->
        if (st == TsDemuxer.STREAM_PCMA || st == TsDemuxer.STREAM_PCMU) {
            onAudio?.invoke(G711.decode(payload, ulaw || st == TsDemuxer.STREAM_PCMU))
        }
    }

    init {
        media.start()
        val session = media.open(MediaSession.previewRequest(hd))
        Thread({
            try {
                while (!closed) {
                    val p = media.next(15000) ?: if (media.closed) break else throw IOException("Geen beeld ontvangen")
                    if (p.mimeType == "video/mp2t" && (p.sessionId == null || p.sessionId == session)) {
                        if (onAudio != null) demux.feed(p.data)
                        if (!queue.offer(p.data)) { queue.clear(); queue.offer(p.data) }
                    }
                }
            } catch (e: Exception) {
                error = e
            } finally {
                queue.offer(ByteArray(0))
            }
        }, "tapo-live").apply { isDaemon = true; start() }
    }

    /** Blocking read; returns -1 at end of stream. */
    fun read(buf: ByteArray, off: Int, len: Int): Int {
        while (true) {
            val c = current
            if (c != null && pos < c.size) {
                val n = minOf(len, c.size - pos)
                System.arraycopy(c, pos, buf, off, n)
                pos += n
                return n
            }
            if (closed) return -1
            val next = queue.poll(20, TimeUnit.SECONDS) ?: throw IOException("Geen beeld ontvangen")
            if (next.isEmpty()) { error?.let { throw IOException(it.message, it) }; return -1 }
            current = next; pos = 0
        }
    }

    override fun close() {
        closed = true
        media.close()
        queue.offer(ByteArray(0))
    }
}

/** Two-way audio: sends 8 kHz A-law audio to the doorbell speaker. */
class TalkSession(client: TapoClient) : Closeable {
    private val media = client.mediaSession()
    private val muxer = TalkMuxer()
    private val session: Int
    @Volatile private var closed = false

    init {
        media.start()
        try {
            session = media.open(MediaSession.talkRequest(), 6000)
            media.sendAudio(session, muxer.header())
        } catch (e: Exception) {
            media.close(); throw e
        }
        Thread({ try { while (!closed && !media.closed) media.next(60000) } catch (_: Exception) {} },
            "tapo-talk-drain").apply { isDaemon = true; start() }
    }

    fun writeAlaw(alaw: ByteArray) {
        if (closed) throw IOException("Gesprek gesloten")
        var off = 0
        while (off < alaw.size) {
            val n = minOf(8000, alaw.size - off)
            media.sendAudio(session, muxer.packets(alaw.copyOfRange(off, off + n)))
            off += n
        }
    }

    /** Plays 8 kHz mono PCM in real time (20 ms packets), after a short lead-in so the speaker wakes up. */
    fun playPcm(pcm: ShortArray, leadInMs: Int = 600, cancelled: () -> Boolean = { false }) {
        val silence = ShortArray(8 * leadInMs)
        val all = silence + pcm + ShortArray(1600)
        val chunk = 160
        val start = System.nanoTime()
        var sent = 0
        while (sent < all.size && !cancelled()) {
            val n = minOf(chunk, all.size - sent)
            writeAlaw(G711.encodeAlaw(all, sent, n))
            sent += n
            val due = start + sent * 125_000L // 1/8000 s in ns
            val wait = (due - System.nanoTime()) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
        }
    }

    override fun close() {
        closed = true
        media.close()
    }
}

/** Downloads one SD-card recording as decrypted MPEG-TS. */
object RecordingDownloader {
    fun download(
        client: TapoClient,
        rec: Recording,
        out: File,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ) {
        val length = (rec.endTime - rec.startTime).coerceAtLeast(1)
        var usePlayback = false
        repeat(2) {
            val media = client.mediaSession()
            try {
                media.start(if (usePlayback) 50 else 200)
                val req = if (usePlayback) MediaSession.playbackRequest(client.userId(), rec.startTime, rec.endTime)
                else MediaSession.downloadRequest(client.userId(), rec.startTime, rec.endTime, UUID.randomUUID().toString())
                val seq = media.request(req)
                var firstPts: Long? = null
                var lastPts = 0L
                val demux = TsDemuxer { _, _, pts, _ ->
                    if (pts != null) { if (firstPts == null) firstPts = pts; lastPts = pts }
                }
                var bytes = 0L
                var refused = false
                out.outputStream().buffered().use { os ->
                    while (!cancelled()) {
                        val p = media.next(15000) ?: break
                        if (p.mimeType == "video/mp2t") {
                            os.write(p.data); bytes += p.data.size
                            demux.feed(p.data)
                            val dur = firstPts?.let { (lastPts - it) / 90000f } ?: 0f
                            progress((dur / length).coerceIn(0f, 1f))
                            if (usePlayback && dur >= length + 1) break
                        } else if (p.json != null) {
                            val j = p.json
                            val code = j.optJSONObject("params")?.optInt("error_code", 0) ?: 0
                            if (j.optString("type") == "response" && code != 0 && (p.sequence == seq || bytes == 0L)) {
                                if (!usePlayback && code !in listOf(-52405, -52407, -52417, -52435)) { refused = true; break }
                                throw TapoException(code, TapoClient.describe(code))
                            }
                            val params = j.optJSONObject("params")
                            if (j.optString("type") == "notification" && params?.optString("event_type") == "stream_status" &&
                                params.optString("status") == "finished") break
                        }
                    }
                }
                if (refused) { usePlayback = true; return@repeat }
                if (bytes == 0L) throw IOException("Geen opname ontvangen")
                progress(1f)
                return
            } finally {
                media.close()
            }
        }
        throw IOException("Downloaden mislukt")
    }
}
