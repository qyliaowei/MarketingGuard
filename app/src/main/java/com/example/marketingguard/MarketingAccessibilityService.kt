package com.example.marketingguard

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无障碍服务：两级检测 + 顶部警示条
 *
 * 第一级（低成本）：递归读取无障碍节点文本（标题/字幕/正文）
 * 第二级（补强）：节点文本不足时（纯画面视频、字幕烧录在画面里），API 30+ 截屏 → ML Kit 离线 OCR
 */
class MarketingAccessibilityService : AccessibilityService() {

    private lateinit var engine: MarketingFilterEngine
    private lateinit var prefs: android.content.SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null
    private val overlayVisible = AtomicBoolean(false)
    private val hideRunnable = Runnable { hideOverlay() }

    // 去抖与去重
    private var lastHash = 0
    private var lastAnalyzeAt = 0L
    private var lastOcrAt = 0L
    private val ocrBusy = AtomicBoolean(false)
    private val recentAlerts = object : LinkedHashMap<Int, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Long>?): Boolean {
            return size > 24
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        engine = MarketingFilterEngine(this)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        val now = System.currentTimeMillis()
        if (now - lastAnalyzeAt < 800) return
        lastAnalyzeAt = now

        val text = collectScreenText()

        if (text != null && text.length >= MIN_NODE_TEXT) {
            // 第一级：节点文本充足，直接分析
            process(text)
        } else {
            // 第二级：节点文本不足，走截屏 OCR（限流 3 秒一次）
            tryScreenshotOcr(now)
        }
    }

    /** 第一级：递归收集窗口文本（限深限长，避免卡顿） */
    private fun collectScreenText(): String? {
        val root = rootInActiveWindow ?: return null
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 220 && sb.length < 6000) {
            val node = queue.poll() ?: continue
            visited++
            node.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
            node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        val s = sb.toString()
        return if (s.length >= 8) s else null
    }

    /** 第二级：截屏 + 离线 OCR（Android 11 / API 30 起支持，无需额外权限） */
    private fun tryScreenshotOcr(now: Long) {
        if (!prefs.getBoolean(KEY_OCR_ENABLED, true)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (now - lastOcrAt < OCR_INTERVAL_MS) return
        if (!ocrBusy.compareAndSet(false, true)) return
        lastOcrAt = now

        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val raw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        buffer.close()
                        if (raw == null) {
                            ocrBusy.set(false)
                            return
                        }
                        val scaled = downscale(raw)
                        OcrHelper.recognize(scaled) { text ->
                            ocrBusy.set(false)
                            if (text.isNotBlank() && text.length >= 8) {
                                process(text)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        ocrBusy.set(false)
                    }
                })
        } catch (_: Exception) {
            ocrBusy.set(false)
        }
    }

    /** 截屏降采样，宽度不超过 1080px，兼顾识别率与耗电 */
    private fun downscale(src: Bitmap): Bitmap {
        val maxW = 1080
        if (src.width <= maxW) return src
        val ratio = maxW.toFloat() / src.width
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, maxW, h, true)
    }

    /** 统一处理：去重 → 记分 → 收集样本 → 警示 */
    private fun process(text: String) {
        val normalized = MarketingFilterEngine.normalize(text)
        if (normalized.length < 8) return

        val hash = normalized.hashCode()
        if (hash == lastHash) return
        lastHash = hash

        val now = System.currentTimeMillis()
        val result = engine.analyze(normalized)

        if (result.level == MarketingFilterEngine.Level.GREEN) return

        // 同一段内容 15 秒内不重复报警
        val lastAlert = recentAlerts[hash]
        if (lastAlert != null && now - lastAlert < 15_000) return
        recentAlerts[hash] = now

        // 自动收集命中样本（供后续 AI 分析、扩充规则库用）
        RuleStore.appendSample(this, text, result.level.name, result.score)

        if (prefs.getBoolean(KEY_OVERLAY_ENABLED, true)) {
            showOverlay(result)
        }
    }

    /** 屏幕顶部警示条（TYPE_ACCESSIBILITY_OVERLAY，无需悬浮窗权限） */
    private fun showOverlay(result: MarketingFilterEngine.Result) {
        mainHandler.post {
            try {
                val wm = windowManager ?: return@post
                if (!overlayVisible.get()) {
                    val tv = TextView(this)
                    tv.setTextColor(Color.WHITE)
                    tv.textSize = 13f
                    tv.setPadding(36, 28, 36, 28)
                    tv.maxLines = 4
                    overlayView = tv

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                    )
                    params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    wm.addView(tv, params)
                    overlayVisible.set(true)
                }

                overlayView?.let { tv ->
                    tv.text = result.summary
                    tv.setBackgroundColor(Color.parseColor(result.level.colorHex))
                }

                // 6 秒后自动隐藏
                mainHandler.removeCallbacks(hideRunnable)
                mainHandler.postDelayed(hideRunnable, 6000)
            } catch (_: Exception) {
                // 视图可能已被移除，忽略
            }
        }
    }

    private fun hideOverlay() {
        mainHandler.post {
            try {
                overlayView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {
            } finally {
                overlayView = null
                overlayVisible.set(false)
            }
        }
    }

    override fun onInterrupt() {
        hideOverlay()
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    companion object {
        const val PREFS = "mg_prefs"
        const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        const val KEY_OCR_ENABLED = "ocr_enabled"
        const val KEY_SUB_URL = "subscription_url"

        /** 节点文本少于该长度才触发 OCR（正常文章/评论界面文本远大于此，不会频繁截屏） */
        private const val MIN_NODE_TEXT = 24
        private const val OCR_INTERVAL_MS = 3000L
    }
}
