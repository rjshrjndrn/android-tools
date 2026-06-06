## ADDED Requirements

### Requirement: Foreground service with mediaProjection type
The system SHALL run recording in a foreground service declared with `foregroundServiceType="mediaProjection"`.

#### Scenario: Service starts before capture
- **WHEN** user confirms MediaProjection consent
- **THEN** foreground service SHALL start before VirtualDisplay is created

#### Scenario: Persistent notification displayed
- **WHEN** recording service is active
- **THEN** a persistent notification SHALL be shown with a Stop action button

### Requirement: MediaMuxer output to MP4
The system SHALL mux encoded video and audio tracks into a single MP4 file using MediaMuxer.

#### Scenario: Output file created
- **WHEN** recording starts
- **THEN** MP4 file SHALL be created in device storage with timestamp-based filename

#### Scenario: Recording stopped cleanly
- **WHEN** user taps Stop in notification or app
- **THEN** MediaMuxer SHALL be stopped, writing moov atom, producing valid MP4

### Requirement: Unlimited recording duration
The system SHALL NOT impose a time limit on recording. Recording continues until user explicitly stops.

#### Scenario: Long recording
- **WHEN** recording runs for 2+ hours
- **THEN** recording SHALL continue without automatic stopping

### Requirement: Clean resource cleanup
The system SHALL release all resources (MediaCodec, MediaMuxer, AudioRecord, VirtualDisplay, MediaProjection) when recording stops.

#### Scenario: Stop releases resources
- **WHEN** recording is stopped
- **THEN** all encoders, muxer, audio records, virtual display, and projection SHALL be released
- **AND** foreground service SHALL stop
