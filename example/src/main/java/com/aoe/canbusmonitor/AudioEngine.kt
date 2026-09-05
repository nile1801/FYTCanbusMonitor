package com.aoe.canbusmonitor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Audio engine cho tiếng xi nhan.
 *
 * Luồng xử lý:
 * 1) Decode only_tik_tok.mp3 đúng một lần khi service khởi động.
 * 2) Chuyển output decoder về PCM 16-bit trong RAM, kể cả codec trả PCM float/8/24/32-bit.
 * 3) AudioTrack MODE_STREAM được tạo sẵn một lần.
 * 4) Một audio worker ưu tiên cao đọc PCM trong RAM và ghi vòng lặp liên tục vào AudioTrack.
 * 5) Khi CAN match chỉ bật trạng thái + AudioTrack.play(); không decode MP3, không tạo track mới.
 *
 * MODE_STREAM được dùng thay MODE_STATIC/setLoopPoints để tương thích tốt hơn với head-unit/vendor
 * AudioTrack khác nhau. Loop vẫn chạy trên chính PCM trong RAM nên không có MP3 encoder boundary
 * ở mỗi vòng lặp.
 */
class AudioEngine(private val context: Context) {
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val wakeLock = Object()

    @Volatile private var loaded = false
    @Volatile private var released = false
    @Volatile private var desiredRuleActive = false
    @Volatile private var desiredTestActive = false
    @Volatile private var playbackRequested = false

    @Volatile private var sourcePcm: DecodedPcm? = null
    @Volatile private var gainedSamples = ShortArray(0)
    @Volatile private var audioTrack: AudioTrack? = null

    private var gain = SettingsStore.volume(context).coerceIn(0f, MAX_GAIN)
    private var worker: Thread? = null

    init {
        var stage = "EXTRACT"
        try {
            RuntimeState.audioReady = false
            RuntimeState.lastError = "Audio đang nạp: $stage"

            stage = "DECODE"
            val pcm = decodeResourceToPcm16(R.raw.only_tik_tok)
            require(pcm.samples.isNotEmpty()) { "PCM rỗng sau decode" }
            sourcePcm = pcm
            gainedSamples = applyDigitalGain(pcm.samples, gain)

            stage = "AUDIOTRACK"
            audioTrack = createStreamingTrack(pcm)

            stage = "WORKER"
            startWorker()

            loaded = true
            RuntimeState.audioReady = true
            RuntimeState.lastError = null
            Log.i(
                TAG,
                "Audio READY: ${pcm.sampleRate}Hz, ${pcm.channelCount}ch, ${pcm.samples.size} samples, " +
                    "sourceEncoding=${pcm.sourceEncoding}, delay=${pcm.encoderDelayFrames}, padding=${pcm.encoderPaddingFrames}"
            )
        } catch (t: Throwable) {
            loaded = false
            RuntimeState.audioReady = false
            RuntimeState.lastError = "Audio $stage: ${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "Khởi tạo AudioEngine thất bại tại $stage", t)
            safeReleaseTrack(audioTrack)
            audioTrack = null
        }
    }

    @Synchronized
    fun setVolume(value: Float) {
        val newGain = value.coerceIn(0f, MAX_GAIN)
        if (kotlin.math.abs(newGain - gain) < 0.001f) return
        gain = newGain

        val pcm = sourcePcm ?: return
        gainedSamples = applyDigitalGain(pcm.samples, gain)
        Log.i(TAG, "Đã áp dụng digital gain ${"%.2f".format(gain)}x vào PCM trong RAM")
    }

    @Synchronized
    fun setRuleActive(active: Boolean) {
        desiredRuleActive = active
        updatePlaybackStateLocked()
    }

    @Synchronized
    fun stopRule() {
        desiredRuleActive = false
        updatePlaybackStateLocked()
    }

