## ADDED Requirements

### Requirement: Configurable video resolution
The system SHALL create a VirtualDisplay at the resolution specified by the selected preset. Supported resolutions: 854×480 (Low), 1280×720 (Medium), 1920×1080 (High).

#### Scenario: Medium preset captures at 720p
- **WHEN** user selects Medium preset and starts recording
- **THEN** VirtualDisplay SHALL be created at 1280×720 resolution

#### Scenario: Aspect ratio maintained
- **WHEN** VirtualDisplay is created at any preset resolution
- **THEN** screen content SHALL be scaled to fit within the target dimensions preserving aspect ratio

### Requirement: H.265 video encoding
The system SHALL encode video using HEVC (H.265) codec via MediaCodec with hardware acceleration.

#### Scenario: HEVC encoding active
- **WHEN** recording starts
- **THEN** video encoder SHALL use MIME type `video/hevc`

#### Scenario: Fallback to H.264
- **WHEN** hardware HEVC encoder is not available
- **THEN** system SHALL fall back to H.264 (`video/avc`) encoder

### Requirement: Configurable bitrate and frame rate
The system SHALL set video encoding bitrate and frame rate according to the selected preset.

#### Scenario: Medium preset encoding parameters
- **WHEN** Medium preset is active
- **THEN** video bitrate SHALL be 3 Mbps and frame rate SHALL be 30 FPS

#### Scenario: Low preset encoding parameters
- **WHEN** Low preset is active
- **THEN** video bitrate SHALL be 1 Mbps and frame rate SHALL be 24 FPS

#### Scenario: High preset encoding parameters
- **WHEN** High preset is active
- **THEN** video bitrate SHALL be 6 Mbps and frame rate SHALL be 30 FPS
