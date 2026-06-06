# Validation Report

**Verdict: FAIL**

## Problems Found

### 1. 🔴 CRITICAL — Internal audio capture WILL NOT work for video calls

AudioPlaybackCapture can **only** capture audio with usage `USAGE_MEDIA`, `USAGE_GAME`, or `USAGE_UNKNOWN`. Video call apps (Google Meet, Zoom, WhatsApp, etc.) use `USAGE_VOICE_COMMUNICATION`, which is **explicitly excluded** from capture by non-system apps.

From Android docs:
> "the usage value MUST be USAGE_UNKNOWN or USAGE_GAME or USAGE_MEDIA. All other usages CAN NOT be captured."

The built-in screen recorder works because it's a **system app** with `CAPTURE_AUDIO_OUTPUT` privilege. A third-party app cannot replicate this. The entire primary use case — recording the other person's audio in a video call — is broken at the API level.

Evidence:
- [Stack Overflow confirmation](https://stackoverflow.com/questions/57883013/new-api-audioplaybackcaptureconfiguration-android-10-fails-to-record-voice-cal)
- [Android AudioPlaybackCapture docs](https://developer.android.com/media/platform/av-capture)

### 2. 🟡 Wrong API level — Android 16 is API 36, not 35

Proposal and tasks say "API 35". Android 16 (Baklava) is **API 36**. API 35 is Android 15. This propagates through all artifacts: proposal, design, tasks 1.1.

Evidence: [Android 16 setup docs](https://developer.android.com/about/versions/16/setup-sdk) — `targetSdk = 36`.

### 3. 🟡 Crash = total data loss is under-assessed

Design acknowledges the risk but calls it "acceptable." MediaMuxer writes moov atom only on `stop()`. A multi-hour recording lost to a process death, OOM kill, or ANR is the entire recording gone. `androidx.media3.muxer.FragmentedMp4Muxer` exists and would make partial recovery possible. The design dismisses this as "significant complexity" but it's a few extra lines with media3.

### 4. 🟡 Audio spec claims mono 196 kbps — suspect for mixed voice

196 kbps mono AAC is fine for single-source audio but may cause artifacts when mixing two voice streams. If internal audio from a video call is stereo, downmixing to mono before mixing with mic could lose spatial information.

## Missing Considerations

### 5. Video call apps in work profile

AudioPlaybackCapture requires both apps in same user profile. If video call runs in work profile, capture fails silently.

### 6. Android 14+ single-use MediaProjection token

The consent dialog shows "entire screen" vs "single app" choice. If user picks single-app mode and selects the video call app, recording only captures that app. Design doesn't address this choice or use `createConfigForDefaultDisplay()` to force full-screen capture.

### 7. No `MediaProjection.Callback.onStop()` handling

Android 14+ mandates registering a `Callback` before `createVirtualDisplay()` and implementing `onStop()` for cleanup. The specs/tasks don't mention this. Without it: `IllegalStateException` on API 34+.

### 8. `POST_NOTIFICATIONS` runtime permission

Task 7.4 mentions it, but it's a runtime permission on Android 13+. If denied, the foreground service notification won't show, and on some OEMs the service gets killed. Needs a clear handling path.

### 9. Storage permissions unaddressed

No mention of where files are saved or what permissions are needed. Android 10+ scoped storage means you need `MediaStore` or app-specific directory. `WRITE_EXTERNAL_STORAGE` doesn't work on API 29+.

## Questions for the Author

1. **Are you aware that AudioPlaybackCapture cannot capture video call audio?** The built-in recorder can because it's a system app. Your app cannot replicate this. The only workaround is capturing via mic (speaker mode) — which defeats the dual-audio design.

2. **Would mic-only capture (your voice + speakerphone bleed) be acceptable** as a fallback? This drastically simplifies the architecture (no dual AudioRecord, no mixing) but quality is worse.

3. **Do you use the video call app in the same profile or a work profile?** Cross-profile capture is blocked.
