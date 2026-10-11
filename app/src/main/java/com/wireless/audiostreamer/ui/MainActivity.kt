package com.wireless.audiostreamer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import com.wireless.audiostreamer.R
import com.wireless.audiostreamer.databinding.ActivityMainBinding
import com.wireless.audiostreamer.network.DiscoveryHelper
import com.wireless.audiostreamer.network.LocalNetworkBinder
import com.wireless.audiostreamer.service.StreamingService
import com.wireless.audiostreamer.util.AppPrefs
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

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != StreamingService.BROADCAST_STATUS) return
            val connected = intent.getBooleanExtra(StreamingService.EXTRA_STATUS_CONNECTED, false)
            val text = intent.getStringExtra(StreamingService.EXTRA_STATUS_TEXT) ?: ""
            val mode = intent.getStringExtra(StreamingService.EXTRA_STATUS_MODE) ?: ""
            val peer = intent.getStringExtra(StreamingService.EXTRA_STATUS_PEER) ?: ""

            runOnUiThread {
                if (connected) {
                    binding.tvConnectedCount.text = "Connected \u2713"
                    binding.tvConnectedCount.setTextColor(Color.parseColor("#2E7D32"))
                    binding.tvStatus.text = when (mode) {
                        "BT" -> "Connected via Bluetooth\n$peer"
                        else -> "Connected via Wi-Fi / Hotspot\nPeer: $peer"
                    }
                    binding.tvStatus.setTextColor(Color.parseColor("#2E7D32"))
                    binding.roleBar.visibility = View.VISIBLE
                } else {
                    when {
                        text.contains("Waiting", true) -> {
                            binding.tvConnectedCount.text = "Waiting..."
                            binding.tvConnectedCount.setTextColor(Color.parseColor("#F9A825"))
                            binding.tvStatus.text = text
                            binding.tvStatus.setTextColor(Color.parseColor("#F9A825"))
                        }
                        text.contains("Disconnect", true) || text.contains("band", true) || text.contains("lost", true) -> {
                            binding.tvConnectedCount.text = "Disconnected"
                            binding.tvConnectedCount.setTextColor(Color.parseColor("#C62828"))
                            binding.tvStatus.text = text
                            binding.tvStatus.setTextColor(Color.parseColor("#888888"))
                        }
                        else -> binding.tvStatus.text = text
                    }
                }
            }
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            savedResultCode = result.resultCode
            savedResultData = result.data
            startServiceWithGrant(pendingPeerIp, pendingBtAddress)
        } else {
            Toast.makeText(this, "Screen cast Allow karo (audio ke liye zaroori)", Toast.LENGTH_LONG).show()
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
        if (!AppPrefs.isRoleChosen(this)) {
            startActivity(Intent(this, RoleSetupActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        LocalNetworkBinder.preferWifiNetwork(this)
        binding.tvMyInfo.text =
            "${Build.MODEL} \u00b7 ${DiscoveryHelper.localIp()} | Role: ${AppPrefs.getRole(this)}"

        val filter = IntentFilter(StreamingService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }

        setupTabs()
        setupClicks()
        askPermissions()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Settings")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun preferredServiceRole(): String {
        return when (AppPrefs.getRole(this)) {
            AppPrefs.ROLE_SENDER -> StreamingService.ROLE_SENDER
            AppPrefs.ROLE_RECEIVER -> StreamingService.ROLE_RECEIVER
            else -> StreamingService.ROLE_BOTH // Auto
        }
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
            Toast.makeText(this, "Sirf local IP", Toast.LENGTH_SHORT).show()
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
            binding.tvConnectedCount.text = "Connecting..."
            binding.tvConnectedCount.setTextColor(Color.parseColor("#F9A825"))
            binding.tvStatus.text =
                if (mode == "BT") "Bluetooth connect ho raha hai..." else "Peer set..."
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

        ContextCompat.startForegroundService(this, Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_RESULT_CODE, savedResultCode)
            putExtra(StreamingService.EXTRA_RESULT_DATA, data)
            putExtra(StreamingService.EXTRA_CONNECTION_TYPE, connectionMode)
            putExtra(StreamingService.EXTRA_PEER_IP, ip)
            putExtra(StreamingService.EXTRA_BT_ADDRESS, bt)
            putExtra(StreamingService.EXTRA_ROLE, preferredServiceRole())
            putExtra(StreamingService.EXTRA_SCREEN_SHARE, AppPrefs.wantScreenShare(this@MainActivity))
        })
        isStreaming = true
        binding.roleBar.visibility = View.VISIBLE
        binding.tvConnectedCount.text = "Waiting..."
        binding.tvConnectedCount.setTextColor(Color.parseColor("#F9A825"))
        binding.tvStatus.text = "Role: ${AppPrefs.getRole(this)} — waiting peer..."
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
        // Manual override still available
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
        binding.tvMyInfo.text =
            "${Build.MODEL} \u00b7 ${DiscoveryHelper.localIp()} | Role: ${AppPrefs.getRole(this)}"
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
            row.findViewById<TextView>(android.R.id.text2).text = "IP: $ip  \u00b7 Tap"
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
    }

    @SuppressLint("MissingPermission")
    private fun refreshBtList() {
        binding.listBtDevices.removeAllViews()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            val tv = TextView(this)
            tv.text = "Bluetooth ON karo, phir Refresh."
            tv.setTextColor(0xFFC62828.toInt())
            tv.setPadding(16, 24, 16, 24)
            binding.listBtDevices.addView(tv)
            return
        }
        val bonded = try { adapter.bondedDevices } catch (_: Exception) { emptySet() }
        if (bonded.isNullOrEmpty()) {
            val tv = TextView(this)
            tv.text = "Pehle system settings se phone pair karo, phir yahan Refresh."
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
                "${device.address} \u00b7 Tap to connect BT"
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
        binding.tvStatus.text = "Role override: $role"
    }

    private fun stopStreamingFully() {
        stopService(Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        })
        isStreaming = false
        savedResultData = null
        savedResultCode = 0
        binding.roleBar.visibility = View.GONE
        binding.tvConnectedCount.text = "Disconnected"
        binding.tvConnectedCount.setTextColor(Color.parseColor("#C62828"))
        binding.tvStatus.text = "Disconnected"
    }

    override fun onResume() {
        super.onResume()
        binding.tvMyInfo.text =
            "${Build.MODEL} \u00b7 ${DiscoveryHelper.localIp()} | Role: ${AppPrefs.getRole(this)}"
    }

    override fun onDestroy() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        if (isFinishing) stopStreamingFully()
        discovery?.stop()
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (isStreaming) moveTaskToBack(true) else super.onBackPressed()
    }
}
