## ADDED Requirements

### Requirement: Post-hoc muxing for multi-track recording
MediaRecorder handles video (+mic). Internal audio captured separately. Tracks merged after stop.

#### Scenario: Internal audio encoded to temp file
- **WHEN** recording starts with internal or both audio mode
- **THEN** internal audio is encoded with MediaCodec AAC (196kbps, 44100Hz) and written to a temp .m4a file via MediaMuxer
- **THEN** MediaFormat includes `KEY_AAC_PROFILE = AACObjectLC` and `KEY_PCM_ENCODING = ENCODING_PCM_16BIT`

#### Scenario: Post-hoc merge produces final MP4
- **WHEN** recording stops
- **THEN** video track (from MediaRecorder temp file) and internal audio track (from temp .m4a) are merged into final MP4 at the MediaStore URI

#### Scenario: Temp files cleaned after merge
- **WHEN** post-hoc merge completes successfully
- **THEN** temp video and temp audio files in cache dir are deleted

#### Scenario: Merge failure fallback
- **WHEN** post-hoc merge fails
- **THEN** temp video content is copied to the pending MediaStore URI, IS_PENDING is set to 0, user gets video-only recording, error is logged
- **THEN** IS_PENDING is never left at 1 (would make recording invisible to user)

#### Scenario: CODEC_CONFIG buffers discarded in encoder drain loop
- **WHEN** MediaCodec encoder emits output buffer with BUFFER_FLAG_CODEC_CONFIG set
- **THEN** buffer is released without writing to muxer (CSD already embedded in track format via addTrack)

#### Scenario: PTS calculated from sample count
- **WHEN** PCM samples are queued to encoder input buffer
- **THEN** PTS is calculated as `(totalSamplesWritten * 1_000_000L) / sampleRate` BEFORE incrementing totalSamplesWritten
- **THEN** totalSamplesWritten is incremented by the number of short samples AFTER computing PTS
- **THEN** System.nanoTime() or SystemClock is NOT used for audio PTS

#### Scenario: EOS signal uses retry loop
- **WHEN** recording stops and EOS must be signaled to encoder
- **THEN** dequeueInputBuffer is called in a retry loop with 10ms timeout until a valid buffer index is returned
- **THEN** the 500µs one-shot pattern from AOSP is NOT used (returns -1 under CPU pressure, queueInputBuffer(-1) throws IllegalArgumentException)

#### Scenario: Encode loop retries on backpressure
- **WHEN** dequeueInputBuffer returns -1 during normal encoding
- **THEN** the current PCM batch is retried with 10ms timeout, NOT silently dropped
- **THEN** AOSP's 500µs timeout with silent discard is NOT used

#### Scenario: Temp files validated before merge
- **WHEN** post-hoc merge is about to start
- **THEN** temp_video file existence and size > 0 are verified
- **THEN** if temp_video is empty/missing, merge is skipped and IS_PENDING entry is cleaned up

#### Scenario: Merge writes to third temp file
- **WHEN** tracks are merged during post-hoc merge
- **THEN** output is written to temp_merged.mp4 in cacheDir, NOT directly to MediaStore FD
- **THEN** only after temp_merged is fully written and closed, content is copied to MediaStore URI
- **THEN** IS_PENDING is set to 0 only after successful copy

#### Scenario: Merge preserves sync flags
- **WHEN** tracks are copied during post-hoc merge
- **THEN** MediaExtractor.selectTrack(i) is called before reading each track
- **THEN** SAMPLE_FLAG_SYNC is translated to BUFFER_FLAG_KEY_FRAME when writing to muxer
- **THEN** BUFFER_FLAG_CODEC_CONFIG samples are skipped (already in track format)

#### Scenario: Merge preserves rotation metadata
- **WHEN** temp_video contains rotation metadata (portrait recording)
- **THEN** rotation is read via MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
- **THEN** muxer.setOrientationHint(degrees) is called BEFORE muxer.start()
- **THEN** without this, portrait recordings appear as landscape in merged output

#### Scenario: Merge thread holds WakeLock
- **WHEN** merge runs on background thread
- **THEN** a WakeLock is held for the duration of the merge
- **THEN** thread checks volatile isCancelled flag periodically
- **THEN** on cancellation, thread flushes and stops muxer gracefully
- **THEN** stopSelf() is called only after merge thread has exited

#### Scenario: Stale temp files cleaned on startup
- **WHEN** RecordingService.onCreate() runs
- **THEN** any temp_video*.mp4 and temp_audio*.m4a files in cacheDir are deleted
