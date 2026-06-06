## Context

Greenfield Android app for Pixel 9 Pro running GrapheneOS (Android 16 / API 35). No existing codebase. Purpose: screen recording with configurable quality and dual audio capture for recording video calls at reasonable file sizes.

Android's MediaProjection API provides screen capture. MediaCodec + MediaMuxer give full control over encoding. AudioPlaybackCapture (API 29+) enables internal audio capture alongside microphone.

## Goals / Non-Goals

**Goals:**
- Record screen at user-selected resolution (480p/720p/1080p) instead of native 2K
- Capture both internal audio and microphone, mixed into single stream
- Encode with H.265 for smaller files
- Simple preset-based UI (Low/Medium/High)
- Run as foreground service with notification stop control
- Unlimited duration recording

**Non-Goals:**
- Publishing to Play Store / F-Droid
- Supporting multiple Android versions or devices
- Video editing, trimming, or post-processing
- Streaming or sharing features
- Pause/resume recording
- Camera overlay (PiP selfie view)

## Decisions

### 1. MediaCodec + MediaMuxer over MediaRecorder

**Decision:** Use MediaCodec for encoding + MediaMuxer for muxing, not MediaRecorder.

**Rationale:** MediaRecorder accepts only one audio source. We need two (internal + mic) mixed together. MediaCodec gives direct control over encoder configuration and accepts raw PCM input.

**Alternative:** MediaRecorder with single audio source — rejected because we need both sides of a call.

### 2. H.265 (HEVC) as video codec

**Decision:** Use HEVC encoder (`video/hevc`).

**Rationale:** ~40-50% smaller files than H.264 at equivalent quality. Pixel 9 Pro has hardware HEVC encoder. Playback supported everywhere that matters.

**Alternative:** H.264 — simpler, wider compat, but significantly larger files. Not needed since target is single device.

### 3. PCM mixing in a dedicated thread

**Decision:** Two AudioRecord instances read into ring buffers. A mixer thread reads both, adds samples with clamping, and feeds mixed PCM to the audio MediaCodec encoder.

```
Thread: AudioRecord(internal) → ringBuffer1 ─┐
                                               ├→ MixerThread → AudioEncoder
Thread: AudioRecord(mic) → ringBuffer2 ───────┘
```

**Rationale:** AudioRecord.read() is blocking. Separate threads prevent one source from starving the other. Ring buffers decouple read timing from mix timing.

**Alternative:** Single-threaded sequential reads — risks buffer overruns if one source blocks.

### 4. VirtualDisplay at target resolution

**Decision:** Create VirtualDisplay at the selected preset resolution (e.g., 720p), not at native resolution.

**Rationale:** Android composites and scales the screen content to fit the VirtualDisplay dimensions. This means the encoder receives frames already at target resolution — no post-capture downscaling needed.

### 5. Presets over manual controls

**Decision:** Three presets (Low/Medium/High) mapping to fixed resolution + bitrate + FPS combinations.

| Preset | Resolution | Bitrate | FPS | ~Size/hr |
|--------|-----------|---------|-----|----------|
| Low    | 854×480   | 1 Mbps  | 24  | ~0.4 GB  |
| Medium | 1280×720  | 3 Mbps  | 30  | ~1.2 GB  |
| High   | 1920×1080 | 6 Mbps  | 30  | ~2.5 GB  |

**Rationale:** "Just me" app — presets are faster than fiddling with sliders. Medium preset is the primary use case (720p call recording).

### 6. Single MP4 file, no segmentation

**Decision:** Write to single MP4 via MediaMuxer. No file splitting.

**Rationale:** At Medium preset, ~1.2 GB/hr. A 4-hour call = ~5 GB. Acceptable. Segmentation adds complexity (gapless concat, timestamp continuity). Not worth it for personal use.

**Risk:** Crash during recording = potentially corrupt MP4 (moov atom at end). Acceptable for personal use — alternative (fragmented MP4) adds significant complexity.

## Risks / Trade-offs

- **[Crash = lost recording]** → MediaMuxer writes moov atom on stop(). Crash before stop = corrupt file. Mitigation: none for v1, accept the risk. Could add fragmented MP4 later.
- **[Audio sync drift]** → Two separate AudioRecords may drift over long recordings. Mitigation: both use same sample rate (44100Hz), mixer thread processes in lockstep. Monitor in testing.
- **[GrapheneOS permission restrictions]** → GrapheneOS may have additional MediaProjection restrictions. Mitigation: test early. If blocked, fall back to internal-audio-only mode.
- **[HEVC encoder availability]** → Assumed hardware HEVC encoder on Pixel 9 Pro. Mitigation: check at runtime, fall back to H.264.
