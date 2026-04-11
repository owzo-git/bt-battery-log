# BT Battery Ntfy

BT Battery Ntfy is a minimal Android utility app that listens for Bluetooth accessory battery updates and sends an `ntfy` POST when a connected device reports battery at or below a configured threshold.

The app stores:

- ntfy settings and thresholds
- whether monitoring is enabled
- per-device last notified battery and timestamp
- a cooldown timestamp used to suppress duplicate sends
- recent debug log lines

## What It Does

- Watches Bluetooth battery updates using Android Bluetooth broadcasts where available.
- Ignores devices that do not expose a battery percentage.
- Sends an ntfy alert when a device battery is at or below the configured threshold, then suppresses repeats for that device until the cooldown expires.
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
- Some devices expose battery through system broadcasts, some only through vendor-specific headset events, and some do not expose it to third-party apps at all.
- The startup snapshot uses a best-effort reflective battery probe because Android's public SDK does not expose a normal battery getter for all Bluetooth devices.
- Force-stopping the app from system settings can prevent the system from delivering future broadcasts until the app is opened again.
