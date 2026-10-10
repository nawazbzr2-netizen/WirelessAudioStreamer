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
import androidx.core.content.ContextCompat
import com.wireless.audiostreamer.R
import com.wireless.audiostreamer.databinding.ActivityMainBinding
import com.wireless.audiostreamer.service.StreamingService

enum class ConnectionType {
    NONE, SAME_WIFI, HOTSPOT, BLUETOOTH
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isSenderMode = true
    private var selectedConnection = ConnectionType.NONE

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_START_SENDER
                putExtra(StreamingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(StreamingService.EXTRA_RESULT_DATA, result.data)
                putExtra(StreamingService.EXTRA_CONNECTION_TYPE, selectedConnection.name)
            }
            ContextCompat.startForegroundService(this, intent)
            updateUI(streaming = true)
        } else {
            Toast.makeText(this, "Permission denied. System audio capture nahi ho sakta.", Toast.LENGTH_LONG).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            proceedAfterPermissions()
        } else {
            Toast.makeText(this, "Zaroori permissions nahi mili", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        // Mode selection
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

        // Connection type buttons
        binding.btnSameWifi.setOnClickListener {
            selectConnection(ConnectionType.SAME_WIFI)
        }

        binding.btnHotspot.setOnClickListener {
            selectConnection(ConnectionType.HOTSPOT)
        }

        binding.btnBluetooth.setOnClickListener {
            selectConnection(ConnectionType.BLUETOOTH)
        }

        // Start / Stop
        binding.btnStartStop.setOnClickListener {
            if (binding.btnStartStop.text == getString(R.string.start_streaming)) {
                if (selectedConnection == ConnectionType.NONE) {
                    Toast.makeText(this, "Pehle connection type select karo (WiFi / Hotspot / Bluetooth)", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                checkPermissionsAndStart()
            } else {
                stopStreaming()
            }
        }

        // Default
        binding.btnSender.performClick()
    }

    private fun selectConnection(type: ConnectionType) {
        selectedConnection = type

        // Reset all buttons style
        binding.btnSameWifi.strokeWidth = 2
        binding.btnHotspot.strokeWidth = 2
        binding.btnBluetooth.strokeWidth = 2

        when (type) {
            ConnectionType.SAME_WIFI -> {
                binding.btnSameWifi.strokeWidth = 6
                binding.tvConnectionType.text = "Selected: Same WiFi"
                binding.tvStatus.text = "Same WiFi pe dono phones connect hon"
            }
            ConnectionType.HOTSPOT -> {
                binding.btnHotspot.strokeWidth = 6
                binding.tvConnectionType.text = "Selected: Hotspot + WiFi"
                binding.tvStatus.text = "Ek phone Hotspot on kare, dusra usse connect kare"
            }
            ConnectionType.BLUETOOTH -> {
                binding.btnBluetooth.strokeWidth = 6
                binding.tvConnectionType.text = "Selected: Bluetooth"
                binding.tvStatus.text = "Bluetooth pairing se connect hoga"
            }
            else -> {}
        }
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
            proceedAfterPermissions()
        }
    }

    private fun proceedAfterPermissions() {
        when (selectedConnection) {
            ConnectionType.SAME_WIFI,
            ConnectionType.HOTSPOT -> {
                // WiFi / Hotspot path
                if (isSenderMode) {
                    requestMediaProjection()
                } else {
                    startReceiver()
                }
            }
            ConnectionType.BLUETOOTH -> {
                // Bluetooth path (limited on phones)
                Toast.makeText(
                    this,
                    "Bluetooth phone-to-phone audio limited hai. WiFi/Hotspot better hai.",
                    Toast.LENGTH_LONG
                ).show()
                if (isSenderMode) {
                    requestMediaProjection()
                } else {
                    startReceiver()
                }
            }
            else -> {
                Toast.makeText(this, "Connection type select karo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestMediaProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startReceiver() {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START_RECEIVER
            putExtra(StreamingService.EXTRA_CONNECTION_TYPE, selectedConnection.name)
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
            binding.btnStartStop.text = getString(R.string.stop_streaming)
            binding.tvStatus.text = if (isSenderMode) "Sender running…" else "Receiver running…"
            // Disable connection buttons while streaming
            binding.btnSameWifi.isEnabled = false
            binding.btnHotspot.isEnabled = false
            binding.btnBluetooth.isEnabled = false
        } else {
            binding.btnStartStop.text = getString(R.string.start_streaming)
            binding.tvStatus.text = "Idle"
            binding.tvLatency.text = "Latency: -- ms"
            binding.btnSameWifi.isEnabled = true
            binding.btnHotspot.isEnabled = true
            binding.btnBluetooth.isEnabled = true
        }
    }
}
