package com.aoe.canbusmonitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Bản sao có cấu trúc của từng payload callback FYT. */
data class FytEvent(
    val module: String,
    val index: Int,
    val ints: IntArray?,
    val floats: FloatArray?,
    val strings: Array<String?>?,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun formattedPayload(): String {
        val values = arrayListOf<String>()
        ints?.forEach { values.add(it.toString()) }
        if (ints != null) {
            val unsigned = ints.map { it and 0xFF }.toIntArray()
            if (!unsigned.contentEquals(ints)) {
                values.add("//b")
                unsigned.forEach { values.add(it.toString()) }
            }
        }
        floats?.forEach { values.add(it.toString()) }
        strings?.forEach { values.add(it ?: "null") }
        return values.joinToString(", ", "[", "]")
    }

    fun originalStyleLine(): String = "$module:$index: ${formattedPayload()}"
}

enum class MonitorFilterMode {
    ALL,
    ONLY_CAN_INDEX,
    EXCLUDE_CAN_INDEX
}

/**
 * Monitor/debug chạy tách khỏi đường rule/audio.
 * Filter được kiểm tra ngay tại Binder callback trước khi copy array/tạo FytEvent.
 */
object MonitorStore {
    private const val MAX_LINES = 2500
    private val lines = ArrayDeque<String>()
    private val lastPayloads = ConcurrentHashMap<String, String>()

    @Volatile private var filterMode: MonitorFilterMode = MonitorFilterMode.ALL
    @Volatile private var filterModule: String = RuleModule.CANBUS.name
    @Volatile private var filterIndex: Int = 0

    @Volatile var version: Long = 0L
        private set
    @Volatile var latestCanEvent: FytEvent? = null
        private set
    @Volatile var latestRuleEvent: FytEvent? = null
        private set

    /** Fast-path: không lock, không allocation. */
    fun shouldQueueFast(module: String, index: Int): Boolean {
        return when (filterMode) {
            MonitorFilterMode.ALL -> true
            MonitorFilterMode.ONLY_CAN_INDEX -> module == filterModule && index == filterIndex
            MonitorFilterMode.EXCLUDE_CAN_INDEX -> !(module == filterModule && index == filterIndex)
        }
    }

    @Synchronized
    fun configureFilter(mode: MonitorFilterMode, module: String, index: Int) {
        val safeModule = if (module == RuleModule.MAIN.name) RuleModule.MAIN.name else RuleModule.CANBUS.name
        val safeIndex = index.coerceAtLeast(0)
        if (filterMode == mode && filterModule == safeModule && filterIndex == safeIndex) return
        filterMode = mode
        filterModule = safeModule
        filterIndex = safeIndex
        clearLocked()
    }

    fun currentFilterMode(): MonitorFilterMode = filterMode
    fun currentFilterModule(): String = filterModule
    fun currentFilterIndex(): Int = filterIndex

    fun filterSummary(): String = when (filterMode) {
        MonitorFilterMode.ALL -> "Tất cả log"
        MonitorFilterMode.ONLY_CAN_INDEX -> "Chỉ $filterModule:$filterIndex"
        MonitorFilterMode.EXCLUDE_CAN_INDEX -> "Loại trừ $filterModule:$filterIndex"
    }

    @Synchronized
    fun accept(event: FytEvent): Boolean {
        // Re-check vì filter có thể đổi sau khi event đã vào queue.
        if (!shouldQueueFast(event.module, event.index)) return false

        val payload = event.formattedPayload()
        val key = "${event.module}:${event.index}"
        val previous = lastPayloads.put(key, payload)
        if (previous == payload) return false

        val line = event.originalStyleLine()
        Log.i("FYT MODULE", line)
        if (event.module == RuleModule.CANBUS.name) latestCanEvent = event
        if (event.module == RuleModule.CANBUS.name || event.module == RuleModule.MAIN.name) {
            latestRuleEvent = event
        }
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        version++
        return true
    }

    @Synchronized
    fun clear() {
        clearLocked()
    }

    private fun clearLocked() {
        lines.clear()
        lastPayloads.clear()
        latestCanEvent = null
        latestRuleEvent = null
        version++
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    @Synchronized
    fun snapshotLines(): List<String> = lines.toList()
}

enum class RuleModule {
    CANBUS,
    MAIN
}

enum class RuleAction {
    START,
    STOP;

