## Why

GrapheneOS's built-in screen recorder captures at native device resolution (~2K on Pixel 9 Pro) with no way to configure resolution, bitrate, or codec. Recording a 720p video call produces ~5GB/hr files — wasteful when the source content is only 720p. No existing FOSS app provides configurable screen recording with dual audio capture (internal + mic) suitable for privacy-focused GrapheneOS users.

## What Changes

- New Android app targeting API 35 (Android 16) for Pixel 9 Pro / GrapheneOS
- Screen recording via MediaProjection with configurable resolution (480p/720p/1080p)
- H.265 video encoding with configurable bitrate via presets (Low/Medium/High)
- Dual audio capture: internal audio (AudioPlaybackCapture) + microphone, mixed into single AAC stream
- MediaCodec + MediaMuxer pipeline for full control over encoding parameters
- Foreground service with notification-based stop control
- Unlimited recording duration (until user stops)

## Capabilities

### New Capabilities
- `video-capture`: VirtualDisplay creation at configurable resolution, H.265 MediaCodec encoding, frame rate control
- `audio-capture`: Dual AudioRecord (internal + mic) capture, PCM mixing, AAC encoding
- `recording-service`: Foreground service orchestration, MediaMuxer output, lifecycle management, notification controls
- `recording-ui`: Main activity with preset selection (Low/Med/High), start/stop controls, MediaProjection consent flow

### Modified Capabilities

(none — greenfield project)

## Impact

- New Android project with Kotlin, Gradle build system
- Permissions: `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`
- Output: MP4 files saved to device storage
- Target: single device (Pixel 9 Pro), single OS (GrapheneOS / Android 16)
