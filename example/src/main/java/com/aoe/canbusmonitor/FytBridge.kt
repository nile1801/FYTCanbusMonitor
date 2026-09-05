package com.aoe.canbusmonitor

import android.os.RemoteException
import com.aoe.fytcanbusmonitor.ConnectionObserver
import com.aoe.fytcanbusmonitor.IModuleCallback
import com.aoe.fytcanbusmonitor.IRemoteToolkit
import com.aoe.fytcanbusmonitor.RemoteModuleProxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Monitor chỉ chạy khi tab Giám sát đang mở và không pause. */
object MonitorCaptureState {
    @Volatile var enabled: Boolean = false
}

private object MonitorDispatcher {
    private val threadNumber = AtomicInteger(1)
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(512),
        ThreadFactory { runnable ->
            Thread(runnable, "FYT-Monitor-${threadNumber.getAndIncrement()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        },
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
        // O(1) lookup trước mọi allocation.
        val deliverToRule = shouldDeliverToRule?.invoke(updateCode) ?: true
        val deliverToMonitor = MonitorCaptureState.enabled &&
            MonitorStore.shouldQueueFast(moduleName, updateCode)

        if (!deliverToRule && !deliverToMonitor) return

        // Đường ưu tiên: đọc IntArray AIDL trực tiếp ngay trong callback, không copy, không tạo FytEvent.
        // RuleEngine không giữ reference sau khi hàm này return.
        if (deliverToRule) {
            onRuleEvent(moduleName, updateCode, intArray)
        }

        if (!deliverToMonitor) return

        // Chỉ debug monitor mới cần snapshot/copy để xử lý bất đồng bộ ở worker riêng.
        val event = FytEvent(
            module = moduleName,
            index = updateCode,
            ints = intArray?.copyOf(),
            floats = floatArray?.copyOf(),
            strings = strArray?.copyOf()
        )
        MonitorDispatcher.submit(event)
    }
}

private object SubscriptionDispatcher {
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32),
        ThreadFactory { runnable ->
            Thread(runnable, "FYT-Subscriptions").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        },
        ThreadPoolExecutor.DiscardOldestPolicy()
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
 * - desiredIndexes được đổi rất nhanh trên caller thread.
 * - register/unregister Binder chạy trên worker riêng, không chặn main/UI.
 * - chỉ diff phần thay đổi, không re-register toàn bộ khi không cần.
 */
class DynamicModuleSubscription(
    private val moduleId: Int,
    private val callback: IModuleCallback
) : ConnectionObserver {
    private val remoteProxy = RemoteModuleProxy()
    private val desiredIndexes = linkedSetOf<Int>()
    private val registeredIndexes = linkedSetOf<Int>()

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
        scheduleApply()
    }

    override fun onConnected(toolkit: IRemoteToolkit?) {
        try {
            synchronized(this) {
                remoteProxy.remoteModule = toolkit?.getRemoteModule(moduleId)
                registeredIndexes.clear()
            }
            scheduleApply()
        } catch (t: Throwable) {
            RuntimeState.lastError = "Module $moduleId connect: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    override fun onDisconnected() {
        SubscriptionDispatcher.execute {
            synchronized(this) {
                try {
                    registeredIndexes.toList().forEach { index ->
                        remoteProxy.unregister(callback, index)
                    }
                } catch (_: Throwable) {
                } finally {
                    registeredIndexes.clear()
                    remoteProxy.remoteModule = null
                }
            }
        }
    }

    private fun scheduleApply() {
        SubscriptionDispatcher.execute {
            synchronized(this) {
                if (remoteProxy.remoteModule == null) return@synchronized

                val remove = registeredIndexes.filter { it !in desiredIndexes }
                remove.forEach { index ->
                    remoteProxy.unregister(callback, index)
                    registeredIndexes.remove(index)
                }

                val add = desiredIndexes.filter { it !in registeredIndexes }
                add.forEach { index ->
                    remoteProxy.register(callback, index, 1)
                    registeredIndexes.add(index)
                }
            }
        }
    }
}

fun concatRanges(vararg ranges: IntRange): IntArray {
    val out = ArrayList<Int>()
    ranges.forEach { range -> range.forEach { out.add(it) } }
    return out.toIntArray()
}
