package com.example.polarh10activityviewer.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.polarh10activityviewer.ble.ConnectionDevice
import com.example.polarh10activityviewer.ble.checkedDataTypes
import com.example.polarh10activityviewer.session.HrHistoryPoint
import com.example.polarh10activityviewer.session.MotionHistoryPoint
import com.example.polarh10activityviewer.session.SessionRecord
import com.example.polarh10activityviewer.session.SessionSnapshot
import com.example.polarh10activityviewer.session.SessionSummary
import com.example.polarh10activityviewer.session.StreamObservation
import com.example.polarh10activityviewer.session.ActivityMetrics
import com.example.polarh10activityviewer.session.ActivityMetricsCalculator
import com.example.polarh10activityviewer.chart.ChartPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val HISTORY_PAGE_SIZE = 10

internal class SessionDatabase(context: Context, name: String = NAME) :
    SQLiteOpenHelper(context.applicationContext, name, null, 7) {
    override fun onConfigure(db: SQLiteDatabase) {
        // Rebuilding the parent table must not cascade-delete ECG/history rows.
        // onConfigure precedes SQLiteOpenHelper's migration transaction.
        db.setForeignKeyConstraintsEnabled(db.version !in 1..6)
    }

    override fun onOpen(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    private fun createSessions(db: SQLiteDatabase, table: String = "sessions") {
        val observations = checkedDataTypes.joinToString(",") { type ->
            "${type}_received INTEGER NOT NULL, ${type}_missing INTEGER NOT NULL, ${type}_failed INTEGER NOT NULL"
        }
        db.execSQL("""CREATE TABLE $table (
            id TEXT PRIMARY KEY NOT NULL, startRequestedAt INTEGER NOT NULL,
            startedAt INTEGER NOT NULL, endedAt INTEGER NOT NULL, durationMs INTEGER NOT NULL,
            deviceName TEXT, deviceId TEXT, endReason TEXT, interrupted INTEGER NOT NULL,
            incomplete INTEGER NOT NULL, receivedValidHr INTEGER NOT NULL,
            minimumHr INTEGER, maximumHr INTEGER, meanHr REAL, validHrCount INTEGER NOT NULL,
            zone0Ms INTEGER NOT NULL, zone1Ms INTEGER NOT NULL, zone2Ms INTEGER NOT NULL,
            zone3Ms INTEGER NOT NULL, zone4Ms INTEGER NOT NULL, unclassifiedMs INTEGER NOT NULL,
            totalSteps INTEGER, meanCadence REAL, maximumCadence REAL,
            $observations)""")
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSessions(db)
        db.execSQL("CREATE INDEX sessions_started ON sessions(startedAt DESC, id DESC)")
        db.execSQL("""CREATE TABLE hr_points (
            sessionId TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
            secondBucket INTEGER NOT NULL, elapsedMs INTEGER NOT NULL, bpm INTEGER,
            breakBefore INTEGER NOT NULL, PRIMARY KEY(sessionId, secondBucket))""")
        createMotionTable(db)
        createSignalTables(db)
        addActivityMetrics(db)
        addStrainScore(db)
    }

    private fun createMotionTable(db: SQLiteDatabase, table: String = "motion_points") {
        db.execSQL("""CREATE TABLE $table (
            sessionId TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
            secondBucket INTEGER NOT NULL, elapsedMs INTEGER NOT NULL, cadence REAL,
            breakBefore INTEGER NOT NULL, PRIMARY KEY(sessionId, secondBucket))""")
    }

    private fun createSignalTables(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN commitState INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE sessions ADD COLUMN collectionIncomplete INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""CREATE TABLE ecg_segments (sessionId TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
            segmentId INTEGER NOT NULL, anchorSensor INTEGER NOT NULL, anchorElapsed INTEGER NOT NULL,
            validStart INTEGER NOT NULL, validEnd INTEGER NOT NULL, rate INTEGER NOT NULL,
            PRIMARY KEY(sessionId, segmentId))""")
        db.execSQL("""CREATE TABLE ecg_chunks (sessionId TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
            chunkIndex INTEGER NOT NULL, segmentId INTEGER NOT NULL, firstMs REAL NOT NULL, lastMs REAL NOT NULL,
            version INTEGER NOT NULL, samples BLOB NOT NULL, PRIMARY KEY(sessionId, chunkIndex))""")
        db.execSQL("CREATE INDEX ecg_window ON ecg_chunks(sessionId, firstMs)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..6 && newVersion == 7)
        if (oldVersion == 1) createSignalTables(db)
        if (oldVersion < 3) addActivityMetrics(db)
        if (oldVersion == 3) rebuildSessions(db, includeScore = false)
        if (oldVersion < 5) {
            addStrainScore(db)
            // Pre-v4 records need the raw-load migration; v4 loads remain unchanged.
            db.rawQuery("SELECT * FROM sessions", null).use { c ->
                while (c.moveToNext()) {
                    val record = c.record()
                    val finalized = c.long("commitState") == 1L && !record.collectionIncomplete
                    val rawStrain = if (oldVersion < 4) {
                        if (finalized) ActivityMetricsCalculator.strain(record) else null
                    } else record.summary.activityMetrics.sessionStrain
                    db.update("sessions", ContentValues().apply {
                        if (oldVersion < 4) {
                            put("sessionStrain", rawStrain)
                            put("metricsVersion", if (finalized) ActivityMetricsCalculator.VERSION else null)
                        }
                        put("sessionStrainScore", if (finalized) ActivityMetricsCalculator.strainScore(rawStrain) else null)
                    }, "id = ?", arrayOf(record.id))
                }
            }
        }
        if (oldVersion != 3) rebuildSessions(db, includeScore = true)
        // Drop retired motion columns while retaining every cadence point and gap marker.
        createMotionTable(db, "motion_points_v7")
        db.execSQL("""INSERT INTO motion_points_v7 (sessionId, secondBucket, elapsedMs, cadence, breakBefore)
            SELECT sessionId, secondBucket, elapsedMs, cadence, breakBefore FROM motion_points""")
        db.execSQL("DROP TABLE motion_points")
        db.execSQL("ALTER TABLE motion_points_v7 RENAME TO motion_points")
        db.execSQL("DROP TABLE IF EXISTS rr_points")
        db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
    }

    // Copy only retained summary columns. Android 13 SQLite cannot DROP COLUMN.
    private fun rebuildSessions(db: SQLiteDatabase, includeScore: Boolean) {
        createSessions(db, "sessions_v7")
        db.execSQL("ALTER TABLE sessions_v7 ADD COLUMN commitState INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE sessions_v7 ADD COLUMN collectionIncomplete INTEGER NOT NULL DEFAULT 0")
        addActivityMetrics(db, "sessions_v7")
        if (includeScore) db.execSQL("ALTER TABLE sessions_v7 ADD COLUMN sessionStrainScore REAL")
        val columns = db.rawQuery("PRAGMA table_info(sessions_v7)", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }.joinToString(",")
        db.execSQL("INSERT INTO sessions_v7 ($columns) SELECT $columns FROM sessions")
        db.execSQL("DROP TABLE sessions")
        db.execSQL("ALTER TABLE sessions_v7 RENAME TO sessions")
        db.execSQL("CREATE INDEX sessions_started ON sessions(startedAt DESC, id DESC)")
    }
    private fun addActivityMetrics(db: SQLiteDatabase, table: String = "sessions") {
        listOf("intensity REAL", "cardioLoad REAL", "cadenceCvPercent REAL", "cadencePointCount INTEGER",
            "metricsVersion INTEGER", "sessionStrain REAL").forEach {
            db.execSQL("ALTER TABLE $table ADD COLUMN $it")
        }
    }

    private fun addStrainScore(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN sessionStrainScore REAL")
    }
    suspend fun save(snapshot: SessionSnapshot) = withContext(Dispatchers.IO) {
        val record = snapshot.record
        require(record.eligibleForSaving && record.endedAt != null)
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Only finalize after signal writes drain; committed UUIDs are idempotent.
            val exists = db.rawQuery("SELECT commitState FROM sessions WHERE id = ?", arrayOf(record.id)).use { it.moveToFirst() && it.getInt(0) != 0 }
            if (!exists) {
                upsertRecord(db, record, 1)
                db.delete("hr_points", "sessionId = ?", arrayOf(record.id))
                db.delete("motion_points", "sessionId = ?", arrayOf(record.id))
                snapshot.hrPoints.forEach { point ->
                    require(point.sessionId == record.id)
                    db.insertOrThrow("hr_points", null, point.values(record.id))
                }
                snapshot.motionPoints.forEach { point ->
                    require(point.sessionId == record.id)
                    db.insertOrThrow("motion_points", null, point.values(record.id))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // Keyset pagination stays stable when a newer session is saved between page requests.
    suspend fun page(before: SessionRecord? = null): List<SessionRecord> = withContext(Dispatchers.IO) {
        val where = if (before == null) "WHERE commitState != 0" else "WHERE commitState != 0 AND (startedAt < ? OR (startedAt = ? AND id < ?))"
        val args = before?.let { arrayOf(it.startedAt.toString(), it.startedAt.toString(), it.id) }
        readableDatabase.rawQuery("SELECT * FROM sessions $where ORDER BY startedAt DESC, id DESC LIMIT $HISTORY_PAGE_SIZE", args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.record()) }
        }
    }

    suspend fun detail(id: String): SessionSnapshot? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        db.beginTransaction()
        try {
            val record = db.rawQuery("SELECT * FROM sessions WHERE id = ? AND commitState != 0", arrayOf(id)).use {
                if (it.moveToFirst()) it.record() else null
            }
            val result = record?.let {
                val hr = db.rawQuery("SELECT * FROM hr_points WHERE sessionId = ? ORDER BY elapsedMs", arrayOf(id)).use { c ->
                    buildList { while (c.moveToNext()) add(HrHistoryPoint(id, c.long("secondBucket"), c.long("elapsedMs"),
                        c.nullableLong("bpm")?.toInt(), c.bool("breakBefore"))) }
                }
                val motion = db.rawQuery("SELECT * FROM motion_points WHERE sessionId = ? ORDER BY elapsedMs", arrayOf(id)).use { c ->
                    buildList { while (c.moveToNext()) add(MotionHistoryPoint(id, c.long("secondBucket"), c.long("elapsedMs"),
                        c.number("cadence"), c.bool("breakBefore"))) }
                }
                SessionSnapshot(record, hr, motion)
            }
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("hr_points", "sessionId = ?", arrayOf(id))
            db.delete("motion_points", "sessionId = ?", arrayOf(id))
            db.delete("sessions", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun upsertRecord(db: SQLiteDatabase, record: SessionRecord, commitState: Int) {
        val values = record.values().apply { put("commitState", commitState) }
        if (db.update("sessions", values, "id = ?", arrayOf(record.id)) == 0) db.insertOrThrow("sessions", null, values)
    }

    // Called exactly once by the application storage owner, never on ordinary page navigation.
    suspend fun recoverInterrupted() = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("""DELETE FROM sessions WHERE commitState = 0 AND receivedValidHr = 0 AND ACC_received = 0
                AND NOT EXISTS (SELECT 1 FROM ecg_chunks WHERE sessionId = sessions.id)""")
            db.execSQL("""UPDATE sessions SET commitState = 2, collectionIncomplete = 1, interrupted = 1,
                endReason = 'Data collection incomplete' WHERE commitState = 0""")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    suspend fun writeSignals(batches: List<SignalBatch>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (batch in batches) {
                val record = batch.record
                require(record.eligibleForSaving)
                val finished = db.rawQuery("SELECT commitState FROM sessions WHERE id = ?", arrayOf(record.id)).use {
                    it.moveToFirst() && it.getInt(0) != 0
                }
                check(!finished) { "Cannot append to a finalized activity" }
                upsertRecord(db, record, 0)
                batch.segments.forEach { segment ->
                    db.replaceOrThrow("ecg_segments", null, ContentValues().apply {
                        put("sessionId", record.id); put("segmentId", segment.index)
                        put("anchorSensor", segment.anchorSensor); put("anchorElapsed", segment.anchorElapsed)
                        put("validStart", segment.validStart); put("validEnd", segment.validEnd); put("rate", segment.rate)
                    })
                }
                batch.ecg.forEach { chunk ->
                    db.replaceOrThrow("ecg_chunks", null, ContentValues().apply {
                        put("sessionId", record.id); put("chunkIndex", chunk.index); put("segmentId", chunk.segment)
                        put("firstMs", chunk.firstMs); put("lastMs", chunk.lastMs); put("version", EcgCodec.VERSION)
                        put("samples", chunk.bytes)
                    })
                }
                batch.hr.forEach { point ->
                    db.replaceOrThrow("hr_points", null, point.values(record.id))
                }
                batch.motion.forEach { point ->
                    db.replaceOrThrow("motion_points", null, point.values(record.id))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    suspend fun ecgWindow(id: String, start: Long, end: Long): List<ChartPoint> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("""SELECT c.*, s.anchorSensor, s.anchorElapsed, s.validStart, s.validEnd, s.rate
            FROM ecg_chunks c JOIN ecg_segments s ON c.sessionId=s.sessionId AND c.segmentId=s.segmentId
            WHERE c.sessionId = ? AND c.firstMs <= ? AND c.lastMs >= ? ORDER BY c.chunkIndex""",
            arrayOf(id, (end + 1000).toString(), (start - 1000).toString())).use { c ->
            val result = mutableListOf<ChartPoint>()
            var previousSegment: Long? = null
            var previousTime: Double? = null
            while (c.moveToNext()) {
                check(c.long("version") == EcgCodec.VERSION.toLong())
                val segment = c.long("segmentId")
                for (sample in EcgCodec.decode(c.getBlob(c.getColumnIndexOrThrow("samples")))) {
                    val time = c.long("anchorElapsed") + (sample.timestamp - c.long("anchorSensor")) / 1_000_000.0
                    if (time < c.long("validStart") || time > c.long("validEnd") || time < 0) continue
                    val broken = previousSegment != segment || previousTime?.let { time <= it ||
                        (time - it) * c.long("rate") > 3000 } != false
                    result.add(ChartPoint(time, sample.voltage.toDouble(), broken))
                    previousSegment = segment; previousTime = time
                }
            }
            // Keep at most one neighboring point per segment at each viewport boundary.
            result.filterIndexed { i, p -> p.elapsedMs in start.toDouble()..end.toDouble() ||
                (p.elapsedMs < start && result.getOrNull(i+1)?.let { !it.breakBefore && it.elapsedMs >= start } == true) ||
                (p.elapsedMs > end && !p.breakBefore && result.getOrNull(i-1)?.elapsedMs?.let { it <= end } == true) }
        }
    }

    private fun HrHistoryPoint.values(recordId: String) = ContentValues().apply {
        put("sessionId", recordId)
        put("secondBucket", secondBucket)
        put("elapsedMs", elapsedMs)
        put("bpm", bpm)
        put("breakBefore", breakBefore)
    }

    private fun MotionHistoryPoint.values(recordId: String) = ContentValues().apply {
        put("sessionId", recordId)
        put("secondBucket", secondBucket)
        put("elapsedMs", elapsedMs)
        put("cadence", cadence)
        put("breakBefore", breakBefore)
    }

    private fun SessionRecord.values() = ContentValues().apply {
        put("id", id); put("startRequestedAt", startRequestedAt); put("startedAt", startedAt)
        put("endedAt", endedAt); put("durationMs", durationMs); put("deviceName", device?.name)
        put("deviceId", device?.deviceId); put("endReason", endReason); put("interrupted", interrupted)
        put("collectionIncomplete", collectionIncomplete)
        put("incomplete", incomplete); put("receivedValidHr", summary.receivedValidHr)
        with(summary) {
            put("minimumHr", minimumHr); put("maximumHr", maximumHr); put("meanHr", meanHr)
            put("validHrCount", validHrCount)
            zoneDurationsMs.forEachIndexed { index, ms -> put("zone${index}Ms", ms) }
            put("unclassifiedMs", unclassifiedMs); put("totalSteps", totalSteps)
            put("meanCadence", meanCadence); put("maximumCadence", maximumCadence)
            with(activityMetrics) {
                put("intensity", intensity); put("cardioLoad", cardioLoad); put("cadenceCvPercent", cadenceCvPercent)
                put("cadencePointCount", cadencePointCount); put("metricsVersion", algorithmVersion)
                put("sessionStrain", sessionStrain)
                put("sessionStrainScore", sessionStrainScore)
            }
        }
        streams.forEach { (type, observation) ->
            put("${type}_received", observation.received); put("${type}_missing", observation.missing)
            put("${type}_failed", observation.failed)
        }
    }

    private fun Cursor.record(): SessionRecord = SessionRecord(
        id = text("id")!!, startRequestedAt = long("startRequestedAt"), startedAt = long("startedAt"),
        endedAt = long("endedAt"), durationMs = long("durationMs"), endReason = text("endReason"),
        device = text("deviceId")?.let { ConnectionDevice(text("deviceName")!!, it) },
        interrupted = bool("interrupted"), collectionIncomplete = bool("collectionIncomplete"),
        summary = SessionSummary(minimumHr = nullableLong("minimumHr")?.toInt(), maximumHr = nullableLong("maximumHr")?.toInt(),
            meanHr = number("meanHr"), validHrCount = long("validHrCount"),
            zoneDurationsMs = List(5) { long("zone${it}Ms") }, unclassifiedMs = long("unclassifiedMs"),
            totalSteps = nullableLong("totalSteps"), meanCadence = number("meanCadence"), maximumCadence = number("maximumCadence"),
            activityMetrics = ActivityMetrics(number("intensity"), number("cardioLoad"), number("cadenceCvPercent"),
                nullableLong("cadencePointCount")?.toInt(), nullableLong("metricsVersion")?.toInt(),
                number("sessionStrain"), number("sessionStrainScore"))),
        streams = checkedDataTypes.associateWith { type ->
            StreamObservation(bool("${type}_received"), bool("${type}_missing"), bool("${type}_failed"))
        }
    )

    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.bool(name: String) = long(name) != 0L
    private fun Cursor.text(name: String): String? = getString(getColumnIndexOrThrow(name))
    private fun Cursor.nullableLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.number(name: String): Double? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getDouble(it) }

    companion object { const val NAME = "sessions.db" }
}
