package com.hassouni.tv.stream

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.SurfaceView
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import com.hassouni.tv.audio.UsbAudioSource
import com.hassouni.tv.capture.CaptureEngine
import com.hassouni.tv.capture.UvcVideoSource
import com.hassouni.tv.data.AudioSettings
import com.hassouni.tv.data.LiveStats
import com.hassouni.tv.data.OverlayState
import com.hassouni.tv.data.QualitySettings
import com.hassouni.tv.data.SceneSettings
import com.hassouni.tv.data.Res
import com.hassouni.tv.data.SignalState
import com.hassouni.tv.data.StreamTarget
import com.hassouni.tv.overlay.OverlayRenderer
import com.hassouni.tv.notifications.AppNotifications
import com.pedro.common.ConnectChecker
import com.pedro.encoder.TimestampMode
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.CropFilterRender
import com.pedro.library.rtmp.RtmpStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Owns one UVC capture source and one RootEncoder RTMP pipeline for both preview and live output. */
class BroadcastController(context: Context, val capture: CaptureEngine) : ConnectChecker {
    private val appContext = context.applicationContext
    private var stream: RtmpStream? = null
    private var audioSource: UsbAudioSource? = null
    private var overlayRenderer: OverlayRenderer? = null
    private var sceneFilter: CropFilterRender? = null
    private var selectedScene = SceneSettings()
    private var overlayFilter: ImageFilterRender? = null
    private var previewView: SurfaceView? = null
    private var previewHolderCallback: SurfaceHolder.Callback? = null
    private var previewHolder: SurfaceHolder? = null
    private var preparedQuality: QualitySettings? = null
    private var stopRequestedByUser = false
    private var lastNotifiedError: String? = null
    private var selectedOverlay = OverlayState()
    private val preparing = AtomicBoolean(false)
    private var startedAtMs = 0L
    private var adaptive: AdaptiveBitrateController? = null
    private var adaptiveEnabled = false
    private var configuredMaxBitrate = 2500

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _status = MutableStateFlow("جاهز — وصّل كارت HDMI عبر USB-OTG")
    val status: StateFlow<String> = _status
    private val _bitrateKbps = MutableStateFlow(0)
    val bitrateKbps: StateFlow<Int> = _bitrateKbps
    private val _droppedFrames = MutableStateFlow(0)
    val droppedFrames: StateFlow<Int> = _droppedFrames
    private val _sentVideoFrames = MutableStateFlow(0L)
    val sentVideoFrames: StateFlow<Long> = _sentVideoFrames
    private val _sentAudioFrames = MutableStateFlow(0L)
    val sentAudioFrames: StateFlow<Long> = _sentAudioFrames
    private val _bytesSent = MutableStateFlow(0L)
    val bytesSent: StateFlow<Long> = _bytesSent
    private val _audioAvailable = MutableStateFlow(false)
    val audioAvailable: StateFlow<Boolean> = _audioAvailable
    private val _audioMessage = MutableStateFlow("لم يبدأ التقاط الصوت بعد")
    val audioMessage: StateFlow<String> = _audioMessage
    private val _elapsedSec = MutableStateFlow(0L)
    val elapsedSec: StateFlow<Long> = _elapsedSec
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    fun setCameraPermission(granted: Boolean) {
        if (granted) {
            if (stream != null && capture.state.value != SignalState.CONNECTED && !_streaming.value) {
                retryCapture()
            } else {
                previewView?.let(::attachPreview)
            }
        } else {
            capture.setState(SignalState.WEAK)
            capture.setError("إذن الكاميرا مطلوب لقراءة كارت الالتقاط عبر USB.")
        }
    }

    fun attachPreview(view: SurfaceView) {
        if (previewView !== view) {
            val oldView = previewView
            val oldCallback = previewHolderCallback

            // Detach the previous GL preview surface before replacing its view.
            if (stream?.isOnPreview == true) {
                try {
                    stream?.stopPreview(false)
                } catch (error: Exception) {
                    reportFailure("تعذر فصل سطح المعاينة السابق: ${error.message ?: "خطأ غير معروف"}")
                    return
                }
            }
            previewHolder = null

            if (oldView != null && oldCallback != null) {
                runCatching { oldView.holder.removeCallback(oldCallback) }
            }

            previewView = view
            val callback = object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (previewView?.holder === holder) {
                        prepareAndStartPreview()
                    }
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    if (previewView?.holder === holder) {
                        prepareAndStartPreview()
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    // Ignore destruction callbacks belonging to an obsolete view.
                    if (previewView?.holder !== holder) return

                    if (previewHolder === holder && stream?.isOnPreview == true) {
                        try {
                            stream?.stopPreview(false)
                            previewHolder = null
                        } catch (error: Exception) {
                            reportFailure("تعذر فصل سطح المعاينة عند إغلاقه: ${error.message ?: "خطأ غير معروف"}")
                        }
                    }
                }
            }

            previewHolderCallback = callback
            view.holder.addCallback(callback)
        }

