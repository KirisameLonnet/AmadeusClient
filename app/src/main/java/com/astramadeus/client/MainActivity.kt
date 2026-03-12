package com.astramadeus.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.astramadeus.client.ui.MainApp
import com.astramadeus.client.ui.theme.AmadeusTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Timer
import java.util.TimerTask

class MainActivity : ComponentActivity() {

    private val snapshotExpiryMs = 5_000L
    private var statusRefreshIntervalMs = 1000L
    private var snapshotRequestMinIntervalMs = 1000L

    private val _serviceEnabled = MutableStateFlow(false)
    val serviceEnabled: StateFlow<Boolean> = _serviceEnabled.asStateFlow()

    private val _latestSnapshot = MutableStateFlow<String?>(null)
    val latestSnapshot: StateFlow<String?> = _latestSnapshot.asStateFlow()

    private val _maxPullRateHz = MutableStateFlow(1.0f)
    val maxPullRateHz: StateFlow<Float> = _maxPullRateHz.asStateFlow()

    private var statusTimer: Timer? = null
    private var lastSnapshotRequestAt: Long = 0L

    private val snapshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SnapshotBroadcasts.ACTION_SNAPSHOT_UPDATED -> {
                    _latestSnapshot.value = SnapshotBroadcasts.getLatestSnapshot(snapshotExpiryMs)
                }
                SnapshotBroadcasts.ACTION_SERVICE_STATUS_CHANGED -> {
                    _serviceEnabled.value = intent.getBooleanExtra(SnapshotBroadcasts.EXTRA_ENABLED, false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncRateSettingsFromConfig()
        UiFrameWebSocketClient.syncConfig(this)
        enableEdgeToEdge()
        setContent {
            val serviceEnabledState by serviceEnabled.collectAsState()
            val latestSnapshotState by latestSnapshot.collectAsState()
            val maxPullRateHzState by maxPullRateHz.collectAsState()

            AmadeusTheme {
                MainApp(
                    serviceEnabled = serviceEnabledState,
                    latestSnapshot = latestSnapshotState,
                    maxPullRateHz = maxPullRateHzState,
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                    onRefreshStatus = ::refreshStatus,
                    onUpdateMaxPullRateHz = ::updateMaxPullRateHz,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        UiFrameWebSocketClient.syncConfig(this)
        val filter = IntentFilter().apply {
            addAction(SnapshotBroadcasts.ACTION_SNAPSHOT_UPDATED)
            addAction(SnapshotBroadcasts.ACTION_SERVICE_STATUS_CHANGED)
        }

        ContextCompat.registerReceiver(
            this,
            snapshotReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // 启动定时状态检查
        syncRateSettingsFromConfig()
        startStatusTimer()
        
        _serviceEnabled.value = SnapshotBroadcasts.latestServiceEnabled || isAccessibilityServiceEnabled()
        _latestSnapshot.value = SnapshotBroadcasts.getLatestSnapshot(snapshotExpiryMs)
    }

    override fun onPause() {
        stopStatusTimer()
        unregisterReceiver(snapshotReceiver)
        super.onPause()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

        val expectedService = "$packageName/${AmadeusAccessibilityService::class.java.name}"
        return enabledServices.contains(expectedService, ignoreCase = true)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun refreshStatus() {
        syncRateSettingsFromConfig()
        _serviceEnabled.value = SnapshotBroadcasts.latestServiceEnabled || isAccessibilityServiceEnabled()
        _latestSnapshot.value = SnapshotBroadcasts.getLatestSnapshot(snapshotExpiryMs)

        if (_serviceEnabled.value && _latestSnapshot.value == null) {
            val now = System.currentTimeMillis()
            if (now - lastSnapshotRequestAt >= snapshotRequestMinIntervalMs) {
                SnapshotBroadcasts.requestSnapshot(this)
                lastSnapshotRequestAt = now
            }
        }
    }

    private fun updateMaxPullRateHz(rateHz: Float) {
        val normalizedRate = PreviewControlsConfig.normalizeRate(rateHz)
        PreviewControlsConfig.setMaxPullRateHz(this, normalizedRate)
        syncRateSettingsFromConfig()
        startStatusTimer()
    }

    private fun syncRateSettingsFromConfig() {
        val configuredRate = PreviewControlsConfig.getMaxPullRateHz(this)
        _maxPullRateHz.value = configuredRate
        val intervalMs = PreviewControlsConfig.toIntervalMs(configuredRate)
        statusRefreshIntervalMs = intervalMs
        snapshotRequestMinIntervalMs = intervalMs
    }

    private fun startStatusTimer() {
        stopStatusTimer() // 确保之前的定时器已停止
        statusTimer = Timer()
        statusTimer?.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() {
                    runOnUiThread {
                        refreshStatus()
                    }
                }
            },
            0,
            statusRefreshIntervalMs
        )
    }

    private fun stopStatusTimer() {
        statusTimer?.cancel()
        statusTimer = null
    }
}
