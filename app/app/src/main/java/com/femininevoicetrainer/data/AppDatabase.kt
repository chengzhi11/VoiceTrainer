package com.femininevoicetrainer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 数据库单例实现
 * Singleton database implementation for the voice trainer app
 */
@Database(
    entities = [Recording::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    /**
     * 获取录音数据访问对象
     */
    abstract fun recordingDao(): RecordingDao

    companion object {
        /**
         * 数据库名称
         */
        private const val DATABASE_NAME = "feminine_voice_database"

        /**
         * v1 → v2(评分体系升级):五维子分/声线标签/mismatch 列,历史数据保留
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN pitchScore REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE recordings ADD COLUMN resonanceScore REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE recordings ADD COLUMN stabilityScore REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE recordings ADD COLUMN qualityScore REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE recordings ADD COLUMN smoothnessScore REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE recordings ADD COLUMN mismatch REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE recordings ADD COLUMN voiceType TEXT")
                db.execSQL("ALTER TABLE recordings ADD COLUMN voiceCondition TEXT")
            }
        }

        /**
         * 单例实例
         */
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 获取数据库实例
         * Thread-safe singleton pattern with double-checked locking
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    // 优先走正式迁移(v1→v2),缺失路径才兜底重建
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }

        /**
         * 关闭数据库连接
         * Primarily used for testing purposes
         */
        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
