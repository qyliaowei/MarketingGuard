package com.example.marketingguard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var versionView: TextView
    private lateinit var overlaySwitch: Switch
    private lateinit var ocrSwitch: Switch
    private lateinit var subUrlInput: EditText
    private lateinit var checkUpdateBtn: Button

    private val importRuleLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            val error = RuleStore.import(this, uri)
            if (error == null) {
                Toast.makeText(this, "规则导入成功，立即生效 ✔", Toast.LENGTH_LONG).show()
                refreshVersion()
            } else {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status_view)
        versionView = findViewById(R.id.version_view)
        overlaySwitch = findViewById(R.id.overlay_switch)
        ocrSwitch = findViewById(R.id.ocr_switch)
        subUrlInput = findViewById(R.id.et_sub_url)
        checkUpdateBtn = findViewById(R.id.btn_check_update)

        val prefs = getSharedPreferences(MarketingAccessibilityService.PREFS, MODE_PRIVATE)

        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        overlaySwitch.isChecked = prefs.getBoolean(MarketingAccessibilityService.KEY_OVERLAY_ENABLED, true)
        overlaySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(MarketingAccessibilityService.KEY_OVERLAY_ENABLED, checked).apply()
        }

        ocrSwitch.isChecked = prefs.getBoolean(MarketingAccessibilityService.KEY_OCR_ENABLED, true)
        ocrSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(MarketingAccessibilityService.KEY_OCR_ENABLED, checked).apply()
        }

        subUrlInput.setText(
            prefs.getString(MarketingAccessibilityService.KEY_SUB_URL, "")
        )

        findViewById<Button>(R.id.btn_import).setOnClickListener {
            importRuleLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }

        checkUpdateBtn.setOnClickListener { checkUpdate() }

        findViewById<Button>(R.id.btn_reset_rules).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("恢复内置规则")
                .setMessage("将清除本地导入规则与远程订阅缓存，回到 APK 内置规则。")
                .setPositiveButton("确定") { _, _ ->
                    RuleStore.clearCustom(this)
                    refreshVersion()
                    Toast.makeText(this, "已恢复内置规则", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        findViewById<Button>(R.id.btn_export_samples).setOnClickListener { exportSamples() }
        findViewById<Button>(R.id.btn_test).setOnClickListener { runSelfTest() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshVersion()
    }

    private fun refreshStatus() {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
        ).any { it.resolveInfo.serviceInfo.packageName == packageName }
        statusView.text = if (enabled) getString(R.string.service_status_on)
        else getString(R.string.service_status_off)
    }

    private fun refreshVersion() {
        val version = runCatching {
            val root = org.json.JSONObject(RuleStore.loadJson(this))
            root.optString("rules_version", "1.0") to root.optString("updated_at", "—")
        }.getOrDefault("1.0" to "—")
        versionView.text = "规则库 ${version.first}（更新于 ${version.second}）｜来源：${RuleStore.sourceLabel(this)}"
    }

    /** 远程订阅更新（网络操作放后台线程） */
    private fun checkUpdate() {
        val url = subUrlInput.text.toString().trim()
        val prefs = getSharedPreferences(MarketingAccessibilityService.PREFS, MODE_PRIVATE)
        prefs.edit().putString(MarketingAccessibilityService.KEY_SUB_URL, url).apply()

        checkUpdateBtn.isEnabled = false
        checkUpdateBtn.text = "检查中…"

        Thread {
            val result = RuleSync.fetchAndCache(this, url)
            runOnUiThread {
                checkUpdateBtn.isEnabled = true
                checkUpdateBtn.text = getString(R.string.btn_check_update)
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                if (result.ok) refreshVersion()
            }
        }.start()
    }

    /** 把自动收集的命中样本分享出去，粘贴给 AI 分析以扩充规则库 */
    private fun exportSamples() {
        val samples = RuleStore.readSamples(this)
        if (samples == null) {
            Toast.makeText(this, "暂无收集到的样本（命中营销特征后会自动积累）", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "营销雷达命中样本导出")
            putExtra(Intent.EXTRA_TEXT, samples)
        }
        startActivity(Intent.createChooser(intent, "分享样本给 AI 分析"))
    }

    /** 内置示例文案，验证规则引擎工作正常 */
    private fun runSelfTest() {
        val engine = MarketingFilterEngine(this)
        val samples = listOf(
            "这本书被禁了300年！哈佛研究表明，读懂底层逻辑的人只有1%。看懂人性弱点这3张底牌，普通人也能实现阶层跃迁。评论区扣1领取资料，关注我，下期讲财富密码。",
            "今天天气不错，和朋友去公园散步，吃了一碗很好吃的牛肉面。"
        )
        val sb = StringBuilder("当前规则版本：${engine.rulesVersion}\n\n=== 引擎自测 ===\n")
        for ((i, s) in samples.withIndex()) {
            val r = engine.analyze(s)
            sb.append("\n样本${i + 1}：${r.level.label}（${r.level.colorHex}，得分${r.score}）\n")
            sb.append(r.summary).append("\n")
        }
        Toast.makeText(this, "自测完成，详见弹窗", Toast.LENGTH_SHORT).show()
        AlertDialog.Builder(this)
            .setTitle("规则引擎自测结果")
            .setMessage(sb.toString())
            .setPositiveButton("知道了", null)
            .show()
    }
}
