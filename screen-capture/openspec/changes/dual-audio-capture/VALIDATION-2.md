# Validation Pass 2 — dual-audio-capture

Reviewer: independent pass  
Date: 2026-06-06  
Scope: design.md, proposal.md, tasks.md, all three specs. Read-only.

---

## Verdict: RISKY

No single issue is an impossible API usage, but ten issues will cause real failures on specific
devices or under specific conditions. Two (KEY_AAC_PROFILE, EOS timing) are high-probability
failures on common Android hardware. None are theoretical.

---

## Problems Found

### 1. KEY_AAC_PROFILE missing from MediaCodec configuration — RISKY / device-specific FAIL

**Where:** task 4.1, mediacodec-pipeline spec ("MediaCodec AAC encoder (196kbps, 44100Hz, mono)")

**What:** Neither the task nor the spec mentions `MediaFormat.KEY_AAC_PROFILE`.

**Why it matters:** Android MediaCodec docs state explicitly:
> "The selection of the default profile is device specific and may not be deterministic (could be
> ad hoc or even experimental). The encoder may choose a default profile that is not suitable for
> the intended encoding session, which may result in the encoder ultimately rejecting the session."

AOSP's `ScreenInternalAudioRecorder` always sets:
```java
medFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
```

Without this, hardware AAC encoders on Samsung Exynos, Qualcomm, and MediaTek SoCs have been
observed to fail `configure()` or produce AAC-HE bitstreams that cannot be parsed by
`MediaExtractor` during the post-hoc merge step. The merge would fail with no valid audio track.

**Fix:** Add to MediaFormat before `configure()`:
```kotlin
medFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
    MediaCodecInfo.CodecProfileLevel.AACObjectLC)
```

---

### 2. EOS signal at endStream() can crash if dequeueInputBuffer times out — RISKY

**Where:** task 4.5 ("signal BUFFER_FLAG_END_OF_STREAM, drain remaining buffers, stop muxer"),
design §8 (drain sequence)

**What:** No timeout retry logic specified for the `dequeueInputBuffer()` call that precedes
the EOS signal.

**Why it matters:** AOSP's `endStream()`:
```java
int bufferIndex = mCodec.dequeueInputBuffer(TIMEOUT);  // TIMEOUT = 500 microseconds
mCodec.queueInputBuffer(bufferIndex, 0, 0, mPresentationTime,
    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
```

If `dequeueInputBuffer` returns `-1` (no buffer available within 500µs — which can happen under
CPU/memory pressure), `queueInputBuffer(-1, ...)` throws `IllegalArgumentException`. The EOS is
never sent to the codec. Calling `muxer.stop()` before the codec produces its own EOS output
truncates the last audio frames and may produce an unreadable `.m4a` footer.

The 500µs timeout is dangerously short at end-of-stream: the encoder may be mid-frame. Devices
under any load (common during stop: UI callbacks, file I/O, MediaRecorder stop) can trigger this.

**Fix:** Either block (`dequeueInputBuffer(-1)`) or spin with retry on -1 return, specifically
for the EOS signal path:
```kotlin
var bufferIndex: Int
do {
    bufferIndex = codec.dequeueInputBuffer(10_000) // 10ms
} while (bufferIndex < 0)
codec.queueInputBuffer(bufferIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
```

---

### 3. PTS ordering ambiguity causes off-by-one if implemented naively — RISKY

