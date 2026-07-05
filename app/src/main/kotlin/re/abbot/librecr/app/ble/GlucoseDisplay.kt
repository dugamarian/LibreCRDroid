package re.abbot.librecr.app.ble

/**
 * Shared glucose display-state helpers, used by every surface that renders a reading (Home, standby,
 * AOD/floating overlays, the foreground-service notification). Kept next to [ConnectionState] and
 * [GlucoseUi] so all consumers agree on "when is a value unavailable" — previously each surface had
 * its own private copy that could silently drift apart.
 */
internal enum class GlucoseDisplayStatus { NORMAL, OUT_OF_RANGE, SENSOR_ERROR }

/**
 * The connection is between readings (scanning / connecting / handshaking / reconnecting / error),
 * so there is no live value to show — the UI should surface "out of range" instead of a stale one.
 */
internal fun ConnectionState.isUnavailableForGlucoseDisplay(): Boolean =
    this == ConnectionState.SCANNING ||
        this == ConnectionState.CONNECTING ||
        this == ConnectionState.HANDSHAKING ||
        this == ConnectionState.RECONNECTING ||
        this == ConnectionState.ERROR
