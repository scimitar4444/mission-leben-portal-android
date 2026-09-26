package de.missionleben.portal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.missionleben.portal.R
import de.missionleben.portal.push.PushReliabilityStatus

@Composable
internal fun PushReliabilityPanel(
    status: PushReliabilityStatus,
    onNotifications: () -> Unit,
    onBattery: () -> Unit,
    onManufacturer: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                androidx.compose.ui.res.stringResource(R.string.push_reliability_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                androidx.compose.ui.res.stringResource(R.string.push_reliability_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!status.hasSubscription) {
                Text(androidx.compose.ui.res.stringResource(R.string.push_reliability_not_registered))
            }
            Text(
                androidx.compose.ui.res.stringResource(
                    if (status.notificationsAllowed) R.string.push_reliability_notifications_ok
                    else R.string.push_reliability_notifications_off,
                ),
            )
            if (!status.notificationsAllowed) {
                OutlinedButton(onClick = onNotifications) {
                    Text(androidx.compose.ui.res.stringResource(R.string.push_reliability_open_notifications))
                }
            }
            Text(
                androidx.compose.ui.res.stringResource(
                    if (status.batteryExempt) R.string.push_reliability_battery_ok
                    else R.string.push_reliability_battery_limited,
                ),
            )
            if (!status.batteryExempt) {
                OutlinedButton(onClick = onBattery) {
                    Text(androidx.compose.ui.res.stringResource(R.string.push_reliability_allow_battery))
                }
            }
            if (status.samsung || status.tcl) {
                Spacer(Modifier.height(2.dp))
                Text(
                    androidx.compose.ui.res.stringResource(
                        if (status.samsung) R.string.push_reliability_samsung_hint
                        else R.string.push_reliability_tcl_hint,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onManufacturer) {
                    Text(androidx.compose.ui.res.stringResource(R.string.push_reliability_open_manufacturer))
                }
            }
            Text(
                androidx.compose.ui.res.stringResource(R.string.push_reliability_limit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun PushReliabilityPrompt(
    status: PushReliabilityStatus,
    onOpen: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(androidx.compose.ui.res.stringResource(R.string.push_reliability_prompt_title)) },
        text = {
            Text(
                androidx.compose.ui.res.stringResource(
                    if (!status.notificationsAllowed) R.string.push_reliability_prompt_notifications
                    else R.string.push_reliability_prompt_battery,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onOpen) {
                Text(androidx.compose.ui.res.stringResource(R.string.push_reliability_check_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(androidx.compose.ui.res.stringResource(R.string.update_later))
            }
        },
    )
}

@Composable
internal fun ReleaseNotesDialog(notes: List<Int>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(R.string.release_notes_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                notes.forEach { note ->
                    Text("• " + androidx.compose.ui.res.stringResource(note))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(R.string.release_notes_done))
            }
        },
    )
}
