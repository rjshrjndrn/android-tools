# Validation Report: dual-audio-capture

**Date:** 2026-06-06  
**Validator:** pi (read-only design review)  
**Scope:** Design documents, specs, and tasks — not yet implemented

---

## Verdict: RISKY (with two FAIL-level issues)

---

### Problems Found

**1. [FAIL] Merge failure = data loss, user-inaccessible recording**

Task 5.7: *"keep temp_video as fallback recording"* — this does nothing useful for the user.

- `temp_video.mp4` is in `cacheDir` (app-private, no external access)
- MediaStore entry was inserted with `IS_PENDING=1` and never finalized
- `IS_PENDING=1` = invisible to gallery, file manager, and all media queries
- On merge failure: user sees no recording anywhere. Both the MediaStore entry and the video are effectively gone from the user's perspective.

The fallback must either: copy `temp_video` content to the pending MediaStore URI and set `IS_PENDING=0`, or create a new MediaStore entry pointing to the video-only output. As written, merge failure = silent data loss. This is a concrete data-loss risk.

---

**2. [FAIL] CODEC_CONFIG encoder output buffers not handled in drain loop**

Task 4.3 says: *"handle INFO_OUTPUT_FORMAT_CHANGED → addTrack() → muxer.start() before any writeSampleData"*

This is incomplete. The MediaCodec AAC encoder emits output buffers with `BUFFER_FLAG_CODEC_CONFIG` set — this is a **separate event** from `INFO_OUTPUT_FORMAT_CHANGED`. These codec-config output buffers must be **discarded and not written to the muxer**. When `addTrack(codec.outputFormat)` is used after `INFO_OUTPUT_FORMAT_CHANGED`, CSD is already embedded in the track format. Writing a CODEC_CONFIG-flagged output buffer to the muxer after `addTrack()` will corrupt the `.m4a` file.

Evidence from AOSP CTS `ExtractDecodeEditEncodeMuxTest.java`:
```java
if ((audioEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
    // Simply ignore codec config buffers.
    audioEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
    break;
}
```

Task 5.2 mentions skipping CODEC_CONFIG during the **post-hoc merge** (MediaExtractor → ScreenRecordingMuxer step). That is a different code path. The **encoder drain loop** (task 4.3) is silent on this requirement. A developer reading only task 4.3 will not know to discard these buffers during live encoding, resulting in a corrupt audio temp file.

---

**3. [RISKY] A/V sync gap not addressed**

MediaRecorder (video) and AudioRecord (audio) start at different times. There is no mechanism in the design to track or compensate for the startup delta.

- Video PTS: derived from Surface presentation timestamp (CLOCK_MONOTONIC relative), starts near 0
- Audio PTS: sample-count-based, starts at 0
- Both PTS streams are copied verbatim into the merged file with no adjustment

The startup delta between `mediaRecorder.start()` and `audioRecord.startRecording()` is typically 50–200ms depending on AudioRecord initialization time, JVM scheduling, and device load. After the post-hoc merge, audio and video are both anchored at t=0 in the MP4 container but their actual capture start points differ. This produces a detectable, persistent A/V sync offset.

The design states *"No PTS synchronization needed during recording"* which is technically true during recording, but ignores the startup alignment problem that manifests in the final merged file. This is not listed anywhere as a known limitation or accepted trade-off.

---

**4. [RISKY] `startForeground` always claims `FOREGROUND_SERVICE_TYPE_MICROPHONE` in internal-only mode**

Current code always passes:
```kotlin
startForeground(
    NOTIFICATION_ID,
    buildNotification(),
    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
)
```

In internal-only mode, no mic AudioRecord is created. Android 14+ displays a persistent microphone privacy indicator in the status bar for the entire recording duration — misleading the user into thinking their mic is being recorded when it is not.

The manifest must declare `mediaProjection|microphone` as a static set of possible types (correct as-is). But `startForeground()` accepts a dynamic bitmask. In internal-only mode, only `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` should be passed. No task in the spec covers this conditional logic.

