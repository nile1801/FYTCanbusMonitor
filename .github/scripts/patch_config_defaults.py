from pathlib import Path


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"marker not found: {label}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# AppModels.kt: RuleStore schema v3, settings backup/restore, built-in defaults.
# -----------------------------------------------------------------------------
path = Path("example/src/main/java/com/aoe/canbusmonitor/AppModels.kt")
text = path.read_text(encoding="utf-8")
start = text.index("object RuleStore {")
end = text.index("\nobject SettingsStore {", start)
new_rule_store = r'''object RuleStore {
    private const val PREFS = "turn_sound_rules"
    private const val KEY = "rules_json"
    private const val BACKUP_SCHEMA_VERSION = 3

    private data class ParseResult(
        val rules: MutableList<CanRule>,
        val skipped: Int
    )

    @Volatile private var pendingStatusMessage: String? = null

    fun consumeStatusMessage(): String? {
        val message = pendingStatusMessage
        pendingStatusMessage = null
        return message
    }

    fun load(context: Context): MutableList<CanRule> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (prefs.contains(KEY)) {
            val localText = prefs.getString(KEY, "[]") ?: "[]"
            val local = parseRules(localText)
            if (local != null) {
                if (local.skipped > 0) {
                    pendingStatusMessage = "Đã đọc ${local.rules.size} rule, bỏ qua ${local.skipped} rule lỗi trong config nội bộ."
                }
                return local.rules
            }
        }

        val backupText = RuleBackupStore.read(context)
        if (!backupText.isNullOrBlank()) {
            val restored = parseRules(backupText)
            if (restored != null) {
                restoreSettingsFromConfig(context, backupText)
                prefs.edit().putString(KEY, rulesToArray(restored.rules).toString()).apply()
                pendingStatusMessage = if (restored.skipped > 0) {
                    "Đã tự restore ${restored.rules.size} rule + cài đặt tắt từ ${RuleBackupStore.DISPLAY_PATH}; bỏ qua ${restored.skipped} rule lỗi."
                } else {
                    "Đã tự restore ${restored.rules.size} rule + cài đặt tắt từ ${RuleBackupStore.DISPLAY_PATH}."
                }
                return restored.rules
            }
            pendingStatusMessage = "Tìm thấy backup nhưng JSON không đọc được; cần cấu hình rule lại."
        }

        return mutableListOf()
    }

    fun save(context: Context, rules: List<CanRule>) {
        val array = rulesToArray(rules)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()

        if (!RuleBackupStore.write(context, configToJson(context, rules).toString(2))) {
            pendingStatusMessage = "Đã lưu rule trong app nhưng chưa ghi được backup ${RuleBackupStore.DISPLAY_PATH}."
        }
    }

    /** Ghi lại stopMode + timeout hiện tại vào rules.json mà không thay rule. */
    fun syncBackup(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val localText = prefs.getString(KEY, null)
        val rules = if (!localText.isNullOrBlank()) {
            parseRules(localText)?.rules
        } else {
            null
        } ?: load(context)

        if (!RuleBackupStore.write(context, configToJson(context, rules).toString(2))) {
            pendingStatusMessage = "Đã lưu cài đặt trong app nhưng chưa cập nhật được ${RuleBackupStore.DISPLAY_PATH}."
        }
    }

    /** Snapshot đầy đủ để dùng cho nút LƯU CONFIG MẶC ĐỊNH. */
    fun exportCurrentConfig(context: Context): String {
        return configToJson(context, load(context)).toString(2)
    }

    /**
     * Áp một snapshot ngoài vào config hiện tại. Hàm này cố ý gọi save() để rules.json
     * bị override đúng theo config vừa load.
     */
    fun applyExternalConfig(context: Context, text: String): MutableList<CanRule>? {
        val parsed = parseRules(text) ?: return null
        restoreSettingsFromConfig(context, text)
        save(context, parsed.rules)
        pendingStatusMessage = if (parsed.skipped > 0) {
            "Đã áp config ${parsed.rules.size} rule; bỏ qua ${parsed.skipped} rule lỗi."
        } else {
            "Đã áp config ${parsed.rules.size} rule."
        }
        return parsed.rules
    }

    /**
     * Bộ mặc định gốc chỉ dùng khi chưa từng có default_config.json do user lưu.
     * CANBUS:1049 dùng byte thứ 2 (position=1) để phân biệt 80/72/64.
     */
    fun builtInDefaultConfig(): String {
        val rules = listOf(
            CanRule(module = RuleModule.CANBUS, action = RuleAction.START, target = SignalTarget.LEFT, index = 1049, position = 1, expectedValue = 80),
            CanRule(module = RuleModule.CANBUS, action = RuleAction.START, target = SignalTarget.RIGHT, index = 1049, position = 1, expectedValue = 72),
            CanRule(module = RuleModule.CANBUS, action = RuleAction.STOP, target = SignalTarget.LEFT, index = 1049, position = 1, expectedValue = 64),
            CanRule(module = RuleModule.CANBUS, action = RuleAction.STOP, target = SignalTarget.RIGHT, index = 1049, position = 1, expectedValue = 64),
            CanRule(module = RuleModule.MAIN, action = RuleAction.START, target = SignalTarget.HAZARD, index = 139, position = 0, expectedValue = 1),
            CanRule(module = RuleModule.MAIN, action = RuleAction.STOP, target = SignalTarget.HAZARD, index = 139, position = 0, expectedValue = 0)
        )
        return configToJson(rules, StopMode.TRIGGER, SettingsStore.DEFAULT_TIMEOUT_MS).toString(2)
    }

    private fun configToJson(context: Context, rules: List<CanRule>): JSONObject {
        return configToJson(
            rules = rules,
            stopMode = SettingsStore.stopMode(context),
            timeoutMs = SettingsStore.timeoutMillis(context)
        )
    }

    private fun configToJson(rules: List<CanRule>, stopMode: StopMode, timeoutMs: Int): JSONObject {
        return JSONObject().apply {
            put("schemaVersion", BACKUP_SCHEMA_VERSION)
            put("savedAt", System.currentTimeMillis())
            put("settings", JSONObject().apply {
                put("stopMode", stopMode.name)
                put("timeoutMs", timeoutMs)
            })
            put("rules", rulesToArray(rules))
        }
    }

    private fun restoreSettingsFromConfig(context: Context, text: String) {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return
        val settings = try {
            JSONObject(trimmed).optJSONObject("settings")
        } catch (_: Throwable) {
            null
        } ?: return

        val stopMode = runCatching {
            StopMode.valueOf(settings.optString("stopMode", StopMode.TIMEOUT.name))
        }.getOrDefault(StopMode.TIMEOUT)
        val timeout = settings.optInt("timeoutMs", SettingsStore.DEFAULT_TIMEOUT_MS)
        SettingsStore.setStopMode(context, stopMode)
        SettingsStore.setTimeoutMillis(context, timeout)
    }

    private fun rulesToArray(rules: List<CanRule>): JSONArray {
        val array = JSONArray()
        rules.forEach { r ->
            array.put(JSONObject().apply {
                put("id", r.id)
                put("enabled", r.enabled)
                put("module", r.module.name)
                put("action", r.action.name)
                put("target", r.target.name)
                put("index", r.index)
                put("position", r.position)
                put("expected", r.expectedValue)
                put("unsigned", r.unsignedByte)
            })
        }
        return array
    }

    /**
     * Backward compatible:
     * - format cũ: top-level JSONArray
     * - schema v2: { schemaVersion, rules: [...] }
     * - schema v3: thêm settings.stopMode + settings.timeoutMs
     * - field module/action/target thiếu => CANBUS/START/LEFT
     * - rule lỗi độc lập bị skip, các rule còn đọc được vẫn restore.
     */
    private fun parseRules(text: String): ParseResult? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val array = try {
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONObject(trimmed).optJSONArray("rules") ?: return null
            }
        } catch (_: Throwable) {
            return null
        }

        val out = mutableListOf<CanRule>()
        var skipped = 0
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i)
            if (o == null) {
                skipped++
                continue
            }

            try {
                if (!o.has("index") || !o.has("position") || (!o.has("expected") && !o.has("expectedValue"))) {
                    skipped++
                    continue
                }

                val index = o.optInt("index", -1)
                val position = o.optInt("position", -1)
                if (index < 0 || position < 0) {
                    skipped++
                    continue
                }

                val module = runCatching {
                    RuleModule.valueOf(o.optString("module", RuleModule.CANBUS.name))
                }.getOrDefault(RuleModule.CANBUS)
                val action = runCatching {
                    RuleAction.valueOf(o.optString("action", RuleAction.START.name))
                }.getOrDefault(RuleAction.START)
                val target = runCatching {
                    SignalTarget.valueOf(o.optString("target", SignalTarget.LEFT.name))
                }.getOrDefault(SignalTarget.LEFT)
                val rawId = o.optString("id", "").trim()
                val expected = if (o.has("expected")) {
                    o.optInt("expected", 0)
                } else {
                    o.optInt("expectedValue", 0)
                }

                out += CanRule(
                    id = rawId.ifEmpty { UUID.randomUUID().toString() },
                    enabled = o.optBoolean("enabled", true),
                    module = module,
                    action = action,
                    target = target,
                    index = index,
                    position = position,
                    expectedValue = expected,
                    unsignedByte = o.optBoolean("unsigned", o.optBoolean("unsignedByte", false))
                )
            } catch (_: Throwable) {
                skipped++
            }
        }
        return ParseResult(out, skipped)
    }
}
'''
text = text[:start] + new_rule_store + text[end:]
path.write_text(text, encoding="utf-8")


