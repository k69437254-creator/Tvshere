package com.hassouni.tv.capture

import com.hassouni.tv.data.SignalState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** State shared by the UVC controller and the preview UI. */
class CaptureEngine {
    private val _state = MutableStateFlow(SignalState.DISCONNECTED)
    val state: StateFlow<SignalState> = _state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun setState(state: SignalState) { _state.value = state }
    fun setError(message: String?) { _error.value = message }
    fun clearError() { _error.value = null }
}
