package com.hassouni.tv.ui

import android.app.Application
import android.view.SurfaceView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hassouni.tv.data.AudioSettings
import com.hassouni.tv.data.LiveStats
import com.hassouni.tv.data.OverlayState
import com.hassouni.tv.data.Platform
import com.hassouni.tv.data.PreviewShape
import com.hassouni.tv.data.QualitySettings
import com.hassouni.tv.data.SignalState
import com.hassouni.tv.data.SceneSettings
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

    private val scenePreferences = app.getSharedPreferences(
        "tvshere_preferences",
        android.content.Context.MODE_PRIVATE
    )

    private fun loadSceneSettings() = SceneSettings(
        zoom = scenePreferences.getFloat("scene_zoom", 1f).coerceIn(1f, 3f),
        panX = scenePreferences.getFloat("scene_pan_x", 0f).coerceIn(-1f, 1f),
        panY = scenePreferences.getFloat("scene_pan_y", 0f).coerceIn(-1f, 1f)
    )

    val target = MutableStateFlow(StreamTarget())
    val quality = MutableStateFlow(QualitySettings())
    val audioS = MutableStateFlow(AudioSettings())
    val overlayS = MutableStateFlow(OverlayState())
    val sceneS = MutableStateFlow(loadSceneSettings())
    val stats = MutableStateFlow(LiveStats(tempC = -1f))
    val message = MutableStateFlow<String?>(null)
    val signal: StateFlow<SignalState> = capture.state
    val previewShape = MutableStateFlow(PreviewShape.LANDSCAPE_16_9)

    init {
        broadcast.setScene(sceneS.value)
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
    fun setTarget(value: StreamTarget) {
        val platformChanged = target.value.platform != value.platform
        target.value = value
        if (platformChanged) {
            previewShape.value = when (value.platform) {
                Platform.TIKTOK -> PreviewShape.PORTRAIT_9_16
                Platform.TELEGRAM -> PreviewShape.LANDSCAPE_16_9
                Platform.CUSTOM -> PreviewShape.AUTO
            }
        }
    }
    fun setQuality(value: QualitySettings) { quality.value = value }
    fun setPreviewShape(value: PreviewShape) { previewShape.value = value }
    fun setAudio(value: AudioSettings) {
        audioS.value = value
        broadcast.setAudio(value)
    }
    fun setScene(value: SceneSettings) {
        val safe = value.copy(
            zoom = value.zoom.coerceIn(1f, 3f),
            panX = value.panX.coerceIn(-1f, 1f),
            panY = value.panY.coerceIn(-1f, 1f)
        )

        sceneS.value = safe

        scenePreferences.edit()
            .putFloat("scene_zoom", safe.zoom)
            .putFloat("scene_pan_x", safe.panX)
            .putFloat("scene_pan_y", safe.panY)
            .apply()

        broadcast.setScene(safe)
    }

    fun resetScene() = setScene(SceneSettings())

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