# -----------------------------------------------------------------------------
# RuleBackupStore.kt: generic named shared files + persistent default config.
# -----------------------------------------------------------------------------
path = Path("example/src/main/java/com/aoe/canbusmonitor/RuleBackupStore.kt")
path.write_text(r'''package com.aoe.canbusmonitor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Backup config ra shared storage để file không nằm trong sandbox của app.
 * rules.json = config đang chạy; default_config.json = snapshot mặc định do user chủ động lưu.
 */
object RuleBackupStore {
    const val FILE_NAME = "rules.json"
    const val DEFAULT_CONFIG_FILE_NAME = "default_config.json"
    const val RELATIVE_DIR = "Download/FYTCanbusMonitor/"
    const val DISPLAY_PATH = "$RELATIVE_DIR$FILE_NAME"
    const val DEFAULT_CONFIG_DISPLAY_PATH = "$RELATIVE_DIR$DEFAULT_CONFIG_FILE_NAME"

    fun read(context: Context): String? = readNamed(context, FILE_NAME)
    fun write(context: Context, text: String): Boolean = writeNamed(context, FILE_NAME, text)

    fun readDefault(context: Context): String? = readNamed(context, DEFAULT_CONFIG_FILE_NAME)
    fun writeDefault(context: Context, text: String): Boolean = writeNamed(context, DEFAULT_CONFIG_FILE_NAME, text)

    private fun readNamed(context: Context, fileName: String): String? {
        readDirect(fileName)?.let { return it }
        return readMediaStore(context, fileName)
    }

    private fun writeNamed(context: Context, fileName: String, text: String): Boolean {
        if (writeDirect(fileName, text)) return true
        return writeMediaStore(context, fileName, text)
    }

    private fun backupFile(fileName: String): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(File(downloads, "FYTCanbusMonitor"), fileName)
    }

    private fun readDirect(fileName: String): String? {
        return try {
            val file = backupFile(fileName)
            if (!file.isFile) null else file.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeDirect(fileName: String, text: String): Boolean {
        return try {
            val file = backupFile(fileName)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "$fileName.tmp")
            temp.writeText(text, Charsets.UTF_8)
            if (file.exists() && !file.delete()) {
                temp.delete()
                return false
            }
            if (!temp.renameTo(file)) {
                file.writeText(text, Charsets.UTF_8)
                temp.delete()
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun findMediaStoreUri(context: Context, fileName: String): Uri? {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(fileName, RELATIVE_DIR)
        return try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(0)
                Uri.withAppendedPath(collection, id.toString())
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readMediaStore(context: Context, fileName: String): String? {
        val uri = findMediaStoreUri(context, fileName) ?: return null
        return try {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeMediaStore(context: Context, fileName: String, text: String): Boolean {
        val resolver = context.contentResolver
        var uri = findMediaStoreUri(context, fileName)
        return try {
            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                uri = resolver.insert(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values
                ) ?: return false
            }

            resolver.openOutputStream(uri!!, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return false

            runCatching {
                resolver.update(
                    uri!!,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
''', encoding="utf-8")


