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

/** Bản sao có cấu trúc chỉ dùng cho monitor/debug. */
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

object MonitorStore {
    private const val MAX_LINES = 2500
    private val lines = ArrayDeque<String>()
    private val lastPayloads = ConcurrentHashMap<String, String>()

    @Volatile private var filterMode: MonitorFilterMode = MonitorFilterMode.ALL
    @Volatile private var filterModule: String = RuleModule.CANBUS.name
    @Volatile private var filterIndexes: Set<Int> = emptySet()

    @Volatile var version: Long = 0L
        private set
    @Volatile var latestCanEvent: FytEvent? = null
        private set
    @Volatile var latestRuleEvent: FytEvent? = null
        private set

    /** Fast-path: không lock, không tạo object mới. */
    fun shouldQueueFast(module: String, index: Int): Boolean {
        return when (filterMode) {
            MonitorFilterMode.ALL -> true
            MonitorFilterMode.ONLY_CAN_INDEX -> module == filterModule && index in filterIndexes
            MonitorFilterMode.EXCLUDE_CAN_INDEX -> !(module == filterModule && index in filterIndexes)
        }
    }

    @Synchronized
    fun configureFilter(mode: MonitorFilterMode, module: String, indexes: Set<Int>) {
        val safeModule = if (module == RuleModule.MAIN.name) RuleModule.MAIN.name else RuleModule.CANBUS.name
        val safeIndexes = indexes.asSequence().filter { it >= 0 }.toSortedSet()
        if (filterMode == mode && filterModule == safeModule && filterIndexes == safeIndexes) return
        filterMode = mode
        filterModule = safeModule
        filterIndexes = safeIndexes
        clearLocked()
    }

    /** Backward-compatible overload cho code cũ. */
    fun configureFilter(mode: MonitorFilterMode, module: String, index: Int) {
        configureFilter(mode, module, setOf(index.coerceAtLeast(0)))
    }

    fun currentFilterMode(): MonitorFilterMode = filterMode
    fun currentFilterModule(): String = filterModule
    fun currentFilterIndexes(): Set<Int> = filterIndexes
    fun currentFilterIndex(): Int = filterIndexes.firstOrNull() ?: 0

    fun filterSummary(): String {
        val indexes = filterIndexes.sorted().joinToString(", ")
        return when (filterMode) {
            MonitorFilterMode.ALL -> "Tất cả log"
            MonitorFilterMode.ONLY_CAN_INDEX -> "Chỉ $filterModule:[$indexes]"
            MonitorFilterMode.EXCLUDE_CAN_INDEX -> "Loại trừ $filterModule:[$indexes]"
        }
    }

    @Synchronized
    fun accept(event: FytEvent): Boolean {
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
    private const val BACKUP_SCHEMA_VERSION = 3

    private data class ParseResult(
        val rules: MutableList<CanRule>,
        val skipped: Int
    )

    @Volatile private var pendingStatusMessage: String? = null

    fun consumeStatusMessage(): String? {
        val message = pendingStatusMessage
        pendingStatusMessage = null
        return message
    }

    fun load(context: Context): MutableList<CanRule> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (prefs.contains(KEY)) {
            val localText = prefs.getString(KEY, "[]") ?: "[]"
            val local = parseRules(localText)
            if (local != null) {
                if (local.skipped > 0) {
                    pendingStatusMessage = "Đã đọc ${local.rules.size} rule, bỏ qua ${local.skipped} rule lỗi trong config nội bộ."
                }
                return local.rules
            }
        }

        val backupText = RuleBackupStore.read(context)
        if (!backupText.isNullOrBlank()) {
            val restored = parseRules(backupText)
            if (restored != null) {
                restoreSettingsFromConfig(context, backupText)
                prefs.edit().putString(KEY, rulesToArray(restored.rules).toString()).apply()
                pendingStatusMessage = if (restored.skipped > 0) {
                    "Đã tự restore ${restored.rules.size} rule + cài đặt tắt từ ${RuleBackupStore.DISPLAY_PATH}; bỏ qua ${restored.skipped} rule lỗi."
                } else {
                    "Đã tự restore ${restored.rules.size} rule + cài đặt tắt từ ${RuleBackupStore.DISPLAY_PATH}."
                }
                return restored.rules
            }
            pendingStatusMessage = "Tìm thấy backup nhưng JSON không đọc được; cần cấu hình rule lại."
        }

        return mutableListOf()
    }

