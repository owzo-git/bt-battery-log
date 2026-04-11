package com.owzo.btbatteryntfy

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.owzo.btbatteryntfy.data.AppPreferences
import com.owzo.btbatteryntfy.data.AppSettings
import com.owzo.btbatteryntfy.databinding.ActivityMainBinding
import com.owzo.btbatteryntfy.monitor.BatteryMonitorEngine
import com.owzo.btbatteryntfy.monitor.MonitoringWorkScheduler
import com.owzo.btbatteryntfy.net.NtfyClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appPreferences: AppPreferences
    private lateinit var executor: ExecutorService

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
        appPreferences.rawPreferences().unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun bindActions() {
        binding.saveButton.setOnClickListener {
            if (binding.monitoringSwitch.isChecked && !BatteryMonitorEngine.hasBluetoothConnectPermission(this)) {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                persistSettings(monitoringEnabled = binding.monitoringSwitch.isChecked)
                if (binding.monitoringSwitch.isChecked) {
                    MonitoringWorkScheduler.enqueueSnapshot(applicationContext, reason = "settings_saved")
                }
                showSnackbar("Settings saved.")
            }
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
        binding.serverInput.setText(settings.serverUrlOrEndpoint)
        binding.topicInput.setText(settings.topic)
        binding.authInput.setText(settings.authorizationHeader)
        binding.lowThresholdInput.setText(settings.lowThreshold.toString())
        binding.resetThresholdInput.setText(settings.resetThreshold.toString())
        binding.cooldownHoursInput.setText(settings.cooldownHours.toString())
        binding.monitoringSwitch.isChecked = settings.monitoringEnabled
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
                    append("Reset threshold: ${ephemeralSettings.resetThreshold}%\n")
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
        val resetThreshold = parseInt(
            binding.resetThresholdInput.text?.toString(),
            AppSettings.DEFAULT_RESET_THRESHOLD.coerceAtLeast(lowThreshold),
            min = lowThreshold,
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
            resetThreshold = resetThreshold,
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
}
