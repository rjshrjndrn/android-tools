## ADDED Requirements

### Requirement: Internal audio capture via AudioPlaybackCapture
App captures audio output from other apps using AudioPlaybackCaptureConfiguration bound to the active MediaProjection session.

#### Scenario: User records with internal audio enabled
- **WHEN** user starts recording with audio mode "internal" or "both"
- **THEN** an AudioRecord with AudioPlaybackCaptureConfiguration is created using the MediaProjection, capturing USAGE_MEDIA, USAGE_GAME, and USAGE_UNKNOWN audio types

#### Scenario: DRM app audio is not captured
- **WHEN** a playing app has set allowAudioPlaybackCapture="false" or ALLOW_CAPTURE_BY_NONE
- **THEN** that app's audio is silently excluded from the recording (no error, no crash)

#### Scenario: No audio playing
- **WHEN** no apps are producing audio during recording
- **THEN** the internal audio track contains silence; recording continues normally
