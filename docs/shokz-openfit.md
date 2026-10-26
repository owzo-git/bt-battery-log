# Shokz OpenFit 2+ battery (experimental)

Status: issue #1 — **Milestone A** (transport) validated on Android 15; **Milestone B** (opt-in
left/right/case in the normal app flow) implemented, on-device check pending.

The app can read left bud, right bud and charging case battery from a connected Shokz OpenFit over
the vendor RFCOMM service (SDP UUID `0000fef0-0000-1000-8000-00805f9b34fb`), using only public
Android APIs (`createRfcommSocketToServiceRecord`). The protocol comes from
[shokz-openfit-battery-sdk](https://github.com/YentlHendrickx/shokz-openfit-battery-sdk) (MIT, Linux).

Milestone B feeds these readings into the status card, the connected notification and the disconnect
summary when the user turns on **Settings → Shokz OpenFit left/right/case** (off by default). The
manual **Read Shokz battery** button in the debug section stays available.

## Milestone B: opt-in readings

**When the app reads** (`ShokzReadPolicy`, all automatic reads also need monitoring on):

| Trigger | Rule |
|---|---|
| OpenFit connects (or monitoring/setting turned on while connected) | one read ~5 s later; repeated connect events within 60 s share it; skipped once this connection already has a successful read |
| Android reports a new single battery % | read only if the last attempt was ≥ 30 min ago |
| **Refresh now** (setting on) / **Read Shokz battery** (debug, any setting) | always reads, even after a refused channel (user asked) |
| any read fails with `RFCOMM connect failed` | no more automatic reads until the headset disconnects and reconnects |
| headset disconnected | queued read is cancelled; `read()` refuses disconnected devices, so no socket is opened |

The policy check and the "attempt started" timestamp are written together under the preferences
lock, so two workers can't both open the channel. Queued reads are unique per device (`shokz:<address>`,
`KEEP`). There is never a persistent socket.

**What is stored** (per device, SharedPreferences): left, right and case each with their own sample
time; the connection start time; last attempt / last success; a "blocked this connection" flag. A
partial read updates only the parts it returned. The case is never derived from the buds, and the
single Android percent never overwrites or clears these values.

**Fresh vs last-known vs unknown.** A part is *fresh* when it was sampled during the current
connection. The connected notification shows only fresh parts:
`OpenFit 2+ — L 100% · R 100% · Case 60%`, with `—` for parts not read this connection. Before the
first successful read (or if every read fails) it keeps the old `OpenFit 2+ — 80%` text. The status
card shows `Left … · Right … · Case …` with the read time while connected, or
`Last known: …` with its time otherwise. Unknown is always `—`, never 0%.

**Disconnect summary.** If parts were read during the connection, a second line is added:
`Before disconnect (14:05): L 100% · R 100% · Case 60%`. The summary signature (used for duplicate
suppression and dismissal) is unchanged, as are the notification ID and foreground service.

### Milestone B on-device checklist

| # | Scenario | Expected |
|---|---|---|
| B1 | Setting off, OpenFit connects | no vendor read in debug log; notification `OpenFit 2+ — XX%` |
| B2 | Setting on, Shokz app stopped, OpenFit connects | one `vendor read ok (connected)` ~5 s after connect; notification shows L/R/Case |
| B3 | Setting on, Shokz app connected, OpenFit connects | one `vendor read failed (connected) … paused until the headset reconnects`; no further automatic reads; notification stays `XX%` |
| B4 | Disconnect after B2 | summary has `Before disconnect (HH:mm): …`; status card shows `Last known: …` |
| B5 | Refresh now while connected | one manual read, notification updated |

## How to test

1. Enable monitoring (grants `BLUETOOTH_CONNECT`) and connect the OpenFit 2+.
2. Main screen → **Show debug log** → **Read Shokz battery**.
3. Compare with the official Shokz app. Buds have 10% resolution; the case is exact.

Run each scenario and record the result:

| # | Scenario | Expected |
|---|---|---|
| a | Shokz app force-stopped | L/R/Case values |
| b | Shokz app open / running in background | `RFCOMM connect failed`, fast and clean |
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
| 2026-10-26 | Sony XQ-DC54 / 15 | a | L 100% · R 100% · Case 60% — matches official app | 1327–1501 ms | 3 runs, `rx=484B`, all 7 polled opcodes plus `0x31` (init) answered; `0x03=00090900`, `0x0B=003c0000` |
| 2026-10-26 | Sony XQ-DC54 / 15 | b | `RFCOMM connect failed` | 271–286 ms | **Kicks the official app off the headset** — see below |
| 2026-10-26 | Sony XQ-DC54 / 15 | d | one read, then a later one OK | 1501 ms | button is disabled while reading, so a double tap starts one read |
| 2026-10-26 | Sony XQ-DC54 / 15 | c | L 100% · R 100% · Case 60% | 1418 ms | audiobook playing; no audible glitch, player stayed `PLAYING` |
| 2026-10-26 | Sony XQ-DC54 / 15 | e, right bud in case | L 100% · R 100% · Case 60% | 1709 ms | payload unchanged (`00090900`); a full bud in the case still reports 9, so in-case state is not visible from `0x03` |
| 2026-10-26 | Sony XQ-DC54 / 15 | e, both buds in closed case | `No connected OpenFit found.` | — | headset disconnected (ACL) ~15 s before; no socket was opened, no stale values shown |

### Finding: a failed read disconnects the official Shokz app

With the Shokz app connected (showing L/R/Case), one **failed** read from this app made the Shokz
app switch to "Device disconnected. Attempting to reconnect…" and stay there for over a minute.
Control run without our read: the Shokz app stayed connected. The audio link was not affected, and
force-stopping and reopening the Shokz app restored it. A successful read while the Shokz app is
stopped does not stop the Shokz app connecting later.

So the headset seems to drop its existing vendor RFCOMM session when a second client connects.
Implications for Milestone B:

- Background polling must not run while the Shokz app may be connected, or it will repeatedly kick
  the Shokz app off. Android does not let us see whether another app is running or holds the channel.
- Safest default: Shokz reads stay opt-in (setting off by default), with a clear warning, and are
  done at most once per connect plus rarely after that. After a `CONNECT_FAILED`, back off for the
  rest of the session instead of retrying.
