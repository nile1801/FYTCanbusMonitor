package com.aoe.canbusmonitor

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper

class AudioEngine(private val context: Context) {
    private val soundPool: SoundPool
    private val handler = Handler(Looper.getMainLooper())
    private var soundId: Int = 0
    @Volatile private var loaded = false
    @Volatile private var desiredRuleActive = false
    @Volatile private var testActive = false
    private var ruleStreamId = 0
    private var testStreamId = 0
    private var volume = SettingsStore.volume(context)
    private var loopGapMs = SettingsStore.loopGapMs(context).toLong()

    private val ruleRepeat = object : Runnable {
        override fun run() {
            synchronized(this@AudioEngine) {
                if (!loaded || !desiredRuleActive) return
                playRuleOneShot()
            }
        }
    }

    private val testRepeat = object : Runnable {
        override fun run() {
            synchronized(this@AudioEngine) {
                if (!loaded || !testActive) return
                playTestOneShot()
            }
        }
    }

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
                    if (testActive) startTestIfReady()
                }
            }
        }
        soundId = soundPool.load(context, R.raw.turn_signal, 1)
    }

    @Synchronized
    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        if (ruleStreamId != 0) soundPool.setVolume(ruleStreamId, volume, volume)
        if (testStreamId != 0) soundPool.setVolume(testStreamId, volume, volume)
    }

    @Synchronized
    fun setLoopGapMs(value: Int) {
        val newGap = value.coerceIn(0, SettingsStore.MAX_LOOP_GAP_MS).toLong()
        if (newGap == loopGapMs) return
        loopGapMs = newGap

        // Apply immediately. Restart the current sample once so the next repeat uses
        // the new interval instead of waiting for the old scheduled delay.
        if (desiredRuleActive) {
            handler.removeCallbacks(ruleRepeat)
            if (ruleStreamId != 0) soundPool.stop(ruleStreamId)
            ruleStreamId = 0
            startRuleIfReady()
        }
        if (testActive) {
            handler.removeCallbacks(testRepeat)
            if (testStreamId != 0) soundPool.stop(testStreamId)
            testStreamId = 0
            startTestIfReady()
        }
    }

    @Synchronized
    fun setRuleActive(active: Boolean) {
        desiredRuleActive = active
        if (active) startRuleIfReady() else stopRule()
    }

    @Synchronized
    private fun startRuleIfReady() {
        if (!loaded || !desiredRuleActive || ruleStreamId != 0 || handler.hasCallbacks(ruleRepeat)) return
        playRuleOneShot()
    }

    @Synchronized
    private fun playRuleOneShot() {
        if (!loaded || !desiredRuleActive) return
        ruleStreamId = soundPool.play(soundId, volume, volume, 1, 0, 1.0f)
        handler.removeCallbacks(ruleRepeat)
        handler.postDelayed(ruleRepeat, SAMPLE_DURATION_MS + loopGapMs)
    }

    @Synchronized
    fun stopRule() {
        desiredRuleActive = false
        handler.removeCallbacks(ruleRepeat)
        if (ruleStreamId != 0) {
            soundPool.stop(ruleStreamId)
            ruleStreamId = 0
        }
    }

    @Synchronized
    fun startTest() {
        testActive = true
        startTestIfReady()
    }

    @Synchronized
    private fun startTestIfReady() {
        if (!loaded || !testActive || testStreamId != 0 || handler.hasCallbacks(testRepeat)) return
        playTestOneShot()
    }

    @Synchronized
    private fun playTestOneShot() {
        if (!loaded || !testActive) return
        testStreamId = soundPool.play(soundId, volume, volume, 0, 0, 1.0f)
        handler.removeCallbacks(testRepeat)
        handler.postDelayed(testRepeat, SAMPLE_DURATION_MS + loopGapMs)
    }

    @Synchronized
    fun stopTest() {
        testActive = false
        handler.removeCallbacks(testRepeat)
        if (testStreamId != 0) {
            soundPool.stop(testStreamId)
            testStreamId = 0
        }
    }

    @Synchronized
    fun release() {
        stopTest()
        stopRule()
        handler.removeCallbacksAndMessages(null)
        soundPool.release()
        loaded = false
        RuntimeState.audioReady = false
    }

    companion object {
        // ffprobe duration of res/raw/turn_signal.mp3 is 1.126125 s.
        // Scheduling from the decoded sample duration lets us insert an explicit silent gap
        // while preserving SoundPool's fast start/stop response.
        private const val SAMPLE_DURATION_MS = 1126L
    }
}
