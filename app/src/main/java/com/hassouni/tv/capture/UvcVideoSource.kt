package com.hassouni.tv.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbConstants
import android.view.Surface
import com.hassouni.tv.notifications.AppNotifications
import androidx.core.content.ContextCompat
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicLong
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
    private val lifecycleLock = Any()
    private val lifecycleGeneration = AtomicLong(0L)

    private fun isActive(helper: ICameraHelper, token: Long): Boolean =
        running && lifecycleGeneration.get() == token && cameraHelper === helper

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean =
        width in 160..3840 && height in 120..2160 && fps in 1..120

    override fun start(surfaceTexture: SurfaceTexture) {
        val token = synchronized(lifecycleLock) {
            if (running) return
            running = true
            lifecycleGeneration.incrementAndGet()
        }
        capture.resetDiagnostics("جارٍ فحص اتصال USB وكارت الالتقاط UVC…")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            capture.setState(com.hassouni.tv.data.SignalState.WEAK)
            capture.setError("إذن الكاميرا غير ممنوح. اسمح به ثم أعد فتح المعاينة.")
            capture.updateDiagnostics { it.copy(message = "تعذر الفحص لأن إذن الكاميرا غير ممنوح.") }
            synchronized(lifecycleLock) {
                if (lifecycleGeneration.get() == token) running = false
            }
            return
        }
        val surface = Surface(surfaceTexture)
        val helper = runCatching { CameraHelper() }.getOrElse {
            surface.release()
            synchronized(lifecycleLock) {
                if (lifecycleGeneration.get() == token) running = false
            }
            capture.setState(com.hassouni.tv.data.SignalState.WEAK)
            capture.setError(it.message ?: "تعذر تهيئة USB UVC")
            return
        }
        val installed = synchronized(lifecycleLock) {
            if (!running || lifecycleGeneration.get() != token) {
                false
            } else {
                outputSurface = surface
                cameraHelper = helper
                true
            }
        }
        if (!installed) {
            runCatching { helper.release() }
            runCatching { surface.release() }
            return
        }

        helper.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice?) {
                synchronized(lifecycleLock) {
                    if (!isActive(helper, token)) return
                    val hasVideoInterface = device?.let { usb ->
                        (0 until usb.interfaceCount).any { index ->
                            runCatching { usb.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VIDEO }.getOrDefault(false)
                        }
                    } ?: false
                    capture.updateDiagnostics {
                        it.copy(
                            usbDetected = device != null,
                            uvcInterface = hasVideoInterface,
                            usbPermissionGranted = null,
                            cameraOpened = false,
                            previewStartCallReturned = false,
                            supportedModes = 0,
                            deviceName = device?.productName?.takeIf { name -> name.isNotBlank() } ?: device?.deviceName,
                            message = if (hasVideoInterface) "تم اكتشاف جهاز USB يعلن واجهة فيديو UVC؛ جارٍ طلب الإذن وفتح الكاميرا." else "تم اكتشاف جهاز USB، لكن لم تُثبت واجهة فيديو UVC لهذا الجهاز."
                        )
                    }
                    if (device != null && !hasVideoInterface) {
                        capture.setState(com.hassouni.tv.data.SignalState.WEAK)
                        capture.setError("الجهاز المكتشف لا يعلن واجهة فيديو UVC التي يحتاجها Tvshere.")
                        return
                    }
                    capture.clearError()
                    device?.let { helper.selectDevice(it) }

                }
}

            override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
                synchronized(lifecycleLock) {
                    if (!isActive(helper, token)) return
                    capture.updateDiagnostics { it.copy(usbPermissionGranted = true, message = "تم فتح جهاز USB؛ جارٍ فتح كاميرا UVC.") }
                    helper.openCamera()

                }
}

            override fun onCameraOpen(device: UsbDevice?) {
                synchronized(lifecycleLock) {
                    if (!isActive(helper, token)) return
                    val current = helper
                    val advertisedModes = current.supportedSizeList.orEmpty()
                    capture.updateDiagnostics {
                        it.copy(cameraOpened = true, supportedModes = advertisedModes.size,
                            message = if (advertisedModes.isNotEmpty()) "تم فتح كاميرا UVC واكتشاف ${advertisedModes.size} وضع فيديو معلن." else "تم فتح كاميرا UVC، لكن لم تُعلن قائمة أوضاع فيديو.")
                    }
                    val candidates = compatiblePreviewSizes(advertisedModes)
                    var selectedSize: Size? = null
                    for (candidate in candidates) {
                        val accepted = runCatching {
                            current.previewSize = candidate
                            true
                        }.getOrDefault(false)
                        if (accepted) {
                            selectedSize = candidate
                            break
                        }
                    }
                    selectedSize?.let { chosen ->
                        capture.updateDiagnostics { it.copy(previewWidth = chosen.width, previewHeight = chosen.height) }
                    }
                    val selected = selectedSize != null
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
                        capture.updateDiagnostics { it.copy(previewStartCallReturned = true, message = "تم فتح UVC وعاد استدعاء بدء المعاينة دون استثناء؛ لم يتم التحقق من وصول إطارات الفيديو.") }
                        capture.setState(com.hassouni.tv.data.SignalState.CONNECTED)
                        AppNotifications.show(context, "تم اكتشاف كارت الالتقاط", "تم فتح جهاز UVC وعاد استدعاء بدء المعاينة دون استثناء.", 4101, "tvshere_usb", "تنبيهات USB")
                    } catch (e: Exception) {
                        removeOutputSurfaceFromHelper()
                        capture.updateDiagnostics {
                            it.copy(
                                cameraOpened = current.isCameraOpened,
                                previewStartCallReturned = false,
                                message = "فشل بدء معاينة UVC: ${e.message ?: "تحقق من اتصال كارت الالتقاط."}"
                            )
                        }
                        capture.setState(com.hassouni.tv.data.SignalState.WEAK)
                        capture.setError(e.message ?: "فشل تفاوض UVC مع دقة كارت الالتقاط.")
                    }

                }
}

            override fun onCameraClose(device: UsbDevice?) {
                synchronized(lifecycleLock) {
                    if (!isActive(helper, token)) return
                    removeOutputSurfaceFromHelper()
                    capture.updateDiagnostics { it.copy(cameraOpened = false, previewStartCallReturned = false, message = "أُغلقت كاميرا UVC.") }
                    capture.setState(com.hassouni.tv.data.SignalState.DISCONNECTED)

                }
}

            override fun onDeviceClose(device: UsbDevice?) = Unit

            override fun onDetach(device: UsbDevice?) {
                synchronized(lifecycleLock) {
                    if (!isActive(helper, token)) return
                    removeOutputSurfaceFromHelper()
                    capture.resetDiagnostics("تم فصل جهاز الالتقاط USB. افحص OTG والطاقة ثم أعد التوصيل.")
                    capture.setState(com.hassouni.tv.data.SignalState.DISCONNECTED)
                    capture.setError("تم فصل كارت الالتقاط USB.")
                    AppNotifications.show(context, "انفصل كارت الالتقاط", "تم فصل جهاز USB؛ افحص كابل OTG والطاقة.", 4102, "tvshere_usb", "تنبيهات USB")
                }
            }
            override fun onCancel(device: UsbDevice?) {
                synchronized(lifecycleLock) {
                    if (!isActive(helper, token)) return
                    capture.updateDiagnostics { it.copy(usbPermissionGranted = false, cameraOpened = false, previewStartCallReturned = false, message = "لم يُمنح إذن USB للكارت.") }
                    capture.setState(com.hassouni.tv.data.SignalState.WEAK)
                    capture.setError("لم يُمنح إذن USB للكارت. أعد توصيله ووافق على نافذة الإذن.")

                }
}

            override fun onError(device: UsbDevice?, e: CameraException?) {
                synchronized(lifecycleLock) {
                    if (!isActive(helper, token)) return
                    removeOutputSurfaceFromHelper()
                    capture.updateDiagnostics { it.copy(cameraOpened = false, previewStartCallReturned = false, message = "فشل فتح كاميرا UVC: ${e?.message ?: "خطأ غير محدد"}") }
                    capture.setState(com.hassouni.tv.data.SignalState.WEAK)
                    capture.setError(e?.message?.takeIf { it.isNotBlank() }
                        ?: "تعذر فتح كارت الالتقاط. تحقق من OTG والطاقة ودقة خرج HDMI.")
                    AppNotifications.show(context, "تعذر فتح كارت الالتقاط", e?.message ?: "تحقق من OTG والطاقة وتوافق UVC.", 4103, "tvshere_usb", "تنبيهات USB")

                }
}
        })
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
        val detached = synchronized(lifecycleLock) {
            lifecycleGeneration.incrementAndGet()
            running = false
            val oldHelper = cameraHelper
            val oldSurface = outputSurface
            cameraHelper = null
            outputSurface = null
            oldHelper to oldSurface
        }

        val helper = detached.first
        val surface = detached.second

        // External helper operations stay outside lifecycleLock.
        if (surface != null) runCatching { helper?.removeSurface(surface) }
        runCatching { helper?.setStateCallback(null) }
        runCatching { helper?.release() }
        runCatching { surface?.release() }

        capture.updateDiagnostics {
            it.copy(
                cameraOpened = false,
                previewStartCallReturned = false,
                previewWidth = 0,
                previewHeight = 0,
                message = "تم إيقاف مصدر الفيديو؛ معلومات اكتشاف USB محفوظة."
            )
        }
        capture.setState(com.hassouni.tv.data.SignalState.DISCONNECTED)
    }

    override fun release() = stop()
    override fun isRunning(): Boolean = running
    override fun getOrientationConfig() = OrientationConfig(forced = OrientationForced.LANDSCAPE)
}
