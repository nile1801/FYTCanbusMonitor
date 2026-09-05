package com.aoe.canbusmonitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var monitorText: TextView
    private lateinit var latestEventText: TextView
    private lateinit var filterStatusText: TextView
    private lateinit var storageAccessStatusText: TextView
    private lateinit var rulesContainer: LinearLayout
    private lateinit var serviceStatusText: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var soundEnabledSwitch: Switch
    private lateinit var volumeSeek: SeekBar
    private lateinit var monitorScroll: ScrollView
    private var lastMonitorVersion = -1L
    private var monitorUiLineCount = 0
    private var monitorPaused = false
    private var selectedTabIndex = 0
    private var activityResumed = false
    private var storagePermissionDialogShown = false
    private var storageAccessWasGranted = false

    private val refresher = object : Runnable {
        override fun run() {
            if (!activityResumed) return
            when (selectedTabIndex) {
                0 -> if (!monitorPaused) {
                    refreshMonitor()
                    handler.postDelayed(this, 250L)
                }
                2 -> {
                    refreshStatus()
                    handler.postDelayed(this, 1000L)
                }
            }
        }
    }

    private fun scheduleRefresher() {
        handler.removeCallbacks(refresher)
        if (!activityResumed) return
        if ((selectedTabIndex == 0 && !monitorPaused) || selectedTabIndex == 2) {
            handler.post(refresher)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        storageAccessWasGranted = hasPersistentStorageAccess()

        MonitorStore.configureFilter(
            SettingsStore.monitorFilterMode(this),
            SettingsStore.monitorFilterModule(this),
            SettingsStore.monitorFilterIndexes(this)
        )

        // Restore rules + stopMode + timeout trước khi dựng UI nếu backup đã đọc được.
        RuleStore.load(this)

        setContentView(buildTabbedUi())
        renderRules()
        startTurnService()
        handler.post {
            if (!requestPersistentStoragePermissionIfNeeded()) {
                requestNotificationPermissionIfNeeded()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true

        val hasStorageAccess = hasPersistentStorageAccess()
        if (hasStorageAccess && !storageAccessWasGranted) {
            val restoredRules = RuleStore.load(this)
            sendServiceAction(TurnSignalService.ACTION_REFRESH)
            if (restoredRules.isNotEmpty()) {
                toast("Đã đọc lại ${restoredRules.size} rule + cài đặt tắt sau khi cấp quyền backup")
            }
            // Rebuild UI để RadioGroup/SeekBar phản ánh stopMode + timeout vừa restore.
            recreate()
            return
        }
        storageAccessWasGranted = hasStorageAccess
        refreshStorageAccessStatus()
        if (storagePermissionDialogShown) requestNotificationPermissionIfNeeded()

        MonitorCaptureState.enabled = selectedTabIndex == 0 && !monitorPaused
        sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
        scheduleRefresher()
        renderRules()
    }

    override fun onPause() {
        activityResumed = false
        MonitorCaptureState.enabled = false
        sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun buildTabbedUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val content = FrameLayout(this)

        val monitor = buildMonitorTab()
        val rules = buildRulesTab()
        val sound = buildSoundTab()
        val pages = listOf(monitor, rules, sound)

        pages.forEach { page ->
            content.addView(
                page,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        val buttons = arrayListOf<Button>()
        fun addTabButton(label: String, pageIndex: Int) {
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener {
                    selectedTabIndex = pageIndex
                    MonitorCaptureState.enabled = pageIndex == 0 && !monitorPaused
                    sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
                    pages.forEachIndexed { index, page ->
                        page.visibility = if (index == pageIndex) View.VISIBLE else View.GONE
                    }
                    buttons.forEachIndexed { index, tabButton ->
                        tabButton.isEnabled = index != pageIndex
                    }
                    if (pageIndex == 1) renderRules()
                    if (pageIndex == 0) {
                        lastMonitorVersion = -1L
                        monitorUiLineCount = 0
                    }
                    if (pageIndex == 2) refreshStatus()
                    scheduleRefresher()
                }
            }
            buttons += button
            tabBar.addView(button, weightParams())
        }

        addTabButton("Giám sát", 0)
        addTabButton("Luật CAN / MAIN", 1)
        addTabButton("Âm thanh & dịch vụ", 2)

        root.addView(
            tabBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        pages.forEachIndexed { index, page -> page.visibility = if (index == 0) View.VISIBLE else View.GONE }
        buttons.forEachIndexed { index, button -> button.isEnabled = index != 0 }
        selectedTabIndex = 0
        MonitorCaptureState.enabled = false
        return root
    }

    private fun buildMonitorTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val clear = Button(this).apply {
            text = "XÓA"
            setOnClickListener {
                MonitorStore.clear()
                lastMonitorVersion = -1L
                monitorUiLineCount = 0
                refreshMonitor()
            }
        }
        val pause = Button(this).apply {
            text = "TẠM DỪNG"
            setOnClickListener {
                monitorPaused = !monitorPaused
                text = if (monitorPaused) "TIẾP TỤC" else "TẠM DỪNG"
                MonitorCaptureState.enabled = selectedTabIndex == 0 && !monitorPaused
                sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
                if (!monitorPaused) {
                    lastMonitorVersion = -1L
                    monitorUiLineCount = 0
                }
                scheduleRefresher()
            }
        }
        val filter = Button(this).apply {
            text = "LỌC"
            setOnClickListener { showMonitorFilterDialog() }
        }
        val export = Button(this).apply {
            text = "XUẤT TXT"
            setOnClickListener { exportMonitorLog() }
        }
        controls.addView(clear, weightParams())
        controls.addView(pause, weightParams())
        controls.addView(filter, weightParams())
        controls.addView(export, weightParams())
        root.addView(controls)

        filterStatusText = TextView(this).apply {
            text = "Bộ lọc log: ${MonitorStore.filterSummary()} • foreground Monitor mới mở rộng subscription; background chỉ giữ index rule"
            setPadding(0, dp(5), 0, dp(2))
        }
        root.addView(filterStatusText)

        latestEventText = TextView(this).apply {
            text = "CANBUS/MAIN mới nhất: đang chờ..."
            setPadding(0, dp(6), 0, dp(4))
        }
        root.addView(latestEventText)

        root.addView(Button(this).apply {
            text = "DÙNG SỰ KIỆN CANBUS/MAIN MỚI NHẤT LÀM TRIGGER"
            setOnClickListener {
                val event = MonitorStore.latestRuleEvent
                if (event?.ints?.isEmpty() != false) {
                    toast("Chưa có sự kiện CANBUS/MAIN IntArray để dùng")
                } else {
                    showRuleDialog(null, event, RuleAction.START)
                }
            }
        })

        monitorScroll = ScrollView(this)
        monitorText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(4), dp(6), dp(4), dp(12))
            text = "Đang chờ dữ liệu FYT..."
        }
        monitorScroll.addView(
            monitorText,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(monitorScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun buildRulesTab(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Mỗi trigger có Nguồn (CANBUS/MAIN), Hành động (BẬT/TẮT) và Nhóm (TRÁI/PHẢI/HAZARD). Các nhóm có state riêng. Rule cũ chỉ có CANBUS vẫn restore được và mặc định field mới là CANBUS + BẬT + TRÁI."
            textSize = 15f
            setPadding(0, 0, 0, dp(8))
        })

        storageAccessStatusText = TextView(this).apply {
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(storageAccessStatusText)
        root.addView(Button(this).apply {
            text = "CẤP / KIỂM TRA QUYỀN BACKUP FILE"
            setOnClickListener { openAllFilesAccessSettings() }
        })
        root.addView(TextView(this).apply {
            text = "Config đang chạy: ${RuleBackupStore.DISPLAY_PATH}. Snapshot mặc định tự lưu: ${RuleBackupStore.DEFAULT_CONFIG_DISPLAY_PATH}. Muốn giữ qua uninstall/cài lại, Android 11+ cần bật quyền Quản lý tất cả tệp cho app."
            setPadding(0, dp(4), 0, dp(6))
        })

        val defaultRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        defaultRow.addView(Button(this).apply {
            text = "LOAD CONFIG MẶC ĐỊNH"
            setOnClickListener {
                val savedDefault = RuleBackupStore.readDefault(this@MainActivity)
                val sourceText = savedDefault ?: RuleStore.builtInDefaultConfig()
                val restored = RuleStore.applyExternalConfig(this@MainActivity, sourceText)
                if (restored == null) {
                    toast("Config mặc định lỗi, không thể load")
                    return@setOnClickListener
                }
                sendServiceAction(TurnSignalService.ACTION_REFRESH)
                toast(
                    if (savedDefault != null) {
                        "Đã load mặc định anh lưu gần nhất (${restored.size} rule)"
                    } else {
                        "Đã load bộ mặc định gốc (${restored.size} rule)"
                    }
                )
                recreate()
            }
        }, weightParams())
        defaultRow.addView(Button(this).apply {
            text = "LƯU CONFIG MẶC ĐỊNH"
            setOnClickListener {
                val snapshot = RuleStore.exportCurrentConfig(this@MainActivity)
                if (RuleBackupStore.writeDefault(this@MainActivity, snapshot)) {
                    toast("Đã lưu config hiện tại làm mặc định")
                } else {
                    toast("Không ghi được ${RuleBackupStore.DEFAULT_CONFIG_DISPLAY_PATH}")
                }
            }
        }, weightParams())
        root.addView(defaultRow)
        root.addView(TextView(this).apply {
            text = "LOAD sẽ override rules.json hiện tại. Nếu chưa từng bấm LƯU CONFIG MẶC ĐỊNH thì app dùng bộ mặc định gốc MG G50; sau khi đã lưu, các lần LOAD sau sẽ dùng snapshot mới nhất anh lưu."
            setPadding(0, dp(4), 0, dp(10))
        })
        refreshStorageAccessStatus()

        root.addView(TextView(this).apply {
            text = "CÁCH TẮT ÂM THANH"
            textSize = 16f
            setPadding(0, dp(4), 0, dp(4))
        })

        val stopModeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val timeoutId = View.generateViewId()
        val triggerId = View.generateViewId()
        stopModeGroup.addView(RadioButton(this).apply {
            id = timeoutId
            text = "Tắt sau thời gian timeout đã cấu hình khi không còn trigger BẬT match"
        })
        stopModeGroup.addView(RadioButton(this).apply {
            id = triggerId
            text = "Chỉ tắt khi trigger TẮT của đúng nhóm match"
        })
        stopModeGroup.check(if (SettingsStore.stopMode(this) == StopMode.TRIGGER) triggerId else timeoutId)
        root.addView(stopModeGroup)

        val timeoutLabel = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(timeoutLabel)

        val timeoutSeek = SeekBar(this).apply {
            max = SettingsStore.MAX_TIMEOUT_MS / SettingsStore.TIMEOUT_STEP_MS
            progress = SettingsStore.timeoutMillis(this@MainActivity) / SettingsStore.TIMEOUT_STEP_MS
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    timeoutLabel.text = "Timeout: ${progress * SettingsStore.TIMEOUT_STEP_MS} ms"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val value = (seekBar?.progress ?: 0) * SettingsStore.TIMEOUT_STEP_MS
                    SettingsStore.setTimeoutMillis(this@MainActivity, value)
                    RuleStore.syncBackup(this@MainActivity)
                    sendServiceAction(TurnSignalService.ACTION_REFRESH)
                }
            })
        }
        timeoutLabel.text = "Timeout: ${timeoutSeek.progress * SettingsStore.TIMEOUT_STEP_MS} ms"
        root.addView(timeoutSeek)
        root.addView(TextView(this).apply {
            text = "Khoảng chỉnh: 0–2000 ms, mỗi nấc 100 ms. Chỉ áp dụng khi chọn chế độ TIMEOUT."
            setPadding(0, 0, 0, dp(8))
        })

        stopModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == triggerId) StopMode.TRIGGER else StopMode.TIMEOUT
            SettingsStore.setStopMode(this, mode)
            RuleStore.syncBackup(this)
            sendServiceAction(TurnSignalService.ACTION_REFRESH)
        }

        root.addView(TextView(this).apply {
            text = "Ở chế độ trigger TẮT, app không tự timeout. Ví dụ TRÁI đã BẬT thì chỉ trigger TẮT map vào TRÁI mới tắt. PHẢI/HAZARD không tắt chéo nhau."
            setPadding(0, dp(4), 0, dp(10))
        })

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addRow.addView(Button(this).apply {
            text = "+ TRIGGER BẬT"
            setOnClickListener { showRuleDialog(null, null, RuleAction.START) }
        }, weightParams())
        addRow.addView(Button(this).apply {
            text = "+ TRIGGER TẮT"
            setOnClickListener { showRuleDialog(null, null, RuleAction.STOP) }
        }, weightParams())
        root.addView(addRow)

        rulesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(rulesContainer)
        scroll.addView(root)
        return scroll
    }

    private fun buildSoundTab(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(20))
        }
        serviceStatusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(serviceStatusText)

        soundEnabledSwitch = Switch(this).apply {
            text = "Bật âm thanh xi nhan"
            isChecked = SettingsStore.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                SettingsStore.setEnabled(this@MainActivity, checked)
                sendServiceAction(TurnSignalService.ACTION_REFRESH)
            }
        }
        root.addView(soundEnabledSwitch)

        volumeLabel = TextView(this).apply { setPadding(0, dp(14), 0, 0) }
        root.addView(volumeLabel)
        volumeSeek = SeekBar(this).apply {
            max = 100
            progress = (SettingsStore.volume(this@MainActivity) * 100f).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    volumeLabel.text = "Âm lượng xi nhan: $progress%"
                    if (fromUser) SettingsStore.setVolume(this@MainActivity, progress / 100f)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    sendServiceAction(TurnSignalService.ACTION_REFRESH)
                }
            })
        }
        volumeLabel.text = "Âm lượng xi nhan: ${volumeSeek.progress}%"
        root.addView(volumeSeek)

        root.addView(Button(this).apply {
            text = "ÁP DỤNG / LƯU CÀI ĐẶT"
            setOnClickListener { applySoundSettings(true) }
        })

        val testRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        testRow.addView(Button(this).apply {
            text = "THỬ ÂM THANH"
            setOnClickListener {
                applySoundSettings(false)
                sendServiceAction(TurnSignalService.ACTION_TEST_SOUND)
            }
        }, weightParams())
        testRow.addView(Button(this).apply {
            text = "DỪNG THỬ"
            setOnClickListener { sendServiceAction(TurnSignalService.ACTION_STOP_TEST) }
        }, weightParams())
        root.addView(testRow)

        root.addView(TextView(this).apply {
            text = "Trạng thái bật/tắt và âm lượng được lưu lại. Đóng app, mở lại hoặc khởi động lại đầu DUDU không cần cấu hình lại."
            setPadding(0, dp(10), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "only_tik_tok.mp3 được giải mã một lần khi service khởi động thành PCM và giữ trong RAM. AudioTrack MODE_STREAM cùng worker ưu tiên audio phát vòng trực tiếp từ PCM, nên khi rule match không decode MP3 và không tạo player mới."
            setPadding(0, dp(8), 0, dp(10))
        })
        root.addView(TextView(this).apply {
            text = "TỰ KHỞI ĐỘNG\n\nKhởi động lại Android hoàn toàn: app tự xử lý bằng BOOT_COMPLETED + foreground service.\n\nDUDUOS 3.7 sleep/wake: tạo một Automatic Task:\nVehicle ignition → Open app → FYT Turn Sound - Chạy nền"
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
        })
        root.addView(Button(this).apply {
            text = "KHỞI ĐỘNG / LÀM MỚI DỊCH VỤ"
            setOnClickListener {
                startTurnService()
                sendServiceAction(TurnSignalService.ACTION_REFRESH)
            }
        })
        scroll.addView(root)
        return scroll
    }

    private fun applySoundSettings(showToast: Boolean) {
        SettingsStore.setEnabled(this, soundEnabledSwitch.isChecked)
        SettingsStore.setVolume(this, volumeSeek.progress / 100f)
        sendServiceAction(TurnSignalService.ACTION_REFRESH)
        if (showToast) toast("Đã lưu cài đặt âm thanh")
    }

    private fun showMonitorFilterDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        root.addView(TextView(this).apply { text = "Nguồn cần lọc" })
        val moduleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(RuleModule.CANBUS.name, RuleModule.MAIN.name)
            )
            setSelection(if (SettingsStore.monitorFilterModule(this@MainActivity) == RuleModule.MAIN.name) 1 else 0)
        }
        root.addView(moduleSpinner)

        root.addView(TextView(this).apply { text = "Các index cần lọc" })
        val indexEdit = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = "Ví dụ: 1019, 1049"
            setText(SettingsStore.monitorFilterIndexes(this@MainActivity).sorted().joinToString(", "))
            selectAll()
        }
        root.addView(indexEdit)
        root.addView(TextView(this).apply {
            text = "Có thể nhập nhiều index, ngăn cách bằng dấu phẩy. Ví dụ: 1019, 1049. Cách nhập này dùng cho cả Chỉ hiện và Loại trừ. Filter chỉ tác động monitor/log, không thay đổi rule phát âm thanh."
            setPadding(0, dp(8), 0, dp(4))
        })

        var selected = when (SettingsStore.monitorFilterMode(this)) {
            MonitorFilterMode.ALL -> 0
            MonitorFilterMode.ONLY_CAN_INDEX -> 1
            MonitorFilterMode.EXCLUDE_CAN_INDEX -> 2
        }
        val choices = arrayOf(
            "Tất cả log",
            "Chỉ hiện các index đã nhập của nguồn này",
            "Loại trừ các index đã nhập của nguồn này"
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("Bộ lọc log FYT")
            .setView(root)
            .setSingleChoiceItems(choices, selected) { _, which -> selected = which }
            .setNegativeButton("HỦY", null)
            .setPositiveButton("ÁP DỤNG", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsedIndexes = parseIndexList(indexEdit.text.toString())
                if (selected != 0 && parsedIndexes == null) {
                    toast("Nhập index từ 0 trở lên, nhiều index ngăn cách bằng dấu phẩy")
                    return@setOnClickListener
                }
                val mode = when (selected) {
                    1 -> MonitorFilterMode.ONLY_CAN_INDEX
                    2 -> MonitorFilterMode.EXCLUDE_CAN_INDEX
                    else -> MonitorFilterMode.ALL
                }
                val module = moduleSpinner.selectedItem.toString()
                val safeIndexes = parsedIndexes ?: SettingsStore.monitorFilterIndexes(this)
                SettingsStore.setMonitorFilter(this, mode, module, safeIndexes)
                MonitorStore.configureFilter(mode, module, safeIndexes)
                filterStatusText.text = "Bộ lọc log: ${MonitorStore.filterSummary()} • foreground Monitor mới mở rộng subscription; background chỉ giữ index rule"
                lastMonitorVersion = -1L
                monitorUiLineCount = 0
                refreshMonitor()
                sendServiceAction(TurnSignalService.ACTION_UPDATE_SUBSCRIPTIONS)
                toast("Đã áp dụng: ${MonitorStore.filterSummary()}")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun parseIndexList(text: String): Set<Int>? {
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val out = linkedSetOf<Int>()
        for (part in parts) {
            val value = part.toIntOrNull() ?: return null
            if (value < 0) return null
            out += value
        }
        return out
    }

    private fun refreshMonitor() {
        if (!activityResumed || selectedTabIndex != 0 || monitorPaused) return
        if (MonitorStore.version == lastMonitorVersion) return

        latestEventText.text = MonitorStore.latestRuleLine?.let {
            "CANBUS/MAIN mới nhất: $it"
        } ?: "CANBUS/MAIN mới nhất: đang chờ..."

        fun applyFullSnapshot() {
            val snapshot = MonitorStore.takeUiSnapshot()
            monitorText.text = if (snapshot.text.isBlank()) "Đang chờ dữ liệu FYT..." else snapshot.text
            monitorUiLineCount = snapshot.lineCount
            lastMonitorVersion = snapshot.version
        }

        if (lastMonitorVersion < 0L) {
            applyFullSnapshot()
        } else {
            val batch = MonitorStore.drainUiBatch()
            if (batch.requiresReset) {
                applyFullSnapshot()
            } else {
                if (batch.lines.isNotEmpty()) {
                    val addition = batch.lines.joinToString("\n")
                    if (monitorUiLineCount == 0) {
                        monitorText.text = addition
                    } else {
                        monitorText.append("\n")
                        monitorText.append(addition)
                    }
                    monitorUiLineCount += batch.lines.size
                }
                lastMonitorVersion = batch.version

                // Store vẫn giữ 2500 dòng. UI được phép append tới 3000 rồi rebuild một lần,
                // thay vì rebuild toàn bộ sau mỗi event khi đã chạm giới hạn.
                if (monitorUiLineCount > 3000) applyFullSnapshot()
            }
        }
        monitorScroll.post { monitorScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshStatus() {
        val error = RuntimeState.lastError?.let { "\nLỗi gần nhất: $it" } ?: ""
        val state = RuleStateSnapshot(
            RuntimeState.leftActive,
            RuntimeState.rightActive,
            RuntimeState.hazardActive
        )
        val stopText = if (SettingsStore.stopMode(this) == StopMode.TRIGGER) {
            "TRIGGER TẮT"
        } else {
            "TIMEOUT ${SettingsStore.timeoutMillis(this)} ms"
        }
        serviceStatusText.text = buildString {
            append("Dịch vụ: ").append(if (RuntimeState.serviceRunning) "ĐANG CHẠY" else "ĐÃ DỪNG")
            append("\nGói FYT: ").append(if (RuntimeState.fytPackagePresent) "CÓ SẴN" else "KHÔNG CÓ - chế độ thử điện thoại")
            append("\nFYT com.syu.ms: ").append(if (RuntimeState.fytConnected) "ĐÃ KẾT NỐI" else "ĐANG CHỜ")
            append("\nMẫu âm thanh: ").append(if (RuntimeState.audioReady) "ĐÃ NẠP RAM" else "ĐANG NẠP")
            append("\nNhóm đang active: ").append(state.summary())
            append("\nCách tắt: ").append(stopText)
            append(error)
        }
    }

    private fun renderRules() {
        if (!::rulesContainer.isInitialized) return
        val rules = RuleStore.load(this)
        RuleStore.consumeStatusMessage()?.let { toast(it) }
        rulesContainer.removeAllViews()
        if (rules.isEmpty()) {
            rulesContainer.addView(TextView(this).apply {
                text = "Chưa có trigger. Mở tab Giám sát, thao tác xi nhan/hazard rồi dùng sự kiện CANBUS/MAIN mới nhất để tạo trigger."
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        rules.forEach { rule ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(0x11000000)
            }
            val enabled = Switch(this).apply {
                text = rule.summary()
                isChecked = rule.enabled
                setOnCheckedChangeListener { _, checked ->
                    val current = RuleStore.load(this@MainActivity)
                    current.find { it.id == rule.id }?.enabled = checked
                    RuleStore.save(this@MainActivity, current)
                    sendServiceAction(TurnSignalService.ACTION_REFRESH)
                }
            }
            card.addView(enabled)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(this).apply {
                text = "SỬA"
                setOnClickListener { showRuleDialog(rule, null, rule.action) }
            }, weightParams())
            row.addView(Button(this).apply {
                text = "XÓA"
                setOnClickListener {
                    val current = RuleStore.load(this@MainActivity).filterNot { it.id == rule.id }
                    RuleStore.save(this@MainActivity, current)
                    renderRules()
                    sendServiceAction(TurnSignalService.ACTION_REFRESH)
                }
            }, weightParams())
            card.addView(row)
            rulesContainer.addView(
                card,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            )
        }
    }

    private fun showRuleDialog(existing: CanRule?, event: FytEvent?, defaultAction: RuleAction) {
        val eventModule = RuleModule.values().firstOrNull { it.name == event?.module } ?: RuleModule.CANBUS
        val first = event?.ints?.getOrNull(0) ?: 0
        val base = existing?.copy() ?: CanRule(
            module = eventModule,
            action = defaultAction,
            target = SignalTarget.LEFT,
            index = event?.index ?: 0,
            position = 0,
            expectedValue = if (first < 0) first and 0xFF else first,
            unsignedByte = first < 0
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        fun addSpinner(label: String, values: List<String>, selected: Int): Spinner {
            root.addView(TextView(this).apply { text = label })
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    values
                )
                setSelection(selected.coerceIn(0, values.lastIndex))
            }
            root.addView(spinner)
            return spinner
        }

        fun numberField(label: String, value: Int): EditText {
            root.addView(TextView(this).apply { text = label })
            val edit = EditText(this).apply {
                hint = label
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(value.toString())
                selectAll()
            }
            root.addView(edit)
            return edit
        }

        val moduleValues = RuleModule.values()
        val actionValues = RuleAction.values()
        val targetValues = SignalTarget.values()
        val moduleSpinner = addSpinner("Nguồn", moduleValues.map { it.name }, moduleValues.indexOf(base.module))
        val actionSpinner = addSpinner("Hành động", actionValues.map { it.label() }, actionValues.indexOf(base.action))

        val singleTargetLabel = TextView(this).apply { text = "Map vào" }
        root.addView(singleTargetLabel)
        val targetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                targetValues.map { it.label() }
            )
            setSelection(targetValues.indexOf(base.target).coerceAtLeast(0))
        }
        root.addView(targetSpinner)

        val stopTargetsLabel = TextView(this).apply {
            text = "Map trigger TẮT vào (có thể chọn nhiều)"
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(stopTargetsLabel)
        val stopTargetsBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val stopTargetChecks = targetValues.map { target ->
            CheckBox(this).apply {
                text = target.label()
                isChecked = base.action == RuleAction.STOP && base.target == target
            }.also { stopTargetsBox.addView(it, weightParams()) }
        }
        if (base.action == RuleAction.STOP && stopTargetChecks.none { it.isChecked }) {
            stopTargetChecks[SignalTarget.LEFT.ordinal].isChecked = true
        }
        root.addView(stopTargetsBox)

        fun refreshTargetControls() {
            val isStop = actionValues[actionSpinner.selectedItemPosition] == RuleAction.STOP
            singleTargetLabel.visibility = if (isStop) View.GONE else View.VISIBLE
            targetSpinner.visibility = if (isStop) View.GONE else View.VISIBLE
            stopTargetsLabel.visibility = if (isStop) View.VISIBLE else View.GONE
            stopTargetsBox.visibility = if (isStop) View.VISIBLE else View.GONE
        }
        actionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshTargetControls()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        refreshTargetControls()

        val indexEdit = numberField("Index", base.index)
        val positionEdit = numberField("Vị trí trong IntArray", base.position)
        val valueEdit = numberField("Giá trị cần khớp", base.expectedValue)
        val unsigned = CheckBox(this).apply {
            text = "So sánh dạng byte không dấu (giá trị thực & 0xFF)"
            isChecked = base.unsignedByte
        }
        root.addView(unsigned)

        event?.let {
            root.addView(TextView(this).apply {
                text = "Dữ liệu bắt được: ${it.originalStyleLine()}"
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(8), 0, 0)
            })
        }

        val dialogScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        dialogScroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Thêm trigger FYT" else "Sửa trigger FYT")
            .setView(dialogScroll)
            .setNegativeButton("HỦY", null)
            .setPositiveButton("LƯU", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val idx = indexEdit.text.toString().toIntOrNull()
                val pos = positionEdit.text.toString().toIntOrNull()
                val value = valueEdit.text.toString().toIntOrNull()
                if (idx == null || pos == null || value == null || idx < 0 || pos < 0) {
                    toast("Index, vị trí và giá trị phải là số hợp lệ")
                    return@setOnClickListener
                }

                val selectedAction = actionValues[actionSpinner.selectedItemPosition]
                val selectedTargets = if (selectedAction == RuleAction.STOP) {
                    targetValues.filterIndexed { index, _ -> stopTargetChecks[index].isChecked }
                } else {
                    listOf(targetValues[targetSpinner.selectedItemPosition])
                }
                if (selectedTargets.isEmpty()) {
                    toast("Trigger TẮT phải map vào ít nhất một nhóm")
                    return@setOnClickListener
                }

                val rules = RuleStore.load(this)
                // Khi sửa, bỏ rule gốc rồi tạo lại. STOP chọn nhiều target sẽ sinh nhiều rule
                // giống hệt nhau, chỉ khác target/map như yêu cầu.
                rules.removeAll { it.id == base.id }
                selectedTargets.forEachIndexed { targetIndex, target ->
                    val ruleId = if (targetIndex == 0) {
                        base.id
                    } else {
                        java.util.UUID.randomUUID().toString()
                    }
                    rules.add(
                        base.copy(
                            id = ruleId,
                            module = moduleValues[moduleSpinner.selectedItemPosition],
                            action = selectedAction,
                            target = target,
                            index = idx,
                            position = pos,
                            expectedValue = value,
                            unsignedByte = unsigned.isChecked
                        )
                    )
                }
                RuleStore.save(this, rules)
                dialog.dismiss()
                renderRules()
                sendServiceAction(TurnSignalService.ACTION_REFRESH)
            }
        }
        dialog.show()
    }

    private fun exportMonitorLog() {
        val log = MonitorStore.snapshot()
        if (log.isBlank()) {
            toast("Log giám sát đang trống")
            return
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "FYT-${timestampForFile()}.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore trả về null")
            contentResolver.openOutputStream(uri, "w")!!.use { out ->
                out.write(log.toByteArray(Charsets.UTF_8))
            }
            toast("Đã lưu vào thư mục Download")
        } catch (t: Throwable) {
            toast("Xuất log thất bại: ${t.message}")
        }
    }

    private fun startTurnService() {
        try {
            startForegroundService(Intent(this, TurnSignalService::class.java))
        } catch (t: Throwable) {
            RuntimeState.lastError = "Khởi động dịch vụ: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun sendServiceAction(action: String) {
        try {
            startForegroundService(Intent(this, TurnSignalService::class.java).apply { this.action = action })
        } catch (t: Throwable) {
            RuntimeState.lastError = "Lệnh dịch vụ: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun hasPersistentStorageAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    /**
     * Android 11+ dùng special app access, không có runtime Allow/Deny dialog kiểu permission thường.
     * Lần cài mới sẽ giải thích rồi đưa user tới đúng trang bật Allow access to manage all files.
     */
    private fun requestPersistentStoragePermissionIfNeeded(): Boolean {
        if (hasPersistentStorageAccess() || storagePermissionDialogShown) return false
        storagePermissionDialogShown = true
        AlertDialog.Builder(this)
            .setTitle("Cấp quyền lưu/restore rule")
            .setMessage(
                "Để rules.json vẫn đọc được sau khi uninstall rồi cài lại, app cần quyền Quản lý tất cả tệp. " +
                    "Android sẽ mở trang Special app access; hãy bật Allow access to manage all files cho FYT Turn Sound."
            )
            .setNegativeButton("ĐỂ SAU") { _, _ -> requestNotificationPermissionIfNeeded() }
            .setPositiveButton("CẤP QUYỀN") { _, _ -> openAllFilesAccessSettings() }
            .show()
        return true
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val packageUri = Uri.parse("package:$packageName")
        val intents = arrayOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Throwable) {
            }
        }
        toast("Không mở được màn cấp quyền lưu trữ trên ROM này")
    }

    private fun refreshStorageAccessStatus() {
        if (!::storageAccessStatusText.isInitialized) return
        storageAccessStatusText.text = if (hasPersistentStorageAccess()) {
            "Quyền backup file: ĐÃ CẤP • có thể đọc/ghi trực tiếp ${RuleBackupStore.DISPLAY_PATH}"
        } else {
            "Quyền backup file: CHƯA CẤP • rule vẫn lưu trong app nhưng có thể không restore sau uninstall"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("turn_sound_ui", MODE_PRIVATE)
        if (prefs.getBoolean("notification_permission_requested", false)) return
        prefs.edit().putBoolean("notification_permission_requested", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }

    private fun weightParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