# -----------------------------------------------------------------------------
# MainActivity.kt: multi-target STOP UI + default config buttons + backup sync.
# -----------------------------------------------------------------------------
path = Path("example/src/main/java/com/aoe/canbusmonitor/MainActivity.kt")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    "import android.widget.ArrayAdapter\n",
    "import android.widget.AdapterView\nimport android.widget.ArrayAdapter\n",
    "AdapterView import"
)

text = replace_once(
    text,
    '''        MonitorStore.configureFilter(
            SettingsStore.monitorFilterMode(this),
            SettingsStore.monitorFilterModule(this),
            SettingsStore.monitorFilterIndexes(this)
        )

        setContentView(buildTabbedUi())''',
    '''        MonitorStore.configureFilter(
            SettingsStore.monitorFilterMode(this),
            SettingsStore.monitorFilterModule(this),
            SettingsStore.monitorFilterIndexes(this)
        )

        // Restore rules + stopMode + timeout trước khi dựng UI nếu backup đã đọc được.
        RuleStore.load(this)

        setContentView(buildTabbedUi())''',
    "pre-ui config restore"
)

text = replace_once(
    text,
    '''            val restoredRules = RuleStore.load(this)
            renderRules()
            sendServiceAction(TurnSignalService.ACTION_REFRESH)
            if (restoredRules.isNotEmpty()) {
                toast("Đã đọc lại ${restoredRules.size} rule sau khi cấp quyền backup")
            }
        }''',
    '''            val restoredRules = RuleStore.load(this)
            sendServiceAction(TurnSignalService.ACTION_REFRESH)
            if (restoredRules.isNotEmpty()) {
                toast("Đã đọc lại ${restoredRules.size} rule + cài đặt tắt sau khi cấp quyền backup")
            }
            // Rebuild UI để RadioGroup/SeekBar phản ánh stopMode + timeout vừa restore.
            recreate()
            return
        }''',
    "onResume restore UI"
)

