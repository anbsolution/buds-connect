# Buds Control

Android Native companion-app foundation for OnePlus Nord Buds 2R.

## Phase 1 scope
- OxygenOS-inspired Jetpack Compose UI
- Bluetooth permission handling
- Listing already-paired Bluetooth devices
- Local sound-profile preference storage
- Battery and proprietary-control capability states
- GitHub Actions debug APK workflow

## Important compatibility note
This project does **not** claim proprietary Nord Buds 2R controls are implemented. Standard Android Bluetooth APIs do not expose all vendor-specific features. Separate earbud/case battery, tap remapping, firmware information, locating tone and call-noise controls require a verified device protocol and real-device tests.

The equalizer screen in Phase 1 saves local preference values only. It does not apply system-wide audio processing or change the earbud firmware.

Nord Buds 2R does not have Active Noise Cancellation (ANC).

## Build
Requires JDK 17 and Gradle 8.9:
```bash
gradle assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions
Push to `main` or manually run the `Android APK Build` workflow. Download the `buds-control-debug-apk` artifact after a successful run.
