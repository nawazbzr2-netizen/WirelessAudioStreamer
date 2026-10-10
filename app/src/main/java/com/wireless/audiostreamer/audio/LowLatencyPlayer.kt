package com.wireless.audiostreamer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LowLatencyPlayer(
    private val targetJitterMs: Int = 70 // WiFi default 50-100ms range
) {
    companion object {
        private const val TAG = "LowLatencyPlayer"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private var jitter: JitterBuffer = JitterBuffer(targetMs = targetJitterMs)
    private val running = AtomicBoolean(false)
    private var playThread: Thread? = null

    fun start() {
        if (running.get()) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val hwBuf = maxOf(minBuf * 2, SAMPLE_RATE * 2 * 2 / 20) // ~50ms hw

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
                .setBufferSizeInBytes(hwBuf)
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
                        if (!jitter.isPrimed()) {
                            Thread.sleep(5)
                            continue
                        }
                        val data = jitter.poll()
                        if (data != null) {
                            var offset = 0
                            while (offset < data.size && running.get()) {
                                val w = audioTrack?.write(data, offset, data.size - offset) ?: -1
                                if (w < 0) break
                                offset += w
                            }
                        } else {
                            Thread.sleep(3)
                        }
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "play", e)
                    }
                }
            }
            Log.i(TAG, "Player started jitter=${targetJitterMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
        }
    }

    fun enqueue(sequence: Int, pcm: ByteArray) {
        if (running.get()) jitter.add(sequence, pcm)
    }

    fun stop() {
        running.set(false)
        playThread?.interrupt()
        playThread = null
        jitter.reset()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }
}
