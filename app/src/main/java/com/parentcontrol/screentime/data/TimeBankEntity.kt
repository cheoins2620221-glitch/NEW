package com.parentcontrol.screentime.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 시간 은행(저축) + 대출 + 리워드 + 총 사용 한도 + 부모 보호(PIN) 통합 엔티티.
 *
 * - 총 사용 한도: totalDailyLimitMillis. 허용 앱을 제외한 모든 앱의 사용시간 합계가
 *   이 값을 넘으면 허용 앱 외의 모든 앱이 잠긴다.
 * - 그레이스: 하루에 한 번만 부여 (graceUsedDate로 오늘 이미 썼는지 확인)
 */
@Entity(tableName = "time_bank")
data class TimeBankEntity(
    @PrimaryKey val id: Int = 1,

    // 총 사용 한도
    val totalDailyLimitMillis: Long = 5 * 60 * 60_000L, // 기본 5시간
    val gracePeriodSeconds: Int = 120,
    val graceUsedDate: String = "", // 오늘 이미 그레이스를 썼는지

    // 저축(적립)
    val bankedMillis: Long = 0L,
    val lastSettledDate: String = "",
    val maxBankMillis: Long = 4 * 60 * 60_000L,
    val bankWindowStartMinuteOfDay: Int = 20 * 60,
    val bankWindowEndMinuteOfDay: Int = 21 * 60,

    // 하루 인출 한도
    val dailyWithdrawLimitMillis: Long = 3 * 60 * 60_000L,
    val withdrawnTodayMillis: Long = 0L,
    val withdrawnDate: String = "",

    // 대출
    val loanBalanceMillis: Long = 0L,
    val loanDueDate: String = "",
    val loanBorrowedTodayMillis: Long = 0L,
    val loanBorrowedDate: String = "",
    val loanRepayMode: String = "PREPAY",
    val loanInterestRatePercent: Int = 20,

    // 리워드
    val rewardPoints: Long = 0L,
    val exchangeTaxPercent: Int = 10,

    // 보호
    val parentPin: String? = null
)
