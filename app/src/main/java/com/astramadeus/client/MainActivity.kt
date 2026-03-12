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
import androidx.core.content.ContextCompat
import com.astramadeus.client.ui.MainApp
import com.astramadeus.client.ui.theme.AmadeusTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    private val snapshotExpiryMs = 5_000L

    private val _serviceEnabled = MutableStateFlow(false)
    val serviceEnabled: StateFlow<Boolean> = _serviceEnabled.asStateFlow()

    private val snapshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SnapshotBroadcasts.ACTION_SNAPSHOT_UPDATED -> {
                    // TODO: Notify compose of snapshot update
                }
                SnapshotBroadcasts.ACTION_SERVICE_STATUS_CHANGED -> {
                    _serviceEnabled.value = intent.getBooleanExtra(SnapshotBroadcasts.EXTRA_ENABLED, false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmadeusTheme {
                MainApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
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

        _serviceEnabled.value = SnapshotBroadcasts.latestServiceEnabled || isAccessibilityServiceEnabled()
    }

    override fun onPause() {
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
}
