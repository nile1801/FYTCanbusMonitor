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
    private var mainSubscription: DynamicModuleSubscription? = null
    private var btSubscription: DynamicModuleSubscription? = null
    private var canSubscription: DynamicModuleSubscription? = null
    private var currentRules: List<CanRule> = emptyList()

    private val monitorMainIndexes = concatRanges(0..76, 78..200)
    private val monitorBtIndexes = concatRanges(0..100)
    private val monitorCanIndexes = concatRanges(0..200, 500..600, 1000..1200)

    @Volatile private var destroying = false

    private val statusObserver = object : ConnectionObserver {
        override fun onConnected(toolkit: IRemoteToolkit?) {
            RuntimeState.fytConnected = toolkit != null
            if (RuntimeState.audioReady) RuntimeState.lastError = null
            updateNotification()
        }

        override fun onDisconnected() {
            RuntimeState.fytConnected = false
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

        MonitorStore.configureFilter(
            SettingsStore.monitorFilterMode(this),
            SettingsStore.monitorFilterModule(this),
            SettingsStore.monitorFilterIndexes(this)
        )

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        audio = AudioEngine(this)
        ruleEngine = RuleEngine(SettingsStore.timeoutMillis(this).toLong()) { state ->
            RuntimeState.leftActive = state.left
            RuntimeState.rightActive = state.right
            RuntimeState.hazardActive = state.hazard
            RuntimeState.ruleActive = state.anyActive
            audio.setRuleActive(state.anyActive && SettingsStore.isEnabled(this))
            updateNotification()
        }
        currentRules = RuleStore.load(this)
        ruleEngine.setHoldMillis(SettingsStore.timeoutMillis(this).toLong())
        ruleEngine.setStopMode(SettingsStore.stopMode(this))
        ruleEngine.setRules(currentRules)

        if (RuntimeState.fytPackagePresent) {
            connectToFyt()
        } else {
            RuntimeState.fytConnected = false
            if (RuntimeState.audioReady) RuntimeState.lastError = null
            updateNotification()
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

        // Chốt desired indexes trước khi kết nối; onConnected chỉ apply đúng tập hiện tại.
        updateSubscriptions()

        observers.forEach { MsToolkitConnection.instance.addObserver(it) }
        try {
            MsToolkitConnection.instance.connect(applicationContext)
        } catch (t: Throwable) {
            if (RuntimeState.audioReady) {
                RuntimeState.lastError = "Kết nối FYT: ${t.javaClass.simpleName}: ${t.message}"
            }
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
            else -> refreshConfiguration()
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
        ruleEngine.setHoldMillis(SettingsStore.timeoutMillis(this).toLong())
        ruleEngine.setStopMode(SettingsStore.stopMode(this))
        ruleEngine.setRules(currentRules)
        audio.setVolume(SettingsStore.volume(this))
        updateSubscriptions()

        if (SettingsStore.isEnabled(this) && ruleEngine.active) {
            audio.setRuleActive(true)
        } else {
            audio.stopRule()
        }
        updateNotification()
    }

    /**
     * Background / tab khác / Monitor pause:
     *   chỉ subscribe index thật sự có trong enabled CANBUS/MAIN rules.
     *
     * Monitor foreground:
     *   subscribe rule indexes + phần monitor cần theo filter đã lưu.
     *   ALL     -> full monitor ranges
     *   ONLY    -> chỉ các index filter của đúng module
     *   EXCLUDE -> full ranges trừ index filter của đúng module
     *
     * Rule indexes luôn được union trở lại, nên filter log không bao giờ làm mất trigger âm thanh.
     */
    private fun updateSubscriptions() {
        val ruleCan = currentRules.asSequence()
            .filter { it.enabled && it.module == RuleModule.CANBUS }
            .map { it.index }
            .filter { it >= 0 }
            .toSet()
        val ruleMain = currentRules.asSequence()
            .filter { it.enabled && it.module == RuleModule.MAIN }
            .map { it.index }
            .filter { it >= 0 }
            .toSet()

        val monitorEnabled = MonitorCaptureState.enabled
        val mode = SettingsStore.monitorFilterMode(this)
        val filterModule = SettingsStore.monitorFilterModule(this)
        val filterIndexes = SettingsStore.monitorFilterIndexes(this)

        fun monitorIndexes(module: String, fullRange: IntArray): Set<Int> {
            if (!monitorEnabled) return emptySet()
            return when (mode) {
                MonitorFilterMode.ALL -> fullRange.toSet()
                MonitorFilterMode.ONLY_CAN_INDEX ->
                    if (module == filterModule) filterIndexes else emptySet()
                MonitorFilterMode.EXCLUDE_CAN_INDEX ->
                    if (module == filterModule) {
                        fullRange.asSequence().filter { it !in filterIndexes }.toSet()
                    } else {
                        fullRange.toSet()
                    }
            }
        }

        val mainWanted = (ruleMain + monitorIndexes(RuleModule.MAIN.name, monitorMainIndexes))
            .sorted()
            .toIntArray()
        val canWanted = (ruleCan + monitorIndexes(RuleModule.CANBUS.name, monitorCanIndexes))
            .sorted()
            .toIntArray()
        val btWanted = monitorIndexes("BT", monitorBtIndexes)
            .sorted()
            .toIntArray()

        mainSubscription?.setIndexes(mainWanted)
        canSubscription?.setIndexes(canWanted)
        btSubscription?.setIndexes(btWanted)
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

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val status = when {
            !RuntimeState.audioReady -> "Audio chưa sẵn sàng"
            !RuntimeState.fytPackagePresent -> "Chế độ thử điện thoại • audio đã sẵn sàng"
            !RuntimeState.fytConnected -> "Đang chờ dịch vụ FYT"
            RuntimeState.ruleActive && SettingsStore.isEnabled(this) -> "FYT rule đang active • âm xi nhan đang phát"
            else -> "Đã kết nối FYT • đang giám sát rule"
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
        const val ACTION_UPDATE_SUBSCRIPTIONS = "com.aoe.canbusmonitor.turnsound.UPDATE_SUBSCRIPTIONS"
        const val ACTION_TEST_SOUND = "com.aoe.canbusmonitor.turnsound.TEST"
        const val ACTION_STOP_TEST = "com.aoe.canbusmonitor.turnsound.STOP_TEST"
        const val ACTION_STOP_SERVICE = "com.aoe.canbusmonitor.turnsound.STOP_SERVICE"
        private const val CHANNEL_ID = "fyt_turn_sound"
        private const val NOTIFICATION_ID = 7007
    }
}
