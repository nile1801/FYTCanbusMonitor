package com.aoe.canbusmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
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
    @Volatile private var destroying = false

    private val statusObserver = object : ConnectionObserver {
        override fun onConnected(toolkit: IRemoteToolkit?) {
            RuntimeState.fytConnected = toolkit != null
            RuntimeState.lastError = null
            updateNotification()
        }

        override fun onDisconnected() {
            RuntimeState.fytConnected = false
            RuntimeState.ruleActive = false
            if (!destroying) {
                ruleEngine.clearState()
                audio.stopRule()
                updateNotification()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeState.serviceRunning = true
        RuntimeState.fytPackagePresent = isFytPackageAvailable()

        // Filter log được lưu persistent. Nó chỉ tác động MonitorStore, không tác động RuleEngine.
        MonitorStore.configureFilter(
            SettingsStore.monitorFilterMode(this),
            SettingsStore.monitorFilterIndex(this)
        )

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Khởi tạo AudioEngine ngay khi service chạy để SoundPool giải mã MP3 và giữ mẫu âm thanh trong RAM.
        audio = AudioEngine(this)
        ruleEngine = RuleEngine { active ->
            RuntimeState.ruleActive = active
            val shouldPlay = active && SettingsStore.isEnabled(this)
            audio.setRuleActive(shouldPlay)
            updateNotification()
        }
        ruleEngine.setRules(RuleStore.load(this))

        if (RuntimeState.fytPackagePresent) {
            connectToFyt()
        } else {
            // Chế độ thử trên điện thoại/tablet Android thường: UI và âm thanh vẫn chạy,
            // nhưng không đụng vào service FYT riêng của đầu xe là com.syu.ms.
            RuntimeState.fytConnected = false
            RuntimeState.lastError = null
            updateNotification()
        }
    }

    private fun connectToFyt() {
        val mainCallback = FytModuleCallback("MAIN") { }
        val btCallback = FytModuleCallback("BT") { }
        val canCallback = FytModuleCallback("CANBUS") { event -> ruleEngine.onCanEvent(event) }

        observers += statusObserver
        observers += ModuleSubscription(
            MODULE_CODE_MAIN,
            mainCallback,
            concatRanges(0..76, 78..200)
        )
        observers += ModuleSubscription(
            MODULE_CODE_BT,
            btCallback,
            concatRanges(0..100)
        )
        observers += ModuleSubscription(
            MODULE_CODE_CANBUS,
            canCallback,
            concatRanges(0..200, 500..600, 1000..1200)
        )

        observers.forEach { MsToolkitConnection.instance.addObserver(it) }
        try {
            MsToolkitConnection.instance.connect(applicationContext)
        } catch (t: Throwable) {
            RuntimeState.lastError = "Kết nối FYT: ${t.javaClass.simpleName}: ${t.message}"
            updateNotification()
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
            ACTION_TEST_SOUND -> {
                audio.setVolume(SettingsStore.volume(this))
                audio.startTest()
            }
            ACTION_STOP_TEST -> audio.stopTest()
            ACTION_STOP_SERVICE -> {
                audio.stopTest()
                audio.stopRule()
                stopSelf()
            }
            else -> refreshConfiguration()
        }
        return START_STICKY
    }

    private fun refreshConfiguration() {
        MonitorStore.configureFilter(
            SettingsStore.monitorFilterMode(this),
            SettingsStore.monitorFilterIndex(this)
        )
        ruleEngine.setRules(RuleStore.load(this))
        audio.setVolume(SettingsStore.volume(this))
        if (SettingsStore.isEnabled(this) && ruleEngine.active) {
            audio.setRuleActive(true)
        } else {
            audio.stopRule()
        }
        updateNotification()
    }

    override fun onDestroy() {
        destroying = true
        observers.forEach { MsToolkitConnection.instance.removeObserver(it) }
        observers.clear()
        if (::ruleEngine.isInitialized) ruleEngine.release()
        if (::audio.isInitialized) audio.release()
        RuntimeState.serviceRunning = false
        RuntimeState.fytConnected = false
        RuntimeState.ruleActive = false
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
            description = "Giữ dịch vụ giám sát FYT CAN chạy nền"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val status = when {
            !RuntimeState.fytPackagePresent -> "Chế độ thử điện thoại • không có dịch vụ FYT"
            !RuntimeState.fytConnected -> "Đang chờ dịch vụ FYT CAN"
            RuntimeState.ruleActive && SettingsStore.isEnabled(this) -> "Đã kết nối CAN • âm xi nhan đang phát"
            else -> "Đã kết nối CAN • đang giám sát"
        }
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

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.aoe.canbusmonitor.turnsound.REFRESH"
        const val ACTION_TEST_SOUND = "com.aoe.canbusmonitor.turnsound.TEST"
        const val ACTION_STOP_TEST = "com.aoe.canbusmonitor.turnsound.STOP_TEST"
        const val ACTION_STOP_SERVICE = "com.aoe.canbusmonitor.turnsound.STOP_SERVICE"
        private const val CHANNEL_ID = "fyt_turn_sound"
        private const val NOTIFICATION_ID = 7007
    }
}
