package com.cellscope.drive.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cellscope.drive.data.dao.CallReliabilityDao
import com.cellscope.drive.data.dao.ContinuityDao
import com.cellscope.drive.data.dao.MeasurementDao
import com.cellscope.drive.data.dao.QoeDao
import com.cellscope.drive.data.dao.SessionDao
import com.cellscope.drive.data.dao.TransitionDao
import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.CallReliabilityEventEntity
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.data.dao.DirectionalSurveyDao
import com.cellscope.drive.data.entity.DirectionalSurveyEntity
import com.cellscope.drive.data.entity.NetworkContinuityEventEntity
import com.cellscope.drive.data.entity.ServiceHoleEntity

@Database(
    entities = [
        DriveSession::class,
        LteMeasurementEntity::class,
        CellTransitionEntity::class,
        ActiveQoeTestEntity::class,
        NetworkContinuityEventEntity::class,
        ServiceHoleEntity::class,
        CallReliabilityEventEntity::class,
        DirectionalSurveyEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class CellScopeDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun transitionDao(): TransitionDao
    abstract fun qoeDao(): QoeDao
    abstract fun continuityDao(): ContinuityDao
    abstract fun callReliabilityDao(): CallReliabilityDao
    abstract fun directionalSurveyDao(): DirectionalSurveyDao


    companion object {
        @Volatile
        private var INSTANCE: CellScopeDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drive_sessions ADD COLUMN totalDistanceM REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE drive_sessions ADD COLUMN maxSpeedKmh REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE drive_sessions ADD COLUMN avgRsrpDbm REAL DEFAULT NULL")

                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN bearing REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN sinrDb REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN eci INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN neighborCellsJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN transitionType TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN isGpsValid INTEGER NOT NULL DEFAULT 1")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cell_transitions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        timestampIso TEXT NOT NULL,
                        timestampMs INTEGER NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        fromPci INTEGER,
                        toPci INTEGER,
                        fromRsrp INTEGER,
                        toRsrp INTEGER,
                        fromTac INTEGER,
                        toTac INTEGER,
                        eventType TEXT NOT NULL,
                        confidenceScore REAL NOT NULL,
                        details TEXT NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES drive_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cell_transitions_sessionId ON cell_transitions(sessionId)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drive_sessions ADD COLUMN avgLatencyMs REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE drive_sessions ADD COLUMN downloadThroughputMbps REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE drive_sessions ADD COLUMN uploadThroughputMbps REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE drive_sessions ADD COLUMN gpsAvailabilityPct REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE drive_sessions ADD COLUMN radioAvailabilityPct REAL DEFAULT NULL")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS active_qoe_tests (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        timestampIso TEXT NOT NULL,
                        timestampMs INTEGER NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        testType TEXT NOT NULL,
                        latencyMs INTEGER,
                        downloadSpeedMbps REAL,
                        uploadSpeedMbps REAL,
                        bytesTransferred INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        success INTEGER NOT NULL,
                        httpStatusCode INTEGER,
                        errorMessage TEXT,
                        FOREIGN KEY(sessionId) REFERENCES drive_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_active_qoe_tests_sessionId ON active_qoe_tests(sessionId)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN nci INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN nrArfcn INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN csiRsrp INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN csiSinr REAL DEFAULT NULL")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN dataNetworkType TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN voiceNetworkType TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN networkGen TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN serviceState TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN dataActivityState TEXT DEFAULT NULL")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS network_continuity_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        timestampIso TEXT NOT NULL,
                        timestampMs INTEGER NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        accuracyM REAL,
                        fromGeneration TEXT NOT NULL,
                        toGeneration TEXT NOT NULL,
                        fromTech TEXT,
                        toTech TEXT,
                        transitionCategory TEXT NOT NULL,
                        fromSignalDbm INTEGER,
                        toSignalDbm INTEGER,
                        durationSeconds INTEGER,
                        dataConnectivityState TEXT,
                        evidenceClassification TEXT NOT NULL,
                        details TEXT NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES drive_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_network_continuity_events_sessionId ON network_continuity_events(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_network_continuity_events_timestampMs ON network_continuity_events(timestampMs)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS service_holes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        timestampIso TEXT NOT NULL,
                        timestampMs INTEGER NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        accuracyM REAL,
                        holeType TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        durationMs INTEGER,
                        recoveryTimestampMs INTEGER,
                        rsrpDbm INTEGER,
                        networkType TEXT,
                        networkGen TEXT,
                        engineeringEvidence TEXT NOT NULL,
                        resolutionStatus TEXT NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES drive_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_service_holes_sessionId ON service_holes(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_service_holes_timestampMs ON service_holes(timestampMs)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS call_reliability_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        timestampIso TEXT NOT NULL,
                        timestampMs INTEGER NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        accuracyM REAL,
                        callState TEXT NOT NULL,
                        classification TEXT NOT NULL,
                        previousState TEXT,
                        networkGeneration TEXT,
                        detailedTech TEXT,
                        rsrpDbm INTEGER,
                        serviceState TEXT,
                        callDurationSec INTEGER,
                        riskScoreCategory TEXT NOT NULL,
                        evidenceFactors TEXT NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES drive_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_call_reliability_events_sessionId ON call_reliability_events(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_call_reliability_events_timestampMs ON call_reliability_events(timestampMs)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN headingDeg REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN magneticHeadingDeg REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN headingAccuracyDeg REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN orientationSensorType TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE lte_measurements ADD COLUMN headingValid INTEGER NOT NULL DEFAULT 0")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS directional_surveys (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        timestampIso TEXT NOT NULL,
                        timestampMs INTEGER NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        accuracyM REAL,
                        cellId INTEGER NOT NULL,
                        networkGen TEXT NOT NULL,
                        sampleCount INTEGER NOT NULL,
                        headingSpanDeg REAL NOT NULL,
                        estimatedHeadingDeg REAL,
                        uncertaintySectorDeg REAL,
                        confidenceScore REAL NOT NULL,
                        confidenceTier TEXT NOT NULL,
                        samplesJson TEXT NOT NULL,
                        disclaimer TEXT NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES drive_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_directional_surveys_sessionId ON directional_surveys(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_directional_surveys_timestampMs ON directional_surveys(timestampMs)")
            }
        }

        fun getDatabase(context: Context): CellScopeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CellScopeDatabase::class.java,
                    "cellscope_drive.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
