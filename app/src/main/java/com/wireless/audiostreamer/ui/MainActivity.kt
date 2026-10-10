package com.wireless.audiostreamer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import com.wireless.audiostreamer.network.LocalNetworkBinder
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

    private var connectionMode = "WIFI"
    private var isStreaming = false
    private var savedResultCode: Int = 0
    private var savedResultData: Intent? = null
    private var pendingPeerIp: String? = null
    private var pendingBtAddress: String? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            savedResultCode = result.resultCode
            savedResultData = result.data
            startServiceWithGrant(pendingPeerIp, pendingBtAddress)
        } else {
            Toast.makeText(this, "Screen cast Allow karo", Toast.LENGTH_LONG).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            LocalNetworkBinder.preferWifiNetwork(this)
            startWifiReady()
            ensureScreenCast()
        } else {
            Toast.makeText(this, "Permissions allow karo", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        LocalNetworkBinder.preferWifiNetwork(this)
        binding.tvMyInfo.text = "${Build.MODEL} · ${DiscoveryHelper.localIp()} (LAN only)"
        setupTabs()
        setupClicks()
        askPermissions()
    }

    private fun ensureScreenCast() {
        if (savedResultData != null && isStreaming) return
        if (savedResultData != null) {
            startServiceWithGrant(null, null)
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun connectToPeer(peerIp: String?, btAddress: String?, mode: String) {
        connectionMode = mode
        if (peerIp != null && !LocalNetworkBinder.isPrivateIp(peerIp)) {
            Toast.makeText(this, "Sirf local WiFi/Hotspot IP allowed", Toast.LENGTH_SHORT).show()
            return
        }
        if (savedResultData == null) {
            pendingPeerIp = peerIp
            pendingBtAddress = btAddress
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
            return
        }
        if (isStreaming) {
            startService(Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_SET_PEER
                putExtra(StreamingService.EXTRA_PEER_IP, peerIp)
                putExtra(StreamingService.EXTRA_BT_ADDRESS, btAddress)
                putExtra(StreamingService.EXTRA_CONNECTION_TYPE, mode)
            })
            showConnectedUI()
            Toast.makeText(this, "Local peer set (no internet)", Toast.LENGTH_SHORT).show()
        } else {
            startServiceWithGrant(peerIp, btAddress)
        }
    }

    private fun startServiceWithGrant(peerIp: String?, btAddress: String?) {
        val data = savedResultData ?: return
        val ip = peerIp ?: pendingPeerIp
        val bt = btAddress ?: pendingBtAddress
        pendingPeerIp = null
        pendingBtAddress = null

        LocalNetworkBinder.preferWifiNetwork(this)

        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_RESULT_CODE, savedResultCode)
            putExtra(StreamingService.EXTRA_RESULT_DATA, data)
            putExtra(StreamingService.EXTRA_CONNECTION_TYPE, connectionMode)
            putExtra(StreamingService.EXTRA_PEER_IP, ip)
            putExtra(StreamingService.EXTRA_BT_ADDRESS, bt)
            putExtra(StreamingService.EXTRA_ROLE, StreamingService.ROLE_BOTH)
        }
        ContextCompat.startForegroundService(this, intent)
        isStreaming = true
        showConnectedUI()
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
            LocalNetworkBinder.preferWifiNetwork(this)
            startWifiReady()
            Toast.makeText(this, "Scanning local LAN...", Toast.LENGTH_SHORT).show()
        }
        binding.btnStartListen.setOnClickListener {
            connectionMode = "WIFI"
            if (savedResultData != null) {
                if (!isStreaming) startServiceWithGrant(null, null)
            } else ensureScreenCast()
        }
        binding.btnRefreshBt.setOnClickListener { refreshBtList() }
        binding.btnBtSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        binding.btnRoleSender.setOnClickListener { setRole(StreamingService.ROLE_SENDER) }
        binding.btnRoleReceiver.setOnClickListener { setRole(StreamingService.ROLE_RECEIVER) }
        binding.btnDisconnect.setOnClickListener { stopStreamingFully() }
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
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else {
            LocalNetworkBinder.preferWifiNetwork(this)
            startWifiReady()
            ensureScreenCast()
        }
    }

    private fun startWifiReady() {
        discovery?.stop()
        discovery = DiscoveryHelper(this) { ip ->
            if (!LocalNetworkBinder.isPrivateIp(ip)) return@DiscoveryHelper
            runOnUiThread {
                if (!peers.containsKey(ip)) {
                    peers[ip] = "Phone"
                    rebuildPeerList()
                }
                if (isStreaming) {
                    startService(Intent(this@MainActivity, StreamingService::class.java).apply {
                        action = StreamingService.ACTION_SET_PEER
                        putExtra(StreamingService.EXTRA_PEER_IP, ip)
                        putExtra(StreamingService.EXTRA_CONNECTION_TYPE, "WIFI")
                    })
                }
            }
        }
        discovery?.start(uiScope)
        binding.tvMyInfo.text = "${Build.MODEL} · ${DiscoveryHelper.localIp()} (LAN only)"
    }

    private fun rebuildPeerList() {
        binding.listPeers.removeAllViews()
        if (peers.isEmpty()) {
            binding.tvNoPeers.visibility = View.VISIBLE
            return
        }
        binding.tvNoPeers.visibility = View.GONE
        peers.forEach { (ip, _) ->
            val row = LayoutInflater.from(this)
                .inflate(android.R.layout.simple_list_item_2, binding.listPeers, false)
            row.findViewById<TextView>(android.R.id.text1).text = "Phone (local)"
            row.findViewById<TextView>(android.R.id.text2).text = "IP: $ip  · Tap"
            row.setPadding(24, 28, 24, 28)
            row.setBackgroundColor(0xFFEDE7F6.toInt())
            row.setOnClickListener { connectToPeer(ip, null, "WIFI") }
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
            tv.text = "No paired phones"
            tv.setTextColor(0xFF999999.toInt())
            tv.setPadding(16, 24, 16, 24)
            binding.listBtDevices.addView(tv)
            return
        }
        bonded.forEach { device ->
            val row = LayoutInflater.from(this)
                .inflate(android.R.layout.simple_list_item_2, binding.listBtDevices, false)
            row.findViewById<TextView>(android.R.id.text1).text = device.name ?: "Unknown"
            row.findViewById<TextView>(android.R.id.text2).text = "${device.address} · BT only"
            row.setPadding(24, 28, 24, 28)
            row.setBackgroundColor(0xFFEDE7F6.toInt())
            row.setOnClickListener { connectToPeer(null, device.address, "BT") }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = 12
            binding.listBtDevices.addView(row, lp)
        }
    }

    private fun setRole(role: String) {
        startService(Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_SET_ROLE
            putExtra(StreamingService.EXTRA_ROLE, role)
        })
        binding.tvStatus.text = "Role: $role"
    }

    private fun showConnectedUI() {
        binding.roleBar.visibility = View.VISIBLE
        binding.tvConnectedCount.text = "Running (local only)"
        binding.tvStatus.text = "No internet used. WiFi/Hotspot/BT only."
        binding.tvRoleHint.text = "Hotspot without internet bhi chalega"
    }

    private fun stopStreamingFully() {
        stopService(Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        })
        isStreaming = false
        savedResultData = null
        savedResultCode = 0
        binding.roleBar.visibility = View.GONE
        binding.tvStatus.text = "Disconnected"
    }

    override fun onDestroy() {
        if (isFinishing) stopStreamingFully()
        discovery?.stop()
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (isStreaming) moveTaskToBack(true) else super.onBackPressed()
    }
}
