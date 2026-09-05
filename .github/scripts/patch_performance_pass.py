from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"marker not found: {label}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, new_block: str, label: str) -> str:
    i = text.find(start)
    if i < 0:
        raise SystemExit(f"start marker not found: {label}")
    j = text.find(end, i)
    if j < 0:
        raise SystemExit(f"end marker not found: {label}")
    return text[:i] + new_block + text[j:]


# -----------------------------------------------------------------------------
# AppModels.kt: monitor allocations/UI batching + RuleEngine hot-path/timer.
# -----------------------------------------------------------------------------
path = Path("example/src/main/java/com/aoe/canbusmonitor/AppModels.kt")
text = path.read_text(encoding="utf-8")
monitor_block = r'''/** Bản sao có cấu trúc chỉ dùng cho monitor/debug. */
data class FytEvent(
    val module: String,
    val index: Int,
    val ints: IntArray?,
    val floats: FloatArray?,
    val strings: Array<String?>?,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** Format một lượt, không tạo list/IntArray trung gian. */
    fun formattedPayload(): String = buildString {
        append('[')
        var first = true
        fun addValue(value: Any?) {
            if (!first) append(", ")
            append(value)
            first = false
        }

        ints?.forEach { addValue(it) }
        if (ints != null) {
            var unsignedDiffers = false
            for (value in ints) {
                if ((value and 0xFF) != value) {
                    unsignedDiffers = true
                    break
                }
            }
            if (unsignedDiffers) {
                addValue("//b")
                ints.forEach { addValue(it and 0xFF) }
            }
        }
        floats?.forEach { addValue(it) }
        strings?.forEach { addValue(it ?: "null") }
        append(']')
    }

    fun originalStyleLine(): String = "$module:$index: ${formattedPayload()}"
}

enum class MonitorFilterMode {
    ALL,
    ONLY_CAN_INDEX,
    EXCLUDE_CAN_INDEX
}

data class MonitorUiBatch(
    val version: Long,
    val lines: List<String>,
    val requiresReset: Boolean
)

data class MonitorUiSnapshot(
    val version: Long,
    val text: String,
    val lineCount: Int
)

object MonitorStore {
    private const val MAX_LINES = 2500
    private const val MAX_PENDING_UI_LINES = 1024
    private val lines = ArrayDeque<String>()
    private val pendingUiLines = ArrayDeque<String>()
    private val lastPayloads = HashMap<String, String>()
    private var uiNeedsReset = true

    @Volatile private var filterMode: MonitorFilterMode = MonitorFilterMode.ALL
    @Volatile private var filterModule: String = RuleModule.CANBUS.name
    @Volatile private var filterIndexes: Set<Int> = emptySet()

    @Volatile var version: Long = 0L
        private set
    @Volatile var latestCanEvent: FytEvent? = null
        private set
    @Volatile var latestRuleEvent: FytEvent? = null
        private set
    @Volatile var latestRuleLine: String? = null
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

        // Payload chỉ format đúng một lần cho dedupe + line hiển thị.
        val payload = event.formattedPayload()
        val key = "${event.module}:${event.index}"
        val previous = lastPayloads.put(key, payload)
        if (previous == payload) return false

        val line = "${event.module}:${event.index}: $payload"
        if (event.module == RuleModule.CANBUS.name) latestCanEvent = event
        if (event.module == RuleModule.CANBUS.name || event.module == RuleModule.MAIN.name) {
            latestRuleEvent = event
            latestRuleLine = line
        }

        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()

        if (pendingUiLines.size >= MAX_PENDING_UI_LINES) {
            pendingUiLines.clear()
            uiNeedsReset = true
        }
        if (!uiNeedsReset) pendingUiLines.addLast(line)

        version++
        return true
    }

    @Synchronized
    fun clear() {
        clearLocked()
    }

    private fun clearLocked() {
        lines.clear()
        pendingUiLines.clear()
        lastPayloads.clear()
        latestCanEvent = null
        latestRuleEvent = null
        latestRuleLine = null
        uiNeedsReset = true
        version++
    }

    /** Snapshot đầy đủ chỉ dùng lúc mở/rebuild Monitor hoặc export. */
    @Synchronized
    fun takeUiSnapshot(): MonitorUiSnapshot {
        val result = MonitorUiSnapshot(
            version = version,
            text = lines.joinToString("\n"),
            lineCount = lines.size
        )
        pendingUiLines.clear()
        uiNeedsReset = false
        return result
    }

    /** Lấy riêng các dòng mới kể từ lần UI drain trước. */
    @Synchronized
    fun drainUiBatch(): MonitorUiBatch {
        if (uiNeedsReset) {
            pendingUiLines.clear()
            return MonitorUiBatch(version, emptyList(), true)
        }
        val out = if (pendingUiLines.isEmpty()) emptyList() else pendingUiLines.toList()
        pendingUiLines.clear()
        return MonitorUiBatch(version, out, false)
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    @Synchronized
    fun snapshotLines(): List<String> = lines.toList()
}

'''
text = replace_between(
    text,
    "/** Bản sao có cấu trúc chỉ dùng cho monitor/debug. */",
    "enum class RuleModule {",
    monitor_block,
    "monitor block"
)

