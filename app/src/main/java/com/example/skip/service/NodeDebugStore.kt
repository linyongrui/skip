package com.example.skip.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object NodeDebugStore {
    private val _snapshot = MutableStateFlow("No snapshot requested.")
    val snapshot = _snapshot.asStateFlow()
    @Volatile var requested = false
    fun requestSnapshot() { requested = true; _snapshot.value = "Waiting for the next target window event..." }
    fun publish(value: String) { requested = false; _snapshot.value = value }
}
