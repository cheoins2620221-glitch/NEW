package com.parentcontrol.screentime.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AllowedAppEntity::class, AppUsageEntity::class, TimeBankEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "screentime.db"
                )
                    .fallbackToDestructiveMigration() // 테스트 단계: 스키마 변경 시 데이터 초기화 허용
                    .build().also { INSTANCE = it }
            }
        }
    }
}