rule_engine = r'''class RuleEngine(
    initialHoldMillis: Long = DEFAULT_HOLD_MS,
    private val onStateChanged: (RuleStateSnapshot) -> Unit
) {
    /**
     * State callback được xếp hàng trên worker riêng. Vì enqueue diễn ra khi đang giữ lock RuleEngine,
     * thứ tự BẬT/TẮT vẫn được bảo toàn nhưng AudioTrack/Notification không còn giữ lock CAN hot-path.
     */
    private val stateExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FYT-RuleState").apply {
            isDaemon = true
            priority = (Thread.NORM_PRIORITY + 1).coerceAtMost(Thread.MAX_PRIORITY)
        }
    }

    /** ScheduledThreadPoolExecutor chỉ tạo thread khi TIMEOUT thực sự cần dùng. TRIGGER không tốn timer thread. */
    private val timeoutScheduler = java.util.concurrent.ScheduledThreadPoolExecutor(
        1,
        java.util.concurrent.ThreadFactory { runnable ->
            Thread(runnable, "FYT-RuleTimeout").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }
    ).apply {
        setRemoveOnCancelPolicy(true)
    }

    @Volatile private var rulesSnapshot: List<CanRule> = emptyList()
    @Volatile private var canRulesByIndex: Map<Int, Array<CanRule>> = emptyMap()
    @Volatile private var mainRulesByIndex: Map<Int, Array<CanRule>> = emptyMap()
    @Volatile private var stopMode: StopMode = StopMode.TIMEOUT
    @Volatile private var holdMillis: Long = initialHoldMillis.coerceIn(0L, MAX_HOLD_MS)

    private val targetActive = BooleanArray(SignalTarget.values().size)
    private val lastMatchElapsed = LongArray(SignalTarget.values().size)
    private var activeCount = 0
    private var timeoutFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private var released = false

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
        clearStateLocked(true)
        rebuildRuleIndexesLocked()
    }

    @Synchronized
    fun setHoldMillis(value: Long) {
        val safe = value.coerceIn(0L, MAX_HOLD_MS)
        if (holdMillis == safe) return
        holdMillis = safe
        if (stopMode == StopMode.TIMEOUT) clearStateLocked(true)
    }

    @Synchronized
    fun setRules(newRules: List<CanRule>) {
        val copied = newRules.map { it.copy() }
        if (copied == rulesSnapshot) return
        rulesSnapshot = copied
        clearStateLocked(true)
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
        if (released) return
        val relevantRules = when (module) {
            RuleModule.CANBUS.name -> canRulesByIndex[index]
            RuleModule.MAIN.name -> mainRulesByIndex[index]
            else -> null
        } ?: return
        values ?: return

        var stoppedMask = 0
        var stateChanged = false
        val now = if (stopMode == StopMode.TIMEOUT) SystemClock.elapsedRealtime() else 0L

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

        if (stateChanged) queueStateLocked()
        if (stopMode == StopMode.TIMEOUT && activeCount > 0) {
            // Không polling 100 ms nữa; đặt đúng mốc timeout gần nhất.
            rescheduleTimeoutLocked()
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
        clearStateLocked(true)
    }

    private fun clearStateLocked(notify: Boolean) {
        cancelTimeoutLocked()
        val changed = activeCount > 0 || active
        activeCount = 0
        for (i in targetActive.indices) {
            targetActive[i] = false
            lastMatchElapsed[i] = 0L
        }
        active = false
        if (changed && notify) queueStateLocked()
    }

    @Synchronized
    private fun handleTimeout() {
        timeoutFuture = null
        if (released || stopMode != StopMode.TIMEOUT || activeCount <= 0) return

        val now = SystemClock.elapsedRealtime()
        var changed = false
        for (i in targetActive.indices) {
            if (!targetActive[i]) continue
            if (now - lastMatchElapsed[i] >= holdMillis) {
                targetActive[i] = false
                lastMatchElapsed[i] = 0L
                activeCount = (activeCount - 1).coerceAtLeast(0)
                changed = true
            }
        }

        if (changed) queueStateLocked()
        if (activeCount > 0) rescheduleTimeoutLocked()
    }

    private fun rescheduleTimeoutLocked() {
        cancelTimeoutLocked()
        if (released || stopMode != StopMode.TIMEOUT || activeCount <= 0) return

        val now = SystemClock.elapsedRealtime()
        var nearestDeadline = Long.MAX_VALUE
        for (i in targetActive.indices) {
            if (!targetActive[i]) continue
            val deadline = lastMatchElapsed[i] + holdMillis
            if (deadline < nearestDeadline) nearestDeadline = deadline
        }
        if (nearestDeadline == Long.MAX_VALUE) return

        val delay = (nearestDeadline - now).coerceAtLeast(0L)
        timeoutFuture = timeoutScheduler.schedule(
            { handleTimeout() },
            delay,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
    }

    private fun cancelTimeoutLocked() {
        timeoutFuture?.cancel(false)
        timeoutFuture = null
    }

    /** Chỉ snapshot + enqueue trong lock; callback thật chạy ngoài lock trên FYT-RuleState. */
    private fun queueStateLocked() {
        active = activeCount > 0
        val snapshot = RuleStateSnapshot(
            left = targetActive[SignalTarget.LEFT.ordinal],
            right = targetActive[SignalTarget.RIGHT.ordinal],
            hazard = targetActive[SignalTarget.HAZARD.ordinal]
        )
        try {
            stateExecutor.execute {
                try {
                    onStateChanged(snapshot)
                } catch (_: Throwable) {
                    // Không để callback audio/UI làm chết rule worker.
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        clearStateLocked(false)
        rulesSnapshot = emptyList()
        canRulesByIndex = emptyMap()
        mainRulesByIndex = emptyMap()
        timeoutScheduler.shutdownNow()
        stateExecutor.shutdownNow()
    }

    companion object {
        private const val DEFAULT_HOLD_MS = 1500L
        private const val MAX_HOLD_MS = 2000L
    }
}

'''
text = replace_between(text, "class RuleEngine(", "object RuntimeState {", rule_engine, "RuleEngine")
path.write_text(text, encoding="utf-8")


