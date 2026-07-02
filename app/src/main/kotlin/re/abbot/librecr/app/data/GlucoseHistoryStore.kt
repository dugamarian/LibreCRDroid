package re.abbot.librecr.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import re.abbot.librecr.app.stats.GlucoseSample
import re.abbot.librecr.app.stats.GlucoseStatistics
import re.abbot.librecr.app.stats.GlucoseStats
import java.io.File

/**
 * Multi-day glucose history backing the Statistics screen, stored in SQLite.
 *
 * One row per minute (`minute` = epochMillis / 60000 is the PRIMARY KEY, so it is the
 * table's rowid and same-minute readings de-duplicate via INSERT-OR-REPLACE). That makes
 * the three things the stats screen does cheap and index-bounded:
 *   - append: a single keyed upsert (no whole-file rewrite, no O(n) line count),
 *   - statistics: computed by ONE aggregate query over the period — mean/SD/TIR/min-max
 *     never materialize the (up to ~130k) rows in memory,
 *   - retention: a single ranged DELETE keeps only [RETENTION_DAYS] days.
 *
 * Replaces the earlier append-only NDJSON file, which was migrated in once on first use.
 */
class GlucoseHistoryStore(context: Context) {
    private val app = context.applicationContext
    private val helper = OpenHelper(app)

    @Volatile private var migrated = false
    @Volatile private var lastPruneAtMs = 0L

    /**
     * Append one reading; same-minute duplicates replace in place. Prunes past the retention window.
     * [mgDl] is the capped display value (feeds statistics); [chartMgDl] is the uncapped-below-floor
     * value (feeds the chart via [chartSamples]) — usually identical.
     */
    suspend fun append(mgDl: Int, chartMgDl: Int, atMs: Long): Unit = withContext(Dispatchers.IO) {
        ensureMigrated()
        val db = helper.writableDatabase
        db.insertWithOnConflict(TABLE, null, row(atMs, mgDl, chartMgDl), SQLiteDatabase.CONFLICT_REPLACE)
        maybePrune(db)
    }

    /**
     * Retention is 90 days, so there is no need to run a ranged DELETE on every per-minute append
     * (it almost always deletes nothing). Prune at most once per [PRUNE_INTERVAL_MS] (and once at
     * process start, since lastPruneAtMs is 0).
     */
    private fun maybePrune(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        if (now - lastPruneAtMs < PRUNE_INTERVAL_MS) return
        lastPruneAtMs = now
        prune(db)
    }

    /** All samples at or after [sinceMs], oldest first (minute-granular times). Capped display value. */
    suspend fun samples(sinceMs: Long): List<GlucoseSample> = withContext(Dispatchers.IO) {
        ensureMigrated()
        helper.readableDatabase.rawQuery(
            "SELECT minute, mgdl FROM $TABLE WHERE minute >= ? ORDER BY minute ASC",
            arrayOf((sinceMs / MIN_MS).toString()),
        ).use { c ->
            buildList(c.count) {
                while (c.moveToNext()) add(GlucoseSample(mgDl = c.getInt(1), atMs = c.getLong(0) * MIN_MS))
            }
        }
    }

    /** Like [samples], but the uncapped chart value (falls back to mgdl for pre-v2 rows). Chart only. */
    suspend fun chartSamples(sinceMs: Long): List<GlucoseSample> = withContext(Dispatchers.IO) {
        ensureMigrated()
        helper.readableDatabase.rawQuery(
            "SELECT minute, COALESCE(chart_mgdl, mgdl) FROM $TABLE WHERE minute >= ? ORDER BY minute ASC",
            arrayOf((sinceMs / MIN_MS).toString()),
        ).use { c ->
            buildList(c.count) {
                while (c.moveToNext()) add(GlucoseSample(mgDl = c.getInt(1), atMs = c.getLong(0) * MIN_MS))
            }
        }
    }

