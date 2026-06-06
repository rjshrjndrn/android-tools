## 1. Audio Mode UI

- [ ] 1.1 Add AudioMode enum to RecordingConfig (MIC_ONLY, INTERNAL_ONLY, BOTH)
- [ ] 1.2 Add radio buttons to MainActivity for audio mode selection
- [ ] 1.3 Pass audio mode through to RecordingService via intent extra

## 2. Internal Audio Capture

- [ ] 2.1 Create AudioPlaybackCaptureConfiguration with USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN using MediaProjection
- [ ] 2.2 Create AudioRecord from config with AudioFormat.CHANNEL_OUT_MONO, 44100Hz, PCM_16BIT
- [ ] 2.3 Wrap AudioRecord creation in try/catch — fallback to mic-only mode with Toast on failure
- [ ] 2.4 Create dedicated audio capture thread that reads from AudioRecord in a loop

## 3. PCM Mixing (both mode only)

- [ ] 3.1 Create second AudioRecord for mic (AudioSource.MIC, NOT VOICE_COMMUNICATION) 
- [ ] 3.2 Read from both AudioRecords on audio thread, handle unequal read sizes with offset tracking
- [ ] 3.3 Mix PCM: scale mic buffer 1.4x, add to internal buffer, clamp to Short.MIN/MAX
- [ ] 3.4 If one source errors mid-recording, fill its buffer with zeros and continue

## 4. Audio Encoding (internal/both modes)

- [ ] 4.1 Create MediaCodec AAC encoder (196kbps, 44100Hz, mono)
- [ ] 4.2 Feed mixed/single-source PCM to encoder input buffers with correct PTS (sample-count-based)
- [ ] 4.3 Create MediaMuxer for temp audio file in cache dir
- [ ] 4.4 Drain encoder output to MediaMuxer
- [ ] 4.5 Signal end-of-stream on stop, flush encoder, stop muxer

## 5. Post-hoc Merge

- [ ] 5.1 Create ScreenRecordingMuxer utility: extract tracks from temp files, write to final MP4
- [ ] 5.2 For internal-only: merge video track (from MediaRecorder, no audio) + internal audio track
- [ ] 5.3 For both: merge video+mic track (from MediaRecorder) + internal audio track
- [ ] 5.4 Write merged output to MediaStore URI, set IS_PENDING=0
- [ ] 5.5 Clean up temp files after successful merge
- [ ] 5.6 Handle merge failure: keep temp_video.mp4 as fallback, log error

## 6. Service Integration

- [ ] 6.1 Refactor RecordingService: mic-only mode uses MediaRecorder as-is (zero changes to current path)
- [ ] 6.2 Internal/both modes: start MediaRecorder to temp file + start internal audio capture thread
- [ ] 6.3 On stop: stop MediaRecorder, stop audio capture, run post-hoc merge
- [ ] 6.4 Update stopRecording() to handle async merge (don't mark MediaStore complete until merge done)
- [ ] 6.5 Handle MediaProjection revocation mid-recording: stop both pipelines cleanly

## 7. Testing

- [ ] 7.1 Test mic-only mode — identical behavior to current app
- [ ] 7.2 Test internal-only: play YouTube/music, verify audio in recording
- [ ] 7.3 Test both mode: play music + speak, verify both audible
- [ ] 7.4 Test DRM app (Spotify) — verify no crash, internal audio silently excluded
- [ ] 7.5 Test stop/start cycle — verify temp files cleaned, no resource leaks
- [ ] 7.6 Test MediaProjection revocation (swipe down Quick Settings → revoke)
- [ ] 7.7 Test long recording (10+ minutes) — verify no memory leak or buffer accumulation
- [ ] 7.8 Test AudioRecord creation failure — verify fallback to mic-only with Toast
