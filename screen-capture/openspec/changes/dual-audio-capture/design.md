## Context

Screen recorder currently uses `MediaRecorder` which accepts a single audio source. To capture both mic and internal audio, we need the `MediaCodec` + `MediaMuxer` pipeline with dual `AudioRecord` instances — the same pattern AOSP's built-in `ScreenInternalAudioRecorder` uses.

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

### 1. Use AOSP's ScreenInternalAudioRecorder pattern

Two `AudioRecord` instances running on a dedicated thread:
- Internal audio: `AudioPlaybackCaptureConfiguration` with MediaProjection
- Mic: `AudioSource.VOICE_COMMUNICATION` (has echo cancellation built-in)

Mix PCM buffers with additive mixing + clamping. Mic scaled 1.4x (AOSP default).

```
MediaProjection
    ├── VirtualDisplay → Surface → MediaCodec (video H.265/H.264)
    └── AudioPlaybackCapture → AudioRecord #1 ──┐
                                                 ├─ mix → MediaCodec (AAC) → MediaMuxer
                   MIC → AudioRecord #2 ─────────┘                              ↑
                                                                     video track ┘
```

### 2. MediaCodec + MediaMuxer replaces MediaRecorder

MediaRecorder doesn't support dual audio sources. Replace with:
- **Video**: MediaCodec encoder (HEVC/H.264) reading from VirtualDisplay Surface
- **Audio**: MediaCodec encoder (AAC) reading mixed PCM from dual AudioRecord
- **Muxer**: MediaMuxer writing both tracks to MP4

### 3. Audio mode selection in UI

Three radio buttons or spinner:
- Mic only (current behavior, simplest)
- Internal only (AudioPlaybackCapture, no mic)
- Both (dual AudioRecord + mixing)

Default: Both

### 4. Thread model

```
Main Thread          ── UI updates, timer
Audio Thread         ── read both AudioRecords, mix, feed to audio MediaCodec
Video MediaCodec     ── async mode, reads from VirtualDisplay Surface automatically
Muxer writes         ── from MediaCodec output callbacks (either thread)
```

### 5. Keep MediaRecorder as fallback for mic-only

When user selects "mic only", use existing MediaRecorder pipeline — simpler, proven. Only switch to MediaCodec pipeline for internal/both modes.

## Risks / Trade-offs

- **Complexity**: MediaCodec+MediaMuxer is significantly more code than MediaRecorder. More failure modes, more threading.
- **Audio sync**: Mic and internal audio buffers may have different latencies. May need timestamp alignment.
- **CPU**: Dual AudioRecord + mixing + encoding uses more CPU than MediaRecorder. Should be fine on Pixel 9 Pro but monitor.
- **File corruption on crash**: MediaMuxer doesn't write moov atom until `stop()`. Crash = corrupt file. Same as current MediaRecorder behavior.
- **App opt-out**: DRM apps (Netflix, Spotify) set `ALLOW_CAPTURE_BY_NONE` — their audio will be silent. Expected behavior, not a bug.
- **Pixel Camera Services quirk**: On GrapheneOS, denying camera permission to Pixel Camera Services can break audio capture. Document workaround.