    fun save(context: Context, rules: List<CanRule>) {
        val array = rulesToArray(rules)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()

        if (!RuleBackupStore.write(context, configToJson(context, rules).toString(2))) {
            pendingStatusMessage = "Đã lưu rule trong app nhưng chưa ghi được backup ${RuleBackupStore.DISPLAY_PATH}."
        }
    }

    /** Ghi lại stopMode + timeout hiện tại vào rules.json mà không thay rule. */
    fun syncBackup(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val localText = prefs.getString(KEY, null)
        val rules = if (!localText.isNullOrBlank()) {
            parseRules(localText)?.rules
        } else {
            null
        } ?: load(context)

        if (!RuleBackupStore.write(context, configToJson(context, rules).toString(2))) {
            pendingStatusMessage = "Đã lưu cài đặt trong app nhưng chưa cập nhật được ${RuleBackupStore.DISPLAY_PATH}."
        }
    }

    /** Snapshot đầy đủ để dùng cho nút LƯU CONFIG MẶC ĐỊNH. */
    fun exportCurrentConfig(context: Context): String {
        return configToJson(context, load(context)).toString(2)
    }

    /**
     * Áp một snapshot ngoài vào config hiện tại. Hàm này cố ý gọi save() để rules.json
     * bị override đúng theo config vừa load.
     */
    fun applyExternalConfig(context: Context, text: String): MutableList<CanRule>? {
        val parsed = parseRules(text) ?: return null
        restoreSettingsFromConfig(context, text)
        save(context, parsed.rules)
        pendingStatusMessage = if (parsed.skipped > 0) {
            "Đã áp config ${parsed.rules.size} rule; bỏ qua ${parsed.skipped} rule lỗi."
        } else {
            "Đã áp config ${parsed.rules.size} rule."
        }
        return parsed.rules
    }

    /**
     * Bộ mặc định gốc chỉ dùng khi chưa từng có default_config.json do user lưu.
     * CANBUS:1049 dùng byte thứ 2 (position=1) để phân biệt 80/72/64.
     */
    fun builtInDefaultConfig(): String {
        val rules = listOf(
            CanRule(module = RuleModule.CANBUS, action = RuleAction.START, target = SignalTarget.LEFT, index = 1049, position = 1, expectedValue = 80),
            CanRule(module = RuleModule.CANBUS, action = RuleAction.START, target = SignalTarget.RIGHT, index = 1049, position = 1, expectedValue = 72),
            CanRule(module = RuleModule.CANBUS, action = RuleAction.STOP, target = SignalTarget.LEFT, index = 1049, position = 1, expectedValue = 64),
            CanRule(module = RuleModule.CANBUS, action = RuleAction.STOP, target = SignalTarget.RIGHT, index = 1049, position = 1, expectedValue = 64),
            CanRule(module = RuleModule.MAIN, action = RuleAction.START, target = SignalTarget.HAZARD, index = 139, position = 0, expectedValue = 1),
            CanRule(module = RuleModule.MAIN, action = RuleAction.STOP, target = SignalTarget.HAZARD, index = 139, position = 0, expectedValue = 0)
        )
        return configToJson(rules, StopMode.TRIGGER, SettingsStore.DEFAULT_TIMEOUT_MS).toString(2)
    }

    private fun configToJson(context: Context, rules: List<CanRule>): JSONObject {
        return configToJson(
            rules = rules,
            stopMode = SettingsStore.stopMode(context),
            timeoutMs = SettingsStore.timeoutMillis(context)
        )
    }

    private fun configToJson(rules: List<CanRule>, stopMode: StopMode, timeoutMs: Int): JSONObject {
        return JSONObject().apply {
            put("schemaVersion", BACKUP_SCHEMA_VERSION)
            put("savedAt", System.currentTimeMillis())
            put("settings", JSONObject().apply {
                put("stopMode", stopMode.name)
                put("timeoutMs", timeoutMs)
            })
            put("rules", rulesToArray(rules))
        }
    }

    private fun restoreSettingsFromConfig(context: Context, text: String) {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return
        val settings = try {
            JSONObject(trimmed).optJSONObject("settings")
        } catch (_: Throwable) {
            null
        } ?: return

        val stopMode = runCatching {
            StopMode.valueOf(settings.optString("stopMode", StopMode.TIMEOUT.name))
        }.getOrDefault(StopMode.TIMEOUT)
        val timeout = settings.optInt("timeoutMs", SettingsStore.DEFAULT_TIMEOUT_MS)
        SettingsStore.setStopMode(context, stopMode)
        SettingsStore.setTimeoutMillis(context, timeout)
    }

