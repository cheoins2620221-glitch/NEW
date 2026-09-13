package com.parentcontrol.screentime.manager

import com.parentcontrol.screentime.data.AppDao
import com.parentcontrol.screentime.data.TimeBankEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 총 사용 한도, 저축, 대출, 리워드, PIN 보호를 모두 담당.
 *
 * 하루 정산(settleIfNeeded) 흐름:
 *   1) 어제 "허용 앱을 제외한 전체 사용시간"으로 잔여시간(leftover) 계산
 *   2) PREPAY 모드 대출이 있으면 leftover를 대출 상환에 먼저 사용
 *   3) 남은 leftover 중 저축 상한까지는 저축, 초과분은 리워드로 자동 전환
 *   4) DEADLINE 모드 대출이 기한을 넘겼으면 이자 부과 + 기한 연장
 *   5) PREPAY 모드 대출 차감 예정일이 됐으면 저축 잔액에서 남은 대출 잔액 차감
 *   6) 하루 인출 한도 / 그레이스 카운터 초기화
 */
class TimeBankManager(private val dao: AppDao) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    companion object {
        const val MAX_LOAN_PER_DAY_MILLIS = 60 * 60_000L
    }

    fun todayString(now: Long = System.currentTimeMillis()): String = dateFormat.format(now)

    private fun addDays(dateStr: String, days: Int): String {
        val cal = Calendar.getInstance()
        cal.time = dateFormat.parse(dateStr) ?: return dateStr
        cal.add(Calendar.DAY_OF_MONTH, days)
        return dateFormat.format(cal.time)
    }

    // ---------------- 하루 정산 ----------------

    suspend fun settleIfNeeded(now: Long = System.currentTimeMillis()) {
        val original = dao.getBank() ?: TimeBankEntity()
        val today = todayString(now)
        if (original.lastSettledDate == today) return

        var updated = original

        if (original.lastSettledDate.isNotEmpty()) {
            val totalUsedYesterday = dao.getTotalUsedMillisForDate(original.lastSettledDate)
            var leftoverMillis = (updated.totalDailyLimitMillis - totalUsedYesterday).coerceAtLeast(0L)

            if (updated.loanRepayMode == "PREPAY" && updated.loanBalanceMillis > 0) {
                val repay = leftoverMillis.coerceAtMost(updated.loanBalanceMillis)
                updated = updated.copy(loanBalanceMillis = updated.loanBalanceMillis - repay)
                leftoverMillis -= repay
            }

            val roomInBank = (updated.maxBankMillis - updated.bankedMillis).coerceAtLeast(0L)
            val toBank = leftoverMillis.coerceAtMost(roomInBank)
            val toReward = leftoverMillis - toBank
            updated = updated.copy(
                bankedMillis = updated.bankedMillis + toBank,
                rewardPoints = updated.rewardPoints + (toReward / 60_000L)
            )
        }

        if (updated.loanRepayMode == "DEADLINE" && updated.loanBalanceMillis > 0 &&
            updated.loanDueDate.isNotEmpty() && today >= updated.loanDueDate
        ) {
            val interest = updated.loanBalanceMillis * updated.loanInterestRatePercent / 100
            updated = updated.copy(
                loanBalanceMillis = updated.loanBalanceMillis + interest,
                loanDueDate = addDays(today, 7)
            )
        }

        if (updated.loanRepayMode == "PREPAY" && updated.loanBalanceMillis > 0 &&
            updated.loanDueDate.isNotEmpty() && today >= updated.loanDueDate
        ) {
            val deduct = updated.loanBalanceMillis.coerceAtMost(updated.bankedMillis)
            updated = updated.copy(
                bankedMillis = updated.bankedMillis - deduct,
                loanBalanceMillis = updated.loanBalanceMillis - deduct
            )
        }

        if (updated.withdrawnDate != today) {
            updated = updated.copy(withdrawnTodayMillis = 0L, withdrawnDate = today)
        }

        dao.upsertBank(updated.copy(lastSettledDate = today))
    }

    // ---------------- 총 사용 한도 / 그레이스 ----------------

    /** 오늘 이미 그레이스를 사용했는지 */
    suspend fun hasUsedGraceToday(now: Long = System.currentTimeMillis()): Boolean {
        val bank = dao.getBank() ?: return false
        return bank.graceUsedDate == todayString(now)
    }

    suspend fun markGraceUsedToday(now: Long = System.currentTimeMillis()) {
        val bank = dao.getBank() ?: TimeBankEntity()
        dao.upsertBank(bank.copy(graceUsedDate = todayString(now)))
    }

    /** 지금까지 저축/대출로 확보한 "오늘 추가로 쓸 수 있는" 최대량 (하루 인출 한도까지) */
    suspend fun remainingWithdrawableMillis(now: Long = System.currentTimeMillis()): Long {
        val bank = dao.getBank() ?: return 0L
        val today = todayString(now)
        val withdrawnToday = if (bank.withdrawnDate == today) bank.withdrawnTodayMillis else 0L
        val dailyCapLeft = (bank.dailyWithdrawLimitMillis - withdrawnToday).coerceAtLeast(0L)
        return bank.bankedMillis.coerceAtMost(dailyCapLeft)
    }

    suspend fun consumeBank(requestMillis: Long, now: Long = System.currentTimeMillis()): Long {
        val bank = dao.getBank() ?: return 0L
        val today = todayString(now)
        val withdrawnToday = if (bank.withdrawnDate == today) bank.withdrawnTodayMillis else 0L
        val dailyCapLeft = (bank.dailyWithdrawLimitMillis - withdrawnToday).coerceAtLeast(0L)
        val actual = requestMillis.coerceAtMost(dailyCapLeft).coerceAtMost(bank.bankedMillis)
        if (actual <= 0) return 0L
        dao.upsertBank(
            bank.copy(
                bankedMillis = bank.bankedMillis - actual,
                withdrawnTodayMillis = withdrawnToday + actual,
                withdrawnDate = today
            )
        )
        return actual
    }

    // ---------------- 대출 ----------------

    suspend fun remainingLoanQuotaToday(now: Long = System.currentTimeMillis()): Long {
        val bank = dao.getBank() ?: return MAX_LOAN_PER_DAY_MILLIS
        val today = todayString(now)
        val borrowedToday = if (bank.loanBorrowedDate == today) bank.loanBorrowedTodayMillis else 0L
        return (MAX_LOAN_PER_DAY_MILLIS - borrowedToday).coerceAtLeast(0L)
    }

    suspend fun takeLoan(amountMillis: Long, now: Long = System.currentTimeMillis()): Boolean {
        val bank = dao.getBank() ?: TimeBankEntity()
        val today = todayString(now)
        val borrowedToday = if (bank.loanBorrowedDate == today) bank.loanBorrowedTodayMillis else 0L
        if (borrowedToday + amountMillis > MAX_LOAN_PER_DAY_MILLIS) return false

        val isFirstLoan = bank.loanBalanceMillis <= 0
        val dueDate = when {
            bank.loanRepayMode == "DEADLINE" && isFirstLoan -> addDays(today, 7)
            bank.loanRepayMode == "DEADLINE" -> bank.loanDueDate
            else -> addDays(today, 1)
        }

        dao.upsertBank(
            bank.copy(
                bankedMillis = bank.bankedMillis + amountMillis,
                loanBalanceMillis = bank.loanBalanceMillis + amountMillis,
                loanDueDate = dueDate,
                loanBorrowedTodayMillis = borrowedToday + amountMillis,
                loanBorrowedDate = today
            )
        )
        return true
    }

    suspend fun repayLoanNow(amountMillis: Long): Long {
        val bank = dao.getBank() ?: return 0L
        val actual = amountMillis.coerceAtMost(bank.loanBalanceMillis).coerceAtMost(bank.bankedMillis)
        if (actual <= 0) return 0L
        dao.upsertBank(
            bank.copy(
                bankedMillis = bank.bankedMillis - actual,
                loanBalanceMillis = bank.loanBalanceMillis - actual
            )
        )
        return actual
    }

    // ---------------- 리워드 환전 ----------------

    suspend fun exchangeRewardToTime(points: Long): Boolean {
        val bank = dao.getBank() ?: return false
        if (points <= 0 || points > bank.rewardPoints) return false
        val tax = points * bank.exchangeTaxPercent / 100
        val netMinutes = points - tax
        dao.upsertBank(
            bank.copy(
                rewardPoints = bank.rewardPoints - points,
                bankedMillis = bank.bankedMillis + netMinutes * 60_000L
            )
        )
        return true
    }

    suspend fun exchangeTimeToReward(minutes: Long): Boolean {
        val bank = dao.getBank() ?: return false
        val millis = minutes * 60_000L
        if (millis <= 0 || millis > bank.bankedMillis) return false
        val tax = minutes * bank.exchangeTaxPercent / 100
        val netPoints = minutes - tax
        dao.upsertBank(
            bank.copy(
                bankedMillis = bank.bankedMillis - millis,
                rewardPoints = bank.rewardPoints + netPoints
            )
        )
        return true
    }

    // ---------------- 부모 설정 저장 / PIN ----------------

    suspend fun updateSettings(
        totalDailyLimitMinutes: Int,
        gracePeriodSeconds: Int,
        maxBankMinutes: Int,
        windowStartMinuteOfDay: Int,
        windowEndMinuteOfDay: Int,
        dailyWithdrawLimitMinutes: Int,
        loanRepayMode: String,
        loanInterestRatePercent: Int,
        exchangeTaxPercent: Int
    ) {
        val bank = dao.getBank() ?: TimeBankEntity(lastSettledDate = todayString())
        dao.upsertBank(
            bank.copy(
                totalDailyLimitMillis = totalDailyLimitMinutes * 60_000L,
                gracePeriodSeconds = gracePeriodSeconds,
                maxBankMillis = maxBankMinutes * 60_000L,
                bankWindowStartMinuteOfDay = windowStartMinuteOfDay,
                bankWindowEndMinuteOfDay = windowEndMinuteOfDay,
                dailyWithdrawLimitMillis = dailyWithdrawLimitMinutes * 60_000L,
                loanRepayMode = loanRepayMode,
                loanInterestRatePercent = loanInterestRatePercent,
                exchangeTaxPercent = exchangeTaxPercent
            )
        )
    }

    suspend fun verifyPin(input: String): Boolean {
        val bank = dao.getBank() ?: return true
        return bank.parentPin.isNullOrEmpty() || bank.parentPin == input
    }

    suspend fun hasPin(): Boolean = !dao.getBank()?.parentPin.isNullOrEmpty()

    suspend fun setPin(newPin: String?) {
        val bank = dao.getBank() ?: TimeBankEntity()
        dao.upsertBank(bank.copy(parentPin = newPin?.ifBlank { null }))
    }
}
