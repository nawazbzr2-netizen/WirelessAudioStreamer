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

@RequiresApi(Build.VERSION_CODES.Q)
class SystemAudioCapturer(
    private val mediaProjection: MediaProjection,
    private val onPcmFrame: (sequence: Int, pcm: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "SystemAudioCapturer"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_MS = 20 // 20ms frames — more stable than 10ms
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
                "WasDummy",
                320, 240, 120,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null as Surface?,
                null,
                handler
            )

            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val frameBytes = SAMPLE_RATE * 2 * 2 * FRAME_MS / 1000
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBuf * 4, frameBytes * 8)

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
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
            Log.i(TAG, "Capture ${FRAME_MS}ms frames")

            scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(frameBytes)
                while (isActive && isCapturing) {
                    var filled = 0
                    while (filled < frameBytes && isCapturing) {
                        val read = audioRecord?.read(buffer, filled, frameBytes - filled) ?: -1
                        if (read < 0) return@launch
                        filled += read
                    }
                    if (filled == frameBytes) {
                        onPcmFrame(seq++, buffer.copyOf())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            isCapturing = false
        }
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
