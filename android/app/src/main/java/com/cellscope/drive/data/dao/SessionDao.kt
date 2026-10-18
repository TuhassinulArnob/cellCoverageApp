package com.cellscope.drive.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.cellscope.drive.data.entity.DriveSession

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: DriveSession): Long

    @Update
    suspend fun updateSession(session: DriveSession)

    @Query("SELECT * FROM drive_sessions ORDER BY id DESC")
    suspend fun getAllSessions(): List<DriveSession>

    @Query("SELECT * FROM drive_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: Long): DriveSession?

    @Query("SELECT * FROM drive_sessions WHERE endTimeMs IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun getActiveSession(): DriveSession?

    @Query("DELETE FROM drive_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)
}
