# Validation Report — screen-capture-app

## Verdict: RISKY

### Problems Found

**1. ParcelFileDescriptor leak — never closed**

`createOutputFile()` opens `ParcelFileDescriptor` via `contentResolver.openFileDescriptor(uri, "rw")` but only extracts `.fileDescriptor` and returns it. The `ParcelFileDescriptor` wrapper is never stored or closed — leaks a native file descriptor.

```kotlin
// Leaks: fd (ParcelFileDescriptor) never closed
val fd = contentResolver.openFileDescriptor(uri, "rw")
return Pair(uri, fd.fileDescriptor)
```

**2. No FOREGROUND_SERVICE_MICROPHONE type declared**

Service uses `AudioSource.MIC` but manifest only declares `foregroundServiceType="mediaProjection"`. Android 14+ requires `FOREGROUND_SERVICE_TYPE_MICROPHONE` alongside `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` when using mic in a foreground service. May silently fail or throw `SecurityException` on GrapheneOS.

**3. BIND_AUTO_CREATE race with startForegroundService**

`onStart()` calls `bindService()` with `BIND_AUTO_CREATE`, creating service before `startForegroundService` is called. Service exists with no foreground notification until user completes permission flow. On Android 14+, foreground service must call `startForeground()` within timeout.

**4. Double-negation logic bug in hasHevcEncoder()**

```kotlin
!info.isEncoder.not() &&
    info.isEncoder &&
```

`!info.isEncoder.not()` ≡ `info.isEncoder`. Redundant — not a runtime bug but confusing dead code suggesting copy-paste error.

**5. stopRecording() in onDestroy() can crash**

`stopRecording()` calls `contentResolver.update()` / `contentResolver.delete()`. In `onDestroy()`, service context may be partially torn down, making ContentResolver calls unreliable.

**6. No guard against double start**

Tapping Start while already recording sends new `onStartCommand`. No `if (isRecording) return` guard — would attempt second recording on same service instance.

**7. Portrait screen not handled**

Presets assume landscape (854×480, 1280×720, 1920×1080). Pixel 9 Pro is portrait (1080×2340). VirtualDisplay will letterbox or distort. Should swap width/height based on orientation.

### Missing Considerations

- **No WakeLock** — CPU may sleep during long recordings with screen off
- **No error feedback** — if `startRecording()` fails, service silently stops, no Toast
- **Crash = corrupt file** — acknowledged in design but no mitigation
- **No second-recording guard** in `onStartCommand`

### Questions for the Author

1. Tested on GrapheneOS? May restrict MediaProjection or AudioSource.MIC differently
2. Want portrait recordings at preset resolution, or swap width/height by orientation?
3. Is ParcelFileDescriptor leak acceptable for personal app?
