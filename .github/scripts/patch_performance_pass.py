from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"marker not found: {label}")
    return text.replace(old, new, 1)

# Worker callback đọc soundEnabled ngoài main/service thread, nên công bố thay đổi rõ ràng.
path = Path("example/src/main/java/com/aoe/canbusmonitor/TurnSignalService.kt")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    "    private var soundEnabled = true",
    "    @Volatile private var soundEnabled = true",
    "soundEnabled volatile"
)
path.write_text(text, encoding="utf-8")

# Monitor chỉ được bật ở onResume. Không mở rộng subscription trong khe onCreate -> onResume.
path = Path("example/src/main/java/com/aoe/canbusmonitor/MainActivity.kt")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    "        MonitorCaptureState.enabled = !monitorPaused\n        return root",
    "        MonitorCaptureState.enabled = false\n        return root",
    "monitor initial foreground state"
)
path.write_text(text, encoding="utf-8")

print("final performance touch-up applied")
