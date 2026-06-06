## ADDED Requirements

### Requirement: Foreground service with mediaProjection type
The system SHALL run recording in a foreground service declared with `foregroundServiceType="mediaProjection"`.

#### Scenario: Service starts before capture
- **WHEN** user confirms MediaProjection consent
- **THEN** foreground service SHALL start before VirtualDisplay is created

#### Scenario: Persistent notification displayed
- **WHEN** recording service is active
- **THEN** a persistent notification SHALL be shown with a Stop action button

### Requirement: MediaRecorder output to MP4
The system SHALL record video and audio into a single MP4 file using MediaRecorder.

#### Scenario: Output file created
- **WHEN** recording starts
- **THEN** MP4 file SHALL be created in app-specific movies directory with timestamp-based filename

#### Scenario: Recording stopped cleanly
- **WHEN** user taps Stop in notification or app
- **THEN** MediaRecorder SHALL be stopped, producing valid MP4

### Requirement: Unlimited recording duration
The system SHALL NOT impose a time limit on recording. Recording continues until user explicitly stops.

#### Scenario: Long recording
- **WHEN** recording runs for 2+ hours
- **THEN** recording SHALL continue without automatic stopping

### Requirement: MediaProjection.Callback registration
The system SHALL register a MediaProjection.Callback with onStop() handler before creating VirtualDisplay.

#### Scenario: System revokes projection
- **WHEN** system or user revokes MediaProjection
- **THEN** onStop() callback SHALL trigger clean shutdown of recording

### Requirement: Clean resource cleanup
The system SHALL release all resources (MediaRecorder, VirtualDisplay, MediaProjection) when recording stops.

#### Scenario: Stop releases resources
- **WHEN** recording is stopped
- **THEN** MediaRecorder, VirtualDisplay, and MediaProjection SHALL be released
- **AND** foreground service SHALL stop
