from pathlib import Path


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"marker not found: {label}")
    return text.replace(old, new, 1)


# 1) FytBridge: dynamic subscriptions with async diff register/unregister.
path = Path("example/src/main/java/com/aoe/canbusmonitor/FytBridge.kt")
text = path.read_text(encoding="utf-8")
old = '''class ModuleSubscription(
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
'''
new = '''private object SubscriptionDispatcher {
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
'''
text = replace_once(text, old, new, "ModuleSubscription")
path.write_text(text, encoding="utf-8")


# 2) TurnSignalService: compute rule-only vs foreground-monitor subscriptions.
path = Path("example/src/main/java/com/aoe/canbusmonitor/TurnSignalService.kt")
text = path.read_text(encoding="utf-8")

old = '''    private lateinit var audio: AudioEngine
    private lateinit var ruleEngine: RuleEngine
    private val observers = arrayListOf<ConnectionObserver>()
    @Volatile private var destroying = false
'''
new = '''    private lateinit var audio: AudioEngine
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
'''
text = replace_once(text, old, new, "service fields")

old = '''        ruleEngine.setStopMode(SettingsStore.stopMode(this))
        ruleEngine.setRules(RuleStore.load(this))

        if (RuntimeState.fytPackagePresent) {
'''
new = '''        ruleEngine.setStopMode(SettingsStore.stopMode(this))
        currentRules = RuleStore.load(this)
        ruleEngine.setRules(currentRules)

        if (RuntimeState.fytPackagePresent) {
'''
text = replace_once(text, old, new, "initial rules")

old = '''        observers += statusObserver
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
'''
new = '''        mainSubscription = DynamicModuleSubscription(MODULE_CODE_MAIN, mainCallback)
        btSubscription = DynamicModuleSubscription(MODULE_CODE_BT, btCallback)
        canSubscription = DynamicModuleSubscription(MODULE_CODE_CANBUS, canCallback)

        observers += statusObserver
        observers += mainSubscription!!
        observers += btSubscription!!
        observers += canSubscription!!

        // Chốt desired indexes trước khi kết nối; onConnected chỉ apply đúng tập hiện tại.
        updateSubscriptions()

        observers.forEach { MsToolkitConnection.instance.addObserver(it) }
'''
text = replace_once(text, old, new, "connect subscriptions")

old = '''        when (intent?.action) {
            ACTION_REFRESH -> refreshConfiguration()
            ACTION_TEST_SOUND -> {
'''
new = '''        when (intent?.action) {
            ACTION_REFRESH -> refreshConfiguration()
            ACTION_UPDATE_SUBSCRIPTIONS -> updateSubscriptions()
            ACTION_TEST_SOUND -> {
'''
text = replace_once(text, old, new, "service action")

old = '''        ruleEngine.setHoldMillis(SettingsStore.timeoutMillis(this).toLong())
        ruleEngine.setStopMode(SettingsStore.stopMode(this))
        ruleEngine.setRules(RuleStore.load(this))
        audio.setVolume(SettingsStore.volume(this))

        if (SettingsStore.isEnabled(this) && ruleEngine.active) {
'''
new = '''        ruleEngine.setHoldMillis(SettingsStore.timeoutMillis(this).toLong())
        ruleEngine.setStopMode(SettingsStore.stopMode(this))
        currentRules = RuleStore.load(this)
        ruleEngine.setRules(currentRules)
        audio.setVolume(SettingsStore.volume(this))
        updateSubscriptions()

        if (SettingsStore.isEnabled(this) && ruleEngine.active) {
'''
text = replace_once(text, old, new, "refresh config")

marker = '''    override fun onDestroy() {
'''
insert = '''    /**
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

'''
if marker not in text:
    raise SystemExit("marker not found: onDestroy")
text = text.replace(marker, insert + marker, 1)

