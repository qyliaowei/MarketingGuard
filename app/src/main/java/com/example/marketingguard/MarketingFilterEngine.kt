package com.example.marketingguard

import android.content.Context
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * 营销内容检测引擎
 * 从 assets/marketing_filter_rules.json 加载规则库（关键词 / 句式正则 / 收网动作 / 谬误触发器），
 * 对任意文本记分并输出警报等级。
 */
class MarketingFilterEngine private constructor(jsonText: String) {

    enum class Level(val label: String, val colorHex: String) {
        GREEN("正常", "#4CAF50"),
        YELLOW("疑似营销内容", "#F9A825"),
        ORANGE("高度可疑：检测到营销特征", "#F4511E"),
        RED("营销收网动作！请勿轻信，注意付费陷阱", "#E53935")
    }

    data class Match(val category: String, val detail: String)

    data class Result(
        val score: Int,
        val level: Level,
        val matches: List<Match>
    ) {
        val summary: String
            get() = buildString {
                append("【营销雷达】")
                append(level.label)
                append("｜风险分 ")
                append(score)
                if (matches.isNotEmpty()) {
                    append("\n命中特征：")
                    append(matches.take(5).joinToString("、") { "${it.category}(${it.detail})" })
                }
            }
    }

    private data class KeywordGroup(val category: String, val weight: Int, val terms: List<String>)
    private data class PatternGroup(val type: String, val weight: Int, val regexes: List<Regex>)

    // 记分权重
    private var wKeyword5 = 2
    private var wKeyword4 = 1
    private var wPattern = 1
    private var wShockTitle = 2
    private var wCta = 4
    private var wFallacy = 2
    private var wAiFingerprint = 1

    private var thYellow = 5
    private var thOrange = 10
    private var thRed = 15

    private val keywordGroups = mutableListOf<KeywordGroup>()
    private val patternGroups = mutableListOf<PatternGroup>()
    private val ctaRegexes = mutableListOf<Regex>()
    private val fallacyTriggers = mutableListOf<Pair<String, List<String>>>()
    private val selfRefTerms = mutableListOf<String>()

    /** 当前规则库版本号（来自 JSON 的 rules_version 字段） */
    var rulesVersion: String = "1.0"
        private set

    /** 主构造：规则来源由 RuleStore 决定（自定义导入文件优先，否则 assets 内置规则） */
    constructor(context: Context) : this(RuleStore.loadJson(context))

    init {
        parse(JSONObject(jsonText))
    }

    private fun parse(root: JSONObject) {
        rulesVersion = root.optString("rules_version", "1.0").ifBlank { "1.0" }

        root.optJSONObject("scoring")?.let { s ->
            s.optInt("keyword_5star", 2).let { wKeyword5 = it }
            s.optInt("keyword_4star", 1).let { wKeyword4 = it }
            s.optInt("pattern_hit", 1).let { wPattern = it }
            s.optInt("shock_title_hit", 2).let { wShockTitle = it }
            s.optInt("cta_hit", 4).let { wCta = it }
            s.optInt("fallacy_hit", 2).let { wFallacy = it }
            s.optInt("ai_fingerprint_hit", 1).let { wAiFingerprint = it }
            s.optJSONObject("thresholds")?.let { t ->
                thYellow = t.optInt("yellow", 5)
                thOrange = t.optInt("orange", 10)
                thRed = t.optInt("red", 15)
            }
        }

        keywordGroups.clear()
        root.optJSONArray("keywords")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                val terms = mutableListOf<String>()
                g.optJSONArray("terms")?.let { ts ->
                    for (j in 0 until ts.length()) terms.add(ts.optString(j))
                }
                keywordGroups.add(KeywordGroup(g.optString("category"), g.optInt("weight", 5), terms))
            }
        }

        patternGroups.clear()
        root.optJSONArray("patterns")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                val regexes = mutableListOf<Regex>()
                g.optJSONArray("regexes")?.let { rs ->
                    for (j in 0 until rs.length()) {
                        runCatching { regexes.add(Regex(rs.optString(j))) }
                    }
                }
                patternGroups.add(PatternGroup(g.optString("type"), g.optInt("weight", 1), regexes))
            }
        }

        ctaRegexes.clear()
        root.optJSONObject("cta_patterns")?.optJSONArray("regexes")?.let { rs ->
            for (j in 0 until rs.length()) {
                runCatching { ctaRegexes.add(Regex(rs.optString(j))) }
            }
        }

        fallacyTriggers.clear()
        root.optJSONArray("fallacies")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val triggers = mutableListOf<String>()
                f.optJSONArray("triggers")?.let { ts ->
                    for (j in 0 until ts.length()) triggers.add(ts.optString(j))
                }
                fallacyTriggers.add(f.optString("name") to triggers)
            }
        }

        selfRefTerms.clear()
        root.optJSONObject("whitelist")?.optJSONArray("self_reference_terms")?.let { ts ->
            for (j in 0 until ts.length()) selfRefTerms.add(ts.optString(j))
        }
    }

    /** 主入口：对一段屏幕文本进行检测 */
    fun analyze(text: String): Result {
        val t = text.trim()
        if (t.length < 8) return Result(0, Level.GREEN, emptyList())

        val matches = mutableListOf<Match>()
        var score = 0

        // 1. 关键词黑名单
        for (g in keywordGroups) {
            val hits = g.terms.filter { t.contains(it) }
            if (hits.isEmpty()) continue
            val per = if (g.weight >= 5) wKeyword5 else wKeyword4
            // 每个类别最多计 4 个词，防止长文刷分
            val counted = hits.take(4)
            score += per * counted.size
            matches.add(Match(g.category, counted.joinToString("/")))
        }

        // 2. 句式正则（伪深刻 / 震惊体 / AI 指纹）
        for (g in patternGroups) {
            val weight = when {
                g.type.contains("震惊") -> wShockTitle
                g.type.contains("AI") || g.type.contains("指纹") -> wAiFingerprint
                else -> wPattern
            }
            val hits = mutableListOf<String>()
            for (r in g.regexes) {
                r.find(t)?.let { hits.add(it.value.take(18)) }
            }
            if (hits.isNotEmpty()) {
                score += weight * hits.size
                matches.add(Match(g.type, hits.joinToString("/")))
            }
        }

        // 3. 收网动作（命中即红色警报）
        val ctaHits = ctaRegexes.mapNotNull { it.find(t)?.value?.take(18) }
        if (ctaHits.isNotEmpty()) {
            score += wCta
            matches.add(Match("收网动作", ctaHits.joinToString("/")))
        }

        // 4. 逻辑谬误触发器
        for ((name, triggers) in fallacyTriggers) {
            val hit = triggers.firstOrNull { t.contains(it) }
            if (hit != null) {
                score += wFallacy
                matches.add(Match("逻辑谬误·$name", hit))
            }
        }

        // 5. 反讽豁免：内容本身在讲"营销套路/反诈"时降权
        if (selfRefTerms.any { t.contains(it) }) {
            score /= 3
        }

        val level = when {
            ctaHits.isNotEmpty() || score > thRed -> Level.RED
            score >= thOrange -> Level.ORANGE
            score >= thYellow -> Level.YELLOW
            else -> Level.GREEN
        }
        return Result(score, level, matches)
    }

    companion object {
        /** 供无障碍服务调用的全角/半角归一化（可选） */
        fun normalize(text: String): String {
            val p = Pattern.compile("[\\s\\p{C}]+")
            return p.matcher(text).replaceAll(" ")
        }
    }
}
