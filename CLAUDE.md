# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Debug APK
gradle assembleDebug --no-daemon

# Install to connected device
gradle installDebug --no-daemon

# Release APK (unsigned unless KEYSTORE_PATH/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD env vars are set)
gradle assembleRelease --no-daemon
```

No test suite exists yet.

## Architecture

NotiMirror mirrors iPhone notifications to Android via BLE using Apple's ANCS protocol. The iPhone must already be bonded over Bluetooth before ANCS is available.

**Dependency wiring:** `NotiMirrorApp` creates a single `AppContainer` (manual DI, no framework). All components are singletons for the app lifetime. `AncsForegroundService` accesses them via `(application as NotiMirrorApp).container`.

**BLE/ANCS data flow:**
1. `AncsForegroundService` drives scanning (`BleScanner`) and connection (`BleConnectionManager`)
2. On `ServiceDiscovered`, `AncsClient` subscribes to the two ANCS notify characteristics (Notification Source + Data Source)
3. Each 8-byte **Notification Source** packet is parsed by `parseNotificationSource()` → `AncsNotificationEvent` with a `notificationUid`
4. `AncsClient` stores a placeholder `IPhoneNotification` in `NotificationRepository` and writes a `GetNotificationAttributes` command to the Control Point
5. iOS responds on **Data Source** (possibly fragmented across BLE packets). `DataSourceParser` buffers and reassembles fragments, then returns `ParsedAttributes` once complete
6. `AncsClient` calls `repository.updateAttributes(uid, ...)` — the `uid` must match the placeholder's uid (both come from the same iOS notification UID)
7. `MainViewModel` exposes `repository.notifications` as a `StateFlow`; `DashboardScreen` renders it

**Key ANCS detail:** The Data Source response layout is `[CommandID 1B][UID 4B LE][attributes...]`. The UID is at byte offset 1, not 0.

**Write serialisation:** Android BLE only allows one in-flight GATT write at a time. `BleConnectionManager` has an `ArrayDeque` write queue drained in `onCharacteristicWrite`.

**UI:** Single-Activity Compose app with a `NavHost` (Dashboard → Pairing → Debug → Settings). All screens share one `MainViewModel`. The Debug screen shows a live `repository.debugEvents` log useful for diagnosing ANCS packet issues.

**Settings persistence:** `AppSettings` uses Jetpack DataStore (not SharedPreferences). Filtered apps (muted by bundle ID), show-body toggle, auto-reconnect, and keep-screen-awake are stored there.

## CI

`.github/workflows/debug-apk.yml` builds a debug APK on every push to main and publishes it as a GitHub Release tagged `build-<run_number>`. PRs only build (no release).
