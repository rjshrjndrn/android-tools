package dev.screenrecorder

import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder

data class RecordingConfig(
    val label: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val fps: Int,
    val videoEncoder: Int,
    val sizePerHour: String,
) {
    companion object {
        private fun hasHevcEncoder(): Boolean {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            return codecList.codecInfos.any { info ->
                !info.isEncoder.not() &&
                    info.isEncoder &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
            }
        }

        private val useHevc = hasHevcEncoder()

        private val videoEncoder: Int
            get() = if (useHevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264

        val LOW = RecordingConfig(
            label = "Low",
            width = 854,
            height = 480,
            bitrate = 1_000_000,
            fps = 24,
            videoEncoder = videoEncoder,
            sizePerHour = "~0.4 GB/hr",
        )

        val MEDIUM = RecordingConfig(
            label = "Medium",
            width = 1280,
            height = 720,
            bitrate = 3_000_000,
            fps = 30,
            videoEncoder = videoEncoder,
            sizePerHour = "~1.2 GB/hr",
        )

        val HIGH = RecordingConfig(
            label = "High",
            width = 1920,
            height = 1080,
            bitrate = 6_000_000,
            fps = 30,
            videoEncoder = videoEncoder,
            sizePerHour = "~2.5 GB/hr",
        )

        val PRESETS = listOf(LOW, MEDIUM, HIGH)
        val DEFAULT = MEDIUM
    }
}
