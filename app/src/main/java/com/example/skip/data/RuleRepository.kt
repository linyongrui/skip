package com.example.skip.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.ruleDataStore by preferencesDataStore("skip_rules")

data class AppSettings(val paused: Boolean = false)

class RuleRepository(private val context: Context) {
    private val documentKey = stringPreferencesKey("rule_document")
    private val pausedKey = booleanPreferencesKey("paused")
    private val logKey = stringPreferencesKey("bounded_log")
    val rules: Flow<List<SkipRule>> = context.ruleDataStore.data.map { prefs ->
        runCatching { keepHighestPriorityRules(RuleDocument.parse(prefs[documentKey] ?: "{\"version\":1,\"rules\":[]}")) }.getOrDefault(emptyList())
    }
    val settings: Flow<AppSettings> = context.ruleDataStore.data.map { AppSettings(it[pausedKey] ?: false) }
    val logs: Flow<String> = context.ruleDataStore.data.map { formatStoredLogs(it[logKey] ?: "") }
    suspend fun saveRules(rules: List<SkipRule>) {
        val normalized = keepHighestPriorityRules(rules)
        require(normalized.size <= MAX_RULES)
        require(normalized.map { it.id }.distinct().size == normalized.size) { "规则 ID 不能重复" }
        context.ruleDataStore.edit { it[documentKey] = RuleDocument(normalized).toJson() }
    }
    suspend fun resetWithInitialRules(rules: List<SkipRule>) {
        require(rules.size <= MAX_RULES) { "应用数量过多" }
        require(rules.map { it.id }.distinct().size == rules.size) { "规则 ID 不能重复" }
        context.ruleDataStore.edit { it[documentKey] = RuleDocument(rules).toJson() }
    }
    suspend fun addRuleIfAbsent(rule: SkipRule): Boolean {
        require(rule.validate() == null) { rule.validate() ?: "规则无效" }
        var added = false
        context.ruleDataStore.edit { preferences ->
            val existing = RuleDocument.parse(preferences[documentKey] ?: "{\"version\":1,\"rules\":[]}")
            val duplicate = existing.any {
                it.packageName == rule.packageName && it.viewId == rule.viewId &&
                    it.text == rule.text && it.contentDescription == rule.contentDescription && it.action == rule.action
            }
            val candidateRules = if (duplicate) existing else existing + rule
            val normalized = keepHighestPriorityRules(candidateRules)
            require(normalized.size <= MAX_RULES) { "规则数量过多" }
            if (normalized != existing) preferences[documentKey] = RuleDocument(normalized).toJson()
            added = !duplicate && normalized.any { it.id == rule.id }
        }
        return added
    }
    suspend fun recordSuccess(ruleId: String) {
        context.ruleDataStore.edit { preferences ->
            val existing = RuleDocument.parse(preferences[documentKey] ?: "{\"version\":1,\"rules\":[]}")
            val updated = existing.map { if (it.id == ruleId) it.copy(successCount = it.successCount + 1) else it }
            if (updated != existing) preferences[documentKey] = RuleDocument(updated).toJson()
        }
    }
    suspend fun setPaused(paused: Boolean) { context.ruleDataStore.edit { it[pausedKey] = paused } }
    suspend fun appendLog(message: String) { context.ruleDataStore.edit { preferences ->
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "$timestamp $message\n"
        preferences[logKey] = ((preferences[logKey] ?: "") + line).takeLast(64 * 1024)
    } }
    suspend fun clearLogs() { context.ruleDataStore.edit { it.remove(logKey) } }

    private fun keepHighestPriorityRules(rules: List<SkipRule>): List<SkipRule> = rules
        .groupBy { it.packageName }
        .map { (_, packageRules) ->
            val highestPriority = packageRules.maxOf { it.source.priority() }
            packageRules.last { it.source.priority() == highestPriority }
        }

    private fun formatStoredLogs(raw: String): String {
        if (raw.isBlank()) return raw
        val legacyTimestamp = Regex("^(\\d{10,}) (.*)$")
        return raw.lineSequence().joinToString("\n") { line ->
            val match = legacyTimestamp.matchEntire(line) ?: return@joinToString line
            runCatching {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date(match.groupValues[1].toLong()))
                "$timestamp ${match.groupValues[2]}"
            }.getOrDefault(line)
        }
    }
}
