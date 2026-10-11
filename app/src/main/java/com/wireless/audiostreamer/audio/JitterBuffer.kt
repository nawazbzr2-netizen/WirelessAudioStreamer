package com.wireless.audiostreamer.audio

import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean

/** Stable buffer for clear speech/music — mono 200ms default. */
class JitterBuffer(
    private val targetMs: Int = 200,
    private val sampleRate: Int = 44100,
    private val channels: Int = 1,
    private val bytesPerSample: Int = 2
) {
    private val bytesPerMs = (sampleRate * channels * bytesPerSample) / 1000
    private val targetBytes = (targetMs * bytesPerMs).coerceAtLeast(bytesPerMs * 60)
    private val maxBytes = targetBytes * 3

    private val lock = Any()
    private val map = TreeMap<Int, ByteArray>()
    private var nextSeq: Int? = null
    private var bufferedBytes = 0
    private val primed = AtomicBoolean(false)

    fun add(sequence: Int, data: ByteArray) {
        synchronized(lock) {
            if (map.containsKey(sequence)) return
            while (bufferedBytes > maxBytes && map.isNotEmpty()) {
                val first = map.firstKey()
                val removed = map.remove(first)
                if (removed != null) bufferedBytes -= removed.size
            }
            map[sequence] = data
            bufferedBytes += data.size
            if (!primed.get() && bufferedBytes >= targetBytes) {
                nextSeq = map.firstKey()
                primed.set(true)
            }
        }
    }

    fun poll(): ByteArray? {
        synchronized(lock) {
            if (!primed.get()) return null
            val seq = nextSeq ?: return null
            val data = map.remove(seq)
            if (data != null) {
                bufferedBytes -= data.size
                nextSeq = seq + 1
                return data
            }
            val higher = map.higherKey(seq)
            return if (higher != null && higher - seq <= 8) {
                nextSeq = seq + 1
                ByteArray(bytesPerMs * 20) // 20ms silence for gap
            } else if (higher != null) {
                nextSeq = higher
                null
            } else null
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
}
