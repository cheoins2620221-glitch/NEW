package com.parentcontrol.screentime.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.parentcontrol.screentime.R
import com.parentcontrol.screentime.data.AllowedAppEntity
import com.parentcontrol.screentime.data.AppDatabase
import com.parentcontrol.screentime.manager.TimeBankManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var dao: com.parentcontrol.screentime.data.AppDao
    private lateinit var timeBankManager: TimeBankManager
    private lateinit var adapter: AllowedAppAdapter

    private var installedApps: List<Pair<String, String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dao = AppDatabase.getInstance(applicationContext).appDao()
        timeBankManager = TimeBankManager(dao)

        setupPermissionButtons()
        setupInstalledAppsSpinner()
        setupAddAllowedAppButton()
        setupAllowedAppsList()
        setupLimitSettings()
        setupBankSettings()
        setupLoanButtons()
        setupRewardButtons()
        setupPinButton()

        lifecycleScope.launch {
            timeBankManager.settleIfNeeded()
            refreshStatusLabels()
        }
    }

    private fun setupPermissionButtons() {
        findViewById<Button>(R.id.btnOpenAccessibilitySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOpenOverlaySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private suspend fun refreshStatusLabels() {
        val bank = dao.getBank() ?: return
        val today = timeBankManager.todayString()
        val totalUsed = dao.getTotalUsedMillisForDate(today)

        findViewById<TextView>(R.id.txtTotalUsage).text =
            "오늘 사용시간: ${totalUsed / 60_000L}분 / 한도 ${bank.totalDailyLimitMillis / 60_000L}분"
        findViewById<TextView>(R.id.txtBankRemaining).text =
            "저축 잔액: ${bank.bankedMillis / 60_000L}분"
        findViewById<TextView>(R.id.txtLoanStatus).text =
            "대출 잔액: ${bank.loanBalanceMillis / 60_000L}분" +
                if (bank.loanBalanceMillis > 0) " (${if (bank.loanRepayMode == "DEADLINE") "기한제" else "미리쓰기제"}, 기준일 ${bank.loanDueDate})" else ""
        findViewById<TextView>(R.id.txtRewardStatus).text = "리워드 포인트: ${bank.rewardPoints}"
    }

    private fun runWithPinCheck(action: () -> Unit) {
        lifecycleScope.launch {
            if (!timeBankManager.hasPin()) {
                action()
                return@launch
            }
            val input = EditText(this@MainActivity)
            input.hint = "PIN 입력"
            AlertDialog.Builder(this@MainActivity)
                .setTitle("부모 PIN 확인")
                .setView(input)
                .setPositiveButton("확인") { _, _ ->
                    lifecycleScope.launch {
                        if (timeBankManager.verifyPin(input.text.toString())) {
                            action()
                        } else {
                            Toast.makeText(this@MainActivity, "PIN이 틀렸어요", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    // ---------- 총 사용 한도 설정 ----------
    private fun setupLimitSettings() {
        lifecycleScope.launch {
            val bank = dao.getBank()
            if (bank != null) {
                findViewById<EditText>(R.id.inputTotalDailyLimit).setText((bank.totalDailyLimitMillis / 60_000L).toString())
                findViewById<EditText>(R.id.inputGraceSeconds).setText(bank.gracePeriodSeconds.toString())
            }
        }
        findViewById<Button>(R.id.btnSaveLimitSettings).setOnClickListener {
            runWithPinCheck { saveAllSettings() }
        }
    }

    // ---------- 은행/대출/리워드 설정 ----------
    private fun setupBankSettings() {
        lifecycleScope.launch {
            val bank = dao.getBank()
            if (bank != null) {
                findViewById<EditText>(R.id.inputMaxBankMinutes).setText((bank.maxBankMillis / 60_000L).toString())
                findViewById<EditText>(R.id.inputDailyWithdrawMinutes).setText((bank.dailyWithdrawLimitMillis / 60_000L).toString())
                findViewById<EditText>(R.id.inputWindowStartHour).setText((bank.bankWindowStartMinuteOfDay / 60).toString())
                findViewById<EditText>(R.id.inputWindowStartMinute).setText((bank.bankWindowStartMinuteOfDay % 60).toString())
                findViewById<EditText>(R.id.inputWindowEndHour).setText((bank.bankWindowEndMinuteOfDay / 60).toString())
                findViewById<EditText>(R.id.inputWindowEndMinute).setText((bank.bankWindowEndMinuteOfDay % 60).toString())
                findViewById<EditText>(R.id.inputInterestRate).setText(bank.loanInterestRatePercent.toString())
                findViewById<EditText>(R.id.inputExchangeTax).setText(bank.exchangeTaxPercent.toString())
                if (bank.loanRepayMode == "DEADLINE") {
                    findViewById<RadioGroup>(R.id.radioLoanMode).check(R.id.radioDeadline)
                }
            }
        }
        findViewById<Button>(R.id.btnSaveBankSettings).setOnClickListener {
            runWithPinCheck { saveAllSettings() }
        }
    }

    private fun saveAllSettings() {
        val totalLimit = findViewById<EditText>(R.id.inputTotalDailyLimit).text.toString().toIntOrNull()
        val graceSeconds = findViewById<EditText>(R.id.inputGraceSeconds).text.toString().toIntOrNull() ?: 120
        val maxMinutes = findViewById<EditText>(R.id.inputMaxBankMinutes).text.toString().toIntOrNull()
        val dailyWithdraw = findViewById<EditText>(R.id.inputDailyWithdrawMinutes).text.toString().toIntOrNull()
        val startHour = findViewById<EditText>(R.id.inputWindowStartHour).text.toString().toIntOrNull()
        val startMinute = findViewById<EditText>(R.id.inputWindowStartMinute).text.toString().toIntOrNull() ?: 0
        val endHour = findViewById<EditText>(R.id.inputWindowEndHour).text.toString().toIntOrNull()
        val endMinute = findViewById<EditText>(R.id.inputWindowEndMinute).text.toString().toIntOrNull() ?: 0
        val interestRate = findViewById<EditText>(R.id.inputInterestRate).text.toString().toIntOrNull() ?: 20
        val exchangeTax = findViewById<EditText>(R.id.inputExchangeTax).text.toString().toIntOrNull() ?: 10
        val loanMode = if (findViewById<RadioGroup>(R.id.radioLoanMode).checkedRadioButtonId == R.id.radioDeadline) "DEADLINE" else "PREPAY"

        if (totalLimit == null || maxMinutes == null || dailyWithdraw == null || startHour == null || endHour == null) {
            Toast.makeText(this, "값을 모두 입력해 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val startTotal = startHour * 60 + startMinute
        val endTotal = endHour * 60 + endMinute
        if (startTotal >= endTotal) {
            Toast.makeText(this, "종료 시간이 시작 시간보다 늦어야 해요", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            timeBankManager.updateSettings(
                totalDailyLimitMinutes = totalLimit,
                gracePeriodSeconds = graceSeconds,
                maxBankMinutes = maxMinutes,
                windowStartMinuteOfDay = startTotal,
                windowEndMinuteOfDay = endTotal,
                dailyWithdrawLimitMinutes = dailyWithdraw,
                loanRepayMode = loanMode,
                loanInterestRatePercent = interestRate,
                exchangeTaxPercent = exchangeTax
            )
            Toast.makeText(this@MainActivity, "설정을 저장했어요", Toast.LENGTH_SHORT).show()
            refreshStatusLabels()
        }
    }

    // ---------- 대출 ----------
    private fun setupLoanButtons() {
        findViewById<Button>(R.id.btnTakeLoan).setOnClickListener {
            lifecycleScope.launch {
                val ok = timeBankManager.takeLoan(60 * 60_000L)
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "1시간 대출을 받았어요" else "오늘 대출 한도(1시간)를 다 쓰셨어요",
                    Toast.LENGTH_SHORT
                ).show()
                refreshStatusLabels()
            }
        }
        findViewById<Button>(R.id.btnRepayLoan).setOnClickListener {
            lifecycleScope.launch {
                val bank = dao.getBank()
                val repaid = timeBankManager.repayLoanNow(bank?.loanBalanceMillis ?: 0L)
                Toast.makeText(
                    this@MainActivity,
                    if (repaid > 0) "${repaid / 60_000L}분 상환했어요" else "상환할 대출이 없거나 잔액이 부족해요",
                    Toast.LENGTH_SHORT
                ).show()
                refreshStatusLabels()
            }
        }
    }

    // ---------- 리워드 환전 ----------
    private fun setupRewardButtons() {
        findViewById<Button>(R.id.btnTimeToReward).setOnClickListener {
            val amount = findViewById<EditText>(R.id.inputExchangeAmount).text.toString().toLongOrNull()
            if (amount == null || amount <= 0) {
                Toast.makeText(this, "수량을 입력해 주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val ok = timeBankManager.exchangeTimeToReward(amount)
                Toast.makeText(this@MainActivity, if (ok) "리워드로 전환했어요" else "저축 잔액이 부족해요", Toast.LENGTH_SHORT).show()
                refreshStatusLabels()
            }
        }
        findViewById<Button>(R.id.btnRewardToTime).setOnClickListener {
            val amount = findViewById<EditText>(R.id.inputExchangeAmount).text.toString().toLongOrNull()
            if (amount == null || amount <= 0) {
                Toast.makeText(this, "수량을 입력해 주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val ok = timeBankManager.exchangeRewardToTime(amount)
                Toast.makeText(this@MainActivity, if (ok) "저축 시간으로 전환했어요" else "리워드 포인트가 부족해요", Toast.LENGTH_SHORT).show()
                refreshStatusLabels()
            }
        }
    }

    // ---------- PIN ----------
    private fun setupPinButton() {
        findViewById<Button>(R.id.btnSetPin).setOnClickListener {
            runWithPinCheck {
                val newPin = findViewById<EditText>(R.id.inputNewPin).text.toString()
                lifecycleScope.launch {
                    timeBankManager.setPin(newPin.ifBlank { null })
                    Toast.makeText(
                        this@MainActivity,
                        if (newPin.isBlank()) "PIN 잠금을 해제했어요" else "PIN을 설정했어요",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ---------- 설치된 앱 목록 ----------
    private fun setupInstalledAppsSpinner() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        installedApps = resolveInfos
            .map { it.activityInfo.packageName to pm.getApplicationLabel(it.activityInfo.applicationInfo).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second }
            .map { it.second to it.first }

        val labels = installedApps.map { it.first }
        val spinner = findViewById<Spinner>(R.id.spinnerInstalledApps)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }

    // ---------- 허용 앱 추가 ----------
    private fun setupAddAllowedAppButton() {
        findViewById<Button>(R.id.btnAddAllowedApp).setOnClickListener {
            runWithPinCheck { addAllowedApp() }
        }
    }

    private fun addAllowedApp() {
        val spinner = findViewById<Spinner>(R.id.spinnerInstalledApps)
        val selectedIndex = spinner.selectedItemPosition
        if (selectedIndex < 0 || installedApps.isEmpty()) {
            Toast.makeText(this, "앱을 선택해 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val (label, pkg) = installedApps[selectedIndex]
        lifecycleScope.launch {
            dao.upsertAllowedApp(AllowedAppEntity(pkg, label, isSystemDefault = false))
            Toast.makeText(this@MainActivity, "$label 을(를) 허용 목록에 추가했어요", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAllowedAppsList() {
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerAllowedApps)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AllowedAppAdapter { app ->
            runWithPinCheck {
                AlertDialog.Builder(this)
                    .setTitle("삭제")
                    .setMessage("${app.appLabel}을(를) 허용 목록에서 제거할까요? 제거하면 이 앱도 한도에 포함됩니다.")
                    .setPositiveButton("삭제") { _, _ ->
                        lifecycleScope.launch { dao.deleteAllowedApp(app.packageName) }
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
        recyclerView.adapter = adapter

        lifecycleScope.launch {
            dao.observeAllowedApps().collect { list -> adapter.submitList(list) }
        }
    }
}
