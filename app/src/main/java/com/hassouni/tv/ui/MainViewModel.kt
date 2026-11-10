package com.hassouni.tv.ui

import android.app.Application
import android.view.SurfaceView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hassouni.tv.data.AudioSettings
import com.hassouni.tv.data.LiveStats
import com.hassouni.tv.data.OverlayState
import com.hassouni.tv.data.QualitySettings
import com.hassouni.tv.data.SignalState
import com.hassouni.tv.data.StreamTarget
import com.hassouni.tv.monitor.SystemMonitor
import com.hassouni.tv.stream.BroadcastController
import com.hassouni.tv.stream.BroadcastRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val broadcast: BroadcastController = BroadcastRuntime.obtain(app)
    val capture = broadcast.capture
    private val monitor = SystemMonitor(app)

    val target = MutableStateFlow(StreamTarget())
    val quality = MutableStateFlow(QualitySettings())
    val audioS = MutableStateFlow(AudioSettings())
    val overlayS = MutableStateFlow(OverlayState())
    val stats = MutableStateFlow(LiveStats(tempC = -1f))
    val message = MutableStateFlow<String?>(null)
    val signal: StateFlow<SignalState> = capture.state

    init {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                broadcast.tick()
                stats.update {
                    it.copy(
                        cpu = monitor.cpuPercent(),
                        ramMb = monitor.ramMb(),
                        tempC = monitor.batteryTempC(),
                        bitrateKbps = broadcast.bitrateKbps.value,
                        dropped = broadcast.droppedFrames.value,
                        elapsedSec = broadcast.elapsedSec.value,
                        sentVideoFrames = broadcast.sentVideoFrames.value,
                        sentAudioFrames = broadcast.sentAudioFrames.value,
                        bytesSent = broadcast.bytesSent.value
                    )
                }
            }
        }
    }

    fun setCameraPermission(granted: Boolean) = broadcast.setCameraPermission(granted)
    fun attachPreview(view: SurfaceView) = broadcast.attachPreview(view)
    fun retryCapture() = broadcast.retryCapture()
    fun setTarget(value: StreamTarget) { target.value = value }
    fun setQuality(value: QualitySettings) { quality.value = value }
    fun setAudio(value: AudioSettings) {
        audioS.value = value
        broadcast.setAudio(value)
    }
    fun setOverlay(value: OverlayState) {
        overlayS.value = value
        broadcast.setOverlay(value)
    }
    fun dismissMessage() { message.value = null }

    fun toggleStream() {
        if (broadcast.streaming.value) {
            broadcast.stop()
        } else {
            broadcast.start(target.value, quality.value, audioS.value, overlayS.value)
            message.value = broadcast.lastError.value
        }
    }

    override fun onCleared() {
        BroadcastRuntime.releaseIfIdle(broadcast)
        super.onCleared()
    }
}
