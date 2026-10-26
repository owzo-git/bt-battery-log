package com.owzo.btbatteryntfy

import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.owzo.btbatteryntfy.data.AppPreferences
import com.owzo.btbatteryntfy.data.AppSettings
import com.owzo.btbatteryntfy.data.StatusSnapshot
import com.owzo.btbatteryntfy.databinding.ActivityMainBinding
import com.owzo.btbatteryntfy.monitor.BatteryMonitorEngine
import com.owzo.btbatteryntfy.monitor.BatteryNotificationController
import com.owzo.btbatteryntfy.monitor.MonitoringWorkScheduler
import com.owzo.btbatteryntfy.monitor.shokz.ShokzComponentFormatter
import com.owzo.btbatteryntfy.monitor.shokz.ShokzOpenFitBatteryProvider
import com.owzo.btbatteryntfy.monitor.shokz.ShokzReadResult
import com.owzo.btbatteryntfy.monitor.shokz.ShokzReadTrigger
import com.owzo.btbatteryntfy.monitor.shokz.ShokzRefreshCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appPreferences: AppPreferences
    private val autoSaveHandler = Handler(Looper.getMainLooper())
    private var suppressAutoSave = false
    private var debugVisible = false

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
                appPreferences.appendDebugLog("Bluetooth permission granted. Monitoring enabled.")
                MonitoringWorkScheduler.enqueueSnapshot(applicationContext, reason = "permission_granted")
            } else {
                binding.monitoringSwitch.isChecked = false
                persistSettings(monitoringEnabled = false)
                appPreferences.appendDebugLog("Bluetooth permission denied. Monitoring remains disabled.")
                showSnackbar(getString(R.string.bluetooth_permission_required))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appPreferences = AppPreferences(applicationContext)
        appPreferences.rawPreferences().registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        debugVisible = savedInstanceState?.getBoolean(KEY_DEBUG_VISIBLE, false) ?: false
        populateForm(appPreferences.loadSettings())
        bindActions()
        refreshStatusOnly()
    }

    override fun onDestroy() {
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        appPreferences.rawPreferences().unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_DEBUG_VISIBLE, debugVisible)
        super.onSaveInstanceState(outState)
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
                appPreferences.appendDebugLog("Monitoring enabled.")
                MonitoringWorkScheduler.enqueueSnapshot(applicationContext, reason = "monitoring_enabled")
            } else {
                appPreferences.appendDebugLog("Monitoring disabled.")
                BatteryNotificationController.cancelMainNotification(applicationContext)
                appPreferences.clearConnectedNotificationState()
            }
        }

        binding.shokzVendorReadSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressAutoSave) {
                return@setOnCheckedChangeListener
            }

            persistSettings(monitoringEnabled = binding.monitoringSwitch.isChecked)
            appPreferences.appendDebugLog(
                if (isChecked) "Shokz left/right/case reads enabled." else "Shokz left/right/case reads disabled.",
            )
            if (binding.monitoringSwitch.isChecked) {
                // Enabling queues the once-per-connection read; disabling restores the single-% text.
                MonitoringWorkScheduler.enqueueSnapshot(
                    applicationContext,
                    reason = if (isChecked) "shokz_enabled" else "shokz_disabled",
                )
            }
        }

        setupAutosaveTextField(binding.disconnectDelayInput)

        binding.refreshNowButton.setOnClickListener {
            if (!binding.monitoringSwitch.isChecked) {
                showSnackbar(getString(R.string.enable_monitoring_first))
                return@setOnClickListener
            }

            appPreferences.appendDebugLog("Manual refresh requested.")
            MonitoringWorkScheduler.enqueueSnapshot(applicationContext, reason = "manual_refresh")
            showSnackbar(getString(R.string.refresh_scheduled))
        }

        binding.toggleDebugButton.setOnClickListener {
            debugVisible = !debugVisible
            applyDebugVisibility()
        }

        binding.clearLogsButton.setOnClickListener {
            appPreferences.clearLogs()
            refreshStatusOnly()
        }

        binding.shokzReadButton.setOnClickListener {
            readShokzBattery()
        }
    }

    private fun readShokzBattery() {
        if (!BatteryMonitorEngine.hasBluetoothConnectPermission(this)) {
            showSnackbar(getString(R.string.bluetooth_permission_required))
            return
        }

        binding.shokzReadButton.isEnabled = false
        binding.shokzDebugResultText.text = getString(R.string.shokz_debug_reading)
        lifecycleScope.launch {
            try {
                val devices = withContext(Dispatchers.IO) {
                    ShokzOpenFitBatteryProvider.findConnectedDevices(applicationContext)
                }
                if (devices.isEmpty()) {
                    appPreferences.appendDebugLog("Shokz read: no connected OpenFit found.")
                    binding.shokzDebugResultText.text = getString(R.string.shokz_debug_no_device)
                    return@launch
                }

                val sections = devices.mapNotNull { device ->
                    val label = BatteryMonitorEngine.displayName(BatteryMonitorEngine.safeDeviceName(device), null)
                    // Manual reads always run; the coordinator logs and stores the result.
                    ShokzRefreshCoordinator.refresh(applicationContext, device, ShokzReadTrigger.MANUAL)
                        ?.let { shokzResultText(label, it) }
                }
                binding.shokzDebugResultText.text = sections.joinToString(separator = "\n\n")
            } finally {
                binding.shokzReadButton.isEnabled = true
            }
        }
    }

    private fun shokzResultText(label: String, result: ShokzReadResult): String = buildString {
        appendLine(label)
        when (result) {
            is ShokzReadResult.Success -> {
                val status = result.status
                appendLine(
                    "L ${formatShokzPercent(status.left)} · R ${formatShokzPercent(status.right)} · " +
                        "Case ${formatShokzPercent(status.case)}",
                )
                appendLine(
                    "Sampled ${BatteryMonitorEngine.formatReadableTimestamp(status.sampledAtMs)} " +
                        "in ${result.elapsedMs} ms",
                )
                append(result.diagnostics.describe())
            }
            is ShokzReadResult.Failure -> {
                append("Failed: ${result.failure.label} after ${result.elapsedMs} ms")
                result.detail?.let { append("\n$it") }
                result.diagnostics?.let { append("\n${it.describe()}") }
            }
        }
    }

    private fun formatShokzPercent(value: Int?): String {
        return value?.let { "$it%" } ?: getString(R.string.shokz_debug_unknown)
    }

    private fun populateForm(settings: AppSettings) {
        suppressAutoSave = true
        binding.disconnectDelayInput.setText(settings.disconnectDelaySeconds.toString())
        binding.monitoringSwitch.isChecked = settings.monitoringEnabled
        binding.shokzVendorReadSwitch.isChecked = settings.shokzVendorReadEnabled
        suppressAutoSave = false
    }

    private fun refreshStatusOnly() {
        val status = appPreferences.getStatusSnapshot(
            defaultBatteryEvent = getString(R.string.no_recent_battery_event),
            defaultDisconnectSummary = getString(R.string.no_recent_disconnect_summary),
            defaultActivityFeedText = getString(R.string.no_activity_events),
            defaultDebugLogText = getString(R.string.no_debug_logs),
        )

        val settings = appPreferences.loadSettings()
        val permissionSummary = if (BatteryMonitorEngine.hasBluetoothConnectPermission(this)) {
            ""
        } else {
            " (${getString(R.string.permission_missing)})"
        }

        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val notificationsSummary = if (notificationsEnabled) "" else " (${getString(R.string.notifications_blocked)})"

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

        val activityFeed = status.activityFeedText.ifBlank { getString(R.string.no_activity_events) }
        val activityItems = parseActivityFeed(status.activityFeedText)
        binding.activityFeedText.text = activityFeed
        renderStatusCard(
            status = status,
            activityItems = activityItems,
            notificationsEnabled = notificationsEnabled,
        )
        renderShokzComponents(settings)
        renderActivityFeed(activityItems)
        binding.debugLogText.text = status.debugLogText.ifBlank { getString(R.string.no_debug_logs) }
        applyDebugVisibility()
    }

    private fun renderStatusCard(
        status: StatusSnapshot,
        activityItems: List<ActivityFeedItem>,
        notificationsEnabled: Boolean,
    ) {
        binding.notificationStateChip.text = getString(
            if (notificationsEnabled) R.string.notifications_ok else R.string.notifications_blocked,
        )

        val connectedState = appPreferences.getConnectedNotificationState()
        if (connectedState != null) {
            val deviceLabel = BatteryMonitorEngine.displayName(
                connectedState.deviceName,
                connectedState.deviceAddress,
            )
            binding.statusDeviceNameText.text = deviceLabel
            binding.statusSummaryText.text = getString(
                R.string.battery_connected_summary,
                connectedState.batteryPercent,
            )
            binding.statusTimestampText.text = getString(
                R.string.status_last_update,
                status.lastBatteryEvent.extractBatteryEventTime()
                    ?: activityItems.firstOrNull { it.deviceName == deviceLabel }?.timestamp
                    ?: getString(R.string.status_no_data),
            )
            binding.connectionStateChip.text = getString(R.string.status_connected)
            return
        }

        val latest = activityItems.firstOrNull()
        if (latest == null) {
            binding.statusDeviceNameText.text = getString(R.string.status_no_headset_seen)
            binding.statusSummaryText.text = getString(R.string.status_waiting_for_data)
            binding.statusTimestampText.text = getString(R.string.status_last_update_none)
            binding.connectionStateChip.text = getString(R.string.status_no_data)
            return
        }

        binding.statusDeviceNameText.text = latest.deviceName
        binding.statusSummaryText.text = if (latest.status == ActivityFeedStatus.DISCONNECTED) {
            getString(
                R.string.battery_status_summary,
                latest.batteryPercent,
                getString(R.string.status_last_seen_time, latest.timeOnly()),
            )
        } else {
            getString(R.string.battery_status_summary, latest.batteryPercent, latest.status.displayText(this))
        }
        binding.statusTimestampText.text = getString(
            if (latest.status == ActivityFeedStatus.DISCONNECTED) {
                R.string.status_disconnected_at
            } else {
                R.string.status_last_update
            },
            latest.timestamp,
        )
        binding.connectionStateChip.text = when (latest.status) {
            ActivityFeedStatus.DISCONNECTED -> getString(R.string.status_disconnected)
            ActivityFeedStatus.CONNECTED,
            ActivityFeedStatus.UPDATED,
            -> getString(R.string.status_connected)
            ActivityFeedStatus.NOTIFICATION_DISMISSED -> latest.status.displayText(this)
        }
    }

    /** Left/right/case from the vendor channel; "—" marks parts that were not read. */
    private fun renderShokzComponents(settings: AppSettings) {
        val lastDevice = appPreferences.getLastShokzDevice()
        if (!settings.shokzVendorReadEnabled || lastDevice == null) {
            binding.statusComponentsText.visibility = View.GONE
            return
        }

        val identity = lastDevice.first
        val allKnown = appPreferences.getShokzComponents(identity)
        val connected = appPreferences.getConnectedNotificationState()?.deviceIdentity == identity
        val fresh = if (connected) {
            BatteryMonitorEngine.currentShokzComponents(appPreferences, settings, identity)
        } else {
            null
        }
        binding.statusComponentsText.text = when {
            fresh != null && !fresh.isEmpty -> getString(
                R.string.shokz_components_fresh,
                ShokzComponentFormatter.labeled(fresh),
                BatteryMonitorEngine.formatReadableTimestamp(fresh.latestSampledAtMs),
            )
            !allKnown.isEmpty -> getString(
                R.string.shokz_components_last_known,
                ShokzComponentFormatter.labeled(allKnown),
                BatteryMonitorEngine.formatReadableTimestamp(allKnown.latestSampledAtMs),
            )
            else -> {
                binding.statusComponentsText.visibility = View.GONE
                return
            }
        }
        binding.statusComponentsText.visibility = View.VISIBLE
    }

    private fun renderActivityFeed(items: List<ActivityFeedItem>) {
        binding.activityFeedList.removeAllViews()
        binding.activityFeedEmptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.activityFeedList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        items.forEachIndexed { index, item ->
            if (index > 0) {
                binding.activityFeedList.addView(
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(1),
                        )
                        setBackgroundColor(
                            resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant),
                        )
                    },
                )
            }

            binding.activityFeedList.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(
                        0,
                        if (index == 0) 0 else dp(12),
                        0,
                        if (index == items.lastIndex) 0 else dp(12),
                    )
                    addView(activityFeedTextView(item.deviceName, primary = true))
                    addView(
                        activityFeedTextView(
                            getString(
                                R.string.activity_feed_battery_status,
                                item.batteryPercent,
                                item.status.displayText(this@MainActivity),
                            ),
                        ),
                    )
                    addView(activityFeedTextView(item.timestamp, secondary = true))
                },
            )
        }
    }

    private fun activityFeedTextView(
        value: String,
        primary: Boolean = false,
        secondary: Boolean = false,
    ): TextView {
        return TextView(this).apply {
            text = value
            setTextAppearance(
                when {
                    primary -> com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                    secondary -> com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                    else -> com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                },
            )
            setTextColor(
                resolveThemeColor(
                    if (secondary) {
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    } else {
                        com.google.android.material.R.attr.colorOnSurface
                    },
                ),
            )
            if (primary) {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        }
    }

    private fun parseActivityFeed(rawFeed: String): List<ActivityFeedItem> {
        return rawFeed.lines()
            .mapNotNull { line ->
                val match = ACTIVITY_FEED_LINE_REGEX.matchEntire(line.trim()) ?: return@mapNotNull null
                val batteryPercent = match.groupValues[3].toIntOrNull()?.takeIf { it in 0..100 }
                    ?: return@mapNotNull null
                ActivityFeedItem(
                    timestamp = match.groupValues[1],
                    deviceName = match.groupValues[2],
                    batteryPercent = batteryPercent,
                    status = ActivityFeedStatus.fromStoredLabel(match.groupValues[4]),
                )
            }
    }

    private fun applyDebugVisibility() {
        binding.debugSection.visibility = if (debugVisible) View.VISIBLE else View.GONE
        binding.toggleDebugButton.text =
            getString(if (debugVisible) R.string.hide_debug_log else R.string.show_debug_log)
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
            shokzVendorReadEnabled = binding.shokzVendorReadSwitch.isChecked,
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

    private fun resolveThemeColor(attribute: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attribute, typedValue, true)
        return typedValue.data
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun String.extractBatteryEventTime(): String? {
        return substringAfter(" @ ", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
    }

    private data class ActivityFeedItem(
        val timestamp: String,
        val deviceName: String,
        val batteryPercent: Int,
        val status: ActivityFeedStatus,
    ) {
        fun timeOnly(): String = timestamp.substringAfterLast(' ')
    }

    private enum class ActivityFeedStatus {
        CONNECTED,
        UPDATED,
        DISCONNECTED,
        NOTIFICATION_DISMISSED,
        ;

        fun displayText(activity: MainActivity): String {
            return when (this) {
                CONNECTED -> activity.getString(R.string.status_connected)
                UPDATED -> activity.getString(R.string.status_updated)
                DISCONNECTED -> activity.getString(R.string.status_disconnected)
                NOTIFICATION_DISMISSED -> activity.getString(R.string.status_notification_dismissed)
            }
        }

        companion object {
            fun fromStoredLabel(label: String): ActivityFeedStatus {
                return when (label.lowercase()) {
                    "connected" -> CONNECTED
                    "updated" -> UPDATED
                    "disconnected" -> DISCONNECTED
                    "notification dismissed" -> NOTIFICATION_DISMISSED
                    else -> UPDATED
                }
            }
        }
    }

    companion object {
        private const val AUTO_SAVE_DELAY_MS = 500L
        private const val KEY_DEBUG_VISIBLE = "debug_visible"
        private val ACTIVITY_FEED_LINE_REGEX =
            Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}) (.+) (\d{1,3})% \((.+)\)$""")
    }
}
