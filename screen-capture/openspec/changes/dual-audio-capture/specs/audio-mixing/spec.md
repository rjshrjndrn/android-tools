## ADDED Requirements

### Requirement: Real-time PCM mixing of mic and internal audio
When both audio sources are active, their PCM buffers are mixed additively with clamping to prevent overflow.

#### Scenario: Both audio sources active
- **WHEN** audio mode is "both"
- **THEN** mic PCM is scaled by 1.4x, added to internal audio PCM, and clamped to Short.MIN_VALUE/MAX_VALUE before encoding

#### Scenario: Mic-only mode selected
- **WHEN** audio mode is "mic"
- **THEN** existing MediaRecorder pipeline is used (no AudioPlaybackCapture, no mixing)

#### Scenario: Internal-only mode selected
- **WHEN** audio mode is "internal"
- **THEN** only AudioPlaybackCapture AudioRecord is created; no mic AudioRecord
