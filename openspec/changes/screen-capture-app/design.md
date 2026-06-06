## Context

Greenfield Android app for Pixel 9 Pro running GrapheneOS (Android 16 / API 36). No existing codebase. Purpose: screen recording with configurable quality and mic audio capture for recording video calls at reasonable file sizes.

Android's MediaProjection API provides screen capture. MediaRecorder provides encoding and muxing in a single API. Mic captures both the user's voice and the other party's audio via speakerphone.

Note: AudioPlaybackCapture cannot capture `USAGE_VOICE_COMMUNICATION` audio for non-system apps. Internal call audio capture would require Shizuku + AudioPolicy API — deferred to a future enhancement.

## Goals / Non-Goals

**Goals:**
- Record screen at user-selected resolution (480p/720p/1080p) instead of native 2K
- Capture mic audio (user voice + speakerphone bleed)
- Encode with H.265 for smaller files
- Simple preset-based UI (Low/Medium/High)
- Run as foreground service with notification stop control
- Unlimited duration recording

**Non-Goals:**
- Internal audio capture (requires system-level privileges)
- Publishing to Play Store / F-Droid
- Supporting multiple Android versions or devices
- Video editing, trimming, or post-processing
- Streaming or sharing features
- Pause/resume recording
- Camera overlay (PiP selfie view)

## Decisions

### 1. MediaRecorder over MediaCodec + MediaMuxer

**Decision:** Use MediaRecorder for encoding and muxing.

**Rationale:** With mic-only audio, there's no need for dual AudioRecord + PCM mixing. MediaRecorder handles encoding (video + audio) and muxing (MP4) in a single API. Simpler code, fewer threads, fewer failure modes.

**Alternative:** MediaCodec + MediaMuxer — rejected. Only needed for dual audio mixing which is deferred. Adds threading complexity (ring buffers, mixer thread) for no benefit.

### 2. H.265 (HEVC) as video codec

**Decision:** Use HEVC encoder via `MediaRecorder.VideoEncoder.HEVC`.

**Rationale:** ~40-50% smaller files than H.264 at equivalent quality. Pixel 9 Pro has hardware HEVC encoder. Playback supported everywhere that matters.

**Alternative:** H.264 — fallback if HEVC unavailable at runtime.

### 3. VirtualDisplay at target resolution

**Decision:** Create VirtualDisplay at the selected preset resolution (e.g., 720p), not at native resolution.

**Rationale:** Android composites and scales the screen content to fit the VirtualDisplay dimensions. Encoder receives frames already at target resolution — no post-capture downscaling needed.

### 4. Presets over manual controls

**Decision:** Three presets (Low/Medium/High) mapping to fixed resolution + bitrate + FPS combinations.

| Preset | Resolution | Bitrate | FPS | ~Size/hr |
|--------|-----------|---------|-----|----------|
| Low    | 854×480   | 1 Mbps  | 24  | ~0.4 GB  |
| Medium | 1280×720  | 3 Mbps  | 30  | ~1.2 GB  |
| High   | 1920×1080 | 6 Mbps  | 30  | ~2.5 GB  |

**Rationale:** "Just me" app — presets are faster than fiddling with sliders. Medium preset is the primary use case (720p call recording).

### 5. MediaProjection.Callback registration

**Decision:** Register `MediaProjection.Callback` with `onStop()` handler before calling `createVirtualDisplay()`.

**Rationale:** Android 14+ mandates this. Without it: `IllegalStateException`. The callback handles cleanup when the system revokes the projection (e.g., user revokes from settings).

### 6. Storage via app-specific directory

**Decision:** Save recordings to `getExternalFilesDir(Environment.DIRECTORY_MOVIES)`.

**Rationale:** No extra permissions needed on Android 10+ scoped storage. User can access via file manager. Avoids MediaStore complexity for a personal app.

## Risks / Trade-offs

- **[Audio quality via speaker]** → Mic captures room noise + echo alongside call audio. Acceptable for personal archival. Enhancement path: Shizuku + AudioPolicy API for clean internal audio.
- **[Crash = lost recording]** → MediaRecorder writes moov atom on stop(). Crash before stop = corrupt file. Acceptable for personal use. Enhancement path: fragmented MP4 via media3.
- **[HEVC encoder availability]** → Assumed hardware HEVC on Pixel 9 Pro. Check at runtime, fall back to H.264.
- **[GrapheneOS restrictions]** → Test MediaProjection consent flow early. No known blockers but GrapheneOS hardens security.