    private fun rulesToArray(rules: List<CanRule>): JSONArray {
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
        return array
    }

    /**
     * Backward compatible:
     * - format cũ: top-level JSONArray
     * - schema v2: { schemaVersion, rules: [...] }
     * - schema v3: thêm settings.stopMode + settings.timeoutMs
     * - field module/action/target thiếu => CANBUS/START/LEFT
     * - rule lỗi độc lập bị skip, các rule còn đọc được vẫn restore.
     */
    private fun parseRules(text: String): ParseResult? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val array = try {
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONObject(trimmed).optJSONArray("rules") ?: return null
            }
        } catch (_: Throwable) {
            return null
        }

        val out = mutableListOf<CanRule>()
        var skipped = 0
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i)
            if (o == null) {
                skipped++
                continue
            }

            try {
                if (!o.has("index") || !o.has("position") || (!o.has("expected") && !o.has("expectedValue"))) {
                    skipped++
                    continue
                }

                val index = o.optInt("index", -1)
                val position = o.optInt("position", -1)
                if (index < 0 || position < 0) {
                    skipped++
                    continue
                }

                val module = runCatching {
                    RuleModule.valueOf(o.optString("module", RuleModule.CANBUS.name))
                }.getOrDefault(RuleModule.CANBUS)
                val action = runCatching {
                    RuleAction.valueOf(o.optString("action", RuleAction.START.name))
                }.getOrDefault(RuleAction.START)
                val target = runCatching {
                    SignalTarget.valueOf(o.optString("target", SignalTarget.LEFT.name))
                }.getOrDefault(SignalTarget.LEFT)
                val rawId = o.optString("id", "").trim()
                val expected = if (o.has("expected")) {
                    o.optInt("expected", 0)
                } else {
                    o.optInt("expectedValue", 0)
                }

                out += CanRule(
                    id = rawId.ifEmpty { UUID.randomUUID().toString() },
                    enabled = o.optBoolean("enabled", true),
                    module = module,
                    action = action,
                    target = target,
                    index = index,
                    position = position,
                    expectedValue = expected,
                    unsignedByte = o.optBoolean("unsigned", o.optBoolean("unsignedByte", false))
                )
            } catch (_: Throwable) {
                skipped++
            }
        }
        return ParseResult(out, skipped)
    }
}

object SettingsStore {
    private const val PREFS = "turn_sound_settings"
    private const val ENABLED = "enabled"
    private const val VOLUME = "volume"
    private const val STOP_MODE = "stop_mode"
    private const val TIMEOUT_MS = "timeout_ms"
    private const val MONITOR_FILTER_MODE = "monitor_filter_mode"
    private const val MONITOR_FILTER_MODULE = "monitor_filter_module"
    private const val MONITOR_FILTER_INDEX = "monitor_filter_index"
    private const val MONITOR_FILTER_INDEXES = "monitor_filter_indexes"

    const val DEFAULT_TIMEOUT_MS = 1500
    const val MAX_TIMEOUT_MS = 2000
    const val TIMEOUT_STEP_MS = 100

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

    fun timeoutMillis(context: Context): Int {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
        return normalizeTimeout(raw)
    }

    fun setTimeoutMillis(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(TIMEOUT_MS, normalizeTimeout(value))
            .apply()
    }

    private fun normalizeTimeout(value: Int): Int {
        val safe = value.coerceIn(0, MAX_TIMEOUT_MS)
        return ((safe + TIMEOUT_STEP_MS / 2) / TIMEOUT_STEP_MS) * TIMEOUT_STEP_MS
    }