text = replace_once(
    text,
    '''        root.addView(TextView(this).apply {
            text = "Backup rule: ${RuleBackupStore.DISPLAY_PATH}. Muốn tự restore sau khi uninstall/cài lại, Android 11+ cần bật quyền Quản lý tất cả tệp cho app."
            setPadding(0, dp(4), 0, dp(10))
        })
        refreshStorageAccessStatus()

        root.addView(TextView(this).apply {''',
    '''        root.addView(TextView(this).apply {
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

        root.addView(TextView(this).apply {''',
    "default buttons"
)

text = replace_once(
    text,
    '''                    SettingsStore.setTimeoutMillis(this@MainActivity, value)
                    sendServiceAction(TurnSignalService.ACTION_REFRESH)''',
    '''                    SettingsStore.setTimeoutMillis(this@MainActivity, value)
                    RuleStore.syncBackup(this@MainActivity)
                    sendServiceAction(TurnSignalService.ACTION_REFRESH)''',
    "timeout backup sync"
)

text = replace_once(
    text,
    '''            SettingsStore.setStopMode(this, mode)
            sendServiceAction(TurnSignalService.ACTION_REFRESH)''',
    '''            SettingsStore.setStopMode(this, mode)
            RuleStore.syncBackup(this)
            sendServiceAction(TurnSignalService.ACTION_REFRESH)''',
    "stop mode backup sync"
)

