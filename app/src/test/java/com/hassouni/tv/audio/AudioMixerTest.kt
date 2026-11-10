package com.hassouni.tv.audio

import com.hassouni.tv.data.AudioSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMixerTest {
    @Test fun muteProducesSilence() {
        val input = byteArrayOf(0xE8.toByte(), 0x03, 0x18, 0xFC.toByte()) // +1000, -1000
        assertEquals(listOf<Byte>(0, 0, 0, 0), AudioMixer.process(input, input.size, AudioSettings(tvMuted = true)).toList())
    }

    @Test fun volumeScalesPcm16LittleEndianSamples() {
        val input = byteArrayOf(0xE8.toByte(), 0x03, 0x18, 0xFC.toByte())
        val result = AudioMixer.process(input, input.size, AudioSettings(tvVolume = 0.5f))
        assertEquals(500, ((result[1].toInt() shl 8) or (result[0].toInt() and 0xff)).toShort().toInt())
        assertEquals(-500, ((result[3].toInt() shl 8) or (result[2].toInt() and 0xff)).toShort().toInt())
    }

    @Test fun outputHonorsValidReadLength() {
        val input = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertEquals(3, AudioMixer.process(input, 3, AudioSettings()).size)
    }
}
