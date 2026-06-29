# LibreCRDroid

> **⚠️ WARNING — for testing and research only**
> This application is not a certified medical device. Do not make medical
> decisions based on displayed values. Always verify blood glucose with a
> certified glucometer.

Kotlin/Android port of [LibreCRKit](../LibreCRKit) (Swift/iOS) — full Libre 3
protocol: NFC activation, BLE GATT transport, security handshake (Phase 5/6,
AES white-box, P-256 ECDH/ECDSA), per-minute glucose decoding, and persistent
background connection.

The protocol layer is **byte-for-byte identical to the Swift implementation**,
proven by golden-vector tests.

---

## Module architecture

### `:protocol` — pure Kotlin/JVM library
No Android dependencies. Runs as plain JUnit on any dev machine.

- **BLE framing** — 2B LE offset writes + 18B chunks; notification reassembly
- **`CRC16`** —  CRC16 + NFC commands 
- **`AesCcm`** — AES-CCM (SP800-38C), configurable tag; AES block = JCA
- **`LibAes`** — table-driven white-box AES (Phase 5/6 block primitive);
  `libaes_*.bin` tables are byte-identical copies of the Swift resources
- **`Ecc`** — P-256 ECDH + ECDSA-SHA256 via JCA
- **`SensorCert`** — 140 B parse + ECDSA verify against bundled Abbott keys
- **`PairingFlow`** — full state machine: fresh first-pair and cached reconnect
  (Phase 5 raw key caching)
- **Data plane** — `DataPlaneCrypto`, `RealtimeGlucoseReading`, `PatchStatus`,
  `HistoricalReadingPage`, `ClinicalReadingRecord`

### `:app` — Android phone application
- `BluetoothGatt` transport with serialized GATT operation queue
- Targeted BLE scanner (`autoConnect=false`, 60s cold-start scan timeout)
- BLE state machine: `CONNECTING → HANDSHAKE → ONLINE → RECONNECTING`
- `connectedDevice` foreground service — persistent background connection
- **Automatic reconnect** after BLE loss (attempt-indexed backoff:
  500ms → 2s → 2s → 5s → 10s → 20s → 30s max)
- No-data watchdog (reconnect if no reading arrives for >N minutes)
- DataStore persistence: NFC session, cached Phase 5 key, last glucose
- **Sensor errors** (`PatchStatus`) surfaced visually: `CheckSensor`,
  `SensorEnded`, `ReplaceSensor`, unknown codes
- System notifications for sensor attention states
- Structured log viewer with hex dump of every GATT operation
- Compose UI: home screen (glucose + trend + delta + status), settings, log
- **LibreView cloud upload** — each reading forwarded to Abbott's `/api/measurements`
  endpoint; cached session, lifeCount dedup, automatic re-auth on token rejection

### `:wear` — Wear OS watch application
- Direct BLE connection watch → sensor (independent of phone)
- `connectedDevice` foreground service on the watch
- **Fast reconnect** with the same attempt-indexed backoff strategy
- Watchface complications: glucose value + trend + delta + reading age
- Visual error badge on complications for sensor errors (red/yellow)
- Bidirectional phone ↔ watch sync via Wear Data Layer:
  glucose readings, sensor states, session handoff, BLE logs
- **Watch log sync** — watch's BLE log visible in the phone's Log screen
- One-button session handoff phone → watch

---

## Feature matrix

| Feature | Phone | Watch |
|---|---|---|
| BLE connection to Libre 3 | ✅ | ✅ |
| Phase 5/6 handshake (fresh first-pair) | ✅ | ✅ |
| Cached Phase 5 key reconnect | ✅ | ✅ |
| Per-minute realtime glucose | ✅ | ✅ |
| Backfill (clinical per-min + historical 5-min) | ✅ | ✅ |
| Automatic reconnect with backoff | ✅ | ✅ |
| Foreground service (background) | ✅ | ✅ |
| NFC sensor activation | ✅ | — |
| Sensor error display | ✅ | ✅ (badge) |
| System notifications | ✅ | ✅ |
| Structured hex log | ✅ | ✅ |
| Bidirectional sync | ✅ ↔ ✅ | ✅ ↔ ✅ |
| Watch log on phone | ✅ | ✅ |
| Watchface complications | — | ✅ |
| LibreView cloud upload | ✅ | — |

---

## Build and test

```sh
# Protocol golden-vector tests (byte-perfect parity with Swift):
./gradlew :protocol:test

# Debug APKs:
./gradlew :app:assembleDebug :wear:assembleDebug

# Quick Kotlin compile check:
./gradlew :app:compileDebugKotlin :wear:compileDebugKotlin
```

---

## Verification — "byte for byte"

Parity with Swift is enforced by golden vectors exported from the authoritative
Swift source:

1. **In the Swift repo:** `swift run librecr-vectors` writes
   `vectors/librecr_vectors.json` — canonical input→output pairs.
