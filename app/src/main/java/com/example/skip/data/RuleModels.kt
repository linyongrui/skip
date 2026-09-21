package com.example.skip.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val RULE_FORMAT_VERSION = 1
const val MAX_RULES = 1_000
const val MAX_FIELD_LENGTH = 160
const val MAX_RULE_DOCUMENT_LENGTH = 256 * 1024

enum class RuleAction { CLICK, PARENT_CLICK, BACK }
enum class RuleSource { INITIAL, MANUAL, CAPTURE, IMPORT }

fun RuleSource.priority(): Int = when (this) {
    RuleSource.INITIAL -> 0
    RuleSource.IMPORT -> 1
    RuleSource.MANUAL -> 2
    RuleSource.CAPTURE -> 3
}

data class SkipRule(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = false,
    val packageName: String,
    val viewId: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val className: String = "",
    val pageMustContain: String = "",
    val pageMustNotContain: String = "",
    val action: RuleAction = RuleAction.CLICK,
    val retryLimit: Int = 1,
    val source: RuleSource = RuleSource.MANUAL
) {
    fun validate(): String? {
        if (!PACKAGE_REGEX.matches(packageName)) return "包名无效"
        if (viewId.isBlank() && text.isBlank() && contentDescription.isBlank()) return "请设置 View ID、文本或内容描述"
        if (listOf(viewId, text, contentDescription, className, pageMustContain, pageMustNotContain).any { it.length > MAX_FIELD_LENGTH }) return "字段内容过长"
        if (retryLimit !in 0..2) return "重试次数必须在 0 到 2 之间"
        if (action == RuleAction.BACK && pageMustContain.isBlank()) return "系统返回动作必须设置页面必须包含"
        if (action == RuleAction.PARENT_CLICK && className.isBlank() && pageMustContain.isBlank()) return "点击父级动作需要设置控件类名或页面必须包含"
        return null
    }

    fun toJson() = JSONObject().apply {
        put("id", id); put("enabled", enabled); put("packageName", packageName)
        put("viewId", viewId); put("text", text); put("contentDescription", contentDescription)
        put("className", className); put("pageMustContain", pageMustContain); put("pageMustNotContain", pageMustNotContain)
        put("action", action.name); put("retryLimit", retryLimit); put("source", source.name)
    }

    companion object {
        private val PACKAGE_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        fun fromJson(json: JSONObject): SkipRule {
            val allowed = setOf("id", "enabled", "packageName", "viewId", "text", "contentDescription", "className", "pageMustContain", "pageMustNotContain", "action", "retryLimit", "source")
            require(json.keys().asSequence().all { it in allowed }) { "包含未知规则字段" }
            fun string(name: String): String {
                val value = if (json.has(name)) json.get(name) else ""
                require(value is String) { "$name 必须为字符串" }
                require(value.length <= MAX_FIELD_LENGTH) { "$name 内容过长" }
                return value
            }
            val enabled = if (json.has("enabled")) json.get("enabled").also { require(it is Boolean) { "enabled 必须为布尔值" } } as Boolean else false
            val retryLimit = if (json.has("retryLimit")) {
                val value = json.get("retryLimit")
                require(value is Number && value.toDouble() == value.toInt().toDouble()) { "retryLimit 必须为整数" }
                value.toInt()
            } else 1
            val rule = SkipRule(
                id = string("id").ifBlank { UUID.randomUUID().toString() }, enabled = enabled,
                packageName = string("packageName"), viewId = string("viewId"), text = string("text"),
                contentDescription = string("contentDescription"), className = string("className"),
                pageMustContain = string("pageMustContain"), pageMustNotContain = string("pageMustNotContain"),
                action = RuleAction.valueOf(string("action")), retryLimit = retryLimit,
                source = if (json.has("source")) RuleSource.valueOf(string("source")) else RuleSource.MANUAL
            )
            require(rule.validate() == null) { rule.validate() ?: "规则无效" }
            return rule
        }
    }
}

data class RuleDocument(val rules: List<SkipRule>) {
    fun toJson(): String = JSONObject().put("version", RULE_FORMAT_VERSION).put("rules", JSONArray(rules.map { it.toJson() })).toString(2)
    companion object {
        fun parse(raw: String, forceEnabled: Boolean = false): List<SkipRule> {
            require(raw.length <= MAX_RULE_DOCUMENT_LENGTH) { "规则文件过大" }
            val root = JSONObject(raw)
            require(root.length() == 2 && root.optInt("version", -1) == RULE_FORMAT_VERSION) { "不支持的规则文件" }
            val array = root.getJSONArray("rules")
            require(array.length() <= MAX_RULES) { "规则数量过多" }
            val parsed = buildList { for (i in 0 until array.length()) {
                val rule = SkipRule.fromJson(array.getJSONObject(i))
                add(if (forceEnabled) rule.copy(enabled = true, source = RuleSource.IMPORT) else rule)
            } }
            require(parsed.map { it.id }.distinct().size == parsed.size) { "规则 ID 不能重复" }
            return parsed
        }

    }
}
