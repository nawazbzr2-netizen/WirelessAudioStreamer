package com.wireless.audiostreamer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wireless.audiostreamer.databinding.ActivityMainBinding
import com.wireless.audiostreamer.service.StreamingService

enum class ConnectionType {
    NONE, SAME_WIFI, HOTSPOT, BLUETOOTH
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedConnection = ConnectionType.NONE
    private var isConnected = false

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startServiceWithProjection(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Permission chahiye thi. Dobara try karo.", Toast.LENGTH_LONG).show()
            setConnected(false)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startConnection()
        } else {
            Toast.makeText(this, "Permissions allow karo", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupClicks()
    }

    private fun setupClicks() {
        binding.btnSameWifi.setOnClickListener { selectType(ConnectionType.SAME_WIFI) }
        binding.btnHotspot.setOnClickListener { selectType(ConnectionType.HOTSPOT) }
        binding.btnBluetooth.setOnClickListener { selectType(ConnectionType.BLUETOOTH) }

        binding.btnConnect.setOnClickListener {
            if (selectedConnection == ConnectionType.NONE) {
                Toast.makeText(this, "Pehle upar se connection type choose karo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkPermissionsAndConnect()
        }

        binding.btnDisconnect.setOnClickListener {
            stopService()
            setConnected(false)
        }
    }

    private fun selectType(type: ConnectionType) {
        selectedConnection = type

        // Reset colors
        binding.btnSameWifi.setBackgroundColor(0xFF1E88E5.toInt())
        binding.btnHotspot.setBackgroundColor(0xFF00897B.toInt())
        binding.btnBluetooth.setBackgroundColor(0xFF5E35B1.toInt())

        when (type) {
            ConnectionType.SAME_WIFI -> {
                binding.btnSameWifi.setBackgroundColor(0xFF1565C0.toInt())
                binding.tvConnectionType.text = "Same WiFi selected"
                binding.tvStatus.text = "Dono phones same WiFi pe hon"
            }
            ConnectionType.HOTSPOT -> {
                binding.btnHotspot.setBackgroundColor(0xFF00695C.toInt())
                binding.tvConnectionType.text = "Hotspot selected"
                binding.tvStatus.text = "Ek phone Hotspot on kare, dusra usse jud jaye"
            }
            ConnectionType.BLUETOOTH -> {
                binding.btnBluetooth.setBackgroundColor(0xFF4527A0.toInt())
                binding.tvConnectionType.text = "Bluetooth selected"
                binding.tvStatus.text = "Bluetooth on rakho (limited support)"
            }
            else -> {}
        }
    }

    private fun checkPermissionsAndConnect() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += listOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startConnection()
        }
    }

    private fun startConnection() {
        // System audio ke liye MediaProjection chahiye
        // Dono phones pe permission lenge taake dono sound bhej/suna saken
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startServiceWithProjection(resultCode: Int, data: Intent) {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StreamingService.EXTRA_RESULT_DATA, data)
            putExtra(StreamingService.EXTRA_CONNECTION_TYPE, selectedConnection.name)
        }
        ContextCompat.startForegroundService(this, intent)
        setConnected(true)
    }

    private fun stopService() {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        startService(intent)
    }

    private fun setConnected(connected: Boolean) {
        isConnected = connected
        if (connected) {
            binding.tvStatus.text = "Connected — sound share ho sakta hai"
            binding.btnConnect.visibility = View.GONE
            binding.btnDisconnect.visibility = View.VISIBLE
            binding.btnSameWifi.isEnabled = false
            binding.btnHotspot.isEnabled = false
            binding.btnBluetooth.isEnabled = false
        } else {
            binding.tvStatus.text = "Abhi connected nahi"
            binding.tvConnectionType.text = ""
            binding.tvLatency.text = "Latency: -- ms"
            binding.btnConnect.visibility = View.VISIBLE
            binding.btnDisconnect.visibility = View.GONE
            binding.btnSameWifi.isEnabled = true
            binding.btnHotspot.isEnabled = true
            binding.btnBluetooth.isEnabled = true
            selectedConnection = ConnectionType.NONE
        }
    }
}
