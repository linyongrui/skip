package com.example.skip

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    if (screen != "home") androidx.activity.compose.BackHandler { screen = "home" }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("无法读取文件") } }
                .onSuccess { raw ->
                    runCatching { RuleDocument.parse(raw, forceDisabled = true) }.fold(
                        onSuccess = { imported ->
                            if (rules.size + imported.size > 100) showMessage(context, "规则数量过多")
                            else scope.launch { runCatching { repository.saveRules(rules + imported) }.onSuccess { showMessage(context, "已导入 ${imported.size} 条规则，默认未启用") }.onFailure { showMessage(context, it.message ?: "保存规则失败") } }
                        },
                        onFailure = { showMessage(context, it.message ?: "规则文件无效") }
                    )
                }.onFailure { showMessage(context, it.message ?: "无法读取文件") }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(RuleDocument(rules).toJson()) } ?: error("无法写入文件") } }
                .onSuccess { showMessage(context, "规则已导出") }
                .onFailure { showMessage(context, it.message ?: "导出失败") }
        }
    }
    Scaffold(modifier = Modifier.fillMaxSize()) { padding -> Column(Modifier.padding(padding)) {
        when (screen) {
            "rules" -> RulesScreen(rules, onBack = { screen = "home" }, onAdd = { editing = SkipRule(enabled = true, packageName = "") }, onEdit = { editing = it }, onToggle = { rule, enabled -> scope.launch { repository.saveRules(rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }) } }, onDelete = { rule -> scope.launch { repository.saveRules(rules.filterNot { it.id == rule.id }) } }, onImportFile = { importFile.launch(arrayOf("application/json", "text/plain")) }, onExportFile = { exportFile.launch("跳过规则.json") })
            "debug" -> DebugScreen(onBack = { screen = "home" })
            else -> HomeScreen(rules, settings, serviceEnabled, onOpenSettings = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, onPause = { scope.launch { repository.setPaused(it) } }, onRules = { screen = "rules" }, onDebug = { screen = "debug" }, onLogging = { scope.launch { repository.setLogging(it) } }, onClearLogs = { scope.launch { repository.clearLogs() } }, logs = logs)
        }
    } }
    editing?.let { original -> RuleEditor(original, onDismiss = { editing = null }, onSave = { candidate -> scope.launch { repository.saveRules(if (rules.any { it.id == candidate.id }) rules.map { if (it.id == candidate.id) candidate else it } else rules + candidate); editing = null } }) }
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

@Composable private fun RulesScreen(rules: List<SkipRule>, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (SkipRule) -> Unit, onToggle: (SkipRule, Boolean) -> Unit, onDelete: (SkipRule) -> Unit, onImportFile: () -> Unit, onExportFile: () -> Unit) = Page("规则", onBack) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<SkipRule?>(null) }
    val appLabels = remember(rules) { rules.map { it.packageName }.distinct().associateWith { packageName -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName) } }
    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("添加规则") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { OutlinedButton(onClick = onImportFile, modifier = Modifier.weight(1f)) { Text("从文件导入") }; OutlinedButton(onClick = onExportFile, modifier = Modifier.weight(1f)) { Text("导出到文件") } }
    rules.forEach { rule -> Card(modifier = Modifier.fillMaxWidth().clickable { onEdit(rule) }) { Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(appLabels[rule.packageName] ?: rule.packageName, style = MaterialTheme.typography.titleSmall); Text("${rule.action.displayName()} · ${rule.viewId.ifBlank { rule.text.ifBlank { rule.contentDescription } }}", style = MaterialTheme.typography.bodySmall, maxLines = 1) }; Switch(rule.enabled, { onToggle(rule, it) }); TextButton(onClick = { pendingDelete = rule }) { Text("删除") } } } }
    pendingDelete?.let { rule -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("确认删除规则？") }, text = { Text("将删除“${appLabels[rule.packageName] ?: rule.packageName}”的这条规则。") }, confirmButton = { TextButton(onClick = { pendingDelete = null; onDelete(rule) }) { Text("删除") } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }) }
}

@Composable private fun DebugScreen(onBack: () -> Unit) {
    val snapshot by NodeDebugStore.snapshot.collectAsState()
    Page("节点树调试", onBack) { Button(onClick = NodeDebugStore::requestSnapshot, modifier = Modifier.fillMaxWidth()) { Text("请求下一次快照") }; Text(snapshot, style = MaterialTheme.typography.bodySmall) }
}

@Composable private fun Page(title: String, onBack: (() -> Unit)? = null, content: @Composable () -> Unit) = Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { Text(title, style = MaterialTheme.typography.headlineMedium); onBack?.let { TextButton(onClick = it) { Text("返回") } } }; Divider(); content() }
@Composable private fun StatusRow(label: String, value: String) = Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { Text(label); Text(value) }

