## ADDED Requirements

### Requirement: Internal audio capture via AudioPlaybackCapture
App captures audio output from other apps using AudioPlaybackCaptureConfiguration bound to the active MediaProjection session.

#### Scenario: User records with internal audio enabled
- **WHEN** user starts recording with audio mode "internal" or "both"
- **THEN** an AudioRecord with AudioPlaybackCaptureConfiguration is created using the MediaProjection, capturing USAGE_MEDIA, USAGE_GAME, and USAGE_UNKNOWN audio types

#### Scenario: AudioRecord uses correct channel mask
- **WHEN** AudioRecord is created for playback capture
- **THEN** AudioFormat uses CHANNEL_OUT_MONO (playback channel, not CHANNEL_IN_MONO)

#### Scenario: DRM app audio is not captured
- **WHEN** a playing app has set allowAudioPlaybackCapture="false" or ALLOW_CAPTURE_BY_NONE
- **THEN** that app's audio is silently excluded from the recording (no error, no crash)

#### Scenario: AudioRecord creation fails
- **WHEN** AudioRecord.Builder().build() throws (vendor ROM incompatibility)
- **THEN** app falls back to mic-only mode and shows Toast to user

#### Scenario: AudioPlaybackCapture returns silence (WebView v147 bug)
- **WHEN** internal audio capture produces only silence
- **THEN** recording continues normally — mic audio (if enabled) still captured

#### Scenario: No audio playing
- **WHEN** no apps are producing audio during recording
- **THEN** the internal audio track contains silence; recording continues normally

#### Scenario: Mic uses AudioSource.MIC not VOICE_COMMUNICATION
- **WHEN** both audio sources are active
- **THEN** mic AudioRecord uses AudioSource.MIC to avoid privacy-sensitive concurrent capture restrictions
