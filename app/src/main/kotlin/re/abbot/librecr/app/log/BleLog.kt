package re.abbot.librecr.app.log

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured, hex-dumping logger — the Android equivalent of Swift `BLETiming`
 * + the per-step `eventLogger` closures. Every BLE op and handshake stage is
 * timestamped and kept in an in-memory ring buffer for the in-app log viewer,
 * so a live session can be diffed byte-for-byte against an iOS capture.
 *
 * Viewer emissions are *coalesced*: a burst of log lines (the hot BLE-callback
 * path produces dozens per glucose minute) collapses into at most one snapshot
 * copy per [PUBLISH_INTERVAL_MS], instead of an O(n) `toList()` + StateFlow
 * emission on every single line. When idle the flush loops suspend on the
 * channel (zero CPU).
 */
object BleLog {
    private const val TAG = "LibreCR"
    private const val MAX_LINES = 2000
    private const val PUBLISH_INTERVAL_MS = 250L

    private val phoneLines = ArrayDeque<String>()
    private val watchLines = ArrayDeque<String>()

    private val _phoneLog = MutableStateFlow<List<String>>(emptyList())
    val phoneLog: StateFlow<List<String>> = _phoneLog

    private val _watchLog = MutableStateFlow<List<String>>(emptyList())
    val watchLog: StateFlow<List<String>> = _watchLog

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Conflated flush signals: many log() calls collapse into a single pending flush that the
    // consumer drains after PUBLISH_INTERVAL_MS, so snapshot copies are throttled during bursts.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val phoneFlush = Channel<Unit>(Channel.CONFLATED)
    private val watchFlush = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in phoneFlush) {
                _phoneLog.value = snapshotPhone()
                delay(PUBLISH_INTERVAL_MS)
            }
        }
        scope.launch {
            for (ignored in watchFlush) {
                _watchLog.value = snapshotWatch()
                delay(PUBLISH_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun log(message: String) {
        if (!_enabled.value) return
        val line = "${timeFmt.format(Date())} $message"
        Log.d(TAG, line)
        phoneLines.addLast(line)
        while (phoneLines.size > MAX_LINES) phoneLines.removeFirst()
        phoneFlush.trySend(Unit)
    }

    /**
     * Fold in a block of log lines pulled from another device (the watch) into the same viewer.
     * Each incoming line already carries its own timestamp, so it is kept verbatim and only prefixed
     * with [source] so it's visually distinct. Ingested regardless of [enabled] (it's a manual pull).
     */
    @Synchronized
    fun ingestRemote(remoteLines: List<String>, source: String = "WATCH") {
        if (remoteLines.isEmpty()) return
        watchLines.addLast("──── $source log (${remoteLines.size} lines) @ ${timeFmt.format(Date())} ────")
        for (l in remoteLines) watchLines.addLast("[$source] $l")
        while (watchLines.size > MAX_LINES) watchLines.removeFirst()
        watchFlush.trySend(Unit)
    }

    fun hex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) sb.append("%02x".format(b.toInt() and 0xff))
        return sb.toString()
    }

    @Synchronized
    private fun snapshotPhone(): List<String> = phoneLines.toList()

    @Synchronized
    private fun snapshotWatch(): List<String> = watchLines.toList()

    @Synchronized
    fun clearPhone() {
        phoneLines.clear()
        _phoneLog.value = emptyList()
    }

    @Synchronized
    fun clearWatch() {
        watchLines.clear()
        _watchLog.value = emptyList()
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
    }
}
