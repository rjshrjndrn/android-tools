## 1. Project Setup

- [ ] 1.1 Initialize Android project with Kotlin, Gradle KTS, target API 35, min API 29
- [ ] 1.2 Configure AndroidManifest with permissions (RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION) and service declaration

## 2. Recording Configuration

- [ ] 2.1 Create RecordingConfig data class with presets (Low/Medium/High) mapping to resolution, bitrate, FPS, codec
- [ ] 2.2 Add runtime HEVC encoder availability check with H.264 fallback

## 3. Video Encoding

- [ ] 3.1 Implement VideoEncoder: MediaCodec setup for HEVC with Surface input, configurable resolution/bitrate/FPS
- [ ] 3.2 Implement encoded frame output loop (drain encoder → feed to muxer)

## 4. Audio Capture & Mixing

- [ ] 4.1 Implement AudioCaptureManager: dual AudioRecord setup (AudioPlaybackCapture for internal, MIC for microphone)
- [ ] 4.2 Implement PCM mixing thread — read from both sources, add samples with clamping, output mixed buffer
- [ ] 4.3 Implement AudioEncoder: MediaCodec setup for AAC (44100 Hz, mono, 196 kbps), feed mixed PCM, drain encoded output

## 5. Muxing & Output

- [ ] 5.1 Implement Muxer wrapper: MediaMuxer setup for MP4, track registration (video + audio), thread-safe writeSampleData
- [ ] 5.2 Implement timestamp-based output filename and storage path resolution

## 6. Recording Service

- [ ] 6.1 Implement RecordingService as foreground service with mediaProjection type
- [ ] 6.2 Orchestrate full pipeline: MediaProjection → VirtualDisplay → VideoEncoder + AudioCaptureManager → Muxer
- [ ] 6.3 Implement clean stop: stop encoders, stop muxer (write moov), release VirtualDisplay and MediaProjection
- [ ] 6.4 Create notification channel and persistent notification with Stop action

## 7. UI

- [ ] 7.1 Create MainActivity layout: preset selector (radio group), start/stop button, recording timer
- [ ] 7.2 Implement MediaProjection consent flow (startActivityForResult → pass result to service)
- [ ] 7.3 Bind to RecordingService for status updates (recording state, elapsed time)
- [ ] 7.4 Handle permission requests (RECORD_AUDIO, POST_NOTIFICATIONS)

## 8. Integration & Testing

- [ ] 8.1 End-to-end test: record 30s at each preset, verify output MP4 has correct resolution/bitrate/audio
- [ ] 8.2 Test long recording (10+ minutes) for audio sync drift
- [ ] 8.3 Test stop from notification vs stop from app
