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
 * Giữ cách hoạt động của monitor gốc: chỉ thêm dòng khi payload thay đổi,
 * đồng thời vẫn ghi Logcat với tag FYT MODULE như bản gốc.
 *
 * Bộ lọc chỉ áp dụng cho monitor/log, không áp dụng cho RuleEngine.
 */
object MonitorStore {
    private const val MAX_LINES = 2500
    private val lines = ArrayDeque<String>()
    private val lastPayloads = ConcurrentHashMap<String, String>()

    @Volatile private var filterMode: MonitorFilterMode = MonitorFilterMode.ALL
    @Volatile private var filterIndex: Int = 0

    @Volatile var version: Long = 0L
        private set
    @Volatile var latestCanEvent: FytEvent? = null
        private set

    /**
     * Fast-path dùng ngay trong Binder callback, trước khi copy array/tạo FytEvent/Runnable.
     * Các field filter đều volatile nên phép kiểm tra này không cần lock.
     */
    fun shouldQueueFast(module: String, index: Int): Boolean {
        return when (filterMode) {
            MonitorFilterMode.ALL -> true
            MonitorFilterMode.ONLY_CAN_INDEX -> module == "CANBUS" && index == filterIndex
            MonitorFilterMode.EXCLUDE_CAN_INDEX -> !(module == "CANBUS" && index == filterIndex)
        }
    }

    private fun shouldKeep(event: FytEvent): Boolean = shouldQueueFast(event.module, event.index)

    @Synchronized
    fun configureFilter(mode: MonitorFilterMode, index: Int) {
        val safeIndex = index.coerceAtLeast(0)
        if (filterMode == mode && filterIndex == safeIndex) return
        filterMode = mode
        filterIndex = safeIndex
        clearLocked()
    }

    fun currentFilterMode(): MonitorFilterMode = filterMode
    fun currentFilterIndex(): Int = filterIndex

    fun filterSummary(): String = when (filterMode) {
        MonitorFilterMode.ALL -> "Tất cả log"
        MonitorFilterMode.ONLY_CAN_INDEX -> "Chỉ CANBUS:$filterIndex"
        MonitorFilterMode.EXCLUDE_CAN_INDEX -> "Loại trừ CANBUS:$filterIndex"
    }

