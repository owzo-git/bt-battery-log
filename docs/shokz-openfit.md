# Shokz OpenFit 2+ battery (experimental)

Status: **Milestone A spike** for issue #1 — transport not yet validated on Android.

The app can read left bud, right bud and charging case battery from a connected Shokz OpenFit over
the vendor RFCOMM service (SDP UUID `0000fef0-0000-1000-8000-00805f9b34fb`), using only public
Android APIs (`createRfcommSocketToServiceRecord`). The protocol comes from
[shokz-openfit-battery-sdk](https://github.com/YentlHendrickx/shokz-openfit-battery-sdk) (MIT, Linux).

For now the read is manual and only shown in the debug section; it does not feed notifications.

## How to test

1. Enable monitoring (grants `BLUETOOTH_CONNECT`) and connect the OpenFit 2+.
2. Main screen → **Show debug log** → **Read Shokz battery**.
3. Compare with the official Shokz app. Buds have 10% resolution; the case is exact.

Run each scenario and record the result:

| # | Scenario | Expected |
|---|---|---|
| a | Shokz app force-stopped | L/R/Case values |
| b | Shokz app open / running in background | likely `RFCOMM connect failed` (channel is single-client) |
| c | During music playback (Shokz app stopped) | values, no audible glitch |
| d | Read pressed twice quickly | second read waits, both finish, no error |
| e | Buds in closed case / one bud out | missing parts shown as `—`, never 0% |

The result box shows timing and a short diagnostic (`rx` bytes, opcodes answered, battery payload
hex). Copy the payload hex from a successful run; it becomes a real test fixture for Milestone B.
No MAC addresses or full frames are logged.

## Implementation notes

- One short-lived socket per read: connect → 2 init frames → 7 polling frames → close.
- `BluetoothSocket` has no timeouts, so a watchdog closes the socket after 6 s (connect) / 15 s
  (whole read). Reads poll `available()` instead of blocking.
- Reads are serialized per device with a mutex.
- Only bonded devices named `*openfit*` that report as connected are queried, so the app never pages
  a headset that is off. `cancelDiscovery()` is not called because it needs `BLUETOOTH_SCAN`.
- No new permissions.

## Test results

| Date | Phone / Android | Scenario | Result | Read time | Notes |
|---|---|---|---|---|---|
| | | | | | |
