package com.wireless.audiostreamer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import com.wireless.audiostreamer.databinding.ActivityMainBinding
import com.wireless.audiostreamer.network.DiscoveryHelper
import com.wireless.audiostreamer.service.StreamingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var discovery: DiscoveryHelper? = null
    private val peers = ConcurrentHashMap<String, String>()

    private var pendingPeerIp: String? = null
    private var pendingBtAddress: String? = null
    private var connectionMode = "WIFI"

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_START
                putExtra(StreamingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(StreamingService.EXTRA_RESULT_DATA, result.data)
                putExtra(StreamingService.EXTRA_CONNECTION_TYPE, connectionMode)
                putExtra(StreamingService.EXTRA_PEER_IP, pendingPeerIp)
                putExtra(StreamingService.EXTRA_BT_ADDRESS, pendingBtAddress)
                putExtra(StreamingService.EXTRA_ROLE, StreamingService.ROLE_IDLE)
            }
            ContextCompat.startForegroundService(this, intent)
            showConnectedUI()
        } else {
            Toast.makeText(this, "Permission chahiye thi", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            when (connectionMode) {
                "WIFI" -> startWifiReady()
                "BT" -> refreshBtList()
            }
        } else {
            Toast.makeText(this, "Permissions allow karo", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvMyInfo.text = "${Build.MODEL} · ${DiscoveryHelper.localIp()}"

        setupTabs()
        setupClicks()
        askPermissions()
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.panelWifi.visibility = View.VISIBLE
                        binding.panelBluetooth.visibility = View.GONE
                        connectionMode = "WIFI"
                    }
                    1 -> {
                        binding.panelWifi.visibility = View.GONE
                        binding.panelBluetooth.visibility = View.VISIBLE
                        connectionMode = "BT"
                        refreshBtList()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupClicks() {
        binding.btnScan.setOnClickListener {
            connectionMode = "WIFI"
            startWifiReady()
            Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show()
        }

        binding.btnStartListen.setOnClickListener {
            connectionMode = "WIFI"
            pendingPeerIp = null
            requestProjectionAndStart()
        }

        binding.btnRefreshBt.setOnClickListener { refreshBtList() }

        binding.btnBtSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        binding.btnRoleSender.setOnClickListener {
            setRole(StreamingService.ROLE_SENDER)
        }

        binding.btnRoleReceiver.setOnClickListener {
            setRole(StreamingService.ROLE_RECEIVER)
        }

        binding.btnDisconnect.setOnClickListener {
            stopService(Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_STOP
            })
            hideConnectedUI()
        }
    }

    private fun askPermissions() {
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
            startWifiReady()
        }
    }

    private fun startWifiReady() {
        discovery?.stop()
        discovery = DiscoveryHelper { ip ->
            runOnUiThread {
                if (!peers.containsKey(ip)) {
                    peers[ip] = "Phone"
                    rebuildPeerList()
                }
            }
        }
        discovery?.start(uiScope)
        binding.tvMyInfo.text = "${Build.MODEL} · ${DiscoveryHelper.localIp()}"
    }

    private fun rebuildPeerList() {
        binding.listPeers.removeAllViews()
        if (peers.isEmpty()) {
            binding.tvNoPeers.visibility = View.VISIBLE
            return
        }
        binding.tvNoPeers.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        peers.forEach { (ip, _) ->
            val row = inflater.inflate(android.R.layout.simple_list_item_2, binding.listPeers, false)
            row.findViewById<TextView>(android.R.id.text1).text = "Phone"
            row.findViewById<TextView>(android.R.id.text2).text = "IP: $ip  · Tap to connect"
            row.setPadding(24, 28, 24, 28)
            row.setBackgroundColor(0xFFEDE7F6.toInt())
            row.setOnClickListener {
                pendingPeerIp = ip
                pendingBtAddress = null
                connectionMode = "WIFI"
                requestProjectionAndStart()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = 12
            binding.listPeers.addView(row, lp)
        }
        binding.tvConnectedCount.text = "Found (${peers.size})"
    }

    @SuppressLint("MissingPermission")
    private fun refreshBtList() {
        binding.listBtDevices.removeAllViews()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val bonded = try { adapter.bondedDevices } catch (_: Exception) { emptySet() }
        if (bonded.isNullOrEmpty()) {
            val tv = TextView(this)
            tv.text = "Koi paired phone nahi. Pehle Bluetooth settings se pair karo."
            tv.setTextColor(0xFF999999.toInt())
            tv.setPadding(16, 24, 16, 24)
            binding.listBtDevices.addView(tv)
            return
        }
        bonded.forEach { device ->
            val row = LayoutInflater.from(this)
                .inflate(android.R.layout.simple_list_item_2, binding.listBtDevices, false)
            row.findViewById<TextView>(android.R.id.text1).text = device.name ?: "Unknown"
            row.findViewById<TextView>(android.R.id.text2).text =
                "${device.address}  · Tap to connect"
            row.setPadding(24, 28, 24, 28)
            row.setBackgroundColor(0xFFEDE7F6.toInt())
            row.setOnClickListener {
                pendingBtAddress = device.address
                pendingPeerIp = null
                connectionMode = "BT"
                requestProjectionAndStart()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = 12
            binding.listBtDevices.addView(row, lp)
        }
    }

    private fun requestProjectionAndStart() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun setRole(role: String) {
        startService(Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_SET_ROLE
            putExtra(StreamingService.EXTRA_ROLE, role)
        })

        if (role == StreamingService.ROLE_SENDER) {
            binding.tvStatus.text = "Sender: is phone ki awaaz dusre pe (yahan mute)"
            binding.tvRoleHint.text = "Sender active — local speaker band, sirf Receiver pe sound"
            binding.btnRoleSender.alpha = 1f
            binding.btnRoleReceiver.alpha = 0.5f
        } else {
            binding.tvStatus.text = "Receiver: dusre phone ki awaaz yahan chalegi"
            binding.tvRoleHint.text = "Receiver active — yahan sound suno"
            binding.btnRoleSender.alpha = 0.5f
            binding.btnRoleReceiver.alpha = 1f
        }
    }

    private fun showConnectedUI() {
        binding.roleBar.visibility = View.VISIBLE
        binding.tvConnectedCount.text = "Connected (1)"
        binding.tvStatus.text = "Connected. Ab Sender ya Receiver choose karo."
        Toast.makeText(this, "Connected! Role choose karo", Toast.LENGTH_SHORT).show()
    }

    private fun hideConnectedUI() {
        binding.roleBar.visibility = View.GONE
        binding.tvConnectedCount.text = "Connected (0)"
        binding.tvStatus.text = "Nobody yet. Neeche se connect karo."
        pendingPeerIp = null
        pendingBtAddress = null
    }

    override fun onBackPressed() {
        if (binding.roleBar.visibility == View.VISIBLE) {
            moveTaskToBack(true)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        discovery?.stop()
        uiScope.cancel()
        super.onDestroy()
    }
}