        prepareAndStartPreview()
    }

    private fun prepareAndStartPreview() {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!ensurePrepared(preparedQuality ?: QualitySettings())) return
        startPreviewOnCurrentSurface()
    }

    private fun startPreviewOnCurrentSurface() {
        val view = previewView ?: return
        val current = stream ?: return
        val holder = view.holder
        val surface = holder.surface

        if (!surface.isValid || view.width <= 0 || view.height <= 0) return

        if (current.isOnPreview) {
            if (previewHolder === holder) {
                try {
                    current.getGlInterface().setPreviewResolution(view.width, view.height)
                } catch (error: Exception) {
                    reportFailure("تعذر تحديث أبعاد المعاينة: ${error.message ?: "خطأ غير معروف"}")
                }
                return
            }

            // The pipeline is previewing another holder; detach it before rebinding.
            try {
                current.stopPreview(false)
            } catch (error: Exception) {
                reportFailure("تعذر فصل سطح المعاينة السابق قبل إعادة الربط: ${error.message ?: "خطأ غير معروف"}")
                return
            }
            previewHolder = null
        }

        runCatching {
            current.startPreview(surface, view.width, view.height)
            previewHolder = holder
        }.onFailure {
            previewHolder = null
            reportFailure("تعذر بدء المعاينة: ${it.message ?: "خطأ غير معروف"}")
        }
    }

    fun retryCapture() {
        if (_streaming.value) {
            reportFailure("أوقف البث قبل إعادة تهيئة كارت الالتقاط.")
            return
        }
        cleanupPreparedResources()
        capture.setState(SignalState.DISCONNECTED)
        capture.clearError()
        prepareAndStartPreview()
    }

    fun setAudio(settings: AudioSettings) {
        audioSource?.settings = settings
    }

    /**
     * Replace the crop filter through RootEncoder's GL action queue.
     * Never mutate a live CropFilterRender matrix directly from the UI thread.
     */
    fun setScene(settings: SceneSettings) {
        selectedScene = settings.copy(
            zoom = settings.zoom.coerceIn(1f, 3f),
            panX = settings.panX.coerceIn(-1f, 1f),
            panY = settings.panY.coerceIn(-1f, 1f)
        )

        val current = stream ?: return
        if (sceneFilter == null) return

        val next = createSceneFilter(selectedScene)

        runCatching {
            current.getGlInterface().setFilter(0, next)
            sceneFilter = next
        }.onFailure {
            reportFailure("تعذر تحديث قص المشهد: ${it.message ?: "خطأ رسومي"}")
        }
    }

    private fun createSceneFilter(settings: SceneSettings): CropFilterRender {
        val zoom = settings.zoom.coerceIn(1f, 3f)
        val cropWidth = 100f / zoom
        val cropHeight = 100f / zoom

        val travelX = 100f - cropWidth
        val travelY = 100f - cropHeight

        val offsetX =
            (travelX / 2f) * (1f + settings.panX.coerceIn(-1f, 1f))
        val offsetY =
            (travelY / 2f) * (1f + settings.panY.coerceIn(-1f, 1f))

        return CropFilterRender().apply {
            setCropArea(offsetX, offsetY, cropWidth, cropHeight)
        }
    }

    fun setOverlay(state: OverlayState) {
        selectedOverlay = state
        val renderer = overlayRenderer ?: return
        loadLogoIfNeeded(state)
        runCatching {
            renderer.render(state)
            overlayFilter?.setImage(renderer.bitmap)
        }.onFailure { reportFailure("تعذر تحديث الطبقات: ${it.message}") }
    }

    private var loadedLogoUri: String? = null
    private fun loadLogoIfNeeded(state: OverlayState) {
        val uri = state.watermarkUri
        if (uri == loadedLogoUri) return
        loadedLogoUri = uri
        val bitmap = if (uri.isNullOrBlank()) null else runCatching {
            appContext.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        overlayRenderer?.setLogo(bitmap)
        if (bitmap != null && !bitmap.isRecycled) runCatching { bitmap.recycle() }
    }

    fun start(target: StreamTarget, quality: QualitySettings, audio: AudioSettings, overlay: OverlayState) {
        if (_streaming.value) {
            stop()
            return
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            reportFailure("إذن الكاميرا غير ممنوح.")
            return
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            reportFailure("إذن الصوت غير ممنوح. يلزم السماح به حتى يتمكن Android من التقاط صوت USB.")
            return
        }
        if (capture.state.value != SignalState.CONNECTED) {
            reportFailure("لا يمكن بدء البث قبل اتصال كارت UVC وظهور المعاينة بصورة مستقرة.")
            return
        }
        val server = target.url.trim().trimEnd('/')
        val key = target.key.trim().trimStart('/')
        if (!(server.startsWith("rtmp://", true) || server.startsWith("rtmps://", true))) {
            reportFailure("أدخل رابط خادم صالحًا يبدأ بـ rtmp:// أو rtmps://.")
            return
        }
        if (key.isBlank()) {
            reportFailure("أدخل مفتاح البث الذي وفرته منصة البث.")
            return
        }
        stopRequestedByUser = false
        if (!ensurePrepared(quality)) return
        setAudio(audio)
        setOverlay(overlay)

        val serviceIntent = Intent(appContext, StreamService::class.java)
        try {
            ContextCompat.startForegroundService(appContext, serviceIntent)
        } catch (e: Exception) {
            reportFailure("تعذر تشغيل خدمة البث الأمامية: ${e.message ?: "تحقق من أذونات Android"}")
            return
        }

        try {
            val current = stream ?: error("محرك البث غير مهيأ")
            current.getStreamClient().setReTries(4)
            current.getStreamClient().setDelay(0)
            // Start each live session with clean sender counters and adaptive state.
            current.getStreamClient().resetDroppedVideoFrames()
            current.getStreamClient().resetDroppedAudioFrames()
            current.getStreamClient().resetBytesSend()
            current.getStreamClient().resetSentVideoFrames()
            current.getStreamClient().resetSentAudioFrames()
            _droppedFrames.value = 0
            _sentVideoFrames.value = 0L
            _sentAudioFrames.value = 0L
            _bytesSent.value = 0L
            _bitrateKbps.value = 0
            _elapsedSec.value = 0L
            runCatching { current.getStreamClient().resizeCache(if (quality.ultraLowLatency) 512 * 1024 else 4 * 1024 * 1024) }
            adaptiveEnabled = quality.adaptive
            val activeResolution = if (quality.autoRes) Res.P720 else quality.res
            configuredMaxBitrate = quality.bitrateKbps.coerceIn(200, activeResolution.maxKbps.coerceAtLeast(200)).coerceAtLeast(300)
            adaptive = AdaptiveBitrateController(configuredMaxBitrate, 300, configuredMaxBitrate)
            _lastError.value = null
            _status.value = "جارٍ الاتصال بخادم البث…"
            startedAtMs = System.currentTimeMillis()
            _streaming.value = true
            _connected.value = false
            current.startStream("$server/$key")
        } catch (e: Exception) {
            runCatching { if (stream?.isStreaming == true) stream?.stopStream() }
            _streaming.value = false
            _connected.value = false
            startedAtMs = 0L
            _elapsedSec.value = 0L
            appContext.stopService(serviceIntent)
            reportFailure("فشل بدء البث: ${e.message ?: "خطأ في محرك RTMP"}")
        }
    }

    fun stop() {
        stopRequestedByUser = true
        val current = stream
        if (current?.isStreaming == true) runCatching { current.stopStream() }
        _streaming.value = false
        _connected.value = false
        _elapsedSec.value = 0L
        startedAtMs = 0L
        _audioAvailable.value = false
        if (_audioMessage.value.startsWith("صوت USB متصل")) {
            _audioMessage.value = "تم إيقاف التقاط الصوت مع جلسة البث"
        }
        _status.value = "تم إيقاف البث"
        appContext.stopService(Intent(appContext, StreamService::class.java))
    }

    fun tick() {
        if (_streaming.value && capture.state.value != SignalState.CONNECTED) {
            stop()
            reportFailure("توقف البث لأن اتصال كارت الالتقاط انقطع. أعد توصيل الكارت ثم ابدأ جلسة جديدة.")
            return
        }
        if (_streaming.value && startedAtMs > 0L) _elapsedSec.value = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0)
        val current = stream
        if (current != null && current.isStreaming) {
            val client = current.getStreamClient()
            _droppedFrames.value = runCatching { client.getDroppedVideoFrames().toInt() }.getOrDefault(_droppedFrames.value)
            _sentVideoFrames.value = runCatching { client.getSentVideoFrames() }.getOrDefault(_sentVideoFrames.value)
            _sentAudioFrames.value = runCatching { client.getSentAudioFrames() }.getOrDefault(_sentAudioFrames.value)
            _bytesSent.value = runCatching { client.getBytesSend() }.getOrDefault(_bytesSent.value)
        }
        if (selectedOverlay.tickerEnabled && selectedOverlay.tickerText.isNotBlank()) {
            val renderer = overlayRenderer
            val filter = overlayFilter
            if (renderer != null && filter != null && !renderer.bitmap.isRecycled) {
                runCatching {
                    renderer.render(selectedOverlay)
                    filter.setImage(renderer.bitmap)
                }
            }
        }
    }

    private fun cleanupPreparedResources() {
        if (_streaming.value) return

        val oldStream = stream
        val oldRenderer = overlayRenderer

        stream = null
        audioSource = null
        previewHolder = null
        preparedQuality = null
        overlayFilter = null
        sceneFilter = null
        overlayRenderer = null
        loadedLogoUri = null

        runCatching { oldStream?.release() }
        runCatching { oldRenderer?.release() }
    }

    private fun ensurePrepared(quality: QualitySettings): Boolean {
        if (preparing.get()) return false
        val chosenRes = if (quality.autoRes) Res.P720 else quality.res
        val effective = quality.copy(res = chosenRes, autoRes = false)
        if (stream != null && preparedQuality == effective) return true
        if (_streaming.value) {
            reportFailure("أوقف البث قبل تغيير دقة أو إعدادات الترميز.")
            return false
        }
        if (!preparing.compareAndSet(false, true)) return false
        try {
            cleanupPreparedResources()

            val video = UvcVideoSource(appContext, capture)
            val audio = UsbAudioSource(appContext)
            val audioConfig = audio.preferredConfig(48_000, true)
            _audioAvailable.value = false
            _audioMessage.value = "بانتظار بدء جلسة البث وفحص مدخل صوت USB"
            audio.onAvailability = { available, message ->
                _audioAvailable.value = available
                _audioMessage.value = message ?: if (available) "صوت USB متصل" else "صوت USB غير متاح"
                AppNotifications.show(appContext, if (available) "مدخل صوت USB متصل" else "تنبيه مدخل صوت USB", _audioMessage.value, 4301, "tvshere_audio", "تنبيهات الصوت")
            }
            stream = RtmpStream(appContext, this, video, audio).apply {
                setTimestampMode(TimestampMode.CLOCK, TimestampMode.BUFFER)
                getGlInterface().autoHandleOrientation = false
                getStreamClient().setReTries(4)
            }
            audioSource = audio

            val initialBitrate = quality.bitrateKbps.coerceIn(200, chosenRes.maxKbps.coerceAtLeast(200))
            val prepared = stream!!.prepareVideo(
                chosenRes.w, chosenRes.h, initialBitrate * 1000,
                quality.fps.coerceIn(15, 60), 2, 0
            ) && stream!!.prepareAudio(audioConfig.sampleRate, audioConfig.stereo, 128_000)
            if (!prepared) {
                cleanupPreparedResources()
                reportFailure("لم يستطع الهاتف تهيئة مرمّز الفيديو/الصوت بهذه الدقة. جرّب 720p أو 480p.")
                return false
            }

            sceneFilter = createSceneFilter(selectedScene)
            stream!!.getGlInterface().addFilter(0, sceneFilter!!)
            overlayRenderer = OverlayRenderer(chosenRes.w, chosenRes.h)
            loadLogoIfNeeded(selectedOverlay)
            overlayRenderer!!.render(selectedOverlay)
            overlayFilter = ImageFilterRender().apply {
                // RootEncoder sprite scale is expressed in percent (100 = full frame).
                setScale(100f, 100f)
                setPosition(0f, 0f)
                setImage(overlayRenderer!!.bitmap)
            }
            stream!!.getGlInterface().addFilter(overlayFilter!!)
            preparedQuality = effective
            _status.value = "المحرك جاهز — في انتظار كارت الالتقاط"
            startPreviewOnCurrentSurface()
            return true
        } catch (e: Exception) {
            cleanupPreparedResources()
            reportFailure("تعذر إعداد محرك البث: ${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            preparing.set(false)
        }
    }

    fun releaseWhenIdle() {
        if (_streaming.value) return
        val oldView = previewView
        val callback = previewHolderCallback
        if (oldView != null && callback != null) runCatching { oldView.holder.removeCallback(callback) }
        previewHolderCallback = null
        cleanupPreparedResources()
        previewView = null
    }

    private fun reportFailure(message: String) {
        _lastError.value = message
        _status.value = message
        if (lastNotifiedError != message) AppNotifications.show(appContext, "تنبيه Tvshere", message, 4203, "tvshere_stream", "تنبيهات البث")
        lastNotifiedError = message
    }

    override fun onConnectionStarted(url: String) {
        _status.value = "بدأ الاتصال بخادم RTMP"
        AppNotifications.show(appContext, "جارٍ بدء البث", "بدأ Tvshere الاتصال بوجهة RTMP.", 4200, "tvshere_stream", "تنبيهات البث")
    }

    override fun onConnectionSuccess() {
        _streaming.value = true
        _connected.value = true
        _status.value = "البث متصل ويُرسل الفيديو"
        _lastError.value = null
        lastNotifiedError = null
        AppNotifications.show(appContext, "البث متصل", "تم الاتصال بخادم البث ويجري إرسال الفيديو.", 4201, "tvshere_stream", "تنبيهات البث")
    }

    override fun onNewBitrate(bitrate: Long) {
        _bitrateKbps.value = (bitrate / 1000L).toInt().coerceAtLeast(0)
        val current = stream ?: return
        val dropped = runCatching { current.getStreamClient().getDroppedVideoFrames().toInt() }.getOrDefault(0)
        _droppedFrames.value = dropped
        if (adaptiveEnabled) {
            val next = adaptive?.update(current.getStreamClient().hasCongestion(), dropped)
            if (next != null) runCatching { current.setVideoBitrateOnFly(next * 1000) }
        }
    }

    override fun onConnectionFailed(reason: String) {
        if (stopRequestedByUser) {
            _streaming.value = false
            _connected.value = false
            appContext.stopService(Intent(appContext, StreamService::class.java))
            return
        }

        val retried = runCatching { stream?.getStreamClient()?.reTry(3000, reason, null) == true }.getOrDefault(false)
        if (retried) {
            _connected.value = false
            _status.value = "انقطع الاتصال مؤقتًا — تجري إعادة المحاولة"
            AppNotifications.show(appContext, "إعادة الاتصال بالبث", "انقطع الاتصال مؤقتًا؛ يحاول Tvshere الاتصال مجددًا.", 4204, "tvshere_stream", "تنبيهات البث")
        }
        else {
            runCatching { stream?.stopStream() }
            _streaming.value = false
            _connected.value = false
            reportFailure("فشل الاتصال: $reason")
            appContext.stopService(Intent(appContext, StreamService::class.java))
        }
    }

    override fun onDisconnect() {
        val wasConnected = _connected.value
        _streaming.value = false
        _connected.value = false
        startedAtMs = 0L
        if (_status.value == "البث متصل ويُرسل الفيديو") _status.value = "انقطع اتصال البث"
        if (wasConnected && !stopRequestedByUser) AppNotifications.show(appContext, "انقطع البث", "فُقد اتصال خادم البث. افتح Tvshere لمراجعة الحالة.", 4202, "tvshere_stream", "تنبيهات البث")
        appContext.stopService(Intent(appContext, StreamService::class.java))
    }

    override fun onAuthError() {
        _streaming.value = false
        _connected.value = false
        startedAtMs = 0L
        _elapsedSec.value = 0L
        reportFailure("رفض الخادم مفتاح البث أو بيانات المصادقة.")
        runCatching { stream?.stopStream() }
        appContext.stopService(Intent(appContext, StreamService::class.java))
    }

    override fun onAuthSuccess() {
        _status.value = "تم قبول بيانات المصادقة"
    }
}

/** Process-wide owner so a foreground service can keep the same encoder alive when the Activity stops. */
object BroadcastRuntime {
    @Volatile private var instance: BroadcastController? = null

    @Synchronized
    fun obtain(context: Context): BroadcastController {
        return instance ?: BroadcastController(context.applicationContext, CaptureEngine()).also { instance = it }
    }

    @Synchronized
    fun releaseIfIdle(controller: BroadcastController) {
        if (instance === controller && !controller.streaming.value) {
            controller.releaseWhenIdle()
            instance = null
        }
    }
}
