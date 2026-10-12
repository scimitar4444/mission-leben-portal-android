package de.missionleben.portal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.missionleben.portal.R
import de.missionleben.portal.model.AnnouncementItem
import de.missionleben.portal.model.NoticeInboxPolicy
import de.missionleben.portal.model.UiState
import de.missionleben.portal.push.PushAction
import java.text.DateFormat
import java.util.Date

private data class NoticeRow(val id: String, val time: Long, val read: Boolean,
    val title: String, val text: String, val icon: Int, val onClick: () -> Unit)

@Composable
internal fun NoticeInboxScreen(state: UiState, onBack: () -> Unit, onRefresh: () -> Unit,
    onOpenNotice: (String) -> Unit, onMarkAllRead: () -> Unit, onMarkAnnouncementRead: (Long) -> Unit) {
    var announcement by remember { mutableStateOf<AnnouncementItem?>(null) }
    val notices = NoticeInboxPolicy.visible(state.noticeHistory, state.applications, state.mode)
    val rows = notices.map { notice ->
        val summary = state.noticeSummaries[notice.eventId]
        NoticeRow("event-${notice.eventId}", notice.receivedAtMillis, notice.read,
            summary?.first?.takeIf { it.isNotBlank() } ?: stringResource(
                if (notice.action == PushAction.OPEN_DOCUMENTS) R.string.notice_document else R.string.notice_ticket),
            summary?.second.orEmpty(),
            if (notice.action == PushAction.OPEN_DOCUMENTS) R.drawable.ic_app_documents else R.drawable.ic_app_support,
            { onOpenNotice(notice.eventId) })
    } + state.announcements.map { item ->
        NoticeRow("announcement-${item.id}", item.publishedAtEpochSeconds * 1000,
            item.id in state.readAnnouncementIds, item.subject, item.message,
            R.drawable.ic_info_outline, { announcement = item; onMarkAnnouncementRead(item.id) })
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("notice-inbox")) {
        PortalHeader(stringResource(R.string.notice_inbox_title), onBack, onRefresh)
        Text(stringResource(R.string.notice_inbox_description), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (state.noticeHistoryLoading || state.announcementsLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.weight(1f))
            if (rows.any { !it.read }) TextButton(onClick = onMarkAllRead) {
                Text(stringResource(R.string.notice_mark_all_read))
            }
        }
        if (state.announcementsStale) Text(stringResource(R.string.announcements_cached),
            style = MaterialTheme.typography.bodySmall)
        if (rows.isEmpty()) Text(stringResource(R.string.notice_inbox_empty), Modifier.padding(vertical = 20.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows.sortedByDescending { it.time }, key = { it.id }) { row ->
                Card(Modifier.fillMaxWidth().clickable(onClick = row.onClick).testTag("notice-" + row.id)) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(painterResource(row.icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(row.title, fontWeight = if (row.read) FontWeight.Normal else FontWeight.Bold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (row.text.isNotBlank()) Text(row.text, style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.time)),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!row.read) Text(stringResource(R.string.notice_new), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    announcement?.let { item ->
        AlertDialog(onDismissRequest = { announcement = null }, title = { Text(item.subject) },
            text = { Text(item.message, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { announcement = null }) { Text(stringResource(android.R.string.ok)) } })
    }
}
