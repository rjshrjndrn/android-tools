## ADDED Requirements

### Requirement: Microphone audio capture
The system SHALL capture audio from the device microphone using MediaRecorder with `AudioSource.MIC`.

#### Scenario: User voice captured during recording
- **WHEN** recording is active and user speaks
- **THEN** user's voice SHALL be captured in the recording

#### Scenario: Speakerphone call audio captured
- **WHEN** recording is active and a video call is on speakerphone
- **THEN** the other party's audio SHALL be captured via microphone pickup

### Requirement: RECORD_AUDIO permission
The system SHALL request `RECORD_AUDIO` runtime permission before starting recording with audio.

#### Scenario: Permission granted
- **WHEN** user grants RECORD_AUDIO permission
- **THEN** recording SHALL proceed with mic audio

#### Scenario: Permission denied
- **WHEN** user denies RECORD_AUDIO permission
- **THEN** app SHALL inform user that audio will not be recorded
- **AND** recording MAY proceed without audio if user confirms
