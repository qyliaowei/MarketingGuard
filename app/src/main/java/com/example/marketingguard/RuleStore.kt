package com.example.marketingguard

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * 规则库存储管理，三级来源优先级：
 *   1. 本地覆盖（用户手动导入，最高优先级）
 *   2. 远程订阅缓存（从订阅 URL 下载）
 *   3. 内置 assets 规则（兜底，永远可用）
 *
 * 热更新闭环：导出样本 → AI 扩充 → 导出新 JSON → 导入立即生效；
 * 或配置订阅 URL → 一键检查更新，多设备共用同一份规则。
 */
object RuleStore {

    private const val BUILTIN_ASSET = "marketing_filter_rules.json"

    /** 用户手动导入的本地覆盖规则 */
    fun rulesFile(context: Context): File =
        File(context.filesDir, "marketing_filter_rules.json")

    /** 远程订阅下载的规则缓存 */
    fun remoteFile(context: Context): File =
        File(context.filesDir, "marketing_filter_rules_remote.json")

    /** 无障碍服务自动收集的命中样本 */
    fun samplesFile(context: Context): File =
        File(context.filesDir, "captured_samples.txt")

    private fun readBuiltin(context: Context): String =
        context.assets.open(BUILTIN_ASSET)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** 按优先级加载当前生效的规则 JSON 文本 */
    fun loadJson(context: Context): String {
        val local = rulesFile(context)
        if (local.exists()) {
            runCatching { local.readText() }.getOrNull()?.let { return it }
        }
        val remote = remoteFile(context)
        if (remote.exists()) {
            runCatching { remote.readText() }.getOrNull()?.let { return it }
        }
        return readBuiltin(context)
    }

    /** 规则来源描述，如「本地覆盖（v1.2-...）」「远程订阅（v1.1-...）」「内置规则」 */
    fun sourceLabel(context: Context): String {
        return when {
            rulesFile(context).exists() ->
                "本地覆盖（${versionOf(readQuietly(rulesFile(context))) ?: "未知版本"}）"
            remoteFile(context).exists() ->
                "远程订阅（${versionOf(readQuietly(remoteFile(context))) ?: "未知版本"}）"
            else -> "内置规则"
        }
    }

    /** 当前实际使用中的规则版本号 */
    fun currentVersion(context: Context): String = versionOf(loadJson(context)) ?: "未知"

    private fun readQuietly(f: File): String? = runCatching { f.readText() }.getOrNull()

    private fun versionOf(json: String?): String? {
        if (json == null) return null
        return runCatching {
            JSONObject(json).optString("rules_version", "未知版本")
        }.getOrNull()
    }

    /**
     * 从系统文件选择器导入规则 JSON 作为本地覆盖。
     * 导入前做合法性校验，失败不覆盖旧规则。返回错误信息，null 表示成功。
     */
    fun import(context: Context, uri: Uri): String? {
        return try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return "无法读取所选文件"
            val error = validate(text)
            if (error != null) return error
            rulesFile(context).writeText(text)
            null
        } catch (e: Exception) {
            "导入失败：${e.message ?: "未知错误"}（旧规则未被覆盖）"
        }
    }

    /** 校验规则 JSON 结构是否可用 */
    fun validate(text: String): String? {
        return try {
            val root = JSONObject(text)
            if (root.optJSONArray("keywords") == null) {
                "校验失败：缺少 keywords 字段，请确认是营销雷达规则文件"
            } else if (root.optJSONObject("scoring") == null) {
                "校验失败：缺少 scoring 字段"
            } else null
        } catch (e: Exception) {
            "校验失败：${e.message ?: "不是合法的 JSON"}"
        }
    }

    /** 把远程订阅内容写入缓存（调用方已校验） */
    fun saveRemote(context: Context, text: String) {
        remoteFile(context).writeText(text)
    }

    /** 清除所有外部规则，回到内置规则 */
    fun clearCustom(context: Context) {
        rulesFile(context).delete()
        remoteFile(context).delete()
    }

    /** 追加一条命中样本，文件超 200KB 时自动清空重写（防无限增长） */
    fun appendSample(context: Context, text: String, level: String, score: Int) {
        try {
            val f = samplesFile(context)
            if (f.exists() && f.length() > 200_000) f.writeText("")
            val clean = text.take(400).replace('\n', ' ').replace('\t', ' ')
            f.appendText("${System.currentTimeMillis()}\t$level\t$score\t$clean\n")
        } catch (_: Exception) {
        }
    }

    /** 读取样本文件内容用于导出，没有样本返回 null */
    fun readSamples(context: Context): String? {
        val f = samplesFile(context)
        return if (f.exists() && f.length() > 0) f.readText() else null
    }
}