@Composable private fun RuleEditor(rule: SkipRule, onDismiss: () -> Unit, onSave: (SkipRule) -> Unit) {
    val context = LocalContext.current
    var packageName by remember { mutableStateOf(rule.packageName) }; var viewId by remember { mutableStateOf(rule.viewId) }; var text by remember { mutableStateOf(rule.text) }; var description by remember { mutableStateOf(rule.contentDescription) }; var className by remember { mutableStateOf(rule.className) }; var required by remember { mutableStateOf(rule.pageMustContain) }; var forbidden by remember { mutableStateOf(rule.pageMustNotContain) }; var retry by remember { mutableStateOf(rule.retryLimit.toString()) }; var action by remember { mutableStateOf(rule.action) }; var error by remember { mutableStateOf("") }
    var showAppPicker by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("规则") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("目标应用", style = MaterialTheme.typography.titleMedium)
        val selectedAppName = remember(packageName) { packageName.takeIf { it.isNotBlank() }?.let { value -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(value, 0)).toString() }.getOrDefault(value) } }
        OutlinedButton(onClick = { showAppPicker = true }, modifier = Modifier.fillMaxWidth()) { Text(selectedAppName ?: "选择已安装应用") }
        if (selectedAppName != null && selectedAppName != packageName) Text(packageName, style = MaterialTheme.typography.bodySmall)
        Field("也可手动输入包名", packageName) { packageName = it }
        Field("视图 ID", viewId) { viewId = it }; Field("文本", text) { text = it }; Field("内容描述", description) { description = it }; Field("控件类名（可选）", className) { className = it }; Field("页面必须包含", required) { required = it }; Field("页面不能包含", forbidden) { forbidden = it }; Text("动作：${action.displayName()}"); Row { RuleAction.entries.forEach { value -> TextButton(onClick = { action = value }) { Text(value.displayName()) } } }; Field("重试次数（0-2）", retry, KeyboardType.Number) { retry = it }; if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton(onClick = { val candidate = rule.copy(packageName = packageName.trim(), viewId = viewId, text = text, contentDescription = description, className = className, pageMustContain = required, pageMustNotContain = forbidden, action = action, retryLimit = retry.toIntOrNull() ?: -1); error = candidate.validate() ?: ""; if (error.isBlank()) onSave(candidate) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    if (showAppPicker) InstalledAppPicker(onDismiss = { showAppPicker = false }, onSelected = { packageName = it.packageName; showAppPicker = false })
}

private data class InstalledApp(val label: String, val packageName: String, val isSystem: Boolean)

@Composable private fun InstalledAppPicker(onDismiss: () -> Unit, onSelected: (InstalledApp) -> Unit) {
    val context = LocalContext.current
    val apps by produceState<List<InstalledApp>?>(initialValue = null, context) { value = withContext(Dispatchers.Default) { loadInstalledApps(context) } }
    var query by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    val visibleApps = (apps ?: emptyList()).filter { app -> (showSystemApps || !app.isSystem) && (query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择已安装应用") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("按名称或包名搜索") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("显示系统应用"); Switch(showSystemApps, { showSystemApps = it }) }
            if (apps == null) CircularProgressIndicator()
            LazyColumn { items(visibleApps, key = { it.packageName }) { app ->
            Row(Modifier.fillMaxWidth().clickable { onSelected(app) }, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.padding(vertical = 8.dp)) { Text(app.label); Text(app.packageName, style = MaterialTheme.typography.bodySmall) }
                RadioButton(selected = false, onClick = { onSelected(app) })
            }
            } }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun loadInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    return pm.getInstalledApplications(0)
        .map { appInfo -> InstalledApp(appInfo.loadLabel(pm).toString(), appInfo.packageName, (appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) }
        .distinctBy { it.packageName }
        .sortedWith(compareBy<InstalledApp> { it.label.lowercase() }.thenBy { it.packageName })
}
@Composable private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, update: (String) -> Unit) = OutlinedTextField(value, update, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(
    keyboardType = type
), singleLine = true)
private fun RuleAction.displayName() = when (this) { RuleAction.CLICK -> "点击"; RuleAction.PARENT_CLICK -> "点击父级"; RuleAction.BACK -> "系统返回" }
private fun isServiceEnabled(context: Context): Boolean = (context.getSystemService(AccessibilityManager::class.java)?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK) ?: emptyList()).any { it.resolveInfo.serviceInfo.packageName == context.packageName && it.resolveInfo.serviceInfo.name == "${context.packageName}.service.SkipAccessibilityService" }
private fun showMessage(context: Context, message: String) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
