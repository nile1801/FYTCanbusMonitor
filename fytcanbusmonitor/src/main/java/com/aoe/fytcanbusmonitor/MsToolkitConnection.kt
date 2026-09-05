package com.aoe.fytcanbusmonitor

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
