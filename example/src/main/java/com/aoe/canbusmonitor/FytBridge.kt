package com.aoe.canbusmonitor

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
