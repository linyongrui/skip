package com.terrydemo.skip.service

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object NodeDebugStore {
    private val _snapshot = MutableStateFlow("尚未请求快照。")
    val snapshot = _snapshot.asStateFlow()
    private val _targetPackageName = MutableStateFlow<String?>(null)
    val targetPackageName = _targetPackageName.asStateFlow()
    @Volatile private var requestExpiresAt = 0L
    @Volatile private var requestId = 0L
    private val scope = CoroutineScope(Dispatchers.Default)
    private var expirationJob: Job? = null

    @Synchronized
    fun requestSnapshot(packageName: String) {
        expirationJob?.cancel()
        requestId += 1
        val currentRequestId = requestId
        _targetPackageName.value = packageName
        requestExpiresAt = SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MS
        _snapshot.value = "请在 30 秒内打开所选应用，并点击悬浮抓取按钮。"
        expirationJob = scope.launch {
            delay(REQUEST_TIMEOUT_MS)
            if (_targetPackageName.value == packageName && requestId == currentRequestId) clear("请求已超时，请重新请求快照。")
        }
    }

    fun isRequestedFor(packageName: String): Boolean {
        if (_targetPackageName.value != packageName) return false
        if (SystemClock.elapsedRealtime() <= requestExpiresAt) return true
        clear("请求已超时，请重新请求快照。")
        return false
    }

    fun publish(value: String) = clear(value)

    fun updateStatus(value: String) { _snapshot.value = value }

    fun appendStatus(value: String) { _snapshot.value = "${_snapshot.value}\n\n$value" }

    @Synchronized
    private fun clear(message: String) {
        expirationJob?.cancel()
        expirationJob = null
        _targetPackageName.value = null
        requestExpiresAt = 0L
        _snapshot.value = message
    }

    private const val REQUEST_TIMEOUT_MS = 30_000L
}
