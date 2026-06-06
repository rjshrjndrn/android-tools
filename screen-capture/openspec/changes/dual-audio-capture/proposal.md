## Why

Current screen recorder captures mic audio only. Internal audio (app sounds, media playback, game audio) is lost. Users want recordings that include both device audio and mic commentary simultaneously.

## What Changes

- Replace `MediaRecorder` audio pipeline with dual `AudioRecord` + `MediaCodec` + `MediaMuxer`
- Add `AudioPlaybackCaptureConfiguration` for internal audio capture via `MediaProjection`
- Mix PCM from mic and internal audio sources in real-time
- Add UI toggle for audio source selection (mic only, internal only, both)
- Video encoding moves from `MediaRecorder` to `MediaCodec` + `MediaMuxer`

## Capabilities

### New Capabilities
- `internal-audio-capture`: Capture audio playing from other apps via AudioPlaybackCapture API (USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN)
- `audio-mixing`: Real-time PCM mixing of mic + internal audio with volume scaling and clamping
- `mediacodec-pipeline`: Replace MediaRecorder with MediaCodec encoder + MediaMuxer for video+audio tracks

### Modified Capabilities
- `recording-service`: Service now manages dual AudioRecord instances, MediaCodec, MediaMuxer instead of single MediaRecorder

## Impact

- **RecordingService.kt**: Major rewrite — MediaRecorder replaced with MediaCodec+MediaMuxer pipeline
- **RecordingConfig.kt**: Add audio source selection (mic/internal/both)
- **MainActivity.kt**: UI for audio source toggle
- **AndroidManifest.xml**: Already has RECORD_AUDIO + MediaProjection permissions (sufficient)
- **Dependencies**: None new — all APIs are in Android SDK (API 29+)
- **Complexity**: Significant increase — manual encoding, threading, buffer management
