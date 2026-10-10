package com.wireless.audiostreamer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures system audio using AudioPlaybackCapture (Android 10+).
 * Requires a valid MediaProjection obtained from the user.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class SystemAudioCapturer(
    private val mediaProjection: MediaProjection,
    private val onPcmData: (ByteArray) -> Unit
) {

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isCapturing = false

    fun start(scope: CoroutineScope) {
        if (isCapturing) return

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = minBuffer * 2

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

        audioRecord?.startRecording()
        isCapturing = true

        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            while (isActive && isCapturing) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    onPcmData(buffer.copyOf(read))
                }
            }
        }
    }

    fun stop() {
        isCapturing = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
