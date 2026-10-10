# Development Guide – Wireless Audio Streamer

## Current Status (Scaffold)

| Module                    | Status      | Notes |
|---------------------------|-------------|-------|
| Project structure         | ✅ Done    | Gradle, Manifest, basic UI |
| MainActivity + permissions| ✅ Done    | MediaProjection flow ready |
| StreamingService          | ✅ Skeleton| Foreground service ready |
| SystemAudioCapturer       | ✅ Done    | AudioPlaybackCapture |
| LowLatencyPlayer          | ✅ Done    | AudioTrack Low Latency |
| UdpAudioStreamer/Receiver | ✅ Done    | Basic UDP + seq/timestamp |
| Opus Encoder/Decoder      | ⏳ Pending | Needs libopus via NDK |
| Nearby Connections        | ⏳ Pending | One-touch discovery |
| Jitter Buffer             | ⏳ Pending | Adaptive 30-70ms |
| Latency Meter UI          | ⏳ Pending | Real-time ms display |

## Next Steps (Priority Order)

1. **Integrate Opus** (libopus + NDK or pure Java Opus)
2. **Nearby Connections** for one-touch pairing
3. Wire everything inside `StreamingService`
4. Adaptive Jitter Buffer
5. Real-time latency calculation & UI update
6. Volume controls + mute
7. Better error handling & auto-reconnect

## Latency Target

**85–150 ms** end-to-end

Recommended settings:
- Opus 20 ms frames @ 48 kHz stereo, ~96 kbps
- UDP
- AudioTrack PERFORMANCE_MODE_LOW_LATENCY
- Adaptive jitter buffer 30–70 ms

## How to Build

1. Clone the repo
2. Open in Android Studio (Hedgehog or newer recommended)
3. Sync Gradle
4. Run on two Android 10+ devices on same WiFi / Hotspot

## Notes

- System audio capture **requires** user to grant MediaProjection (screen capture) permission every time (or until revoked).
- For production, consider adding a persistent notification with latency & stop button.
- Opus is strongly recommended over raw PCM for bandwidth and slight latency benefits.