    fun label(): String = if (this == START) "BẬT" else "TẮT"
}

enum class SignalTarget {
    LEFT,
    RIGHT,
    HAZARD;

    fun label(): String = when (this) {
        LEFT -> "TRÁI"
        RIGHT -> "PHẢI"
        HAZARD -> "HAZARD"
    }
}

enum class StopMode {
    TIMEOUT,
    TRIGGER
}

data class CanRule(
    val id: String = UUID.randomUUID().toString(),
    var enabled: Boolean = true,
    var module: RuleModule = RuleModule.CANBUS,
    var action: RuleAction = RuleAction.START,
    var target: SignalTarget = SignalTarget.LEFT,
    var index: Int = 0,
    var position: Int = 0,
    var expectedValue: Int = 0,
    var unsignedByte: Boolean = false
) {
    fun summary(): String {
        val mode = if (unsignedByte) "byte không dấu" else "số nguyên gốc"
        return "${action.label()} ${target.label()} • ${module.name}:$index  int[$position] = $expectedValue  ($mode)"
    }
}

object RuleStore {
    private const val PREFS = "turn_sound_rules"
    private const val KEY = "rules_json"

    fun load(context: Context): MutableList<CanRule> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val out = mutableListOf<CanRule>()
        try {
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val module = runCatching {
                    RuleModule.valueOf(o.optString("module", RuleModule.CANBUS.name))
                }.getOrDefault(RuleModule.CANBUS)
                val action = runCatching {
                    RuleAction.valueOf(o.optString("action", RuleAction.START.name))
                }.getOrDefault(RuleAction.START)
                val target = runCatching {
                    SignalTarget.valueOf(o.optString("target", SignalTarget.LEFT.name))
                }.getOrDefault(SignalTarget.LEFT)

                out += CanRule(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    enabled = o.optBoolean("enabled", true),
                    module = module,
                    action = action,
                    target = target,
                    index = o.optInt("index", 0),
                    position = o.optInt("position", 0),
                    expectedValue = o.optInt("expected", 0),
                    unsignedByte = o.optBoolean("unsigned", false)
                )
            }
        } catch (_: Throwable) {
        }
        return out
    }

    fun save(context: Context, rules: List<CanRule>) {
        val array = JSONArray()
        rules.forEach { r ->
            array.put(JSONObject().apply {
                put("id", r.id)
                put("enabled", r.enabled)
                put("module", r.module.name)
                put("action", r.action.name)
                put("target", r.target.name)
                put("index", r.index)
                put("position", r.position)
                put("expected", r.expectedValue)
                put("unsigned", r.unsignedByte)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}

object SettingsStore {
    private const val PREFS = "turn_sound_settings"
    private const val ENABLED = "enabled"
    private const val VOLUME = "volume"
    private const val STOP_MODE = "stop_mode"
    private const val MONITOR_FILTER_MODE = "monitor_filter_mode"
    private const val MONITOR_FILTER_MODULE = "monitor_filter_module"
    private const val MONITOR_FILTER_INDEX = "monitor_filter_index"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
    }

    fun volume(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(VOLUME, 1.0f).coerceIn(0f, 1f)

    fun setVolume(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(VOLUME, value.coerceIn(0f, 1f)).apply()
    }

    fun stopMode(context: Context): StopMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(STOP_MODE, StopMode.TIMEOUT.name) ?: StopMode.TIMEOUT.name
        return runCatching { StopMode.valueOf(raw) }.getOrDefault(StopMode.TIMEOUT)
    }

    fun setStopMode(context: Context, mode: StopMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(STOP_MODE, mode.name)
            .apply()
    }

    fun monitorFilterMode(context: Context): MonitorFilterMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(MONITOR_FILTER_MODE, MonitorFilterMode.ALL.name)
            ?: MonitorFilterMode.ALL.name
        return try {
            MonitorFilterMode.valueOf(raw)
        } catch (_: Throwable) {
            MonitorFilterMode.ALL
        }
    }

    fun monitorFilterModule(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(MONITOR_FILTER_MODULE, RuleModule.CANBUS.name)
        return if (raw == RuleModule.MAIN.name) RuleModule.MAIN.name else RuleModule.CANBUS.name
    }

    fun monitorFilterIndex(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(MONITOR_FILTER_INDEX, 1019)
            .coerceAtLeast(0)

    fun setMonitorFilter(context: Context, mode: MonitorFilterMode, module: String, index: Int) {
        val safeModule = if (module == RuleModule.MAIN.name) RuleModule.MAIN.name else RuleModule.CANBUS.name
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(MONITOR_FILTER_MODE, mode.name)
            .putString(MONITOR_FILTER_MODULE, safeModule)
            .putInt(MONITOR_FILTER_INDEX, index.coerceAtLeast(0))
            .apply()
    }
}

data class RuleStateSnapshot(
    val left: Boolean,
    val right: Boolean,
    val hazard: Boolean
) {
    val anyActive: Boolean get() = left || right || hazard

    fun summary(): String {
        val active = arrayListOf<String>()
        if (left) active += "TRÁI"
        if (right) active += "PHẢI"
        if (hazard) active += "HAZARD"
        return if (active.isEmpty()) "KHÔNG" else active.joinToString(" + ")
    }
}

/**
 * RuleEngine fast-path cho CANBUS/MAIN có tần suất cao.
 *
 * - Rule được index sẵn theo module + index để lookup O(1).
 * - TIMEOUT: chỉ START rule được đưa vào fast-path. Mỗi target tự timeout sau 1.5 giây kể từ
 *   START match cuối cùng; watchdog 100 ms độc lập với tần suất event.
 * - TRIGGER: START target giữ true cho tới khi STOP rule của đúng target match. Không có timeout.
 * - Khi target đã true, START match tiếp theo không gọi AudioEngine; TIMEOUT chỉ cập nhật heartbeat,
 *   TRIGGER bỏ qua hoàn toàn.
 */
class RuleEngine(
    private val holdMillis: Long = DEFAULT_HOLD_MS,
    private val onStateChanged: (RuleStateSnapshot) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var rulesSnapshot: List<CanRule> = emptyList()
    @Volatile private var canRulesByIndex: Map<Int, Array<CanRule>> = emptyMap()
    @Volatile private var mainRulesByIndex: Map<Int, Array<CanRule>> = emptyMap()
    @Volatile private var stopMode: StopMode = StopMode.TIMEOUT

    private val targetActive = BooleanArray(SignalTarget.values().size)
    private val lastMatchElapsed = LongArray(SignalTarget.values().size)
    private var activeCount = 0
    private var watchdogScheduled = false
    private val watchdogRunnable = Runnable { handleWatchdog() }

    @Volatile var active: Boolean = false
        private set

    /** Binder fast-path: O(1), không tạo object. */
    fun hasRuleFor(module: String, index: Int): Boolean {
        return when (module) {
            RuleModule.CANBUS.name -> canRulesByIndex[index] != null
            RuleModule.MAIN.name -> mainRulesByIndex[index] != null
            else -> false
        }
    }

    @Synchronized
    fun setStopMode(mode: StopMode) {
        if (stopMode == mode) return
        stopMode = mode
        clearStateLocked()
        rebuildRuleIndexesLocked()
    }

    @Synchronized
    fun setRules(newRules: List<CanRule>) {
        val copied = newRules.map { it.copy() }
        if (copied == rulesSnapshot) return
        rulesSnapshot = copied
        clearStateLocked()
        rebuildRuleIndexesLocked()
    }

    private fun rebuildRuleIndexesLocked() {
        val eligible = rulesSnapshot.asSequence().filter {
            it.enabled && (stopMode == StopMode.TRIGGER || it.action == RuleAction.START)
        }
        val can = mutableMapOf<Int, MutableList<CanRule>>()
        val main = mutableMapOf<Int, MutableList<CanRule>>()
        eligible.forEach { rule ->
            val map = if (rule.module == RuleModule.MAIN) main else can
            map.getOrPut(rule.index) { mutableListOf() }.add(rule)
        }
        canRulesByIndex = can.mapValues { (_, list) -> list.toTypedArray() }
        mainRulesByIndex = main.mapValues { (_, list) -> list.toTypedArray() }
    }

    @Synchronized
    fun onFytEvent(event: FytEvent) {
        val relevantRules = when (event.module) {
            RuleModule.CANBUS.name -> canRulesByIndex[event.index]
            RuleModule.MAIN.name -> mainRulesByIndex[event.index]
            else -> null
        } ?: return

        val values = event.ints ?: return
        val stoppedThisEvent = BooleanArray(SignalTarget.values().size)
        var stateChanged = false
        val now = SystemClock.elapsedRealtime()

        // Ở TRIGGER mode, STOP được xử lý trước và có ưu tiên hơn START nếu cùng event cùng target.
        if (stopMode == StopMode.TRIGGER) {
            for (rule in relevantRules) {
                if (rule.action != RuleAction.STOP || !matches(rule, values)) continue
                val ordinal = rule.target.ordinal
                stoppedThisEvent[ordinal] = true
                if (targetActive[ordinal]) {
                    targetActive[ordinal] = false
                    lastMatchElapsed[ordinal] = 0L
                    activeCount = (activeCount - 1).coerceAtLeast(0)
                    stateChanged = true
                }
            }
        }

        for (rule in relevantRules) {
            if (rule.action != RuleAction.START || !matches(rule, values)) continue
            val ordinal = rule.target.ordinal
            if (stoppedThisEvent[ordinal]) continue

            if (targetActive[ordinal]) {
                if (stopMode == StopMode.TIMEOUT) {
                    // Target đang true: chỉ refresh heartbeat, không gọi AudioEngine/Handler theo frame.
                    lastMatchElapsed[ordinal] = now
                }
                continue
            }

            targetActive[ordinal] = true
            activeCount++
            if (stopMode == StopMode.TIMEOUT) lastMatchElapsed[ordinal] = now
            stateChanged = true
        }

        if (stateChanged) emitStateLocked()

        if (stopMode == StopMode.TIMEOUT && activeCount > 0) {
            scheduleWatchdogLocked(WATCHDOG_INTERVAL_MS)
        }
    }

    private fun matches(rule: CanRule, values: IntArray): Boolean {
        val position = rule.position
        if (position < 0 || position >= values.size) return false
        val raw = values[position]
        val actual = if (rule.unsignedByte) raw and 0xFF else raw
        return actual == rule.expectedValue
    }

    @Synchronized
    fun clearState() {
        clearStateLocked()
    }

    private fun clearStateLocked() {
        handler.removeCallbacks(watchdogRunnable)
        watchdogScheduled = false
        var changed = activeCount > 0
        activeCount = 0
        for (i in targetActive.indices) {
            targetActive[i] = false
            lastMatchElapsed[i] = 0L
        }
        active = false
        if (changed) emitStateLocked()
    }

    @Synchronized
    fun release() {
        clearStateLocked()
        rulesSnapshot = emptyList()
        canRulesByIndex = emptyMap()
        mainRulesByIndex = emptyMap()
    }

    @Synchronized
    private fun handleWatchdog() {
        watchdogScheduled = false
        if (stopMode != StopMode.TIMEOUT || activeCount <= 0) return

        val now = SystemClock.elapsedRealtime()
        var changed = false
        for (target in SignalTarget.values()) {
            val i = target.ordinal
            if (!targetActive[i]) continue
            if (now - lastMatchElapsed[i] >= holdMillis) {
                targetActive[i] = false
                lastMatchElapsed[i] = 0L
                activeCount = (activeCount - 1).coerceAtLeast(0)
                changed = true
            }
        }

        if (changed) emitStateLocked()
        if (activeCount > 0) scheduleWatchdogLocked(WATCHDOG_INTERVAL_MS)
    }

    private fun scheduleWatchdogLocked(delayMillis: Long) {
        if (watchdogScheduled) return
        watchdogScheduled = true
        handler.postDelayed(watchdogRunnable, delayMillis)
    }

    private fun emitStateLocked() {
        active = activeCount > 0
        onStateChanged(
            RuleStateSnapshot(
                left = targetActive[SignalTarget.LEFT.ordinal],
                right = targetActive[SignalTarget.RIGHT.ordinal],
                hazard = targetActive[SignalTarget.HAZARD.ordinal]
            )
        )
    }

    companion object {
        private const val DEFAULT_HOLD_MS = 1500L
        private const val WATCHDOG_INTERVAL_MS = 100L
    }
}

object RuntimeState {
    @Volatile var serviceRunning = false
    @Volatile var fytPackagePresent = true
    @Volatile var fytConnected = false
    @Volatile var audioReady = false
    @Volatile var ruleActive = false
    @Volatile var leftActive = false
    @Volatile var rightActive = false
    @Volatile var hazardActive = false
    @Volatile var lastError: String? = null
}

fun timestampForFile(now: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
