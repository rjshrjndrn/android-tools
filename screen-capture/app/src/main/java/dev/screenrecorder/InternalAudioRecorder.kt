package dev.screenrecorder

import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Captures internal audio (and optionally mic) via AudioPlaybackCapture API,
 * encodes to AAC, and writes to a temp .m4a file for post-hoc muxing.
 *
 * Follows AOSP ScreenInternalAudioRecorder pattern.
 */
class InternalAudioRecorder(
    private val mediaProjection: MediaProjection,
    private val audioMode: AudioMode,
    private val tempAudioFile: File,
) {
    companion object {
        private const val TAG = "InternalAudioRecorder"
        private const val SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 196_000
        private const val MIC_VOLUME_SCALE = 1.4f
    }

    private var internalAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var audioThread: Thread? = null

    @Volatile
    private var isCapturing = false
    private var muxerStarted = false
    private var audioTrackIndex = -1
    private var totalSamplesWritten = 0L

    /** Actual mode after fallback (may differ from requested if internal capture fails) */
    var effectiveMode: AudioMode = audioMode
        private set

    /** Error message if fallback occurred */
    var fallbackReason: String? = null
        private set

    /**
     * Create AudioRecord instances and encoder. Call on main thread.
     * Returns true if setup succeeded, false if fallback to mic-only.
     */
    fun setup(): Boolean {
        if (audioMode == AudioMode.MIC_ONLY) return true // nothing to do

        // Task 2.1: Create AudioPlaybackCaptureConfiguration
        val internalRecord = try {
            createInternalAudioRecord()
        } catch (e: Exception) {
            // Task 2.3: Catch Exception (not just UnsupportedOperationException)
            // Vendor ROMs throw RuntimeException: registerAudioPolicy() returned -1
            Log.e(TAG, "AudioPlaybackCapture creation failed, falling back to mic-only", e)
            effectiveMode = AudioMode.MIC_ONLY
            fallbackReason = "Internal audio not available on this device"
            return false
        }

        // Task 2.4: Verify recording state after startRecording()
        try {
            internalRecord.startRecording()
            if (internalRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord.startRecording() silently failed (vendor ROM issue)")
                internalRecord.release()
                effectiveMode = AudioMode.MIC_ONLY
                fallbackReason = "Internal audio capture failed to start"
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord.startRecording() threw", e)
            internalRecord.release()
            effectiveMode = AudioMode.MIC_ONLY
            fallbackReason = "Internal audio capture failed to start"
            return false
        }

        internalAudioRecord = internalRecord

        // Task 3.2: Create mic AudioRecord for both mode
        if (audioMode == AudioMode.BOTH) {
            try {
                val micRecord = createMicAudioRecord()
                micRecord.startRecording()
                if (micRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.w(TAG, "Mic AudioRecord failed to start, continuing with internal only")
                    micRecord.release()
                    effectiveMode = AudioMode.INTERNAL_ONLY
                } else {
                    micAudioRecord = micRecord
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mic AudioRecord creation failed, continuing with internal only", e)
                effectiveMode = AudioMode.INTERNAL_ONLY
            }
        }

        // Task 4.1-4.2: Create encoder and muxer
        setupEncoder()
        setupMuxer()

        return true
    }

    /**
     * Start audio capture thread. Call after setup().
     */
    fun start() {
        if (audioMode == AudioMode.MIC_ONLY || effectiveMode == AudioMode.MIC_ONLY) return

        isCapturing = true
        // Task 2.5: Dedicated audio capture thread
        audioThread = Thread({
            captureLoop()
        }, "AudioCaptureThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Stop audio capture and encoding. Blocks until thread finishes.
     */
    fun stop() {
        isCapturing = false
        audioThread?.join(5000)
        audioThread = null

        internalAudioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        internalAudioRecord = null

        micAudioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        micAudioRecord = null

        encoder?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        encoder = null

        if (muxerStarted) {
            try { muxer?.stop() } catch (_: Exception) {}
        }
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        muxerStarted = false
    }

    // Task 2.1-2.2: Create AudioRecord with AudioPlaybackCaptureConfiguration
    private fun createInternalAudioRecord(): AudioRecord {
        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()

        // Task 2.2: AudioFormat uses CHANNEL_OUT_MONO (playback channel)
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        // Task 2.2: Buffer size = getMinBufferSize() * 2
        // Use CHANNEL_IN_MONO for getMinBufferSize (both = 1 channel, identical result)
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        // Task 2.2: MUST use setBufferSizeInBytes on Builder — omitting defaults to 1 frame
        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    // Task 3.2: Create mic AudioRecord (AudioSource.MIC, NOT VOICE_COMMUNICATION)
    private fun createMicAudioRecord(): AudioRecord {
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    // Task 4.1: Create MediaCodec AAC encoder
    private fun setupEncoder() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            // KEY_AAC_PROFILE required — hardware encoders fail without it
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            // KEY_PCM_ENCODING required — some API 29-31 devices default to FLOAT
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    // Task 4.2: Create MediaMuxer for temp audio file
    private fun setupMuxer() {
        muxer = MediaMuxer(
            tempAudioFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
    }

    /**
     * Main capture loop running on audio thread.
     * Reads PCM from AudioRecord(s), mixes if both mode, encodes to AAC.
     */
    private fun captureLoop() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val internalBuffer = ShortArray(bufferSize)
        val micBuffer = if (effectiveMode == AudioMode.BOTH) ShortArray(bufferSize) else null
        val mixedBuffer = ShortArray(bufferSize)

        // Offset tracking for unequal reads
        var internalOffset = 0
        var micOffset = 0

        try {
            while (isCapturing) {
                val internalRead = internalAudioRecord?.read(
                    internalBuffer, internalOffset, bufferSize - internalOffset
                ) ?: AudioRecord.ERROR

                val micRead = if (effectiveMode == AudioMode.BOTH && micBuffer != null) {
                    micAudioRecord?.read(
                        micBuffer, micOffset, bufferSize - micOffset
                    ) ?: AudioRecord.ERROR
                } else {
                    0
                }

                if (effectiveMode == AudioMode.BOTH && micBuffer != null) {
                    // Task 3.5: Both sources error → break loop
                    val internalError = internalRead < 0
                    val micError = micRead < 0

                    if (internalError && micError) {
                        Log.e(TAG, "Both audio sources errored, ending capture")
                        break
                    }

                    val internalTotal = if (internalError) internalOffset else internalOffset + internalRead
                    val micTotal = if (micError) micOffset else micOffset + micRead

                    // Task 3.5: Fill errored source with zeros
                    if (internalError) {
                        internalBuffer.fill(0, internalOffset, internalOffset + (micTotal - micOffset).coerceAtLeast(0))
                    }
                    if (micError) {
                        micBuffer.fill(0, micOffset, micOffset + (internalTotal - internalOffset).coerceAtLeast(0))
                    }

                    // Task 3.3: Handle unequal read sizes
                    val minSamples = minOf(
                        if (internalError) internalTotal else internalTotal,
                        if (micError) micTotal else micTotal
                    )

                    if (minSamples > 0) {
                        // Task 3.4: Mix PCM — scale mic 1.4x, add, clamp
                        for (i in 0 until minSamples) {
                            val mixed = internalBuffer[i].toInt() + (micBuffer[i] * MIC_VOLUME_SCALE).toInt()
                            mixedBuffer[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }

                        feedEncoder(mixedBuffer, minSamples)

                        // Task 3.3: shiftToStart with correct params (AOSP bug fix)
                        if (internalTotal > minSamples) {
                            System.arraycopy(internalBuffer, minSamples, internalBuffer, 0, internalTotal - minSamples)
                            internalOffset = internalTotal - minSamples
                        } else {
                            internalOffset = 0
                        }
                        if (micTotal > minSamples) {
                            System.arraycopy(micBuffer, minSamples, micBuffer, 0, micTotal - minSamples)
                            micOffset = micTotal - minSamples
                        } else {
                            micOffset = 0
                        }
                    }
                } else {
                    // Internal-only mode
                    if (internalRead < 0) {
                        Log.e(TAG, "Internal AudioRecord read error: $internalRead")
                        break
                    }
                    if (internalRead > 0) {
                        feedEncoder(internalBuffer, internalRead)
                    }
                }

                // Drain encoder output
                drainEncoder(false)
            }

            // Task 4.5: Signal EOS and drain
            signalEndOfStream()
            drainEncoder(true)

        } catch (e: Exception) {
            Log.e(TAG, "Audio capture loop error", e)
        }

        Log.d(TAG, "Audio capture loop ended, totalSamples=$totalSamplesWritten")
    }

    // Task 4.4: Feed PCM to encoder input buffers
    private fun feedEncoder(samples: ShortArray, count: Int) {
        val codec = encoder ?: return

        // Convert shorts to bytes
        val byteBuffer = ByteBuffer.allocate(count * 2)
        for (i in 0 until count) {
            byteBuffer.putShort(samples[i])
        }
        byteBuffer.flip()

        var bytesRemaining = byteBuffer.remaining()
        while (bytesRemaining > 0) {
            // Task 4.4: 10ms timeout with retry (AOSP's 500µs drops PCM under load)
            val bufferIndex = codec.dequeueInputBuffer(10_000)
            if (bufferIndex < 0) continue // retry

            val inputBuffer = codec.getInputBuffer(bufferIndex) ?: continue
            val bytesToWrite = minOf(bytesRemaining, inputBuffer.remaining())
            val oldLimit = byteBuffer.limit()
            byteBuffer.limit(byteBuffer.position() + bytesToWrite)
            inputBuffer.put(byteBuffer)
            byteBuffer.limit(oldLimit)

            val samplesInBatch = bytesToWrite / 2

            // Task 4.4: Compute PTS BEFORE incrementing totalSamplesWritten
            val ptsUs = (totalSamplesWritten * 1_000_000L) / SAMPLE_RATE

            codec.queueInputBuffer(bufferIndex, 0, bytesToWrite, ptsUs, 0)
            totalSamplesWritten += samplesInBatch
            bytesRemaining = byteBuffer.remaining()
        }
    }

    // Task 4.5: Signal EOS with retry loop
    private fun signalEndOfStream() {
        val codec = encoder ?: return

        // Retry loop with 10ms timeout — AOSP's 500µs one-shot crashes with
        // IllegalArgumentException on -1 return under CPU pressure
        var bufferIndex: Int
        do {
            bufferIndex = codec.dequeueInputBuffer(10_000) // 10ms
        } while (bufferIndex < 0)

        val ptsUs = (totalSamplesWritten * 1_000_000L) / SAMPLE_RATE
        codec.queueInputBuffer(bufferIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    // Task 4.3: Drain encoder output, handle FORMAT_CHANGED, discard CODEC_CONFIG
    private fun drainEncoder(endOfStream: Boolean) {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)

            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Task 4.3: addTrack → muxer.start() before any writeSampleData
                    val newFormat = codec.outputFormat
                    audioTrackIndex = muxer!!.addTrack(newFormat)
                    muxer!!.start()
                    muxerStarted = true
                    Log.d(TAG, "Audio muxer started, format: $newFormat")
                }
                outputIndex >= 0 -> {
                    // Task 4.3: Discard BUFFER_FLAG_CODEC_CONFIG — CSD already in track format
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codec.releaseOutputBuffer(outputIndex, false)
                        continue
                    }

                    if (muxerStarted && info.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            muxer!!.writeSampleData(audioTrackIndex, outputBuffer, info)
                        }
                    }

                    codec.releaseOutputBuffer(outputIndex, false)

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
                else -> {
                    // No more output available
                    if (!endOfStream) return
                    // If EOS, keep draining
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) return
                        // Small sleep to avoid busy-wait during EOS drain
                        Thread.sleep(1)
                    }
                }
            }
        }
    }
}
