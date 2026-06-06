## ADDED Requirements

### Requirement: Internal audio capture
The system SHALL capture internal device audio using AudioPlaybackCapture via MediaProjection.

#### Scenario: Internal audio captured during video call
- **WHEN** recording is active and a video call is playing audio
- **THEN** call audio SHALL be captured in the recording

### Requirement: Microphone capture
The system SHALL capture microphone audio using AudioRecord with RECORD_AUDIO permission.

#### Scenario: User voice captured
- **WHEN** recording is active and user speaks
- **THEN** user's voice from microphone SHALL be captured in the recording

### Requirement: Dual audio mixing
The system SHALL mix internal audio and microphone audio into a single PCM stream by adding samples with clamping to prevent clipping.

#### Scenario: Both audio sources mixed
- **WHEN** both internal audio and microphone are active
- **THEN** output audio stream SHALL contain both sources mixed together

#### Scenario: One source silent
- **WHEN** one audio source produces silence (zero samples)
- **THEN** output SHALL contain only the active source without distortion

### Requirement: AAC audio encoding
The system SHALL encode the mixed audio stream as AAC at 44100 Hz sample rate, mono, 196 kbps bitrate.

#### Scenario: Audio encoding parameters
- **WHEN** recording starts
- **THEN** audio encoder SHALL use AAC codec at 44100 Hz, mono, 196 kbps
