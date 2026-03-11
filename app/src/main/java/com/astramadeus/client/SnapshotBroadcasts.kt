package com.astramadeus.client

import android.content.Context
import android.content.Intent

object SnapshotBroadcasts {
    const val ACTION_SNAPSHOT_UPDATED = "com.astramadeus.client.ACTION_SNAPSHOT_UPDATED"
    const val ACTION_SERVICE_STATUS_CHANGED = "com.astramadeus.client.ACTION_SERVICE_STATUS_CHANGED"
    const val EXTRA_ENABLED = "extra_enabled"

    @Volatile
    var latestSnapshot: String? = null
        private set

    @Volatile
    var latestServiceEnabled: Boolean = false
        private set

    @Volatile
    private var latestSnapshotAt: Long = 0L

    fun publishSnapshot(context: Context, snapshot: String) {
        latestSnapshot = snapshot
        latestSnapshotAt = System.currentTimeMillis()
        context.sendBroadcast(
            Intent(ACTION_SNAPSHOT_UPDATED)
                .setPackage(context.packageName),
        )
    }

    fun publishStatus(context: Context, enabled: Boolean) {
        latestServiceEnabled = enabled
        context.sendBroadcast(
            Intent(ACTION_SERVICE_STATUS_CHANGED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_ENABLED, enabled),
        )
    }

    fun getLatestSnapshot(maxAgeMs: Long): String? {
        val snapshot = latestSnapshot ?: return null
        val age = System.currentTimeMillis() - latestSnapshotAt
        if (age > maxAgeMs) {
            latestSnapshot = null
            latestSnapshotAt = 0L
            return null
        }
        return snapshot
    }
}
