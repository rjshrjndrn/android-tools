## ADDED Requirements

### Requirement: Preset selection
The system SHALL display three recording presets: Low, Medium, High. Medium SHALL be selected by default.

#### Scenario: Default selection
- **WHEN** app launches
- **THEN** Medium preset SHALL be pre-selected

#### Scenario: Preset info displayed
- **WHEN** user views presets
- **THEN** each preset SHALL show resolution, estimated file size per hour

### Requirement: Start recording flow
The system SHALL provide a Start Recording button that triggers MediaProjection consent dialog.

#### Scenario: Start recording tapped
- **WHEN** user taps Start Recording
- **THEN** system MediaProjection consent dialog SHALL appear

#### Scenario: Consent granted
- **WHEN** user approves MediaProjection consent
- **THEN** recording SHALL begin with selected preset parameters

#### Scenario: Consent denied
- **WHEN** user denies MediaProjection consent
- **THEN** app SHALL return to idle state without error

### Requirement: Recording status indication
The system SHALL indicate when recording is active with elapsed time.

#### Scenario: Recording active UI
- **WHEN** recording is in progress
- **THEN** app SHALL show recording indicator and elapsed time
- **AND** Start button SHALL change to Stop button

### Requirement: Stop from notification
The system SHALL allow stopping recording from the foreground service notification.

#### Scenario: Notification stop
- **WHEN** user taps Stop on notification
- **THEN** recording SHALL stop and file SHALL be saved
