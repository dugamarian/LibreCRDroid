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
 * Viewer emissions are *coalesced*: a burst of log lines collapses into at most
 * one snapshot copy per [PUBLISH_INTERVAL_MS], instead of an O(n) `toList()` +
 * StateFlow emission on every single line — important on the battery-critical
 * watch, where the BLE-callback path logs dozens of lines per glucose minute.
 */
object BleLog {
    private const val TAG = "LibreCR"
    private const val MAX_LINES = 2000
    private const val PUBLISH_INTERVAL_MS = 250L

    private val lines = ArrayDeque<String>()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Conflated flush signal: many log() calls collapse into a single pending flush that the
    // consumer drains after PUBLISH_INTERVAL_MS, so snapshot copies are throttled during bursts.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flush = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in flush) {
                _log.value = snapshot()
                delay(PUBLISH_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun log(message: String) {
        val line = "${timeFmt.format(Date())} $message"
        Log.d(TAG, line)
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        flush.trySend(Unit)
    }

    /** Current ring-buffer contents, oldest→newest. Used to ship the watch log to the phone viewer. */
    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    fun hex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) sb.append("%02x".format(b.toInt() and 0xff))
        return sb.toString()
    }

    @Synchronized
    fun clear() {
        lines.clear()
        _log.value = emptyList()
    }
}
