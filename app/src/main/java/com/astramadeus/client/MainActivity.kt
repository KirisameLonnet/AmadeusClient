package com.astramadeus.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private val snapshotExpiryMs = 5_000L

    private lateinit var serviceStatusValue: TextView
    private lateinit var latestSnapshotValue: TextView
    private lateinit var uiStatePreviewView: UiStatePreviewView
    private lateinit var toolsPanel: LinearLayout
    private lateinit var toggleToolsButton: ImageButton
    private var toolsExpanded: Boolean = true

    private val snapshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SnapshotBroadcasts.ACTION_SNAPSHOT_UPDATED -> {
                    renderLatestSnapshot()
                }

                SnapshotBroadcasts.ACTION_SERVICE_STATUS_CHANGED -> {
                    renderServiceStatus(intent.getBooleanExtra(SnapshotBroadcasts.EXTRA_ENABLED, false))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        serviceStatusValue = findViewById(R.id.serviceStatusValue)
        latestSnapshotValue = findViewById(R.id.latestSnapshotValue)
        uiStatePreviewView = findViewById(R.id.uiStatePreviewView)
        toolsPanel = findViewById(R.id.toolsPanel)
        toggleToolsButton = findViewById(R.id.toggleToolsButton)

        findViewById<Button>(R.id.openAccessibilitySettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.refreshStatusButton).setOnClickListener {
            renderServiceStatus(isAccessibilityServiceEnabled())
        }
        toggleToolsButton.setOnClickListener {
            setToolsExpanded(!toolsExpanded)
        }

        setToolsExpanded(true)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
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

        renderServiceStatus(SnapshotBroadcasts.latestServiceEnabled || isAccessibilityServiceEnabled())
        renderLatestSnapshot()
    }

    override fun onPause() {
        unregisterReceiver(snapshotReceiver)
        super.onPause()
    }

    private fun renderServiceStatus(enabled: Boolean) {
        serviceStatusValue.text = if (enabled) {
            getString(R.string.service_status_enabled)
        } else {
            getString(R.string.service_status_disabled)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

        val expectedService = "$packageName/${AmadeusAccessibilityService::class.java.name}"
        return enabledServices.contains(expectedService, ignoreCase = true)
    }

    private fun renderLatestSnapshot() {
        val rawSnapshot = SnapshotBroadcasts.getLatestSnapshot(snapshotExpiryMs)
        if (rawSnapshot == null) {
            latestSnapshotValue.text = getString(R.string.waiting_for_snapshot)
            uiStatePreviewView.submit(null)
            return
        }

        val preview = UiStateParser.parse(rawSnapshot)
        if (preview == null) {
            latestSnapshotValue.text = getString(R.string.preview_parse_failed)
            uiStatePreviewView.submit(null)
            return
        }

        latestSnapshotValue.text = getString(R.string.preview_ready)
        uiStatePreviewView.submit(preview)
    }

    private fun setToolsExpanded(expanded: Boolean) {
        toolsExpanded = expanded
        toolsPanel.visibility = if (expanded) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }

        toggleToolsButton.setImageResource(
            if (expanded) android.R.drawable.arrow_down_float else android.R.drawable.arrow_up_float,
        )
        toggleToolsButton.contentDescription = getString(
            if (expanded) R.string.collapse_tools else R.string.expand_tools,
        )
    }
}