old_target = '''        val moduleValues = RuleModule.values()
        val actionValues = RuleAction.values()
        val targetValues = SignalTarget.values()
        val moduleSpinner = addSpinner("Nguồn", moduleValues.map { it.name }, moduleValues.indexOf(base.module))
        val actionSpinner = addSpinner("Hành động", actionValues.map { it.label() }, actionValues.indexOf(base.action))
        val targetSpinner = addSpinner("Map vào", targetValues.map { it.label() }, targetValues.indexOf(base.target))
        val indexEdit = numberField("Index", base.index)'''
new_target = '''        val moduleValues = RuleModule.values()
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

        val indexEdit = numberField("Index", base.index)'''
text = replace_once(text, old_target, new_target, "multi-target controls")

old_save = '''                val rules = RuleStore.load(this)
                val updated = base.copy(
                    module = moduleValues[moduleSpinner.selectedItemPosition],
                    action = actionValues[actionSpinner.selectedItemPosition],
                    target = targetValues[targetSpinner.selectedItemPosition],
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
                sendServiceAction(TurnSignalService.ACTION_REFRESH)'''
new_save = '''                val selectedAction = actionValues[actionSpinner.selectedItemPosition]
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
                sendServiceAction(TurnSignalService.ACTION_REFRESH)'''
text = replace_once(text, old_save, new_save, "multi-target save")
path.write_text(text, encoding="utf-8")


# -----------------------------------------------------------------------------
# TurnSignalService.kt: ensure restored settings are applied after RuleStore.load.
# -----------------------------------------------------------------------------
path = Path("example/src/main/java/com/aoe/canbusmonitor/TurnSignalService.kt")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''        ruleEngine.setStopMode(SettingsStore.stopMode(this))
        currentRules = RuleStore.load(this)
        ruleEngine.setRules(currentRules)''',
    '''        currentRules = RuleStore.load(this)
        ruleEngine.setHoldMillis(SettingsStore.timeoutMillis(this).toLong())
        ruleEngine.setStopMode(SettingsStore.stopMode(this))
        ruleEngine.setRules(currentRules)''',
    "service initial restored settings"
)
text = replace_once(
    text,
    '''        ruleEngine.setHoldMillis(SettingsStore.timeoutMillis(this).toLong())
        ruleEngine.setStopMode(SettingsStore.stopMode(this))
        currentRules = RuleStore.load(this)
        ruleEngine.setRules(currentRules)''',
    '''        currentRules = RuleStore.load(this)
        ruleEngine.setHoldMillis(SettingsStore.timeoutMillis(this).toLong())
        ruleEngine.setStopMode(SettingsStore.stopMode(this))
        ruleEngine.setRules(currentRules)''',
    "service refresh restored settings"
)
path.write_text(text, encoding="utf-8")


# -----------------------------------------------------------------------------
# Version bump.
# -----------------------------------------------------------------------------
path = Path("example/build.gradle")
text = path.read_text(encoding="utf-8")
text = replace_once(text, "versionCode 12", "versionCode 13", "versionCode")
text = replace_once(
    text,
    'versionName "1.6.2-dynamic-rule-subscriptions"',
    'versionName "1.6.3-config-defaults-multistop"',
    "versionName"
)
path.write_text(text, encoding="utf-8")
