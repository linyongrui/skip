package com.example.skip

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.skip.data.AppSettings
import com.example.skip.data.RuleAction
import com.example.skip.data.RuleDocument
import com.example.skip.data.RuleRepository
import com.example.skip.data.SkipRule
import com.example.skip.service.NodeDebugStore
import com.example.skip.ui.theme.SkipTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var serviceEnabled = mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceEnabled.value = isServiceEnabled(this)
        enableEdgeToEdge()
        setContent { SkipTheme { SkipApp(serviceEnabled.value) } }
    }
    override fun onResume() { super.onResume(); serviceEnabled.value = isServiceEnabled(this) }
}

@Composable
private fun SkipApp(serviceEnabled: Boolean) {
    val context = LocalContext.current
    val repository = remember { RuleRepository(context.applicationContext) }
    val rules by repository.rules.collectAsState(emptyList())
    val settings by repository.settings.collectAsState(AppSettings())
    val logs by repository.logs.collectAsState("")
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf("home") }
    var editing by remember { mutableStateOf<SkipRule?>(null) }
    var importing by remember { mutableStateOf(false) }
    Scaffold(modifier = Modifier.fillMaxSize()) { padding -> Column(Modifier.padding(padding)) {
        when (screen) {
            "rules" -> RulesScreen(rules, onBack = { screen = "home" }, onAdd = { editing = SkipRule(packageName = "") }, onEdit = { editing = it }, onToggle = { rule, enabled -> scope.launch { repository.saveRules(rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }) } }, onDelete = { rule -> scope.launch { repository.saveRules(rules.filterNot { it.id == rule.id }) } }, onImport = { importing = true }, onExport = { copyText(context, RuleDocument(rules).toJson()) })
            "debug" -> DebugScreen(onBack = { screen = "home" })
            else -> HomeScreen(rules, settings, serviceEnabled, onOpenSettings = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, onPause = { scope.launch { repository.setPaused(it) } }, onRules = { screen = "rules" }, onDebug = { screen = "debug" }, onLogging = { scope.launch { repository.setLogging(it) } }, onClearLogs = { scope.launch { repository.clearLogs() } }, logs = logs)
        }
    } }
    editing?.let { original -> RuleEditor(original, onDismiss = { editing = null }, onSave = { candidate -> scope.launch { repository.saveRules(if (rules.any { it.id == candidate.id }) rules.map { if (it.id == candidate.id) candidate else it } else rules + candidate); editing = null } }) }
    if (importing) ImportDialog(onDismiss = { importing = false }, onImport = { raw -> runCatching { RuleDocument.parse(raw) }.fold(onSuccess = { imported -> if (rules.size + imported.size > 100) "规则数量过多" else { scope.launch { repository.saveRules(rules + imported); importing = false }; null } }, onFailure = { it.message ?: "规则文件无效" }) })
}

@Composable private fun HomeScreen(rules: List<SkipRule>, settings: AppSettings, serviceEnabled: Boolean, onOpenSettings: () -> Unit, onPause: (Boolean) -> Unit, onRules: () -> Unit, onDebug: () -> Unit, onLogging: (Boolean) -> Unit, onClearLogs: () -> Unit, logs: String) = Page("跳过") {
    StatusRow("无障碍服务", if (serviceEnabled) "已开启" else "未开启")
    StatusRow("自动跳过", if (settings.paused) "已暂停" else "运行中")
    StatusRow("已启用规则", rules.count { it.enabled }.toString())
    Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("打开无障碍设置") }
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { Text("全局暂停"); Switch(settings.paused, onPause) }
    OutlinedButton(onClick = onRules, modifier = Modifier.fillMaxWidth()) { Text("规则（${rules.size}）") }
    OutlinedButton(onClick = onDebug, modifier = Modifier.fillMaxWidth()) { Text("节点树调试") }
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { Text("受限本地日志"); Switch(settings.loggingEnabled, onLogging) }
    if (settings.loggingEnabled) { OutlinedButton(onClick = onClearLogs) { Text("清除日志") }; if (logs.isNotBlank()) Text(logs.takeLast(500), style = MaterialTheme.typography.bodySmall) }
}

