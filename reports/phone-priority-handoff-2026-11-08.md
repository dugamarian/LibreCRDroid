# Phone priority and autonomous watch takeover

The existing opt-in switch in the phone's sensor settings now prefers the phone.
The default remains off. Both the phone and watch APKs must be updated.

## Behavior

- The phone keeps its current sensor connection when priority is enabled. Preparing
  the watch does not restart an existing phone BLE loop or replace its reconnect key.
- Before standby is reported ready, the selected watch receives matching sensor
  provisioning and confirms an active `connectedDevice` foreground service. A
  missing permission, unsupported companion, or rejected background start leaves
  readiness pending in the phone UI. A pending preparation can finish when the
  user opens the watch app and grants permissions.
- The watch keeps the same service alive while parked. Phone capability events
  registered in the watch manifest, plus peer callbacks, request an immediate
  wakeful presence check. A 15-second timer provides a fallback and checks direct
  proximity even when the Data Layer remains connected through the cloud.
- When the phone disappears, the watch starts its sensor loop in the existing
  foreground service. It does not need an activity or another service launch.
  If the phone remains nearby but stops producing live packets, the existing
  three-minute silence threshold and bounded first-packet claim apply.
- On return, the phone requests a sequenced watch release, receives its current
  reusable reconnect key, and resumes collection. Its foreground service also
  stays alive through automatic transfers. No five-reading return streak is
  required. Current watch ownership overrides delayed relay packets.
- Repeated failed phone connection attempts introduce a cooldown while the watch
  collects; a new phone-presence event permits another immediate return attempt.
- Disabling automatic mode keeps the current collector. Choosing either manual
  route turns automatic mode off so that the manual selection persists. Manual
  phone takeover parks the watch without autonomous recovery.
- Saved standby intent and existing first-packet/release state survive restart.
  An owned local loop is restored after service recreation; a parked watch checks
  the phone before opening BLE. Peer backups remain separate from live sessions.

## Verification

Final builds and all JVM suites passed using Android Studio's bundled Java 21
(the existing API 36 Robolectric tests require it):

| Suite | Passed | Failed / skipped |
| --- | ---: | ---: |
| Protocol | 176 | 0 / 0 |
| Phone | 139 | 0 / 0 |
| Watch | 63 | 0 / 0 |

The protocol total includes 19 new phone-priority tests: healthy watch release,
existing phone session preservation, automatic return after absence, persisted
ownership, refused and lost release acknowledgements, launch/stop failures,
wrong-sensor rejection, disabled standby, direct versus cloud-only proximity,
silence, and bounded phone startup claims. Existing manual and watch-first
recovery tests still pass.

`PhonePriorityStandbyTest` also passed on the Wear OS 7/API 37 emulator. It verifies
the manifest's phone capability listener, prepares a real foreground service,
confirms standby opens no BLE loop, sends the activity to the background, and
starts the BLE connection attempt against a synthetic unreachable sensor.
The supplied context rejects every new service/foreground-service launch, so
the test would fail if takeover tried to start another service. The foreground
notification remains registered during collection. This test does not prove a
physical sensor handshake or paired-device Data Layer delivery.

Both debug APKs and the watch instrumentation APK built successfully.
`git diff --check` passed.

Full lint remains blocked by existing errors outside this change: six phone
widget errors (`LocalContextConfigurationRead` and five `UseAppTint` findings)
and the watch sizing test's `UseSdkSuppress` finding. No lint error points to the
handoff implementation.

## Physical validation still needed

There was no connected physical phone/watch pair or sensor. Validate power-off
and physical phone departure, automatic return, actual key exchange while
separated, Doze, reboot, and each manual route on the user's devices. Triggering
takeover promptly does not guarantee an uninterrupted radio connection: Wear OS
can defer inexact alarms, and the sensor must become available for reconnection.
Force-stopping an application prevents its background recovery until reopened.

## Platform references

- [Android BLE background communication](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [Foreground service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Wear Data Layer capability events and manifest listeners](https://developer.android.com/training/wearables/data/events)

## APKs

- `app/build/outputs/apk/debug/app-debug.apk`
- `wear/build/outputs/apk/debug/wear-debug.apk`