---

**5. [RISKY] PTS formula unspecified — drift risk**

Task 4.4: *"Feed mixed/single-source PCM to encoder input buffers with sample-count-based PTS"*

The formula is not given. The correct implementation is:
```kotlin
ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate
```

If a developer uses `System.nanoTime()` instead (extremely common mistake in MediaCodec examples), audio PTS will drift relative to video PTS over long recordings due to OS scheduling jitter. On a 10-minute recording this can accumulate 100–500ms of drift. The spec says "sample-count-based" but leaves the formula implicit. It must be stated explicitly.

---

**6. [RISKY] AudioRecord buffer size completely unspecified**

Tasks 2.2 and 3.2 create `AudioRecord` instances. No `bufferSizeInBytes` guidance exists anywhere in the spec. `AudioRecord` construction requires this value:

- Too small → `AudioRecord.ERROR_INVALID_OPERATION` on `read()`, or buffer overruns causing dropped audio
- Too large → unnecessary memory pressure, increased latency

AOSP `ScreenInternalAudioRecorder.java` (confirmed via source at android.googlesource.com) uses:
```java
int size = AudioRecord.getMinBufferSize(mConfig.sampleRate, mConfig.channelInMask, mConfig.encoding) * 2;
```
Where `channelInMask = CHANNEL_IN_MONO` (not `CHANNEL_OUT_MONO`) — because `getMinBufferSize()` only cares about channel count, so either mask produces the same result. None of this is in the spec. The config default `1 << 17` (131072 bytes) used as a separate `bufferSizeBytes` field in AOSP's config is also not mentioned.

---

**7. [RISKY] `AudioSource.MIC` vs `VOICE_COMMUNICATION` — deviates from AOSP without stated validation**

Design says: *"Mic: AudioSource.MIC (NOT VOICE_COMMUNICATION — it's privacy-sensitive and blocks concurrent capture on some ROMs)"*

AOSP `ScreenInternalAudioRecorder.java` (source confirmed at android.googlesource.com/platform/frameworks/base) uses:
```java
mAudioRecordMic = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    mConfig.sampleRate, AudioFormat.CHANNEL_IN_MONO, mConfig.encoding, size);
```

The claim that `VOICE_COMMUNICATION` blocks concurrent capture on some ROMs is asserted without device list, error log, or reference. If true, the counter-risk is that `AudioSource.MIC` may behave differently in the presence of AudioPlaybackCapture's audio policy registration on other devices. Neither direction is tested or cited. This is a deliberate deviation from the AOSP reference implementation with no validation evidence.

---

**8. [RISKY] `registerAudioPolicy() returned -1` throws `RuntimeException` — exception type not documented**

From scrcpy issue #5138 (confirmed via search at github.com/Genymobile/scrcpy), on certain vendor ROMs AudioRecord construction for playback capture throws:
```
java.lang.RuntimeException: registerAudioPolicy() returned -1
at com.genymobile.scrcpy.audio.AudioPlaybackCapture.createAudioRecord(AudioPlaybackCapture.java:97)
```

Task 2.3: *"Wrap AudioRecord creation in try/catch — fallback to mic-only mode with Toast on failure"*

The task does not specify which exception type to catch. A developer who writes `catch (e: UnsupportedOperationException)` (logical assumption since that is the "official" Android SDK failure mode per the docs) will miss the `RuntimeException` path entirely and crash. The spec must explicitly state `catch (e: Exception)` to handle both `UnsupportedOperationException` (SDK-documented) and `RuntimeException` (vendor ROM variant confirmed in the wild).

---

### Missing Considerations

**A. Temp file cleanup on process kill during merge**

If the process is killed while the merge thread is running (OOM kill, user force-stop, battery pull), `temp_video.mp4` and `temp_audio.m4a` remain in `cacheDir` indefinitely, consuming storage. No on-start cleanup logic is specified. A cleanup pass in `RecordingService.onCreate()` — delete any stale temp files matching the naming convention — is not mentioned anywhere in the spec or tasks.

