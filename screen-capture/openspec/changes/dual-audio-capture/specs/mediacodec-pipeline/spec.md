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
- **THEN** temp video file is kept as the recording (without internal audio), error is logged
