package com.wireless.audiostreamer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Mono playback + large buffer = clear understandable audio. */
class LowLatencyPlayer(
    private val targetJitterMs: Int = 200
) {
    companion object {
        private const val TAG = "LowLatencyPlayer"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private var jitter = JitterBuffer(targetMs = targetJitterMs, channels = 1)
    private val running = AtomicBoolean(false)
    private var playThread: Thread? = null

    fun start() {
        if (running.get()) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val hwBuf = maxOf(minBuf * 6, SAMPLE_RATE * 2 * 150 / 1000) // ~150ms HW

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
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            }

            audioTrack = builder.build()
            audioTrack?.play()
            running.set(true)

            playThread = thread(name = "WasPlayer", isDaemon = true) {
                while (running.get()) {
                    try {
                        if (!jitter.isPrimed()) {
                            Thread.sleep(10)
                            continue
                        }
                        val data = jitter.poll()
                        if (data != null && data.isNotEmpty()) {
                            var offset = 0
                            while (offset < data.size && running.get()) {
                                val w = audioTrack?.write(data, offset, data.size - offset) ?: -1
                                if (w <= 0) {
                                    Thread.sleep(3)
                                    break
                                }
                                offset += w
                            }
                        } else {
                            Thread.sleep(5)
                        }
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "play", e)
                        Thread.sleep(15)
                    }
                }
            }
            Log.i(TAG, "Mono player jitter=${targetJitterMs}ms")
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
