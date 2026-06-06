## ADDED Requirements

### Requirement: Real-time PCM mixing of mic and internal audio
When both audio sources are active, their PCM buffers are mixed on a dedicated thread.

#### Scenario: Both audio sources active
- **WHEN** audio mode is "both"
- **THEN** mic PCM is scaled by 1.4x, added to internal audio PCM, and clamped to Short.MIN_VALUE/MAX_VALUE before encoding

#### Scenario: Unequal read sizes
- **WHEN** AudioRecord reads return different buffer sizes for mic vs internal
- **THEN** offset tracking ensures partial buffers are carried over, only complete aligned segments are mixed

#### Scenario: One source errors mid-recording
- **WHEN** one AudioRecord returns an error or EOF
- **THEN** its buffer is filled with zeros and recording continues with the other source

#### Scenario: Mic-only mode
- **WHEN** audio mode is "mic"
- **THEN** MediaRecorder handles audio natively — no AudioPlaybackCapture, no mixing, no post-hoc merge

#### Scenario: Internal-only mode
- **WHEN** audio mode is "internal"
- **THEN** only AudioPlaybackCapture AudioRecord is created; MediaRecorder records video without audio source
