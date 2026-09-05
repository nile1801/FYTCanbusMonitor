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

    private fun shouldKeep(event: FytEvent): Boolean {
        return when (filterMode) {
            MonitorFilterMode.ALL -> true
            MonitorFilterMode.ONLY_CAN_INDEX -> event.module == "CANBUS" && event.index == filterIndex
            MonitorFilterMode.EXCLUDE_CAN_INDEX -> !(event.module == "CANBUS" && event.index == filterIndex)
        }
    }

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
        // Kiểm tra filter trước khi format payload để event bị loại gần như không tốn CPU.
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
 * RuleEngine ưu tiên phản hồi tức thì khi thấy frame match.
 *
 * Nhiều CANBOX phát frame trạng thái theo đúng nhịp chớp của bóng xi nhan: frame match rồi
 * frame không match xen kẽ. Nếu stop ngay ở frame không match thì audio bị cắt thành từng đoạn.
 * Vì vậy sau mỗi frame match, trạng thái active được giữ thêm một khoảng ngắn. Frame match kế tiếp
 * chỉ gia hạn thời hạn này; AudioEngine sẽ giữ nguyên một SoundPool stream, không play lại.
 */
class RuleEngine(
    private val holdMillis: Long = DEFAULT_HOLD_MS,
    private val onActiveChanged: (Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var rules: List<CanRule> = emptyList()
    @Volatile var active: Boolean = false
        private set

    private var lastMatchElapsed: Long = 0L
    private val stopRunnable = Runnable { handleHoldTimeout() }

    @Synchronized
    fun setRules(newRules: List<CanRule>) {
        val copied = newRules.map { it.copy() }
        if (copied == rules) return
        rules = copied

        // Thay luật là một thay đổi cấu hình thực sự: bỏ latch cũ để không giữ âm thanh
        // bởi một rule đã bị sửa/xóa. Các lần refresh cùng bộ rule không gây gián đoạn.
        handler.removeCallbacks(stopRunnable)
        lastMatchElapsed = 0L
        setActiveLocked(false)
    }

    @Synchronized
    fun onCanEvent(event: FytEvent) {
        if (event.module != "CANBUS") return
        val values = event.ints ?: return

        // CAN như 1019 có thể bắn liên tục. Nếu index đó không nằm trong bất kỳ rule đang bật nào,
        // bỏ ngay mà không quét toàn bộ rule và không đụng AudioEngine.
        val relevantRules = rules.filter { it.enabled && it.index == event.index }
        if (relevantRules.isEmpty()) return

        val matched = relevantRules.any { rule ->
            if (rule.position !in values.indices) return@any false
            val actualRaw = values[rule.position]
            val actual = if (rule.unsignedByte) actualRaw and 0xFF else actualRaw
            actual == rule.expectedValue
        }
        if (!matched) {
            // Không stop ngay: frame OFF của nhịp chớp có thể xen giữa hai frame ON.
            // stopRunnable sẽ kết thúc âm thanh nếu không còn frame match mới trong holdMillis.
            return
        }

        lastMatchElapsed = SystemClock.elapsedRealtime()
        setActiveLocked(true)
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, holdMillis)
    }

    @Synchronized
    fun clearState() {
        handler.removeCallbacks(stopRunnable)
        lastMatchElapsed = 0L
        setActiveLocked(false)
    }

    @Synchronized
    fun release() {
        clearState()
    }

    @Synchronized
    private fun handleHoldTimeout() {
        if (!active) return
        val elapsed = SystemClock.elapsedRealtime() - lastMatchElapsed
        val remaining = holdMillis - elapsed
        if (remaining > 0L) {
            handler.postDelayed(stopRunnable, remaining)
            return
        }
        setActiveLocked(false)
    }

    private fun setActiveLocked(value: Boolean) {
        if (active == value) return
        active = value
        onActiveChanged(value)
    }

    companion object {
        // Lớn hơn một chu kỳ chớp CAN thông thường để nối các frame ON thành một phiên liên tục,
        // nhưng vẫn đủ ngắn để tắt âm thanh sớm sau khi người lái tắt xi nhan.
        private const val DEFAULT_HOLD_MS = 1300L
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
