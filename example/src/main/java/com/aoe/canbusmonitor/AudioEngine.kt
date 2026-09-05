package com.aoe.canbusmonitor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Audio engine cho tiếng xi nhan.
 *
 * Luồng xử lý:
 * 1) Decode only_tik_tok.mp3 đúng một lần khi service khởi động.
 * 2) Trim encoder delay/padding nếu MP3 có metadata gapless.
 * 3) Giữ PCM 16-bit trong RAM.
 * 4) Nạp PCM vào AudioTrack MODE_STATIC và đặt loop point vô hạn.
 * 5) Khi CAN match chỉ gọi play(), không decode MP3 và không tạo track ở đường nóng.
 */
class AudioEngine(private val context: Context) {
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    @Volatile private var loaded = false
    @Volatile private var desiredRuleActive = false
    @Volatile private var desiredTestActive = false

    private var decodedPcm: DecodedPcm? = null
    private var ruleTrack: AudioTrack? = null
    private var testTrack: AudioTrack? = null

    // Engine đã hỗ trợ gain thật tới 300%. UI hiện tại có thể truyền 0..1,
    // nhưng khi UI nâng lên 0..300% thì không cần đổi lại AudioEngine.
    private var gain = SettingsStore.volume(context).coerceIn(0f, MAX_GAIN)

    init {
        try {
            decodedPcm = decodeResourceToPcm16(R.raw.only_tik_tok)
            rebuildTracksLocked()
            loaded = true
            RuntimeState.audioReady = true
            RuntimeState.lastError = null
        } catch (t: Throwable) {
            loaded = false
            RuntimeState.audioReady = false
            RuntimeState.lastError = "Audio PCM: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    @Synchronized
    fun setVolume(value: Float) {
        val newGain = value.coerceIn(0f, MAX_GAIN)
        if (kotlin.math.abs(newGain - gain) < 0.001f) return
        gain = newGain

        // Gain >100% phải nhân trực tiếp PCM. Việc rebuild chỉ xảy ra khi người dùng
        // thay đổi volume, không xảy ra mỗi lần CANBUS bắn event.
        if (loaded) rebuildTracksLocked()
    }

    @Synchronized
    fun setRuleActive(active: Boolean) {
        desiredRuleActive = active
        if (active) startRuleIfReady() else stopRulePlaybackLocked()
    }

    @Synchronized
    private fun startRuleIfReady() {
        if (!loaded || !desiredRuleActive) return
        val track = ruleTrack ?: return
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            rewindStaticTrack(track)
            track.play()
        }
    }

    @Synchronized
    fun stopRule() {
        desiredRuleActive = false
        stopRulePlaybackLocked()
    }

    private fun stopRulePlaybackLocked() {
        stopAndRewind(ruleTrack)
    }

    @Synchronized
    fun startTest() {
        desiredTestActive = true
        if (!loaded) return
        val track = testTrack ?: return
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            rewindStaticTrack(track)
            track.play()
        }
    }

    @Synchronized
    fun stopTest() {
        desiredTestActive = false
        stopAndRewind(testTrack)
    }

    /**
     * Nạp lại hai AudioTrack khi gain thay đổi. PCM gốc đã decode vẫn giữ nguyên trong RAM.
     * Khi đang phát, track mới sẽ tiếp tục phát sau khi rebuild.
     */
    private fun rebuildTracksLocked() {
        val pcm = decodedPcm ?: return
        val keepRulePlaying = desiredRuleActive
        val keepTestPlaying = desiredTestActive

        releaseTrack(ruleTrack)
        releaseTrack(testTrack)
        ruleTrack = null
        testTrack = null

        val gainedSamples = applyDigitalGain(pcm.samples, gain)
        ruleTrack = createLoopingStaticTrack(pcm, gainedSamples)
        testTrack = createLoopingStaticTrack(pcm, gainedSamples)

        if (keepRulePlaying) ruleTrack?.play()
        if (keepTestPlaying) testTrack?.play()
    }

    private fun createLoopingStaticTrack(pcm: DecodedPcm, samples: ShortArray): AudioTrack {
        require(samples.isNotEmpty()) { "PCM rỗng" }
        val channelMask = when (pcm.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> throw IllegalArgumentException("Chỉ hỗ trợ PCM mono/stereo, nhận ${pcm.channelCount} kênh")
        }
        val frameCount = samples.size / pcm.channelCount
        require(frameCount > 0) { "PCM không có frame" }

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(pcm.sampleRate)
            .setChannelMask(channelMask)
            .build()

        val bytesNeeded = samples.size * 2
        val minBuffer = AudioTrack.getMinBufferSize(
            pcm.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(0)

        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(maxOf(bytesNeeded, minBuffer))
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack không khởi tạo được")
        }

        val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        if (written != samples.size) {
            track.release()
            throw IllegalStateException("AudioTrack chỉ ghi $written/${samples.size} mẫu PCM")
        }

        val loopResult = track.setLoopPoints(0, frameCount, -1)
        if (loopResult != AudioTrack.SUCCESS) {
            track.release()
            throw IllegalStateException("AudioTrack setLoopPoints lỗi $loopResult")
        }
        track.setPlaybackHeadPosition(0)
        return track
    }