# -----------------------------------------------------------------------------
# FytBridge.kt: no monitor array copies, smaller latest-first queue, coalesced
# subscriptions, Binder calls outside lock, no unregister storm on disconnect.
# -----------------------------------------------------------------------------
Path("example/src/main/java/com/aoe/canbusmonitor/FytBridge.kt").write_text(r'''package com.aoe.canbusmonitor

import android.os.RemoteException
import com.aoe.fytcanbusmonitor.ConnectionObserver
import com.aoe.fytcanbusmonitor.IModuleCallback
import com.aoe.fytcanbusmonitor.IRemoteToolkit
import com.aoe.fytcanbusmonitor.RemoteModuleProxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Monitor chỉ chạy khi tab Giám sát đang mở và không pause. */
object MonitorCaptureState {
    @Volatile var enabled: Boolean = false
}

private object MonitorDispatcher {
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(256),
        ThreadFactory { runnable ->
            Thread(runnable, "FYT-Monitor").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        },
        // Nếu flood thì ưu tiên giữ event mới thay vì để UI xem log cũ quá trễ.
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    fun submit(event: FytEvent) {
        executor.execute {
            try {
                MonitorStore.accept(event)
            } catch (_: Throwable) {
                // Monitor/log tuyệt đối không được ảnh hưởng đường rule -> audio.
            }
        }
    }
}

class FytModuleCallback(
    private val moduleName: String,
    private val onRuleEvent: (module: String, index: Int, ints: IntArray?) -> Unit,
    private val shouldDeliverToRule: ((Int) -> Boolean)? = null
) : IModuleCallback.Stub() {
    @Throws(RemoteException::class)
    override fun update(
        updateCode: Int,
        intArray: IntArray?,
        floatArray: FloatArray?,
        strArray: Array<String?>?
    ) {
        val deliverToRule = shouldDeliverToRule?.invoke(updateCode) ?: true
        val deliverToMonitor = MonitorCaptureState.enabled &&
            MonitorStore.shouldQueueFast(moduleName, updateCode)

        if (!deliverToRule && !deliverToMonitor) return

        // Rule luôn được xử lý trước monitor và dùng trực tiếp IntArray do Parcel vừa tạo.
        if (deliverToRule) {
            onRuleEvent(moduleName, updateCode, intArray)
        }

        if (!deliverToMonitor) return

        // AIDL Stub tạo array mới từ Parcel cho callback này; worker có thể giữ reference an toàn,
        // không cần copyOf thêm một lần nữa.
        MonitorDispatcher.submit(
            FytEvent(
                module = moduleName,
                index = updateCode,
                ints = intArray,
                floats = floatArray,
                strings = strArray
            )
        )
    }
}

private object SubscriptionDispatcher {
    private val executor = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "FYT-Subscriptions").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }
    )

    fun execute(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (t: Throwable) {
                RuntimeState.lastError = "Subscription worker: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
    }
}

/**
 * Subscription động:
 * - setIndexes chỉ đổi desired state rất nhanh.
 * - mỗi module tối đa có một apply-loop đang queue/running; thay đổi liên tiếp được coalesce.
 * - Binder register/unregister chạy ngoài synchronized lock.
 * - disconnect chỉ clear local state; không gọi hàng trăm unregister vào Binder đã chết.
 */
class DynamicModuleSubscription(
    private val moduleId: Int,
    private val callback: IModuleCallback
) : ConnectionObserver {
    private val remoteProxy = RemoteModuleProxy()
    private val desiredIndexes = linkedSetOf<Int>()
    private val registeredIndexes = linkedSetOf<Int>()
    private var applyQueued = false
    private var connectionEpoch = 0L

    @Synchronized
    fun setIndexes(indexes: IntArray) {
        val next = indexes.asSequence()
            .filter { it >= 0 }
            .distinct()
            .sorted()
            .toCollection(linkedSetOf())

        if (desiredIndexes == next) return
        desiredIndexes.clear()
        desiredIndexes.addAll(next)
        scheduleApplyLocked()
    }

    override fun onConnected(toolkit: IRemoteToolkit?) {
        try {
            val remote = toolkit?.getRemoteModule(moduleId)
            synchronized(this) {
                remoteProxy.remoteModule = remote
                registeredIndexes.clear()
                connectionEpoch++
                scheduleApplyLocked()
            }
        } catch (t: Throwable) {
            RuntimeState.lastError = "Module $moduleId connect: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    override fun onDisconnected() {
        synchronized(this) {
            // Remote service đã mất; unregister lúc này chỉ tạo Binder lỗi/thời gian chờ vô ích.
            remoteProxy.remoteModule = null
            registeredIndexes.clear()
            connectionEpoch++
        }
    }

    private fun scheduleApplyLocked() {
        if (applyQueued) return
        applyQueued = true
        SubscriptionDispatcher.execute { applyLoop() }
    }

    private fun applyLoop() {
        var consecutiveFailures = 0
        while (true) {
            val epoch: Long
            val desired: Set<Int>
            val registered: Set<Int>
            synchronized(this) {
                if (remoteProxy.remoteModule == null) {
                    applyQueued = false
                    return
                }
                epoch = connectionEpoch
                desired = desiredIndexes.toSet()
                registered = registeredIndexes.toSet()
            }

            val remove = registered.filter { it !in desired }
            val add = desired.filter { it !in registered }

            if (remove.isEmpty() && add.isEmpty()) {
                synchronized(this) {
                    if (epoch == connectionEpoch && desiredIndexes == registeredIndexes) {
                        applyQueued = false
                        return
                    }
                }
                continue
            }

            val removedOk = ArrayList<Int>(remove.size)
            val addedOk = ArrayList<Int>(add.size)
            var failed = false

            // Không giữ synchronized(this) trong các Binder call có thể block.
            for (index in remove) {
                if (remoteProxy.unregisterSafe(callback, index)) removedOk += index else failed = true
            }
            for (index in add) {
                if (remoteProxy.registerSafe(callback, index, 1)) addedOk += index else failed = true
            }

            synchronized(this) {
                if (epoch == connectionEpoch) {
                    removedOk.forEach { registeredIndexes.remove(it) }
                    addedOk.forEach { registeredIndexes.add(it) }
                }
            }

            if (failed) {
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    RuntimeState.lastError = "Module $moduleId: register/unregister Binder thất bại"
                    synchronized(this) { applyQueued = false }
                    return
                }
                try {
                    Thread.sleep(50L)
                } catch (_: InterruptedException) {
                }
            } else {
                consecutiveFailures = 0
            }
        }
    }
}

fun concatRanges(vararg ranges: IntRange): IntArray {
    val size = ranges.sumOf { it.count() }
    val out = IntArray(size)
    var p = 0
    ranges.forEach { range ->
        range.forEach { value -> out[p++] = value }
    }
    return out
}
''', encoding="utf-8")


