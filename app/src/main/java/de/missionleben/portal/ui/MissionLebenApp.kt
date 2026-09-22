package de.missionleben.portal.ui

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Badge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.data.ContactPage
import de.missionleben.portal.R
import de.missionleben.portal.auth.IdentityBirthday
import de.missionleben.portal.auth.ReauthenticationPolicy
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.AnnouncementItem
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.model.LinkTarget
import de.missionleben.portal.model.NewsItem
import de.missionleben.portal.model.PortalCapability
import de.missionleben.portal.model.PortalApplication
import de.missionleben.portal.model.UiState
import de.missionleben.portal.push.NotificationPrivacy
import de.missionleben.portal.push.PushRegistrationStore
import de.missionleben.portal.update.UpdateStatus
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

@Composable
fun MissionLebenApp(
    state: UiState,
    onStartLogin: () -> Unit,
    onRetryQuickUnlock: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenPublicUrl: (String) -> Unit,
    onReloadApplications: () -> Unit,
    onSearchContacts: suspend (String, Boolean, Int, String, String) -> ContactPage,
    onComposeContactEmail: (String) -> Unit,
    onMarkAnnouncementRead: (Long) -> Unit,
    onSelfEnrollment: () -> Unit,
    onRefreshDeviceStatus: () -> Unit,
    onEnableQuickUnlock: () -> Unit,
    onOpenTalk: (String, String) -> Unit,
    onNotificationPrivacyChange: (NotificationPrivacy) -> Unit,
    onCalendarReminderChange: (Int) -> Unit,
    onCommunicationNotificationsChange: (Boolean) -> Unit,
    onQuietHoursChange: (Boolean) -> Unit,
    onQuietStartChange: (Int) -> Unit,
    onQuietEndChange: (Int) -> Unit,
    onLogout: () -> Unit,
    onResetProfile: () -> Unit,
    onDismissMessage: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onApproveLogin: () -> Unit,
    onDenyLogin: () -> Unit,
    onLoginApprovalExpired: (String) -> Unit,
    onInstallUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    currentLanguageTag: String?,
    onLanguageChange: (String?) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (state.mode == null) {
            Onboarding(
                state = state,
                onSelfEnrollment = onSelfEnrollment,
                onDismissMessage = onDismissMessage,
                onCheckForUpdates = onCheckForUpdates,
                currentLanguageTag = currentLanguageTag,
                onLanguageChange = onLanguageChange,
            )
        } else {
            Home(
                state = state,
                onStartLogin = onStartLogin,
                onRetryQuickUnlock = onRetryQuickUnlock,
                onOpenUrl = onOpenUrl,
                onOpenPublicUrl = onOpenPublicUrl,
                onReloadApplications = onReloadApplications,
                onSearchContacts = onSearchContacts,
                onComposeContactEmail = onComposeContactEmail,
                onMarkAnnouncementRead = onMarkAnnouncementRead,
                onSelfEnrollment = onSelfEnrollment,
                onRefreshDeviceStatus = onRefreshDeviceStatus,
                onEnableQuickUnlock = onEnableQuickUnlock,
                onOpenTalk = onOpenTalk,
                onNotificationPrivacyChange = onNotificationPrivacyChange,
                onCalendarReminderChange = onCalendarReminderChange,
                onCommunicationNotificationsChange = onCommunicationNotificationsChange,
                onQuietHoursChange = onQuietHoursChange,
                onQuietStartChange = onQuietStartChange,
                onQuietEndChange = onQuietEndChange,
                onLogout = onLogout,
                onResetProfile = onResetProfile,
                onDismissMessage = onDismissMessage,
                onCheckForUpdates = onCheckForUpdates,
                currentLanguageTag = currentLanguageTag,
                onLanguageChange = onLanguageChange,
            )
        }
    }
    state.loginApprovalRequest?.let { request ->
        var remainingSeconds by remember(request.requestId, request.expiresAtEpochSeconds) {
            mutableLongStateOf(request.remainingSeconds())
        }
        LaunchedEffect(
            request.requestId,
            request.expiresAtEpochSeconds,
            state.loginApprovalSubmitting,
        ) {
            while (true) {
                remainingSeconds = request.remainingSeconds()
                if (remainingSeconds == 0L) {
                    if (!state.loginApprovalSubmitting) {
                        onLoginApprovalExpired(request.requestId)
                    }
                    break
                }
                delay(250L)
            }
        }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.login_approval_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.login_approval_body))
                    if (request.application.isNotBlank()) {
                        Text(
                            stringResource(R.string.login_approval_application, request.application),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        pluralStringResource(
                            R.plurals.login_approval_expires_in_seconds,
                            remainingSeconds.toInt(),
                            remainingSeconds,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.loginApprovalSubmitting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.login_approval_sending))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onApproveLogin,
                    enabled = !state.loginApprovalSubmitting && remainingSeconds > 0L,
                ) {
                    Text(stringResource(R.string.login_approval_approve))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDenyLogin,
                    enabled = !state.loginApprovalSubmitting && remainingSeconds > 0L,
                ) {
                    Text(stringResource(R.string.login_approval_deny))
                }
            },
        )
    }
    if (state.loginApprovalRequest == null) state.availableUpdate?.let { update ->
        val downloading = state.updateStatus == UpdateStatus.DOWNLOADING
        AlertDialog(
            onDismissRequest = { if (!downloading) onDismissUpdate() },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.update_available_description, update.versionName))
                    if (downloading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.update_downloading))
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onInstallUpdate, enabled = !downloading) {
                    Text(stringResource(R.string.update_download_install))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissUpdate, enabled = !downloading) {
                    Text(stringResource(R.string.update_later))
                }
            },
        )
    }
}

