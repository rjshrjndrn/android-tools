## Context

Screen recorder currently uses `MediaRecorder` which accepts a single audio source. To capture both mic and internal audio, we follow AOSP's actual architecture: separate recording pipelines with **post-hoc muxing** — not live muxing.

Validated: AudioPlaybackCapture works on GrapheneOS with no modifications. Zero commits in GrapheneOS fork touching MediaProjection or AudioPlaybackCapture APIs.

## Goals / Non-Goals

**Goals:**
- Capture internal audio via AudioPlaybackCapture API
- Capture mic audio simultaneously
- Mix both audio streams in real-time
- Maintain current video quality presets
- User chooses audio mode: mic / internal / both

**Non-Goals:**
- DRM-protected audio (apps can opt out — by design)
- System sounds (notifications, ringtones) — Android restricts to USAGE_MEDIA/GAME/UNKNOWN
- Echo cancellation between mic and speaker output
- Per-app audio source selection

## Decisions

### 1. Post-hoc muxing (AOSP pattern) — NOT live muxing

AOSP's screen recorder uses separate files merged after stop:

```
DURING RECORDING:
═════════════════

MediaProjection
    ├── VirtualDisplay → MediaRecorder → temp_video.mp4 (video + mic)
    │
    └── AudioPlaybackCapture → AudioRecord ──┐
                                              ├─ mix PCM → MediaCodec (AAC)
                         MIC → AudioRecord ──┘         → MediaMuxer → temp_audio.m4a

AFTER STOP:
═══════════

temp_video.mp4 ──┐
                  ├── ScreenRecordingMuxer → final.mp4 (all tracks merged)
temp_audio.m4a ──┘
                  (extract tracks, write to final, delete temps)
```

**Why not live muxing:**
- MediaMuxer.writeSampleData() is NOT thread-safe — concurrent writes from audio/video threads corrupt data
- MediaMuxer.start() requires ALL tracks added first — async FORMAT_CHANGED callbacks create startup gate problem
- Video PTS (system nanotime from Surface) and audio PTS (sample count) start at different epochs — live muxing produces corrupt MP4 with wrong duration
- AOSP chose post-hoc muxing for these exact reasons

**Trade-off:** 1-2 second merge delay when stopping. Acceptable for screen recorder.

### 2. Dual AudioRecord for mic + internal audio

Two `AudioRecord` instances on a dedicated thread:
- Internal audio: `AudioPlaybackCaptureConfiguration` with MediaProjection
  - Uses `AudioFormat.CHANNEL_OUT_MONO` (playback channel, not input)
  - Matches USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN
- Mic: `AudioSource.MIC` (NOT VOICE_COMMUNICATION). AOSP uses VOICE_COMMUNICATION for its echo cancellation — it prevents speaker output from leaking into mic in "both" mode. We use MIC instead, accepting echo as a trade-off (echo cancellation is a non-goal). If echo is unacceptable, switch to VOICE_COMMUNICATION with try/catch fallback to MIC. Note: the concurrent capture restriction concern from earlier was incorrect — AudioPlaybackCapture uses a software audio mix tap, not mic hardware, so no conflict exists.

**AudioRecord buffer size:** Use `AudioRecord.getMinBufferSize(sampleRate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT) * 2` for both internal and mic AudioRecords. Pass `CHANNEL_IN_MONO` to `getMinBufferSize()` even when the AudioRecord itself uses `CHANNEL_OUT_MONO` — both represent 1 channel, buffer size is identical.

Mix PCM buffers: scale mic 1.4x, add, clamp to Short range.

Handle unequal read sizes: track buffer offsets, use `shiftToStart()` pattern from AOSP. **AOSP bug warning:** AOSP's `shiftToStart()` passes the old offset as `end` instead of `readShortsTotal` — use `shiftToStart(buffer, minShorts, readShortsTotal)` to avoid silently dropping samples.

### 3. Architecture per mode (AOSP pattern)

MediaRecorder handles video. Audio handling depends on mode:

```
MODE: mic-only
══════════════
MediaRecorder (video + mic) → final.mp4
(current behavior, zero changes)

MODE: internal-only  
═══════════════════
MediaRecorder (video only, no audio source) → temp_video.mp4
AudioPlaybackCapture → AudioRecord → MediaCodec AAC → MediaMuxer → temp_audio.m4a
Post-hoc merge: copy video track + copy audio track → final.mp4

MODE: both
══════════
MediaRecorder (video only, no audio source) → temp_video.mp4
AudioPlaybackCapture → AudioRecord #1 ──┐
                                         ├─ real-time PCM mix → MediaCodec AAC → MediaMuxer → temp_audio.m4a
                   MIC → AudioRecord #2 ─┘
Post-hoc merge: copy video track + copy pre-mixed audio track → final.mp4
```

