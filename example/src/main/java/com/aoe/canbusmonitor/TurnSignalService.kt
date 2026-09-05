package com.aoe.canbusmonitor

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
    @Volatile private var soundEnabled = true
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
