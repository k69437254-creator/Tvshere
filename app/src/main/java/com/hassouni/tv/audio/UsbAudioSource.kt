package com.hassouni.tv.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import com.hassouni.tv.data.AudioSettings
import kotlin.concurrent.thread
import kotlin.math.max

/** Preferred PCM settings chosen from the capabilities reported by a USB Audio Class input. */
data class UsbAudioConfig(val sampleRate: Int, val stereo: Boolean)

/** AudioSource that accepts USB/UAC inputs only. It never routes to the phone mic. */
class UsbAudioSource(private val context: Context) : AudioSource() {
    @Volatile var settings: AudioSettings = AudioSettings()
    @Volatile var onLevel: ((Float) -> Unit)? = null
    @Volatile var onAvailability: ((Boolean, String?) -> Unit)? = null
    @Volatile private var running = false
    @Volatile private var keepReading = false
    @Volatile private var reader: AudioRecord? = null
    @Volatile private var worker: Thread? = null

    /** Match the encoder's audio format to the attached USB input when Android exposes its capabilities. */
    fun preferredConfig(defaultRate: Int = 48_000, defaultStereo: Boolean = true): UsbAudioConfig {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val input = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val supportedRates = input?.sampleRates?.filter { it in 8_000..96_000 }.orEmpty()
        val chosenRate = when {
            supportedRates.isEmpty() -> defaultRate
            defaultRate in supportedRates -> defaultRate
            48_000 in supportedRates -> 48_000
            44_100 in supportedRates -> 44_100
            else -> supportedRates.minBy { kotlin.math.abs(it - defaultRate) }
        }
        val channels = input?.channelCounts ?: intArrayOf()
        val stereo = if (channels.isEmpty()) defaultStereo else defaultStereo && channels.any { it >= 2 }
        return UsbAudioConfig(chosenRate, stereo)
    }

    override fun create(sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean): Boolean {
        // AudioRecord is created in start(), after runtime RECORD_AUDIO permission is granted.
        return sampleRate in 8_000..96_000
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        this.getMicrophoneData = getMicrophoneData
        if (running) return
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val input = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        if (input == null) {
            onAvailability?.invoke(false, "لم يتعرف Android على مدخل صوت USB في كارت الالتقاط؛ سيعمل البث بالفيديو فقط.")
            return
        }
        if (input.sampleRates.isNotEmpty() && sampleRate !in input.sampleRates) {
            onAvailability?.invoke(false, "مدخل USB لا يدعم معدل العينة المحضّر (${sampleRate}Hz). أعد تهيئة الصوت.")
            return
        }
        if (input.channelCounts.isNotEmpty() && isStereo && input.channelCounts.none { it >= 2 }) {
            onAvailability?.invoke(false, "مدخل USB لا يدعم الصوت المجسّم الذي جرى تحضير المرمّز عليه.")
            return
        }

        val channelMask = if (isStereo && input.channelCounts.any { it >= 2 })
            AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val channelCount = if (channelMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
        val minBuffer = runCatching {
            AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        }.getOrDefault(AudioRecord.ERROR_BAD_VALUE)
        if (minBuffer <= 0) {
            onAvailability?.invoke(false, "مدخل USB لا يدعم إعداد الصوت ${sampleRate}Hz.")
            return
        }

        val bufferBytes = max(minBuffer * 2, sampleRate * channelCount / 25)
        val record = try {
            val unprocessed = runCatching {
                AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, sampleRate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            }.getOrNull()
            if (unprocessed?.state == AudioRecord.STATE_INITIALIZED) unprocessed else {
                unprocessed?.release()
                AudioRecord(MediaRecorder.AudioSource.DEFAULT, sampleRate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            }
        } catch (e: SecurityException) {
            onAvailability?.invoke(false, "إذن تسجيل الصوت غير ممنوح.")
            return
        } catch (e: Exception) {
            onAvailability?.invoke(false, e.message ?: "تعذر إنشاء مدخل صوت USB.")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onAvailability?.invoke(false, "تعذر تهيئة AudioRecord مع مدخل USB.")
            return
        }
        val preferred = runCatching { record.setPreferredDevice(input) }.getOrDefault(false)
        if (!preferred) {
            record.release()
            onAvailability?.invoke(false, "رفض Android توجيه AudioRecord إلى مدخل USB؛ أوقفنا الصوت لمنع استخدام ميكروفون الهاتف.")
            return
        }
        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            onAvailability?.invoke(false, e.message ?: "تعذر بدء التقاط صوت USB.")
            return
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            onAvailability?.invoke(false, "لم يبدأ مدخل صوت USB التسجيل.")
            return
        }

        reader = record
        running = true
        keepReading = true
        worker = thread(name = "hassouni-usb-audio", isDaemon = true) {
            // setPreferredDevice is a request; verify the actual route before forwarding PCM.
            var routedDevice = runCatching { record.routedDevice }.getOrNull()
            val routeDeadline = SystemClock.elapsedRealtime() + 1_200L
            while (keepReading && routedDevice?.id != input.id && SystemClock.elapsedRealtime() < routeDeadline) {
                try { Thread.sleep(40L) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                routedDevice = runCatching { record.routedDevice }.getOrNull()
            }
            if (!keepReading) return@thread
            if (routedDevice?.id != input.id) {
                onAvailability?.invoke(false, "لم يؤكد Android توجيه الصوت إلى مدخل USB؛ لم نستخدم ميكروفون الهاتف كبديل.")
                if (reader === record) reader = null
                keepReading = false
                running = false
                runCatching { record.stop() }
                runCatching { record.release() }
                return@thread
            }
            onAvailability?.invoke(true, "صوت USB متصل (${sampleRate}Hz، ${if (channelCount == 2) "ستيريو" else "أحادي"})")
            val buffer = ByteArray(max(minBuffer, 4096))
            try {
                while (keepReading) {
                    val count = runCatching { record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) }.getOrDefault(-1)
                    if (count > 0) {
                        val pcm = AudioMixer.process(buffer, count, settings)
                        val frame = Frame(pcm, 0, pcm.size, System.nanoTime() / 1_000L)
                        runCatching { this.getMicrophoneData?.inputPCMData(frame) }
                        onLevel?.invoke(AudioMixer.level(pcm))
                    } else if (count < 0) {
                        if (keepReading) onAvailability?.invoke(false, "انقطع إدخال صوت USB (AudioRecord: $count).")
                        break
                    }
                }
            } finally {
                running = false
                // If read() ends unexpectedly, release the recorder rather than keeping
                // an initialized but dead AudioRecord attached to the stream session.
                if (reader === record) {
                    reader = null
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { record.stop() }
                    runCatching { record.release() }
                }
            }
        }
    }

    override fun stop() {
        keepReading = false
        val current = reader
        reader = null
        if (current != null) {
            if (current.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { current.stop() }
            runCatching { current.release() }
        }
        worker?.interrupt()
        worker = null
        running = false
        getMicrophoneData = null
    }

    override fun isRunning(): Boolean = running
    override fun release() = stop()
}
