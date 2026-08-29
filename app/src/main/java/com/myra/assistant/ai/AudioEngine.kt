package com.myra.assistant.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioEngine(private val context: Context) {

    interface Callback {
        fun onMicAudioChunk(chunk: ByteArray) {}
        fun onAmplitudeChanged(rmsNormalized: Float) {}
        fun onSpeakingStarted() {}
        fun onSpeakingStopped() {}
        fun onError(message: String) {}
    }

    var callback: Callback? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    val isSpeaking = AtomicBoolean(false)

    private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()
    private var recordThread: Thread? = null
    private var playbackThread: Thread? = null

    private val recordBufferSize: Int
    private val trackBufferSize: Int

    init {
        val minRecBuf = AudioRecord.getMinBufferSize(
            MIC_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        recordBufferSize = maxOf(minRecBuf, CHUNK_SIZE * 4)

        val minTrackBuf = AudioTrack.getMinBufferSize(
            SPEAKER_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        trackBufferSize = maxOf(minTrackBuf, CHUNK_SIZE * 4)
    }

    fun startRecording() {
        if (isRecording.get()) return

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            callback?.onError("Microphone permission not granted")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    MIC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recordBufferSize
                )
            }

            audioRecord?.startRecording()
            isRecording.set(true)

            recordThread = Thread({
                val buffer = ByteArray(CHUNK_SIZE)
                while (isRecording.get()) {
                    val read = audioRecord?.read(buffer, 0, CHUNK_SIZE) ?: -1
                    if (read > 0) {
                        val chunk = buffer.copyOf(read)
                        val rms = calculateRms(chunk)

                        mainHandler.post {
                            callback?.onAmplitudeChanged(rms)
                        }

                        // Echo suppression: Don't send mic chunk to Gemini if MYRA is speaking or muted
                        if (!isMuted.get() && !isSpeaking.get()) {
                            callback?.onMicAudioChunk(chunk)
                        }
                    }
                }
            }, "Myra-Record-Thread").apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            callback?.onError("Mic error: ${e.message}")
        }
    }

    fun stopRecording() {
        isRecording.set(false)
        try {
            recordThread?.interrupt()
            recordThread = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }

    fun startPlayback() {
        if (isPlaying.get()) return

        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SPEAKER_SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            audioTrack = AudioTrack(
                attributes,
                format,
                trackBufferSize,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            audioTrack?.play()
            isPlaying.set(true)

            playbackThread = Thread({
                var silenceCount = 0
                while (isPlaying.get()) {
                    val chunk = playbackQueue.poll()
                    if (chunk != null) {
                        if (!isSpeaking.getAndSet(true)) {
                            mainHandler.post { callback?.onSpeakingStarted() }
                        }
                        silenceCount = 0
                        audioTrack?.write(chunk, 0, chunk.size)

                        val rms = calculateRms(chunk)
                        mainHandler.post { callback?.onAmplitudeChanged(rms) }
                    } else {
                        if (isSpeaking.get()) {
                            silenceCount++
                            // Wait a short drain period before considering speaking stopped
                            if (silenceCount > 6) {
                                isSpeaking.set(false)
                                mainHandler.post { callback?.onSpeakingStopped() }
                            }
                        }
                        try {
                            Thread.sleep(25)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }
            }, "Myra-Playback-Thread").apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting playback", e)
            callback?.onError("Speaker error: ${e.message}")
        }
    }

    fun stopPlayback() {
        isPlaying.set(false)
        isSpeaking.set(false)
        clearPlaybackQueue()
        try {
            playbackThread?.interrupt()
            playbackThread = null
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback", e)
        }
    }

    fun queueAudio(pcmData: ByteArray) {
        if (pcmData.isNotEmpty()) {
            playbackQueue.offer(pcmData)
        }
    }

    fun clearPlaybackQueue() {
        playbackQueue.clear()
        if (isSpeaking.getAndSet(false)) {
            mainHandler.post { callback?.onSpeakingStopped() }
        }
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }

    fun isMuted(): Boolean = isMuted.get()

    fun release() {
        stopRecording()
        stopPlayback()
    }

    private fun calculateRms(pcmBytes: ByteArray): Float {
        if (pcmBytes.size < 2) return 0f
        var sumSquares = 0.0
        val sampleCount = pcmBytes.size / 2
        for (i in 0 until sampleCount) {
            val low = pcmBytes[i * 2].toInt() and 0xFF
            val high = pcmBytes[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += (sample * sample).toDouble()
        }
        val mean = sumSquares / sampleCount
        val rms = sqrt(mean).toFloat()
        // Normalize 0..32767 to 0..1.0f with logarithmic/aesthetic curve
        val normalized = (rms / 8000f).coerceIn(0f, 1f)
        return normalized
    }

    companion object {
        private const val TAG = "AudioEngine"
        const val MIC_SAMPLE_RATE = 16000
        const val SPEAKER_SAMPLE_RATE = 24000
        const val CHUNK_SIZE = 1024
    }
}
