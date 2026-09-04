package com.aoe.canbusmonitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var monitorText: TextView
    private lateinit var latestCanText: TextView
    private lateinit var rulesContainer: LinearLayout
    private lateinit var serviceStatusText: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var soundEnabledSwitch: Switch
    private lateinit var volumeSeek: SeekBar
    private lateinit var monitorScroll: ScrollView
    private var lastMonitorVersion = -1L
    private var monitorPaused = false

    private val refresher = object : Runnable {
        override fun run() {
            refreshMonitor()
            refreshStatus()
            handler.postDelayed(this, 400L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dựng giao diện trước khi khởi động service hoặc hiện hộp thoại quyền thông báo.
        // Nhờ vậy app vẫn dùng được trên điện thoại Android thường để thử giao diện và âm thanh.
        setContentView(buildTabbedUi())
        renderRules()
        startTurnService()

        // Chỉ hỏi quyền sau khi giao diện đã được tạo, tránh lặp hộp thoại nếu Activity bị tạo lại.
        handler.post { requestNotificationPermissionIfNeeded() }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresher)
        handler.post(refresher)
        renderRules()
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    /**
     * Thanh tab nhẹ dùng Button + FrameLayout để tránh phụ thuộc vào TabHost cũ.
     */
    private fun buildTabbedUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

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
                    pages.forEachIndexed { index, page ->
                        page.visibility = if (index == pageIndex) View.VISIBLE else View.GONE
                    }
                    buttons.forEachIndexed { index, tabButton ->
                        // Nút bị vô hiệu hóa chính là tab đang được chọn.
                        tabButton.isEnabled = index != pageIndex
                    }
                    if (pageIndex == 1) renderRules()
                    if (pageIndex == 0) {
                        lastMonitorVersion = -1L
                        refreshMonitor()
                    }
                }
            }
            buttons += button
            tabBar.addView(button, weightParams())
        }

        addTabButton("Giám sát", 0)
        addTabButton("Luật CAN", 1)
        addTabButton("Âm thanh & dịch vụ", 2)

        root.addView(
            tabBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        pages.forEachIndexed { index, page ->
            page.visibility = if (index == 0) View.VISIBLE else View.GONE
        }
        buttons.forEachIndexed { index, button -> button.isEnabled = index != 0 }
        return root
    }

    /** Tab đầu tiên vẫn giữ kiểu monitor cuộn đơn giản của app FYTCanbusMonitor gốc. */
    private fun buildMonitorTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val clear = Button(this).apply {
            text = "XÓA"
            setOnClickListener { MonitorStore.clear(); lastMonitorVersion = -1L; refreshMonitor() }
        }
        val pause = Button(this).apply {
            text = "TẠM DỪNG"
            setOnClickListener {
                monitorPaused = !monitorPaused
                text = if (monitorPaused) "TIẾP TỤC" else "TẠM DỪNG"
                if (!monitorPaused) lastMonitorVersion = -1L
            }
        }
        val export = Button(this).apply {
            text = "XUẤT TXT"
            setOnClickListener { exportMonitorLog() }
        }
        controls.addView(clear, weightParams())
        controls.addView(pause, weightParams())
        controls.addView(export, weightParams())
        root.addView(controls)

        latestCanText = TextView(this).apply {
            text = "CANBUS mới nhất: đang chờ..."
            setPadding(0, dp(6), 0, dp(4))
        }
        root.addView(latestCanText)

        val useLatest = Button(this).apply {
            text = "DÙNG SỰ KIỆN CAN MỚI NHẤT LÀM LUẬT"
            setOnClickListener {
                val event = MonitorStore.latestCanEvent
                if (event?.ints?.isEmpty() != false) {
                    toast("Chưa có sự kiện CANBUS IntArray để dùng")
                } else {
                    showRuleDialog(null, event)
                }
            }
        }
        root.addView(useLatest)

        monitorScroll = ScrollView(this)
        monitorText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(4), dp(6), dp(4), dp(12))
            text = "Đang chờ dữ liệu FYT CAN..."
        }
        monitorScroll.addView(
            monitorText,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            monitorScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        return root
    }

    private fun buildRulesTab(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Chỉ cần một luật đang bật khớp dữ liệu CAN là phát âm thanh. Âm thanh chỉ dừng khi không còn luật nào khớp. Cách này xử lý an toàn trường hợp đèn cảnh báo nguy hiểm làm cả trái và phải cùng hoạt động; nếu hazard có CAN index riêng thì thêm index đó thành một luật khác."
            textSize = 15f
            setPadding(0, 0, 0, dp(10))
        })
        root.addView(Button(this).apply {
            text = "+ THÊM LUẬT"
            setOnClickListener { showRuleDialog(null, null) }
        })
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
            text = "File MP3 mới được SoundPool nạp và giải mã ngay khi service khởi tạo, sau đó giữ mẫu âm thanh trong RAM để phản hồi nhanh. Khi phát tới cuối file, SoundPool lặp lại từ đầu ngay lập tức, không chèn thời gian nghỉ. App không yêu cầu Audio Focus nên được thiết kế để trộn cùng nhạc/Vietmap/CarPlay thay vì chủ động dừng hoặc hạ âm lượng các nguồn khác."
            setPadding(0, dp(8), 0, dp(10))
        })

        root.addView(TextView(this).apply {
            text = "TỰ KHỞI ĐỘNG\n\nKhởi động lại Android hoàn toàn: app tự xử lý bằng BOOT_COMPLETED + foreground service.\n\nDUDUOS 3.7 sleep/wake: tạo một Automatic Task:\nVehicle ignition → Open app → FYT Turn Sound - Chạy nền\n\nMục Chạy nền chỉ khởi động service rồi đóng Activity trong suốt ngay, vì vậy màn hình cấu hình không nằm đè lên giao diện DUDU."
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
        })

        root.addView(Button(this).apply {
            text = "KHỞI ĐỘNG / LÀM MỚI DỊCH VỤ"
            setOnClickListener { startTurnService(); sendServiceAction(TurnSignalService.ACTION_REFRESH) }
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

    private fun refreshMonitor() {
        val latest = MonitorStore.latestCanEvent
        latestCanText.text = if (latest == null) {
            "CANBUS mới nhất: đang chờ..."
        } else {
            "CANBUS mới nhất: ${latest.originalStyleLine()}"
        }
        if (monitorPaused || MonitorStore.version == lastMonitorVersion) return
        lastMonitorVersion = MonitorStore.version
        val text = MonitorStore.snapshot()
        monitorText.text = if (text.isBlank()) "Đang chờ dữ liệu FYT CAN..." else text
        monitorScroll.post { monitorScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshStatus() {
        val error = RuntimeState.lastError?.let { "\nLỗi gần nhất: $it" } ?: ""
        serviceStatusText.text = buildString {
            append("Dịch vụ: ").append(if (RuntimeState.serviceRunning) "ĐANG CHẠY" else "ĐÃ DỪNG")
            append("\nGói FYT: ").append(if (RuntimeState.fytPackagePresent) "CÓ SẴN" else "KHÔNG CÓ - chế độ thử điện thoại")
            append("\nFYT com.syu.ms: ").append(if (RuntimeState.fytConnected) "ĐÃ KẾT NỐI" else "ĐANG CHỜ")
            append("\nMẫu âm thanh: ").append(if (RuntimeState.audioReady) "ĐÃ NẠP RAM" else "ĐANG NẠP")
            append("\nCó luật đang khớp: ").append(if (RuntimeState.ruleActive) "CÓ" else "KHÔNG")
            append(error)
        }
    }

    private fun renderRules() {
        if (!::rulesContainer.isInitialized) return
        val rules = RuleStore.load(this)
        rulesContainer.removeAllViews()
        if (rules.isEmpty()) {
            rulesContainer.addView(TextView(this).apply {
                text = "Chưa có luật. Hãy bật xi nhan trong tab Giám sát, sau đó dùng sự kiện CAN mới nhất hoặc thêm luật thủ công."
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
                setOnClickListener { showRuleDialog(rule, null) }
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

    private fun showRuleDialog(existing: CanRule?, event: FytEvent?) {
        val base = existing?.copy() ?: run {
            val first = event?.ints?.getOrNull(0) ?: 0
            CanRule(
                index = event?.index ?: 0,
                position = 0,
                expectedValue = if (first < 0) first and 0xFF else first,
                unsignedByte = first < 0
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        fun numberField(label: String, value: Int): EditText {
            val edit = EditText(this).apply {
                hint = label
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(value.toString())
                selectAll()
            }
            root.addView(TextView(this).apply { text = label })
            root.addView(edit)
            return edit
        }

        val indexEdit = numberField("CANBUS index", base.index)
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

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Thêm luật CAN" else "Sửa luật CAN")
            .setView(root)
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
                val rules = RuleStore.load(this)
                val updated = base.copy(
                    index = idx,
                    position = pos,
                    expectedValue = value,
                    unsignedByte = unsigned.isChecked
                )
                val oldIndex = rules.indexOfFirst { it.id == updated.id }
                if (oldIndex >= 0) rules[oldIndex] = updated else rules.add(updated)
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
                put(MediaStore.MediaColumns.DISPLAY_NAME, "FYT-CAN-${timestampForFile()}.txt")
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
            startForegroundService(
                Intent(this, TurnSignalService::class.java).apply { this.action = action }
            )
        } catch (t: Throwable) {
            RuntimeState.lastError = "Lệnh dịch vụ: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return

        val prefs = getSharedPreferences("turn_sound_ui", MODE_PRIVATE)
        if (prefs.getBoolean("notification_permission_requested", false)) return

        // Ghi trạng thái trước khi mở hộp thoại hệ thống để không hỏi quyền lặp lại.
        prefs.edit().putBoolean("notification_permission_requested", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }

    private fun weightParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
