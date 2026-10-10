# Wireless Audio Streamer

Android app that turns one phone into a wireless speaker for another phone.

**Features:**
- One-touch connection over WiFi / Hotspot
- Streams **all system audio** (YouTube, Spotify, games, any app)
- Target latency: **85–150 ms**
- Advanced UI with real-time latency meter
- Foreground service for background streaming

## Requirements
- Android 10+ (API 29) for system audio capture
- Same WiFi network or one phone's Hotspot

## How it works
1. Open app on both phones
2. One phone → **Sender** mode (grants MediaProjection permission)
3. Other phone → **Receiver** mode
4. One-touch connect via Nearby Connections
5. Play any audio on Sender → sound comes from Receiver speaker

## Tech Stack
- Kotlin
- AudioPlaybackCapture + MediaProjection (system audio)
- Opus codec (20ms frames)
- UDP streaming
- Nearby Connections API
- AudioTrack with PERFORMANCE_MODE_LOW_LATENCY

## Project Status
This is the initial scaffold. Core modules are being implemented step by step.

## License
MIT
