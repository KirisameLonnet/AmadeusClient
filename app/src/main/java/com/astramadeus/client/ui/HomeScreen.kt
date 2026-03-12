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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.astramadeus.client.PreviewControlsConfig
import com.astramadeus.client.R
import com.astramadeus.client.UiStateParser
import com.astramadeus.client.UiStatePreview
import com.astramadeus.client.UiStatePreviewView
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    latestSnapshot: String?,
    maxPullRateHz: Float,
    onUpdateMaxPullRateHz: (Float) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showVisionMosaic by remember {
        mutableStateOf(PreviewControlsConfig.getShowVisionMosaic(context))
    }
    var showNodeOverlay by remember {
        mutableStateOf(PreviewControlsConfig.getShowNodeOverlay(context))
    }
    var showOcrOverlay by remember {
        mutableStateOf(PreviewControlsConfig.getShowOcrOverlay(context))
    }
    var sliderValue by remember { mutableStateOf(maxPullRateHz) }

    LaunchedEffect(maxPullRateHz) {
        sliderValue = maxPullRateHz
    }

    var latestSnapshotId by remember { mutableStateOf<String?>(null) }
    var renderedFrame by remember { mutableStateOf<ProcessedPreviewFrame?>(null) }
    var previewFrameStatus by remember { mutableStateOf(PreviewFrameStatus.IDLE) }
    val inFlightSnapshotIds = remember { mutableSetOf<String>() }

    LaunchedEffect(latestSnapshot) {
        val rawSnapshot = latestSnapshot
        if (rawSnapshot.isNullOrBlank()) {
            latestSnapshotId = null
            previewFrameStatus = if (renderedFrame == null) {
                PreviewFrameStatus.IDLE
            } else {
                PreviewFrameStatus.SHOWING_LAST_FRAME
            }
            return@LaunchedEffect
        }

        val snapshotId = rawSnapshot.toStableSnapshotId()
        latestSnapshotId = snapshotId
        if (renderedFrame?.snapshotId == snapshotId || inFlightSnapshotIds.contains(snapshotId)) {
            return@LaunchedEffect
        }

        previewFrameStatus = if (renderedFrame == null) {
            PreviewFrameStatus.BUILDING_FIRST_FRAME
        } else {
            PreviewFrameStatus.BUILDING_NEXT_FRAME
        }
        inFlightSnapshotIds += snapshotId
        scope.launch {
            val preview = UiStateParser.parse(rawSnapshot)
            if (preview == null) {
                inFlightSnapshotIds -= snapshotId
                if (latestSnapshotId == snapshotId) {
                    previewFrameStatus = if (renderedFrame == null) {
                        PreviewFrameStatus.PARSE_FAILED
                    } else {
                        PreviewFrameStatus.SHOWING_LAST_FRAME
                    }
                }
                return@launch
            }
            val mergedResults = preview.visionSegments
                .mapNotNull { segment ->
                    segment.ocrText.takeIf { it.isNotBlank() }?.let { segment.id to it }
                }
                .toMap()

            inFlightSnapshotIds -= snapshotId
            if (latestSnapshotId == snapshotId) {
                renderedFrame = ProcessedPreviewFrame(snapshotId, preview, mergedResults)
                previewFrameStatus = PreviewFrameStatus.READY
            }
        }
    }

    val parsedPreview = renderedFrame?.preview
    val visionOcrResults = renderedFrame?.ocrResults.orEmpty()

    val snapshotHint = when {
        previewFrameStatus == PreviewFrameStatus.IDLE -> androidx.compose.ui.res.stringResource(R.string.waiting_for_snapshot)
        previewFrameStatus == PreviewFrameStatus.BUILDING_FIRST_FRAME -> androidx.compose.ui.res.stringResource(R.string.preview_building_first_frame)
        previewFrameStatus == PreviewFrameStatus.BUILDING_NEXT_FRAME -> androidx.compose.ui.res.stringResource(R.string.preview_building_next_frame)
        previewFrameStatus == PreviewFrameStatus.PARSE_FAILED -> androidx.compose.ui.res.stringResource(R.string.preview_parse_failed)
        previewFrameStatus == PreviewFrameStatus.SHOWING_LAST_FRAME -> androidx.compose.ui.res.stringResource(R.string.preview_showing_last_frame)
        else -> androidx.compose.ui.res.stringResource(R.string.preview_ready)
    }

    val receivedUiStateInfo = parsedPreview?.let { preview ->
        if (preview.activityName.isBlank()) {
            "${preview.packageName} | ${preview.eventType}"
        } else {
            "${preview.packageName} | ${preview.eventType}\n${preview.activityName}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ElevatedCard(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.preview_controls_title),
                    style = MaterialTheme.typography.titleMedium,
                )

                PreviewToggleRow(
                    label = androidx.compose.ui.res.stringResource(R.string.preview_toggle_mosaic),
                    checked = showVisionMosaic,
                    onCheckedChange = { checked ->
                        showVisionMosaic = checked
                        PreviewControlsConfig.setShowVisionMosaic(context, checked)
                    },
                )

                PreviewToggleRow(
                    label = androidx.compose.ui.res.stringResource(R.string.preview_toggle_nodes),
                    checked = showNodeOverlay,
                    onCheckedChange = { checked ->
                        showNodeOverlay = checked
                        PreviewControlsConfig.setShowNodeOverlay(context, checked)
                    },
                )

                PreviewToggleRow(
                    label = androidx.compose.ui.res.stringResource(R.string.preview_toggle_ocr),
                    checked = showOcrOverlay,
                    onCheckedChange = { checked ->
                        showOcrOverlay = checked
                        PreviewControlsConfig.setShowOcrOverlay(context, checked)
                    },
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    val rateText = String.format(Locale.US, "%.1f Hz", sliderValue)
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.preview_rate_title, rateText),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = PreviewControlsConfig.MIN_PULL_RATE_HZ..PreviewControlsConfig.MAX_PULL_RATE_HZ,
                        steps = ((PreviewControlsConfig.MAX_PULL_RATE_HZ - PreviewControlsConfig.MIN_PULL_RATE_HZ) /
                            PreviewControlsConfig.RATE_STEP_HZ).toInt() - 1,
                        onValueChangeFinished = {
                            val normalized = PreviewControlsConfig.normalizeRate(sliderValue)
                            sliderValue = normalized
                            PreviewControlsConfig.setMaxPullRateHz(context, normalized)
                            onUpdateMaxPullRateHz(normalized)
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        ElevatedCard(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.latest_event_label),
                    style = MaterialTheme.typography.titleMedium,
                )

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    factory = { context -> UiStatePreviewView(context) },
                    update = { view ->
                        view.submit(
                            preview = parsedPreview,
                            showVisionMosaic = showVisionMosaic,
                            showNodeOverlay = showNodeOverlay,
                            showOcrOverlay = showOcrOverlay,
                            visionOcrResults = visionOcrResults,
                        )
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (receivedUiStateInfo != null) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.received_ui_state_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = receivedUiStateInfo,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text(
            text = snapshotHint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

private fun String.toStableSnapshotId(): String {
    return hashCode().toUInt().toString(16)
}

private data class ProcessedPreviewFrame(
    val snapshotId: String,
    val preview: UiStatePreview?,
    val ocrResults: Map<String, String>,
)

private enum class PreviewFrameStatus {
    IDLE,
    BUILDING_FIRST_FRAME,
    BUILDING_NEXT_FRAME,
    READY,
    SHOWING_LAST_FRAME,
    PARSE_FAILED,
}

@Composable
private fun PreviewToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
