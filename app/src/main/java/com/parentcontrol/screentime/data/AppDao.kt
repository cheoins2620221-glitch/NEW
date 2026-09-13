package com.parentcontrol.screentime.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    // ---- 허용 앱 목록 ----
    @Query("SELECT * FROM allowed_apps ORDER BY isSystemDefault DESC, appLabel")
    fun observeAllowedApps(): Flow<List<AllowedAppEntity>>

    @Query("SELECT packageName FROM allowed_apps")
    suspend fun getAllowedPackages(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllowedApp(app: AllowedAppEntity)

    @Query("DELETE FROM allowed_apps WHERE packageName = :packageName")
    suspend fun deleteAllowedApp(packageName: String)

    // ---- 오늘 사용시간 (허용되지 않은 앱만 기록됨) ----
    @Query("SELECT * FROM app_usage WHERE packageName = :packageName AND date = :date LIMIT 1")
    suspend fun getUsage(packageName: String, date: String): AppUsageEntity?

    @Query("SELECT * FROM app_usage WHERE date = :date")
    suspend fun getAllUsageForDate(date: String): List<AppUsageEntity>

    @Query("SELECT COALESCE(SUM(usedMillis), 0) FROM app_usage WHERE date = :date")
    suspend fun getTotalUsedMillisForDate(date: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsage(usage: AppUsageEntity)

    // ---- 시간 은행 ----
    @Query("SELECT * FROM time_bank WHERE id = 1 LIMIT 1")
    suspend fun getBank(): TimeBankEntity?

    @Query("SELECT * FROM time_bank WHERE id = 1 LIMIT 1")
    fun observeBank(): Flow<TimeBankEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBank(bank: TimeBankEntity)
}