old = '''        const val ACTION_REFRESH = "com.aoe.canbusmonitor.turnsound.REFRESH"
        const val ACTION_TEST_SOUND = "com.aoe.canbusmonitor.turnsound.TEST"
'''
new = '''        const val ACTION_REFRESH = "com.aoe.canbusmonitor.turnsound.REFRESH"
        const val ACTION_UPDATE_SUBSCRIPTIONS = "com.aoe.canbusmonitor.turnsound.UPDATE_SUBSCRIPTIONS"
        const val ACTION_TEST_SOUND = "com.aoe.canbusmonitor.turnsound.TEST"
'''
text = replace_once(text, old, new, "companion action")
path.write_text(text, encoding="utf-8")


# 3) MainActivity: tell service whenever foreground/tab/pause/filter state changes.
path = Path("example/src/main/java/com/aoe/canbusmonitor/MainActivity.kt")
text = path.read_text(encoding="utf-8")

old = '''        MonitorCaptureState.enabled = selectedTabIndex == 0 && !monitorPaused
        handler.removeCallbacks(refresher)
'''
new = '''        MonitorCaptureState.enabled = selectedTabIndex == 0 && !monitorPaused
        sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
        handler.removeCallbacks(refresher)
'''
text = replace_once(text, old, new, "onResume subscription update")

old = '''    override fun onPause() {
        MonitorCaptureState.enabled = false
        handler.removeCallbacks(refresher)
        super.onPause()
    }
'''
new = '''    override fun onPause() {
        MonitorCaptureState.enabled = false
        sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
        handler.removeCallbacks(refresher)
        super.onPause()
    }
'''
text = replace_once(text, old, new, "onPause subscription update")

old = '''                    selectedTabIndex = pageIndex
                    MonitorCaptureState.enabled = pageIndex == 0 && !monitorPaused
                    pages.forEachIndexed { index, page ->
'''
new = '''                    selectedTabIndex = pageIndex
                    MonitorCaptureState.enabled = pageIndex == 0 && !monitorPaused
                    sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
                    pages.forEachIndexed { index, page ->
'''
text = replace_once(text, old, new, "tab switch subscription update")

old = '''                monitorPaused = !monitorPaused
                text = if (monitorPaused) "TIẾP TỤC" else "TẠM DỪNG"
                MonitorCaptureState.enabled = selectedTabIndex == 0 && !monitorPaused
                if (!monitorPaused) lastMonitorVersion = -1L
'''
new = '''                monitorPaused = !monitorPaused
                text = if (monitorPaused) "TIẾP TỤC" else "TẠM DỪNG"
                MonitorCaptureState.enabled = selectedTabIndex == 0 && !monitorPaused
                sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
                if (!monitorPaused) lastMonitorVersion = -1L
'''
text = replace_once(text, old, new, "monitor pause subscription update")

old = '''            text = "Bộ lọc log: ${MonitorStore.filterSummary()} • monitor chỉ capture khi tab Giám sát đang mở"
'''
new = '''            text = "Bộ lọc log: ${MonitorStore.filterSummary()} • foreground Monitor mới mở rộng subscription; background chỉ giữ index rule"
'''
text = replace_once(text, old, new, "initial filter status")

old = '''                filterStatusText.text = "Bộ lọc log: ${MonitorStore.filterSummary()} • monitor chỉ capture khi tab Giám sát đang mở"
                lastMonitorVersion = -1L
                refreshMonitor()
                sendServiceAction(TurnSignalService.ACTION_REFRESH)
'''
new = '''                filterStatusText.text = "Bộ lọc log: ${MonitorStore.filterSummary()} • foreground Monitor mới mở rộng subscription; background chỉ giữ index rule"
                lastMonitorVersion = -1L
                refreshMonitor()
                sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
'''
text = replace_once(text, old, new, "filter apply subscription update")

path.write_text(text, encoding="utf-8")


# 4) Version bump.
path = Path("example/build.gradle")
text = path.read_text(encoding="utf-8")
text = replace_once(text, 'versionCode 11', 'versionCode 12', "versionCode")
text = replace_once(
    text,
    'versionName "1.6.1-persistent-storage-permission"',
    'versionName "1.6.2-dynamic-rule-subscriptions"',
    "versionName"
)
path.write_text(text, encoding="utf-8")
