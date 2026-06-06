## 1. Project Setup

- [x] 1.1 Initialize Android project with Kotlin, Gradle KTS, target/compile SDK 36, min SDK 29
- [x] 1.2 Configure AndroidManifest with permissions (RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION, POST_NOTIFICATIONS) and RecordingService declaration

## 2. Recording Configuration

- [ ] 2.1 Create RecordingConfig data class with presets (Low/Medium/High) mapping to resolution, bitrate, FPS
- [ ] 2.2 Add runtime HEVC encoder availability check with H.264 fallback

## 3. Recording Service

- [ ] 3.1 Implement RecordingService as foreground service with mediaProjection type and notification channel
- [ ] 3.2 Create persistent notification with Stop action button
- [ ] 3.3 Set up MediaProjection with Callback.onStop() registration
- [ ] 3.4 Create VirtualDisplay at preset resolution connected to MediaRecorder surface
- [ ] 3.5 Configure MediaRecorder: video source (Surface), audio source (MIC), output format (MP4), video encoder (HEVC), audio encoder (AAC), preset resolution/bitrate/FPS
- [ ] 3.6 Implement clean stop: stop MediaRecorder, release VirtualDisplay and MediaProjection, stop service
- [ ] 3.7 Generate timestamp-based output filename in app-specific movies directory

## 4. UI

- [ ] 4.1 Create MainActivity layout: preset selector (radio group), start/stop button, recording timer
- [ ] 4.2 Implement MediaProjection consent flow (ActivityResultLauncher → pass result to service)
- [ ] 4.3 Bind to RecordingService for status updates (recording state, elapsed time)
- [ ] 4.4 Handle runtime permission requests (RECORD_AUDIO, POST_NOTIFICATIONS)

## 5. Testing

- [ ] 5.1 End-to-end test: record 30s at each preset, verify output MP4 has correct resolution and audio
- [ ] 5.2 Test stop from notification vs stop from app
- [ ] 5.3 Test permission denial flows (RECORD_AUDIO denied, POST_NOTIFICATIONS denied)
