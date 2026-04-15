# HomeAI Assistant – Android App

A 100% local, always-on AI assistant for Samsung A12 (or any Android device, min SDK 23).

---

## Features

| Feature | Status |
|---|---|
| Fullscreen animated AI face (emerald neon eyes) | ✅ Complete |
| Breathing glow + pupil movement + blink animation | ✅ Complete |
| Front/back camera toggle with label indicator | ✅ Complete |
| Person detection via camera (heuristic stub) | ✅ Stub – plug in TFLite |
| Local LLM for Spanish text generation | ✅ Stub – plug in model |
| TTS in Spanish (es-ES) | ✅ Complete |
| STT in Spanish (es-ES) | ✅ Complete |
| 24/7 foreground service with wake lock | ✅ Complete |
| Auto-start on boot | ✅ Complete |
| Immersive sticky fullscreen (kiosk mode) | ✅ Complete |
| Screen always-on | ✅ Complete |
| Fully offline (no cloud APIs) | ✅ Complete |

---

## Project Structure

```
app/src/main/kotlin/com/homeai/assistant/
├── ui/
│   ├── MainActivity.kt          – Fullscreen activity, immersive mode, UI
│   └── EyesView.kt              – Custom animated eye canvas view
├── camera/
│   ├── CameraController.kt      – CameraX wrapper, front/back toggle
│   └── PersonDetector.kt        – TFLite person detection (stub + heuristic)
├── audio/
│   ├── SpeechInputManager.kt    – SpeechRecognizer (es-ES)
│   └── SpeechOutputManager.kt   – TextToSpeech (es-ES, STREAM_MUSIC)
├── llm/
│   └── LocalAssistant.kt        – Local LLM wrapper (Spanish stub)
├── service/
│   ├── HomeAIService.kt         – Foreground service, pipeline orchestration
│   └── ServiceLifecycleOwner.kt – LifecycleOwner for CameraX in a Service
└── receiver/
    └── BootReceiver.kt          – BOOT_COMPLETED → start service
```

---

## Build Instructions

### Requirements
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34

### Steps

1. **Clone / open** this folder as an Android project in Android Studio.
2. **Sync Gradle** (File → Sync Project with Gradle Files).
3. **(Optional) Add models** – see `app/src/main/assets/README_MODELS.txt`.
4. **Connect your Samsung A12** (or any Android 6.0+ device) via USB with USB debugging enabled.
5. **Run** (Shift+F10) or build an APK (Build → Build APK).

---

## Adding a Real Person-Detection Model

1. Download `EfficientDet-Lite0` or `MobileNet SSD v2` from TFLite Model Garden.
2. Rename to `person_detect.tflite` and place in `app/src/main/assets/`.
3. Open `PersonDetector.kt` and uncomment the TFLite `Interpreter` initialisation block.
4. Replace the body of `analyzeWithModel()` with real inference using TFLite Support Library.

---

## Adding a Real Local LLM

1. Download **TinyLlama 1.1B Q4_K_M GGUF** (~700 MB) from Hugging Face.
2. Add `llama.cpp` Android JNI bindings to the project (prebuilt `.so` or CMake build).
3. Place the `.gguf` file in `app/src/main/assets/`.
4. In `LocalAssistant.kt`, uncomment the `nativeInit` block and implement `generateWithModel()`.

---

## Spanish TTS Setup on Samsung A12

The Samsung A12 may not have Spanish TTS data pre-installed.

1. Go to **Settings → General Management → Language → Text-to-Speech**.
2. Select **Google Text-to-Speech Engine** → tap the settings gear.
3. Under **Install voice data**, download **Spanish (Spain)** or **Spanish (United States)**.
4. Set the default language to **Español (España)** if desired.

---

## Permissions Required

| Permission | Purpose |
|---|---|
| `CAMERA` | Person detection via front/back camera |
| `RECORD_AUDIO` | Speech recognition |
| `WAKE_LOCK` | Keep CPU running in background |
| `FOREGROUND_SERVICE` | Persistent background service |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on device boot |
| `INTERNET` | Android SpeechRecognizer may use Google servers |

---

## Keeping the App Always On (Samsung A12)

For true 24/7 operation:

1. **Disable battery optimisation** for this app:
   Settings → Apps → HomeAI → Battery → "Unrestricted".
2. **Disable app sleeping**:
   Settings → Device Care → Battery → App power management → "Never sleeping apps" → add HomeAI.
3. **Keep screen on while charging** is handled automatically by `FLAG_KEEP_SCREEN_ON`.
4. The foreground service + wake lock ensure the service continues even when the screen is off.

---

## Architecture Notes

- **Conversation history** is capped at 20 turns (+ system prompt) to avoid memory growth.
- **CameraController** uses CameraX `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST` to drop frames when the analyser is busy — ideal for low-end devices.
- **EyesView** uses `ValueAnimator` with no per-frame allocations; blinking is random-interval scheduled with `postDelayed`.
- **HomeAIService** orchestrates all modules and uses Kotlin coroutines for async inference without blocking the main thread.
