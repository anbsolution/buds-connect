# Phase 4 — Advanced Customization

Target device: OnePlus Nord Buds 2R, product ID `062C14`.

## Features added
- Custom EQ list read (`0x0122`) and custom preset create/save (`0x0418`).
- Six-band frequency profile: 62, 250, 1000, 4000, 8000 and 16000 Hz.
- Gain range -6 dB to +6 dB; every save is followed by a fresh list read.
- Left/right gesture editing for single, double, triple and long press.
- Gesture options are restricted to the Nord Buds 2R model-profile support masks.
- Full gesture table is preserved and rewritten; a fresh `0x0108` read follows the write acknowledgement.
- Battery/wearing event subscription (`0x0205`) for battery push events.
- Local low-battery notification at 20% or below, when a fresh non-charging battery report arrives; alert state resets after a report above 20%.
- Automatic RFCOMM reconnection, maximum 3 attempts, only while the app screen/session remains active.
- App version 1.2.0, versionCode 3. Application ID unchanged.

## Important implementation boundaries
- The app does not expose ANC controls; Nord Buds 2R does not support ANC.
- Call noise reduction is built-in dual-mic AI processing, not a separate app switch.
- Low-battery alerts require notification permission on Android 13+.
- The case may report battery only while its lid is open.
- Automatic reconnect is session-scoped. It is not a background service and does not promise reconnection after Android kills the app.
- Gesture choices come from the model's documented support masks. No unsupported options are offered.
- Custom EQ uses the model's default six bands and -6..+6 gain tag.
- Physical device testing and GitHub Actions build validation remain required before release.

## Files replaced/added
- `app/src/main/java/com/budscontrol/app/bluetooth/RfcommClient.kt`
- `app/src/main/java/com/budscontrol/app/Phase3Screen.kt` (now Phase 4 screen implementation)
- `app/src/main/java/com/budscontrol/app/MainActivity.kt`
- `app/src/main/java/com/budscontrol/app/LowBatteryNotifier.kt`
- `app/src/main/AndroidManifest.xml`
- `app/build.gradle.kts`
- `docs/PHASE4-ADVANCED-CUSTOMIZATION.md`
