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
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

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
    private var internalReaderThread: Thread? = null
    private var micReaderThread: Thread? = null

    // Queues between reader threads and mix/encode thread
    private val internalQueue = ArrayBlockingQueue<ShortArray>(64)
    private val micQueue = ArrayBlockingQueue<ShortArray>(64)

    // Accumulator for leftover mic samples between mix iterations
    // Prevents mic data loss when chunk sizes differ between AudioRecord sources
    private val micAccum = ShortArray(8192) // ~185ms at 44100Hz
    private var micAccumUsed = 0

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

        // Separate reader thread for internal audio — blocking read won't starve mic
        internalReaderThread = Thread({
            internalReaderLoop()
        }, "InternalAudioReader").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        // Separate reader thread for mic (BOTH mode only)
        if (effectiveMode == AudioMode.BOTH) {
            micReaderThread = Thread({
                micReaderLoop()
            }, "MicAudioReader").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }

        // Mix + encode thread consumes from queues
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

        // Stop AudioRecords to unblock blocking read() calls in reader threads
        internalAudioRecord?.let { try { it.stop() } catch (_: Exception) {} }
        micAudioRecord?.let { try { it.stop() } catch (_: Exception) {} }

        // Join reader threads first (they feed the queues)
        internalReaderThread?.join(3000)
        internalReaderThread = null
        micReaderThread?.join(3000)
        micReaderThread = null

        // Poison the internal queue so captureLoop unblocks if waiting
        internalQueue.offer(ShortArray(0))

        // Wait for mix/encode thread — it owns encoder/muxer shutdown
        audioThread?.join(8000)
        audioThread = null

        // Release AudioRecords after threads exit
        internalAudioRecord?.release()
        internalAudioRecord = null
        micAudioRecord?.release()
        micAudioRecord = null

        // Safety cleanup if thread timed out and left resources alive
        encoder?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        encoder = null
        if (muxerStarted) { try { muxer?.stop() } catch (_: Exception) {} }
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        muxerStarted = false
    }

    /** Called by audio thread only — stops encoder + muxer from the owning thread. */
    private fun shutdownEncoderAndMuxer() {
        encoder?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        encoder = null
        if (muxerStarted) { try { muxer?.stop() } catch (_: Exception) {} }
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

    /** Reads from internalAudioRecord and puts chunks into internalQueue. */
    private fun internalReaderLoop() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val buf = ShortArray(bufferSize)
        while (isCapturing) {
            val read = internalAudioRecord?.read(buf, 0, bufferSize) ?: AudioRecord.ERROR
            if (read < 0) {
                Log.e(TAG, "Internal AudioRecord read error: $read")
                break
            }
            if (read > 0) {
                internalQueue.offer(buf.copyOf(read), 200, TimeUnit.MILLISECONDS)
            }
        }
        Log.d(TAG, "internalReaderLoop exited")
    }

    /** Reads from micAudioRecord and puts chunks into micQueue. Exits silently on error. */
    private fun micReaderLoop() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val buf = ShortArray(bufferSize)
        while (isCapturing) {
            val read = micAudioRecord?.read(buf, 0, bufferSize) ?: AudioRecord.ERROR
            if (read < 0) {
                Log.w(TAG, "Mic AudioRecord read error: $read — mic will be silent")
                break
            }
            if (read > 0) {
                micQueue.offer(buf.copyOf(read), 200, TimeUnit.MILLISECONDS)
            }
        }
        Log.d(TAG, "micReaderLoop exited")
    }

    /**
     * Mix + encode loop. Consumes from internalQueue (blocking) and micQueue (non-blocking).
     * Internal audio drives timing; mic is zero-filled when not available.
     */
    private fun captureLoop() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val mixedBuffer = ShortArray(bufferSize)

        try {
            while (isCapturing) {
                // Block until internal audio is available (200ms timeout to check isCapturing)
                val internalChunk = internalQueue.poll(200, TimeUnit.MILLISECONDS)
                    ?: continue

                // Poison pill: empty array signals stop()
                if (internalChunk.isEmpty()) break

                if (effectiveMode == AudioMode.BOTH) {
                    // Drain mic queue into accumulator (non-blocking)
                    // Leftover from previous iteration stays in micAccum[0..micAccumUsed)
                    var micPoll = micQueue.poll()
                    while (micPoll != null && micAccumUsed + micPoll.size <= micAccum.size) {
                        System.arraycopy(micPoll, 0, micAccum, micAccumUsed, micPoll.size)
                        micAccumUsed += micPoll.size
                        micPoll = micQueue.poll()
                    }

                    val count = internalChunk.size
                    for (i in 0 until count) {
                        val internal = internalChunk[i].toInt()
                        val mic = if (i < micAccumUsed)
                            (micAccum[i] * MIC_VOLUME_SCALE).toInt() else 0
                        val mixed = internal + mic
                        mixedBuffer[i] = mixed.coerceIn(
                            Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()
                        ).toShort()
                    }

                    // Consume used samples, shift remainder to front of accumulator
                    val used = minOf(count, micAccumUsed)
                    if (micAccumUsed > count) {
                        System.arraycopy(micAccum, count, micAccum, 0, micAccumUsed - count)
                    }
                    micAccumUsed = maxOf(0, micAccumUsed - count)

                    feedEncoder(mixedBuffer, count)
                } else {
                    // INTERNAL_ONLY: feed raw internal chunk
                    feedEncoder(internalChunk, internalChunk.size)
                }

                drainEncoder(false)
            }

            if (encoder != null) {
                signalEndOfStream()
                drainEncoder(true)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Audio capture loop error", e)
        } finally {
            shutdownEncoderAndMuxer()
        }

        Log.d(TAG, "Audio capture loop ended, totalSamples=$totalSamplesWritten")
    }

    // Task 4.4: Feed PCM to encoder input buffers
    private fun feedEncoder(samples: ShortArray, count: Int) {
        val codec = encoder ?: return

        // Convert shorts to bytes (MUST use native byte order — Java defaults to BIG_ENDIAN
        // but MediaCodec expects LITTLE_ENDIAN PCM on Android)
        val byteBuffer = ByteBuffer.allocate(count * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until count) {
            byteBuffer.putShort(samples[i])
        }
        byteBuffer.flip()

        var bytesRemaining = byteBuffer.remaining()
        while (bytesRemaining > 0 && isCapturing) {
            // Task 4.4: 10ms timeout with retry (AOSP's 500µs drops PCM under load)
            val bufferIndex = codec.dequeueInputBuffer(10_000)
            if (bufferIndex < 0) {
                // Input queue full — drain output buffers so encoder can accept more input.
                // Without this, output pool fills up → dequeueInputBuffer deadlocks forever.
                drainEncoder(false)
                continue
            }

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

    // Task 4.5: Signal EOS with retry loop (bounded — 50 × 10ms = 500ms max)
    private fun signalEndOfStream() {
        val codec = encoder ?: return

        var bufferIndex = -1
        var attempts = 0
        while (bufferIndex < 0 && attempts < 50) {
            bufferIndex = codec.dequeueInputBuffer(10_000) // 10ms
            if (bufferIndex < 0) {
                // Output pool may still be full — drain to unblock encoder input
                drainEncoder(false)
            }
            attempts++
        }
        if (bufferIndex < 0) {
            Log.w(TAG, "EOS: could not get input buffer after $attempts attempts, skipping")
            return
        }

        val ptsUs = (totalSamplesWritten * 1_000_000L) / SAMPLE_RATE
        codec.queueInputBuffer(bufferIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    // Task 4.3: Drain encoder output, handle FORMAT_CHANGED, discard CODEC_CONFIG
    private fun drainEncoder(endOfStream: Boolean) {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()
        var eosIterations = 0
        val maxEosIterations = 500 // 500 × 2ms = 1s max EOS drain

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
                    // EOS drain: sleep briefly and retry, but respect cancellation
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        Thread.sleep(2)
                        eosIterations++
                        if (eosIterations >= maxEosIterations) {
                            Log.w(TAG, "EOS drain timed out after ${eosIterations * 2}ms")
                            return
                        }
                    }
                    // Safety: bail out if encoder was stopped externally
                    if (encoder == null) return
                }
            }
        }
    }
}
