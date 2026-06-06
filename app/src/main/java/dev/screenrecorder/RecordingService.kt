package dev.screenrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import android.provider.MediaStore
import android.util.Log
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

        // Store projection result data here — Intent extras can lose
        // nested Parcelable Intent on newer Android versions
        private var pendingResultData: Intent? = null

        fun startIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            configIndex: Int,
        ): Intent {
            pendingResultData = resultData
            return Intent(context, RecordingService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_CONFIG_INDEX, configIndex)
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputPfd: android.os.ParcelFileDescriptor? = null
    private var isRecording = false
    private var startTimeMs = 0L

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

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = pendingResultData
        val configIndex = intent?.getIntExtra(EXTRA_CONFIG_INDEX, 1) ?: 1
        pendingResultData = null

        if (resultCode == -1 || resultData == null) {
            Log.e(TAG, "Missing MediaProjection result: code=$resultCode data=$resultData")
            stopSelf()
            return START_NOT_STICKY
        }

        val config = RecordingConfig.PRESETS.getOrElse(configIndex) { RecordingConfig.DEFAULT }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        startRecording(resultCode, resultData, config)

        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, resultData: Intent, config: RecordingConfig) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system")
                stopRecording()
            }
        }, handler)

        // Swap dimensions for portrait orientation
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val vidWidth = if (isPortrait) minOf(config.width, config.height) else maxOf(config.width, config.height)
        val vidHeight = if (isPortrait) maxOf(config.width, config.height) else minOf(config.width, config.height)

        val (uri, pfd) = createOutputFile()
        outputUri = uri
        outputPfd = pfd

        try {
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
                vidWidth,
                vidHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                handler,
            )

            recorder.start()
            isRecording = true
            startTimeMs = System.currentTimeMillis()
            onStateChanged?.invoke(true)
            handler.post(elapsedRunnable)

            Log.d(TAG, "Recording started: ${config.label} → $outputUri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanup()
            stopSelf()
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        handler.removeCallbacks(elapsedRunnable)

        try {
            mediaRecorder?.stop()
            // Mark MediaStore entry as complete
            outputUri?.let { uri ->
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
            }
            Log.d(TAG, "Recording saved: $outputUri")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
            // Clean up failed recording from MediaStore
            outputUri?.let { contentResolver.delete(it, null, null) }
        }

        cleanup()
        onStateChanged?.invoke(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        mediaRecorder?.release()
        mediaRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        try { outputPfd?.close() } catch (_: Exception) {}
        outputPfd = null
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

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording screen")
            .setContentText("Tap Stop to finish recording")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop", stopPending
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