    @Synchronized
    fun accept(event: FytEvent): Boolean {
        // Re-check vì filter có thể đổi sau khi event đã vào queue.
        if (!shouldKeep(event)) return false

        val payload = event.formattedPayload()
        val key = "${event.module}:${event.index}"
        val previous = lastPayloads.put(key, payload)
        if (previous == payload) return false

        val line = event.originalStyleLine()
        Log.i("FYT MODULE", line)
        if (event.module == "CANBUS") latestCanEvent = event
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
        version++
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    @Synchronized
    fun snapshotLines(): List<String> = lines.toList()
}

data class CanRule(
    val id: String = UUID.randomUUID().toString(),
    var enabled: Boolean = true,
    var index: Int = 0,
    var position: Int = 0,
    var expectedValue: Int = 0,
    var unsignedByte: Boolean = false
) {
    fun summary(): String {
        val mode = if (unsignedByte) "byte không dấu" else "số nguyên gốc"
        return "CANBUS:$index  int[$position] = $expectedValue  ($mode)"
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
                out += CanRule(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    enabled = o.optBoolean("enabled", true),
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
    private const val MONITOR_FILTER_MODE = "monitor_filter_mode"
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

    fun monitorFilterIndex(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(MONITOR_FILTER_INDEX, 1019)
            .coerceAtLeast(0)

    fun setMonitorFilter(context: Context, mode: MonitorFilterMode, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(MONITOR_FILTER_MODE, mode.name)
            .putInt(MONITOR_FILTER_INDEX, index.coerceAtLeast(0))
            .apply()
    }
}

/**
 * RuleEngine tối ưu cho CANBUS có tần suất cao.
 *
 * - Rule được index sẵn theo CANBUS:index để lookup O(1), không scan toàn bộ danh sách mỗi frame.
 * - Index không có rule đang bật sẽ return ngay.
 * - Khi đã active=true, frame match tiếp theo chỉ cập nhật heartbeat lastMatchElapsed rồi return;
 *   không gọi lại AudioEngine và không remove/post timer theo từng frame.
 * - Một watchdog độc lập kiểm tra định kỳ. Chỉ khi không còn frame MATCH trong holdMillis thì
 *   active mới chuyển false. Frame OFF xen giữa các nhịp chớp không làm tiếng bị cắt.
 */
class RuleEngine(
    private val holdMillis: Long = DEFAULT_HOLD_MS,
    private val onActiveChanged: (Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var rulesSnapshot: List<CanRule> = emptyList()
    @Volatile private var rulesByIndex: Map<Int, Array<CanRule>> = emptyMap()

    @Volatile var active: Boolean = false
        private set

    private var lastMatchElapsed: Long = 0L
    private var watchdogScheduled = false
    private val watchdogRunnable = Runnable { handleWatchdog() }

    /** Fast-path cho Binder callback: O(1), không tạo object. */
    fun hasRuleForIndex(index: Int): Boolean = rulesByIndex[index] != null

    @Synchronized
    fun setRules(newRules: List<CanRule>) {
        val copied = newRules.map { it.copy() }
        if (copied == rulesSnapshot) return

        rulesSnapshot = copied
        rulesByIndex = copied
            .asSequence()
            .filter { it.enabled }
            .groupBy { it.index }
            .mapValues { (_, list) -> list.toTypedArray() }

        // Đổi cấu hình rule là thay đổi thật: bỏ trạng thái cũ để không giữ tiếng bởi rule đã sửa/xóa.
        handler.removeCallbacks(watchdogRunnable)
        watchdogScheduled = false
        lastMatchElapsed = 0L
        setActiveLocked(false)
    }

    @Synchronized
    fun onCanEvent(event: FytEvent) {
        if (event.module != "CANBUS") return

        // O(1): index không có rule bỏ ngay, không filter/list allocation.
        val relevantRules = rulesByIndex[event.index] ?: return
        val values = event.ints ?: return

        var matched = false
        for (rule in relevantRules) {
            val position = rule.position
            if (position < 0 || position >= values.size) continue

            val actualRaw = values[position]
            val actual = if (rule.unsignedByte) actualRaw and 0xFF else actualRaw
            if (actual == rule.expectedValue) {
                matched = true
                break
            }
        }

        if (!matched) {
            // Không false ngay ở frame OFF/non-match. CANBOX có thể phát ON/OFF xen kẽ theo nhịp đèn.
            return
        }

        // Heartbeat cực nhẹ. Đây là việc duy nhất frame match lặp lại cần làm khi active=true.
        lastMatchElapsed = SystemClock.elapsedRealtime()

        if (active) {
            // Audio đang chạy rồi: không gọi AudioEngine, không thao tác Handler/timer theo frame.
            return
        }

        setActiveLocked(true)
        scheduleWatchdogLocked(WATCHDOG_INTERVAL_MS)
    }

    @Synchronized
    fun clearState() {
        handler.removeCallbacks(watchdogRunnable)
        watchdogScheduled = false
        lastMatchElapsed = 0L
        setActiveLocked(false)
    }

    @Synchronized
    fun release() {
        clearState()
        rulesSnapshot = emptyList()
        rulesByIndex = emptyMap()
    }

    @Synchronized
    private fun handleWatchdog() {
        watchdogScheduled = false
        if (!active) return

        val elapsed = SystemClock.elapsedRealtime() - lastMatchElapsed
        val remaining = holdMillis - elapsed
        if (remaining <= 0L) {
            setActiveLocked(false)
            return
        }

        // Tần suất watchdog cố định, hoàn toàn độc lập với số lượng CAN frame nhận được.
        scheduleWatchdogLocked(minOf(WATCHDOG_INTERVAL_MS, remaining).coerceAtLeast(1L))
    }

    private fun scheduleWatchdogLocked(delayMillis: Long) {
        if (watchdogScheduled) return
        watchdogScheduled = true
        handler.postDelayed(watchdogRunnable, delayMillis)
    }

    private fun setActiveLocked(value: Boolean) {
        if (active == value) return
        active = value
        onActiveChanged(value)
    }

    companion object {
        // Nếu sau 1.3 giây không có frame nào MATCH nữa thì coi xi nhan đã tắt.
        private const val DEFAULT_HOLD_MS = 1300L

        // Chỉ 10 lần kiểm tra/giây dù CANBUS có spam hàng trăm hoặc hàng nghìn frame/giây.
        private const val WATCHDOG_INTERVAL_MS = 100L
    }
}

object RuntimeState {
    @Volatile var serviceRunning = false
    @Volatile var fytPackagePresent = true
    @Volatile var fytConnected = false
    @Volatile var audioReady = false
    @Volatile var ruleActive = false
    @Volatile var lastError: String? = null
}

fun timestampForFile(now: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
