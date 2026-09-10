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

data class AppSettings(val paused: Boolean = false, val loggingEnabled: Boolean = false)

class RuleRepository(private val context: Context) {
    private val documentKey = stringPreferencesKey("rule_document")
    private val pausedKey = booleanPreferencesKey("paused")
    private val loggingKey = booleanPreferencesKey("logging")
    private val logKey = stringPreferencesKey("bounded_log")
    val rules: Flow<List<SkipRule>> = context.ruleDataStore.data.map { prefs ->
        runCatching { RuleDocument.parse(prefs[documentKey] ?: "{\"version\":1,\"rules\":[]}") }.getOrDefault(emptyList())
    }
    val settings: Flow<AppSettings> = context.ruleDataStore.data.map { AppSettings(it[pausedKey] ?: false, it[loggingKey] ?: false) }
    val logs: Flow<String> = context.ruleDataStore.data.map { formatStoredLogs(it[logKey] ?: "") }
    suspend fun saveRules(rules: List<SkipRule>) {
        require(rules.size <= MAX_RULES)
        require(rules.map { it.id }.distinct().size == rules.size) { "规则 ID 不能重复" }
        context.ruleDataStore.edit { it[documentKey] = RuleDocument(rules).toJson() }
    }
    suspend fun setPaused(paused: Boolean) { context.ruleDataStore.edit { it[pausedKey] = paused } }
    suspend fun setLogging(enabled: Boolean) { context.ruleDataStore.edit { it[loggingKey] = enabled } }
    suspend fun appendLog(message: String) { context.ruleDataStore.edit { preferences ->
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "$timestamp $message\n"
        preferences[logKey] = ((preferences[logKey] ?: "") + line).takeLast(64 * 1024)
    } }
    suspend fun clearLogs() { context.ruleDataStore.edit { it.remove(logKey) } }

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
