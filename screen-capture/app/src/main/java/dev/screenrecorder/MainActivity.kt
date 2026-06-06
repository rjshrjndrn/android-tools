package dev.screenrecorder

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var presetGroup: RadioGroup
    private lateinit var audioModeGroup: RadioGroup
    private lateinit var recordButton: MaterialButton
    private lateinit var timerText: TextView

    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private var recordingService: RecordingService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as RecordingService.LocalBinder).service
            recordingService = service
            isBound = true

            service.onStateChanged = { recording ->
                runOnUiThread { updateUI(recording) }
            }
            service.onElapsedChanged = { elapsedMs ->
                runOnUiThread { updateTimer(elapsedMs) }
            }

            updateUI(service.isCurrentlyRecording())
            if (service.isCurrentlyRecording()) {
                updateTimer(service.getElapsedMs())
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        presetGroup = findViewById(R.id.presetGroup)
        audioModeGroup = findViewById(R.id.audioModeGroup)
        recordButton = findViewById(R.id.recordButton)
        timerText = findViewById(R.id.timerText)

        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startRecordingService(result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
            if (audioGranted) {
                requestScreenCapture()
            } else {
                Toast.makeText(this, "Mic permission required for audio", Toast.LENGTH_SHORT).show()
            }
        }

        recordButton.setOnClickListener {
            if (recordingService?.isCurrentlyRecording() == true) {
                recordingService?.stopRecording()
            } else {
                checkPermissionsAndStart()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind without BIND_AUTO_CREATE — only connects if service
        // is already running (started via startForegroundService)
        Intent(this, RecordingService::class.java).also { intent ->
            bindService(intent, connection, 0)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            recordingService?.onStateChanged = null
            recordingService?.onElapsedChanged = null
            unbindService(connection)
            isBound = false
        }
    }

    private fun checkPermissionsAndStart() {
        val perms = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startRecordingService(resultCode: Int, resultData: Intent) {
        val configIndex = when (presetGroup.checkedRadioButtonId) {
            R.id.presetLow -> 0
            R.id.presetMedium -> 1
            R.id.presetHigh -> 2
            else -> 1
        }

        val audioMode = when (audioModeGroup.checkedRadioButtonId) {
            R.id.audioMic -> AudioMode.MIC_ONLY
            R.id.audioInternal -> AudioMode.INTERNAL_ONLY
            R.id.audioBoth -> AudioMode.BOTH
            else -> AudioMode.MIC_ONLY
        }

        val intent = RecordingService.startIntent(this, resultCode, resultData, configIndex, audioMode)
        startForegroundService(intent)

        // Now bind to get service reference for UI updates
        bindService(Intent(this, RecordingService::class.java), connection, 0)
    }

    private fun updateUI(recording: Boolean) {
        recordButton.text = if (recording) "Stop Recording" else "Start Recording"
        presetGroup.isEnabled = !recording
        for (i in 0 until presetGroup.childCount) {
            presetGroup.getChildAt(i).isEnabled = !recording
        }
        audioModeGroup.isEnabled = !recording
        for (i in 0 until audioModeGroup.childCount) {
            audioModeGroup.getChildAt(i).isEnabled = !recording
        }
        timerText.visibility = if (recording) View.VISIBLE else View.GONE
        if (!recording) {
            timerText.text = "00:00:00"
        }
    }

    private fun updateTimer(elapsedMs: Long) {
        val seconds = (elapsedMs / 1000) % 60
        val minutes = (elapsedMs / 60_000) % 60
        val hours = elapsedMs / 3_600_000
        timerText.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}