# -----------------------------------------------------------------------------
# TurnSignalService.kt: cached rule indexes/settings, no duplicate startup reload,
# async/debounced notifications, background path avoids monitor prefs work.
# -----------------------------------------------------------------------------
Path("example/src/main/java/com/aoe/canbusmonitor/TurnSignalService.kt").write_text(r'''package com.aoe.canbusmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.aoe.fytcanbusmonitor.ConnectionObserver
import com.aoe.fytcanbusmonitor.IRemoteToolkit
import com.aoe.fytcanbusmonitor.ModuleCodes.MODULE_CODE_BT
import com.aoe.fytcanbusmonitor.ModuleCodes.MODULE_CODE_CANBUS
import com.aoe.fytcanbusmonitor.ModuleCodes.MODULE_CODE_MAIN
import com.aoe.fytcanbusmonitor.MsToolkitConnection

class TurnSignalService : Service() {
    private lateinit var audio: AudioEngine
    private lateinit var ruleEngine: RuleEngine
    private val observers = arrayListOf<ConnectionObserver>()
    private var mainSubscription: DynamicModuleSubscription? = null
    private var btSubscription: DynamicModuleSubscription? = null
    private var canSubscription: DynamicModuleSubscription? = null

    private var currentRules: List<CanRule> = emptyList()
    private var currentStopMode: StopMode = StopMode.TIMEOUT
    private var soundEnabled = true
    private var ruleCanIndexes: Set<Int> = emptySet()
    private var ruleMainIndexes: Set<Int> = emptySet()

    private val monitorMainIndexes = concatRanges(0..76, 78..200)
    private val monitorBtIndexes = concatRanges(0..100)
    private val monitorCanIndexes = concatRanges(0..200, 500..600, 1000..1200)
    private val monitorMainSet = monitorMainIndexes.toSet()
    private val monitorBtSet = monitorBtIndexes.toSet()
    private val monitorCanSet = monitorCanIndexes.toSet()

    private val notificationHandler = Handler(Looper.getMainLooper())
    private val notificationRunnable = Runnable { updateNotificationNow() }
    private var lastNotificationStatus: String? = null

    @Volatile private var destroying = false

    private val statusObserver = object : ConnectionObserver {
        override fun onConnected(toolkit: IRemoteToolkit?) {
            RuntimeState.fytConnected = toolkit != null
            if (RuntimeState.audioReady) RuntimeState.lastError = null
            scheduleNotificationUpdate()
        }

        override fun onDisconnected() {
            RuntimeState.fytConnected = false
            if (!destroying) {
                ruleEngine.clearState()
                // Dừng ngay cả trước khi state worker chạy callback false.
                audio.stopRule()
                scheduleNotificationUpdate()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeState.serviceRunning = true
        RuntimeState.fytPackagePresent = isFytPackageAvailable()

        MonitorStore.configureFilter(
            SettingsStore.monitorFilterMode(this),
            SettingsStore.monitorFilterModule(this),
            SettingsStore.monitorFilterIndexes(this)
        )

        createNotificationChannel()
        val initialStatus = notificationStatus()
        lastNotificationStatus = initialStatus
        startForeground(NOTIFICATION_ID, buildNotification(initialStatus))

        // RuleStore.load có thể restore stopMode/timeout từ rules.json.
        currentRules = RuleStore.load(this)
        currentStopMode = SettingsStore.stopMode(this)
        soundEnabled = SettingsStore.isEnabled(this)

        audio = AudioEngine(this)
        ruleEngine = RuleEngine(SettingsStore.timeoutMillis(this).toLong()) { state ->
            RuntimeState.leftActive = state.left
            RuntimeState.rightActive = state.right
            RuntimeState.hazardActive = state.hazard
            RuntimeState.ruleActive = state.anyActive
            // Đây chạy trên FYT-RuleState, không còn giữ lock RuleEngine/Binder callback.
            audio.setRuleActive(state.anyActive && soundEnabled)
            scheduleNotificationUpdate()
        }
        ruleEngine.setHoldMillis(SettingsStore.timeoutMillis(this).toLong())
        ruleEngine.setStopMode(currentStopMode)
        ruleEngine.setRules(currentRules)
        rebuildRuleSubscriptionIndexes()
        scheduleNotificationUpdate()

        if (RuntimeState.fytPackagePresent) {
            connectToFyt()
        } else {
            RuntimeState.fytConnected = false
            if (RuntimeState.audioReady) RuntimeState.lastError = null
            scheduleNotificationUpdate()
        }
    }

    private fun connectToFyt() {
        val mainCallback = FytModuleCallback(
            moduleName = RuleModule.MAIN.name,
            onRuleEvent = { module, index, ints -> ruleEngine.onRawEvent(module, index, ints) },
            shouldDeliverToRule = { index -> ruleEngine.hasRuleFor(RuleModule.MAIN.name, index) }
        )

        val btCallback = FytModuleCallback(
            moduleName = "BT",
            onRuleEvent = { _, _, _ -> },
            shouldDeliverToRule = { false }
        )

        val canCallback = FytModuleCallback(
            moduleName = RuleModule.CANBUS.name,
            onRuleEvent = { module, index, ints -> ruleEngine.onRawEvent(module, index, ints) },
            shouldDeliverToRule = { index -> ruleEngine.hasRuleFor(RuleModule.CANBUS.name, index) }
        )

        mainSubscription = DynamicModuleSubscription(MODULE_CODE_MAIN, mainCallback)
        btSubscription = DynamicModuleSubscription(MODULE_CODE_BT, btCallback)
        canSubscription = DynamicModuleSubscription(MODULE_CODE_CANBUS, canCallback)

        observers += statusObserver
        observers += mainSubscription!!
        observers += btSubscription!!
        observers += canSubscription!!

        updateSubscriptions()
        observers.forEach { MsToolkitConnection.instance.addObserver(it) }
        try {
            MsToolkitConnection.instance.connect(applicationContext)
        } catch (t: Throwable) {
            if (RuntimeState.audioReady) {
                RuntimeState.lastError = "Kết nối FYT: ${t.javaClass.simpleName}: ${t.message}"
            }
            scheduleNotificationUpdate()
        }
    }

    private fun isFytPackageAvailable(): Boolean {
        return try {
            packageManager.getPackageInfo("com.syu.ms", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH -> refreshConfiguration()
            ACTION_UPDATE_SUBSCRIPTIONS -> updateSubscriptions()
            ACTION_TEST_SOUND -> {
                audio.setVolume(SettingsStore.volume(this))
                audio.startTest()
            }
            ACTION_STOP_TEST -> audio.stopTest()
            ACTION_STOP_SERVICE -> {
                audio.stopTest()
                ruleEngine.clearState()
                audio.stopRule()
                stopSelf()
            }
            // onCreate đã load toàn bộ config. Start bình thường/null không đọc JSON/prefs lại lần 2.
            else -> Unit
        }
        return START_STICKY
    }

    private fun refreshConfiguration() {
        MonitorStore.configureFilter(
            SettingsStore.monitorFilterMode(this),
            SettingsStore.monitorFilterModule(this),
            SettingsStore.monitorFilterIndexes(this)
        )
        currentRules = RuleStore.load(this)
        currentStopMode = SettingsStore.stopMode(this)
        soundEnabled = SettingsStore.isEnabled(this)

        ruleEngine.setHoldMillis(SettingsStore.timeoutMillis(this).toLong())
        ruleEngine.setStopMode(currentStopMode)
        ruleEngine.setRules(currentRules)
        audio.setVolume(SettingsStore.volume(this))
        rebuildRuleSubscriptionIndexes()
        updateSubscriptions()

        if (soundEnabled && ruleEngine.active) {
            audio.setRuleActive(true)
        } else {
            audio.stopRule()
        }
        scheduleNotificationUpdate()
    }

    /** TIMEOUT không cần subscribe STOP-only index; TRIGGER cần cả START + STOP. */
    private fun rebuildRuleSubscriptionIndexes() {
        val eligible = currentRules.asSequence().filter {
            it.enabled && (currentStopMode == StopMode.TRIGGER || it.action == RuleAction.START)
        }
        val can = linkedSetOf<Int>()
        val main = linkedSetOf<Int>()
        eligible.forEach { rule ->
            if (rule.index < 0) return@forEach
            if (rule.module == RuleModule.MAIN) main += rule.index else can += rule.index
        }
        ruleCanIndexes = can
        ruleMainIndexes = main
    }

    /**
     * Background/tab khác/Monitor pause: chỉ rule indexes, không đọc filter prefs.
     * Monitor foreground: union rule indexes với phạm vi monitor theo filter đã lưu.
     */
    private fun updateSubscriptions() {
        if (!MonitorCaptureState.enabled) {
            mainSubscription?.setIndexes(ruleMainIndexes.sorted().toIntArray())
            canSubscription?.setIndexes(ruleCanIndexes.sorted().toIntArray())
            btSubscription?.setIndexes(IntArray(0))
            return
        }

        val mode = SettingsStore.monitorFilterMode(this)
        val filterModule = SettingsStore.monitorFilterModule(this)
        val filterIndexes = SettingsStore.monitorFilterIndexes(this)

        fun monitorIndexes(module: String, fullRange: Set<Int>): Set<Int> {
            return when (mode) {
                MonitorFilterMode.ALL -> fullRange
                MonitorFilterMode.ONLY_CAN_INDEX ->
                    if (module == filterModule) filterIndexes else emptySet()
                MonitorFilterMode.EXCLUDE_CAN_INDEX ->
                    if (module == filterModule) fullRange.filterTo(linkedSetOf()) { it !in filterIndexes }
                    else fullRange
            }
        }

        val mainWanted = (ruleMainIndexes + monitorIndexes(RuleModule.MAIN.name, monitorMainSet))
            .sorted().toIntArray()
        val canWanted = (ruleCanIndexes + monitorIndexes(RuleModule.CANBUS.name, monitorCanSet))
            .sorted().toIntArray()
        val btWanted = monitorIndexes("BT", monitorBtSet).sorted().toIntArray()

        mainSubscription?.setIndexes(mainWanted)
        canSubscription?.setIndexes(canWanted)
        btSubscription?.setIndexes(btWanted)
    }

    override fun onDestroy() {
        destroying = true
        notificationHandler.removeCallbacks(notificationRunnable)
        observers.forEach { MsToolkitConnection.instance.removeObserver(it) }
        observers.clear()
        if (::audio.isInitialized) audio.stopRule()
        if (::ruleEngine.isInitialized) ruleEngine.release()
        if (::audio.isInitialized) audio.release()
        RuntimeState.serviceRunning = false
        RuntimeState.fytConnected = false
        RuntimeState.ruleActive = false
        RuntimeState.leftActive = false
        RuntimeState.rightActive = false
        RuntimeState.hazardActive = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Giữ dịch vụ giám sát FYT chạy nền"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun notificationStatus(): String = when {
        !RuntimeState.audioReady -> "Audio chưa sẵn sàng"
        !RuntimeState.fytPackagePresent -> "Chế độ thử điện thoại • audio đã sẵn sàng"
        !RuntimeState.fytConnected -> "Đang chờ dịch vụ FYT"
        RuntimeState.ruleActive && soundEnabled -> "FYT rule đang active • âm xi nhan đang phát"
        else -> "Đã kết nối FYT • đang giám sát rule"
    }

    private fun buildNotification(status: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("FYT Turn Sound")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pending)
            .build()
    }

    /** Chỉ post một update; nhiều state đổi sát nhau được gộp, không block Binder/rule worker. */
    private fun scheduleNotificationUpdate() {
        notificationHandler.removeCallbacks(notificationRunnable)
        notificationHandler.post(notificationRunnable)
    }

    private fun updateNotificationNow() {
        val status = notificationStatus()
        if (status == lastNotificationStatus) return
        lastNotificationStatus = status
        try {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification(status)
            )
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.aoe.canbusmonitor.turnsound.REFRESH"
        const val ACTION_UPDATE_SUBSCRIPTIONS = "com.aoe.canbusmonitor.turnsound.UPDATE_SUBSCRIPTIONS"
        const val ACTION_TEST_SOUND = "com.aoe.canbusmonitor.turnsound.TEST"
        const val ACTION_STOP_TEST = "com.aoe.canbusmonitor.turnsound.STOP_TEST"
        const val ACTION_STOP_SERVICE = "com.aoe.canbusmonitor.turnsound.STOP_SERVICE"
        private const val CHANNEL_ID = "fyt_turn_sound"
        private const val NOTIFICATION_ID = 7007
    }
}
''', encoding="utf-8")


