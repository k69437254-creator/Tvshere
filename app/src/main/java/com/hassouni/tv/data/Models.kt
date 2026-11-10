package com.hassouni.tv.data

enum class SignalState(val label: String) { DISCONNECTED("غير متصل"), CONNECTED("متصل"), WEAK("إشارة ضعيفة") }
enum class Platform(val label: String, val hint: String) {
    TELEGRAM("Telegram Live", "الصق رابط RTMP/RTMPS الذي وفرته Telegram"),
    TIKTOK("TikTok Live", "الصق رابط الخادم الذي وفره TikTok لهذه الجلسة"),
    CUSTOM("خادم RTMP مخصص", "rtmp://server/app")
}
enum class Res(val label: String, val w: Int, val h: Int, val maxKbps: Int) {
    P1080("1080p", 1920, 1080, 6000), P720("720p", 1280, 720, 3500),
    P480("480p", 854, 480, 1500), P360("360p", 640, 360, 800), P240("240p", 426, 240, 400)
}
data class StreamTarget(val platform: Platform = Platform.TELEGRAM, val url: String = "", val key: String = "", val showKey: Boolean = false)
data class QualitySettings(
    val autoRes: Boolean = true, val res: Res = Res.P720, val fps: Int = 30,
    val bitrateKbps: Int = 2500, val adaptive: Boolean = true, val ultraLowLatency: Boolean = false
)
data class AudioSettings(val tvVolume: Float = 1f, val tvMuted: Boolean = false)
data class OverlayState(
    val watermarkUri: String? = null, val watermarkOpacity: Float = 0.8f,
    val scoreEnabled: Boolean = false, val home: String = "", val away: String = "",
    val homeScore: Int = 0, val awayScore: Int = 0, val minute: String = "",
    val tickerEnabled: Boolean = false, val tickerText: String = ""
)
data class LiveStats(
    val bitrateKbps: Int = 0, val dropped: Int = 0, val cpu: Int = 0, val ramMb: Int = 0,
    val tempC: Float = 0f, val elapsedSec: Long = 0,
    val sentVideoFrames: Long = 0, val sentAudioFrames: Long = 0, val bytesSent: Long = 0
)
