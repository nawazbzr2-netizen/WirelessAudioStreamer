package com.wireless.audiostreamer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Smooth playback — larger HW buffer + 140ms jitter to avoid cutting/stutter.
 */
class LowLatencyPlayer(
    private val targetJitterMs: Int = 140
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
            // ~120ms hardware buffer — fewer underruns
            val hwBuf = maxOf(minBuf * 4, SAMPLE_RATE * 2 * 2 * 120 / 1000)

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

            // NONE mode = more stable for music than LOW_LATENCY on many OEMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            }

            audioTrack = builder.build()
            audioTrack?.play()
            running.set(true)

            playThread = thread(name = "WasSmoothPlayer", isDaemon = true) {
                while (running.get()) {
                    try {
                        if (!jitter.isPrimed()) {
                            Thread.sleep(8)
                            continue
                        }
                        val data = jitter.poll()
                        if (data != null && data.isNotEmpty()) {
                            var offset = 0
                            while (offset < data.size && running.get()) {
                                val w = audioTrack?.write(data, offset, data.size - offset) ?: -1
                                if (w <= 0) {
                                    Thread.sleep(2)
                                    break
                                }
                                offset += w
                            }
                        } else {
                            // Soft wait — do not spin
                            Thread.sleep(4)
                        }
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "play", e)
                        Thread.sleep(10)
                    }
                }
            }
            Log.i(TAG, "Player started jitter=${targetJitterMs}ms hwBuf=$hwBuf")
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
