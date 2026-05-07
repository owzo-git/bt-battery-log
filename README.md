# BT Battery Log

BT Battery Log is a lightweight Android utility that tracks supported Bluetooth headset battery levels and shows local Android notifications only.

No `ntfy` server, endpoint, topic, or network setup is required.

## What It Does

- Watches Bluetooth battery updates from Android Bluetooth broadcasts and headset vendor events when available.
- Focuses on headphone-style Bluetooth audio devices.
- While a supported headset is connected and reports battery:
  - Shows one persistent ongoing notification.
  - Reuses the same notification ID and updates it in place as battery changes.
- When the headset disconnects and stays disconnected after a short delay:
  - Converts that same notification into a dismissible disconnect summary.
  - Keeps the summary until you swipe it away.
- Deduplicates repeated disconnect signals so duplicate summaries are suppressed.
- Restores monitoring behavior after reboot if monitoring is enabled.

## App Settings

- Monitoring enabled/disabled
- Disconnect delay seconds
- Refresh now
- Clear debug logs

The app also shows recent events for lightweight debugging.

## Android Permissions Used

- `android.permission.RECEIVE_BOOT_COMPLETED`
  Re-arms monitoring behavior after device reboot.

- `android.permission.BLUETOOTH_CONNECT`
  Needed on modern Android versions to read Bluetooth device details and battery-related events.

- `android.permission.BLUETOOTH` and `android.permission.BLUETOOTH_ADMIN` with `maxSdkVersion="30"`
  Included for compatibility with older Android releases.

- `android.permission.POST_NOTIFICATIONS`
  Allows Android notifications on Android 13+.

## Build

```bash
./gradlew assembleDebug
```

## Install

```bash
./gradlew installDebug
```

## Known Limitations

- Bluetooth battery reporting depends on whether the phone + headset expose battery data to third-party apps.
- Disconnect detection for third-party apps is best-effort. Most headphones expose usable profile disconnect events, but behavior varies by phone/accessory.
- Some devices expose battery through system broadcasts, some only through vendor-specific headset events, and some do not expose it at all.
- Force-stopping the app from system settings can prevent future broadcasts until the app is opened again.
