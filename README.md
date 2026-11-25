# LibreCRDroid

> **⚠️ WARNING — for testing and research only**
> This application is not a certified medical device. Do not make medical
> decisions based on displayed values. Always verify blood glucose with a
> certified glucometer.
> 
**⚠️Tested only on Pixel 10 pro XL and Pixel watch 4**
> 
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
- **Glucose widgets** — Material 3 Jetpack Glance widgets in the selected unit, with
  range colors, the sensor trend in an expressive shape, rate per minute, a
  host-rendered elapsed-time clock and, when enlarged, a three-hour sparkline.
- **Android Auto notifications** — opt-in experimental CGM messaging card with a
  dedicated on/off switch, quiet refreshes and explicit stale/error states.
- **Glucose broadcasting** — opt-in local xDrip-compatible broadcasts for other
  Android apps, including the original timestamp, trend and delta per minute.

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
- Live glucose notification / Ongoing Activity: trend → value → delta/min → minutes since the
  reading, for direct BLE and phone-relayed readings. On Wear OS 7, also requests native Live
  Update promotion (subject to system settings and the watch manufacturer's implementation).
- The ongoing activity icon contains a monochrome trend arrow, glucose, delta and the reading's
  clock time. The complete text appears in the notification and launcher Recents; tiny
  watch-face indicators may only show the icon. The regular app launcher icon stays unchanged.
- The Wear OS 7 status chip shows the trend arrow, glucose and delta (two spaces on each side of
  the middle dot) and the minutes since the reading: `120  ·  -2 · <1 m.`, then `1 m.`, … An NA
  packet shows `NA · <1 m.`. Wear never re-renders this critical text, and it outranks a
  chronometer in the chip, so the app reposts each new minute. The card header shows a system
  chronometer instead, and launcher Recents a system stopwatch.
- During a reading gap, one exact while-idle alarm (`USE_EXACT_ALARM`, shared by the chip and the
  age complication) wakes the app at each new minute: 70 s after the reading (10 s of grace for a
  slightly late packet), then every minute up to `5 m.`. The foreground card and an active age
  complication add one wake-up at the 330-second expiry; relayed cards expire through their OS
  timeout. Every reading re-arms the alarm, so readings that arrive each minute cause no extra
  wake-ups. Screen wake and clock changes also catch the chip up.
- The age/delta complication shows the same `<1 m.`, `1 m.` … above the delta while the value is
  inside the 330-second window, then the reading's clock time in the stale color, which needs no
  further repaint. That line is bold with an outline, at the largest size that still clears a
  circular slot and the delta.
- Tap the watch glucose screen to enable/hide Live Updates. Explicit hiding or dismissing a
  card before its timeout is remembered across readings and restarts. Automatic expiry does
  not disable Live Updates; fresh readings restore the card. The required BLE foreground-service
  notification remains.
  Values expire after the existing 330-second display window; relayed cards also have an OS
  timeout. The existing stale-complication alarm refreshes the notification without extra alarms.

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

## Glucose broadcasting to other Android apps

Enable **Settings → Glucose broadcasting → Broadcast glucose to other apps**
(Romanian: **Setări → Transmiterea glicemiei**). This is off by default and uses
the shared phone-BLE / watch-relay data funnel. Enable xDrip broadcast reception
in the receiving app. This implements xDrip's **output** broadcast format; it
does not implement the separate protocol used to import readings into xDrip itself.

By default, any compatible app listening for the broadcast can receive the data.
To limit recipients, enter Android package names separated by spaces or commas
and tap **Save recipients**. Package-targeted broadcasts also support receivers
declared in the recipient's manifest on Android 8+. Without a destination package,
receivers should register dynamically; on Android 13+ they must be exported to
receive broadcasts from another app. No receiver permission is imposed, and no
new LibreCRDroid inbound receiver, polling, cloud connection or service is needed.

Only valid readings less than six minutes old are sent. Current unavailable/error
states suppress transmission of cached numbers. Repeated store/status updates
do not resend the same reading. Enabling sharing, changing destinations, or a
process restart sends the latest reading if it is still valid and fresh, keeping
its original timestamp. History/backfill is not broadcast as a stream. Receivers
must calculate age from the timestamp and expire old values when data stops;
there is no periodic replay or separate no-data broadcast.

The action is `com.eveningoutpost.dexdrip.BgEstimate`, matching the upstream
[xDrip intent contract](https://github.com/NightscoutFoundation/xDrip/blob/master/app/src/main/java/com/eveningoutpost/dexdrip/utilitymodels/Intents.java).
The following extras use the prefix `com.eveningoutpost.dexdrip.Extras.`:

| Suffix | Android extra type | Meaning |
|---|---|---|
| `BgEstimate` | `Double` | Glucose in **mg/dL**, including when the UI uses mmol/L |
| `Time` | `Long` | Original stored reading timestamp, Unix epoch milliseconds |
| `BgSlope` | `Double`, optional | Delta in mg/dL **per millisecond**, stored per-minute delta ÷ 60,000 |
| `BgSlopeName` | `String` | `SingleDown`, `FortyFiveDown`, `Flat`, `FortyFiveUp`, `SingleUp`, or `NOT COMPUTABLE` |
| `SourceDesc` | `String` | `LibreCRDroid (Libre 3)` |
| `Sender` | `String` | `re.abbot.librecr.app` |
| `Display.Units` | `String` | Preferred display unit: `mg/dl` or `mmol` |

LibreCRDroid also includes `re.abbot.librecr.app.Extras.LifeCount` (`Int`, sensor
minute counter), `.Trend` (`String`, original Libre trend name) and
`.DeltaMgDlPerMinute` (`Double`, optional). Missing or non-finite deltas are
omitted. Raw glucose, noise and sensor battery values are not fabricated.
Slope units and names follow the upstream
[xDrip glucose model](https://github.com/NightscoutFoundation/xDrip/blob/master/app/src/main/java/com/eveningoutpost/dexdrip/models/BgReading.java).

## BMW iDrive through AAIdrive

An experimental dedicated glucose page is available for NBT/NBT Evo Connected Apps.
Install the official [AAIdrive](https://github.com/BimmerGestalt/AAIdrive/releases)
on the same phone and first confirm its BMW/MINI connection works. Enable
**Settings → BMW iDrive → Dedicated iDrive screen** in LibreCRDroid (off by default).
Open AAIdrive, check for LibreCRDroid under **Addons**, then reconnect Connected Apps
if the phone was already connected during installation. **LibreCR · Glucose**
(localized to the app language) opens automatically once its first presentation is ready.
It opens once per connection; reading updates and transport retries do not take focus
back after you leave the page. You can reopen it from the car's Connected Apps /
ConnectedDrive menu.

The page uses a dark Material 3 Expressive inspired dashboard with rounded tonal
cards, Google Sans Rounded, a compact glucose value/trend, delta per minute and
reading age/time. A three-hour glucose chart fills the remaining card, using the
selected unit, configured target range and glucose range colors. Persisted history
is refreshed once a minute and combined with the live recent ring. Known uncapped
history values are preserved. Missing intervals of six minutes or longer are not
connected, and expired readings are not extended to the current time.

The image and chart width follow native RHMI split-screen events. The glucose card
keeps its size in both modes. **Settings → BMW iDrive → Car display** offers explicit
Full screen and Split screen choices for head units that do not report their mode.
Automatic reserves room for the native sidebar until the car reports a mode.
Changes apply immediately without reopening the page or taking focus.

Both phone BLE and watch-relayed readings use the existing shared data funnel.
An independent one-second clock hides the current number after six minutes, even
without another reading; historical chart points retain their timestamps. Warmup,
unavailable readings and sensor errors also hide the numeric value. Only changed
visible content transmits an image. Hiding the page stops image transfers.

The LibreCR page no longer binds the AAIdrive map service, displays a map, offers
navigation/search controls or requires a Google Maps SDK key. The installed
AAIdrive continues to provide the Connected Apps transport and signed OEM resources.
The existing AAIdrive installation works; no companion map update is required.
Disconnecting or disabling the feature disposes the remote app. The addon adds
no BLE connection, cloud polling, screen-capture permission or foreground service.

The addon reads the original `smartthings` RHMI layout, image database and app
certificate from the installed AAIdrive package (`me.hufman.androidautoidrive`) at
runtime. These OEM resources are not copied into this repository or APK. It uses
IDriveConnectKit 0.8 and IDriveConnectKitAndroid 0.8, resolves the BMW/MINI security
service independently, and registers its own app/menu identity. A forked or future
AAIdrive package with different assets may require adaptation. Missing resources,
authentication waits and connection retries are reported on the phone's BMW iDrive
settings page. Automatic transport retries continue while AAIdrive keeps the addon
bound. Reconnect Connected Apps after installing/updating AAIdrive or fixing missing
resources.

If LibreCRDroid appears under AAIdrive Addons but not on the dashboard, check the
status and **Connection diagnostics** in Settings → BMW iDrive. **Screen registered**
means the startup RPCs completed; it does not acknowledge that the car drew the icon.
Use **Copy diagnostics** to share the last stage/error. The custom AM launcher has
its own identity, separate from the signed RHMI application's inherited entry, so
the head unit cannot replace it by matching the RHMI app ID. Selecting the launcher
focuses the existing glucose page and re-registers the launcher to clear its loading
indicator. Reconnect Connected Apps after updating from the initial addon build.

Local tests cover units, freshness, sensor states, history merging, uncapped values,
missing intervals, RHMI focus and image delivery, and full/split chart rendering.
Native-graphics previews are written to `app/build/reports/idrive-chart-preview/`
by `IDriveGlucoseRendererTest`; preview data is synthetic.
The original layout
from the official AAIdrive 1.4.4 release was also checked locally for compatibility;
its actual vehicle authentication and display have **not** been verified on hardware.
Before relying on this experimental display, test screen-off updates, music/calls,
phone/watch handoff, expiration after six minutes without readings, disconnect and
reconnect, toggling the setting, and a physical shortcut if supported by the head unit.
Keep the phone/watch glucose alarms active; this page does not implement car alarms
or an overlay over navigation, instrument-cluster output, or HUD output.

## Glucose widgets and Android Auto

Add **LibreCR · Compact glucose** or **LibreCR · Glucose with chart** from the
phone's widget picker. No configuration is required; the widgets use the latest
accepted phone or watch-relayed reading, the unit and the target range from
Settings. Tap a widget to open LibreCR on hosts that allow launching the phone
activity.

Both providers share one responsive Material 3 layout set and differ only in their
default size. They resize from a 2×1 bar through a 2×2 tile and the compact card
up to wide and tall cards that add a three-hour sparkline (target band, out-of-range
segments colored at the exact crossing, latest reading highlighted). The reading is
colored by range; the trend arrow sits in a calm cookie shape when in range and in a
burst when out of range, so the state does not rely on color alone. Surfaces use
the launcher's corner radius and the wallpaper (Material You) palette on Android 12+
with the teal brand colors as fallback. The card and chart backgrounds are translucent,
and every color has a light and dark variant.
Text uses the system Material 3 Expressive type scale (rounded Google Sans Flex) on
Pixels that ship it, Google Sans on older Pixels and the platform font elsewhere.
Android 15+ widget pickers show a preview generated from the real layout; Android
12–14 use a static preview with the same design. Picker previews always use sample
data, never your readings.

New readings and sensor-state changes trigger updates immediately. A minute ticker
runs while widgets are placed and the application process is alive. The reading-age
clock runs in the host, independently of the app process. At the existing six-minute
freshness threshold the widget hides the value, trend and delta and labels the data
as stale. Unavailable readings, warmup and sensor errors have explicit states.
An inexact, non-wakeup alarm and Android's 30-minute widget refresh provide fallback
updates after process death; Doze may delay those updates and the stale-state change.
There is no exact-alarm permission or additional foreground service.

These are standard app widgets that follow Google's car-widget guidance and the
widget quality guidelines: built with Glance, responsive to any grid cell down to
2×1 (short car-grid rows get the one-row layout), light and dark plus dynamic
theming — colors are system color resources, so a host can apply its own palette —
full-bleed bounds, no scrolling, and secondary rows that drop out before the
reading would be cropped by a small cell or a large font scale.
If your Android Auto version offers a car-widget picker, check for these LibreCR
widgets there. Availability, placement next to Maps/Waze and visibility when another
app is fullscreen are controlled by Android Auto and must be tested on the target
vehicle. Installing this APK does not enable Google's widget rollout.

In the AARE BMW build with widget theme support, widgets use the same day/night
setting as the car maps on Android 16+. Select **Colors → Car's day/night** in AARE
to follow the signal sent by the head unit.

### Experimental Android Auto notification

Enable **Settings → Android Auto → Persistent notifications** (off by default).
Allow LibreCRDroid notifications on the phone and enable message notifications and
the first message line in Android Auto settings. For a sideloaded APK, Android Auto
may also require **Developer settings → Unknown sources** for messaging notifications;
see Google's [testing guide](https://developer.android.com/training/cars/testing).
The setup steps are also shown directly in the Android Auto settings page. A delivery
status distinguishes a dismissed card, blocked permission and a successful phone post;
**Send notification again** retries with real current CGM data and a fresh session.
Successful posting on the phone is not an acknowledgement that the car displayed it.

The implementation declares the `notification` car capability and posts a single
`MessagingStyle` notification with reply/mark-as-read actions, using
[`CarAppExtender`](https://developer.android.com/reference/androidx/car/app/notification/CarAppExtender)
and `CarNotificationManager`. It follows the transport used by
[GlucoDataAuto](https://github.com/pachi81/GlucoDataAuto). The reply action only refreshes
local content; no voice reply is stored or sent. This does not create a separate car
app, a media session, an overlay, or a navigation template.

The card contains mg/dL, the sensor trend arrow, delta in mg/dL/min, elapsed reading
age and the original measurement time. Shared widget freshness rules hide obsolete
values after six minutes and handle unavailable readings, warmup and sensor errors.
Each accepted phone or watch-relayed update refreshes the card; a 60-second keepalive
also updates its age while the application process is running and Android Auto is
connected. The transport timestamp is refreshed for the car card, while the displayed
measurement timestamp and age always refer to the real reading. Doze or process death
can delay refreshes; this feature adds no foreground service or exact alarm.

Only the first post in a connection requests car heads-up importance; it may briefly
show a banner. Later refreshes use default car importance, a quiet phone channel and
the same notification ID. The channel has HIGH importance with no sound/vibration,
matching the GlucoDataAuto transport. Upgrades migrate the original LOW channel to a
new channel because Android cannot raise an existing channel's importance in place;
explicit user importance/blocking choices are preserved. The notification uses a
dismissible incoming-message envelope. Turning the switch off or disconnecting cancels the card.
Closing/marking it read hides it for the rest of the connection; reconnecting or
turning the option off/on allows it again. Dismissal is retained across process
restarts within the same phone boot. If an entire disconnect/reconnect happens while
the process is dead, a retained dismissal may require toggling the switch again.
[`CarConnection`](https://developer.android.com/reference/androidx/car/app/connection/CarConnection)
gates posting on phone projection only; Android Automotive OS native connections do
not enable it.

Android Auto decides the card's appearance, truncation, dashboard placement and
lifetime. Reposting a message **does not guarantee permanent visibility beside
Maps/Waze**. The Material 3 settings preview illustrates content,
not a custom car layout. Test this feature on the intended host before relying on
its visibility.

Google's [notification API](https://developer.android.com/training/cars/communication/notification-messaging)
is intended for actual messaging apps. This CGM-only use does not meet the
[car quality requirement MF-5](https://developer.android.com/docs/quality-guidelines/car-app-quality)
for a peer-to-peer messaging service, so it must not be presented as an approved
Google Play Android Auto integration. Keep this distinction when testing a sideloaded
build or preparing a Play release.

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

A Libre 3 that has lost its receiver is connectable for only a few seconds per
minute, so the watch never leaves a gap in which that slot can pass unanswered:

- Any loss of an established link, whatever GATT status reports it, is followed
  by a controller-owned connect (`autoConnect=true`) with no app-side deadline.
  It keeps listening through Doze and retries a failed establishment inside the
  same slot. Wear OS 7 reports the link-supervision timeout as status **147**
  where earlier releases reported **8**; both mean the radio link was lost.
- On the watch the pause before that connect is capped at 5 s and held under a
  wake lock for the first six attempts (500 ms → 2 s → 2 s → 5 s → 5 s → 5 s);
  the table above applies from the seventh attempt and to the phone.
- A direct connect is used only for the cold first attempt and once after a
  controller connect failed (62/133) before any link came up.

- Reconnect scan timeout: **12 seconds** (only cold start uses 60s)
- Backoff reset only after the first valid reading, not on mere GATT connect
  (on the watch any decrypted realtime packet counts, so warm-up and sensor-error
  periods without a value do not use up the exact re-arms)
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

## Phone priority with automatic watch takeover

The phone's **Settings → Libre 3 connection → Phone priority, watch backup**
switch is off by default. Enabling it prefers the phone and prepares the selected
watch for autonomous takeover. The watch confirms both sensor provisioning and a
running `connectedDevice` foreground service before the phone reports standby as
ready. An older watch app, missing Bluetooth permissions, or a denied service
start leaves the setting pending. Open the watch app to grant its Bluetooth
permissions if needed. Both apps must be updated for this mode.

While the phone collects, the watch keeps that same foreground service running
with its glucose notification, without opening a sensor GATT connection. When
the phone disconnects, the watch starts BLE collection inside the already running
service: takeover does not need to launch another foreground service from the
background or open the watch screen. Manifest-registered phone capability changes
and peer callbacks trigger a wakeful check;
15-second presence checks provide a fallback and notice a switch to a cloud-only
Data Layer connection. Cloud connectivity does not count as a nearby phone.
On a genuine local GATT disconnect, the phone sends a message-only link-loss hint
to wake the watch. The watch validates the sender, sensor, freshness and packet
boundary, then queries current phone health before attempting takeover; a healthy
phone or a valid startup claim is preserved. This avoids waiting three minutes
when the phone–watch link still works. Deliberate stops and manual transfers do
not emit that hint. If a hint cannot arrive, the existing three-minute silence
fallback and bounded 150-second first-packet claim remain.

Delayed phone packets from before the loss cannot cancel the watch's recovery:
the hint's life-count/time boundary is saved. A genuinely newer phone packet can
park a pending watch loop only after a current status confirms healthy phone
collection. Manual cancellation and watch recovery share one coordinator mutex.

When the watch observes an absent phone become nearby again, it can initiate the return immediately;
there is no five-reading wait. The existing sequenced release/acknowledgement and
reconnect-key exchange complete before phone collection starts. A current watch
status overrides delayed relay packets, preventing old data from restarting a
completed transfer. A fresh watch reading confirms fallback ownership and stops
the phone's stale BLE loop; it never forces an immediate return. The phone–watch
Bluetooth link can outlive the phone–sensor link, so receiving a watch relay does
not prove the phone can reach the sensor again. The watch's return cooldown is
preserved through repeated presence callbacks and cleared by an observed absent
to nearby transition. When Bluetooth never disconnects, the watch also schedules
bounded return trials after a fresh local reading and a persisted cooldown.
Trials wait 3 minutes initially, then back off through 6, 12, 24 and 30 minutes
after unsuccessful transfers. This replaces the old requirement to observe a
Bluetooth departure before returning automatically.

A trial requires a ready watch and an acknowledged release. Only a direct phone
sensor reading beyond the watch's final life count confirms the transfer. The
phone has a fixed 150-second window, preserved across restart. Failure stops its
BLE loop before restoring the watch through the sequenced key exchange. The
watch also saves a 180-second release lease so it can recover independently if
the phone or the acknowledgement disappears; renewed startup claims cannot keep
it parked after that lease. A single phone reading does not reset the failure
backoff: three minutes of continuing phone packets are required. Critical watch
battery can still request the phone without a return trial.
A failed presence query is unknown, not a departure/reappearance. A silent/dead
fallback watch remains recoverable through the phone's liveness checks.
Force-stopping an app prevents its background recovery until
it is opened again. Android can defer inexact alarms in Doze, and the sensor's
advertising schedule controls actual reconnection timing; a zero-gap radio
transfer is not guaranteed.

Turning the setting off keeps the current collector. The existing manual route
switch works in both directions and turns automatic mode off, so a manual choice
is preserved. With automatic mode off, a manually parked watch stays parked.
Standby intent survives process death and reboot; the watch checks a live phone
before restoring its own BLE loop. Separate peer-key backups remain unchanged.

JVM tests cover phone priority, background-standby decisions, powered-off/absent
phones, cloud-only reachability, automatic return, disabled mode, failed/lost
release acknowledgements, bounded return trials and rollback, first-packet claims,
and restart recovery. Real radio
and Wear OS background/Doze behavior still require a paired phone and watch.

### Legacy watch-first recovery protocol

The earlier watch-first coordinator and watch compatibility path remain in the
codebase for existing/manual transfer recovery and older companion settings. The
new phone-priority switch does not enable that coordinator's automatic policy.
The following describes the retained watch-first protocol and its tests.

Three failed watch reconnects request phone takeover. Independently, three
minutes without a new live sensor packet, plus a 20-second grace, cause the phone
to query the watch and, if no healthy receiver answers, try the sensor itself.
The grace keeps that check off the sensor's minute grid: readings and reconnect
advertisements share one second of every minute, so a check at exactly three
minutes lands on the instant a recovering watch re-establishes its link, before
its first new reading. A watch whose own sensor link came back within the last
45 seconds (and that had a reading within the last six minutes) reports a short
reconnect claim and refuses automatic releases until it ends; the phone checks
again when the claim expires. A dead watch is never required to acknowledge a
release. A healthy watch reported by a fresh status
response is left collecting, even if its relayed values stopped reaching the phone.
Both receivers use a replaceable wakeup alarm alongside the foreground monitor:
normal live packets postpone the alarm, rather than causing periodic wakeups.
The three-minute threshold is a target; Android may defer inexact alarms in Doze.
Unusable live sensor readings still prove the link is alive; historical replay,
duplicate values and delayed DataItems do not renew liveness.

A service-start acknowledgement is only a launch acknowledgement. The new
collector must deliver a fresh sensor packet, or a fresh status proving that it
has done so, within a 150-second grace period. An interrupted transfer is stored
and recovered after process restart. New commands carry a persisted sequence and
token; obsolete commands cannot restart a cancelled transfer.

After five usable readings in adjacent sensor minutes, the phone can return to
the original watch. The watch must be nearby and ready, with at least 15% battery
(or charging above 5%). At 5% or lower, an uncharged watch may proactively request
the phone. If the phone also loses the sensor, it can try an available watch
without waiting for five impossible readings. Failed watch transfers introduce
progressively longer pauses (up to 30 minutes) instead of oscillating indefinitely.

The watch keeps collecting when the phone is absent. Its reconnects use Android's
persistent controller connection from the first one after a lost link, with no
20-second bound in any mode. The phone also uses a passive controller wait
without holding a wake lock throughout the wait. If the wearer leaves both
receivers behind, neither can receive live readings while the sensor is out of
range; reconnection/backfill resumes when a receiver can reach it again.

Both receivers retain a separate backup of the peer's reusable reconnect key for
the same sensor. Receiving a backup never modifies a running BLE session, and an
older peer key cannot replace a newer proven local key. Per-link encryption
material is never backed up. A watch reboot checks a live phone before starting
BLE; an abandoned automatic release can recover on the watch without an ACK.
While automatically parked, the watch keeps its own foreground recovery monitor,
including after reboot, so a failed phone does not strand it. An explicit manual phone takeover parks the watch until another user-directed
handoff, and remains available with the automatic switch off.

Validation includes deterministic coordinator tests for peer loss, a dead watch,
all receivers out of range, low battery, stale/duplicate/wrong-sensor packets,
lost acknowledgements, service-start failure, transfer deadlines, and process
restart. Real-device validation must additionally cover a powered-off watch,
Android Doze/background starts, physical RF loss, key synchronization while
separated, and recovery after recharging. Do not infer a successful radio
reconnection from a passing JVM state-machine test.