    /** Nhân biên độ PCM thật. Nếu vượt 16-bit thì clamp để tránh integer overflow. */
    private fun applyDigitalGain(source: ShortArray, gainValue: Float): ShortArray {
        if (gainValue == 1f) return source.copyOf()
        if (gainValue == 0f) return ShortArray(source.size)

        return ShortArray(source.size) { i ->
            val scaled = (source[i].toFloat() * gainValue).roundToInt()
            scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Decode resource MP3 thành PCM 16-bit bằng MediaCodec một lần duy nhất.
     * MediaFormat encoder-delay / encoder-padding là số frame cần trim khỏi đầu/cuối.
     */
    private fun decodeResourceToPcm16(resId: Int): DecodedPcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.resources.openRawResourceFd(resId).use { afd ->
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }

            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = candidate
                    break
                }
            }
            require(trackIndex >= 0 && inputFormat != null) { "Không tìm thấy audio track trong only_tik_tok.mp3" }

            val format = inputFormat!!
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("MP3 không có MIME")
            val encoderDelayFrames = format.intOrZero(MediaFormat.KEY_ENCODER_DELAY)
            val encoderPaddingFrames = format.intOrZero(MediaFormat.KEY_ENCODER_PADDING)

            // Yêu cầu decoder trả PCM 16-bit để AudioTrack có đường dữ liệu đơn giản nhất.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            extractor.selectTrack(trackIndex)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var outputEncoding = AudioFormat.ENCODING_PCM_16BIT

            while (!outputEos) {
                if (!inputEos) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Decoder input buffer null")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                extractor.sampleTime.coerceAtLeast(0L),
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        outputSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        outputEncoding = outputFormat.intOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                    }
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                                ?: throw IllegalStateException("Decoder output buffer null")
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            outputBuffer.get(bytes)
                            out.write(bytes)
                        }
                        outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            require(outputEncoding == AudioFormat.ENCODING_PCM_16BIT) {
                "Decoder trả PCM encoding=$outputEncoding, cần PCM 16-bit"
            }
            require(outputChannels == 1 || outputChannels == 2) {
                "Số kênh PCM không hỗ trợ: $outputChannels"
            }

            val rawBytes = out.toByteArray()
            require(rawBytes.size >= 2) { "Decoder không trả dữ liệu PCM" }
            val shortBuffer = ByteBuffer.wrap(rawBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            val untrimmed = ShortArray(shortBuffer.remaining())
            shortBuffer.get(untrimmed)

            val frameCount = untrimmed.size / outputChannels
            val startFrame = encoderDelayFrames.coerceIn(0, frameCount)
            val endFrame = (frameCount - encoderPaddingFrames).coerceIn(startFrame, frameCount)
            val startSample = startFrame * outputChannels
            val endSample = endFrame * outputChannels
            val trimmed = if (endSample > startSample) {
                untrimmed.copyOfRange(startSample, endSample)
            } else {
                untrimmed
            }

            return DecodedPcm(
                samples = trimmed,
                sampleRate = outputSampleRate,
                channelCount = outputChannels,
                encoderDelayFrames = encoderDelayFrames,
                encoderPaddingFrames = encoderPaddingFrames
            )
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            extractor.release()
        }
    }

    private fun MediaFormat.intOrZero(key: String): Int =
        if (containsKey(key)) getInteger(key) else 0

    private fun MediaFormat.intOrDefault(key: String, defaultValue: Int): Int =
        if (containsKey(key)) getInteger(key) else defaultValue

    private fun rewindStaticTrack(track: AudioTrack) {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) return
        try {
            track.setPlaybackHeadPosition(0)
        } catch (_: Throwable) {
            try {
                track.reloadStaticData()
            } catch (_: Throwable) {
            }
        }
    }

    private fun stopAndRewind(track: AudioTrack?) {
        if (track == null) return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
        } catch (_: Throwable) {
        }
        rewindStaticTrack(track)
    }

    private fun releaseTrack(track: AudioTrack?) {
        if (track == null) return
        try {
            track.pause()
        } catch (_: Throwable) {
        }
        try {
            track.flush()
        } catch (_: Throwable) {
        }
        try {
            track.release()
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun release() {
        desiredTestActive = false
        desiredRuleActive = false
        releaseTrack(testTrack)
        releaseTrack(ruleTrack)
        testTrack = null
        ruleTrack = null
        decodedPcm = null
        loaded = false
        RuntimeState.audioReady = false
    }

    private data class DecodedPcm(
        val samples: ShortArray,
        val sampleRate: Int,
        val channelCount: Int,
        val encoderDelayFrames: Int,
        val encoderPaddingFrames: Int
    )

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_GAIN = 3.0f
    }
}
