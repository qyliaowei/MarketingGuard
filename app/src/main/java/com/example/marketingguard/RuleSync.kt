package com.example.marketingguard

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程订阅同步：从订阅 URL 下载规则 JSON，校验通过后写入缓存。
 * 只下载规则文件，不上传任何数据。必须在后台线程调用。
 */
object RuleSync {

    data class SyncResult(val ok: Boolean, val message: String, val version: String? = null)

    fun fetchAndCache(context: Context, url: String): SyncResult {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return SyncResult(false, "请先填写订阅地址")
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return SyncResult(false, "订阅地址需以 http:// 或 https:// 开头")
        }

        var conn: HttpURLConnection? = null
        return try {
            val c = (URL(trimmed).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "MarketingGuard/1.2")
                setRequestProperty("Accept", "application/json,text/plain,*/*")
            }
            conn = c
            val code = c.responseCode
            if (code !in 200..299) {
                return SyncResult(false, "服务器返回 HTTP $code")
            }
            val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (text.length > 512 * 1024) {
                return SyncResult(false, "规则文件过大（>512KB），已拒绝")
            }

            val error = RuleStore.validate(text)
            if (error != null) return SyncResult(false, error)

            val newVersion = runCatching {
                JSONObject(text).optString("rules_version", "未知版本")
            }.getOrDefault("未知版本")
            val oldVersion = RuleStore.currentVersion(context)

            RuleStore.saveRemote(context, text)

            val msg = if (newVersion == oldVersion) {
                "已是最新规则（$newVersion）"
            } else {
                "规则已更新：$oldVersion → $newVersion"
            }
            SyncResult(true, msg, newVersion)
        } catch (e: Exception) {
            SyncResult(false, "更新失败：${e.message ?: "网络异常"}")
        } finally {
            conn?.disconnect()
        }
    }
}
