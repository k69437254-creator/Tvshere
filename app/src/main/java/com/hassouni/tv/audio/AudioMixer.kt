package com.hassouni.tv.audio

import com.hassouni.tv.data.AudioSettings

/** PCM 16-bit little-endian gain and mute processing before AAC encoding. */
object AudioMixer {
    fun process(tv: ByteArray, size: Int, settings: AudioSettings): ByteArray {
        val count = size.coerceIn(0, tv.size)
        val output = ByteArray(count)
        if (settings.tvMuted) return output
        var index = 0
        while (index + 1 < count) {
            val value = (sample(tv, index) * settings.tvVolume)
                .coerceIn(-32768f, 32767f).toInt()
            output[index] = (value and 0xFF).toByte()
            output[index + 1] = ((value shr 8) and 0xFF).toByte()
            index += 2
        }
        if (index < count) output[index] = tv[index]
        return output
    }

    private fun sample(bytes: ByteArray, index: Int): Short =
        ((bytes[index + 1].toInt() shl 8) or (bytes[index].toInt() and 0xFF)).toShort()

    fun level(pcm: ByteArray): Float {
        var peak = 0
        var index = 0
        while (index + 1 < pcm.size) {
            peak = maxOf(peak, kotlin.math.abs(sample(pcm, index).toInt()))
            index += 2
        }
        return peak / 32768f
    }
}
