package com.cellscope.drive.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cellscope.drive.data.dao.MeasurementDao
import com.cellscope.drive.data.dao.SessionDao
import com.cellscope.drive.data.dao.TransitionDao
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity

@Database(
    entities = [
        DriveSession::class,
        LteMeasurementEntity::class,
        CellTransitionEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class CellScopeDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun transitionDao(): TransitionDao

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

        fun getDatabase(context: Context): CellScopeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CellScopeDatabase::class.java,
                    "cellscope_drive.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