@Composable private fun RulesScreen(rules: List<SkipRule>, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (SkipRule) -> Unit, onToggle: (SkipRule, Boolean) -> Unit, onDelete: (SkipRule) -> Unit, onImport: () -> Unit, onExport: () -> Unit) = Page("规则", onBack) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onAdd) { Text("添加") }; OutlinedButton(onClick = onImport) { Text("导入 JSON") }; OutlinedButton(onClick = onExport) { Text("复制 JSON") } }
    rules.forEach { rule -> Card(modifier = Modifier.fillMaxWidth().clickable { onEdit(rule) }) { Column(Modifier.padding(12.dp)) { Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { Text(rule.packageName); Switch(rule.enabled, { onToggle(rule, it) }) }; Text("${rule.action.displayName()}  ${rule.viewId.ifBlank { rule.text.ifBlank { rule.contentDescription } }}", style = MaterialTheme.typography.bodySmall); TextButton(onClick = { onDelete(rule) }) { Text("删除") } } } }
}

@Composable private fun DebugScreen(onBack: () -> Unit) {
    val snapshot by NodeDebugStore.snapshot.collectAsState()
    Page("节点树调试", onBack) { Button(onClick = NodeDebugStore::requestSnapshot, modifier = Modifier.fillMaxWidth()) { Text("请求下一次快照") }; Text(snapshot, style = MaterialTheme.typography.bodySmall) }
}

@Composable private fun Page(title: String, onBack: (() -> Unit)? = null, content: @Composable () -> Unit) = Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { Text(title, style = MaterialTheme.typography.headlineMedium); onBack?.let { TextButton(onClick = it) { Text("返回") } } }; Divider(); content() }
@Composable private fun StatusRow(label: String, value: String) = Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { Text(label); Text(value) }

@Composable private fun RuleEditor(rule: SkipRule, onDismiss: () -> Unit, onSave: (SkipRule) -> Unit) {
    var packageName by remember { mutableStateOf(rule.packageName) }; var viewId by remember { mutableStateOf(rule.viewId) }; var text by remember { mutableStateOf(rule.text) }; var description by remember { mutableStateOf(rule.contentDescription) }; var className by remember { mutableStateOf(rule.className) }; var required by remember { mutableStateOf(rule.pageMustContain) }; var forbidden by remember { mutableStateOf(rule.pageMustNotContain) }; var retry by remember { mutableStateOf(rule.retryLimit.toString()) }; var action by remember { mutableStateOf(rule.action) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rule") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Field("Target package", packageName) { packageName = it }; Field("View ID", viewId) { viewId = it }; Field("Text", text) { text = it }; Field("Content description", description) { description = it }; Field("Class name (optional)", className) { className = it }; Field("Page must contain", required) { required = it }; Field("Page must not contain", forbidden) { forbidden = it }; Text("Action: ${action.name}"); Row { RuleAction.entries.forEach { value -> TextButton(onClick = { action = value }) { Text(value.name) } } }; Field("Retry limit (0-2)", retry, KeyboardType.Number) { retry = it }; if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { val candidate = rule.copy(packageName = packageName.trim(), viewId = viewId, text = text, contentDescription = description, className = className, pageMustContain = required, pageMustNotContain = forbidden, action = action, retryLimit = retry.toIntOrNull() ?: -1); error = candidate.validate() ?: ""; if (error.isBlank()) onSave(candidate) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
@Composable private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, update: (String) -> Unit) = OutlinedTextField(value, update, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(
    keyboardType = type
), singleLine = true)
@Composable private fun ImportDialog(onDismiss: () -> Unit, onImport: (String) -> String?) { var raw by remember { mutableStateOf("") }; var error by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = onDismiss, title = { Text("Import rules") }, text = { Column { OutlinedTextField(raw, { raw = it }, label = { Text("Versioned JSON") }, modifier = Modifier.fillMaxWidth()); if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { error = onImport(raw) ?: "" }) { Text("Import disabled") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }) }
private fun isServiceEnabled(context: Context): Boolean = (context.getSystemService(AccessibilityManager::class.java)?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK) ?: emptyList()).any { it.resolveInfo.serviceInfo.packageName == context.packageName && it.resolveInfo.serviceInfo.name == "${context.packageName}.service.SkipAccessibilityService" }
private fun copyText(context: Context, text: String) { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Skip rules", text)) }
