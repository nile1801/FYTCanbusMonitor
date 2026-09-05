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
    private val onEvent: (FytEvent) -> Unit
) : IModuleCallback.Stub() {
    @Throws(RemoteException::class)
    override fun update(
        updateCode: Int,
        intArray: IntArray?,
        floatArray: FloatArray?,
        strArray: Array<String?>?
    ) {
        val event = FytEvent(
            module = moduleName,
            index = updateCode,
            ints = intArray?.copyOf(),
            floats = floatArray?.copyOf(),
            strings = strArray?.copyOf()
        )

        // Đường ưu tiên: xử lý rule/audio ngay trên callback FYT.
        // Không format chuỗi, ghi Logcat hay cập nhật monitor trước khi xét luật.
        onEvent(event)

        // Đường phụ: monitor/log chạy ở worker riêng. Nếu CAN spam quá nhanh,
        // hàng đợi có giới hạn và bỏ log cũ thay vì làm nghẽn callback CAN.
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
