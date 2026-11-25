package re.abbot.librecr.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.stats.GlucoseSample
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Imports a FreeStyle LibreView CSV export into the persistent glucose history so it feeds the
 * Statistics screen. The export has two header rows, then columns:
 * `2 = Device Timestamp`, `3 = Record Type`, `4 = Historic Glucose mg/dL`, `5 = Scan Glucose mg/dL`.
 * Record type 0 = historic (15-min), 1 = scan. Returns the number of new readings stored.
 *
 * Timestamps are device-local (e.g. `10-21-2022 11:46 PM`); parsed in the phone's time zone, which
 * matches how live readings are timestamped.
 */
suspend fun importLibreViewCsv(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
    val formats = libreViewDateFormats()
    val samples = ArrayList<GlucoseSample>(1 shl 18)
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("Nu pot deschide fișierul.")
    stream.bufferedReader().useLines { lines ->
        var index = 0
        for (line in lines) {
            val i = index++
            if (i < 2) continue // two header rows
            parseLibreViewLine(line, formats)?.let { samples.add(it) }
        }
    }
    if (samples.isEmpty()) throw IllegalStateException("Nu am găsit valori de glicemie în fișier.")
    GlucoRitm.history.importSamples(samples)
}

/** Date formats LibreView exports use across regions; the first match wins. */
internal fun libreViewDateFormats(): List<SimpleDateFormat> = listOf(
    SimpleDateFormat("MM-dd-yyyy hh:mm a", Locale.US),
    SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US),
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US),
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US),
)

/**
 * Parses one LibreView data row into a [GlucoseSample], or null if it isn't a usable glucose
 * record. Pure (no Android types) so it is unit-testable. `limit = 7` keeps allocation low since
 * only fields 2..5 are needed.
 */
internal fun parseLibreViewLine(line: String, formats: List<SimpleDateFormat>): GlucoseSample? {
    if (line.isBlank()) return null
    val cols = line.split(",", limit = 7)
    if (cols.size < 6) return null
    val mgStr = when (cols[3].trim()) {
        "0" -> cols[4]
        "1" -> cols[5]
        else -> null
    }?.trim().orEmpty()
    val mg = mgStr.toIntOrNull() ?: return null
    if (mg !in 10..600) return null
    val atMs = parseTimestamp(cols[2].trim(), formats) ?: return null
    return GlucoseSample(mg, atMs)
}

private fun parseTimestamp(raw: String, formats: List<SimpleDateFormat>): Long? {
    for (format in formats) {
        runCatching { format.parse(raw) }.getOrNull()?.let { return it.time }
    }
    return null
}
