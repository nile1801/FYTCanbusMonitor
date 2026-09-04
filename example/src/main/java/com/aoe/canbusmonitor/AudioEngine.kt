package com.aoe.canbusmonitor

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

class AudioEngine(private val context: Context) {
    private val soundPool: SoundPool
    private var soundId: Int = 0
    @Volatile private var loaded = false
    @Volatile private var desiredRuleActive = false
    @Volatile private var desiredTestActive = false
    private var ruleStreamId = 0
    private var testStreamId = 0
    private var volume = SettingsStore.volume(context)

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(attrs)
            .build()

        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (sampleId == soundId && status == 0) {
                loaded = true
                RuntimeState.audioReady = true
                synchronized(this) {
                    if (desiredRuleActive) startRuleIfReady()
                    if (desiredTestActive) startTestIfReady()
                }
            }
        }

        // SoundPool giải mã mẫu âm thanh ngay khi AudioEngine được khởi tạo và giữ mẫu
        // đã giải mã trong RAM. Khi CAN kích hoạt, chỉ cần gọi play() nên độ trễ rất thấp.
        soundId = soundPool.load(context, R.raw.turn_signal, 1)
    }

    @Synchronized
    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        if (ruleStreamId != 0) soundPool.setVolume(ruleStreamId, volume, volume)
        if (testStreamId != 0) soundPool.setVolume(testStreamId, volume, volume)
    }

    @Synchronized
    fun setRuleActive(active: Boolean) {
        desiredRuleActive = active
        if (active) startRuleIfReady() else stopRule()
    }

    @Synchronized
    private fun startRuleIfReady() {
        if (!loaded || !desiredRuleActive || ruleStreamId != 0) return
        // loop = -1: vừa hết file sẽ quay lại đầu ngay, không chèn khoảng nghỉ phụ.
        ruleStreamId = soundPool.play(soundId, volume, volume, 1, -1, 1.0f)
    }

    @Synchronized
    fun stopRule() {
        desiredRuleActive = false
        if (ruleStreamId != 0) {
            soundPool.stop(ruleStreamId)
            ruleStreamId = 0
        }
    }

    @Synchronized
    fun startTest() {
        desiredTestActive = true
        startTestIfReady()
    }

    @Synchronized
    private fun startTestIfReady() {
        if (!loaded || !desiredTestActive || testStreamId != 0) return
        testStreamId = soundPool.play(soundId, volume, volume, 0, -1, 1.0f)
    }

    @Synchronized
    fun stopTest() {
        desiredTestActive = false
        if (testStreamId != 0) {
            soundPool.stop(testStreamId)
            testStreamId = 0
        }
    }

    @Synchronized
    fun release() {
        stopTest()
        stopRule()
        soundPool.release()
        loaded = false
        RuntimeState.audioReady = false
    }
}
