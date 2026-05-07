package com.owzo.btbatteryntfy

import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.snackbar.Snackbar
import com.owzo.btbatteryntfy.data.AppPreferences
import com.owzo.btbatteryntfy.data.AppSettings
import com.owzo.btbatteryntfy.databinding.ActivityMainBinding
import com.owzo.btbatteryntfy.monitor.BatteryMonitorEngine
import com.owzo.btbatteryntfy.monitor.BatteryNotificationController
import com.owzo.btbatteryntfy.monitor.MonitoringWorkScheduler

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appPreferences: AppPreferences
    private val autoSaveHandler = Handler(Looper.getMainLooper())
    private var suppressAutoSave = false
    private val autoSaveRunnable = Runnable {
        persistSettings(monitoringEnabled = binding.monitoringSwitch.isChecked)
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
        appPreferences.rawPreferences().registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        populateForm(appPreferences.loadSettings())
        refreshStatusOnly()
        bindActions()
    }

    override fun onDestroy() {
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        appPreferences.rawPreferences().unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        super.onDestroy()
    }

    private fun bindActions() {
        binding.monitoringSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressAutoSave) {
                return@setOnCheckedChangeListener
            }

            if (isChecked && !BatteryMonitorEngine.hasBluetoothConnectPermission(this)) {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return@setOnCheckedChangeListener
            }

            persistSettings(monitoringEnabled = isChecked)
            if (isChecked) {
                appPreferences.appendLog("Monitoring enabled.")
                MonitoringWorkScheduler.enqueueSnapshot(applicationContext, reason = "monitoring_enabled")
            } else {
                appPreferences.appendLog("Monitoring disabled.")
                BatteryNotificationController.cancelMainNotification(applicationContext)
            }
        }

        setupAutosaveTextField(binding.disconnectDelayInput)

        binding.saveButton.setOnClickListener {
            if (!binding.monitoringSwitch.isChecked) {
                showSnackbar("Enable monitoring first.")
                return@setOnClickListener
            }

            appPreferences.appendLog("Manual refresh requested.")
            MonitoringWorkScheduler.enqueueSnapshot(applicationContext, reason = "manual_refresh")
            showSnackbar("Refresh scheduled.")
        }

        binding.clearLogsButton.setOnClickListener {
            appPreferences.clearLogs()
            refreshStatusOnly()
        }
    }

    private fun populateForm(settings: AppSettings) {
        suppressAutoSave = true
        binding.disconnectDelayInput.setText(settings.disconnectDelaySeconds.toString())
        binding.monitoringSwitch.isChecked = settings.monitoringEnabled
        suppressAutoSave = false
    }

    private fun refreshStatusOnly() {
        val status = appPreferences.getStatusSnapshot(
            defaultBatteryEvent = getString(R.string.no_recent_battery_event),
            defaultDisconnectSummary = getString(R.string.no_recent_disconnect_summary),
            defaultLogText = getString(R.string.no_debug_logs),
        )

        val settings = appPreferences.loadSettings()
        val permissionSummary = if (BatteryMonitorEngine.hasBluetoothConnectPermission(this)) {
            ""
        } else {
            " (${getString(R.string.permission_missing)})"
        }

        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val notificationsSummary = if (notificationsEnabled) "" else " (notifications blocked)"

        binding.monitoringStatusText.text = buildString {
            append(
                if (settings.monitoringEnabled) {
                    getString(R.string.monitoring_enabled)
                } else {
                    getString(R.string.monitoring_disabled)
                },
            )
            append(permissionSummary)
            append(notificationsSummary)
        }
        binding.lastBatteryEventText.text = status.lastBatteryEvent
        binding.lastDisconnectSummaryText.text = status.lastDisconnectSummary
        binding.logText.text = status.logText.ifBlank { getString(R.string.no_debug_logs) }
    }

    private fun persistSettings(monitoringEnabled: Boolean) {
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        val settings = collectSettings(monitoringEnabled)
        appPreferences.saveSettings(settings)
    }

    private fun collectSettings(monitoringEnabled: Boolean): AppSettings {
        val disconnectDelaySeconds = parseInt(
            binding.disconnectDelayInput.text?.toString(),
            AppSettings.DEFAULT_DISCONNECT_DELAY_SECONDS,
            min = 5,
            max = 300,
        )

        return AppSettings(
            disconnectDelaySeconds = disconnectDelaySeconds,
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

    private fun setupAutosaveTextField(textField: com.google.android.material.textfield.TextInputEditText) {
        textField.doAfterTextChanged {
            if (suppressAutoSave) {
                return@doAfterTextChanged
            }

            autoSaveHandler.removeCallbacks(autoSaveRunnable)
            autoSaveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_DELAY_MS)
        }
    }

    companion object {
        private const val AUTO_SAVE_DELAY_MS = 500L
    }
}
