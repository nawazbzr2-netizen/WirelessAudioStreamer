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

    // Cached screen-cast grant — dobara permission na maange
    private var savedResultCode: Int = 0
    private var savedResultData: Intent? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            savedResultCode = result.resultCode
            savedResultData = result.data
            startServiceWithGrant(null, null)
        } else {
            Toast.makeText(this, "Screen cast Allow karo", Toast.LENGTH_LONG).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
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
        binding.tvMyInfo.text = "${Build.MODEL} · ${DiscoveryHelper.localIp()}"
        setupTabs()
        setupClicks()
        askPermissions()
    }

    /** Sirf tab projection maango jab pehle se grant na ho */
    private fun ensureScreenCast() {
        if (savedResultData != null && isStreaming) {
            return // already running
        }
        if (savedResultData != null) {
            // grant hai, service start
            startServiceWithGrant(null, null)
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    /** Peer connect — projection dobara NAHI maango */
    private fun connectToPeer(peerIp: String?, btAddress: String?, mode: String) {
        connectionMode = mode
        if (savedResultData == null) {
            // pehli dafa
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
            // after grant will start without peer; user taps again OR we store pending
            pendingPeerIp = peerIp
            pendingBtAddress = btAddress
            return
        }
        if (isStreaming) {
            // Service already chal rahi — sirf peer update
            val i = Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_SET_PEER
                putExtra(StreamingService.EXTRA_PEER_IP, peerIp)
                putExtra(StreamingService.EXTRA_BT_ADDRESS, btAddress)
                putExtra(StreamingService.EXTRA_CONNECTION_TYPE, mode)
            }
            startService(i)
            showConnectedUI()
            Toast.makeText(this, "Peer set — sound chalna chahiye", Toast.LENGTH_SHORT).show()
        } else {
            startServiceWithGrant(peerIp, btAddress)
        }
    }

    private var pendingPeerIp: String? = null
    private var pendingBtAddress: String? = null

    private fun startServiceWithGrant(peerIp: String?, btAddress: String?) {
        val data = savedResultData ?: return
        val ip = peerIp ?: pendingPeerIp
        val bt = btAddress ?: pendingBtAddress
        pendingPeerIp = null
        pendingBtAddress = null

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
            startWifiReady()
            Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show()
        }

        binding.btnStartListen.setOnClickListener {
            connectionMode = "WIFI"
            if (savedResultData != null) {
                if (!isStreaming) startServiceWithGrant(null, null)
                else Toast.makeText(this, "Already running", Toast.LENGTH_SHORT).show()
            } else {
                ensureScreenCast()
            }
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
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startWifiReady()
            ensureScreenCast()
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
                // Auto-link peer jab service chal rahi ho — bina permission
                if (isStreaming && savedResultData != null) {
                    startService(Intent(this@MainActivity, StreamingService::class.java).apply {
                        action = StreamingService.ACTION_SET_PEER
                        putExtra(StreamingService.EXTRA_PEER_IP, ip)
                        putExtra(StreamingService.EXTRA_CONNECTION_TYPE, "WIFI")
                    })
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
                connectToPeer(ip, null, "WIFI")
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
            tv.text = "Koi paired phone nahi."
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
                connectToPeer(null, device.address, "BT")
            }
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
        binding.tvConnectedCount.text = "Running"
        binding.tvStatus.text = "Send + Receive active. Peer auto/tap se link."
        binding.tvRoleHint.text = "Default BOTH. Optional: Sender / Receiver"
    }

    private fun stopStreamingFully() {
        stopService(Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        })
        isStreaming = false
        // Screen-cast token clear — next open pe phir Allow
        savedResultData = null
        savedResultCode = 0
        binding.roleBar.visibility = View.GONE
        binding.tvConnectedCount.text = "Connected (0)"
        binding.tvStatus.text = "Disconnected"
    }

    override fun onDestroy() {
        if (isFinishing) {
            stopStreamingFully()
        }
        discovery?.stop()
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (isStreaming) moveTaskToBack(true) else super.onBackPressed()
    }
}
