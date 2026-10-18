package com.cellscope.drive.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cellscope.drive.data.entity.DirectionalSurveyEntity

@Dao
interface DirectionalSurveyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSurvey(survey: DirectionalSurveyEntity): Long

    @Query("SELECT * FROM directional_surveys WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getSurveysForSession(sessionId: Long): List<DirectionalSurveyEntity>

    @Query("SELECT * FROM directional_surveys ORDER BY timestampMs DESC")
    suspend fun getAllSurveys(): List<DirectionalSurveyEntity>

    @Query("DELETE FROM directional_surveys WHERE sessionId = :sessionId")
    suspend fun deleteSurveysForSession(sessionId: Long)
}
