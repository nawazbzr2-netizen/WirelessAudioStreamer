package com.wireless.audiostreamer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Smooth player: consumes JitterBuffer at steady rate (~200ms prebuffer).
 */
class LowLatencyPlayer {
    companion object {
        private const val TAG = "LowLatencyPlayer"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val TARGET_MS = 200
    }

    private var audioTrack: AudioTrack? = null
    private val jitter = JitterBuffer(targetMs = TARGET_MS, sampleRate = SAMPLE_RATE)
    private val running = AtomicBoolean(false)
    private var playThread: Thread? = null

    fun start() {
        if (running.get()) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            // Hardware buffer ~100-150ms
            val hwBuf = maxOf(minBuf * 3, SAMPLE_RATE * 2 * 2 / 8)

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

            // Prefer POWER_SAVING / none over LOW_LATENCY for fewer glitches on music
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            }

            audioTrack = builder.build()
            audioTrack?.play()
            running.set(true)

            // Steady pull from jitter buffer
            playThread = thread(name = "WasSmoothPlayer", isDaemon = true) {
                // 20ms frames
                val frameMs = 20
                val frameBytes = SAMPLE_RATE * 2 * 2 * frameMs / 1000
                while (running.get()) {
                    try {
                        if (!jitter.isPrimed()) {
                            Thread.sleep(10)
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
                            // underrun: brief wait, optional comfort noise could go here
                            Thread.sleep(5)
                        }
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "play loop", e)
                    }
                }
            }
            Log.i(TAG, "Smooth player started target=${TARGET_MS}ms")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
        }
    }

    /** Feed network packet into jitter buffer */
    fun enqueue(sequence: Int, pcm: ByteArray) {
        if (!running.get()) return
        jitter.add(sequence, pcm)
    }

    @Deprecated("Use enqueue(seq, pcm)", ReplaceWith("enqueue(0, pcm)"))
    fun write(pcm: ByteArray) {
        enqueue(0, pcm)
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
        Log.i(TAG, "Player stopped")
    }
}
