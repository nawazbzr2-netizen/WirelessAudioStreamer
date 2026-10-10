package com.wireless.audiostreamer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.wireless.audiostreamer.databinding.ActivityMainBinding
import com.wireless.audiostreamer.service.StreamingService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isSenderMode = true

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // Start foreground service with MediaProjection data
            val intent = Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_START_SENDER
                putExtra(StreamingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(StreamingService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
            updateUI(streaming = true)
        } else {
            Toast.makeText(this, "Permission denied. System audio cannot be captured.", Toast.LENGTH_LONG).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            if (isSenderMode) requestMediaProjection() else startReceiver()
        } else {
            Toast.makeText(this, "Required permissions not granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        binding.btnSender.setOnClickListener {
            isSenderMode = true
            binding.btnSender.isEnabled = false
            binding.btnReceiver.isEnabled = true
            binding.tvMode.text = "Mode: Sender (System Audio)"
        }

        binding.btnReceiver.setOnClickListener {
            isSenderMode = false
            binding.btnReceiver.isEnabled = false
            binding.btnSender.isEnabled = true
            binding.tvMode.text = "Mode: Receiver (Speaker)"
        }

        binding.btnStartStop.setOnClickListener {
            if (binding.btnStartStop.text == getString(com.wireless.audiostreamer.R.string.start_streaming)) {
                checkPermissionsAndStart()
            } else {
                stopStreaming()
            }
        }

        // Default to Sender
        binding.btnSender.performClick()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            if (isSenderMode) requestMediaProjection() else startReceiver()
        }
    }

    private fun requestMediaProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startReceiver() {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START_RECEIVER
        }
        ContextCompat.startForegroundService(this, intent)
        updateUI(streaming = true)
    }

    private fun stopStreaming() {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        startService(intent)
        updateUI(streaming = false)
    }

    private fun updateUI(streaming: Boolean) {
        if (streaming) {
            binding.btnStartStop.text = getString(com.wireless.audiostreamer.R.string.stop_streaming)
            binding.tvStatus.text = if (isSenderMode) "Sender running…" else "Receiver running…"
        } else {
            binding.btnStartStop.text = getString(com.wireless.audiostreamer.R.string.start_streaming)
            binding.tvStatus.text = "Idle"
            binding.tvLatency.text = "Latency: -- ms"
        }
    }
}
