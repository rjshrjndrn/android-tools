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
- **THEN** playback capture AudioRecord (built via AudioRecord.Builder) MUST call `.setBufferSizeInBytes()` — omitting it defaults to minimum (1 frame), causing instant buffer overflow

#### Scenario: AudioRecord recording state validated after start
- **WHEN** AudioRecord.startRecording() is called
- **THEN** getRecordingState() is checked to equal RECORDSTATE_RECORDING
- **THEN** if state is not RECORDING (vendor ROMs like MIUI silently no-op startRecording), fallback to mic-only with Toast

#### Scenario: Mic uses AudioSource.MIC not VOICE_COMMUNICATION
- **WHEN** both audio sources are active
- **THEN** mic AudioRecord uses AudioSource.MIC
- **THEN** trade-off: MIC does not provide echo cancellation — speaker output may leak into mic in "both" mode. VOICE_COMMUNICATION would prevent this but echo cancellation is a non-goal

#### Scenario: Foreground service type matches audio mode
- **WHEN** audio mode is internal-only
- **THEN** startForeground() uses FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION only (no MICROPHONE)
- **WHEN** audio mode is mic-only or both
- **THEN** startForeground() uses FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION | FOREGROUND_SERVICE_TYPE_MICROPHONE

#### Scenario: Storage availability checked before recording
- **WHEN** recording starts with internal or both audio mode
- **THEN** required space is estimated dynamically: `(bitrate * 1800 / 8) + (196000 * 1800 / 8)` with 10% margin
- **THEN** cacheDir.usableSpace is checked against the estimate
- **THEN** if insufficient space, recording is aborted with user-visible error
- **THEN** a flat threshold is NOT used (false rejection on LOW preset which only needs ~250 MB)
