package com.wireless.audiostreamer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LowLatencyPlayer {
    companion object {
        private const val TAG = "LowLatencyPlayer"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // ~200ms target buffer for clean playback
        private const val TARGET_BUFFER_MS = 200
    }

    private var audioTrack: AudioTrack? = null
    private val queue = LinkedBlockingQueue<ByteArray>(64)
    private val running = AtomicBoolean(false)
    private var playThread: Thread? = null

    fun start() {
        if (running.get()) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val targetBytes = SAMPLE_RATE * 2 * 2 * TARGET_BUFFER_MS / 1000
            val bufferSize = maxOf(minBuf * 4, targetBytes)

            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            audioTrack = builder.build()
            audioTrack?.play()
            running.set(true)

            playThread = thread(name = "WasPlayer", isDaemon = true) {
                while (running.get()) {
                    try {
                        val data = queue.take()
                        audioTrack?.write(data, 0, data.size)
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "write error", e)
                    }
                }
            }
            Log.i(TAG, "Player started buf=$bufferSize")
        } catch (e: Exception) {
            Log.e(TAG, "Player start failed", e)
        }
    }

    fun write(pcm: ByteArray) {
        if (!running.get()) return
        // Drop oldest if queue too full (avoid multi-second delay)
        if (queue.size > 40) {
            queue.poll()
        }
        queue.offer(pcm)
    }

    fun stop() {
        running.set(false)
        playThread?.interrupt()
        playThread = null
        queue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        Log.i(TAG, "Player stopped")
    }
}
