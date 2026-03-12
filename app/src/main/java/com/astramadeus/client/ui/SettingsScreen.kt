package com.astramadeus.client.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.ImageView
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.astramadeus.client.R
import com.astramadeus.client.OcrPipelineConfig
import com.astramadeus.client.UiFrameWebSocketClient
import com.astramadeus.client.VisionAssistConfig
import com.astramadeus.client.WebSocketPushConfig
import kotlinx.coroutines.delay

private enum class SettingsPage {
    Main,
    VisionApps,
    WebSocket,
}

private data class InstalledAppItem(
    val appName: String,
    val packageName: String,
    val icon: Drawable,
    val isSystemApp: Boolean,
)

private data class LocalAddressItem(
    val interfaceName: String,
    val hostAddress: String,
    val ipVersion: String,
)

@Composable
fun SettingsScreen(
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }

    when (page) {
        SettingsPage.Main -> SettingsMainPage(
            serviceEnabled = serviceEnabled,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onRefresh = onRefresh,
            onOpenVisionApps = { page = SettingsPage.VisionApps },
            onOpenWebSocket = { page = SettingsPage.WebSocket },
        )

        SettingsPage.VisionApps -> VisionAssistAppsPage(onBack = { page = SettingsPage.Main })
        SettingsPage.WebSocket -> WebSocketSettingsPage(onBack = { page = SettingsPage.Main })
    }
}

@Composable
private fun SettingsMainPage(
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onRefresh: () -> Unit,
    onOpenVisionApps: () -> Unit,
    onOpenWebSocket: () -> Unit,
) {
    val context = LocalContext.current
    var ocrParallelism by remember {
        mutableStateOf(OcrPipelineConfig.getMaxParallelism(context).toFloat())
    }

    val serviceStatus = if (serviceEnabled) {
        stringResource(R.string.service_status_enabled)
    } else {
        stringResource(R.string.service_status_disabled)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.service_status_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = serviceStatus,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (serviceEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row {
                    Button(onClick = onOpenAccessibilitySettings) {
                        Text(text = stringResource(R.string.open_accessibility_settings))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledIconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.refresh_status),
                        )
                    }
                }
            }
        }

        ElevatedCard(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenVisionApps)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.vision_assist_entry_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.vision_assist_entry_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.vision_assist_entry_title),
                )
            }
        }

        ElevatedCard(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenWebSocket)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.websocket_settings_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.websocket_settings_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.websocket_settings_title),
                )
            }
        }
    }
}

