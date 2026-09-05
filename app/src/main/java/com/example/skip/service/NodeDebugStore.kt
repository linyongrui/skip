package com.example.skip.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object NodeDebugStore {
    private val _snapshot = MutableStateFlow("尚未请求快照。")
    val snapshot = _snapshot.asStateFlow()
    @Volatile var requested = false
    fun requestSnapshot() { requested = true; _snapshot.value = "等待目标应用的下一次窗口事件..." }
    fun publish(value: String) { requested = false; _snapshot.value = value }
}
