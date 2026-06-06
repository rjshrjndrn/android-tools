package dev.screenrecorder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Merges separate video and audio temp files into a single MP4.
 * Follows AOSP ScreenRecordingMuxer pattern with post-hoc track copying.
 */
object ScreenRecordingMuxer {

    private const val TAG = "ScreenRecordingMuxer"
    private const val BUFFER_SIZE = 1024 * 1024 // 1MB read buffer

    data class MergeResult(
        val success: Boolean,
        val outputFile: File? = null,
        val error: String? = null,
    )

    /**
     * Merge video and audio temp files into a single MP4.
     *
     * @param tempVideo Video file from MediaRecorder
     * @param tempAudio Audio file from InternalAudioRecorder (may be null/empty)
     * @param outputFile Target file for merged output (temp_merged.mp4 in cacheDir)
     * @param isCancelled Volatile flag checked periodically for graceful shutdown
     * @return MergeResult indicating success/failure
     */
    fun merge(
        tempVideo: File,
        tempAudio: File?,
        outputFile: File,
        isCancelled: () -> Boolean = { false },
    ): MergeResult {
        // Task 5.0: Validate temp files before merge
        if (!tempVideo.exists() || tempVideo.length() == 0L) {
            Log.e(TAG, "temp_video missing or empty: ${tempVideo.absolutePath}")
            return MergeResult(false, error = "Video file missing or empty")
        }

        val hasAudio = tempAudio != null && tempAudio.exists() && tempAudio.length() > 0

        if (!hasAudio) {
            // No audio to merge — just copy video directly
            Log.d(TAG, "No audio track, copying video directly")
            return try {
                copyFile(tempVideo, outputFile)
                MergeResult(true, outputFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy video", e)
                MergeResult(false, error = "Failed to copy video: ${e.message}")
            }
        }

        var muxer: MediaMuxer? = null
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null

        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Task 5.1a: Preserve rotation metadata
            val rotation = getVideoRotation(tempVideo)
            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
            }

            // Task 5.1: Extract tracks by MIME prefix, NOT by index
            videoExtractor = MediaExtractor().apply { setDataSource(tempVideo.absolutePath) }
            audioExtractor = MediaExtractor().apply { setDataSource(tempAudio!!.absolutePath) }

            // Find and add video track
            val videoTrackSrc = findTrackByMime(videoExtractor, "video/")
            if (videoTrackSrc < 0) {
                return MergeResult(false, error = "No video track found in temp_video")
            }
            // Task 5.1: selectTrack() required — without it readSampleData() returns -1
            videoExtractor.selectTrack(videoTrackSrc)
            val videoTrackDst = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackSrc))

            // Find and add audio track
            val audioTrackSrc = findTrackByMime(audioExtractor, "audio/")
            if (audioTrackSrc < 0) {
                Log.w(TAG, "No audio track found in temp_audio, copying video only")
                audioExtractor.release()
                audioExtractor = null
            }
            val audioTrackDst = if (audioTrackSrc >= 0) {
                audioExtractor!!.selectTrack(audioTrackSrc)
                muxer.addTrack(audioExtractor.getTrackFormat(audioTrackSrc))
            } else {
                -1
            }

            muxer.start()

            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            // Copy video track
            copyTrack(videoExtractor, muxer, videoTrackDst, buffer, bufferInfo, isCancelled)

            if (isCancelled()) {
                Log.w(TAG, "Merge cancelled during video copy")
                return MergeResult(false, error = "Merge cancelled")
            }

            // Copy audio track
            if (audioExtractor != null && audioTrackDst >= 0) {
                copyTrack(audioExtractor, muxer, audioTrackDst, buffer, bufferInfo, isCancelled)
            }

            if (isCancelled()) {
                Log.w(TAG, "Merge cancelled during audio copy")
                return MergeResult(false, error = "Merge cancelled")
            }

            muxer.stop()
            Log.d(TAG, "Merge complete: ${outputFile.absolutePath}")
            return MergeResult(true, outputFile)

        } catch (e: Exception) {
            Log.e(TAG, "Merge failed", e)
            return MergeResult(false, error = "Merge failed: ${e.message}")
        } finally {
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Copy samples from extractor to muxer for a given track.
     * Normalizes PTS to start at 0 (MediaRecorder internal clock ≠ 0).
     * Task 5.2: Skip CODEC_CONFIG, preserve SAMPLE_FLAG_SYNC → BUFFER_FLAG_KEY_FRAME
     */
    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndex: Int,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        isCancelled: () -> Boolean,
    ) {
        var firstPts = Long.MIN_VALUE

        while (!isCancelled()) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            val sampleFlags = extractor.sampleFlags

            // Task 5.2: Skip BUFFER_FLAG_CODEC_CONFIG samples
            if (sampleFlags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                extractor.advance()
                continue
            }

            // Normalize PTS: subtract first sample time so track starts at 0
            val rawPts = extractor.sampleTime
            if (firstPts == Long.MIN_VALUE) firstPts = rawPts
            val normalizedPts = rawPts - firstPts

            // Task 5.2: Preserve SAMPLE_FLAG_SYNC → BUFFER_FLAG_KEY_FRAME
            var flags = 0
            if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            }

            bufferInfo.set(0, sampleSize, normalizedPts, flags)
            muxer.writeSampleData(trackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }

    /**
     * Find track index by MIME prefix (e.g., "video/" or "audio/").
     * Task 5.1: Use MIME prefix, NOT index.
     */
    private fun findTrackByMime(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    /**
     * Task 5.1a: Read rotation from video file.
     */
    private fun getVideoRotation(videoFile: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )
            rotation?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read rotation metadata", e)
            0
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Copy file contents.
     */
    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.channel.transferTo(0, input.channel.size(), output.channel)
            }
        }
    }

    /**
     * Task 5.5: Copy merged output to a content URI (MediaStore).
     */
    fun copyToUri(
        context: android.content.Context,
        sourceFile: File,
        destUri: android.net.Uri,
    ) {
        context.contentResolver.openOutputStream(destUri)?.use { output ->
            FileInputStream(sourceFile).use { input ->
                input.copyTo(output, bufferSize = 8192)
            }
        } ?: throw IllegalStateException("Failed to open output stream for $destUri")
    }
}
