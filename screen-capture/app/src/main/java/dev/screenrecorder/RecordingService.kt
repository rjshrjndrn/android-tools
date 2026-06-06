package dev.screenrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Binder
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "screen_recorder_channel"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.screenrecorder.STOP"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_CONFIG_INDEX = "config_index"
        private const val EXTRA_AUDIO_MODE = "audio_mode"

        // Store projection result data here — Intent extras can lose
        // nested Parcelable Intent on newer Android versions
        private var pendingResultData: Intent? = null

        fun startIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            configIndex: Int,
            audioMode: AudioMode = AudioMode.MIC_ONLY,
        ): Intent {
            pendingResultData = resultData
            return Intent(context, RecordingService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_CONFIG_INDEX, configIndex)
                putExtra(EXTRA_AUDIO_MODE, audioMode.name)
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var internalAudioRecorder: InternalAudioRecorder? = null
    private var outputUri: Uri? = null
    private var outputPfd: android.os.ParcelFileDescriptor? = null
    private var isRecording = false
    private var startTimeMs = 0L
    private var currentAudioMode = AudioMode.MIC_ONLY

    // Temp files for post-hoc merge (internal/both modes)
    private var tempVideoFile: File? = null
    private var tempAudioFile: File? = null

    // Merge thread control
    private var mergeThread: Thread? = null
    @Volatile
    private var isMergeCancelled = false

    private val handler = Handler(Looper.getMainLooper())

    var onStateChanged: ((Boolean) -> Unit)? = null
    var onElapsedChanged: ((Long) -> Unit)? = null

    private val elapsedRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                onElapsedChanged?.invoke(System.currentTimeMillis() - startTimeMs)
                handler.postDelayed(this, 1000)
            }
        }
    }

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Task 6.7: Delete stale temp files from previous interrupted recordings
        cleanStaleTempFiles()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }

        if (isRecording) {
            Log.w(TAG, "Already recording, ignoring start request")
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = pendingResultData
        val configIndex = intent?.getIntExtra(EXTRA_CONFIG_INDEX, 1) ?: 1
        pendingResultData = null

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "Missing MediaProjection result: code=$resultCode data=$resultData")
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Error starting recording"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val config = RecordingConfig.PRESETS.getOrElse(configIndex) { RecordingConfig.DEFAULT }
        val audioMode = try {
            AudioMode.valueOf(intent?.getStringExtra(EXTRA_AUDIO_MODE) ?: AudioMode.MIC_ONLY.name)
        } catch (_: IllegalArgumentException) {
            AudioMode.MIC_ONLY
        }

        // Task 6.8: startForeground bitmask conditional on audio mode
        val serviceType = when (audioMode) {
            AudioMode.INTERNAL_ONLY -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            AudioMode.MIC_ONLY, AudioMode.BOTH ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Recording screen"),
            serviceType,
        )
        startRecording(resultCode, resultData, config, audioMode)

        return START_NOT_STICKY
    }

    private fun startRecording(
        resultCode: Int,
        resultData: Intent,
        config: RecordingConfig,
        audioMode: AudioMode,
    ) {
        currentAudioMode = audioMode

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system")
                // Task 6.6: Handle MediaProjection revocation mid-recording
                handler.post { stopRecording() }
            }
        }, handler)

        // Swap dimensions for portrait orientation
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val vidWidth = if (isPortrait) minOf(config.width, config.height) else maxOf(config.width, config.height)
        val vidHeight = if (isPortrait) maxOf(config.width, config.height) else minOf(config.width, config.height)

        val effectiveMode = if (audioMode == AudioMode.MIC_ONLY) {
            AudioMode.MIC_ONLY
        } else {
            // Task 6.2: Check cacheDir available space for internal/both modes
            val estimatedBytes = (config.bitrate.toLong() * 1800 / 8) + (196_000L * 1800 / 8)
            val required = (estimatedBytes * 1.1).toLong()
            if (cacheDir.usableSpace < required) {
                Log.e(TAG, "Insufficient storage: need $required, have ${cacheDir.usableSpace}")
                handler.post {
                    Toast.makeText(this, "Not enough storage space", Toast.LENGTH_LONG).show()
                }
                cleanup()
                stopSelf()
                return
            }
            audioMode
        }

        try {
            if (effectiveMode == AudioMode.MIC_ONLY) {
                // Task 6.1: Mic-only mode — current path, MediaRecorder → MediaStore directly
                startMicOnlyRecording(config, vidWidth, vidHeight)
            } else {
                // Internal/both modes: MediaRecorder → temp file, audio → separate temp file
                startDualRecording(config, vidWidth, vidHeight, effectiveMode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanup()
            stopSelf()
        }
    }

    // Task 6.1: Mic-only — identical to original behavior
    private fun startMicOnlyRecording(config: RecordingConfig, vidWidth: Int, vidHeight: Int) {
        val (uri, pfd) = createOutputFile()
        outputUri = uri
        outputPfd = pfd

        val recorder = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(config.videoEncoder)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(vidWidth, vidHeight)
            setVideoFrameRate(config.fps)
            setVideoEncodingBitRate(config.bitrate)
            setAudioEncodingBitRate(196_000)
            setAudioSamplingRate(44100)
            setOutputFile(pfd.fileDescriptor)
            prepare()
        }
        mediaRecorder = recorder

        val metrics = resources.displayMetrics
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecorder",
            vidWidth, vidHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.surface, null, handler,
        )

        recorder.start()
        onRecordingStarted(config)
    }

    // Task 6.2: Internal/both — MediaRecorder to temp file, audio capture separately
    private fun startDualRecording(
        config: RecordingConfig,
        vidWidth: Int,
        vidHeight: Int,
        audioMode: AudioMode,
    ) {
        // Create MediaStore entry (IS_PENDING=1) — will be finalized after merge
        val (uri, pfd) = createOutputFile()
        outputUri = uri
        outputPfd = pfd

        // Temp files in cache dir
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tempVideo = File(cacheDir, "temp_video_$timestamp.mp4")
        val tempAudio = File(cacheDir, "temp_audio_$timestamp.m4a")
        tempVideoFile = tempVideo
        tempAudioFile = tempAudio

        // Setup internal audio recorder
        val projection = mediaProjection
            ?: throw IllegalStateException("MediaProjection is null")

        val audioRecorder = InternalAudioRecorder(projection, audioMode, tempAudio)
        val setupOk = audioRecorder.setup()

        if (!setupOk) {
            // Fallback to mic-only — show Toast
            handler.post {
                Toast.makeText(this, audioRecorder.fallbackReason ?: "Internal audio unavailable", Toast.LENGTH_LONG).show()
            }
            // Fall back: close temp files, use mic-only path
            tempVideoFile = null
            tempAudioFile = null
            currentAudioMode = AudioMode.MIC_ONLY
            startMicOnlyRecording(config, vidWidth, vidHeight)
            return
        }

        internalAudioRecorder = audioRecorder
        currentAudioMode = audioRecorder.effectiveMode

        // Task 3.1/6.2: MediaRecorder records VIDEO ONLY (no audio source) → temp file
        val recorder = MediaRecorder(this).apply {
            if (currentAudioMode == AudioMode.MIC_ONLY) {
                // Shouldn't happen here, but safety
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(config.videoEncoder)
            if (currentAudioMode == AudioMode.MIC_ONLY) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(196_000)
                setAudioSamplingRate(44100)
            }
            setVideoSize(vidWidth, vidHeight)
            setVideoFrameRate(config.fps)
            setVideoEncodingBitRate(config.bitrate)
            // Task 6.2: setOutputFile(path), NOT FileDescriptor for temp files
            setOutputFile(tempVideo.absolutePath)
            prepare()
        }
        mediaRecorder = recorder

        val metrics = resources.displayMetrics
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecorder",
            vidWidth, vidHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.surface, null, handler,
        )

        recorder.start()
        audioRecorder.start()
        onRecordingStarted(config)
    }

    private fun onRecordingStarted(config: RecordingConfig) {
        isRecording = true
        startTimeMs = System.currentTimeMillis()
        onStateChanged?.invoke(true)
        handler.post(elapsedRunnable)
        Log.d(TAG, "Recording started: ${config.label} mode=$currentAudioMode → $outputUri")
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        handler.removeCallbacks(elapsedRunnable)

        // Stop audio capture first
        internalAudioRecorder?.stop()

        if (currentAudioMode == AudioMode.MIC_ONLY || tempVideoFile == null) {
            // Task 6.1: Mic-only — direct finalization
            stopMicOnlyRecording()
        } else {
            // Task 6.3: Internal/both — stop MediaRecorder, then merge on background thread
            stopDualRecording()
        }
    }

    private fun stopMicOnlyRecording() {
        try {
            mediaRecorder?.stop()
            outputUri?.let { uri ->
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
            }
            Log.d(TAG, "Recording saved: $outputUri")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
            // Task 6.6: Clean up failed recording from MediaStore
            outputUri?.let { contentResolver.delete(it, null, null) }
        }

        cleanup()
        onStateChanged?.invoke(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopDualRecording() {
        // Stop MediaRecorder
        var mediaRecorderFailed = false
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder.stop() failed (possibly no frames encoded)", e)
            mediaRecorderFailed = true
        }
        mediaRecorder?.release()
        mediaRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null

        // Task 6.4: Update notification to "Finishing recording…"
        updateNotification("Finishing recording…")

        onStateChanged?.invoke(false)

        val uri = outputUri ?: run {
            Log.e(TAG, "No output URI for merge")
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val tempVideo = tempVideoFile
        val tempAudio = tempAudioFile
        val pfd = outputPfd

        isMergeCancelled = false

        // Task 6.3: Merge on BACKGROUND THREAD with WakeLock
        mergeThread = Thread {
            val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "screenrecorder:merge")
            wakeLock.acquire(60_000) // 60s max

            try {
                if (mediaRecorderFailed || tempVideo == null || !tempVideo.exists() || tempVideo.length() == 0L) {
                    // Task 6.6: MediaRecorder failed — try to salvage
                    handleMergeFailure(uri, tempVideo, tempAudio, pfd)
                    return@Thread
                }

                val tempMerged = File(cacheDir, "temp_merged_${System.currentTimeMillis()}.mp4")

                // Task 5.5: Write to temp_merged, then copy to MediaStore
                val result = ScreenRecordingMuxer.merge(
                    tempVideo = tempVideo,
                    tempAudio = if (tempAudio?.exists() == true && tempAudio.length() > 0L) tempAudio else null,
                    outputFile = tempMerged,
                    isCancelled = { isMergeCancelled },
                )

                if (result.success && result.outputFile != null) {
                    // Copy merged file to MediaStore URI
                    ScreenRecordingMuxer.copyToUri(this@RecordingService, result.outputFile, uri)
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, values, null, null)
                    Log.d(TAG, "Merge complete, recording saved: $uri")
                } else {
                    // Task 5.7: Merge failed — fallback to video-only
                    Log.e(TAG, "Merge failed: ${result.error}")
                    handleMergeFailure(uri, tempVideo, tempAudio, pfd)
                }

                // Task 5.6: Clean up temp files
                tempVideo.delete()
                tempAudio?.delete()
                tempMerged.delete()

            } catch (e: Exception) {
                Log.e(TAG, "Merge thread error", e)
                handleMergeFailure(uri, tempVideo, tempAudio, pfd)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()

                // Task 6.5: stopForeground + stopSelf only after merge thread exits
                handler.post {
                    try { pfd?.close() } catch (_: Exception) {}
                    outputPfd = null
                    mediaProjection?.stop()
                    mediaProjection = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }.apply {
            name = "MergeThread"
            start()
        }
    }

    // Task 5.7/6.6: Merge failure fallback — save video-only recording
    private fun handleMergeFailure(
        uri: Uri,
        tempVideo: File?,
        tempAudio: File?,
        pfd: android.os.ParcelFileDescriptor?,
    ) {
        try {
            if (tempVideo != null && tempVideo.exists() && tempVideo.length() > 0) {
                // Copy video-only to MediaStore
                ScreenRecordingMuxer.copyToUri(this, tempVideo, uri)
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
                Log.w(TAG, "Saved video-only recording (merge failed): $uri")
            } else {
                // Task 6.6: No valid video — delete IS_PENDING entry
                contentResolver.delete(uri, null, null)
                Log.e(TAG, "No valid recording to save, deleted MediaStore entry")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle merge failure", e)
            // Last resort: ensure IS_PENDING doesn't stay 1
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {}
        }

        // Clean up temp files
        tempVideo?.delete()
        tempAudio?.delete()
    }

    private fun cleanup() {
        mediaRecorder?.release()
        mediaRecorder = null
        internalAudioRecorder?.stop()
        internalAudioRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        try { outputPfd?.close() } catch (_: Exception) {}
        outputPfd = null
        tempVideoFile = null
        tempAudioFile = null
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    fun getElapsedMs(): Long {
        return if (isRecording) System.currentTimeMillis() - startTimeMs else 0
    }

    private fun createOutputFile(): Pair<Uri, android.os.ParcelFileDescriptor> {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "REC_$timestamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenRecorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create MediaStore entry")
        val pfd = contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Failed to open file descriptor")
        return Pair(uri, pfd)
    }

    // Task 6.7: Delete stale temp files from previous interrupted recordings
    private fun cleanStaleTempFiles() {
        cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("temp_video") && file.name.endsWith(".mp4") ||
                file.name.startsWith("temp_audio") && file.name.endsWith(".m4a") ||
                file.name.startsWith("temp_merged") && file.name.endsWith(".mp4")
            ) {
                Log.d(TAG, "Deleting stale temp file: ${file.name}")
                file.delete()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows while screen recording is active"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String = "Tap Stop to finish recording"): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording screen")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop", stopPending
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    // Task 6.4: Update notification text (e.g., "Finishing recording…")
    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        // Task 6.4: Service onDestroy sets isCancelled, merge thread flushes gracefully
        isMergeCancelled = true
        stopRecording()
        super.onDestroy()
    }
}
