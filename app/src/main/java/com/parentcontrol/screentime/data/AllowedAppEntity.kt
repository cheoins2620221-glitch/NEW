package com.parentcontrol.screentime.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 시간이 다 되어도 잠기지 않는 "허용 앱" 목록.
 * 전화 앱과 문자(SMS) 앱은 서비스 시작 시 자동으로 여기에 등록된다.
 * 그 외 앱은 사용자가 직접 추가할 수 있다.
 */
@Entity(tableName = "allowed_apps")
data class AllowedAppEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val isSystemDefault: Boolean = false // 전화/문자처럼 자동 등록된 항목인지
)
