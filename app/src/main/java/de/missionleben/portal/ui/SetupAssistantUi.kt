package de.missionleben.portal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.missionleben.portal.R
import de.missionleben.portal.model.UiState
import de.missionleben.portal.model.AppFeaturePolicy
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.push.NotificationPrivacy
import de.missionleben.portal.push.PushReliabilityStatus
import de.missionleben.portal.setup.SetupStep

@Composable
internal fun SetupAssistantDialog(
    steps: List<SetupStep>,
    position: Int,
    state: UiState,
    pushStatus: PushReliabilityStatus,
    onRequestNotifications: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenManufacturerSettings: () -> Unit,
    onCalendarSyncEnabledChange: (Boolean) -> Unit,
    onCalendarSyncDaysChange: (Int) -> Unit,
    onNotificationPrivacyChange: (NotificationPrivacy) -> Unit,
    onCalendarReminderChange: (Int) -> Unit,
    onCommunicationNotificationsChange: (Boolean) -> Unit,
    onQuietHoursChange: (Boolean) -> Unit,
    onQuietStartChange: (Int) -> Unit,
    onQuietEndChange: (Int) -> Unit,
    onSelectStep: (Int) -> Unit,
    onNext: () -> Unit,
    onLater: () -> Unit,
) {
    val step = steps[position - 1]
    val features = AppFeaturePolicy.from(state.applications)
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.setup_assistant_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.setup_assistant_intro))
                SetupStepStrip(steps, position, onSelectStep)
                Text(
                    "${stringResource(R.string.setup_assistant_step, position, steps.size)} · ${stringResource(step.shortLabel())}",
                    style = MaterialTheme.typography.titleMedium,
                )
                when (step) {
                    SetupStep.NOTIFICATIONS -> {
                        Text(stringResource(R.string.notifications_title))
                        Text(stringResource(R.string.setup_push_explain))
                        Text(stringResource(
                            if (pushStatus.notificationsAllowed) R.string.push_reliability_notifications_ok
                            else R.string.push_reliability_notifications_off,
                        ))
                        if (!pushStatus.notificationsAllowed) {
                            OutlinedButton(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.push_reliability_open_notifications))
                            }
                        }
                    }
                    SetupStep.BATTERY -> {
                        Text(stringResource(R.string.setup_battery_title))
                        Text(stringResource(R.string.push_reliability_prompt_battery))
                        Text(stringResource(
                            if (pushStatus.batteryExempt) R.string.push_reliability_battery_ok
                            else R.string.push_reliability_battery_limited,
                        ))
                        if (!pushStatus.batteryExempt) {
                            OutlinedButton(onClick = onRequestBatteryExemption, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.push_reliability_allow_battery))
                            }
                        }
                        if (pushStatus.samsung || pushStatus.tcl) {
                            OutlinedButton(onClick = onOpenManufacturerSettings, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.push_reliability_open_manufacturer))
                            }
                            Text(stringResource(
                                if (pushStatus.samsung) R.string.setup_battery_samsung
                                else R.string.setup_battery_tcl,
                            ))
                        } else {
                            Text(stringResource(R.string.setup_battery_other))
                        }
                    }
                    SetupStep.MESSAGES -> {
                        Text(stringResource(R.string.setup_messages_explain))
                        NotificationPrivacyPanel(
                            state = state,
                            zimbraAvailable = features.zimbra,
                            talkAvailable = features.talk,
                            compact = true,
                            onChange = onNotificationPrivacyChange,
                            onCalendarReminderChange = onCalendarReminderChange,
                            onCommunicationNotificationsChange = onCommunicationNotificationsChange,
                            onQuietHoursChange = onQuietHoursChange,
                            onQuietStartChange = onQuietStartChange,
                            onQuietEndChange = onQuietEndChange,
                        )
                    }
                    SetupStep.CALENDAR -> {
                        Text(stringResource(R.string.setup_calendar_explain))
                        CalendarSyncPanel(state, onCalendarSyncEnabledChange, onCalendarSyncDaysChange, compact = true)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNext) {
                Text(stringResource(if (position == steps.size) R.string.release_notes_done else R.string.setup_assistant_next))
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
        },
    )
}

@Composable
private fun SetupStepStrip(steps: List<SetupStep>, position: Int, onSelectStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        steps.forEachIndexed { index, step ->
            val active = index + 1 == position
            val label = stringResource(step.shortLabel())
            Surface(
                onClick = { onSelectStep(index + 1) },
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = "$label, ${index + 1}"
                    selected = active
                },
                shape = CircleShape,
                color = if (active) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun SetupStep.shortLabel() = when (this) {
    SetupStep.NOTIFICATIONS -> R.string.setup_notifications_short
    SetupStep.BATTERY -> R.string.setup_battery_short
    SetupStep.MESSAGES -> R.string.setup_messages_short
    SetupStep.CALENDAR -> R.string.setup_calendar_short
}