# -----------------------------------------------------------------------------
# MsToolkitConnection.kt: actually use dedicated connection thread instead of
# creating an unused HandlerThread while posting work to main looper.
# -----------------------------------------------------------------------------
Path("fytcanbusmonitor/src/main/java/com/aoe/fytcanbusmonitor/MsToolkitConnection.kt").write_text(r'''package com.aoe.fytcanbusmonitor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import java.util.ArrayList
import java.util.concurrent.ThreadLocalRandom

class MsToolkitConnection private constructor() : ServiceConnection {
    @Volatile private var mConnecting = false
    @Volatile private var mContext: Context? = null
    @Volatile var remoteToolkit: IRemoteToolkit? = null
        private set

    private val mHandler = Handler(requireNotNull(looper))
    private val mConnectionObservers = ArrayList<ConnectionObserver>()

    private val mRunnableConnect = object : Runnable {
        override fun run() {
            if (remoteToolkit != null) {
                mConnecting = false
                return
            }

            val intent = Intent("com.syu.ms.toolkit").apply {
                component = ComponentName("com.syu.ms", "app.ToolkitService")
            }
            try {
                mContext?.bindService(intent, instance, Context.BIND_AUTO_CREATE)
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            } catch (_: Throwable) {
            }

            if (remoteToolkit == null) {
                mHandler.postDelayed(this, randomReconnectDelay())
            }
        }
    }

    companion object {
        private val connectionThread = HandlerThread("FYT-Connection").apply { start() }
        var looper: Looper? = connectionThread.looper
            private set
        val instance = MsToolkitConnection()

        private fun randomReconnectDelay(): Long =
            ThreadLocalRandom.current().nextLong(1000L, 4001L)
    }

    @Synchronized
    fun connect(context: Context?) {
        connect(context, 0L)
    }

    @Synchronized
    private fun connect(context: Context?, delayMillis: Long) {
        if (!mConnecting && remoteToolkit == null && context != null) {
            mContext = context.applicationContext
            mConnecting = true
            mHandler.removeCallbacks(mRunnableConnect)
            mHandler.postDelayed(mRunnableConnect, delayMillis)
        }
    }

    @Synchronized
    fun addObserver(observer: ConnectionObserver?) {
        if (observer == null || mConnectionObservers.contains(observer)) return
        mConnectionObservers.add(observer)
        val toolkit = remoteToolkit
        if (toolkit != null) {
            mHandler.post { observer.onConnected(toolkit) }
        }
    }

    @Synchronized
    fun removeObserver(observer: ConnectionObserver?) {
        if (observer == null) return
        mConnectionObservers.remove(observer)
        if (remoteToolkit != null) {
            mHandler.post { observer.onDisconnected() }
        }
    }

    @Synchronized
    fun clearObservers() {
        val current = if (remoteToolkit != null) mConnectionObservers.toList() else emptyList()
        mConnectionObservers.clear()
        current.forEach { observer -> mHandler.post { observer.onDisconnected() } }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        val toolkit = IRemoteToolkit.Stub.asInterface(service)
        val observers: List<ConnectionObserver>
        synchronized(this) {
            remoteToolkit = toolkit
            mConnecting = false
            mHandler.removeCallbacks(mRunnableConnect)
            observers = mConnectionObservers.toList()
        }
        observers.forEach { observer ->
            mHandler.post {
                val current = remoteToolkit
                if (current != null) observer.onConnected(current)
            }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        val observers: List<ConnectionObserver>
        val context: Context?
        synchronized(this) {
            remoteToolkit = null
            mConnecting = false
            mHandler.removeCallbacks(mRunnableConnect)
            observers = mConnectionObservers.toList()
            context = mContext
        }
        observers.forEach { observer -> mHandler.post { observer.onDisconnected() } }
        connect(context, randomReconnectDelay())
    }

    override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
    override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
}
''', encoding="utf-8")


