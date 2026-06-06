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
- Mic: `AudioSource.MIC` (NOT VOICE_COMMUNICATION — it's privacy-sensitive and blocks concurrent capture on some ROMs)

Mix PCM buffers: scale mic 1.4x, add, clamp to Short range.

Handle unequal read sizes: track buffer offsets, use `shiftToStart()` pattern from AOSP.

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

- If AudioRecord creation fails (vendor ROM issue): fall back to mic-only, show Toast
- If one AudioRecord errors mid-recording: fill with zeros, continue (AOSP pattern)
- If AudioPlaybackCapture returns silence (WebView v147 bug): recording continues with mic audio

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
```

No concurrent MediaMuxer writes. No PTS synchronization needed during recording.

### 8. MediaCodec drain sequence (critical)

Audio encoder drain loop must handle:
1. `INFO_OUTPUT_FORMAT_CHANGED` → `muxer.addTrack(codec.outputFormat)` → `muxer.start()`
2. Buffer samples only written AFTER muxer started
3. End-of-stream: signal `BUFFER_FLAG_END_OF_STREAM`, drain remaining, stop muxer

### 9. Post-hoc merge details

- Select tracks by MIME prefix (`video/`, `audio/`), NOT by index
- Skip `BUFFER_FLAG_CODEC_CONFIG` samples during merge (already embedded in track format)
- MediaRecorder outputs to temp file in cache dir (NOT MediaStore)
- IS_PENDING=0 set only after merge completes successfully
- Foreground service stays alive during merge, notification shows "Finishing recording…"
- Merge runs on background thread to avoid ANR

## Risks / Trade-offs

- **Post-hoc merge delay**: 1-2 seconds to merge tracks after stop. Acceptable.
- **Temp file storage**: Need temp files in cache dir. Cleaned up after merge.
- **App opt-out**: DRM apps set ALLOW_CAPTURE_BY_NONE — their audio silent. Expected.
- **Pixel Camera Services quirk**: On GrapheneOS, denying camera permission to Pixel Camera Services can break audio capture. Document workaround.
- **WebView v147 regression**: AudioPlaybackCapture may return silence (active bug June 2026). Graceful degradation: recording continues with mic-only audio.
- **VOICE_COMMUNICATION blocked**: Using AudioSource.MIC instead to avoid privacy-sensitive concurrent capture restrictions.
- **RecordingConfig constants**: MediaRecorder.VideoEncoder.HEVC is integer constant, MediaCodec uses MIME strings. Need conversion for MediaCodec audio encoder setup.
