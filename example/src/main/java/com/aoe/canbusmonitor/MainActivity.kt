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
    private lateinit var loopGapEdit: EditText
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

        // Build the UI before starting services or opening a runtime-permission dialog.
        // This also keeps generic Android phones usable as a UI/audio test device.
        setContentView(buildTabbedUi())
        renderRules()
        startTurnService()

        // Ask only after the first frame is queued. A startup crash must never cause a
        // permission-dialog loop, and we only ask once per install/session of app data.
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
     * Lightweight tab bar implemented with normal Buttons + FrameLayout.
     * We intentionally do not use android.widget.TabHost: Android 16 can resolve its
     * legacy label-indicator layout to resource id 0 for a programmatically-built host.
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
                        // Disabled = selected. It gives a clear state without relying on
                        // theme-specific colors that may differ on DUDUOS.
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

        addTabButton("Monitor", 0)
        addTabButton("Rules", 1)
        addTabButton("Sound & Service", 2)

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

    /** First tab deliberately preserves the original app's plain scrolling event monitor. */
    private fun buildMonitorTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val clear = Button(this).apply {
            text = "CLEAR"
            setOnClickListener { MonitorStore.clear(); lastMonitorVersion = -1L; refreshMonitor() }
        }
        val pause = Button(this).apply {
            text = "PAUSE"
            setOnClickListener {
                monitorPaused = !monitorPaused
                text = if (monitorPaused) "RESUME" else "PAUSE"
                if (!monitorPaused) lastMonitorVersion = -1L
            }
        }
        val export = Button(this).apply {
            text = "EXPORT TXT"
            setOnClickListener { exportMonitorLog() }
        }
        controls.addView(clear, weightParams())
        controls.addView(pause, weightParams())
        controls.addView(export, weightParams())
        root.addView(controls)

        latestCanText = TextView(this).apply {
            text = "Latest CANBUS: waiting..."
            setPadding(0, dp(6), 0, dp(4))
        }
        root.addView(latestCanText)

        val useLatest = Button(this).apply {
            text = "USE LATEST CAN EVENT AS RULE"
            setOnClickListener {
                val event = MonitorStore.latestCanEvent
                if (event?.ints?.isEmpty() != false) {
                    toast("No CANBUS IntArray event available yet")
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
            text = "Waiting for FYT CAN data..."
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
            text = "Any enabled rule matching = PLAY. Sound stops only when the last matching rule becomes false. This safely handles LEFT + RIGHT together for hazard. If hazard uses a separate CAN index, add that index as another rule."
            textSize = 15f
            setPadding(0, 0, 0, dp(10))
        })
        root.addView(Button(this).apply {
            text = "+ ADD RULE"
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
            text = "Turn signal sound enabled"
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
                    volumeLabel.text = "Turn signal volume: $progress%"
                    if (fromUser) SettingsStore.setVolume(this@MainActivity, progress / 100f)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    sendServiceAction(TurnSignalService.ACTION_REFRESH)
                }
            })
        }
        volumeLabel.text = "Turn signal volume: ${volumeSeek.progress}%"
        root.addView(volumeSeek)

        root.addView(TextView(this).apply {
            text = "Sleep after each sound sample before playing it again (ms)"
            setPadding(0, dp(14), 0, dp(4))
        })
        loopGapEdit = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(SettingsStore.loopGapMs(this@MainActivity).toString())
            hint = "0 - ${SettingsStore.MAX_LOOP_GAP_MS} ms"
            setSelectAllOnFocus(true)
        }
        root.addView(loopGapEdit)
        root.addView(TextView(this).apply {
            text = "0 = no extra sleep. Maximum ${SettingsStore.MAX_LOOP_GAP_MS} ms. The delay is inserted after the 1.126 s sound sample, and stop remains immediate when the CAN rule turns off."
            setPadding(0, 0, 0, dp(8))
        })

        root.addView(Button(this).apply {
            text = "APPLY / SAVE SOUND SETTINGS"
            setOnClickListener { applySoundSettings(true) }
        })

        val testRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        testRow.addView(Button(this).apply {
            text = "TEST SOUND"
            setOnClickListener {
                if (applySoundSettings(false)) {
                    sendServiceAction(TurnSignalService.ACTION_TEST_SOUND)
                }
            }
        }, weightParams())
        testRow.addView(Button(this).apply {
            text = "STOP TEST"
            setOnClickListener { sendServiceAction(TurnSignalService.ACTION_STOP_TEST) }
        }, weightParams())
        root.addView(testRow)

        root.addView(TextView(this).apply {
            text = "Sound enabled, volume and loop sleep are stored in SharedPreferences. APPLY saves them, so rebooting the unit or reopening the app does not require configuring them again. TEST SOUND also applies the values currently shown above before testing."
            setPadding(0, dp(10), 0, dp(6))
        })

        root.addView(TextView(this).apply {
            text = "Audio is a short tick-tock sample cut from xinhan.mp3 and preloaded/decoded by SoundPool. The app does not request Audio Focus, so it is designed to mix over music instead of pausing or ducking it."
            setPadding(0, dp(8), 0, dp(10))
        })

        root.addView(TextView(this).apply {
            text = "AUTO START\n\nFull reboot: handled automatically by BOOT_COMPLETED + foreground service.\n\nDUDUOS 3.7 sleep/wake: create one Automatic Task:\nVehicle ignition → Open app → FYT Turn Sound - Background\n\nThe Background entry starts the service and closes immediately with a transparent Activity, so the configuration screen should not remain on top."
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
        })

        root.addView(Button(this).apply {
            text = "START / REFRESH SERVICE"
            setOnClickListener { startTurnService(); sendServiceAction(TurnSignalService.ACTION_REFRESH) }
        })
        scroll.addView(root)
        return scroll
    }

    private fun applySoundSettings(showToast: Boolean): Boolean {
        val gap = loopGapEdit.text.toString().trim().toIntOrNull()
        if (gap == null || gap !in 0..SettingsStore.MAX_LOOP_GAP_MS) {
            toast("Loop sleep must be 0-${SettingsStore.MAX_LOOP_GAP_MS} ms")
            return false
        }
        SettingsStore.applySoundSettings(
            this,
            soundEnabledSwitch.isChecked,
            volumeSeek.progress / 100f,
            gap
        )
        sendServiceAction(TurnSignalService.ACTION_REFRESH)
        if (showToast) toast("Sound settings saved")
        return true
    }

    private fun refreshMonitor() {
        val latest = MonitorStore.latestCanEvent
        latestCanText.text = if (latest == null) {
            "Latest CANBUS: waiting..."
        } else {
            "Latest CANBUS: ${latest.originalStyleLine()}"
        }
        if (monitorPaused || MonitorStore.version == lastMonitorVersion) return
        lastMonitorVersion = MonitorStore.version
        val text = MonitorStore.snapshot()
        monitorText.text = if (text.isBlank()) "Waiting for FYT CAN data..." else text
        monitorScroll.post { monitorScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshStatus() {
        val error = RuntimeState.lastError?.let { "\nLast error: $it" } ?: ""
        serviceStatusText.text = buildString {
            append("Service: ").append(if (RuntimeState.serviceRunning) "RUNNING" else "STOPPED")
            append("\nFYT package: ").append(if (RuntimeState.fytPackagePresent) "AVAILABLE" else "NOT PRESENT (phone test mode)")
            append("\nFYT com.syu.ms: ").append(if (RuntimeState.fytConnected) "CONNECTED" else "WAITING")
            append("\nAudio sample: ").append(if (RuntimeState.audioReady) "READY" else "LOADING")
            append("\nAny rule matched: ").append(if (RuntimeState.ruleActive) "YES" else "NO")
            append(error)
        }
    }

    private fun renderRules() {
        if (!::rulesContainer.isInitialized) return
        val rules = RuleStore.load(this)
        rulesContainer.removeAllViews()
        if (rules.isEmpty()) {
            rulesContainer.addView(TextView(this).apply {
                text = "No rules yet. Trigger the turn signal in Monitor, then use the latest CAN event or add a rule manually."
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
                text = "EDIT"
                setOnClickListener { showRuleDialog(rule, null) }
            }, weightParams())
            row.addView(Button(this).apply {
                text = "DELETE"
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
        val positionEdit = numberField("IntArray position", base.position)
        val valueEdit = numberField("Expected value", base.expectedValue)
        val unsigned = CheckBox(this).apply {
            text = "Compare as unsigned byte (actual & 0xFF)"
            isChecked = base.unsignedByte
        }
        root.addView(unsigned)
        event?.let {
            root.addView(TextView(this).apply {
                text = "Captured: ${it.originalStyleLine()}"
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(8), 0, 0)
            })
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add CAN rule" else "Edit CAN rule")
            .setView(root)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val idx = indexEdit.text.toString().toIntOrNull()
                val pos = positionEdit.text.toString().toIntOrNull()
                val value = valueEdit.text.toString().toIntOrNull()
                if (idx == null || pos == null || value == null || idx < 0 || pos < 0) {
                    toast("Index, position and value must be valid numbers")
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
            toast("Monitor log is empty")
            return
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "FYT-CAN-${timestampForFile()}.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert returned null")
            contentResolver.openOutputStream(uri, "w")!!.use { out ->
                out.write(log.toByteArray(Charsets.UTF_8))
            }
            toast("Saved to Downloads")
        } catch (t: Throwable) {
            toast("Export failed: ${t.message}")
        }
    }

    private fun startTurnService() {
        try {
            startForegroundService(Intent(this, TurnSignalService::class.java))
        } catch (t: Throwable) {
            RuntimeState.lastError = "Start service: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun sendServiceAction(action: String) {
        try {
            startForegroundService(
                Intent(this, TurnSignalService::class.java).apply { this.action = action }
            )
        } catch (t: Throwable) {
            RuntimeState.lastError = "Service action: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return

        val prefs = getSharedPreferences("turn_sound_ui", MODE_PRIVATE)
        if (prefs.getBoolean("notification_permission_requested", false)) return

        // Store before opening the system dialog. Even if the Activity is unexpectedly
        // recreated, we never generate a stack of duplicate permission dialogs.
        prefs.edit().putBoolean("notification_permission_requested", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }

    private fun weightParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