@Composable
private fun Onboarding(
    state: UiState,
    onSelfEnrollment: () -> Unit,
    onDismissMessage: () -> Unit,
    onCheckForUpdates: () -> Unit,
    currentLanguageTag: String?,
    onLanguageChange: (String?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, top = 15.dp, end = 24.dp, bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { BrandHeader() }
        state.message?.let { message -> item { MessageBanner(message, onDismissMessage) } }
        item {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.onboarding_scan_heading), fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.enrollment_instruction),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        item {
            Button(onClick = onSelfEnrollment, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(stringResource(R.string.self_enrollment_action))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.self_enrollment_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Text(
                stringResource(R.string.enrollment_qr_alternative),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Text(
                stringResource(R.string.android_requirement),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { LanguagePanel(currentLanguageTag, onLanguageChange) }
        item { UpdateFooter(state.updateStatus, onCheckForUpdates) }
    }
}

@Composable
private fun Home(
    state: UiState,
    onStartLogin: () -> Unit,
    onRetryQuickUnlock: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenPublicUrl: (String) -> Unit,
    onReloadApplications: () -> Unit,
    onSearchContacts: suspend (String, Boolean, Int, String, String) -> ContactPage,
    onComposeContactEmail: (String) -> Unit,
    onMarkAnnouncementRead: (Long) -> Unit,
    onSelfEnrollment: () -> Unit,
    onRefreshDeviceStatus: () -> Unit,
    onEnableQuickUnlock: () -> Unit,
    onOpenTalk: (String, String) -> Unit,
    onNotificationPrivacyChange: (NotificationPrivacy) -> Unit,
    onCalendarReminderChange: (Int) -> Unit,
    onCommunicationNotificationsChange: (Boolean) -> Unit,
    onQuietHoursChange: (Boolean) -> Unit,
    onQuietStartChange: (Int) -> Unit,
    onQuietEndChange: (Int) -> Unit,
    onLogout: () -> Unit,
    onResetProfile: () -> Unit,
    onDismissMessage: () -> Unit,
    onCheckForUpdates: () -> Unit,
    currentLanguageTag: String?,
    onLanguageChange: (String?) -> Unit,
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var talkDialogOpen by rememberSaveable { mutableStateOf(false) }
    var contactsOpen by remember(state.signedIn, state.user?.subject) { mutableStateOf(false) }

    if (contactsOpen && state.signedIn) {
        ContactsScreen(
            onSearchContacts,
            onBack = { contactsOpen = false },
            onEmail = onComposeContactEmail,
            busy = state.busy,
            message = state.message,
        )
        return
    }

    BackHandler(enabled = settingsOpen) {
        settingsOpen = false
    }

    if (settingsOpen) {
        SettingsScreen(
            state = state,
            onBack = { settingsOpen = false },
            onSelfEnrollment = onSelfEnrollment,
            onRefreshDeviceStatus = onRefreshDeviceStatus,
            onNotificationPrivacyChange = onNotificationPrivacyChange,
            onCalendarReminderChange = onCalendarReminderChange,
            onCommunicationNotificationsChange = onCommunicationNotificationsChange,
            onQuietHoursChange = onQuietHoursChange,
            onQuietStartChange = onQuietStartChange,
            onQuietEndChange = onQuietEndChange,
            onLogout = onLogout,
            onResetProfile = onResetProfile,
            onDismissMessage = onDismissMessage,
            onCheckForUpdates = onCheckForUpdates,
            currentLanguageTag = currentLanguageTag,
            onLanguageChange = onLanguageChange,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandHeader()
                TextButton(onClick = { settingsOpen = true }) {
                    Text(stringResource(R.string.settings_title))
                }
            }
        }
        state.message?.let { message -> item { MessageBanner(message, onDismissMessage) } }
        if (state.signedIn) {
            item { Greeting(state) }
            if (state.announcementsLoading || state.announcements.isNotEmpty()) {
                item {
                    AnnouncementsPanel(
                        state.announcements,
                        state.announcementsLoading,
                        state.announcementsStale,
                        state.readAnnouncementIds,
                        onOpenUrl,
                        onMarkAnnouncementRead,
                    )
                }
            }
            if (state.newsLoading || state.news.isNotEmpty()) {
                item { NewsPanel(state.news, state.newsLoading, onOpenPublicUrl) }
            }
        } else {
            item { SignInSummary(state, onStartLogin, onRetryQuickUnlock) }
        }
        if (state.enrollmentState != EnrollmentState.TRUSTED) {
            item {
                DeviceStatusSummary(
                    state,
                    onSelfEnrollment,
                    onRefreshDeviceStatus,
                )
            }
        }

        if (state.signedIn) {
            item {
                Card(onClick = { contactsOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.contacts_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.contacts_subtitle), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            item {
                SectionTitle(
                    title = stringResource(R.string.web_apps_title),
                    action = stringResource(R.string.refresh),
                    onAction = onReloadApplications,
                )
            }
            if (state.applicationsLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else if (state.applications.isEmpty()) {
                item { EmptyApps(onOpenUrl) }
            } else {
                items((state.applications.size + 1) / 2) { rowIndex ->
                    val first = state.applications[rowIndex * 2]
                    val second = state.applications.getOrNull(rowIndex * 2 + 1)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppTile(
                            first,
                            state.unreadNotificationBadges.countFor(first),
                            Modifier.weight(1f),
                            onOpenUrl,
                        )
                        if (second != null) {
                            AppTile(
                                second,
                                state.unreadNotificationBadges.countFor(second),
                                Modifier.weight(1f),
                                onOpenUrl,
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (PortalCapability.OPEN_TALK in state.capabilities) {
                item { TalkToolCard { talkDialogOpen = true } }
            }
            item { AccountDeviceSummary(state, onEnableQuickUnlock) }
        }
    }

    if (talkDialogOpen) {
        TalkHandoffDialog(
            state = state,
            onOpenTalk = { target, url ->
                talkDialogOpen = false
                onOpenTalk(target, url)
            },
            onDismiss = { talkDialogOpen = false },
        )
    }
}

@Composable
private fun AnnouncementsPanel(
    announcements: List<AnnouncementItem>,
    loading: Boolean,
    stale: Boolean,
    readAnnouncementIds: Set<Long>,
    onOpenUrl: (String) -> Unit,
    onMarkRead: (Long) -> Unit,
) {
    val latest = announcements.firstOrNull()
    val alreadyRead = latest?.id in readAnnouncementIds
    var expanded by rememberSaveable(latest?.id, alreadyRead) {
        mutableStateOf(latest != null && !alreadyRead)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (latest != null && !expanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = true }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.announcements_title),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            latest.subject,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.announcements_expand),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            } else if (loading && announcements.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                latest?.let { item ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.announcements_title),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            TextButton(onClick = { onOpenUrl(BuildConfig.ANNOUNCEMENTS_PAGE_URL) }) {
                                Text(stringResource(R.string.announcements_all))
                            }
                        }
                        if (item.publishedAtEpochSeconds > 0L) {
                            Text(
                                DateFormat.getDateInstance(DateFormat.MEDIUM)
                                    .format(Date(item.publishedAtEpochSeconds * 1_000L)),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        Text(
                            item.subject,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.message.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                item.message,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (stale) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.announcements_cached),
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = {
                                    expanded = false
                                    if (!alreadyRead) onMarkRead(item.id)
                                },
                            ) {
                                Text(
                                    stringResource(
                                        if (alreadyRead) {
                                            R.string.announcements_collapse
                                        } else {
                                            R.string.announcements_mark_read
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onSelfEnrollment: () -> Unit,
    onRefreshDeviceStatus: () -> Unit,
    onNotificationPrivacyChange: (NotificationPrivacy) -> Unit,
    onCalendarReminderChange: (Int) -> Unit,
    onCommunicationNotificationsChange: (Boolean) -> Unit,
    onQuietHoursChange: (Boolean) -> Unit,
    onQuietStartChange: (Int) -> Unit,
    onQuietEndChange: (Int) -> Unit,
    onLogout: () -> Unit,
    onResetProfile: () -> Unit,
    onDismissMessage: () -> Unit,
    onCheckForUpdates: () -> Unit,
    currentLanguageTag: String?,
    onLanguageChange: (String?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        state.message?.let { message -> item { MessageBanner(message, onDismissMessage) } }
        item { DevicePanel(state, onSelfEnrollment, onRefreshDeviceStatus) }
        if (state.signedIn) {
            item {
                NotificationPrivacyPanel(
                    state,
                    onNotificationPrivacyChange,
                    onCalendarReminderChange,
                    onCommunicationNotificationsChange,
                    onQuietHoursChange,
                    onQuietStartChange,
                    onQuietEndChange,
                )
            }
        }
        item { LanguagePanel(currentLanguageTag, onLanguageChange) }
        if (state.signedIn) {
            item {
                OutlinedButton(onClick = onLogout, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (state.mode == DeviceMode.SHARED) {
                            stringResource(R.string.end_session_securely)
                        } else {
                            stringResource(R.string.sign_out)
                        },
                    )
                }
            }
        }
        item {
            UpdateFooter(
                status = state.updateStatus,
                onCheckForUpdates = onCheckForUpdates,
                onResetProfile = if (PortalCapability.DEVICE_PROFILE_SWITCH in state.capabilities) {
                    onResetProfile
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun UpdateFooter(
    status: UpdateStatus,
    onCheckForUpdates: () -> Unit,
    onResetProfile: (() -> Unit)? = null,
) {
    val checking = status == UpdateStatus.CHECKING
    val actionEnabled = status != UpdateStatus.CHECKING &&
        status != UpdateStatus.DOWNLOADING &&
        status != UpdateStatus.READY
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.version_format, BuildConfig.VERSION_NAME),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            TextButton(onClick = onCheckForUpdates, enabled = actionEnabled) {
                Text(
                    stringResource(
                        if (checking) R.string.update_checking else R.string.update_check_action,
                    ),
                )
            }
        }
        onResetProfile?.let { reset ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TextButton(onClick = reset) { Text(stringResource(R.string.switch_device_profile)) }
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(stringResource(R.string.brand_name), fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
            Text(
                stringResource(R.string.brand_portal),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
            )
        }
    }
}

@Composable
private fun Greeting(state: UiState) {
    val displayName = state.user?.displayName.orEmpty()
    val motivationTexts = listOf(
        stringResource(R.string.motivation_welcome),
        stringResource(R.string.motivation_together),
        stringResource(R.string.motivation_thanks),
        stringResource(R.string.motivation_today),
    )
    val motivationIndex = Math.floorMod(
        "${state.user?.subject.orEmpty()}:${LocalDate.now().toEpochDay()}".hashCode(),
        motivationTexts.size,
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)) {
        Text(
            stringResource(R.string.hello_name, displayName),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (IdentityBirthday.isToday(state.user?.birthdayMonthDay)) {
            Spacer(Modifier.height(7.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🎂", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.birthday_greeting, displayName),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        } else {
            Spacer(Modifier.height(2.dp))
            Text(
                motivationTexts[motivationIndex],
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SignInSummary(
    state: UiState,
    onStartLogin: () -> Unit,
    onRetryQuickUnlock: () -> Unit,
) {
    val deviceAllowsLogin = !state.deviceServiceConfigured || state.enrollmentState == EnrollmentState.TRUSTED
    val quickUnlockAvailable =
        state.mode == DeviceMode.PERSONAL &&
            state.quickUnlockEnabled &&
            !state.reauthenticationRequired
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                if (state.reauthenticationRequired) {
                    stringResource(R.string.reauthenticate_title)
                } else {
                    stringResource(R.string.secure_sign_in)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                when {
                    state.enrollmentState == EnrollmentState.BLOCKED -> stringResource(R.string.device_blocked_description)
                    state.deviceServiceConfigured && state.enrollmentState != EnrollmentState.TRUSTED -> stringResource(R.string.device_pending_description)
                    state.reauthenticationRequired -> stringResource(R.string.reauthenticate_description)
                    state.mode == DeviceMode.PERSONAL -> stringResource(R.string.first_login_description)
                    else -> stringResource(R.string.shared_login_description)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = if (quickUnlockAvailable) onRetryQuickUnlock else onStartLogin,
                enabled = !state.busy && deviceAllowsLogin,
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(
                        stringResource(
                            when {
                                state.reauthenticationRequired -> R.string.reauthenticate_action
                                quickUnlockAvailable -> R.string.quick_access_open
                                else -> R.string.sign_in_with_authentik
                            },
                        ),
                    )
                }
            }
            if (quickUnlockAvailable) {
                TextButton(
                    onClick = onStartLogin,
                    enabled = !state.busy && deviceAllowsLogin,
                ) {
                    Text(stringResource(R.string.sign_in_with_authentik))
                }
            }
            if (state.enrollmentState == EnrollmentState.TRUSTED) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                TrustedDeviceStatusRow(state)
            }
        }
    }
}

@Composable
private fun AccountDeviceSummary(state: UiState, onEnableQuickUnlock: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.mode?.let { stringResource(it.labelRes) }.orEmpty(),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.mode == DeviceMode.PERSONAL) {
                    val remainingDays = state.user?.let {
                        ReauthenticationPolicy.remainingDays(it.authenticatedAtEpochSeconds)
                    } ?: 0L
                    Spacer(Modifier.width(10.dp))
                    SessionValidityPill(remainingDays)
                }
            }
            if (state.mode == DeviceMode.PERSONAL && !state.quickUnlockEnabled) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onEnableQuickUnlock,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.quick_access_enable_title))
                }
            }
            if (state.enrollmentState == EnrollmentState.TRUSTED) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                TrustedDeviceStatusRow(state)
            }
        }
    }
}

@Composable
private fun SessionValidityPill(remainingDays: Long) {
    val label = when (remainingDays) {
        0L -> stringResource(R.string.session_valid_today_short)
        1L -> stringResource(R.string.session_valid_one_day_short)
        else -> stringResource(R.string.session_valid_days_short, remainingDays)
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun DeviceStatusSummary(
    state: UiState,
    onSelfEnrollment: () -> Unit,
    onRefreshDeviceStatus: () -> Unit,
) {
    if (state.enrollmentState != EnrollmentState.TRUSTED) {
        DevicePanel(state, onSelfEnrollment, onRefreshDeviceStatus)
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        TrustedDeviceStatusRow(
            state = state,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun TrustedDeviceStatusRow(state: UiState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.device_identity),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        StatusPill(state.enrollmentState)
    }
}

@Composable
private fun DevicePanel(
    state: UiState,
    onSelfEnrollment: () -> Unit,
    onRefreshDeviceStatus: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.device_identity), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.device_key_format, state.deviceKeyId.take(12)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                StatusPill(state.enrollmentState)
            }
            if (state.enrollmentState == EnrollmentState.NOT_ENROLLED) {
                Spacer(Modifier.height(14.dp))
                Text(
                    if (state.deviceServiceConfigured) stringResource(R.string.enrollment_instruction)
                    else stringResource(R.string.device_api_not_configured),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.deviceServiceConfigured) {
                    Spacer(Modifier.height(10.dp))
                    if (state.mode == DeviceMode.PERSONAL) {
                        Button(
                            onClick = onSelfEnrollment,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.self_enrollment_action))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        stringResource(R.string.enrollment_qr_alternative),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (state.deviceId != null) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.device_id_format, state.deviceId), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                if (state.deviceServiceConfigured && state.enrollmentState != EnrollmentState.TRUSTED) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onRefreshDeviceStatus, enabled = !state.busy) {
                        Text(stringResource(R.string.check_status))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(state: EnrollmentState) {
    val darkTheme = isSystemInDarkTheme()
    val (label, color) = when (state) {
        EnrollmentState.NOT_ENROLLED -> stringResource(R.string.status_not_registered) to MaterialTheme.colorScheme.onSurfaceVariant
        EnrollmentState.PENDING -> stringResource(R.string.status_pending) to
            if (darkTheme) Color(0xFFFFC46B) else Color(0xFFAD6800)
        EnrollmentState.TRUSTED -> stringResource(R.string.status_trusted) to
            if (darkTheme) Color(0xFF62D6A8) else Success
        EnrollmentState.BLOCKED -> stringResource(R.string.status_blocked) to MaterialTheme.colorScheme.error
    }
    Surface(color = color.copy(alpha = 0.12f), shape = CircleShape) {
        Text(label, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NewsPanel(news: List<NewsItem>, loading: Boolean, onOpenUrl: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.news_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = { onOpenUrl(BuildConfig.NEWS_PAGE_URL) }) {
                    Text(stringResource(R.string.news_all))
                }
            }
            if (loading && news.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                news.firstOrNull()?.let { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenUrl(item.link) }
                            .padding(vertical = 9.dp),
                    ) {
                        if (item.publishedAtEpochSeconds > 0L) {
                            Text(
                                DateFormat.getDateInstance(DateFormat.MEDIUM)
                                    .format(Date(item.publishedAtEpochSeconds * 1_000L)),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onAction) { Text(action, maxLines = 1) }
    }
}

@Composable
private fun AppTile(
    application: PortalApplication,
    unreadCount: Int,
    modifier: Modifier,
    onOpenUrl: (String) -> Unit,
) {
    Card(
        modifier = modifier.height(106.dp).clickable { onOpenUrl(application.launchUrl) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.padding(14.dp)) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        application.name.take(1).uppercase(),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Black,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(application.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (unreadCount > 0) {
                Badge(
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp),
                ) {
                    Text(if (unreadCount > 99) "99+" else unreadCount.toString())
                }
            }
        }
    }
}

@Composable
private fun EmptyApps(onOpenUrl: (String) -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.empty_apps_title), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.empty_apps_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onOpenUrl(BuildConfig.PORTAL_URL) }) { Text(stringResource(R.string.open_authentik_portal)) }
        }
    }
}

@Composable
private fun TalkToolCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.talk_handoff_title), fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.talk_handoff_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onOpen) { Text(stringResource(R.string.open_action)) }
        }
    }
}

@Composable
private fun TalkHandoffDialog(
    state: UiState,
    onOpenTalk: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var talkUrl by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<LinkTarget?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.talk_handoff_title)) },
        text = {
            Column {
            Text(
                stringResource(R.string.talk_handoff_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = talkUrl,
                onValueChange = { talkUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.talk_link)) },
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.linkTargets.forEach { target ->
                    val active = selected?.id == target.id
                    OutlinedButton(onClick = { selected = target }, enabled = target.online) {
                        Text(
                            (if (target.online) "● " else "○ ") + target.name,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (state.linkTargets.isEmpty()) {
                Text(stringResource(R.string.no_conference_device), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            }
        },
        confirmButton = {
            Button(
                onClick = { selected?.let { onOpenTalk(it.id, talkUrl) } },
                enabled = selected != null && talkUrl.isNotBlank() && !state.busy,
            ) { Text(stringResource(R.string.open_on_target_device)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_back)) }
        },
    )
}

@Composable
private fun NotificationPrivacyPanel(
    state: UiState,
    onChange: (NotificationPrivacy) -> Unit,
    onCalendarReminderChange: (Int) -> Unit,
    onCommunicationNotificationsChange: (Boolean) -> Unit,
    onQuietHoursChange: (Boolean) -> Unit,
    onQuietStartChange: (Int) -> Unit,
    onQuietEndChange: (Int) -> Unit,
) {
    if (!state.pushConfigured) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.notifications_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (state.mode == DeviceMode.SHARED) {
                Text(
                    stringResource(R.string.notifications_shared_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                StatusPillText(stringResource(R.string.privacy_minimal_label), MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        onCommunicationNotificationsChange(!state.communicationNotificationsEnabled)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.communication_notifications_title),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.communication_notifications_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = state.communicationNotificationsEnabled,
                        onCheckedChange = null,
                    )
                }
                Spacer(Modifier.height(14.dp))
                if (state.communicationNotificationsEnabled) {
                Text(
                    stringResource(R.string.notifications_lockscreen_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        NotificationPrivacy.STANDARD,
                        NotificationPrivacy.DETAILED,
                        NotificationPrivacy.MINIMAL,
                    ).forEach { privacy ->
                        if (state.notificationPrivacy == privacy) {
                            Button(onClick = { onChange(privacy) }) { Text(stringResource(privacy.labelRes)) }
                        } else {
                            OutlinedButton(onClick = { onChange(privacy) }) { Text(stringResource(privacy.labelRes)) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(state.notificationPrivacy.descriptionRes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    stringResource(R.string.calendar_reminder_title),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.calendar_reminder_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PushRegistrationStore.SUPPORTED_CALENDAR_REMINDER_MINUTES.forEach { minutes ->
                        val label = stringResource(R.string.calendar_reminder_minutes, minutes)
                        if (state.calendarReminderMinutes == minutes) {
                            Button(onClick = { onCalendarReminderChange(minutes) }) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { onCalendarReminderChange(minutes) }) { Text(label) }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        onQuietHoursChange(!state.quietHoursEnabled)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.quiet_hours_title),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.quiet_hours_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = state.quietHoursEnabled,
                        onCheckedChange = null,
                    )
                }
                if (state.quietHoursEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QuietTimeButton(
                            label = stringResource(R.string.quiet_hours_from),
                            minutes = state.quietStartMinutes,
                            onChange = onQuietStartChange,
                            modifier = Modifier.weight(1f),
                        )
                        QuietTimeButton(
                            label = stringResource(R.string.quiet_hours_until),
                            minutes = state.quietEndMinutes,
                            onChange = onQuietEndChange,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.quiet_hours_security_exception),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                } else {
                    Text(
                        stringResource(R.string.quiet_hours_security_exception),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuietTimeButton(
    label: String,
    minutes: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hour = minutes / 60
    val minute = minutes % 60
    OutlinedButton(
        modifier = modifier,
        onClick = {
            TimePickerDialog(
                context,
                { _, selectedHour, selectedMinute -> onChange(selectedHour * 60 + selectedMinute) },
                hour,
                minute,
                true,
            ).show()
        },
    ) {
        Text("$label ${String.format(Locale.getDefault(), "%02d:%02d", hour, minute)}")
    }
}

@Composable
private fun LanguagePanel(currentLanguageTag: String?, onLanguageChange: (String?) -> Unit) {
    val languages = listOf(
        null to R.string.language_system,
        "de" to R.string.language_german,
        "en" to R.string.language_english,
        "tr" to R.string.language_turkish,
        "hi" to R.string.language_hindi,
        "es" to R.string.language_spanish,
        "fr" to R.string.language_french,
        "pl" to R.string.language_polish,
        "ro" to R.string.language_romanian,
        "uk" to R.string.language_ukrainian,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.language_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.language_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                languages.forEach { (tag, labelRes) ->
                    if (currentLanguageTag == tag) {
                        Button(onClick = { onLanguageChange(tag) }) { Text(stringResource(labelRes)) }
                    } else {
                        OutlinedButton(onClick = { onLanguageChange(tag) }) { Text(stringResource(labelRes)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPillText(label: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = CircleShape) {
        Text(
            label,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MessageBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onDismiss) {
                Text("×", color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 20.sp)
            }
        }
    }
}
