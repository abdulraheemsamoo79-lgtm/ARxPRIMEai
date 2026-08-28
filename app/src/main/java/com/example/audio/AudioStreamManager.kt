package com.example.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

class AudioStreamManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val SAMPLE_RATE_IN = 16000
        const val SAMPLE_RATE_OUT = 24000
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var recordingJob: Job? = null
    private var playbackJob: Job? = null

    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)

    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()

    var onAudioChunk: ((ByteArray) -> Unit)? = null
    var onInputAmplitude: ((Float) -> Unit)? = null
    var onOutputAmplitude: ((Float) -> Unit)? = null

    init {
        initAudioTrack()
    }

    @Synchronized
    private fun initAudioTrack() {
        try {
            val minBufSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_OUT,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_OUT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            startPlaybackLoop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startPlaybackLoop() {
        isPlaying.set(true)
        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val chunk = audioQueue.poll()
                if (chunk != null && chunk.isNotEmpty()) {
                    try {
                        audioTrack?.write(chunk, 0, chunk.size)
                        calculatePcmAmplitude(chunk) { amp ->
                            onOutputAmplitude?.invoke(amp)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    onOutputAmplitude?.invoke(0.05f)
                    try {
                        kotlinx.coroutines.delay(15)
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        }
    }

    fun startRecording(): Boolean {
        if (isRecording.get()) return true

        val hasRecordPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasRecordPerm) return false

        try {
            val minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_IN,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val bufferSize = (minBufSize * 2).coerceAtLeast(4096)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_IN,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return false
            }

            audioRecord?.startRecording()
            isRecording.set(true)

            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(2048) // 1024 samples = ~64ms chunks
                while (isActive && isRecording.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        val chunk = buffer.copyOf(read)
                        onAudioChunk?.invoke(chunk)
                        calculatePcmAmplitude(chunk) { amp ->
                            onInputAmplitude?.invoke(amp)
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun stopRecording() {
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
        onInputAmplitude?.invoke(0f)
    }

    fun enqueueAudioData(pcmBytes: ByteArray) {
        if (pcmBytes.isNotEmpty()) {
            audioQueue.offer(pcmBytes)
        }
    }

    fun interruptPlayback() {
        audioQueue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        onOutputAmplitude?.invoke(0f)
    }

    private fun calculatePcmAmplitude(bytes: ByteArray, callback: (Float) -> Unit) {
        if (bytes.isEmpty()) {
            callback(0f)
            return
        }
        var sum = 0.0
        val sampleCount = bytes.size / 2
        for (i in 0 until sampleCount) {
            val sample = (bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)
            val shortVal = sample.toShort().toFloat()
            sum += shortVal * shortVal
        }
        val rms = sqrt(sum / sampleCount)
        val normalized = (rms / 12000f).coerceIn(0.0, 1.0).toFloat()
        callback(normalized)
    }

    fun release() {
        stopRecording()
        isPlaying.set(false)
        playbackJob?.cancel()
        audioQueue.clear()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null
    }
}
