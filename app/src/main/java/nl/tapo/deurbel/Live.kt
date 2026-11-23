@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package nl.tapo.deurbel

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import nl.tapo.deurbel.core.G711
import nl.tapo.deurbel.core.LiveStream
import nl.tapo.deurbel.core.TalkSession

/** Media3 DataSource that reads the doorbell's live MPEG-TS stream from port 8800. */
class LiveDataSource(
    private val context: Context,
    private val hd: Boolean,
    private val audio: (ShortArray) -> Unit,
) : BaseDataSource(true) {
    private var stream: LiveStream? = null
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val c = Camera.client(context)
        Camera.loadAudioFormat(c)
        stream = LiveStream(c, hd, Camera.audioUlaw, audio)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = stream?.read(buffer, offset, length) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        stream?.close()
        stream = null
        if (opened) { opened = false; transferEnded() }
    }
}

/** Plays the visitor's audio (from the doorbell microphone) on the phone. */
class VisitorAudio {
    private var track: AudioTrack? = null
    @Volatile var muted = true

    fun feed(pcm: ShortArray) {
        if (muted) return
        val t = track ?: create().also { track = it }
        t.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    private fun create(): AudioTrack {
        val rate = Camera.audioRate
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(rate) // 0.5 s
            .build().also { it.play() }
    }

    fun release() {
        track?.release()
        track = null
    }
}

object LivePlayer {
    fun create(context: Context, visitor: VisitorAudio): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(300, 2000, 200, 200)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val player = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
        player.setMediaSource(source(context, visitor))
        player.volume = 1f
        player.playWhenReady = true
        player.prepare()
        return player
    }

    private fun source(context: Context, visitor: VisitorAudio): MediaSource {
        val p = Prefs(context)
        if (p.rtspUser.isNotBlank()) {
            val u = Uri.encode(p.rtspUser); val pw = Uri.encode(p.rtspPassword)
            val stream = if (p.hdLive) "stream1" else "stream2"
            return RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri("rtsp://$u:$pw@${p.host}:554/$stream"))
        }
        val hd = p.hdLive
        val factory = DataSource.Factory { LiveDataSource(context.applicationContext, hd) { visitor.feed(it) } }
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        return ProgressiveMediaSource.Factory(factory, extractors)
            .setContinueLoadingCheckIntervalBytes(16 * 1024)
            .createMediaSource(MediaItem.fromUri("tapo://live"))
    }
}

/** Hold-to-talk: streams the phone microphone to the doorbell speaker. */
class PushToTalk(private val context: Context) {
    @Volatile private var active = false
    private var thread: Thread? = null
    @Volatile var error: String? = null

    @SuppressLint("MissingPermission")
    fun start(onError: (String) -> Unit) {
        if (active) return
        active = true
        error = null
        thread = Thread {
            var session: TalkSession? = null
            var rec: AudioRecord? = null
            try {
                session = TalkSession(Camera.client(context))
                val min = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 8000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200))
                rec.startRecording()
                val buf = ShortArray(160)
                while (active) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) session.writeAlaw(G711.encodeAlaw(buf, 0, n))
                }
            } catch (e: Exception) {
                error = Camera.friendly(e)
                onError(error!!)
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release()
                session?.close()
                active = false
            }
        }.apply { start() }
    }

    fun stop() { active = false }
}
