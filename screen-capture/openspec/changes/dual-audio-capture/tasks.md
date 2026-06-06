## 1. Audio Mode UI

- [ ] 1.1 Add AudioMode enum to RecordingConfig (MIC_ONLY, INTERNAL_ONLY, BOTH)
- [ ] 1.2 Add radio buttons to MainActivity for audio mode selection
- [ ] 1.3 Pass audio mode through to RecordingService via intent extra

## 2. Internal Audio Capture

- [ ] 2.1 Create AudioPlaybackCaptureConfiguration with USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN using the SAME MediaProjection instance as VirtualDisplay
- [ ] 2.2 Create AudioRecord with AudioFormat.CHANNEL_OUT_MONO, 44100Hz, PCM_16BIT. Buffer size: `AudioRecord.getMinBufferSize(44100, CHANNEL_IN_MONO, ENCODING_PCM_16BIT) * 2`
- [ ] 2.3 Wrap AudioRecord creation in `catch (e: Exception)` — NOT just UnsupportedOperationException. Vendor ROMs throw `RuntimeException: registerAudioPolicy() returned -1`. Fallback to mic-only mode with Toast
- [ ] 2.4 Create dedicated audio capture thread

## 3. PCM Mixing (both mode)

- [ ] 3.1 In both mode: MediaRecorder records VIDEO ONLY (no audio source)
- [ ] 3.2 Create two AudioRecords on audio thread: one for AudioPlaybackCapture, one for MIC (AudioSource.MIC, NOT VOICE_COMMUNICATION). Both use buffer size `getMinBufferSize() * 2`
- [ ] 3.3 Read from both AudioRecords, handle unequal read sizes with offset tracking and shiftToStart()
- [ ] 3.4 Mix PCM: scale mic buffer 1.4x, add to internal buffer, clamp to Short.MIN/MAX
- [ ] 3.5 If one source errors mid-recording, fill its buffer with zeros and continue

## 4. Audio Encoding to Temp File

- [ ] 4.1 Create MediaCodec AAC encoder (196kbps, 44100Hz, mono)
- [ ] 4.2 Create MediaMuxer for temp audio file in cache dir — do NOT start yet
- [ ] 4.3 Implement drain loop: handle INFO_OUTPUT_FORMAT_CHANGED → addTrack() → muxer.start() before any writeSampleData. **Discard BUFFER_FLAG_CODEC_CONFIG output buffers** — CSD already in track format, writing them corrupts .m4a
- [ ] 4.4 Feed mixed/single-source PCM to encoder input buffers with explicit PTS: `ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate`. Increment `totalSamplesWritten` by short sample count per call. Do NOT use System.nanoTime()
- [ ] 4.5 On stop: signal BUFFER_FLAG_END_OF_STREAM, drain remaining buffers, stop muxer

## 5. Post-hoc Merge Utility

- [ ] 5.1 Create ScreenRecordingMuxer: call `MediaExtractor.selectTrack(i)` for each desired track, then iterate by MIME prefix (video/, audio/), NOT by index. Without selectTrack(), readSampleData() silently returns -1
- [ ] 5.2 Skip BUFFER_FLAG_CODEC_CONFIG samples during merge. Preserve SAMPLE_FLAG_SYNC → translate to BUFFER_FLAG_KEY_FRAME when writing to muxer (dropping sync flags = unseekable video)
- [ ] 5.3 For internal-only: copy video track (from temp_video) + copy internal audio track (from temp_audio)
- [ ] 5.4 For both: copy video track (from temp_video) + copy pre-mixed audio track (from temp_audio) — no decode/re-encode
- [ ] 5.5 Write merged output to MediaStore URI, set IS_PENDING=0 only after merge completes
- [ ] 5.6 Clean up temp files in cache dir after successful merge
- [ ] 5.7 Handle merge failure: copy temp_video.mp4 content to the pending MediaStore URI, set IS_PENDING=0 — user gets video-only recording. Log error. Do NOT leave IS_PENDING=1 (invisible to user = data loss). Delete the failed merge output if any

## 6. Service Integration

- [ ] 6.1 Mic-only mode: MediaRecorder → MediaStore URI directly (current path, zero changes)
- [ ] 6.2 Internal/both modes: check cacheDir available space (~1.3 GB needed for 30min 1080p). If insufficient, abort with user Toast. MediaRecorder → temp file in cache dir (setOutputFile(path), NOT FileDescriptor)
- [ ] 6.3 On stop: stop MediaRecorder, stop audio capture thread, run post-hoc merge on BACKGROUND THREAD
- [ ] 6.4 Foreground service stays alive during merge — update notification to "Finishing recording…"
- [ ] 6.5 stopForeground() + stopSelf() called only after merge completes
- [ ] 6.6 Handle MediaProjection revocation mid-recording: stop both pipelines cleanly, merge what we have. Edge cases: if temp_audio is empty (zero samples written), skip audio track in merge. If mediaRecorder.stop() throws (no frames encoded), promote temp_video directly to MediaStore if it exists, otherwise delete IS_PENDING entry
- [ ] 6.7 In `RecordingService.onCreate()`: delete stale temp files matching `temp_video*.mp4` and `temp_audio*.m4a` in cacheDir (handles process kill during merge)
- [ ] 6.8 `startForeground()` bitmask conditional: internal-only mode uses `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` only (no mic indicator). Mic/both modes add `FOREGROUND_SERVICE_TYPE_MICROPHONE`

## 7. Testing

- [ ] 7.1 Test mic-only mode — identical behavior to current app
- [ ] 7.2 Test internal-only: play YouTube/music, verify audio in recording
- [ ] 7.3 Test both mode: play music + speak, verify both audible in final MP4
- [ ] 7.4 Test DRM app (Spotify) — verify no crash, internal audio silently excluded
- [ ] 7.5 Test stop/start cycle — verify temp files cleaned, no resource leaks
- [ ] 7.6 Test MediaProjection revocation (swipe Quick Settings → revoke mid-recording)
- [ ] 7.7 Test long recording (10+ minutes) — verify no memory leak or buffer accumulation
- [ ] 7.8 Test AudioRecord creation failure — verify fallback to mic-only with Toast
- [ ] 7.9 Test merge failure — verify video-only recording saved to MediaStore with IS_PENDING=0
- [ ] 7.10 Test stale temp file cleanup on service restart
- [ ] 7.11 Test foreground service type — verify no mic indicator in internal-only mode
