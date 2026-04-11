package com.owzo.btbatteryntfy

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.snackbar.Snackbar
import com.owzo.btbatteryntfy.monitor.BatteryMonitorEngine
import com.owzo.btbatteryntfy.data.AppPreferences
import com.owzo.btbatteryntfy.data.AppSettings
import com.owzo.btbatteryntfy.monitor.MonitoringWorkScheduler
import com.owzo.btbatteryntfy.monitor.SystemEventReceiver
import com.owzo.btbatteryntfy.databinding.ActivityMainBinding
import com.owzo.btbatteryntfy.net.NtfyClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appPreferences: AppPreferences
    private lateinit var executor: ExecutorService
    private var foregroundDebugReceiverRegistered = false
    private var foregroundDebugEventCount = 0
    private val autoSaveHandler = Handler(Looper.getMainLooper())
    private var suppressAutoSave = false
    private val autoSaveRunnable = Runnable {
        persistSettings(monitoringEnabled = binding.monitoringSwitch.isChecked)
    }
    private val foregroundDebugIdleHintRunnable = Runnable {
        if (!foregroundDebugReceiverRegistered || foregroundDebugEventCount > 0) {
            return@Runnable
        }

        appPreferences.appendLog(
            "No Bluetooth events seen while the app was open. Use Refresh now to run a direct battery check.",
        )
    }
    private val foregroundDebugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val device = IntentCompat.getParcelableExtra(
                intent,
                BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice::class.java,
            )
            val deviceLabel = BatteryMonitorEngine.displayName(
                BatteryMonitorEngine.safeDeviceName(device),
                device?.address,
            )
            val message = summarizeForegroundDebugEvent(action, intent, deviceLabel) ?: return
            foregroundDebugEventCount += 1
            appPreferences.appendLog(message)
        }
    }

    private val preferenceChangeListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            refreshStatusOnly()
        }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                persistSettings(monitoringEnabled = true)
                appPreferences.appendLog("Bluetooth permission granted. Monitoring enabled.")
                MonitoringWorkScheduler.enqueueSnapshot(applicationContext, reason = "permission_granted")
            } else {
                binding.monitoringSwitch.isChecked = false
                persistSettings(monitoringEnabled = false)
                appPreferences.appendLog("Bluetooth permission denied. Monitoring remains disabled.")
                showSnackbar("Bluetooth permission is required for monitoring.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appPreferences = AppPreferences(applicationContext)
        executor = Executors.newSingleThreadExecutor()
        appPreferences.rawPreferences().registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        populateForm(appPreferences.loadSettings())
        refreshStatusOnly()
        bindActions()
    }

    override fun onDestroy() {
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        appPreferences.rawPreferences().unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        registerForegroundDebugReceiver()
    }

    override fun onStop() {
        unregisterForegroundDebugReceiver()
        super.onStop()
    }

    private fun bindActions() {
        binding.monitoringSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressAutoSave) {
                return@setOnCheckedChangeListener
            }

            if (isChecked && !BatteryMonitorEngine.hasBluetoothConnectPermission(this)) {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                persistSettings(monitoringEnabled = isChecked)
                if (isChecked) {
                    val settings = appPreferences.loadSettings()
                    appPreferences.appendLog(
                        "Monitoring enabled from UI. low=${settings.lowThreshold}% cooldown=${settings.cooldownHours}h. Scheduling Bluetooth snapshot.",
                    )
                    MonitoringWorkScheduler.enqueueSnapshot(applicationContext, reason = "settings_saved")
                } else {
                    appPreferences.appendLog("Monitoring disabled from UI.")
                }
            }
        }

        setupAutosaveTextField(binding.serverInput)
        setupAutosaveTextField(binding.topicInput)
        setupAutosaveTextField(binding.authInput)
        setupAutosaveTextField(binding.lowThresholdInput)
        setupAutosaveTextField(binding.cooldownHoursInput)

        binding.saveButton.setOnClickListener {
            if (!binding.monitoringSwitch.isChecked) {
                showSnackbar("Enable monitoring first.")
                return@setOnClickListener
            }

            appPreferences.appendLog("Manual refresh requested from UI. Scheduling Bluetooth snapshot.")
            MonitoringWorkScheduler.enqueueSnapshot(applicationContext, reason = "manual_refresh")
            showSnackbar("Refresh scheduled.")
        }

        binding.testButton.setOnClickListener {
            sendTestNotification()
        }

        binding.clearLogsButton.setOnClickListener {
            appPreferences.clearLogs()
            refreshStatusOnly()
        }
    }

    private fun populateForm(settings: AppSettings) {
        suppressAutoSave = true
        binding.serverInput.setText(settings.serverUrlOrEndpoint)
        binding.topicInput.setText(settings.topic)
        binding.authInput.setText(settings.authorizationHeader)
        binding.lowThresholdInput.setText(settings.lowThreshold.toString())
        binding.cooldownHoursInput.setText(settings.cooldownHours.toString())
        binding.monitoringSwitch.isChecked = settings.monitoringEnabled
        suppressAutoSave = false
    }

    private fun refreshStatusOnly() {
        val status = appPreferences.getStatusSnapshot(
            defaultBatteryEvent = getString(R.string.no_recent_battery_event),
            defaultSendResult = getString(R.string.no_recent_send_result),
            defaultLogText = getString(R.string.no_debug_logs),
        )

        val settings = appPreferences.loadSettings()
        val permissionSummary = if (BatteryMonitorEngine.hasBluetoothConnectPermission(this)) {
            ""
        } else {
            " (${getString(R.string.permission_missing)})"
        }
        binding.monitoringStatusText.text = buildString {
            append(
                if (settings.monitoringEnabled) {
                    getString(R.string.monitoring_enabled)
                } else {
                    getString(R.string.monitoring_disabled)
                },
            )
            append(permissionSummary)
        }
        binding.lastBatteryEventText.text = status.lastBatteryEvent
        binding.lastSendResultText.text = status.lastSendResult
        binding.logText.text = status.logText.ifBlank { getString(R.string.no_debug_logs) }
    }

    private fun sendTestNotification() {
        val ephemeralSettings = collectSettings(monitoringEnabled = appPreferences.loadSettings().monitoringEnabled)
        executor.execute {
            val endpoint = NtfyClient.resolveEndpoint(
                ephemeralSettings.serverUrlOrEndpoint,
                ephemeralSettings.topic,
            )

            if (endpoint == null) {
                appPreferences.updateLastSendResult("Last ntfy send result: invalid ntfy URL or topic")
                appPreferences.appendLog("Test notification skipped because ntfy is not configured correctly.")
                runOnUiThread {
                    showSnackbar("Set a valid ntfy URL first.")
                }
                return@execute
            }

            val now = System.currentTimeMillis()
            val result = NtfyClient.send(
                endpoint = endpoint,
                authorizationHeader = ephemeralSettings.authorizationHeader,
                title = "BT Battery Ntfy test",
                message = buildString {
                    append("BT Battery Ntfy test notification\n\n")
                    append("Timestamp: ")
                    append(com.owzo.btbatteryntfy.monitor.BatteryMonitorEngine.formatTimestamp(now))
                    append('\n')
                    append("Low threshold: ${ephemeralSettings.lowThreshold}%\n")
                    append("Cooldown: ${ephemeralSettings.cooldownHours} hour(s)")
                },
            )

            if (result.success) {
                appPreferences.updateLastSendResult("Last ntfy send result: test notification sent")
                appPreferences.appendLog("Test ntfy notification sent successfully.")
            } else {
                appPreferences.updateLastSendResult("Last ntfy send result: ${result.details}")
                appPreferences.appendLog("Test ntfy notification failed: ${result.details}.")
            }

            runOnUiThread {
                showSnackbar(
                    if (result.success) "Test notification sent." else "Test notification failed.",
                )
            }
        }
    }

    private fun persistSettings(monitoringEnabled: Boolean) {
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        val settings = collectSettings(monitoringEnabled)
        appPreferences.saveSettings(settings)
    }

    private fun collectSettings(monitoringEnabled: Boolean): AppSettings {
        val lowThreshold = parseInt(
            binding.lowThresholdInput.text?.toString(),
            AppSettings.DEFAULT_LOW_THRESHOLD,
            min = 1,
            max = 100,
        )
        val cooldownHours = parseInt(
            binding.cooldownHoursInput.text?.toString(),
            AppSettings.DEFAULT_COOLDOWN_HOURS,
            min = 1,
            max = 720,
        )

        return AppSettings(
            serverUrlOrEndpoint = binding.serverInput.text?.toString().orEmpty().trim(),
            topic = binding.topicInput.text?.toString().orEmpty().trim(),
            authorizationHeader = binding.authInput.text?.toString().orEmpty().trim(),
            lowThreshold = lowThreshold,
            cooldownHours = cooldownHours,
            monitoringEnabled = monitoringEnabled,
        )
    }

    private fun parseInt(
        rawValue: String?,
        defaultValue: Int,
        min: Int,
        max: Int,
    ): Int {
        return rawValue?.trim()?.toIntOrNull()?.coerceIn(min, max) ?: defaultValue.coerceIn(min, max)
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun registerForegroundDebugReceiver() {
        if (foregroundDebugReceiverRegistered) {
            return
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(AppConstants.ACTION_BATTERY_LEVEL_CHANGED)
            addAction(AppConstants.ACTION_HF_INDICATORS_VALUE_CHANGED)
            addAction(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
        }

        ContextCompat.registerReceiver(
            this,
            foregroundDebugReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        foregroundDebugReceiverRegistered = true
        foregroundDebugEventCount = 0
        autoSaveHandler.removeCallbacks(foregroundDebugIdleHintRunnable)

        val settings = appPreferences.loadSettings()
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothState = when {
            bluetoothManager?.adapter == null -> "unavailable"
            bluetoothManager.adapter?.isEnabled == true -> "on"
            else -> "off"
        }
        val permissionState = if (BatteryMonitorEngine.hasBluetoothConnectPermission(this)) {
            "granted"
        } else {
            "missing"
        }

        appPreferences.appendLog(
            "App open. Monitoring=${settings.monitoringEnabled}, permission=$permissionState, Bluetooth=$bluetoothState.",
        )

        if (settings.monitoringEnabled && permissionState == "granted" && bluetoothState == "on") {
            appPreferences.appendLog("App opened. Checking connected devices.")
            MonitoringWorkScheduler.enqueueSnapshot(applicationContext, reason = "activity_visible")
        }

        autoSaveHandler.postDelayed(foregroundDebugIdleHintRunnable, FOREGROUND_DEBUG_IDLE_HINT_DELAY_MS)
    }

    private fun unregisterForegroundDebugReceiver() {
        if (!foregroundDebugReceiverRegistered) {
            return
        }

        autoSaveHandler.removeCallbacks(foregroundDebugIdleHintRunnable)
        unregisterReceiver(foregroundDebugReceiver)
        foregroundDebugReceiverRegistered = false
    }

    private fun setupAutosaveTextField(textField: com.google.android.material.textfield.TextInputEditText) {
        textField.doAfterTextChanged {
            if (suppressAutoSave) {
                return@doAfterTextChanged
            }

            autoSaveHandler.removeCallbacks(autoSaveRunnable)
            autoSaveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_DELAY_MS)
        }
    }

    private fun summarizeForegroundDebugEvent(
        action: String,
        intent: Intent,
        deviceLabel: String,
    ): String? {
        return when (action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> "Bluetooth turned on."
                    BluetoothAdapter.STATE_OFF -> "Bluetooth turned off."
                    else -> null
                }
            }

            BluetoothDevice.ACTION_ACL_CONNECTED ->
                "$deviceLabel connected."

            AppConstants.ACTION_BATTERY_LEVEL_CHANGED -> {
                val battery = intent.getIntExtra(AppConstants.EXTRA_BATTERY_LEVEL, -1)
                if (battery in 0..100) {
                    "$deviceLabel battery broadcast: $battery%."
                } else {
                    null
                }
            }

            AppConstants.ACTION_HF_INDICATORS_VALUE_CHANGED -> {
                val indicatorId = intent.getIntExtra(AppConstants.EXTRA_HF_INDICATOR_ID, -1)
                val value = intent.getIntExtra(AppConstants.EXTRA_HF_INDICATOR_VALUE, -1)
                if (indicatorId == AppConstants.HF_BATTERY_INDICATOR_ID && value in 0..100) {
                    "$deviceLabel headset battery: $value%."
                } else {
                    null
                }
            }

            BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> {
                val value = SystemEventReceiver.parseVendorSpecificBattery(intent)
                if (value in 0..100) {
                    "$deviceLabel headset battery: $value%."
                } else {
                    null
                }
            }

            else -> null
        }
    }

    companion object {
        private const val AUTO_SAVE_DELAY_MS = 500L
        private const val FOREGROUND_DEBUG_IDLE_HINT_DELAY_MS = 8000L
    }
}
