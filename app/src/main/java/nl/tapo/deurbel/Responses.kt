package nl.tapo.deurbel

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import nl.tapo.deurbel.core.TalkSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** A quick reply that can be spoken through the doorbell. */
data class QuickResponse(val id: String, val text: String, val useVoice: Boolean)

object Responses {
    private val defaults = listOf(
        "Ik kom eraan!",
        "Een ogenblikje alstublieft.",
        "Wilt u het pakketje voor de deur leggen? Dank u wel!",
        "We zijn nu niet thuis. Komt u later terug?",
        "Geen interesse, dank u wel.",
        "Wie is daar?",
    )

    fun list(ctx: Context): List<QuickResponse> {
        val json = Prefs(ctx).responsesJson ?: return defaults.mapIndexed { i, t -> QuickResponse("d$i", t, false) }
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            QuickResponse(it.getString("id"), it.getString("text"), it.optBoolean("voice"))
        }
    }

    fun save(ctx: Context, list: List<QuickResponse>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("voice", it.useVoice)) }
        Prefs(ctx).responsesJson = a.toString()
    }

    fun newId() = UUID.randomUUID().toString().take(8)

    fun voiceFile(ctx: Context, id: String) = File(ctx.filesDir, "voice/$id.pcm")
    private fun ttsFile(ctx: Context, r: QuickResponse) = File(ctx.cacheDir, "tts/${r.id}_${r.text.hashCode()}.pcm")

    fun hasVoice(ctx: Context, id: String) = voiceFile(ctx, id).length() > 1600

    fun delete(ctx: Context, r: QuickResponse) {
        voiceFile(ctx, r.id).delete()
        save(ctx, list(ctx).filter { it.id != r.id })
    }

    /** 8 kHz mono PCM for a response: own recording or Dutch text-to-speech. */
    suspend fun pcmFor(ctx: Context, r: QuickResponse): ShortArray = withContext(Dispatchers.IO) {
        if (r.useVoice && hasVoice(ctx, r.id)) return@withContext Pcm.read(voiceFile(ctx, r.id))
        val f = ttsFile(ctx, r)
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            val wav = File(ctx.cacheDir, "tts/tmp_${r.id}.wav")
            Tts.synthesize(ctx, r.text, wav)
            val pcm = Pcm.normalize(Pcm.wavTo8k(wav.readBytes()))
            wav.delete()
            Pcm.write(f, pcm)
        }
        Pcm.read(f)
    }

    /** Speaks the response through the doorbell speaker. */
    suspend fun sendToDoor(ctx: Context, r: QuickResponse, devId: String? = null) {
        val pcm = pcmFor(ctx, r)
        withContext(Dispatchers.IO) {
            TalkSession(Camera.client(ctx, devId)).use { it.playPcm(pcm) }
        }
    }

    /** Plays the response on the phone. */
    suspend fun preview(ctx: Context, r: QuickResponse) {
        val pcm = pcmFor(ctx, r)
        withContext(Dispatchers.IO) { Pcm.play(pcm) }
    }
}

/** Dutch text-to-speech into a WAV file. */
object Tts {
    suspend fun synthesize(ctx: Context, text: String, out: File) {
        var tts: TextToSpeech? = null
        val ready = suspendCancellableCoroutine<Int> { cont ->
            tts = TextToSpeech(ctx.applicationContext) { status -> if (cont.isActive) cont.resume(status) }
        }
        val engine = tts!!
        try {
            if (ready != TextToSpeech.SUCCESS) throw IllegalStateException("Spraakengine niet beschikbaar")
            val lang = engine.setLanguage(Locale("nl", "NL"))
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                throw IllegalStateException("Nederlandse stem ontbreekt. Installeer 'Nederlands' in de spraakinstellingen van Android, of neem je eigen stem op.")
            }
            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                val id = "u" + System.nanoTime()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                    override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                })
                if (engine.synthesizeToFile(text, Bundle(), out, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
            }
            if (!ok || !out.exists()) throw IllegalStateException("Tekst kon niet worden uitgesproken")
        } finally {
            engine.shutdown()
        }
    }
}

/** Records the user's own voice (8 kHz mono). */
class VoiceRecorder {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(out: File, maxSeconds: Int = 20) {
        out.parentFile?.mkdirs()
        running = true
        thread = Thread {
            val min = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, 8000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200))
            val all = ArrayList<Short>()
            val buf = ShortArray(800)
            try {
                rec.startRecording()
                while (running && all.size < maxSeconds * 8000) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) for (i in 0 until n) all.add(buf[i])
                }
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
            Pcm.write(out, Pcm.normalize(Pcm.trimSilence(all.toShortArray())))
        }.apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(3000)
        thread = null
    }
}

/** Helpers for 16-bit PCM audio. */
object Pcm {
    fun write(f: File, pcm: ShortArray) {
        f.parentFile?.mkdirs()
        val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(pcm)
        f.writeBytes(bb.array())
    }

    fun read(f: File): ShortArray {
        val b = f.readBytes()
        val s = ShortArray(b.size / 2)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
        return s
    }

    /** Parses a PCM WAV file and converts it to 8 kHz mono. */
    fun wavTo8k(wav: ByteArray): ShortArray {
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var rate = 22050; var channels = 1; var bits = 16
        var dataOff = 44; var dataLen = wav.size - 44
        var p = 12
        while (p + 8 <= wav.size) {
            val id = String(wav, p, 4)
            val len = bb.getInt(p + 4)
            if (id == "fmt ") {
                channels = bb.getShort(p + 10).toInt()
                rate = bb.getInt(p + 12)
                bits = bb.getShort(p + 22).toInt()
            } else if (id == "data") {
                dataOff = p + 8
                dataLen = if (len <= 0 || dataOff + len > wav.size) wav.size - dataOff else len
                break
            }
            p += 8 + len + (len and 1)
        }
        require(bits == 16) { "Onverwacht audioformaat ($bits bit)" }
        val frames = dataLen / (2 * channels)
        val mono = ShortArray(frames) { i ->
            var sum = 0
            for (c in 0 until channels) sum += bb.getShort(dataOff + (i * channels + c) * 2)
            (sum / channels).toShort()
        }
        return resample(mono, rate, 8000)
    }

    fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to) return input
        // simple low-pass before decimating
        val src = if (from > to) {
            val w = maxOf(1, from / to)
            ShortArray(input.size) { i ->
                var s = 0; var n = 0
                for (k in -w / 2..w / 2) { val j = i + k; if (j in input.indices) { s += input[j]; n++ } }
                (s / n).toShort()
            }
        } else input
        val outLen = (src.size.toLong() * to / from).toInt()
        return ShortArray(outLen) { i ->
            val pos = i.toDouble() * from / to
            val i0 = pos.toInt().coerceAtMost(src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val f = pos - i0
            (src[i0] * (1 - f) + src[i1] * f).toInt().toShort()
        }
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

    /** Plays 8 kHz mono PCM on the phone (blocking). */
    fun play(pcm: ShortArray, rate: Int = 8000) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(maxOf(pcm.size * 2, 3200))
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size * 1000L / rate + 200)
        } finally {
            track.release()
        }
    }
}
