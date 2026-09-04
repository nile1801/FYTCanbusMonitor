package com.aoe.fytcanbusmonitor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import java.util.*

class MsToolkitConnection private constructor() : ServiceConnection {
    private var mConnecting = false
    private var mContext: Context? = null
    var remoteToolkit: IRemoteToolkit? = null
        private set
    private val mHandler: Handler = Handler(Looper.getMainLooper())
    private val mConnectionObservers: ArrayList<ConnectionObserver> = ArrayList()
    private val mRunnableConnect: Runnable = object : Runnable {
        override fun run() {
            if (remoteToolkit != null) {
                mConnecting = false
                return
            }

            val intent = Intent("com.syu.ms.toolkit").apply {
                component = ComponentName("com.syu.ms", "app.ToolkitService")
            }

            try {
                // Some non-FYT Android builds throw instead of simply returning false when
                // the explicit service component does not exist or cannot be bound.
                mContext?.bindService(intent, instance, Context.BIND_AUTO_CREATE)
            } catch (_: SecurityException) {
                // Keep retry semantics for FYT boot races without crashing the host app.
            } catch (_: IllegalArgumentException) {
            } catch (_: Throwable) {
            }

            mHandler.postDelayed(this, Random().nextInt(3000) + 1000L)
        }
    }

    companion object {
        val instance = MsToolkitConnection()
        var looper: Looper? = null

        init {
            val thread = HandlerThread("ConnectionThread")
            thread.start()
            looper = thread.looper
        }
    }

    @Synchronized
    fun connect(context: Context?) {
        connect(context, 0L)
    }

    private fun connect(context: Context?, delayMillis: Long) {
        if (!mConnecting && remoteToolkit == null && context != null) {
            mContext = context.applicationContext
            mConnecting = true
            mHandler.postDelayed(mRunnableConnect, delayMillis)
        }
    }

    @Synchronized
    fun addObserver(observer: ConnectionObserver?) {
        if (observer != null) {
            if (!mConnectionObservers.contains(observer)) {
                mConnectionObservers.add(observer)
                if (remoteToolkit != null) {
                    mHandler.post(OnServiceConnected(this, observer, null))
                }
            }
        }
    }

    @Synchronized
    fun removeObserver(observer: ConnectionObserver?) {
        if (observer != null) {
            mConnectionObservers.remove(observer)
        }
        if (remoteToolkit != null) {
            mHandler.post(OnServiceDisconnected(this, observer, null))
        }
    }

    @Synchronized
    fun clearObservers() {
        if (remoteToolkit != null) {
            val it: Iterator<ConnectionObserver> = mConnectionObservers.iterator()
            while (it.hasNext()) {
                val observer: ConnectionObserver = it.next()
                mHandler.post(OnServiceDisconnected(this, observer, null))
            }
        }
        mConnectionObservers.clear()
    }

    @Synchronized
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        remoteToolkit = IRemoteToolkit.Stub.asInterface(service)
        val it: Iterator<ConnectionObserver> = mConnectionObservers.iterator()
        while (it.hasNext()) {
            val observer: ConnectionObserver = it.next()
            mHandler.post(OnServiceConnected(this, observer, null))
        }
    }

    @Synchronized
    override fun onServiceDisconnected(name: ComponentName) {
        remoteToolkit = null
        val it: Iterator<ConnectionObserver> = mConnectionObservers.iterator()
        while (it.hasNext()) {
            val observer: ConnectionObserver = it.next()
            mHandler.post(OnServiceDisconnected(this, observer, null))
        }
        mConnecting = false
        connect(mContext, Random().nextInt(3000) + 1000L)
    }

    override fun onBindingDied(name: ComponentName) {
        onServiceDisconnected(name)
    }

    override fun onNullBinding(name: ComponentName) {
        onServiceDisconnected(name)
    }

    inner class OnServiceConnected private constructor(observer: ConnectionObserver) : Runnable {
        private val observer: ConnectionObserver?

        internal constructor(
            msToolkitConnection: MsToolkitConnection?,
            connectionObserver: ConnectionObserver,
            onServiceConnected: OnServiceConnected?
        ) : this(connectionObserver)

        override fun run() {
            val toolkit = remoteToolkit
            if (toolkit != null && observer != null) {
                observer.onConnected(toolkit)
            }
        }

        init {
            this.observer = observer
        }
    }

    private inner class OnServiceDisconnected private constructor(observer: ConnectionObserver?) : Runnable {
        private val observer: ConnectionObserver?

        internal constructor(
            msToolkitConnection: MsToolkitConnection?,
            connectionObserver: ConnectionObserver?,
            onServiceDisconnected: OnServiceDisconnected?
        ) : this(connectionObserver)

        override fun run() {
            observer?.onDisconnected()
        }

        init {
            this.observer = observer
        }
    }
}