    /**
     * Period statistics computed entirely in SQL (no row materialization). Buckets/formulas match
     * [GlucoseStats.compute] exactly, so the screen reads the same numbers far more cheaply.
     */
    suspend fun statistics(sinceMs: Long, targetLow: Int, targetHigh: Int): GlucoseStatistics? =
        withContext(Dispatchers.IO) {
            ensureMigrated()
            helper.readableDatabase.rawQuery(
                """
                SELECT COUNT(*)                                              AS n,
                       TOTAL(mgdl)                                           AS sum,
                       TOTAL(CAST(mgdl AS REAL) * mgdl)                      AS sumSq,
                       MIN(mgdl)                                             AS lo,
                       MAX(mgdl)                                             AS hi,
                       SUM(mgdl < 54)                                        AS veryLow,
                       SUM(mgdl >= 54 AND mgdl < ?)                          AS low,
                       SUM(mgdl >= ? AND mgdl <= ?)                          AS inRange,
                       SUM(mgdl > ? AND mgdl <= 250)                         AS high,
                       SUM(mgdl > 250)                                       AS veryHigh,
                       MIN(minute)                                          AS firstMin,
                       MAX(minute)                                          AS lastMin
                FROM $TABLE WHERE minute >= ?
                """.trimIndent(),
                arrayOf(
                    targetLow.toString(), targetLow.toString(), targetHigh.toString(), targetHigh.toString(),
                    (sinceMs / MIN_MS).toString(),
                ),
            ).use { c ->
                if (!c.moveToFirst()) return@withContext null
                val n = c.getInt(0)
                if (n == 0) return@withContext null
                GlucoseStats.fromAggregates(
                    count = n,
                    sum = c.getDouble(1),
                    sumSq = c.getDouble(2),
                    lowestMgDl = c.getInt(3),
                    highestMgDl = c.getInt(4),
                    veryLow = c.getInt(5),
                    low = c.getInt(6),
                    inRange = c.getInt(7),
                    high = c.getInt(8),
                    veryHigh = c.getInt(9),
                    firstAtMs = c.getLong(10) * MIN_MS,
                    lastAtMs = c.getLong(11) * MIN_MS,
                )
            }
        }

    /**
     * Bulk-merge imported samples (e.g. a LibreView CSV export), de-duplicating by minute against
     * existing rows. Returns the number of distinct new minutes added (replacements don't count).
     */
    suspend fun importSamples(incoming: List<GlucoseSample>): Int = withContext(Dispatchers.IO) {
        ensureMigrated()
        if (incoming.isEmpty()) return@withContext 0
        val db = helper.writableDatabase
        val before = count(db)
        db.transaction {
            incoming.forEach { s -> insertWithOnConflict(TABLE, null, row(s.atMs, s.mgDl), SQLiteDatabase.CONFLICT_REPLACE) }
            prune(this)
        }
        (count(db) - before).coerceAtLeast(0)
    }

    private fun prune(db: SQLiteDatabase) {
        val cutoffMinute = (System.currentTimeMillis() - RETENTION_DAYS * DAY_MS) / MIN_MS
        db.delete(TABLE, "minute < ?", arrayOf(cutoffMinute.toString()))
    }

    private fun count(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun row(atMs: Long, mgDl: Int, chartMgDl: Int = mgDl) = ContentValues(3).apply {
        put("minute", atMs / MIN_MS)
        put("mgdl", mgDl)
        put("chart_mgdl", chartMgDl)
    }

    /** One-time import of the legacy NDJSON history, then retire the file. */
    private fun ensureMigrated() {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            val legacy = File(app.filesDir, LEGACY_FILE)
            if (legacy.exists()) {
                runCatching {
                    val db = helper.writableDatabase
                    db.transaction {
                        legacy.useLines { lines ->
                            lines.forEach { line ->
                                parseLegacy(line)?.let { insertWithOnConflict(TABLE, null, row(it.atMs, it.mgDl), SQLiteDatabase.CONFLICT_REPLACE) }
                            }
                        }
                        prune(this)
                    }
                }
                runCatching { legacy.renameTo(File(app.filesDir, "$LEGACY_FILE.imported")) || legacy.delete() }
            }
            migrated = true
        }
    }

    private fun parseLegacy(line: String): GlucoseSample? {
        val m = LEGACY_LINE.matchEntire(line.trim()) ?: return null
        val t = m.groupValues[1].toLongOrNull() ?: return null
        val v = m.groupValues[2].toIntOrNull() ?: return null
        return GlucoseSample(mgDl = v, atMs = t)
    }

    private inline fun SQLiteDatabase.transaction(body: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            body()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private class OpenHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (minute INTEGER PRIMARY KEY, mgdl INTEGER NOT NULL, chart_mgdl INTEGER)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v2: add the uncapped chart value column (null for existing rows → COALESCE falls back to mgdl).
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN chart_mgdl INTEGER")
            }
        }
    }

    companion object {
        private const val DB_NAME = "glucose_history.db"
        private const val DB_VERSION = 2
        private const val TABLE = "glucose"
        private const val MIN_MS = 60_000L
        private const val DAY_MS = 86_400_000L
        const val RETENTION_DAYS = 90L
        private const val PRUNE_INTERVAL_MS = 6 * 60 * 60_000L // 6h; retention is 90 days

        private const val LEGACY_FILE = "glucose_history.ndjson"
        // Both braces escaped: Android's ICU regex rejects a bare '}' (the JVM tolerates it).
        private val LEGACY_LINE = Regex("""\{"t":(\d+),"v":(-?\d+)\}""")
    }
}