**Where:** task 4.4, mediacodec-pipeline spec ("totalSamplesWritten is incremented by the number
of short samples per call")

**What:** The spec doesn't say whether to compute PTS before or after incrementing
`totalSamplesWritten`.

**Why it matters:** Natural reading is: feed buffer → increment → compute PTS. But AOSP does:
compute PTS (from samples so far) → feed buffer → increment. The difference:

- Increment-first: first buffer gets `PTS = firstBatchSamples / 44100 ≈ 23ms`. Every subsequent
  timestamp is shifted by one frame. Audio effectively starts 23ms late in the merged file.
- Increment-after (AOSP): first buffer gets `PTS = 0`. Correct.

A ~23ms shift compounds with the already-accepted A/V sync gap (50-200ms from MediaRecorder
start delta) making A/V sync worse by one frame.

**Fix:** Spec should explicitly state: "Compute `ptsUs` from the current value of
`totalSamplesWritten` BEFORE incrementing it by the samples just queued."

---

### 4. shiftToStart() latent buffer corruption bug inherited from AOSP — RISKY

**Where:** task 3.3 ("handle unequal read sizes with offset tracking and shiftToStart()")

**What:** AOSP's `shiftToStart` call passes the OLD offset as `end` instead of `readShortsTotal`:

```java
// After: readShortsInternal += offsetShortsInternal (old offset)
// After: minShorts = min(readShortsInternal, readShortsMic)
shiftToStart(bufferInternal, minShorts, offsetShortsInternal);  // BUG: should be readShortsInternal
```

`shiftToStart(target, start, end)` copies elements `[start..end)` to position 0. With `end =
offsetShortsInternal_OLD` (< `readShortsInternal`), it copies fewer elements than are actually
remaining. The remaining samples (positions `offsetShortsInternal_OLD` to `readShortsInternal-1`)
stay in place. Next iteration's `read(bufferInternal, newOffset=readShortsInternal-minShorts, ...)`
overwrites them.

**Impact:** When the two AudioRecords return unequal read sizes (same format/rate makes this
unlikely but not impossible — Qualcomm HAL during backpressure, device startup), PCM samples from
the internal or mic buffer are silently dropped, producing audible glitches.

**The design doesn't note this bug.** If following the spec's "shiftToStart() pattern from AOSP"
guidance, developers copy the bug. The correct call is:
```kotlin
shiftToStart(bufferInternal, minShorts, readShortsInternal) // readShortsInternal = new + oldOffset
```

---

### 5. MIC vs VOICE_COMMUNICATION rationale is incorrect — RISKY

**Where:** design §2, task 3.2 ("AudioSource.MIC to avoid privacy-sensitive concurrent capture
restrictions")

**What:** The design frames VOICE_COMMUNICATION as risky because it's "privacy-sensitive" and
might trigger concurrent capture restrictions. This mechanism doesn't apply here.

**Why it matters:** AudioPlaybackCapture captures the **software audio mix output** — not
microphone hardware. It does not participate in the concurrent microphone capture policy at all.
The two sources (mic hardware vs. playback tap) use different audio paths; Android's
privacy-sensitive priority scheme applies only to concurrent hardware microphone captures.

The ACTUAL difference:
- `VOICE_COMMUNICATION` applies noise suppression, echo cancellation, and AGC processing.
- `MIC` gives raw hardware signal.

For "both" mode where the device speaker is playing captured audio while the mic is open,
using `MIC` without echo cancellation means the speaker output leaks into the mic track,
producing audible echo in the final recording. VOICE_COMMUNICATION's echo cancellation is
precisely designed for this scenario.

AOSP uses `VOICE_COMMUNICATION` for a reason. The design's justification for departing from
AOSP is factually wrong. The vendor ROM risk cited in scrcpy issue #5138 (`registerAudioPolicy()
returned -1`) affects **AudioPlaybackCapture creation**, not the mic AudioRecord.

---

### 6. Storage check uses flat 1.3 GB threshold for all presets — RISKY

**Where:** design §11, task 6.2

**What:** The design says "check ~1.3 GB for 30min 1080p" as the threshold, applied universally.

**Why it matters:** LOW preset is 1 Mbps. 30 minutes of LOW = (1,000,000 * 1800)/8 ≈ 225 MB.
MEDIUM (3 Mbps, 30min) ≈ 675 MB. Using 1.3 GB for LOW mode means users with 300–1200 MB free
cache get a false "insufficient space" error when their recording would actually fit.

On GrapheneOS (often minimal cacheDir quotas) and budget Android devices (32–64 GB internal
storage shared across apps), this false rejection is common.

**Fix:**
```kotlin
val estimatedBytes = (config.bitrate.toLong() * 1800 / 8) + (196_000L * 1800 / 8)
```
Check `estimatedBytes * 1.1` (10% safety margin) against `cacheDir.usableSpace`.

---

### 7. KEY_PCM_ENCODING omitted from MediaFormat — RISKY (lower severity)

**Where:** task 4.1, mediacodec-pipeline spec

**What:** AOSP explicitly sets:
```java
medFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, mConfig.encoding);
```

The spec omits this. Android docs list it as "optional." However, some hardware encoders on
API 29–31 devices default to `ENCODING_PCM_FLOAT` when this key is absent. The resulting
AAC frames decode as silence or noise.

**Fix:** Add `setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)`.

---

### 8. Post-hoc merge output destination underspecified — RISKY

**Where:** task 5.5 ("Write merged output to MediaStore URI"), design §9

**What:** Ambiguous whether ScreenRecordingMuxer should write via `MediaMuxer(fd, MPEG_4)` where
`fd = contentResolver.openFileDescriptor(mediaStoreUri).fileDescriptor`, or to a third temp
file followed by copy.

**Why it matters:** If `MediaMuxer(mediaStoreUri_fd, ...)` is used and the muxer fails
mid-write (OOM, storage fill, codec error), the MediaStore file is partially written with
`IS_PENDING=1`. The fallback path in task 5.7 says "copy temp_video content to the pending
MediaStore URI" — but the FD used for the failed muxer may have an unknown position. Writing
to the same FD after a partial muxer failure produces a corrupt file.

AOSP writes to a **third temp file**, then moves content to MediaStore. The pending URI's FD
is only written once, after successful merge.

**Fix:** Specify that ScreenRecordingMuxer writes to `temp_merged.mp4` in cacheDir, not
directly to the MediaStore FD. The IS_PENDING MediaStore entry is written only after
`temp_merged.mp4` is fully written and closed.

---

### 9. AudioRecord recording state not validated post-startRecording() — RISKY

**Where:** task 2.3 (exception catching), internal-audio-capture spec

**What:** Spec covers `catch(Exception)` for `AudioRecord.Builder.build()`. Does not mention
checking `getRecordingState() == RECORDSTATE_RECORDING` after `startRecording()`.

**Why it matters:** Vendor ROMs (especially MIUI) silently succeed at `build()` but no-op
`startRecording()` — the AudioRecord state stays `RECORDSTATE_STOPPED`. All `read()` calls
return `ERROR_INVALID_OPERATION`. The recording captures silence with no exception thrown.

AOSP explicitly checks:
```java
if (mAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
    throw new IllegalStateException("Audio recording failed to start");
}
```

Without this, the fallback-to-mic-only path (task 2.3) never fires for silent-fail ROMs.

---

### 10. Merge thread has no cancellation path on service destruction — RISKY

**Where:** tasks 6.3–6.5

**What:** Merge runs on a background thread. Service `onDestroy()` (called on OOM kill, battery
saver cutoff, or force-stop) calls `stopRecording()`, which returns early because `isRecording=false`.
The merge thread is not interrupted.

**Why it matters:** After `stopForeground()` (end of recording), the service loses its foreground
priority. If the merge thread is still running, Android can kill the process mid-merge. This leaves
`IS_PENDING=1` in MediaStore — the recording is invisible to the user and gallery apps. This is
the exact data loss condition the spec says to avoid.

**Fix:** Merge thread must hold a `WakeLock` (or the service must remain in foreground during
merge, which the spec correctly mandates in task 6.4). Additionally, the thread should check a
`volatile isCancelled` flag periodically and gracefully flush+stop the muxer if cancelled.
`stopSelf()` (task 6.5) must not be called until merge thread has exited.

---

## Missing Considerations

### 11. dequeueInputBuffer timeout in main encode loop drops PCM silently

AOSP's `encode()` uses `TIMEOUT = 500` microseconds. When `dequeueInputBuffer` returns -1
(encoder backpressured), AOSP returns from `encode()` **without encoding the current PCM batch**.
Those bytes are discarded. Under any sustained encoding pressure (first few seconds of recording,
CPU-intensive apps), this produces periodic audio dropouts.

The spec doesn't specify timeout values or retry policy for the main encode loop. Developers
copying AOSP verbatim will have this silent data-drop behavior.

Recommendation: In the encode loop, retry with increasing timeout when `dequeueInputBuffer`
returns -1, rather than dropping PCM silently.

---

### 12. MediaMuxer orientation hint not preserved during merge

`MediaRecorder` embeds rotation metadata when recording in portrait mode (e.g., `KEY_ROTATION=90`
in the container). `MediaExtractor.getTrackFormat(i)` returns a format without `KEY_ROTATION`
(it's stored as container-level metadata, not track-level).

`ScreenRecordingMuxer` must call `muxer.setOrientationHint(degrees)` **before `muxer.start()`**
where `degrees` is read from the temp video's container-level rotation. Without this, portrait
recordings are saved as landscape (rotated 90°) in the merged output.

Android provides `MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION` to read this. The spec
doesn't mention orientation preservation.

---

### 13. Dual-error escape from mixing loop not specified

Task 3.5 says "fill its buffer with zeros and continue" if one source errors. AOSP breaks out of
the loop when **both** sources error:
```java
if (readShortsInternal < 0 && readShortsMic < 0) {
    break;
}
```

The spec only describes the single-source-error case. A developer may implement infinite
zero-fill (both sources return -1 → both become zeros → encode silence forever) instead of
breaking and calling `endStream()`. This hangs the audio thread indefinitely.

---

### 14. AudioRecord.Builder setBufferSizeInBytes not explicitly required

The spec says "Buffer size: `getMinBufferSize() * 2`" for both AudioRecords. The mic AudioRecord
can be created with the legacy constructor (5-arg form) where buffer size is a constructor param.
But the playback capture AudioRecord MUST use `AudioRecord.Builder` with
`setAudioPlaybackCaptureConfig()`. On `AudioRecord.Builder`, buffer size is set via
`.setBufferSizeInBytes()` — if omitted, the builder uses the minimum (1 frame). This is not
mentioned anywhere in the spec.

A developer may correctly set buffer size for mic (constructor) but accidentally omit it for
the playback capture AudioRecord (Builder), getting 2-byte buffers and instant overflows.

---

### 15. No validation of temp video file before merge attempt

Before calling `MediaExtractor.setDataSource(tempVideoPath)`, the merge code should verify the
file exists and is non-zero. Task 6.6 handles `mediaRecorder.stop()` throwing but doesn't
chain this to "skip merge and promote directly." If `stop()` throws, the temp video is likely
empty, and `MediaExtractor.setDataSource()` will throw `IOException: not a valid MP4 container`.
The merge failure fallback (task 5.7) then tries to copy an empty/invalid file to MediaStore.

---

## Questions for the Author

1. **VOICE_COMMUNICATION vs MIC intent confirmed?** The design's rationale (concurrent capture
   restriction) is factually incorrect (different audio paths). Given that VOICE_COMMUNICATION
   provides echo cancellation specifically useful in "both" mode, was the MIC choice a deliberate
   quality trade-off, or based on the mischaracterized concern?

2. **Is the merge writing directly to MediaStore FD, or via a third temp file?** AOSP uses a third
   temp file for safety. The spec says "write merged output to MediaStore URI" which implies direct.
   Direct FD is fine if clarified, but the failure recovery path (task 5.7) needs to account for FD
   position after a partial failed write.

3. **What's the audio thread's dequeueInputBuffer timeout policy?** 500µs (AOSP) drops PCM on
   backpressure silently. Is this acceptable, or should the implementation use longer timeouts with
   retry?

4. **cacheDir quota on GrapheneOS?** Android 8+ enforces per-app cacheDir quotas enforced by the
   `storaged` daemon. `File.getUsableSpace()` returns the system-wide free space, not the per-app
   cache quota. On GrapheneOS with strict quotas, even `usableSpace > 1.3GB` may still fail on
   actual `FileOutputStream` creation. The storage check may be insufficient.

5. **targetSdkVersion?** The spec's conditional `startForeground()` bitmask behavior is only
   meaningful for API 34+ targets. On API 33 targets, `startForeground()` ignores the type
   bitmask beyond what's in the manifest. The foreground service mic indicator behavior differs
   between SDK targets. Clarifying the min/target SDK would resolve which behavior to test against.
