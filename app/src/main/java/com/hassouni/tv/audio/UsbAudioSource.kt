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

    private val lifecycleLock = Any()
    private var generation = 0L

    @Volatile private var running = false
    @Volatile private var keepReading = false
    @Volatile private var reader: AudioRecord? = null
    @Volatile private var worker: Thread? = null

    fun preferredConfig(defaultRate: Int = 48_000, defaultStereo: Boolean = true): UsbAudioConfig {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val input = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
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
        val stereo = if (channels.isEmpty()) {
            defaultStereo
        } else {
            defaultStereo && channels.any { it >= 2 }
        }

        return UsbAudioConfig(chosenRate, stereo)
    }

    override fun create(
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ): Boolean {
        return sampleRate in 8_000..96_000
    }

    private fun isCurrent(session: Long, record: AudioRecord? = null): Boolean {
        synchronized(lifecycleLock) {
            return generation == session &&
                running &&
                keepReading &&
                (record == null || reader === record)
        }
    }

    private fun notifyAvailability(session: Long, available: Boolean, message: String?) {
        synchronized(lifecycleLock) {
            if (generation == session && running && keepReading) {
                onAvailability?.invoke(available, message)
            }
        }
    }

    private fun failSession(session: Long, message: String) {
        synchronized(lifecycleLock) {
            if (generation == session && running) {
                running = false
                keepReading = false
                getMicrophoneData = null
                onAvailability?.invoke(false, message)
            }
        }
    }

    private fun releaseRecord(record: AudioRecord?) {
        if (record == null) return
        if (runCatching {
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)
        ) {
            runCatching { record.stop() }
        }
        runCatching { record.release() }
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        val session = synchronized(lifecycleLock) {
            if (running) return

            generation += 1
            running = true
            keepReading = true
            this.getMicrophoneData = getMicrophoneData
            generation
        }

        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val input = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

        if (input == null) {
            failSession(session, "لم يتعرف Android على مدخل صوت USB في كارت الالتقاط؛ سيعمل البث بالفيديو فقط.")
            return
        }

        if (input.sampleRates.isNotEmpty() && sampleRate !in input.sampleRates) {
            failSession(session, "مدخل USB لا يدعم معدل العينة المحضّر (${sampleRate}Hz). أعد تهيئة الصوت.")
            return
        }

        if (input.channelCounts.isNotEmpty() &&
            isStereo && input.channelCounts.none { it >= 2 }
        ) {
            failSession(session, "مدخل USB لا يدعم الصوت المجسّم الذي جرى تحضير المرمّز عليه.")
            return
        }

        val channelMask = if (isStereo && input.channelCounts.any { it >= 2 }) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }

        val channelCount = if (channelMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1

        val minBuffer = runCatching {
            AudioRecord.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
        }.getOrDefault(AudioRecord.ERROR_BAD_VALUE)

        if (minBuffer <= 0) {
            failSession(session, "مدخل USB لا يدعم إعداد الصوت ${sampleRate}Hz.")
            return
        }

        val bufferBytes = max(minBuffer * 2, sampleRate * channelCount / 25)

        val record = try {
            val unprocessed = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.UNPROCESSED,
                    sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
            }.getOrNull()

            if (unprocessed?.state == AudioRecord.STATE_INITIALIZED) {
                unprocessed
            } else {
                unprocessed?.release()
                AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT,
                    sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
            }
        } catch (e: SecurityException) {
            failSession(session, "إذن تسجيل الصوت غير ممنوح.")
            return
        } catch (e: Exception) {
            failSession(session, e.message ?: "تعذر إنشاء مدخل صوت USB.")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            releaseRecord(record)
            failSession(session, "تعذر تهيئة AudioRecord مع مدخل USB.")
            return
        }

        val preferred = runCatching {
            record.setPreferredDevice(input)
        }.getOrDefault(false)

        if (!preferred) {
            releaseRecord(record)
            failSession(
                session,
                "رفض Android توجيه AudioRecord إلى مدخل USB؛ أوقفنا الصوت لمنع استخدام ميكروفون الهاتف."
            )
            return
        }

        try {
            record.startRecording()
        } catch (e: Exception) {
            releaseRecord(record)
            failSession(session, e.message ?: "تعذر بدء التقاط صوت USB.")
            return
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            releaseRecord(record)
            failSession(session, "لم يبدأ مدخل صوت USB التسجيل.")
            return
        }

        val accepted = synchronized(lifecycleLock) {
            if (generation != session || !running || !keepReading) {
                false
            } else {
                reader = record

                val sessionCallback = getMicrophoneData
                val newWorker = thread(
                    name = "hassouni-usb-audio-$session",
                    isDaemon = true,
                    start = false
                ) {
                    readSession(
                        session, record, input, minBuffer,
                        channelCount, sessionCallback
                    )
                }

                worker = newWorker
                newWorker.start()
                true
            }
        }

        if (!accepted) {
            releaseRecord(record)
        }
    }

    private fun readSession(
        session: Long,
        record: AudioRecord,
        input: AudioDeviceInfo,
        minBuffer: Int,
        channelCount: Int,
        pcmCallback: GetMicrophoneData
    ) {
        try {
            var routedDevice = runCatching { record.routedDevice }.getOrNull()
            val routeDeadline = SystemClock.elapsedRealtime() + 1_200L

            while (
                isCurrent(session, record) &&
                routedDevice?.id != input.id &&
                SystemClock.elapsedRealtime() < routeDeadline
            ) {
                try {
                    Thread.sleep(40L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }

                routedDevice = runCatching { record.routedDevice }.getOrNull()
            }

            if (!isCurrent(session, record)) return

            if (routedDevice?.id != input.id) {
                notifyAvailability(
                    session,
                    false,
                    "لم يؤكد Android توجيه الصوت إلى مدخل USB؛ لم نستخدم ميكروفون الهاتف كبديل."
                )
                return
            }

            notifyAvailability(
                session,
                true,
                "صوت USB متصل (${sampleRate}Hz، ${if (channelCount == 2) "ستيريو" else "أحادي"})"
            )

            val buffer = ByteArray(max(minBuffer, 4096))

            while (isCurrent(session, record)) {
                val count = runCatching {
                    record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                }.getOrDefault(-1)

                if (!isCurrent(session, record)) break

                if (count > 0) {
                    val pcm = AudioMixer.process(buffer, count, settings)
                    val frame = Frame(
                        pcm,
                        0,
                        pcm.size,
                        System.nanoTime() / 1_000L
                    )

                    synchronized(lifecycleLock) {
                        if (generation == session &&
                            running && keepReading && reader === record
                        ) {
                            runCatching { pcmCallback.inputPCMData(frame) }
                            runCatching { onLevel?.invoke(AudioMixer.level(pcm)) }
                        }
                    }
                } else if (count < 0) {
                    if (isCurrent(session, record)) {
                        notifyAvailability(
                            session,
                            false,
                            "انقطع إدخال صوت USB (AudioRecord: $count)."
                        )
                    }
                    break
                }
            }
        } finally {
            finishWorker(session, record)
        }
    }

     private fun finishWorker(session: Long, record: AudioRecord) {
        val owned = synchronized(lifecycleLock) {
            if (generation == session && reader === record) {
                reader = null
                worker = null
                running = false
                keepReading = false
                getMicrophoneData = null
                true
            } else {
                false
            }
        }

        if (owned) releaseRecord(record)
    }

    override fun stop() {
        val resources = synchronized(lifecycleLock) {
            generation += 1

            val oldRecord = reader
            val oldWorker = worker

            reader = null
            worker = null
            keepReading = false
            running = false
            getMicrophoneData = null

            oldRecord to oldWorker
        }

        resources.second?.interrupt()
        releaseRecord(resources.first)
    }

    override fun isRunning(): Boolean = running

    override fun release() = stop()
}