# -----------------------------------------------------------------------------
# RemoteModuleProxy: volatile remote + success-returning registration helpers so
# subscription bookkeeping never marks a failed Binder call as registered.
# -----------------------------------------------------------------------------
path = Path("fytcanbusmonitor/src/main/java/com/aoe/fytcanbusmonitor/RemoteModuleProxy.kt")
text = path.read_text(encoding="utf-8")
text = replace_once(text, "    var remoteModule: IRemoteModule? = null", "    @Volatile var remoteModule: IRemoteModule? = null", "remote volatile")
old = r'''    // com.syu.ipc.IRemoteModule
    override fun register(callback: IModuleCallback?, updateCode: Int, update: Int) {
        val remoteModule = remoteModule
        if (remoteModule != null) {
            try {
                remoteModule.register(callback, updateCode, update)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }
    }

    // com.syu.ipc.IRemoteModule
    override fun unregister(callback: IModuleCallback?, updateCode: Int) {
        val remoteModule = remoteModule
        if (remoteModule != null) {
            try {
                remoteModule.unregister(callback, updateCode)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }
    }
'''
new = r'''    fun registerSafe(callback: IModuleCallback?, updateCode: Int, update: Int): Boolean {
        val remote = remoteModule ?: return false
        return try {
            remote.register(callback, updateCode, update)
            true
        } catch (_: RemoteException) {
            false
        }
    }

    fun unregisterSafe(callback: IModuleCallback?, updateCode: Int): Boolean {
        val remote = remoteModule ?: return false
        return try {
            remote.unregister(callback, updateCode)
            true
        } catch (_: RemoteException) {
            false
        }
    }

    // com.syu.ipc.IRemoteModule
    override fun register(callback: IModuleCallback?, updateCode: Int, update: Int) {
        registerSafe(callback, updateCode, update)
    }

    // com.syu.ipc.IRemoteModule
    override fun unregister(callback: IModuleCallback?, updateCode: Int) {
        unregisterSafe(callback, updateCode)
    }
'''
text = replace_once(text, old, new, "RemoteModuleProxy register/unregister")
path.write_text(text, encoding="utf-8")


