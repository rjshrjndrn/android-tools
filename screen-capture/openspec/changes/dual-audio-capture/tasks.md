## 1. Audio Mode UI

- [ ] 1.1 Add audio mode enum to RecordingConfig (MIC_ONLY, INTERNAL_ONLY, BOTH)
- [ ] 1.2 Add radio buttons / spinner to MainActivity for audio mode selection
- [ ] 1.3 Pass audio mode through to RecordingService via intent extra

## 2. AudioPlaybackCapture Setup

- [ ] 2.1 Create AudioPlaybackCaptureConfiguration with USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN
- [ ] 2.2 Create AudioRecord from AudioPlaybackCaptureConfiguration using MediaProjection
- [ ] 2.3 Create mic AudioRecord with AudioSource.VOICE_COMMUNICATION when mode is BOTH

## 3. PCM Mixing

- [ ] 3.1 Create audio processing thread that reads from both AudioRecords
- [ ] 3.2 Implement PCM buffer mixing: scale mic 1.4x, add buffers, clamp to Short range
- [ ] 3.3 Handle single-source modes (skip mixing when only one source active)

## 4. MediaCodec + MediaMuxer Pipeline

- [ ] 4.1 Create video MediaCodec encoder (HEVC/H.264) with Surface input
- [ ] 4.2 Wire VirtualDisplay to video encoder Surface instead of MediaRecorder Surface
- [ ] 4.3 Create audio MediaCodec encoder (AAC, 196kbps, 44100Hz)
- [ ] 4.4 Feed mixed PCM to audio encoder input buffers
- [ ] 4.5 Create MediaMuxer with output to MediaStore ParcelFileDescriptor
- [ ] 4.6 Add both tracks to MediaMuxer, write encoded buffers from both encoders
- [ ] 4.7 Handle end-of-stream signaling on stop

## 5. Integration

- [ ] 5.1 Refactor RecordingService to use MediaCodec pipeline for internal/both modes
- [ ] 5.2 Keep MediaRecorder pipeline for mic-only mode (fallback)
- [ ] 5.3 Clean resource cleanup for all new objects (AudioRecords, MediaCodecs, MediaMuxer)
- [ ] 5.4 Update stopRecording() to signal EOS, flush encoders, stop muxer, then update MediaStore

## 6. Testing

- [ ] 6.1 Test mic-only mode (should work exactly as before via MediaRecorder)
- [ ] 6.2 Test internal-only mode (play YouTube/music, verify audio in recording)
- [ ] 6.3 Test both mode (play music + speak, verify both audible in recording)
- [ ] 6.4 Test with DRM app (Spotify) — verify no crash, audio silently excluded
- [ ] 6.5 Test stop/start cycle — verify clean cleanup, no leaked resources
