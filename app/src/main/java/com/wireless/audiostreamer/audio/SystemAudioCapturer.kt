package com.wireless.audiostreamer.audio

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Captures system audio as mono PCM 20ms frames (lower bandwidth, clearer). */
@RequiresApi(Build.VERSION_CODES.Q)
class SystemAudioCapturer(
    private val mediaProjection: MediaProjection,
    private val onPcmFrame: (sequence: Int, pcm: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "SystemAudioCapturer"
        const val SAMPLE_RATE = 44100
        // Capture stereo then downmix to mono for network
        private const val IN_CHANNELS = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_MS = 20
    }

    private var audioRecord: AudioRecord? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var handlerThread: HandlerThread? = null
    @Volatile private var isCapturing = false
    private var seq = 0

    fun start(scope: CoroutineScope) {
        if (isCapturing) return
        try {
            handlerThread = HandlerThread("WasProjection").also { it.start() }
            val handler = Handler(handlerThread!!.looper)

            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped")
                    stop()
                }
            }, handler)

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "WasDummy", 320, 240, 120,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null as Surface?, null, handler
            )

            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            // Stereo capture buffer (L+R), then downmix
            val stereoFrameBytes = SAMPLE_RATE * 2 * 2 * FRAME_MS / 1000
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, IN_CHANNELS, AUDIO_FORMAT)
            val bufferSize = maxOf(minBuf * 4, stereoFrameBytes * 8)

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(IN_CHANNELS)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                return
            }

            audioRecord?.startRecording()
            isCapturing = true
            seq = 0
            Log.i(TAG, "Capture mono-downmix ${FRAME_MS}ms")

            scope.launch(Dispatchers.IO) {
                val stereo = ByteArray(stereoFrameBytes)
                while (isActive && isCapturing) {
                    var filled = 0
                    while (filled < stereoFrameBytes && isCapturing) {
                        val read = audioRecord?.read(stereo, filled, stereoFrameBytes - filled) ?: -1
                        if (read < 0) return@launch
                        filled += read
                    }
                    if (filled == stereoFrameBytes) {
                        onPcmFrame(seq++, downmixToMono(stereo))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            isCapturing = false
        }
    }

    /** Stereo 16-bit LE -> mono average */
    private fun downmixToMono(stereo: ByteArray): ByteArray {
        val samples = stereo.size / 4 // stereo frames
        val mono = ByteArray(samples * 2)
        var si = 0
        var mi = 0
        while (si + 3 < stereo.size) {
            val l = (stereo[si].toInt() and 0xFF) or (stereo[si + 1].toInt() shl 8)
            val r = (stereo[si + 2].toInt() and 0xFF) or (stereo[si + 3].toInt() shl 8)
            val ls = l.toShort().toInt()
            val rs = r.toShort().toInt()
            val m = ((ls + rs) / 2).toShort().toInt()
            mono[mi] = (m and 0xFF).toByte()
            mono[mi + 1] = ((m shr 8) and 0xFF).toByte()
            si += 4
            mi += 2
        }
        return mono
    }

    fun stop() {
        isCapturing = false
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { handlerThread?.quitSafely() } catch (_: Exception) {}
        handlerThread = null
    }
}
