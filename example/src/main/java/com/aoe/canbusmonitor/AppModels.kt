package com.aoe.canbusmonitor

import android.content.Context
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

/**
 * Giữ cách hoạt động của monitor gốc: chỉ thêm dòng khi payload thay đổi,
 * đồng thời vẫn ghi Logcat với tag FYT MODULE như bản gốc.
 */
object MonitorStore {
    private const val MAX_LINES = 2500
    private val lines = ArrayDeque<String>()
    private val lastPayloads = ConcurrentHashMap<String, String>()
    @Volatile var version: Long = 0L
        private set
    @Volatile var latestCanEvent: FytEvent? = null
        private set

    @Synchronized
    fun accept(event: FytEvent): Boolean {
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
}

class RuleEngine(private val onActiveChanged: (Boolean) -> Unit) {
    private val currentInts = ConcurrentHashMap<Int, IntArray>()
    @Volatile private var rules: List<CanRule> = emptyList()
    @Volatile var active: Boolean = false
        private set

    fun setRules(newRules: List<CanRule>) {
        rules = newRules.map { it.copy() }
        evaluate()
    }

    fun onCanEvent(event: FytEvent) {
        if (event.module != "CANBUS") return
        event.ints?.let { currentInts[event.index] = it.copyOf() }
        evaluate()
    }

    fun clearState() {
        currentInts.clear()
        if (active) {
            active = false
            onActiveChanged(false)
        }
    }

    private fun evaluate() {
        val newActive = rules.any { rule ->
            if (!rule.enabled) return@any false
            val values = currentInts[rule.index] ?: return@any false
            if (rule.position !in values.indices) return@any false
            val actualRaw = values[rule.position]
            val actual = if (rule.unsignedByte) actualRaw and 0xFF else actualRaw
            actual == rule.expectedValue
        }
        if (newActive != active) {
            active = newActive
            onActiveChanged(newActive)
        }
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
