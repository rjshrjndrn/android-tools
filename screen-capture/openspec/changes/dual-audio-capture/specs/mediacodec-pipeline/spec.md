## ADDED Requirements

### Requirement: Post-hoc muxing for multi-track recording
MediaRecorder handles video (+mic). Internal audio captured separately. Tracks merged after stop.

#### Scenario: Internal audio encoded to temp file
- **WHEN** recording starts with internal or both audio mode
- **THEN** internal audio is encoded with MediaCodec AAC (196kbps, 44100Hz) and written to a temp .m4a file via MediaMuxer

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
- **THEN** PTS is calculated as `(totalSamplesWritten * 1_000_000L) / sampleRate`
- **THEN** totalSamplesWritten is incremented by the number of short samples per call
- **THEN** System.nanoTime() or SystemClock is NOT used for audio PTS

#### Scenario: Merge preserves sync flags
- **WHEN** tracks are copied during post-hoc merge
- **THEN** MediaExtractor.selectTrack(i) is called before reading each track
- **THEN** SAMPLE_FLAG_SYNC is translated to BUFFER_FLAG_KEY_FRAME when writing to muxer
- **THEN** BUFFER_FLAG_CODEC_CONFIG samples are skipped (already in track format)

#### Scenario: Stale temp files cleaned on startup
- **WHEN** RecordingService.onCreate() runs
- **THEN** any temp_video*.mp4 and temp_audio*.m4a files in cacheDir are deleted