    @Synchronized
    fun startTest() {
        desiredTestActive = true
        if (!loaded) {
            // Không ghi đè lỗi khởi tạo gốc; UI cần hiển thị đúng stage đã fail.
            if (RuntimeState.lastError.isNullOrBlank()) {
                RuntimeState.lastError = "Audio chưa sẵn sàng để thử"
            }
            Log.w(TAG, "startTest bỏ qua vì audio chưa READY: ${RuntimeState.lastError}")
            return
        }
        updatePlaybackStateLocked()
    }

    @Synchronized
    fun stopTest() {
        desiredTestActive = false
        updatePlaybackStateLocked()
    }

    private fun updatePlaybackStateLocked() {
        val shouldPlay = loaded && !released && (desiredRuleActive || desiredTestActive)
        if (shouldPlay == playbackRequested) return
        playbackRequested = shouldPlay

        if (shouldPlay) {
            val track = audioTrack
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                loaded = false
                RuntimeState.audioReady = false
                RuntimeState.lastError = "Audio PLAY: AudioTrack không initialized"
                playbackRequested = false
                return
            }

            try {
                // Sau mỗi lần tắt, buffer đã được flush. play() được gọi trước rồi worker ghi PCM;
                // AudioTrack sẽ bắt đầu ngay khi có đủ dữ liệu đầu tiên.
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                synchronized(wakeLock) { wakeLock.notifyAll() }
                RuntimeState.lastError = null
            } catch (t: Throwable) {
                playbackRequested = false
                RuntimeState.lastError = "Audio PLAY: ${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, "AudioTrack.play thất bại", t)
            }
        } else {
            pauseAndFlushTrack()
            synchronized(wakeLock) { wakeLock.notifyAll() }
        }
    }

    private fun createStreamingTrack(pcm: DecodedPcm): AudioTrack {
        val channelMask = when (pcm.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> throw IllegalArgumentException("Chỉ hỗ trợ mono/stereo, nhận ${pcm.channelCount} kênh")
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            pcm.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "getMinBufferSize lỗi $minBuffer" }

        // Dùng ít nhất khoảng 120 ms PCM để đủ chống jitter scheduler nhưng vẫn phản hồi nhanh.
        val frameBytes = pcm.channelCount * 2
        val target120ms = ((pcm.sampleRate * 120L / 1000L) * frameBytes).toInt()
        val bufferBytes = maxOf(minBuffer, target120ms)

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(pcm.sampleRate)
            .setChannelMask(channelMask)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack MODE_STREAM state=${track.state}")
        }

        track.setVolume(1.0f)
        Log.i(TAG, "AudioTrack MODE_STREAM initialized: buffer=$bufferBytes bytes")
        return track
    }

    private fun startWorker() {
        worker = Thread({ audioWorkerLoop() }, "FYT-PcmLoop").apply {
            isDaemon = true
            start()
        }
    }

    private fun audioWorkerLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (_: Throwable) {
        }

        var cursor = 0
        while (!released) {
            if (!playbackRequested) {
                cursor = 0
                synchronized(wakeLock) {
                    if (!released && !playbackRequested) {
                        try {
                            wakeLock.wait()
                        } catch (_: InterruptedException) {
                        }
                    }
                }
                continue
            }

            val track = audioTrack
            val samples = gainedSamples
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED || samples.isEmpty()) {
                RuntimeState.audioReady = false
                RuntimeState.lastError = "Audio WORKER: track/PCM không sẵn sàng"
                playbackRequested = false
                continue
            }

            if (cursor !in samples.indices) cursor = 0
            val count = minOf(WORKER_CHUNK_SAMPLES, samples.size - cursor)
            if (count <= 0) {
                cursor = 0
                continue
            }

            val written = try {
                track.write(samples, cursor, count, AudioTrack.WRITE_BLOCKING)
            } catch (t: Throwable) {
                RuntimeState.lastError = "Audio WRITE: ${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, "AudioTrack.write exception", t)
                playbackRequested = false
                continue
            }

            if (written > 0) {
                cursor += written
                if (cursor >= samples.size) cursor = 0
            } else {
                RuntimeState.lastError = "Audio WRITE: AudioTrack.write=$written"
                Log.e(TAG, "AudioTrack.write lỗi $written")
                playbackRequested = false
            }
        }
    }

    /**
     * Decode MP3 bằng MediaExtractor + MediaCodec đúng một lần, sau đó chuẩn hóa mọi output
     * phổ biến về ShortArray PCM 16-bit để phần phát không phụ thuộc codec vendor.
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
            require(trackIndex >= 0 && inputFormat != null) {
                "Không tìm thấy audio track trong only_tik_tok.mp3"
            }

            val format = inputFormat!!
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("MP3 không có MIME")
            val encoderDelayFrames = format.intOrZero(MediaFormat.KEY_ENCODER_DELAY)
            val encoderPaddingFrames = format.intOrZero(MediaFormat.KEY_ENCODER_PADDING)

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
            var idleLoops = 0

            while (!outputEos) {
                var madeProgress = false

                if (!inputEos) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        madeProgress = true
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
                        madeProgress = true
                        val outputFormat = codec.outputFormat
                        outputSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        outputEncoding = outputFormat.intOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        Log.i(TAG, "Decoder output format: $outputFormat")
                    }
                    else -> if (outputIndex >= 0) {
                        madeProgress = true
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                                ?: throw IllegalStateException("Decoder output buffer null")
                            val start = info.offset.coerceAtLeast(0)
                            val end = (info.offset + info.size).coerceAtMost(outputBuffer.capacity())
                            require(end > start) { "Decoder output range lỗi offset=${info.offset}, size=${info.size}" }
                            outputBuffer.position(start)
                            outputBuffer.limit(end)
                            val bytes = ByteArray(end - start)
                            outputBuffer.get(bytes)
                            out.write(bytes)
                        }
                        outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }

                if (madeProgress) {
                    idleLoops = 0
                } else {
                    idleLoops++
                    if (idleLoops > MAX_IDLE_LOOPS) {
                        throw IllegalStateException("MediaCodec decode timeout sau ${MAX_IDLE_LOOPS * CODEC_TIMEOUT_US / 1_000_000.0}s")
                    }
                }
            }

            require(outputChannels == 1 || outputChannels == 2) {
                "Số kênh PCM không hỗ trợ: $outputChannels"
            }

            val rawBytes = out.toByteArray()
            require(rawBytes.isNotEmpty()) { "Decoder không trả dữ liệu PCM" }

            val untrimmed = convertDecoderOutputToPcm16(rawBytes, outputEncoding)
            require(untrimmed.isNotEmpty()) { "PCM rỗng sau convert encoding=$outputEncoding" }

            val frameCount = untrimmed.size / outputChannels
            require(frameCount > 0) { "PCM không có frame" }

            // Chỉ dùng metadata gapless khi hợp lệ. Nếu codec/vendor trả giá trị bất thường,
            // giữ nguyên PCM để ưu tiên có âm thanh thay vì trim hết file.
            val saneDelay = encoderDelayFrames.coerceIn(0, frameCount)
            val sanePadding = encoderPaddingFrames.coerceIn(0, frameCount - saneDelay)
            val startSample = saneDelay * outputChannels
            val endSample = (frameCount - sanePadding) * outputChannels
            val trimmed = if (endSample > startSample) {
                untrimmed.copyOfRange(startSample, endSample)
            } else {
                untrimmed
            }

            require(trimmed.isNotEmpty()) { "PCM rỗng sau trim" }
            Log.i(
                TAG,
                "Decoded PCM: bytes=${rawBytes.size}, encoding=$outputEncoding, samples=${trimmed.size}, " +
                    "rate=$outputSampleRate, channels=$outputChannels"
            )

            return DecodedPcm(
                samples = trimmed,
                sampleRate = outputSampleRate,
                channelCount = outputChannels,
                encoderDelayFrames = saneDelay,
                encoderPaddingFrames = sanePadding,
                sourceEncoding = outputEncoding
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

    private fun convertDecoderOutputToPcm16(bytes: ByteArray, encoding: Int): ShortArray {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                require(bytes.size % 2 == 0) { "PCM16 byte count lẻ: ${bytes.size}" }
                val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                ShortArray(sb.remaining()).also { sb.get(it) }
            }

            AudioFormat.ENCODING_PCM_FLOAT -> {
                require(bytes.size % 4 == 0) { "PCM float byte count lỗi: ${bytes.size}" }
                val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ShortArray(fb.remaining()) { i ->
                    val value = fb.get(i).coerceIn(-1f, 1f)
                    (value * Short.MAX_VALUE).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
            }

            AudioFormat.ENCODING_PCM_8BIT -> {
                ShortArray(bytes.size) { i ->
                    (((bytes[i].toInt() and 0xFF) - 128) shl 8).toShort()
                }
            }

            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                require(bytes.size % 3 == 0) { "PCM24 byte count lỗi: ${bytes.size}" }
                ShortArray(bytes.size / 3) { i ->
                    val p = i * 3
                    var sample = (bytes[p].toInt() and 0xFF) or
                        ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[p + 2].toInt() and 0xFF) shl 16)
                    if (sample and 0x800000 != 0) sample = sample or -0x1000000
                    (sample shr 8).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }

            AudioFormat.ENCODING_PCM_32BIT -> {
                require(bytes.size % 4 == 0) { "PCM32 byte count lỗi: ${bytes.size}" }
                val ib = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                ShortArray(ib.remaining()) { i ->
                    (ib.get(i) shr 16).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }

            else -> throw IllegalArgumentException("PCM encoding decoder không hỗ trợ: $encoding")
        }
    }

    /** Nhân biên độ PCM thật. Nếu vượt 16-bit thì clamp để không integer overflow. */
    private fun applyDigitalGain(source: ShortArray, gainValue: Float): ShortArray {
        if (gainValue == 1f) return source
        if (gainValue == 0f) return ShortArray(source.size)
        return ShortArray(source.size) { i ->
            val scaled = (source[i].toFloat() * gainValue).roundToInt()
            scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun pauseAndFlushTrack() {
        val track = audioTrack ?: return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
        } catch (t: Throwable) {
            Log.w(TAG, "pause AudioTrack lỗi", t)
        }
        try {
            track.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "flush AudioTrack lỗi", t)
        }
    }

    private fun safeReleaseTrack(track: AudioTrack?) {
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

    private fun MediaFormat.intOrZero(key: String): Int =
        if (containsKey(key)) getInteger(key) else 0

    private fun MediaFormat.intOrDefault(key: String, defaultValue: Int): Int =
        if (containsKey(key)) getInteger(key) else defaultValue

    @Synchronized
    fun release() {
        released = true
        desiredTestActive = false
        desiredRuleActive = false
        playbackRequested = false
        synchronized(wakeLock) { wakeLock.notifyAll() }

        pauseAndFlushTrack()
        safeReleaseTrack(audioTrack)
        audioTrack = null
        sourcePcm = null
        gainedSamples = ShortArray(0)
        loaded = false
        RuntimeState.audioReady = false

        try {
            worker?.interrupt()
            worker?.join(250L)
        } catch (_: Throwable) {
        }
        worker = null
    }

    private data class DecodedPcm(
        val samples: ShortArray,
        val sampleRate: Int,
        val channelCount: Int,
        val encoderDelayFrames: Int,
        val encoderPaddingFrames: Int,
        val sourceEncoding: Int
    )

    companion object {
        private const val TAG = "FYT AudioEngine"
        private const val CODEC_TIMEOUT_US = 20_000L
        private const val MAX_IDLE_LOOPS = 1000
        private const val WORKER_CHUNK_SAMPLES = 4096
        private const val MAX_GAIN = 3.0f
    }
}
