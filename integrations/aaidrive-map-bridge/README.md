# AAIdrive Google Maps companion bridge

Historical integration: the current GlucoRitm iDrive page has returned to the
three-hour glucose chart and no longer uses this bridge or its navigation controls.
These companion sources remain available as a reference; the AAIdrive app itself
does not need to be changed to use the restored glucose chart.

Provides the Google Maps card, native iDrive destination search and route controls
inside LibreCRDroid. Install both updated APKs, enable Maps in AAIdrive, and
reconnect Connected Apps. Choose the gmap variant of AAIdrive.

On the LibreCR glucose page, select **Search destination** with the iDrive
controller. Enter two or more characters to receive up to five Google Places
suggestions. Select a suggestion to calculate a driving route and return to the
map. **Navigation · options** provides a new search, recalculation, stop, and
return to map. The banner shows the destination and the calculated route's total
distance and duration. These totals are not a live remaining-distance estimate.
There are no spoken directions or turn-by-turn instructions.

The glucose card and its freshness clock continue independently. Full/split
viewport behavior and AAIdrive's widescreen preference are retained. The map
follows car CDS location and shares AAIdrive's route. Directions requests happen
when the user starts or recalculates a route, not for each map frame. Superseded
or cancelled Directions callbacks cannot restore an old route.

## Companion sources

The integration is based on AAIdrive `3e93419` (1.4.4-2). The complete companion
patch includes the bridge, search/error handling, route status, cancellation and
tests. It does not contain credentials or OEM signed resources. On a compatible
clean AAIdrive checkout:

```sh
git apply --check /path/to/aaidrive-map-bridge.patch
git apply /path/to/aaidrive-map-bridge.patch
./gradlew :app:testGmapNonalyticsFullDebugUnitTest \
  --tests '*LibreCRMap*Test' --tests '*GMapsNavControllerTest' \
  --tests '*GMapsPlaceSearchTest' --tests 'me.hufman.androidautoidrive.MapAppTest' \
  :app:assembleGmapNonalyticsFullDebug -x :app:generateApkChecksums
```

`prepare_patch.py /path/to/clean/AAIdrive` regenerates the patch without changing
that checkout. Overlay sources match the local AAIdrive build. The legacy
checksum task is excluded because combining build and test tasks triggers its
unrelated implicit-dependency error; delivered APK checksums are generated separately.

## Protocol and lifecycle

Protocol version 1 retains REQUEST_FRAME=1 and FRAME=2. Frames advertise
`controlsVersion=1`, `navigationStatus`, `destinationName`, `distanceMeters` and
`durationSeconds`. Existing clients can ignore these optional fields. New clients
show an update message when an older bridge lacks command support.

Commands are SEARCH=3 (query), NAVIGATE=5 (placeId), STOP_NAVIGATION=7 and
RECALCULATE=8. Replies are RESULTS=4 and NAVIGATION=6, with version, requestId and
commandStatus. Search replies contain at most five bundles with id/name/address.
Navigation accepts only a place from the current suggestions. Command errors,
missing car GPS and timeouts have visible recovery states. Searches are debounced
by 300 ms and stale/spoofed replies are ignored.

The explicit service checks the caller's UID against `re.abbot.librecr.app`.
LibreCR checks AAIdrive's UID, protocol version, command/frame request IDs, JPEG
size/header and decoded dimensions. Neither glucose nor SDK credentials cross
this map boundary. Native map/search/menu page transitions retain the connection;
leaving these pages unbinds after 300 ms. The companion has a five-second idle
watchdog and releases the display, reader, route observer and CDS subscriptions.

The map renderer bypasses AAIdrive's own sidebar inset because LibreCR already
reserves the native sidebar. Automatic mode follows the car's SPLITSCREEN event,
then AAIdrive's widescreen preference. Explicit LibreCR Full/Split modes override
that choice; only the map grows, preserving the glucose card's size.

Local tests cover native controls, command IDs/UIDs, search debounce, cancellation,
route totals, visibility, viewport behavior and localized banners. Real Places
and Directions availability depends on AAIdrive's configured Google key. Actual
vehicle display, attribution, GPS, input and route behavior still need a car test.