@Composable
private fun WebSocketSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current

    var enabled by remember { mutableStateOf(WebSocketPushConfig.isEnabled(context)) }
    var wsUrl by remember { mutableStateOf(WebSocketPushConfig.getUrl(context)) }
    val localAddresses by produceState(initialValue = emptyList<LocalAddressItem>(), context) {
        value = loadLocalAddresses()
    }

    val connectionState by produceState(initialValue = UiFrameWebSocketClient.connectionState) {
        while (true) {
            value = UiFrameWebSocketClient.connectionState
            delay(500)
        }
    }

    val lastError by produceState(initialValue = UiFrameWebSocketClient.lastError) {
        while (true) {
            value = UiFrameWebSocketClient.lastError
            delay(500)
        }
    }

    val stateLabel = when (connectionState) {
        UiFrameWebSocketClient.ConnectionState.DISCONNECTED -> stringResource(R.string.websocket_status_disconnected)
        UiFrameWebSocketClient.ConnectionState.CONNECTING -> stringResource(R.string.websocket_status_connecting)
        UiFrameWebSocketClient.ConnectionState.CONNECTED -> stringResource(R.string.websocket_status_connected)
        UiFrameWebSocketClient.ConnectionState.ERROR -> stringResource(R.string.websocket_status_error)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Text(
                text = stringResource(R.string.websocket_settings_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        ElevatedCard(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.websocket_enable_push),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            enabled = checked
                            WebSocketPushConfig.setEnabled(context, checked)
                            UiFrameWebSocketClient.syncConfig(context)
                        },
                    )
                }

                OutlinedTextField(
                    value = wsUrl,
                    onValueChange = { wsUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.websocket_server_url)) },
                )

                Button(
                    onClick = {
                        WebSocketPushConfig.setUrl(context, wsUrl)
                        wsUrl = WebSocketPushConfig.getUrl(context)
                        UiFrameWebSocketClient.syncConfig(context)
                    },
                ) {
                    Text(text = stringResource(R.string.websocket_save))
                }

                Text(
                    text = stringResource(R.string.websocket_current_status, stateLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                lastError?.takeIf { it.isNotBlank() }?.let { error ->
                    Text(
                        text = stringResource(R.string.websocket_last_error, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        ElevatedCard(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.websocket_local_ips_title),
                    style = MaterialTheme.typography.titleMedium,
                )

                if (localAddresses.isEmpty()) {
                    Text(
                        text = stringResource(R.string.websocket_local_ips_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    localAddresses.forEach { item ->
                        Text(
                            text = "${item.interfaceName} [${item.ipVersion}]: ${item.hostAddress}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        ElevatedCard(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.ocr_settings_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.ocr_parallelism_label, ocrParallelism.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = ocrParallelism,
                    onValueChange = { value ->
                        ocrParallelism = value.roundToInt().toFloat()
                    },
                    valueRange = OcrPipelineConfig.MIN_PARALLELISM.toFloat()..OcrPipelineConfig.MAX_PARALLELISM.toFloat(),
                    steps = OcrPipelineConfig.MAX_PARALLELISM - OcrPipelineConfig.MIN_PARALLELISM - 1,
                    onValueChangeFinished = {
                        val normalized = OcrPipelineConfig.normalizeParallelism(ocrParallelism.roundToInt())
                        ocrParallelism = normalized.toFloat()
                        OcrPipelineConfig.setMaxParallelism(context, normalized)
                    },
                )
                Text(
                    text = stringResource(R.string.ocr_parallelism_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VisionAssistAppsPage(onBack: () -> Unit) {
    val context = LocalContext.current

    var searchQuery by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSystemApps by remember { mutableStateOf(VisionAssistConfig.getShowSystemApps(context)) }
    var sortMode by remember { mutableStateOf(VisionAssistConfig.getSortMode(context)) }
    var enabledPackages by remember { mutableStateOf(VisionAssistConfig.getEnabledPackages(context)) }

    val allApps by produceState(initialValue = emptyList<InstalledAppItem>(), context) {
        value = loadInstalledApps(context.packageManager)
    }

    val filteredApps = remember(allApps, searchQuery, showSystemApps, sortMode, enabledPackages) {
        allApps
            .asSequence()
            .filter { app -> showSystemApps || !app.isSystemApp }
            .filter { app ->
                if (searchQuery.isBlank()) {
                    true
                } else {
                    val query = searchQuery.trim()
                    app.appName.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
                }
            }
            .sortedWith(sortComparator(sortMode, enabledPackages))
            .toList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Text(
                    text = stringResource(R.string.vision_assist_apps_title),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.vision_assist_selected_count, enabledPackages.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.vision_assist_menu),
                    )
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_app_name_asc)) },
                leadingIcon = {
                    if (sortMode == VisionAssistConfig.SORT_APP_NAME_ASC) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                    }
                },
                onClick = {
                    sortMode = VisionAssistConfig.SORT_APP_NAME_ASC
                    VisionAssistConfig.setSortMode(context, sortMode)
                    menuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_app_name_desc)) },
                leadingIcon = {
                    if (sortMode == VisionAssistConfig.SORT_APP_NAME_DESC) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                    }
                },
                onClick = {
                    sortMode = VisionAssistConfig.SORT_APP_NAME_DESC
                    VisionAssistConfig.setSortMode(context, sortMode)
                    menuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_package_asc)) },
                leadingIcon = {
                    if (sortMode == VisionAssistConfig.SORT_PACKAGE_ASC) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                    }
                },
                onClick = {
                    sortMode = VisionAssistConfig.SORT_PACKAGE_ASC
                    VisionAssistConfig.setSortMode(context, sortMode)
                    menuExpanded = false
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (showSystemApps) {
                            stringResource(R.string.hide_system_apps)
                        } else {
                            stringResource(R.string.show_system_apps)
                        },
                    )
                },
                onClick = {
                    showSystemApps = !showSystemApps
                    VisionAssistConfig.setShowSystemApps(context, showSystemApps)
                    menuExpanded = false
                },
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.search_apps_label)) },
            placeholder = { Text(stringResource(R.string.search_apps_placeholder)) },
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(filteredApps, key = { it.packageName }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AndroidView(
                        factory = { ctx -> ImageView(ctx) },
                        update = { view -> view.setImageDrawable(app.icon) },
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.appName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Checkbox(
                        checked = enabledPackages.contains(app.packageName),
                        onCheckedChange = { checked ->
                            VisionAssistConfig.setPackageEnabled(context, app.packageName, checked)
                            enabledPackages = VisionAssistConfig.getEnabledPackages(context)
                        },
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

private fun loadInstalledApps(packageManager: PackageManager): List<InstalledAppItem> {
    @Suppress("DEPRECATION")
    val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
    return apps.map { info ->
        InstalledAppItem(
            appName = packageManager.getApplicationLabel(info).toString(),
            packageName = info.packageName,
            icon = packageManager.getApplicationIcon(info),
            isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        )
    }
}

private fun sortComparator(sortMode: String, enabledPackages: Set<String>): Comparator<InstalledAppItem> {
    val selectedFirst = compareByDescending<InstalledAppItem> { enabledPackages.contains(it.packageName) }
    val orderedByMode = when (sortMode) {
        VisionAssistConfig.SORT_APP_NAME_DESC -> {
            compareByDescending<InstalledAppItem> { it.appName.lowercase() }
                .thenBy { it.packageName.lowercase() }
        }

        VisionAssistConfig.SORT_PACKAGE_ASC -> {
            compareBy<InstalledAppItem> { it.packageName.lowercase() }
                .thenBy { it.appName.lowercase() }
        }

        else -> {
            compareBy<InstalledAppItem> { it.appName.lowercase() }
                .thenBy { it.packageName.lowercase() }
        }
    }

    return selectedFirst.then(orderedByMode)
}

private fun loadLocalAddresses(): List<LocalAddressItem> {
    return runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val addresses = interfaces
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses
                    .toList()
                    .asSequence()
                    .filter { !it.isLoopbackAddress }
                    .map { address ->
                        val host = address.hostAddress?.substringBefore('%').orEmpty()
                        val ipVersion = when (address) {
                            is Inet4Address -> "IPv4"
                            is Inet6Address -> "IPv6"
                            else -> "IP"
                        }
                        LocalAddressItem(
                            interfaceName = networkInterface.name,
                            hostAddress = host,
                            ipVersion = ipVersion,
                        )
                    }
                    .filter { it.hostAddress.isNotBlank() }
            }

        val ipv4 = addresses.filter { it.ipVersion == "IPv4" }
        val ipv6 = addresses.filter { it.ipVersion == "IPv6" }

        (ipv4 + ipv6)
            .distinctBy { "${it.interfaceName}|${it.hostAddress}" }
            .sortedWith(compareBy({ if (it.ipVersion == "IPv4") 0 else 1 }, { it.interfaceName }, { it.hostAddress }))
            .toList()
    }.getOrDefault(emptyList())
}
