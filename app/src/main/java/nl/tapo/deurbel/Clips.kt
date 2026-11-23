package nl.tapo.deurbel

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.tapo.deurbel.core.G711
import nl.tapo.deurbel.core.Recording
import nl.tapo.deurbel.core.RecordingDownloader
import nl.tapo.deurbel.core.TsDemuxer
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Recordings on the doorbell's SD card: list, fetch, save and remove. */
object Clips {
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun key(r: Recording) = "${r.startTime}"

    suspend fun days(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        val c = Camera.client(ctx)
        val end = Calendar.getInstance()
        val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -90) }
        c.recordingDays(day.format(start.time), day.format(end.time)).distinct().sortedDescending()
    }

    suspend fun recordings(ctx: Context, date: String): List<Recording> = withContext(Dispatchers.IO) {
        val hidden = Prefs(ctx).hiddenClips
        Camera.client(ctx).recordingsOfDay(date).filter { key(it) !in hidden }.sortedByDescending { it.startTime }
    }

    suspend fun timeCorrection(ctx: Context): Long = withContext(Dispatchers.IO) { Camera.client(ctx).timeCorrection() }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "clips").apply { mkdirs() }

    fun cached(ctx: Context, r: Recording): File? =
        listOf("mp4", "ts").map { File(dir(ctx), "${key(r)}.$it") }.firstOrNull { it.length() > 0 }

    /** Downloads the clip from the doorbell (once) and converts it to MP4 with sound. */
    suspend fun prepare(ctx: Context, r: Recording, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        cached(ctx, r)?.let { return@withContext it }
        val ts = File(dir(ctx), "${key(r)}.part")
        val c = Camera.client(ctx)
        Camera.loadAudioFormat(c)
        RecordingDownloader.download(c, r, ts, { progress(it * 0.9f) })
        val mp4 = File(dir(ctx), "${key(r)}.mp4")
        try {
            Mp4Converter.convert(ts, mp4, Camera.audioUlaw)
            ts.delete()
            mp4
        } catch (e: Exception) {
            mp4.delete()
            File(dir(ctx), "${key(r)}.ts").also { ts.renameTo(it) }
        }.also { progress(1f) }
    }

    /** Saves the clip to the phone's Movies/Deurbel folder. */
    suspend fun export(ctx: Context, r: Recording, file: File, corr: Long): Uri = withContext(Dispatchers.IO) {
        val ext = file.extension
        val name = "Deurbel_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date((r.startTime + corr) * 1000)) + ".$ext"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, if (ext == "mp4") "video/mp4" else "video/mp2t")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Deurbel")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("Opslaan mislukt")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }

    /** Removes the clip from the app (local copy and list). */
    fun delete(ctx: Context, r: Recording) {
        listOf("mp4", "ts", "part").forEach { File(dir(ctx), "${key(r)}.$it").delete() }
        val p = Prefs(ctx)
        p.hiddenClips = p.hiddenClips + key(r)
    }

    fun clearCache(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }
}

/**
 * Converts a Tapo MPEG-TS recording to MP4: the video is copied as-is, the
 * G.711 audio (which normal players do not understand in TS) is re-encoded to AAC.
 */
object Mp4Converter {
    private const val AAC_RATE = 16000

    fun convert(ts: File, mp4: File, ulaw: Boolean) {
        // 1. audio + first video PTS via our own demuxer
        val pcmChunks = ArrayList<Pair<Long, ShortArray>>()
        var firstVideoPts: Long? = null
        val demux = TsDemuxer { st, _, pts, payload ->
            when (st) {
                TsDemuxer.STREAM_PCMA, TsDemuxer.STREAM_PCMU ->
                    if (pts != null) pcmChunks.add(pts to G711.decode(payload, ulaw || st == TsDemuxer.STREAM_PCMU))
                0x1b, 0x24 -> if (firstVideoPts == null && pts != null) firstVideoPts = pts
            }
        }
        ts.inputStream().buffered().use { inp ->
            val buf = ByteArray(188 * 512)
            while (true) { val n = inp.read(buf); if (n <= 0) break; demux.feed(buf.copyOf(n)) }
        }
        demux.flush()

        // 2. video track from Android's TS extractor
        val ex = MediaExtractor()
        ex.setDataSource(ts.absolutePath)
        val vIndex = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: throw IllegalStateException("Geen video in opname")
        ex.selectTrack(vIndex)
        val vFormat = ex.getTrackFormat(vIndex)

        val muxer = MediaMuxer(mp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vTrack = muxer.addTrack(vFormat)
            val firstExtractorUs = ex.sampleTime.coerceAtLeast(0)
            val audio = if (pcmChunks.isNotEmpty()) encodeAac(pcmChunks, firstVideoPts ?: pcmChunks.first().first, firstExtractorUs) else null
            val aTrack = audio?.let { muxer.addTrack(it.first) }
            muxer.start()
            val cap = if (vFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) vFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 4 * 1024 * 1024
            val buf = ByteBuffer.allocate(maxOf(cap, 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                info.set(0, size, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(vTrack, buf, info)
                ex.advance()
            }
            if (audio != null && aTrack != null) {
                for ((data, i) in audio.second) muxer.writeSampleData(aTrack, ByteBuffer.wrap(data), i)
            }
            muxer.stop()
        } finally {
            muxer.release()
            ex.release()
        }
    }

    private fun encodeAac(chunks: List<Pair<Long, ShortArray>>, basePts: Long, baseUs: Long): Pair<MediaFormat, List<Pair<ByteArray, MediaCodec.BufferInfo>>> {
        val rate = Camera.audioRate
        val startUs = ((chunks.first().first - basePts) * 100 / 9 + baseUs).coerceAtLeast(0)
        val all = chunks.flatMap { it.second.asIterable() }.toShortArray()
        val pcm = Pcm.resample(all, rate, AAC_RATE)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AAC_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 48000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val out = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        var pos = 0
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inputDone) {
                    val ib = codec.dequeueInputBuffer(10_000)
                    if (ib >= 0) {
                        val b = codec.getInputBuffer(ib)!!
                        b.clear()
                        val n = minOf((b.capacity() / 2), 1024, pcm.size - pos)
                        val pts = startUs + pos * 1_000_000L / AAC_RATE
                        if (n <= 0) {
                            codec.queueInputBuffer(ib, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val bb = ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            bb.asShortBuffer().put(pcm, pos, n)
                            b.put(bb)
                            codec.queueInputBuffer(ib, 0, n * 2, pts, 0)
                            pos += n
                        }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                    ob >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val b = codec.getOutputBuffer(ob)!!
                            val data = ByteArray(info.size)
                            b.position(info.offset); b.get(data)
                            out.add(data to MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, 0) })
                        }
                        codec.releaseOutputBuffer(ob, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            codec.stop(); codec.release()
        }
        return (outFormat ?: throw IllegalStateException("AAC-encoder gaf geen formaat")) to out
    }
}
