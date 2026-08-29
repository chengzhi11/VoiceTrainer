package com.femininevoicetrainer.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 录音数据访问接口
 * Data Access Object for Recording entities
 */
@Dao
interface RecordingDao {
    /**
     * 插入新的录音记录
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: Recording): Long

    /**
     * 更新录音记录
     */
    @Update
    suspend fun update(recording: Recording)

    /**
     * 删除录音记录
     */
    @Delete
    suspend fun delete(recording: Recording)

    /**
     * 删除所有录音记录
     */
    @Query("DELETE FROM recordings")
    suspend fun deleteAll()

    /**
     * 根据ID获取录音记录
     */
    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: Long): Recording?

    /**
     * 获取所有录音记录（按日期降序）
     */
    @Query("SELECT * FROM recordings ORDER BY date DESC")
    fun getAllRecordings(): Flow<List<Recording>>

    /**
     * 获取最新的录音记录
     */
    @Query("SELECT * FROM recordings ORDER BY date DESC LIMIT 1")
    suspend fun getLatestRecording(): Recording?

    /**
     * 获取录音总数
     */
    @Query("SELECT COUNT(*) FROM recordings")
    suspend fun getRecordingCount(): Int

    /**
     * 获取平均女声化评分
     */
    @Query("SELECT AVG(score) FROM recordings")
    suspend fun getAverageScore(): Double?

    /**
     * 获取最高评分的录音
     */
    @Query("SELECT * FROM recordings ORDER BY score DESC LIMIT 1")
    suspend fun getHighestScoreRecording(): Recording?

    /**
     * 获取最近的N条录音记录
     */
    @Query("SELECT * FROM recordings ORDER BY date DESC LIMIT :limit")
    fun getRecentRecordings(limit: Int): Flow<List<Recording>>
}
