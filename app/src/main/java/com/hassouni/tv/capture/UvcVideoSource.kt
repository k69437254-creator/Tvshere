package com.hassouni.tv.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.view.Surface
import androidx.core.content.ContextCompat
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import java.util.ArrayList
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource

/**
 * A single UVC owner shared by RootEncoder preview and RTMP encoding.
 * The capture card is opened once; RootEncoder's GL pipeline fans frames out to
 * both the local preview and the encoder surface.
 */
class UvcVideoSource(
    private val context: Context,
    private val capture: CaptureEngine
) : VideoSource() {
    private var cameraHelper: ICameraHelper? = null
    private var outputSurface: Surface? = null
    @Volatile private var running = false

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean =
        width in 160..3840 && height in 120..2160 && fps in 1..120

    override fun start(surfaceTexture: SurfaceTexture) {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            capture.setState(com.hassouni.tv.data.SignalState.WEAK)
            capture.setError("إذن الكاميرا غير ممنوح. اسمح به ثم أعد فتح المعاينة.")
            return
        }
        val surface = Surface(surfaceTexture)
        outputSurface = surface
        val helper = runCatching { CameraHelper() }.getOrElse {
            surface.release()
            outputSurface = null
            capture.setState(com.hassouni.tv.data.SignalState.WEAK)
            capture.setError(it.message ?: "تعذر تهيئة USB UVC")
            return
        }
        cameraHelper = helper
        helper.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice?) {
                if (cameraHelper !== helper) return
                capture.clearError()
                device?.let { helper.selectDevice(it) }
            }

            override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
                if (cameraHelper !== helper) return
                helper.openCamera()
            }

            override fun onCameraOpen(device: UsbDevice?) {
                if (cameraHelper !== helper) return
                val current = helper
                val candidates = compatiblePreviewSizes(current.supportedSizeList.orEmpty())
                var selected = false
                for (candidate in candidates) {
                    val accepted = runCatching {
                        current.previewSize = candidate
                        true
                    }.getOrDefault(false)
                    if (accepted) {
                        selected = true
                        break
                    }
                }
                if (!selected && candidates.isNotEmpty()) {
                    capture.setError("تعذر تطبيق أوضاع الدقة المقترحة؛ نحاول وضع الكارت الافتراضي.")
                }
                // Attach the encoder surface before starting UVC preview. This order also
                // avoids negotiation/renderer timing failures reported by some HDMI dongles.
                try {
                    val targetSurface = outputSurface?.takeIf { it.isValid }
                        ?: error("سطح إخراج الفيديو غير جاهز؛ أعد فتح المعاينة.")
                    current.addSurface(targetSurface, false)
                    current.startPreview()
                    if (!current.isCameraOpened) error("لم يؤكد UVC فتح الكاميرا.")
                    capture.clearError()
                    capture.setState(com.hassouni.tv.data.SignalState.CONNECTED)
                } catch (e: Exception) {
                    removeOutputSurfaceFromHelper()
                    capture.setState(com.hassouni.tv.data.SignalState.WEAK)
                    capture.setError(e.message ?: "فشل تفاوض UVC مع دقة كارت الالتقاط.")
                }
            }

            override fun onCameraClose(device: UsbDevice?) {
                if (cameraHelper !== helper) return
                removeOutputSurfaceFromHelper()
                capture.setState(com.hassouni.tv.data.SignalState.DISCONNECTED)
            }

            override fun onDeviceClose(device: UsbDevice?) = Unit

            override fun onDetach(device: UsbDevice?) {
                if (cameraHelper !== helper) return
                removeOutputSurfaceFromHelper()
                capture.setState(com.hassouni.tv.data.SignalState.DISCONNECTED)
                capture.setError("تم فصل كارت الالتقاط USB.")
            }

            override fun onCancel(device: UsbDevice?) {
                if (cameraHelper !== helper) return
                capture.setState(com.hassouni.tv.data.SignalState.WEAK)
                capture.setError("لم يُمنح إذن USB للكارت. أعد توصيله ووافق على نافذة الإذن.")
            }

            override fun onError(device: UsbDevice?, e: CameraException?) {
                if (cameraHelper !== helper) return
                removeOutputSurfaceFromHelper()
                capture.setState(com.hassouni.tv.data.SignalState.WEAK)
                capture.setError(e?.message?.takeIf { it.isNotBlank() }
                    ?: "تعذر فتح كارت الالتقاط. تحقق من OTG والطاقة ودقة خرج HDMI.")
            }
        })
        running = true
    }


    /** Select a real advertised UVC mode near the encoder target instead of blindly using
     * the capture card's largest default mode (which commonly fails USB bandwidth negotiation). */
    private fun compatiblePreviewSizes(sizes: List<Size>): List<Size> {
        if (sizes.isEmpty()) return emptyList()
        data class Candidate(val size: Size, val fits: Boolean, val pixels: Long, val aspectError: Float, val fpsError: Int, val mjpeg: Boolean)
        return sizes.mapNotNull { source ->
            val rates = source.fpsList?.takeIf { it.isNotEmpty() } ?: listOf(source.fps)
            val validRates = rates.filter { it > 0 }
            val chosenFps = validRates.filter { it <= fps }.maxOrNull()
                ?: validRates.minOrNull() ?: source.fps.takeIf { it > 0 } ?: return@mapNotNull null
            if (source.width <= 0 || source.height <= 0) return@mapNotNull null
            val candidateSize = Size(source.type, source.width, source.height, chosenFps, ArrayList(validRates.ifEmpty { listOf(chosenFps) }))
            val pixelCount = source.width.toLong() * source.height.toLong()
            val fits = source.width <= width && source.height <= height
            val targetAspect = width.toFloat() / height.coerceAtLeast(1)
            val aspectError = kotlin.math.abs(source.width.toFloat() / source.height - targetAspect)
            Candidate(candidateSize, fits, pixelCount, aspectError, kotlin.math.abs(chosenFps - fps), source.type == UVCCamera.UVC_VS_FRAME_MJPEG)
        }.sortedWith(
            compareBy<Candidate>({ if (it.mjpeg) 0 else 1 }, { if (it.fits) 0 else 1 },
                { if (it.fits) -it.pixels else it.pixels }, { it.fpsError }, { it.aspectError })
        ).map { it.size }
    }

    private fun removeOutputSurfaceFromHelper() {
        val surface = outputSurface
        if (surface != null) runCatching { cameraHelper?.removeSurface(surface) }
    }

    override fun stop() {
        running = false
        val surface = outputSurface
        outputSurface = null
        val helper = cameraHelper
        cameraHelper = null
        if (surface != null) runCatching { helper?.removeSurface(surface) }
        runCatching { helper?.setStateCallback(null) }
        runCatching { helper?.release() }
        runCatching { surface?.release() }
        capture.setState(com.hassouni.tv.data.SignalState.DISCONNECTED)
    }

    override fun release() = stop()
    override fun isRunning(): Boolean = running
    override fun getOrientationConfig() = OrientationConfig(forced = OrientationForced.LANDSCAPE)
}
