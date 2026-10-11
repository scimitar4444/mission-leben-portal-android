package de.missionleben.portal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.R
import de.missionleben.portal.calendar.CalendarEvent
import de.missionleben.portal.calendar.TodayAgenda
import de.missionleben.portal.model.UiState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/** One compact row; expanded details do not add more permanent home tiles. */
@Composable
internal fun TodayAgendaPanel(state: UiState, onOpenUrl: (String) -> Unit) {
    val owner = LocalLifecycleOwner.current
    var active by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) { active = true; now = System.currentTimeMillis() }
            if (event == Lifecycle.Event.ON_STOP) active = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(active) {
        while (active) { delay(60_000L); now = System.currentTimeMillis() }
    }
    val events = TodayAgenda.select(state.todayEvents, now, ZoneId.systemDefault())
    if (events.isEmpty() || state.todayFetchedAtMillis <= 0L || now - state.todayFetchedAtMillis > 2 * 3_600_000L) return
    val first = events.first()
    var expanded by remember(state.user?.subject, Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()) {
        mutableStateOf(false)
    }
    fun open(event: CalendarEvent) { TodayAgenda.calendarDayUrl(BuildConfig.ZIMBRA_WEB_BASE_URL, event)?.let(onOpenUrl) }
    Card(Modifier.fillMaxWidth().testTag("today-agenda"), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button) { open(first) }
                    .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column {
                        Text(stringResource(R.string.today_title), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(eventTime(first), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    Text(first.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (events.size == 1) Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp))
                }
                if (events.size > 1) IconButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("today-toggle")) {
                    Icon(painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                        stringResource(if (expanded) R.string.today_collapse else R.string.today_more, events.size - 1))
                }
            }
            if (expanded && events.size > 1) {
                HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                events.drop(1).take(4).forEach { event ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open(event) }
                        .padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(eventTime(event), style = MaterialTheme.typography.labelLarge)
                        Text(event.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp))
                    }
                }
                TextButton(onClick = { open(first) }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.today_calendar))
                }
            }
            if (state.todayStale) Text(stringResource(R.string.today_cached),
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun eventTime(event: CalendarEvent): String = if (event.allDay) stringResource(R.string.today_all_day)
    else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.startMillis))
