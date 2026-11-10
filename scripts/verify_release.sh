#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

[[ -f app/src/main/java/com/hassouni/tv/stream/BroadcastController.kt ]] || fail "BroadcastController is missing"
[[ -f app/src/main/java/com/hassouni/tv/capture/UvcVideoSource.kt ]] || fail "UVC video source is missing"
[[ -f app/src/main/java/com/hassouni/tv/audio/UsbAudioSource.kt ]] || fail "USB audio source is missing"
[[ -f app/src/main/java/com/hassouni/tv/overlay/OverlayRenderer.kt ]] || fail "overlay renderer is missing"
[[ -f app/src/main/java/com/hassouni/tv/stream/StreamService.kt ]] || fail "foreground stream service is missing"
[[ -f app/src/test/java/com/hassouni/tv/audio/AudioMixerTest.kt ]] || fail "AudioMixer unit tests are missing"
[[ -f app/src/test/java/com/hassouni/tv/stream/AdaptiveBitrateControllerTest.kt ]] || fail "adaptive bitrate unit tests are missing"

grep -q 'RootEncoder:library:2.8.1' app/build.gradle.kts || fail "RootEncoder dependency is not pinned"
grep -q 'com.herohan:UVCAndroid:1.0.13' app/build.gradle.kts || fail "UVCAndroid dependency is missing"
grep -q 'FOREGROUND_SERVICE_MICROPHONE' app/src/main/AndroidManifest.xml || fail "microphone foreground permission is missing"
grep -q 'RECORD_AUDIO' app/src/main/AndroidManifest.xml || fail "audio permission is missing"
grep -q 'current.startStream(' app/src/main/java/com/hassouni/tv/stream/BroadcastController.kt || fail "RTMP start path is missing"
grep -q 'capture.state.value != SignalState.CONNECTED' app/src/main/java/com/hassouni/tv/stream/BroadcastController.kt || fail "broadcast can start without an attached UVC source"
grep -q 'fun retryCapture()' app/src/main/java/com/hassouni/tv/stream/BroadcastController.kt || fail "capture retry/recovery path is missing"
grep -q 'compatiblePreviewSizes' app/src/main/java/com/hassouni/tv/capture/UvcVideoSource.kt || fail "UVC preview mode negotiation is missing"
grep -q 'current.previewSize = candidate' app/src/main/java/com/hassouni/tv/capture/UvcVideoSource.kt || fail "UVC does not apply a verified advertised mode"
grep -q 'getDroppedVideoFrames' app/src/main/java/com/hassouni/tv/stream/BroadcastController.kt || fail "real dropped-frame counter is missing"
grep -q 'getBytesSend' app/src/main/java/com/hassouni/tv/stream/BroadcastController.kt || fail "real sent-byte counter is missing"
grep -q 'getSentVideoFrames' app/src/main/java/com/hassouni/tv/stream/BroadcastController.kt || fail "real sent-video-frame counter is missing"
grep -q 'ImageFilterRender' app/src/main/java/com/hassouni/tv/stream/BroadcastController.kt || fail "overlay is not connected to encoder GL pipeline"
grep -q 'AudioRecord' app/src/main/java/com/hassouni/tv/audio/UsbAudioSource.kt || fail "USB audio capture source is missing"
grep -q 'setPreferredDevice(input)' app/src/main/java/com/hassouni/tv/audio/UsbAudioSource.kt || fail "USB input routing is missing"
grep -q 'record.routedDevice' app/src/main/java/com/hassouni/tv/audio/UsbAudioSource.kt || fail "actual USB audio route is not verified"
grep -q 'setScale(100f, 100f)' app/src/main/java/com/hassouni/tv/stream/BroadcastController.kt || fail "overlay does not cover the encoder frame"
grep -q 'usesCleartextTraffic="true"' app/src/main/AndroidManifest.xml || fail "RTMP cleartext network permission is not declared"
grep -q 'PARTIAL_WAKE_LOCK' app/src/main/java/com/hassouni/tv/stream/StreamService.kt || fail "screen-off CPU wake lock is missing"
grep -q 'resetDroppedVideoFrames()' app/src/main/java/com/hassouni/tv/stream/BroadcastController.kt || fail "per-session sender counters are not reset"

if grep -R -n -E 'لم يُدمج بعد|لم تُفعّل بعد|محرك RTMP لم يُدمج|Phase 2|phase2_pending' README.md app/src/main/java; then
  fail "preview-only placeholders remain in user-facing source"
fi
[[ ! -e phase2_pending/RtmpStreamer.kt ]] || fail "obsolete RTMP prototype remains"
[[ ! -e app/src/main/java/com/hassouni/tv/capture/UvcController.kt ]] || fail "obsolete independent UVC controller remains"
[[ ! -e app/src/main/java/com/hassouni/tv/audio/TvAudioSource.kt ]] || fail "obsolete unused audio source remains"

pass "integrated UVC/GL/RTMP source files present"
pass "UVC mode selection and connected-capture start guard present"
pass "core audio and adaptive bitrate unit tests present"
pass "USB-only audio route is verified before PCM is forwarded"
pass "foreground service and CPU wake lock support background streaming"
pass "full-frame overlay filter attached to RootEncoder output pipeline"
pass "real sender counters and adaptive bitrate hooks present"
pass "obsolete preview-only implementation files removed"
echo "NOTE: this is a source-structure check, not an Android compile or physical HDMI/RTMP test."
