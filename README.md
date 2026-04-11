# BT Battery Ntfy

BT Battery Ntfy is a minimal Android utility app that tracks headphone battery while the headphones are connected and sends an `ntfy` POST with the last seen percentage after they disconnect.

The app stores:

- ntfy settings
- whether monitoring is enabled
- disconnect delay and cooldown settings
- per-device last seen battery and timestamp
- per-device audio session state
- recent debug log lines

## What It Does

- Watches Bluetooth battery updates using Android Bluetooth broadcasts where available.
- Focuses on headphone-style Bluetooth audio devices.
- Remembers the last battery percentage seen during a listening session.
- When the headphones disconnect, waits a short configurable delay, confirms they did not reconnect, and then sends an ntfy summary such as:

```text
Shokz OpenRun Pro 2
Battery: 50% @ 2026-04-11 16:52
```

- Uses a cooldown per device to suppress duplicate summaries caused by reconnect glitches.
- Restores monitoring behavior after reboot if monitoring is enabled.
- Includes a simple autosaving settings screen, a manual refresh button, a test send button, status lines, and a recent-event log.

## Android Permissions Used

- `android.permission.INTERNET`
  Needed to send POST requests to the ntfy server.

- `android.permission.RECEIVE_BOOT_COMPLETED`
  Needed to re-arm monitoring behavior after device reboot.

- `android.permission.BLUETOOTH_CONNECT`
  Needed on modern Android versions to read Bluetooth device details and battery-related events.

- `android.permission.BLUETOOTH` and `android.permission.BLUETOOTH_ADMIN` with `maxSdkVersion="30"`
  Included for compatibility with older Android releases.

## Build Steps

This project already includes:

- Gradle wrapper files
- `local.properties` pointing at `/home/owzo/Android/Sdk`
- `gradle.properties` pinned to Android Studio's JBR at `/opt/android-studio/jbr`

Build the debug APK with:

```bash
./gradlew assembleDebug
```

Expected debug APK path:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install With adb

After building:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Notes On ntfy Configuration

- If you enter a base URL such as `https://ntfy.sh`, also enter a topic.
- If you enter a full endpoint such as `https://ntfy.sh/my-topic`, leave the topic blank.
- The authorization field accepts either a full `Authorization` header value such as `Bearer <token>` or raw Basic credentials in the form `user:pass`.
- If you enter `user:pass`, the app automatically sends `Authorization: Basic <base64(user:pass)>`.

## Known Limitations

- Bluetooth battery reporting is inconsistent across accessories and Android versions.
- Disconnect detection for third-party apps is best-effort. Most headphones expose usable profile disconnect events, but some phones or accessories may behave inconsistently.
- Some devices expose battery through system broadcasts, some only through vendor-specific headset events, and some do not expose it to third-party apps at all.
- The startup snapshot uses a best-effort reflective battery probe because Android's public SDK does not expose a normal battery getter for all Bluetooth devices.
- Force-stopping the app from system settings can prevent the system from delivering future broadcasts until the app is opened again.
