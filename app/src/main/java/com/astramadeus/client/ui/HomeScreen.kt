package com.astramadeus.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.astramadeus.client.R
import com.astramadeus.client.UiStateParser
import com.astramadeus.client.UiStatePreviewView

@Composable
fun HomeScreen(
    latestSnapshot: String?,
) {
    val parsedPreview = remember(latestSnapshot) {
        latestSnapshot?.let(UiStateParser::parse)
    }

    val snapshotHint = when {
        latestSnapshot.isNullOrBlank() -> androidx.compose.ui.res.stringResource(R.string.waiting_for_snapshot)
        parsedPreview == null -> androidx.compose.ui.res.stringResource(R.string.preview_parse_failed)
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
                    text = androidx.compose.ui.res.stringResource(R.string.latest_event_label),
                    style = MaterialTheme.typography.titleMedium,
                )
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    factory = { context -> UiStatePreviewView(context) },
                    update = { view -> view.submit(parsedPreview) },
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
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}