# -----------------------------------------------------------------------------
# AudioEngine: sleep indefinitely while idle, reuse source PCM at gain=1, remove
# hot toggle logging. Buffer 120ms and WORKER_CHUNK_SAMPLES=4096 are untouched.
# -----------------------------------------------------------------------------
path = Path("example/src/main/java/com/aoe/canbusmonitor/AudioEngine.kt")
text = path.read_text(encoding="utf-8")
text = replace_once(text, "                            wakeLock.wait(500L)", "                            wakeLock.wait()", "audio idle wait")
text = replace_once(text, "        if (gainValue == 1f) return source.copyOf()", "        if (gainValue == 1f) return source", "gain 1 reuse")
text = text.replace('                Log.d(TAG, "Audio playback ON")\n', '')
text = text.replace('            Log.d(TAG, "Audio playback OFF")\n', '')
path.write_text(text, encoding="utf-8")


# -----------------------------------------------------------------------------
# MainActivity: refresh only visible tab; Monitor incrementally appends new lines
# instead of rebuilding 2500-line TextView every 400ms.
# -----------------------------------------------------------------------------
path = Path("example/src/main/java/com/aoe/canbusmonitor/MainActivity.kt")
text = path.read_text(encoding="utf-8")
old = r'''    private var lastMonitorVersion = -1L
    private var monitorPaused = false
    private var selectedTabIndex = 0
    private var storagePermissionDialogShown = false
    private var storageAccessWasGranted = false

    private val refresher = object : Runnable {
        override fun run() {
            refreshMonitor()
            refreshStatus()
            handler.postDelayed(this, 400L)
        }
    }
'''
new = r'''    private var lastMonitorVersion = -1L
    private var monitorUiLineCount = 0
    private var monitorPaused = false
    private var selectedTabIndex = 0
    private var activityResumed = false
    private var storagePermissionDialogShown = false
    private var storageAccessWasGranted = false

    private val refresher = object : Runnable {
        override fun run() {
            if (!activityResumed) return
            when (selectedTabIndex) {
                0 -> if (!monitorPaused) {
                    refreshMonitor()
                    handler.postDelayed(this, 250L)
                }
                2 -> {
                    refreshStatus()
                    handler.postDelayed(this, 1000L)
                }
            }
        }
    }

    private fun scheduleRefresher() {
        handler.removeCallbacks(refresher)
        if (!activityResumed) return
        if ((selectedTabIndex == 0 && !monitorPaused) || selectedTabIndex == 2) {
            handler.post(refresher)
        }
    }
'''
text = replace_once(text, old, new, "MainActivity fields/refresher")

text = replace_once(
    text,
    "    override fun onResume() {\n        super.onResume()\n\n        val hasStorageAccess = hasPersistentStorageAccess()",
    "    override fun onResume() {\n        super.onResume()\n        activityResumed = true\n\n        val hasStorageAccess = hasPersistentStorageAccess()",
    "onResume activityResumed"
)
text = replace_once(
    text,
    r'''        MonitorCaptureState.enabled = selectedTabIndex == 0 && !monitorPaused
        sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
        handler.removeCallbacks(refresher)
        handler.post(refresher)
        renderRules()
    }

    override fun onPause() {
        MonitorCaptureState.enabled = false
        sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
        handler.removeCallbacks(refresher)
        super.onPause()
    }
''',
    r'''        MonitorCaptureState.enabled = selectedTabIndex == 0 && !monitorPaused
        sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
        scheduleRefresher()
        renderRules()
    }

    override fun onPause() {
        activityResumed = false
        MonitorCaptureState.enabled = false
        sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
        handler.removeCallbacks(refresher)
        super.onPause()
    }
''',
    "onResume/onPause refresher"
)

