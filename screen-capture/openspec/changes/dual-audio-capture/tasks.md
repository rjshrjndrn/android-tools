## 1. Audio Mode UI

- [x] 1.1 Add AudioMode enum to RecordingConfig (MIC_ONLY, INTERNAL_ONLY, BOTH)
- [x] 1.2 Add radio buttons to MainActivity for audio mode selection
- [x] 1.3 Pass audio mode through to RecordingService via intent extra

## 2. Internal Audio Capture

- [x] 2.1 Create AudioPlaybackCaptureConfiguration with USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN using the SAME MediaProjection instance as VirtualDisplay
- [x] 2.2 Create AudioRecord via `AudioRecord.Builder` with `.setBufferSizeInBytes(getMinBufferSize(44100, CHANNEL_IN_MONO, ENCODING_PCM_16BIT) * 2)`. AudioFormat: CHANNEL_OUT_MONO, 44100Hz, PCM_16BIT. Must use `.setBufferSizeInBytes()` on Builder — omitting it defaults to minimum (1 frame = instant overflow)
- [x] 2.3 Wrap AudioRecord creation in `catch (e: Exception)` — NOT just UnsupportedOperationException. Vendor ROMs throw `RuntimeException: registerAudioPolicy() returned -1`. Fallback to mic-only mode with Toast
- [x] 2.4 After `startRecording()`, verify `getRecordingState() == RECORDSTATE_RECORDING`. Vendor ROMs (MIUI) silently no-op startRecording() — state stays STOPPED, all read() return ERROR_INVALID_OPERATION. If state != RECORDING, fallback to mic-only with Toast
- [x] 2.5 Create dedicated audio capture thread

## 3. PCM Mixing (both mode)

- [x] 3.1 In both mode: MediaRecorder records VIDEO ONLY (no audio source)
- [x] 3.2 Create two AudioRecords on audio thread: one for AudioPlaybackCapture, one for MIC (AudioSource.MIC, NOT VOICE_COMMUNICATION). Both use buffer size `getMinBufferSize() * 2`
- [x] 3.3 Read from both AudioRecords, handle unequal read sizes with offset tracking and shiftToStart(). **Fix AOSP bug:** use `shiftToStart(buffer, minShorts, readShortsTotal)` — AOSP passes old offset as end, silently dropping samples on unequal reads
- [x] 3.4 Mix PCM: scale mic buffer 1.4x, add to internal buffer, clamp to Short.MIN/MAX
- [x] 3.5 If one source errors mid-recording, fill its buffer with zeros and continue. **If BOTH sources return error/EOF, break loop and call endStream()** — do NOT zero-fill both (infinite silence loop)

## 4. Audio Encoding to Temp File

- [x] 4.1 Create MediaCodec AAC encoder (196kbps, 44100Hz, mono). MediaFormat MUST include: `KEY_AAC_PROFILE = AACObjectLC` (hardware encoders fail without it) and `KEY_PCM_ENCODING = ENCODING_PCM_16BIT` (some API 29-31 devices default to FLOAT)
- [x] 4.2 Create MediaMuxer for temp audio file in cache dir — do NOT start yet
- [x] 4.3 Implement drain loop: handle INFO_OUTPUT_FORMAT_CHANGED → addTrack() → muxer.start() before any writeSampleData. **Discard BUFFER_FLAG_CODEC_CONFIG output buffers** — CSD already in track format, writing them corrupts .m4a
- [x] 4.4 Feed mixed/single-source PCM to encoder input buffers. Compute PTS BEFORE incrementing: `ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate`, then `totalSamplesWritten += samplesInBatch`. Use 10ms dequeueInputBuffer timeout with retry on -1 (AOSP's 500µs silently drops PCM under load). Do NOT use System.nanoTime()
- [x] 4.5 On stop: signal EOS with dequeueInputBuffer retry loop (10ms timeout, spin until >= 0 — AOSP's 500µs one-shot crashes with IllegalArgumentException on -1 return under CPU pressure). Drain remaining output buffers, stop muxer

## 5. Post-hoc Merge Utility

- [x] 5.1 Create ScreenRecordingMuxer: call `MediaExtractor.selectTrack(i)` for each desired track, then iterate by MIME prefix (video/, audio/), NOT by index. Without selectTrack(), readSampleData() silently returns -1
- [x] 5.2 Skip BUFFER_FLAG_CODEC_CONFIG samples during merge. Preserve SAMPLE_FLAG_SYNC → translate to BUFFER_FLAG_KEY_FRAME when writing to muxer (dropping sync flags = unseekable video)
- [x] 5.3 For internal-only: copy video track (from temp_video) + copy internal audio track (from temp_audio)
- [x] 5.4 For both: copy video track (from temp_video) + copy pre-mixed audio track (from temp_audio) — no decode/re-encode
- [x] 5.0 Validate temp files before merge: check temp_video exists and size > 0. If invalid, skip merge (see 6.6 for fallback)
- [x] 5.1a Read rotation from temp_video via `MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION`, call `muxer.setOrientationHint(degrees)` BEFORE `muxer.start()`. Without this, portrait recordings become landscape
- [x] 5.5 Write merged output to `temp_merged.mp4` in cacheDir (NOT directly to MediaStore FD). Only after temp_merged is fully written and closed, copy content to MediaStore URI and set IS_PENDING=0. Prevents partial writes on merge failure
- [x] 5.6 Clean up temp files in cache dir after successful merge
- [x] 5.7 Handle merge failure: copy temp_video.mp4 content to the pending MediaStore URI, set IS_PENDING=0 — user gets video-only recording. Log error. Do NOT leave IS_PENDING=1 (invisible to user = data loss). Delete the failed merge output if any

## 6. Service Integration

- [ ] 6.1 Mic-only mode: MediaRecorder → MediaStore URI directly (current path, zero changes)
- [ ] 6.2 Internal/both modes: check cacheDir available space dynamically: `estimatedBytes = (bitrate * 1800 / 8) + (196000 * 1800 / 8)`, check `usableSpace > estimatedBytes * 1.1`. Do NOT use flat 1.3 GB (false rejection on LOW preset). MediaRecorder → temp file in cache dir (setOutputFile(path), NOT FileDescriptor)
- [ ] 6.3 On stop: stop MediaRecorder, stop audio capture thread, run post-hoc merge on BACKGROUND THREAD. Merge thread holds WakeLock for duration. Thread checks `volatile isCancelled` periodically for graceful shutdown
- [ ] 6.4 Foreground service stays alive during merge — update notification to "Finishing recording…". Service `onDestroy()` sets isCancelled=true, merge thread flushes+stops muxer gracefully
- [ ] 6.5 stopForeground() + stopSelf() called only after merge thread has EXITED (join or callback). Release WakeLock after stopSelf()
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
