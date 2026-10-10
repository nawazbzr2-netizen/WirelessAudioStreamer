package com.wireless.audiostreamer.audio

import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sequence-ordered jitter buffer.
 * Prebuffers ~targetMs then releases steady frames for clean playback.
 */
class JitterBuffer(
    private val targetMs: Int = 200,
    private val sampleRate: Int = 44100,
    private val channels: Int = 2,
    private val bytesPerSample: Int = 2
) {
    private val bytesPerMs = sampleRate * channels * bytesPerSample / 1000
    private val targetBytes = targetMs * bytesPerMs

    private val lock = Any()
    private val map = TreeMap<Int, ByteArray>() // seq -> pcm
    private var nextSeq: Int? = null
    private var bufferedBytes = 0
    private val primed = AtomicBoolean(false)

    fun add(sequence: Int, data: ByteArray) {
        synchronized(lock) {
            if (map.containsKey(sequence)) return
            // Drop very old packets if buffer huge
            if (bufferedBytes > targetBytes * 3) {
                val first = map.firstKey()
                val removed = map.remove(first)
                if (removed != null) bufferedBytes -= removed.size
            }
            map[sequence] = data
            bufferedBytes += data.size

            if (!primed.get() && bufferedBytes >= targetBytes) {
                // Start from the lowest sequence we have
                nextSeq = map.firstKey()
                primed.set(true)
            }
        }
    }

    /** Pull next contiguous chunk; null if not ready / underrun */
    fun poll(): ByteArray? {
        synchronized(lock) {
            if (!primed.get()) return null
            val seq = nextSeq ?: return null
            val data = map.remove(seq)
            return if (data != null) {
                bufferedBytes -= data.size
                nextSeq = seq + 1
                data
            } else {
                // Missing packet: skip ahead if we have later data (concealment = silence gap)
                val higher = map.higherKey(seq)
                if (higher != null && higher - seq <= 3) {
                    // insert short silence for missing frame (~20ms)
                    val silence = ByteArray(bytesPerMs * 20)
                    nextSeq = seq + 1
                    silence
                } else if (higher != null && higher - seq > 3) {
                    // big gap — jump to next available
                    nextSeq = higher
                    null
                } else {
                    // wait a bit (underrun)
                    null
                }
            }
        }
    }

    fun isPrimed(): Boolean = primed.get()

    fun reset() {
        synchronized(lock) {
            map.clear()
            bufferedBytes = 0
            nextSeq = null
            primed.set(false)
        }
    }

    fun bufferedMs(): Int = synchronized(lock) {
        if (bytesPerMs == 0) 0 else bufferedBytes / bytesPerMs
    }
}
