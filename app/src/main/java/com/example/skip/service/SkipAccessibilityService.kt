package com.example.skip.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import android.widget.Button
import com.example.skip.data.AppSettings
import com.example.skip.data.RuleRepository
import com.example.skip.data.RuleAction
import com.example.skip.data.SkipRule
import com.example.skip.data.RuleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class SkipAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repository: RuleRepository
    @Volatile private var rules: List<SkipRule> = emptyList()
    @Volatile private var settings = AppSettings()
    private val attempts = ConcurrentHashMap<String, Int>()
    private val completedPages = ConcurrentHashMap.newKeySet<String>()
    private var lastExecutionAt = 0L
    private var lastWindowKey = ""
    private var lastScanAt = 0L
    private var lastScanWindowKey = ""
    private var debugOverlay: Button? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = RuleRepository(applicationContext)
        scope.launch { repository.rules.collectLatest { rules = it } }
        scope.launch { repository.settings.collectLatest { settings = it } }
        scope.launch(Dispatchers.Main.immediate) { NodeDebugStore.targetPackageName.collectLatest { packageName ->
            if (packageName == null) removeDebugOverlay() else showDebugOverlay(packageName)
        } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        val activeRules = rules.filter { it.enabled && it.packageName == packageName }
        if (activeRules.isEmpty()) return
        val windowKey = "$packageName:${event.windowId}"
        if (windowKey != lastWindowKey) {
            completedPages.clear(); attempts.clear(); lastWindowKey = windowKey
        }
        val eventNow = SystemClock.elapsedRealtime()
        if (windowKey == lastScanWindowKey && eventNow - lastScanAt < SCAN_INTERVAL_MS) return
        lastScanWindowKey = windowKey
        lastScanAt = eventNow
        val root = rootInActiveWindow ?: return
        var nodes: List<AccessibilityNodeInfo>? = null
        try {
            nodes = collectNodes(root, MAX_DEPTH, MAX_NODES)
            val scannedNodes = nodes ?: return
            val sensitivePage = isSensitivePage(scannedNodes)
            if (settings.paused || completedPages.contains(windowKey)) return
            if (sensitivePage) return
            val now = System.currentTimeMillis()
            if (now - lastExecutionAt < COOLDOWN_MS) return
            val rule = activeRules.firstOrNull { matchesPage(it, scannedNodes) && scannedNodes.any { node -> matchesNode(it, node) } } ?: return
            val attemptKey = "$windowKey:${rule.id}"
            val count = attempts[attemptKey] ?: 0
            if (count > rule.retryLimit) return
            attempts[attemptKey] = count + 1
            val target = scannedNodes.firstOrNull { matchesNode(rule, it) } ?: return
            val success = when (rule.action) {
                RuleAction.CLICK -> target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                RuleAction.PARENT_CLICK -> target.isVisibleToUser && clickAncestor(target)
                RuleAction.BACK -> rule.pageMustContain.isNotBlank() && performGlobalAction(GLOBAL_ACTION_BACK)
            }
            if (success) {
                completedPages.add(windowKey); lastExecutionAt = now
                val appLabel = applicationLabel(packageName)
                scope.launch { repository.appendLog("已对 $appLabel 执行${actionLabel(rule.action)}") }
            }
        } catch (_: RuntimeException) {
            // Fail open: accessibility events must never interfere with the foreground app.
        } finally {
            if (nodes == null) root.recycle() else nodes!!.forEach { runCatching { it.recycle() } }
        }
    }

    override fun onInterrupt() = Unit
    override fun onDestroy() { removeDebugOverlay(); scope.cancel(); super.onDestroy() }

    private fun showDebugOverlay(packageName: String) {
        removeDebugOverlay()
        val button = Button(this).apply {
            text = "抓取"
            contentDescription = "抓取当前界面节点"
            setOnClickListener { captureDebugSnapshot(packageName) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 24; y = 200 }
        runCatching { getSystemService(WindowManager::class.java).addView(button, params); debugOverlay = button }
            .onFailure { NodeDebugStore.publish("无法显示悬浮抓取按钮。") }
    }

    private fun removeDebugOverlay() {
        debugOverlay?.let { view -> runCatching { getSystemService(WindowManager::class.java).removeView(view) } }
        debugOverlay = null
    }

    private fun captureDebugSnapshot(packageName: String) {
        if (!NodeDebugStore.isRequestedFor(packageName)) return
        val root = rootInActiveWindow ?: run {
            NodeDebugStore.updateStatus("当前界面暂时无法读取，请稍后重试。")
            return
        }
        var nodes: List<AccessibilityNodeInfo>? = null
        try {
            if (root.packageName?.toString() != packageName) {
                NodeDebugStore.updateStatus("当前界面不是所选应用，请切换后再点击抓取。")
                return
            }
            nodes = collectNodes(root, MAX_DEPTH, MAX_NODES)
            val scannedNodes = nodes ?: return
            if (isSensitivePage(scannedNodes)) {
                NodeDebugStore.publish("为保护隐私，无法查看包含输入框、密码、验证码或支付信息的页面。")
                return
            }
            val snapshot = scannedNodes.joinToString("\n") { describe(it) }.ifBlank { "没有可访问节点。" }
            val automaticRule = buildAutomaticRule(packageName, scannedNodes)
            NodeDebugStore.publish(snapshot)
            if (automaticRule == null) {
                NodeDebugStore.appendStatus("未自动添加规则：未找到同时具有稳定 View ID 和“跳过/skip”标识的可点击控件。")
            } else {
                scope.launch {
                    runCatching { repository.addRuleIfAbsent(automaticRule.copy(source = RuleSource.CAPTURE)) }
                        .onSuccess { added ->
                            NodeDebugStore.appendStatus(if (added) "已自动添加并启用“点击”规则。" else "相同规则已存在，未重复添加。")
                        }
                        .onFailure { NodeDebugStore.appendStatus("自动添加规则失败：${it.message ?: "未知错误"}") }
                }
            }
        } catch (_: RuntimeException) {
            NodeDebugStore.publish("无法读取当前界面节点。")
        } finally {
            if (nodes == null) root.recycle() else nodes!!.forEach { runCatching { it.recycle() } }
        }
    }

    private fun collectNodes(root: AccessibilityNodeInfo, maxDepth: Int, maxNodes: Int): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(maxNodes)
        val startedAt = SystemClock.elapsedRealtime()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > maxDepth || result.size >= maxNodes || SystemClock.elapsedRealtime() - startedAt >= SCAN_TIMEOUT_MS) {
                node.recycle()
                return
            }
            result.add(node)
            if (depth >= maxDepth) return
            for (index in 0 until node.childCount) {
                if (result.size >= maxNodes || SystemClock.elapsedRealtime() - startedAt >= SCAN_TIMEOUT_MS) break
                node.getChild(index)?.let { visit(it, depth + 1) }
            }
        }
        visit(root, 0)
        return result
    }

    private fun matchesPage(rule: SkipRule, nodes: List<AccessibilityNodeInfo>): Boolean {
        val allText = nodes.joinToString("\u0000") { "${it.text ?: ""}\u0000${it.contentDescription ?: ""}" }
        return (rule.pageMustContain.isBlank() || allText.contains(rule.pageMustContain, true)) &&
            (rule.pageMustNotContain.isBlank() || !allText.contains(rule.pageMustNotContain, true))
    }

    private fun isSensitivePage(nodes: List<AccessibilityNodeInfo>): Boolean {
        val text = nodes.joinToString(" ") { "${it.text ?: ""} ${it.contentDescription ?: ""}" }.lowercase()
        val sensitiveTerms = listOf("密码", "验证码", "支付", "付款", "银行卡", "转账", "支付密码", "password", "verification code", "captcha")
        return nodes.any { it.isPassword || (it.className?.toString()?.contains("EditText") == true && it.isEditable) } || sensitiveTerms.any(text::contains)
    }

    private fun matchesNode(rule: SkipRule, node: AccessibilityNodeInfo): Boolean =
        (rule.viewId.isBlank() || rule.viewId == node.viewIdResourceName) &&
            (rule.text.isBlank() || node.text?.toString()?.contains(rule.text, true) == true) &&
            (rule.contentDescription.isBlank() || node.contentDescription?.toString()?.contains(rule.contentDescription, true) == true) &&
            (rule.className.isBlank() || rule.className == node.className?.toString())

    private fun buildAutomaticRule(packageName: String, nodes: List<AccessibilityNodeInfo>): SkipRule? =
        nodes.firstNotNullOfOrNull { node ->
            val viewId = node.viewIdResourceName.orEmpty()
            val text = skipKeyword(node.text?.toString())
            val description = skipKeyword(node.contentDescription?.toString())
            if (!node.isVisibleToUser || !node.isEnabled || !node.isClickable || viewId.isBlank() || (text == null && description == null && !viewId.contains("skip", true))) {
                null
            } else {
                SkipRule(
                    enabled = true,
                    packageName = packageName,
                    viewId = viewId,
                    text = text.orEmpty(),
                    contentDescription = description.orEmpty(),
                    action = RuleAction.CLICK
                )
            }
        }

    private fun skipKeyword(value: String?): String? = when {
        value.isNullOrBlank() -> null
        value.contains("跳过", true) -> "跳过"
        value.contains("跳過", true) -> "跳過"
        value.contains("skip", true) -> "skip"
        else -> null
    }

    private fun clickAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_ANCESTORS) { depth ->
            val candidate = current ?: return@repeat
            if (depth > 0 && candidate.isVisibleToUser && candidate.isEnabled && candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                runCatching { candidate.recycle() }
                return true
            }
            val parent = candidate.parent
            if (depth > 0) runCatching { candidate.recycle() }
            current = parent
        }
        current?.let { if (it !== node) runCatching { it.recycle() } }
        return false
    }

    private fun describe(node: AccessibilityNodeInfo): String = "${node.className ?: "?"} 视图ID=${node.viewIdResourceName ?: "-"} 文本=${node.text ?: "-"} 内容描述=${node.contentDescription ?: "-"} 可点击=${node.isClickable}"
    private fun applicationLabel(packageName: String): String = runCatching {
        packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
            .takeIf { it.isNotBlank() } ?: packageName
    }.getOrDefault(packageName)
    private fun actionLabel(action: RuleAction) = when (action) { RuleAction.CLICK -> "点击"; RuleAction.PARENT_CLICK -> "点击父级"; RuleAction.BACK -> "系统返回" }

    private companion object { const val MAX_DEPTH = 18; const val MAX_NODES = 250; const val MAX_ANCESTORS = 3; const val COOLDOWN_MS = 800L; const val SCAN_INTERVAL_MS = 250L; const val SCAN_TIMEOUT_MS = 150L }
}
