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
- **THEN** app catches `Exception` (not just `UnsupportedOperationException` — vendor ROMs throw `RuntimeException: registerAudioPolicy() returned -1`)
- **THEN** app falls back to mic-only mode and shows Toast to user

#### Scenario: AudioPlaybackCapture returns silence (WebView v147 bug)
- **WHEN** internal audio capture produces only silence
- **THEN** recording continues normally — mic audio (if enabled) still captured

#### Scenario: No audio playing
- **WHEN** no apps are producing audio during recording
- **THEN** the internal audio track contains silence; recording continues normally

#### Scenario: AudioRecord buffer size
- **WHEN** AudioRecord is created for playback capture or mic
- **THEN** buffer size is `AudioRecord.getMinBufferSize(sampleRate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT) * 2`
- **THEN** CHANNEL_IN_MONO is used for getMinBufferSize() even when AudioRecord uses CHANNEL_OUT_MONO (both = 1 channel, identical result)

#### Scenario: Mic uses AudioSource.MIC not VOICE_COMMUNICATION
- **WHEN** both audio sources are active
- **THEN** mic AudioRecord uses AudioSource.MIC to avoid privacy-sensitive concurrent capture restrictions

#### Scenario: Foreground service type matches audio mode
- **WHEN** audio mode is internal-only
- **THEN** startForeground() uses FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION only (no MICROPHONE)
- **WHEN** audio mode is mic-only or both
- **THEN** startForeground() uses FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION | FOREGROUND_SERVICE_TYPE_MICROPHONE

#### Scenario: Storage availability checked before recording
- **WHEN** recording starts with internal or both audio mode
- **THEN** available space in cacheDir is checked before creating temp files
- **THEN** if insufficient space (~1.3 GB for 30min 1080p), recording is aborted with user-visible error