**Key: In "both" mode, MediaRecorder records VIDEO ONLY.** All audio (mic + internal)
is mixed in real-time on the audio thread and written to temp_audio.m4a.
Post-hoc merge copies tracks — NO decode+re-encode needed.
This matches AOSP's ScreenInternalAudioRecorder pattern exactly.

**Same MediaProjection instance** used for both VirtualDisplay and AudioPlaybackCapture.
No new token requested.

### 5. Audio mode selection in UI

Three radio buttons:
- Mic only (default — current behavior, zero new code)
- Internal only
- Both

### 6. Graceful degradation

- If AudioRecord creation fails (vendor ROM issue): fall back to mic-only, show Toast. **Catch `Exception` (not just `UnsupportedOperationException`)** — vendor ROMs throw `RuntimeException: registerAudioPolicy() returned -1` (confirmed: scrcpy issue #5138).
- If one AudioRecord errors mid-recording: fill with zeros, continue (AOSP pattern)
- If AudioPlaybackCapture returns silence (WebView v147 bug): recording continues with mic audio

### 6a. Foreground service type conditional

`startForeground()` bitmask must match actual audio sources:
- **internal-only mode:** `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` only (no mic privacy indicator)
- **mic-only / both mode:** `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or FOREGROUND_SERVICE_TYPE_MICROPHONE`

Manifest still declares both types statically. The `startForeground()` call selects dynamically.

### 7. Thread model

```
Main Thread          ── UI updates, timer
MediaRecorder        ── handles video + optional mic (existing)
Audio Thread (new)   ── reads AudioRecord(s), mixes PCM,
                        feeds MediaCodec AAC encoder,
                        handles INFO_OUTPUT_FORMAT_CHANGED → addTrack → muxer.start(),
                        drains encoder output to temp MediaMuxer
Merge Thread         ── background thread after stop, merges temp files,
                        writes final MP4 to MediaStore, sets IS_PENDING=0
                        Holds WakeLock during merge. Checks volatile isCancelled
                        flag periodically. stopSelf() only after thread exits.
```

No concurrent MediaMuxer writes. No PTS synchronization needed during recording.

### 8. MediaCodec drain sequence (critical)

**MediaFormat setup (required keys):**
```kotlin
medFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
    MediaCodecInfo.CodecProfileLevel.AACObjectLC)
medFormat.setInteger(MediaFormat.KEY_PCM_ENCODING,
    AudioFormat.ENCODING_PCM_16BIT)
```
Without `KEY_AAC_PROFILE`, hardware encoders (Samsung/Qualcomm/MTK) may fail `configure()` or produce unreadable AAC. Without `KEY_PCM_ENCODING`, some API 29-31 devices default to `ENCODING_PCM_FLOAT` producing silence/noise.

Audio encoder drain loop must handle:
1. `INFO_OUTPUT_FORMAT_CHANGED` → `muxer.addTrack(codec.outputFormat)` → `muxer.start()`
2. **Discard `BUFFER_FLAG_CODEC_CONFIG` output buffers** — CSD is already embedded in the track format via `addTrack(codec.outputFormat)`. Writing these to the muxer corrupts the `.m4a` file.
3. Buffer samples only written AFTER muxer started
4. End-of-stream: `dequeueInputBuffer` with retry loop (NOT 500µs one-shot — returns -1 under load, then `queueInputBuffer(-1, ...)` crashes). Use 10ms timeout in retry loop:
```kotlin
var bufferIndex: Int
do {
    bufferIndex = codec.dequeueInputBuffer(10_000) // 10ms
} while (bufferIndex < 0)
codec.queueInputBuffer(bufferIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
```
5. Drain remaining output buffers, stop muxer

**Encode loop `dequeueInputBuffer` timeout:** Use 10ms (10_000µs), not AOSP's 500µs. On 500µs timeout, -1 return causes current PCM batch to be silently dropped. Retry on -1 to avoid audio dropouts.

**PTS formula (mandatory — do NOT use System.nanoTime()):**
```kotlin
val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate
// compute PTS BEFORE incrementing totalSamplesWritten
totalSamplesWritten += samplesInThisBatch
```
Compute PTS from current `totalSamplesWritten` BEFORE incrementing. Increment-first shifts all timestamps by one frame (~23ms at 44100Hz), compounding A/V sync gap.

### 9. Post-hoc merge details

- **Validate temp files before merge:** check temp_video exists and is non-zero. If empty/missing (e.g. MediaRecorder.stop() threw), skip merge — promote whatever valid temp file exists, or delete IS_PENDING entry
- **Call `MediaExtractor.selectTrack(i)`** for each desired track before reading — without this, `readSampleData()` silently returns -1 with no exception
- Select tracks by MIME prefix (`video/`, `audio/`), NOT by index
- Skip `BUFFER_FLAG_CODEC_CONFIG` samples during merge (already embedded in track format)
- **Preserve `SAMPLE_FLAG_SYNC` → translate to `MediaCodec.BUFFER_FLAG_KEY_FRAME`** when writing to muxer — dropping flags produces unseekable video
- **Preserve rotation metadata:** read `MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION` from temp_video, call `muxer.setOrientationHint(degrees)` BEFORE `muxer.start()`. Without this, portrait recordings become landscape in merged output.
- MediaRecorder outputs to temp file in cache dir (NOT MediaStore)
- **Merge writes to `temp_merged.mp4` in cacheDir (third temp file), NOT directly to MediaStore FD.** Only after temp_merged is fully written and closed, copy its content to the MediaStore URI and set IS_PENDING=0. This prevents partial writes to MediaStore on merge failure.
- Foreground service stays alive during merge, notification shows "Finishing recording…"
- Merge runs on background thread with **WakeLock** held. Thread checks `volatile isCancelled` periodically for graceful shutdown on service destruction.
- **Merge failure fallback:** copy `temp_video.mp4` content to the pending MediaStore URI and set `IS_PENDING=0` — user gets video-only recording. Do NOT leave `IS_PENDING=1` (invisible to user = data loss).

### 10. Temp file cleanup on startup

In `RecordingService.onCreate()`: delete any stale temp files matching `temp_video*.mp4` and `temp_audio*.m4a` in `cacheDir`. Handles process kill during merge leaving orphaned files.

### 11. Storage availability check

Before creating temp files, estimate required space dynamically based on quality preset:
```kotlin
val estimatedBytes = (config.bitrate.toLong() * 1800 / 8) + (196_000L * 1800 / 8) // 30min video + audio
val required = (estimatedBytes * 1.1).toLong() // 10% safety margin
if (cacheDir.usableSpace < required) abort()
```
Do NOT use a flat 1.3 GB threshold — LOW preset (1 Mbps) only needs ~250 MB, and false rejections are common on budget devices.

## Risks / Trade-offs

- **Post-hoc merge delay**: 1-2 seconds to merge tracks after stop. Acceptable.
- **A/V sync gap (accepted)**: MediaRecorder and AudioRecord start at different times (50-200ms delta). Both PTS streams start at 0 in their respective temp files. After merge, this produces a small but detectable A/V sync offset. Accepted trade-off of post-hoc muxing — correcting requires recording wall-clock timestamps at pipeline start and adjusting PTS during merge, which adds complexity for minimal perceptual benefit.
- **Temp file storage**: Need temp files in cache dir. Cleaned up after merge. Stale files cleaned on service startup.
- **App opt-out**: DRM apps set ALLOW_CAPTURE_BY_NONE — their audio silent. Expected.
- **Pixel Camera Services quirk**: On GrapheneOS, denying camera permission to Pixel Camera Services can break audio capture. Document workaround.
- **WebView v147 regression**: AudioPlaybackCapture may return silence (active bug June 2026). Graceful degradation: recording continues with mic-only audio.
- **MIC vs VOICE_COMMUNICATION**: Using AudioSource.MIC instead of AOSP's VOICE_COMMUNICATION. Real trade-off is echo cancellation (VOICE_COMMUNICATION prevents speaker→mic echo in "both" mode), not concurrent capture restrictions (AudioPlaybackCapture uses software tap, no mic hardware conflict). Echo cancellation is a non-goal. If echo is unacceptable, switch to VOICE_COMMUNICATION.
- **RecordingConfig constants**: MediaRecorder.VideoEncoder.HEVC is integer constant, MediaCodec uses MIME strings. Need conversion for MediaCodec audio encoder setup.
- **44100 Hz hardcode (accepted)**: Most Android devices have native HAL rate of 48000 Hz; requesting 44100 Hz forces resampling. AOSP's ScreenInternalAudioRecorder also hardcodes 44100 Hz. Minor quality/CPU trade-off, not worth the complexity of dynamic sample rate detection.
