package com.parentcontrol.screentime.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import com.parentcontrol.screentime.data.AllowedAppEntity
import com.parentcontrol.screentime.data.AppDatabase
import com.parentcontrol.screentime.data.AppUsageEntity
import com.parentcontrol.screentime.manager.TimeBankManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 핵심 감시 서비스.
 *
 * - 전화 앱 / 문자(SMS) 앱은 서비스 시작 시 자동으로 "허용 앱" 목록에 등록됨
 * - 허용 앱이 아닌 모든 앱의 사용시간을 합산해서 "총 사용 한도"와 비교
 * - 총 사용 한도를 넘으면:
 *     1) 오늘 아직 그레이스를 안 썼다면 -> 그레이스 오버레이 (앱은 계속 쓸 수 있음)
 *     2) 그레이스까지 다 썼다면 -> 즉시 홈 이동 + 차단 오버레이
 * - 허용되지 않은 앱을 다시 열려고 시도할 때마다(= 포그라운드로 올라올 때마다) 반복 차단
 */
class AppMonitorAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var dao: com.parentcontrol.screentime.data.AppDao
    private lateinit var timeBankManager: TimeBankManager

    private var currentForegroundPackage: String? = null
    private var lastTickAt: Long = 0L

    private val excludedPackages by lazy {
        setOf(packageName, "com.android.systemui")
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            evaluateCurrentForegroundApp()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        dao = AppDatabase.getInstance(applicationContext).appDao()
        timeBankManager = TimeBankManager(dao)
        scope.launch {
            seedDefaultAllowedApps()
            timeBankManager.settleIfNeeded()
        }
        lastTickAt = System.currentTimeMillis()
        handler.post(tickRunnable)
    }

    /** 전화/문자 기본 앱을 자동으로 허용 목록에 등록 (없으면 skip, 이미 있으면 그대로 유지) */
    private suspend fun seedDefaultAllowedApps() {
        try {
            val telecomManager = getSystemService(TELECOM_SERVICE) as? TelecomManager
            val dialerPkg = telecomManager?.defaultDialerPackage
            if (dialerPkg != null) {
                dao.upsertAllowedApp(AllowedAppEntity(dialerPkg, resolveAppLabel(dialerPkg), isSystemDefault = true))
            }
        } catch (_: Exception) { /* 일부 기기에서 접근 실패 시 무시 */ }

        try {
            val smsPkg = Telephony.Sms.getDefaultSmsPackage(this)
            if (smsPkg != null) {
                dao.upsertAllowedApp(AllowedAppEntity(smsPkg, resolveAppLabel(smsPkg), isSystemDefault = true))
            }
        } catch (_: Exception) { /* 무시 */ }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg in excludedPackages) return
            if (pkg != currentForegroundPackage) {
                currentForegroundPackage = pkg
                evaluateCurrentForegroundApp()
            }
        }
    }

    override fun onInterrupt() {}

    private fun evaluateCurrentForegroundApp() {
        val pkg = currentForegroundPackage ?: return
        val now = System.currentTimeMillis()
        val elapsed = (now - lastTickAt).coerceAtLeast(0L)
        lastTickAt = now

        scope.launch {
            timeBankManager.settleIfNeeded(now)

            val allowedPackages = dao.getAllowedPackages()
            if (pkg in allowedPackages) return@launch // 허용 앱은 시간 추적/차단 대상에서 완전히 제외

            val today = timeBankManager.todayString(now)
            val usage = dao.getUsage(pkg, today) ?: AppUsageEntity(pkg, today)
            dao.upsertUsage(usage.copy(usedMillis = usage.usedMillis + elapsed))

            val bank = dao.getBank() ?: return@launch
            val totalUsed = dao.getTotalUsedMillisForDate(today)
            val overBy = totalUsed - bank.totalDailyLimitMillis

            if (overBy <= 0) return@launch // 아직 총 한도 이내

            // 저축/대출로 확보한 시간으로 우선 커버
            val bankRemaining = timeBankManager.remainingWithdrawableMillis(now)
            if (bankRemaining > 0) {
                val consumed = timeBankManager.consumeBank(overBy.coerceAtMost(bankRemaining), now)
                if (consumed >= overBy) return@launch
            }

            handleLimitExceeded(pkg, bank.gracePeriodSeconds)
        }
    }

    private suspend fun handleLimitExceeded(pkg: String, gracePeriodSeconds: Int) {
        val graceAlreadyUsedToday = timeBankManager.hasUsedGraceToday()

        if (!graceAlreadyUsedToday) {
            timeBankManager.markGraceUsedToday()
            startOverlay(BlockOverlayService.MODE_GRACE, resolveAppLabel(pkg), gracePeriodSeconds)
            handler.postDelayed({ forceExitToHome(pkg) }, gracePeriodSeconds * 1000L)
        } else {
            forceExitToHome(pkg)
        }
    }

    /** 홈으로 강제 이동 + 차단 오버레이. 다시 열면 evaluate가 재실행되어 또 쫓아낸다. */
    private fun forceExitToHome(pkg: String) {
        if (currentForegroundPackage != pkg) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        startOverlay(BlockOverlayService.MODE_BLOCK, resolveAppLabel(pkg), 0)
    }

    private fun startOverlay(mode: String, appLabel: String, graceSeconds: Int) {
        val intent = Intent(this, BlockOverlayService::class.java).apply {
            putExtra(BlockOverlayService.EXTRA_MODE, mode)
            putExtra(BlockOverlayService.EXTRA_APP_LABEL, appLabel)
            putExtra(BlockOverlayService.EXTRA_GRACE_SECONDS, graceSeconds)
        }
        startService(intent)
    }

    private fun resolveAppLabel(pkg: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
    }

    companion object {
        private const val TICK_INTERVAL_MS = 1000L
    }
}