2. Copy that JSON to `protocol/src/test/resources/golden/`.
3. **`:protocol` JUnit tests** recompute every primitive and assert byte
   equality: CRC16, NFC commands, framing, AES-CCM (M4/M8), `LibAes`
   keySetup/block/phase5block, Phase 5 challenge, Phase 6 → session material,
   data-plane decrypt + glucose/patchStatus/historical/clinical parse, ECDH,
   ECDSA. Plus a `PairingFlow` loopback (cached + command-gated) exercising the
   full handshake state machine offline.

On device, `BleLog` emits a timestamped hex dump of every GATT write/notify and
each handshake stage — diff-able against an iOS capture.

---

## BLE reconnect strategy

On connection loss, the watch (and phone) reconnect automatically using an
attempt-indexed backoff:

| Attempt | Delay |
|---|---|
| 1 | 500 ms |
| 2 | 2 s |
| 3 | 2 s |
| 4 | 5 s |
| 5 | 10 s |
| 6 | 20 s |
| 7+ | 30 s (max) |

- Reconnect scan timeout: **12 seconds** (only cold start uses 60s)
- Backoff reset only after the first valid reading, not on mere GATT connect
- Single centralized funnel (`requestSensorReconnect`) — validated triggers:
  `gatt_disconnect`, `stale_fragment_timeout`, `no_data_watchdog`
- Cached Phase 5 key survives transient disconnects; discarded only on crypto
  mismatch or BLE address change

---

## NFC sensor activation

Press **Activează / preia** and hold the phone on the Libre 3 sensor. The app:

1. Reads `patchInfo` with `A1`
2. Sends `A0` (ACTIVATE) if the sensor is in storage state (`state == 1`),
   or `A8` (SWITCH_RECEIVER) to take over an already-active sensor
3. Parses the response: BLE address (bytes 3–8, reversed), BLE PIN (bytes 9–12),
   activation time (bytes 13–16)
4. Saves the `ImportedSession` automatically to DataStore — no manual JSON paste

---

## Sensor error states

`PatchStatus.sensorError` keeps normal end-of-wear separate from shutdown:

| `errorData` | Meaning | `sensorAttention` |
|---|---|---|
| 0 | Normal / check patchState | — |
| 3 | Insertion / start failure | `CheckSensor` |
| 5 | Expired (end-of-wear) | `SensorEnded` |
| 6 | Terminated | `SensorEnded` |
| 7 | Transmission error | `ReplaceSensor` |
| 8 | Terminated | `ReplaceSensor` |
| other | Unknown | `ReplaceSensor` |

Attention states are shown as a visual card on the phone's home screen and as a
colored badge on watch complications.

---

## LibreView cloud upload

Each glucose reading received from the sensor is automatically forwarded to
Abbott's **LibreView** cloud (the same backend used by LibreLink and LibreLinkUp),
enabling viewing in LibreView and sharing with followers via LibreLinkUp.

### How it works

1. The app authenticates with your LibreView credentials (`email` + `password`)
   against the regional LibreView API (`api.libreview.io` and regional mirrors).
2. A session token is cached in memory and reused across readings.
3. Each reading is posted to `/api/measurements` in the LibreLink-compatible
   format (glucose value, trend, timestamp, device serial, sensor serial).
4. The upload is serialized (one at a time), deduplicated by `lifeCount` so
   the same sensor minute is never submitted twice, and re-authenticated once
   automatically if the token is rejected (`status 20 — wrongDeviceInToken`).
5. Upload status is shown in the Settings → Cloud screen:
   uploading / last successful upload (time + mg/dL) / error.


---

## Phone ↔ watch sync

Bidirectional communication uses the Wear Data Layer (`MessageClient`) on
`/librecr/*` paths:

| Path | Direction | Content |
|---|---|---|
| `/librecr/glucose` | watch → phone | current glucose reading |
| `/librecr/glucose/replay` | watch → phone | backfill on reconnect |
| `/librecr/session` | phone → watch | NFC session handoff |
| `/librecr/sensor_status` | either → either | `errorData` + `patchState` |
| `/librecr/log/request` | phone → watch | request watch log |
| `/librecr/log` | watch → phone | watch BLE log |

---

## Status

- ✅ Protocol layer ported and byte-perfect (78 golden + loopback tests passing)
- ✅ Android BLE transport, state machine, foreground service, Compose UI
- ✅ Wear OS app with complications, reconnect, Data Layer sync
- ✅ Full NFC activation (A0/A8, response parsing, automatic session save)
- ✅ Sensor errors visible on phone and watch
- ✅ Fast reconnect with indexed backoff (max 30s, never 60s)
- ✅ Watch log visible on phone
- ✅ LibreView cloud upload (per-reading, cached session, auto re-auth)
- ⏳ On-device validation with a real sensor — diff `BleLog` against iOS capture
