package com.magmaskv.casttompv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var ipEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var debugSwitch: Switch
    private lateinit var debugLogText: TextView
    private var isDebugEnabled = false
    private val TAG = "CastToMPV"
    private val prefs by lazy { getSharedPreferences("cast_config", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        debugLog("=== APP STARTED ===")
        initializeViews()
        loadConfig()
        handleIntent(intent)
    }

    private fun initializeViews() {
        ipEdit = findViewById(R.id.ipEditText)
        portEdit = findViewById(R.id.portEditText)
        statusText = findViewById(R.id.statusTextView)
        debugSwitch = findViewById(R.id.debugSwitch)
        debugLogText = findViewById(R.id.debugLogTextView)

        findViewById<Button>(R.id.saveButton).setOnClickListener { saveConfig() }
        findViewById<Button>(R.id.testButton).setOnClickListener { testConnectionDetailed() }
        findViewById<Button>(R.id.testVideoButton).setOnClickListener { testVideo() }

        debugSwitch.setOnCheckedChangeListener { _, isChecked ->
            isDebugEnabled = isChecked
            saveConfig()
            debugLog("Debug mode: ${if (isChecked) "ENABLED" else "DISABLED"}")
            updateDebugSectionVisibility()
        }
    }

    private fun loadConfig() {
        ipEdit.setText(prefs.getString("pc_ip", "192.168.1.101") ?: "192.168.1.101")
        portEdit.setText(prefs.getString("pc_port", "8080") ?: "8080")
        isDebugEnabled = prefs.getBoolean("debug_enabled", false)
        debugSwitch.isChecked = isDebugEnabled
        statusText.text = "✅ Configured: ${ipEdit.text}:${portEdit.text}"
        updateDebugSectionVisibility()
    }

    private fun updateDebugSectionVisibility() {
        findViewById<TextView>(R.id.debugSectionLabel).visibility =
            if (isDebugEnabled) android.view.View.VISIBLE else android.view.View.GONE
        debugLogText.visibility =
            if (isDebugEnabled) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun saveConfig() {
        val ip = ipEdit.text.toString().trim()
        val port = portEdit.text.toString().trim()

        if (ip.isEmpty() || port.isEmpty()) {
            showToast("Complete all fields")
            return
        }

        prefs.edit().apply {
            putString("pc_ip", ip)
            putString("pc_port", port)
            putBoolean("debug_enabled", isDebugEnabled)
        }.apply()

        statusText.text = "✅ Saved: $ip:$port"
        showToast("Configuration saved")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return

        val url: String? = when {
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { extractUrl(it) }

            intent.action == Intent.ACTION_VIEW ->
                intent.dataString?.let { extractUrl(it) }

            else -> null
        }

        if (url != null) {
            statusText.text = "📡 Sending video..."
            sendToPc(url)
        } else if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW) {
            statusText.text = "❌ No URL found"
            showToast("No video URL found")
        }
    }

    private fun extractUrl(text: String): String? {
        val start = text.indexOf("http")
        return if (start != -1) text.substring(start, text.indexOf(' ', start).takeIf { it != -1 } ?: text.length).trim()
        else null
    }

    private fun testConnectionDetailed() = testEndpoint("test", "Testing detailed connection...", "🔄 Testing detailed connection...")
    private fun testVideo() = testEndpoint("testVideo", "Testing video playback...", "🎬 Testing video playback...")

    private fun testEndpoint(endpoint: String, debugMsg: String, statusMsg: String) {
        val ip = ipEdit.text.toString().trim()
        val port = portEdit.text.toString().trim()

        if (ip.isEmpty() || port.isEmpty()) {
            showToast("Configure IP and port first")
            return
        }

        statusText.text = statusMsg
        debugLog(debugMsg)

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val connection = URL("http://$ip:$port/$endpoint").openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("X-Device-Name", getDeviceName())
                    setRequestProperty("X-Device-Model", android.os.Build.MODEL)
                    setRequestProperty("X-Device-Android", android.os.Build.VERSION.RELEASE)
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                connection.outputStream.use { it.write("".toByteArray()) }
                val responseCode = connection.responseCode
                val responseText = connection.run {
                    if (responseCode >= 400) errorStream.bufferedReader().readText()
                    else inputStream.bufferedReader().readText()
                }

                runOnUiThread {
                    if (responseCode == 200) {
                        statusText.text = "✅ ${endpoint.capitalize()} completed!\nDevice: ${getDeviceName()}"
                        showToast("✅ ${endpoint.capitalize()} successful")
                    } else {
                        statusText.text = "⚠️ ${endpoint.capitalize()} error: $responseCode"
                        showToast("${endpoint.capitalize()} error: $responseCode")
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "❌ ${endpoint.capitalize()} failed: ${e.message}"
                    showToast("❌ ${endpoint.capitalize()} failed: ${e.message}")
                }
            }
        }
    }

    private fun sendToPc(url: String) {
        val ip = ipEdit.text.toString().trim()
        val port = portEdit.text.toString().trim()

        debugLog("Sending to PC ($ip:$port): $url")
        if (ip.isEmpty() || port.isEmpty()) {
            showToast("Configure IP and port first")
            statusText.text = "❌ Configure first"
            return
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                debugLog("Preparing POST to http://$ip:$port/play")
                val connection = URL("http://$ip:$port/play").openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("User-Agent", "CastToMPV/1.0")
                    setRequestProperty("X-Device-Name", getDeviceName())
                    setRequestProperty("X-Device-Model", android.os.Build.MODEL)
                    setRequestProperty("X-Device-Android", android.os.Build.VERSION.RELEASE)
                    connectTimeout = 10000
                    readTimeout = 10000
                }

                val deviceName = getDeviceName()
                debugLog("Device: $deviceName (${android.os.Build.MODEL})")

                val postData = "url=${URLEncoder.encode(url, "UTF-8")}&device=$deviceName"
                debugLog("POST data (first 100 chars): ${postData.take(100)}")
                debugLog("Sending data...")

                connection.outputStream.use { it.write(postData.toByteArray()) }

                val responseCode = connection.responseCode
                val responseText = connection.run {
                    if (responseCode >= 400) errorStream.bufferedReader().readText()
                    else inputStream.bufferedReader().readText()
                }

                debugLog("POST Response: $responseCode - ${connection.responseMessage}")
                debugLog("Response content: $responseText")

                runOnUiThread {
                    statusText.text = if (responseCode == 200) "✅ Video sent from $deviceName!" else "❌ Error: $responseCode"
                    showToast(if (responseCode == 200) "✅ Video sent to PC" else "❌ Error: $responseCode")
                }

                connection.disconnect()

            } catch (e: Exception) {
                debugLog("Error in POST: ${e.message}", true)
                runOnUiThread {
                    statusText.text = "❌ Error: ${e.message}"
                    showToast("❌ Error: ${e.message}")
                }
            }
        }
    }

    private fun getDeviceName(): String = prefs.getString("device_name", "")?.takeIf { it.isNotEmpty() }
        ?: try {
            when {
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1 ->
                    Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
                        ?: Settings.Secure.getString(contentResolver, "bluetooth_name")
                else -> Settings.Secure.getString(contentResolver, "bluetooth_name")
            } ?: android.os.Build.MODEL
        } catch (e: Exception) {
            android.os.Build.MODEL
        }

    private fun debugLog(message: String, forceLog: Boolean = false) {
        if (forceLog || isDebugEnabled) {
            Log.d(TAG, message)
            if (isDebugEnabled) runOnUiThread {
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val newText = "$timestamp: $message\n${debugLogText.text}"
                debugLogText.text = newText.split("\n").take(20).joinToString("\n")
            }
        }
    }

    private fun showToast(message: String) = runOnUiThread {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}