    fun monitorFilterMode(context: Context): MonitorFilterMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(MONITOR_FILTER_MODE, MonitorFilterMode.ALL.name)
            ?: MonitorFilterMode.ALL.name
        return runCatching { MonitorFilterMode.valueOf(raw) }.getOrDefault(MonitorFilterMode.ALL)
    }

    fun monitorFilterModule(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(MONITOR_FILTER_MODULE, RuleModule.CANBUS.name)
        return if (raw == RuleModule.MAIN.name) RuleModule.MAIN.name else RuleModule.CANBUS.name
    }

    fun monitorFilterIndexes(context: Context): Set<Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(MONITOR_FILTER_INDEXES, null)
        if (!raw.isNullOrBlank()) {
            val parsed = raw.split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it >= 0 }
                .toSortedSet()
            if (parsed.isNotEmpty()) return parsed
        }
        return setOf(prefs.getInt(MONITOR_FILTER_INDEX, 1019).coerceAtLeast(0))
    }

    /** Backward-compatible getter cho code cũ. */
    fun monitorFilterIndex(context: Context): Int = monitorFilterIndexes(context).firstOrNull() ?: 1019

    fun setMonitorFilter(context: Context, mode: MonitorFilterMode, module: String, indexes: Set<Int>) {
        val safeModule = if (module == RuleModule.MAIN.name) RuleModule.MAIN.name else RuleModule.CANBUS.name
        val safeIndexes = indexes.filter { it >= 0 }.toSortedSet()
        val first = safeIndexes.firstOrNull() ?: 0
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(MONITOR_FILTER_MODE, mode.name)
            .putString(MONITOR_FILTER_MODULE, safeModule)
            .putString(MONITOR_FILTER_INDEXES, safeIndexes.joinToString(","))
            .putInt(MONITOR_FILTER_INDEX, first)
            .apply()
    }

    /** Backward-compatible overload cho code cũ. */
    fun setMonitorFilter(context: Context, mode: MonitorFilterMode, module: String, index: Int) {
        setMonitorFilter(context, mode, module, setOf(index.coerceAtLeast(0)))
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
 * - Rule index sẵn theo module + index để lookup O(1).
 * - TIMEOUT: chỉ START rule vào fast-path; target false sau thời gian cấu hình không có START match.
 * - TRIGGER: target giữ true cho tới STOP rule đúng target.
 */
class RuleEngine(
    initialHoldMillis: Long = DEFAULT_HOLD_MS,
    private val onStateChanged: (RuleStateSnapshot) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var rulesSnapshot: List<CanRule> = emptyList()
    @Volatile private var canRulesByIndex: Map<Int, Array<CanRule>> = emptyMap()
    @Volatile private var mainRulesByIndex: Map<Int, Array<CanRule>> = emptyMap()
    @Volatile private var stopMode: StopMode = StopMode.TIMEOUT
    @Volatile private var holdMillis: Long = initialHoldMillis.coerceIn(0L, MAX_HOLD_MS)

    private val targetActive = BooleanArray(SignalTarget.values().size)
    private val lastMatchElapsed = LongArray(SignalTarget.values().size)
    private var activeCount = 0
    private var watchdogScheduled = false
    private val watchdogRunnable = Runnable { handleWatchdog() }

    @Volatile var active: Boolean = false
        private set

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
    fun setHoldMillis(value: Long) {
        val safe = value.coerceIn(0L, MAX_HOLD_MS)
        if (holdMillis == safe) return
        holdMillis = safe
        if (stopMode == StopMode.TIMEOUT) clearStateLocked()
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
    fun onRawEvent(module: String, index: Int, values: IntArray?) {
        val relevantRules = when (module) {
            RuleModule.CANBUS.name -> canRulesByIndex[index]
            RuleModule.MAIN.name -> mainRulesByIndex[index]
            else -> null
        } ?: return
        values ?: return

        var stoppedMask = 0
        var stateChanged = false
        val now = SystemClock.elapsedRealtime()

        if (stopMode == StopMode.TRIGGER) {
            for (rule in relevantRules) {
                if (rule.action != RuleAction.STOP || !matches(rule, values)) continue
                val ordinal = rule.target.ordinal
                stoppedMask = stoppedMask or (1 shl ordinal)
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
            if ((stoppedMask and (1 shl ordinal)) != 0) continue

            if (targetActive[ordinal]) {
                if (stopMode == StopMode.TIMEOUT) lastMatchElapsed[ordinal] = now
                continue
            }

            targetActive[ordinal] = true
            activeCount++
            if (stopMode == StopMode.TIMEOUT) lastMatchElapsed[ordinal] = now
            stateChanged = true
        }

        if (stateChanged) emitStateLocked()
        if (stopMode == StopMode.TIMEOUT && activeCount > 0) {
            scheduleWatchdogLocked(watchdogDelayLocked())
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
        val changed = activeCount > 0
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
        if (activeCount > 0) scheduleWatchdogLocked(watchdogDelayLocked())
    }

    private fun watchdogDelayLocked(): Long {
        return if (holdMillis <= 0L) 0L else minOf(WATCHDOG_INTERVAL_MS, holdMillis)
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
        private const val MAX_HOLD_MS = 2000L
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
