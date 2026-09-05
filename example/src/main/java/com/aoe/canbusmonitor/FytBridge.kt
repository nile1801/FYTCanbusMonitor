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

class ModuleSubscription(
    private val moduleId: Int,
    private val callback: IModuleCallback,
    private val indexes: IntArray
) : ConnectionObserver {
    private val remoteProxy = RemoteModuleProxy()

    override fun onConnected(toolkit: IRemoteToolkit?) {
        try {
            remoteProxy.remoteModule = toolkit?.getRemoteModule(moduleId)
            indexes.forEach { index -> remoteProxy.register(callback, index, 1) }
        } catch (t: Throwable) {
            RuntimeState.lastError = "Module $moduleId register: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    override fun onDisconnected() {
        try {
            indexes.forEach { index -> remoteProxy.unregister(callback, index) }
        } catch (_: Throwable) {
        } finally {
            remoteProxy.remoteModule = null
        }
    }
}

fun concatRanges(vararg ranges: IntRange): IntArray {
    val out = ArrayList<Int>()
    ranges.forEach { range -> range.forEach { out.add(it) } }
    return out.toIntArray()
}
