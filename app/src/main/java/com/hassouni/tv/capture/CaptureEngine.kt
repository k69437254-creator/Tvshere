package com.hassouni.tv.capture

import com.hassouni.tv.data.SignalState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CaptureDiagnostics(
    val usbDetected: Boolean = false,
    val uvcInterface: Boolean? = null,
    val usbPermissionGranted: Boolean? = null,
    val cameraOpened: Boolean = false,
    val previewStartCallReturned: Boolean = false,
    val supportedModes: Int = 0,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val deviceName: String? = null,
    val message: String = "بانتظار توصيل جهاز الالتقاط عبر USB-OTG."
)

/** State shared by the UVC controller and the preview UI. */
class CaptureEngine {
    private val _state = MutableStateFlow(SignalState.DISCONNECTED)
    val state: StateFlow<SignalState> = _state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _diagnostics = MutableStateFlow(CaptureDiagnostics())
    val diagnostics: StateFlow<CaptureDiagnostics> = _diagnostics

    fun setState(state: SignalState) { _state.value = state }
    fun setError(message: String?) { _error.value = message }
    fun clearError() { _error.value = null }
    fun updateDiagnostics(update: (CaptureDiagnostics) -> CaptureDiagnostics) {
        _diagnostics.value = update(_diagnostics.value)
    }
    fun resetDiagnostics(message: String = "بانتظار توصيل جهاز الالتقاط عبر USB-OTG.") {
        _diagnostics.value = CaptureDiagnostics(message = message)
    }
}
