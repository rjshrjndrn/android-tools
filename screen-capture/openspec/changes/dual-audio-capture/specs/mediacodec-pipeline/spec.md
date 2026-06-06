## ADDED Requirements

### Requirement: MediaCodec + MediaMuxer recording pipeline
Replace MediaRecorder with MediaCodec video encoder + MediaCodec audio encoder + MediaMuxer for internal/both audio modes.

#### Scenario: Video encoding via MediaCodec
- **WHEN** recording starts with internal or both audio mode
- **THEN** video is encoded using MediaCodec with Surface input from VirtualDisplay, using HEVC (fallback H.264)

#### Scenario: Audio encoding via MediaCodec
- **WHEN** mixed PCM audio is available
- **THEN** audio is encoded using MediaCodec AAC encoder at 196kbps, 44100Hz

#### Scenario: Muxing video and audio tracks
- **WHEN** both encoders produce output buffers
- **THEN** MediaMuxer writes both tracks to a single MP4 file via MediaStore

#### Scenario: Clean stop
- **WHEN** user stops recording
- **THEN** both encoders are signaled with end-of-stream, MediaMuxer.stop() is called, MediaStore IS_PENDING set to 0