text = replace_once(
    text,
    r'''                    if (pageIndex == 1) renderRules()
                    if (pageIndex == 0) {
                        lastMonitorVersion = -1L
                        refreshMonitor()
                    }
''',
    r'''                    if (pageIndex == 1) renderRules()
                    if (pageIndex == 0) {
                        lastMonitorVersion = -1L
                        monitorUiLineCount = 0
                    }
                    if (pageIndex == 2) refreshStatus()
                    scheduleRefresher()
''',
    "tab refresher"
)

text = replace_once(
    text,
    r'''                MonitorStore.clear()
                lastMonitorVersion = -1L
                refreshMonitor()
''',
    r'''                MonitorStore.clear()
                lastMonitorVersion = -1L
                monitorUiLineCount = 0
                refreshMonitor()
''',
    "monitor clear"
)

text = replace_once(
    text,
    r'''                sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
                if (!monitorPaused) lastMonitorVersion = -1L
''',
    r'''                sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
                if (!monitorPaused) {
                    lastMonitorVersion = -1L
                    monitorUiLineCount = 0
                }
                scheduleRefresher()
''',
    "monitor pause"
)

old_refresh = r'''    private fun refreshMonitor() {
        val latest = MonitorStore.latestRuleEvent
        latestEventText.text = if (latest == null) {
            "CANBUS/MAIN mới nhất: đang chờ..."
        } else {
            "CANBUS/MAIN mới nhất: ${latest.originalStyleLine()}"
        }
        if (monitorPaused || MonitorStore.version == lastMonitorVersion) return
        lastMonitorVersion = MonitorStore.version
        val text = MonitorStore.snapshot()
        monitorText.text = if (text.isBlank()) "Đang chờ dữ liệu FYT..." else text
        monitorScroll.post { monitorScroll.fullScroll(View.FOCUS_DOWN) }
    }
'''
new_refresh = r'''    private fun refreshMonitor() {
        if (!activityResumed || selectedTabIndex != 0 || monitorPaused) return
        if (MonitorStore.version == lastMonitorVersion) return

        latestEventText.text = MonitorStore.latestRuleLine?.let {
            "CANBUS/MAIN mới nhất: $it"
        } ?: "CANBUS/MAIN mới nhất: đang chờ..."

        fun applyFullSnapshot() {
            val snapshot = MonitorStore.takeUiSnapshot()
            monitorText.text = if (snapshot.text.isBlank()) "Đang chờ dữ liệu FYT..." else snapshot.text
            monitorUiLineCount = snapshot.lineCount
            lastMonitorVersion = snapshot.version
        }

        if (lastMonitorVersion < 0L) {
            applyFullSnapshot()
        } else {
            val batch = MonitorStore.drainUiBatch()
            if (batch.requiresReset) {
                applyFullSnapshot()
            } else {
                if (batch.lines.isNotEmpty()) {
                    val addition = batch.lines.joinToString("\n")
                    if (monitorUiLineCount == 0) {
                        monitorText.text = addition
                    } else {
                        monitorText.append("\n")
                        monitorText.append(addition)
                    }
                    monitorUiLineCount += batch.lines.size
                }
                lastMonitorVersion = batch.version

                // Store vẫn giữ 2500 dòng. UI được phép append tới 3000 rồi rebuild một lần,
                // thay vì rebuild toàn bộ sau mỗi event khi đã chạm giới hạn.
                if (monitorUiLineCount > 3000) applyFullSnapshot()
            }
        }
        monitorScroll.post { monitorScroll.fullScroll(View.FOCUS_DOWN) }
    }
'''
text = replace_once(text, old_refresh, new_refresh, "refreshMonitor incremental")

text = replace_once(
    text,
    r'''                lastMonitorVersion = -1L
                refreshMonitor()
                sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
''',
    r'''                lastMonitorVersion = -1L
                monitorUiLineCount = 0
                refreshMonitor()
                sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
''',
    "filter reset line count"
)
path.write_text(text, encoding="utf-8")


# -----------------------------------------------------------------------------
# Build/runtime cleanup. UI uses platform widgets/themes, old monitor classes and
# resources are no longer referenced.
# -----------------------------------------------------------------------------
path = Path("example/build.gradle")
text = path.read_text(encoding="utf-8")
text = text.replace('versionCode 13', 'versionCode 14', 1)
text = text.replace('versionName "1.6.3-config-defaults-multistop"', 'versionName "1.6.4-performance-pass"', 1)
old_deps = r'''dependencies {
    implementation 'androidx.core:core-ktx:1.10.1'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.9.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation project(path: ':fytcanbusmonitor')
    testImplementation 'junit:junit:4.13.2'
    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
}
'''
new_deps = r'''dependencies {
    implementation project(path: ':fytcanbusmonitor')
    testImplementation 'junit:junit:4.13.2'
}
'''
text = replace_once(text, old_deps, new_deps, "example dependencies")
path.write_text(text, encoding="utf-8")

path = Path("fytcanbusmonitor/build.gradle")
text = path.read_text(encoding="utf-8")
old_lib_deps = r'''dependencies {

    implementation 'androidx.core:core-ktx:1.10.1'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.9.0'

     testImplementation 'junit:junit:4.13.2'
    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
}
'''
new_lib_deps = r'''dependencies {
    testImplementation 'junit:junit:4.13.2'
}
'''
text = replace_once(text, old_lib_deps, new_lib_deps, "library dependencies")
path.write_text(text, encoding="utf-8")

for dead in [
    "example/src/main/java/com/aoe/canbusmonitor/DataProxy.kt",
    "example/src/main/java/com/aoe/canbusmonitor/IPCConnection.kt",
    "example/src/main/java/com/aoe/canbusmonitor/ModuleCallback.kt",
    "example/src/main/res/layout/activity_main.xml",
    "example/src/main/res/values/themes.xml",
    "example/src/main/res/values/colors.xml",
    "example/src/main/res/raw/turn_signal.mp3",
]:
    Path(dead).unlink(missing_ok=True)

print("performance patch applied")
