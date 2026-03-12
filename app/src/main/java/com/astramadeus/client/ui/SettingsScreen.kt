package com.astramadeus.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SettingsCard(
            title = "General Settings",
            items = listOf(
                SettingItemData("Enable Notifications", "Receive push notifications"),
                SettingItemData("Dark Mode", "Use dark theme across the app")
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
        SettingsCard(
            title = "Privacy",
            items = listOf(
                SettingItemData("Share Analytics", "Help us improve the app by sharing anonymous data"),
                SettingItemData("Location Services", "Allow app to access your location")
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
        SettingsCard(
            title = "Advanced",
            items = listOf(
                SettingItemData("Developer Mode", "Enable advanced debugging features"),
                SettingItemData("Experimental Features", "Try out new features before they are released")
            )
        )
    }
}

data class SettingItemData(val title: String, val subtitle: String)

@Composable
fun SettingsCard(title: String, items: List<SettingItemData>) {
    ElevatedCard(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp, start = 8.dp)
            )
            items.forEach { item ->
                SettingItem(item.title, item.subtitle)
            }
        }
    }
}

@Composable
fun SettingItem(title: String, subtitle: String) {
    var checked by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { checked = it }
        )
    }
}
