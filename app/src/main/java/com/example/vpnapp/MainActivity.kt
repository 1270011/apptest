package com.example.vpnapp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.vpnapp.databinding.ActivityMainBinding
import com.wireguard.android.backend.Tunnel // For Tunnel.State enum
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val TAG = "MainActivity"
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private var selectedConfig: String? = null

    // THIS IS A PLACEHOLDER AND INSECURE CONFIGURATION. DO NOT USE IN PRODUCTION.
    // Used if user doesn't select a config file.
    private val SAMPLE_WG_CONFIG_FOR_INTENT_FALLBACK = """
[Interface]
PrivateKey = CLIENT_PRIVATE_KEY_REPLACE_ME_FALLBACK
Address = 10.0.0.3/24
DNS = 8.8.8.8

[Peer]
PublicKey = SERVER_PUBLIC_KEY_REPLACE_ME_FALLBACK
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = YOUR_SERVER_IP_OR_HOSTNAME_FALLBACK:PORT
"""

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            Log.d(TAG, "File URI received: $uri")
            selectedConfig = readTextFromUri(uri)
            if (selectedConfig != null) {
                Log.d(TAG, "Config loaded successfully.")
                // Log.d(TAG, "Config content:\n$selectedConfig") // Potentially sensitive
                binding.buttonLoadConfig.text = "Config Loaded" // Or update a status TextView
                Toast.makeText(this, "Configuration loaded.", Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "Failed to read config from URI.")
                binding.buttonLoadConfig.text = "Load Config (Error)"
                Toast.makeText(this, "Error loading configuration.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.d(TAG, "No file URI received.")
        }
    }


    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "VPN permission granted, starting VPN.")
            startVpnService()
        } else {
            Log.e(TAG, "VPN permission denied.")
            binding.textviewStatus.text = getString(R.string.status_error) + " (Permission Denied)"
            binding.buttonOn.isEnabled = true
            binding.buttonOff.isEnabled = false
        }
    }

    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stateString = intent?.getStringExtra(MyVpnService.EXTRA_VPN_STATE)
            val message = intent?.getStringExtra(MyVpnService.EXTRA_VPN_MESSAGE)
            Log.d(TAG, "Received VPN status: State=$stateString, Message=$message")

            binding.textviewStatus.text = message ?: stateString ?: getString(R.string.status_disconnected)

            when (stateString) {
                Tunnel.State.UP.name -> {
                    binding.buttonOn.isEnabled = false
                    binding.buttonOff.isEnabled = true
                }
                Tunnel.State.DOWN.name -> {
                    binding.buttonOn.isEnabled = true
                    binding.buttonOff.isEnabled = false
                }
                Tunnel.State.TOGGLING.name -> {
                    binding.buttonOn.isEnabled = false
                    binding.buttonOff.isEnabled = false
                }
                else -> { // Includes null or any other state
                    binding.buttonOn.isEnabled = true
                    binding.buttonOff.isEnabled = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        localBroadcastManager = LocalBroadcastManager.getInstance(this)

        binding.buttonOn.setOnClickListener {
            prepareAndConnectVpn()
        }

        binding.buttonOff.setOnClickListener {
            disconnectVpnService()
        }

        binding.buttonLoadConfig.setOnClickListener {
            // Using "*/*" initially for broader compatibility, can be "text/plain"
            // For .conf files, "application/octet-stream" might also be relevant if "text/plain" fails
            // or if specific .conf mime type is registered.
            openFileLauncher.launch(arrayOf("text/plain", "*/*"))
        }

        // Set initial button state
        binding.buttonOn.isEnabled = true
        binding.buttonOff.isEnabled = false
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MyVpnService.ACTION_VPN_STATUS_UPDATE)
        localBroadcastManager.registerReceiver(vpnStatusReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        localBroadcastManager.unregisterReceiver(vpnStatusReceiver)
    }

    private fun readTextFromUri(uri: Uri): String? {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    return reader.readText()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading text from URI: $uri", e)
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
        }
        return null
    }

    private fun prepareAndConnectVpn() {
        Log.d(TAG, "Prepare and Connect VPN button clicked")
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            Log.d(TAG, "VPN permission required. Launching permission intent.")
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            Log.d(TAG, "VPN permission already granted. Starting VPN service.")
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, MyVpnService::class.java).setAction(MyVpnService.ACTION_CONNECT)
        if (selectedConfig != null) {
            intent.putExtra(MyVpnService.EXTRA_CONFIG_STRING, selectedConfig)
            Log.d(TAG, "Starting VPN with user selected config.")
        } else {
            intent.putExtra(MyVpnService.EXTRA_CONFIG_STRING, SAMPLE_WG_CONFIG_FOR_INTENT_FALLBACK)
            Log.w(TAG, "Starting VPN with fallback sample config. User should load a config file.")
            Toast.makeText(this, "Using sample config. Please load a .conf file.", Toast.LENGTH_LONG).show()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun disconnectVpnService() {
        Log.d(TAG, "VPN OFF button clicked. Stopping VPN Service.")
        val intent = Intent(this, MyVpnService::class.java).setAction(MyVpnService.ACTION_DISCONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
