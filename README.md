# NotiMirror

An Android app that mirrors iPhone notifications to an Android phone over Bluetooth Low Energy using Apple's **ANCS** (Apple Notification Center Service) protocol.

Current app version: **1.1.0** (`versionCode` 2).

---

## How It Works

ANCS is a BLE GATT service that Apple exposes on iPhones once a Bluetooth bond exists between the iPhone and an external accessory. NotiMirror acts as that accessory: it connects over BLE, subscribes to notification events, fetches full notification details, and displays them in a live dashboard. It can also mirror supported notifications into the Android notification shade.

No jailbreak, no CarPlay, no MFi chip required — only a standard Bluetooth bond.

---

## Setup Steps

### 1. Pair the Devices (must do first)

ANCS only starts advertising after a bond is established.

1. On your **iPhone**: Settings → Bluetooth → enable Bluetooth.
2. On your **Android**: Settings → Connected devices → Pair new device.
3. Initiate pairing from Android. Accept the pairing prompt on iPhone.

### 2. Enable Notification Sharing on iPhone

After pairing:

1. On your iPhone: Settings → Bluetooth.
2. Tap the **ⓘ** icon next to your Android device in the "My Devices" list.
3. Enable **Share System Notifications** (toggle must be green).

Without this toggle iOS will not send ANCS events to the Android device.

### 3. Launch NotiMirror

1. Open NotiMirror on Android.
2. Grant all requested permissions (Bluetooth, location, notifications).
3. Tap **Connect to iPhone** on the dashboard or go to the pairing screen.
4. Select your iPhone from the scanned device list.
5. NotiMirror starts a foreground service and connects over BLE.
6. Incoming iPhone notifications will appear on the dashboard within 1–2 seconds.

After the first successful connection, NotiMirror remembers the last device address and can auto-reconnect when the service starts or the Bluetooth link drops.

---

## Permissions Explained

| Permission | Why It's Needed |
|---|---|
| `BLUETOOTH_SCAN` | Discover nearby BLE devices |
| `BLUETOOTH_CONNECT` | Connect to and communicate with the iPhone |
| `ACCESS_FINE_LOCATION` | Required by Android for BLE scanning (not used for location) |
| `POST_NOTIFICATIONS` | (Android 13+) Show foreground service notification |
| `FOREGROUND_SERVICE` | Keep BLE connection alive when app is in background |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Required for Android 14+ foreground service type |

---

## ANCS Protocol Reference

NotiMirror implements the ANCS spec published by Apple.

**Service UUID:** `7905F431-B5CE-4E99-A40F-4B1E122D00D0`

| Characteristic | UUID | Direction |
|---|---|---|
| Notification Source | `9FBF120D-6301-42D9-8C58-25E699A21DBD` | iPhone → Android (notify) |
| Control Point | `69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9` | Android → iPhone (write) |
| Data Source | `22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB` | iPhone → Android (notify) |

**Notification Source packet (8 bytes):**
```
[EventID][EventFlags][CategoryID][CategoryCount][UID uint32 LE]
```

**GetNotificationAttributes command (Control Point write):**
```
[0x00][UID 4B LE][AttrID ...][MaxLen 2B LE for variable attrs]
```

**Data Source response (variable length, may be fragmented across BLE packets):**
```
[CommandID][UID 4B LE][AttrID][Len 2B LE][UTF-8 string] ...
```

---

## Architecture

```
MainActivity
└── AppNavigation (NavHost)
    ├── DashboardScreen   ← observes NotificationRepository.notifications
    ├── PairingScreen     ← triggers AncsForegroundService
    ├── DebugScreen       ← observes NotificationRepository.debugEvents
    └── SettingsScreen    ← reads/writes AppSettings (DataStore)

AncsForegroundService       ← owns BLE lifecycle, survives background
├── BleScanner              ← Flow-based BLE scan
├── BleConnectionManager    ← GATT connection + write queue
└── AncsClient              ← ANCS protocol orchestration
    ├── AncsParser          ← parses NS packets + reassembles DS fragments
    └── NotificationRepository ← StateFlow of notifications + debug log

MainViewModel               ← bridges Repository/Settings to UI
```

---

## Features

- Live dashboard for mirrored iPhone notifications.
- Optional Android notification shade mirroring.
- Per-app filtering and show/hide message body settings.
- Auto-reconnect to the last paired iPhone, with only one pending reconnect attempt at a time.
- iOS app display names fetched through ANCS and persisted locally.
- Debug screen for ANCS events, with optional verbose BLE packet logging in Settings.
- Basic Android Auto message actions for reply/mark-as-read UI compatibility. Replies cannot be sent back to iPhone through ANCS.

---

## Debugging

The Debug screen shows connection and ANCS protocol events. By default it avoids raw BLE packet spam.

For deeper packet diagnostics, enable:

```
Settings → Debug → Verbose BLE logging
```

Verbose logging adds raw Data Source packet hex, Control Point command bytes, and parsed attribute details to the Debug screen.

---

## Known Limitations

- **iOS notification preview privacy** — If the user has "Show Previews: Never" in iPhone Settings → Notifications, iOS will not send the message body in ANCS attributes. The title may also be redacted. This is an iOS privacy control; NotiMirror cannot bypass it.

- **Android OEM battery optimization** — On some manufacturers (Samsung, Xiaomi, Huawei), aggressive battery optimization kills background services. Add NotiMirror to the "Unrestricted" battery list: Settings → Apps → NotiMirror → Battery → Unrestricted.

- **Re-pairing after some iOS updates** — Major iOS updates occasionally invalidate BLE bonds. If notifications stop arriving, go to iPhone Settings → Bluetooth, forget the Android device, and re-pair.

- **ANCS service discovery timing** — On first connection iOS may take 5–10 seconds to expose the ANCS service. NotiMirror retries automatically.

- **Call control depends on iOS action availability** — Answer/decline actions are shown only when iOS marks the notification as supporting those actions.

- **Reply to messages is not supported by ANCS** — Android Auto reply UI can collect a response, but ANCS does not provide a way for NotiMirror to send that text back to the iPhone app.

- **One iPhone at a time** — NotiMirror connects to a single iPhone. Multi-device support is not implemented.

---

## Building

Requires Android Studio Hedgehog (2023.1.1) or newer, AGP 8.5+, and Kotlin 1.9.x.

```bash
./gradlew assembleDebug
```

Install to a connected device:
```bash
./gradlew installDebug
```

Run JVM parser/command tests:
```bash
./gradlew testDebugUnitTest
```

Minimum SDK: **31** (Android 12)  
Target SDK: **35** (Android 15)
Version name/code are maintained in `app/build.gradle.kts` and displayed in Settings → About.

---

## Support

NotiMirror is a small personal project built to solve a practical Bluetooth notification problem. If it saves you time or helps with your own setup, a small tip is appreciated and helps keep development moving.

<a href="https://www.buymeacoffee.com/gemridge" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-red.png" alt="Buy Me a Coffee" width="217" height="60"></a>

---

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