---

**B. Cache dir storage availability not checked**

A 30-minute 1080p recording at 6 Mbps generates approximately 1.3 GB of temp video plus temp audio before the merge step. No storage availability check is specified before creating temp files. A disk-full failure occurring mid-recording or mid-merge leaves the MediaStore entry at `IS_PENDING=1` (invisible to the user) and may leave partially-written temp files. Combined with problem #1 above, this is a data-loss scenario with no recovery path defined.

---

**C. `MediaExtractor.selectTrack()` not mentioned in merge spec**

Task 5.1: *"iterate tracks by MIME prefix (video/, audio/), NOT by index"*

The spec omits that you must call `MediaExtractor.selectTrack(i)` for each desired track before `readSampleData()` will return any data for that track. Without this call, every invocation of `readSampleData()` returns `-1` silently — no exception is thrown, the muxer just receives no data, and the merged output file contains empty tracks. A developer unfamiliar with the MediaExtractor API will hit this and get a corrupt output with no obvious error. The merge spec is underspecified.

---

**D. Sample flags not mentioned in merge copy path**

During the post-hoc merge, `MediaExtractor.getSampleFlags()` returns flags including `SAMPLE_FLAG_SYNC` (keyframe marker). These must be translated to `MediaCodec.BUFFER_FLAG_KEY_FRAME` when calling `muxer.writeSampleData()`. If flags are dropped — passing 0 always — the video track in the merged file has no keyframe markers. The result: random seek fails, and many players (including Android's own MediaPlayer) may refuse playback or only play from the beginning. Task 5.1 lists what to skip (CODEC_CONFIG) but says nothing about what to preserve (sync flags).

---

**E. Partial recording on `MediaProjection.onStop()` — error paths undefined**

Task 6.6: *"Handle MediaProjection revocation mid-recording: stop both pipelines cleanly, merge what we have"*

When MediaProjection is revoked, the VirtualDisplay is invalidated and MediaRecorder's surface becomes invalid. Calling `mediaRecorder.stop()` at this point may throw an exception if the encoder has not produced any frames yet (e.g., revocation happened within the first second). Additionally:

- What if the audio temp file is empty (AudioRecord was just created, never produced a full buffer before revocation)?
- What if MediaRecorder's output file has no moov atom (stop() threw, file is unplayable)?
- Should the merge still be attempted on zero-length inputs?

None of these sub-cases have defined behavior. "Merge what we have" is not actionable for these scenarios.

---

**F. Final audio frame duration in merged MP4**

MediaMuxer documentation states: *"If no explicit END_OF_STREAM sample was passed, then the duration of the last sample would be the same as that of the sample before that."* During the post-hoc merge (ScreenRecordingMuxer), the last audio sample is read from `temp_audio.m4a` via MediaExtractor and written to the merged file. Whether an explicit EOS buffer with a corrected PTS is written at the end of the audio track in the merged file is not specified. The last audio frame may get an incorrect duration, causing audio to appear slightly shorter or longer than the video track in the final MP4, depending on the player's interpretation.

---

**G. Native sample rate mismatch — 44100 Hz hardcoded**

The design hardcodes 44100 Hz for all audio capture (AudioPlaybackCapture and mic). Most modern Android devices have a native audio HAL rate of 48000 Hz. Requesting 44100 Hz forces a resampling pass in the audio stack on every such device, adding CPU overhead and introducing a minor but nonzero quality loss from the sample rate conversion.

Using `AudioRecord.getNativeInputSampleRate(MediaRecorder.AudioSource.DEFAULT)` or defaulting to 48000 Hz would be more performant and avoid the conversion entirely. This is not a correctness bug — the API handles the resampling transparently — but it is a suboptimal choice worth documenting as a conscious trade-off. Note: AOSP SystemUI's ScreenInternalAudioRecorder also hardcodes 44100 Hz, inheriting the same limitation.

---

**H. `CHANNEL_OUT_MONO` confirmed correct — no issue, but note for implementer**

The spec says: *"AudioFormat uses CHANNEL_OUT_MONO (playback channel, not CHANNEL_IN_MONO)"*

This is confirmed correct. AOSP `ScreenInternalAudioRecorder.java` uses `channelOutMask = AudioFormat.CHANNEL_OUT_MONO` for the `AudioPlaybackCaptureConfiguration`-backed AudioRecord, and `channelInMask = AudioFormat.CHANNEL_IN_MONO` for the mic AudioRecord. The spec is right. However, the implementer should note that `AudioRecord.getMinBufferSize()` should be called with `CHANNEL_IN_MONO` (the input mask) even when the AudioRecord itself uses `CHANNEL_OUT_MONO` — they both represent 1 channel so the buffer size is identical, but using `CHANNEL_IN_MONO` with `getMinBufferSize()` avoids any potential undefined behavior from passing an output mask to an input-oriented query function.

---

### Questions for the Author

**1. Merge failure fallback — what does the user get?**

What happens to the `IS_PENDING=1` MediaStore entry on merge failure? Should `temp_video.mp4` be promoted into it — copy the file contents to the pending MediaStore URI and set `IS_PENDING=0`? Should a separate new MediaStore entry be created for the video-only output? Should the `IS_PENDING=1` entry be deleted to avoid clutter? *"Keep temp_video as fallback recording, log error"* is not actionable as written: the file is in `cacheDir` and inaccessible to the user. This needs a concrete decision before implementation.

**2. A/V sync tolerance — accepted limitation or addressed?**

Is the 50–200ms A/V sync offset between MediaRecorder start and AudioRecord start accepted as a known design limitation of the post-hoc muxing approach? If yes, it should be explicitly documented in the design under Risks/Trade-offs. If no — if this must be within some tolerance (e.g., <50ms) — then what is the plan: record wall-clock timestamps at the moment each pipeline starts, compute the delta, and adjust the audio or video PTS during the merge step to compensate?

**3. PTS formula — confirm exact implementation**

Confirm the audio encoder input PTS formula is:
```kotlin
ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate
```
…using a running integer sample count that increments by the number of short samples fed per encoder call — not `System.nanoTime()` and not `SystemClock.elapsedRealtimeNanos()`. This must appear verbatim in the mediacodec-pipeline spec, not just as the phrase "sample-count-based PTS."

**4. AudioRecord buffer size — what value?**

What `bufferSizeInBytes` should be passed when constructing AudioRecord instances? `AudioRecord.getMinBufferSize() * 2` (AOSP's approach for the mic)? The AOSP config constant `1 << 17` (131072 bytes, used as a separate default in ScreenInternalAudioRecorder)? This value directly affects whether AudioRecord construction succeeds and whether audio frames are dropped. It must be specified in the spec, not left to the implementer's judgment.

**5. `VOICE_COMMUNICATION` claim — what is the evidence?**

What specific device(s), ROM version(s), or issue tracker were observed to block concurrent AudioPlaybackCapture capture when using `AudioSource.VOICE_COMMUNICATION`? Is this a documented OEM bug with a link, a StackOverflow report, or an untested assumption? AOSP's own ScreenInternalAudioRecorder uses `VOICE_COMMUNICATION` in production. If the evidence for the ROM-blocking behavior is thin, the safer path is to follow AOSP, use `VOICE_COMMUNICATION`, and add a try/catch fallback.

**6. Foreground service type in internal-only mode — intentional?**

Should the call to `startForeground()` conditionally exclude `FOREGROUND_SERVICE_TYPE_MICROPHONE` when audio mode is internal-only or when no mic AudioRecord is being created? This is a one-line conditional change with a visible user impact: the microphone privacy indicator in the status bar appears for the full duration of an internal-only recording, telling the user their mic is active when it is not. If this should be fixed, it needs to be a named task.

---

*End of validation report.*
