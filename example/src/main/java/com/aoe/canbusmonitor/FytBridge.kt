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
                // Monitor/log không được phép làm ảnh hưởng đường xử lý CAN -> âm thanh.
            }
        }
    }
}

class FytModuleCallback(
    private val moduleName: String,
    private val onEvent: (FytEvent) -> Unit,
    private val shouldDeliverToRule: ((Int) -> Boolean)? = null
) : IModuleCallback.Stub() {
    @Throws(RemoteException::class)
    override fun update(
        updateCode: Int,
        intArray: IntArray?,
        floatArray: FloatArray?,
        strArray: Array<String?>?
    ) {
        // Kiểm tra thật sớm trước khi copy array/tạo FytEvent. Với CANBUS, RuleEngine truyền
        // predicate O(1) dựa trên index; index không có rule sẽ không đi vào đường audio.
        val deliverToRule = shouldDeliverToRule?.invoke(updateCode) ?: true
        val deliverToMonitor = MonitorStore.shouldQueueFast(moduleName, updateCode)
        if (!deliverToRule && !deliverToMonitor) return

        val event = FytEvent(
            module = moduleName,
            index = updateCode,
            ints = intArray?.copyOf(),
            floats = floatArray?.copyOf(),
            strings = strArray?.copyOf()
        )

        // Đường ưu tiên: chỉ index được RuleEngine quan tâm mới vào đây.
        if (deliverToRule) onEvent(event)

        // Đường phụ: filter đã được xét trước queue. Ví dụ exclude CANBUS:1019 thì 1019
        // không còn tạo Runnable trong queue monitor. Queue vẫn giới hạn để bảo vệ callback.
        if (deliverToMonitor) MonitorDispatcher.submit(event)
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
