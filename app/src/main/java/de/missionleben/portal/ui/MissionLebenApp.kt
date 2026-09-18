package de.missionleben.portal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.R
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.model.LinkTarget
import de.missionleben.portal.model.PortalApplication
import de.missionleben.portal.model.UiState
import de.missionleben.portal.push.NotificationPrivacy

@Composable
fun MissionLebenApp(
    state: UiState,
    onSelectMode: (DeviceMode) -> Unit,
    onStartLogin: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onReloadApplications: () -> Unit,
    onEnrollDevice: (String) -> Unit,
    onRefreshDeviceStatus: () -> Unit,
    onOpenTalk: (String, String) -> Unit,
    onNotificationPrivacyChange: (NotificationPrivacy) -> Unit,
    onLogout: () -> Unit,
    onResetProfile: () -> Unit,
    onDismissMessage: () -> Unit,
    currentLanguageTag: String?,
    onLanguageChange: (String?) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (state.mode == null) {
            Onboarding(onSelectMode, currentLanguageTag, onLanguageChange)
        } else {
            Home(
                state = state,
                onStartLogin = onStartLogin,
                onOpenUrl = onOpenUrl,
                onReloadApplications = onReloadApplications,
                onEnrollDevice = onEnrollDevice,
                onRefreshDeviceStatus = onRefreshDeviceStatus,
                onOpenTalk = onOpenTalk,
                onNotificationPrivacyChange = onNotificationPrivacyChange,
                onLogout = onLogout,
                onResetProfile = onResetProfile,
                onDismissMessage = onDismissMessage,
                currentLanguageTag = currentLanguageTag,
                onLanguageChange = onLanguageChange,
            )
        }
    }
}

@Composable
private fun Onboarding(
    onSelectMode: (DeviceMode) -> Unit,
    currentLanguageTag: String?,
    onLanguageChange: (String?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 34.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { BrandHeader() }
        item {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.onboarding_heading), fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.onboarding_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        item {
            ModeCard(
                eyebrow = stringResource(R.string.onboarding_employee_eyebrow),
                title = stringResource(R.string.onboarding_personal_title),
                description = stringResource(R.string.onboarding_personal_description),
                action = stringResource(R.string.onboarding_personal_action),
                accent = Coral,
                onClick = { onSelectMode(DeviceMode.PERSONAL) },
            )
        }
        item {
            ModeCard(
                eyebrow = stringResource(R.string.onboarding_shared_eyebrow),
                title = stringResource(R.string.onboarding_shared_title),
                description = stringResource(R.string.onboarding_shared_description),
                action = stringResource(R.string.onboarding_shared_action),
                accent = Ink,
                onClick = { onSelectMode(DeviceMode.SHARED) },
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
    }
}

@Composable
private fun ModeCard(
    eyebrow: String,
    title: String,
    description: String,
    action: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(22.dp)) {
            Text(eyebrow, color = accent, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                Text(action)
            }
        }
    }
}

@Composable
private fun Home(
    state: UiState,
    onStartLogin: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onReloadApplications: () -> Unit,
    onEnrollDevice: (String) -> Unit,
    onRefreshDeviceStatus: () -> Unit,
    onOpenTalk: (String, String) -> Unit,
    onNotificationPrivacyChange: (NotificationPrivacy) -> Unit,
    onLogout: () -> Unit,
    onResetProfile: () -> Unit,
    onDismissMessage: () -> Unit,
    currentLanguageTag: String?,
    onLanguageChange: (String?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { BrandHeader() }
        state.message?.let { message -> item { MessageBanner(message, onDismissMessage) } }
        item { WelcomePanel(state, onStartLogin, onLogout) }
        item { DevicePanel(state, onEnrollDevice, onRefreshDeviceStatus) }

        if (state.signedIn) {
            item {
                SectionTitle(
                    title = stringResource(R.string.web_apps_title),
                    subtitle = stringResource(R.string.web_apps_subtitle),
                    action = stringResource(R.string.refresh),
                    onAction = onReloadApplications,
                )
            }
            if (state.applicationsLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Coral)
                    }
                }
            } else if (state.applications.isEmpty()) {
                item { EmptyApps(onOpenUrl) }
            } else {
                items((state.applications.size + 1) / 2) { rowIndex ->
                    val first = state.applications[rowIndex * 2]
                    val second = state.applications.getOrNull(rowIndex * 2 + 1)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppTile(first, Modifier.weight(1f), onOpenUrl)
                        if (second != null) AppTile(second, Modifier.weight(1f), onOpenUrl) else Spacer(Modifier.weight(1f))
                    }
                }
            }
            item { TalkHandoffPanel(state, onOpenTalk) }
            item { NotificationPrivacyPanel(state, onNotificationPrivacyChange) }
        }

        item { LanguagePanel(currentLanguageTag, onLanguageChange) }

        item {
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.version_format, BuildConfig.VERSION_NAME), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                TextButton(onClick = onResetProfile) { Text(stringResource(R.string.switch_device_profile)) }
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
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(R.string.brand_name), fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
            Text(stringResource(R.string.brand_portal), color = Coral, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 2.sp)
        }
    }
}

@Composable
private fun WelcomePanel(state: UiState, onStartLogin: () -> Unit, onLogout: () -> Unit) {
    val deviceAllowsLogin = !state.deviceServiceConfigured || state.enrollmentState == EnrollmentState.TRUSTED
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Ink),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(22.dp)) {
            Text(state.mode?.let { stringResource(it.labelRes) }.orEmpty(), color = Color(0xFFFFA1A7), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.signedIn) stringResource(R.string.hello_name, state.user?.displayName.orEmpty()) else stringResource(R.string.secure_sign_in),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    state.enrollmentState == EnrollmentState.BLOCKED ->
                        stringResource(R.string.device_blocked_description)
                    state.deviceServiceConfigured && state.enrollmentState != EnrollmentState.TRUSTED ->
                        stringResource(R.string.device_pending_description)
                    state.signedIn && state.mode == DeviceMode.PERSONAL && state.quickUnlockEnabled ->
                        stringResource(R.string.session_keystore_description)
                    state.signedIn && state.mode == DeviceMode.SHARED ->
                        stringResource(R.string.shared_session_description)
                    state.mode == DeviceMode.PERSONAL ->
                        stringResource(R.string.first_login_description)
                    else -> stringResource(R.string.shared_login_description)
                },
                color = Color(0xFFD7CFDD),
            )
            Spacer(Modifier.height(18.dp))
            if (state.signedIn) {
                OutlinedButton(onClick = onLogout, enabled = !state.busy) {
                    Text(if (state.mode == DeviceMode.SHARED) stringResource(R.string.end_session_securely) else stringResource(R.string.sign_out), color = Color.White)
                }
            } else {
                Button(onClick = onStartLogin, enabled = !state.busy && deviceAllowsLogin) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.sign_in_with_authentik))
                    }
                }
            }
        }
    }
}

@Composable
private fun DevicePanel(
    state: UiState,
    onEnrollDevice: (String) -> Unit,
    onRefreshDeviceStatus: () -> Unit,
) {
    var token by remember { mutableStateOf(state.enrollmentTokenPrefill) }
    LaunchedEffect(state.enrollmentTokenPrefill) {
        if (state.enrollmentTokenPrefill.isNotBlank()) token = state.enrollmentTokenPrefill
    }
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
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.enrollment_code)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { onEnrollDevice(token) }, enabled = !state.busy) { Text(stringResource(R.string.register_device)) }
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
    val (label, color) = when (state) {
        EnrollmentState.NOT_ENROLLED -> stringResource(R.string.status_not_registered) to MaterialTheme.colorScheme.onSurfaceVariant
        EnrollmentState.PENDING -> stringResource(R.string.status_pending) to Color(0xFFAD6800)
        EnrollmentState.TRUSTED -> stringResource(R.string.status_trusted) to Success
        EnrollmentState.BLOCKED -> stringResource(R.string.status_blocked) to MaterialTheme.colorScheme.error
    }
    Surface(color = color.copy(alpha = 0.12f), shape = CircleShape) {
        Text(label, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun AppTile(application: PortalApplication, modifier: Modifier, onOpenUrl: (String) -> Unit) {
    Card(
        modifier = modifier.height(142.dp).clickable { onOpenUrl(application.launchUrl) },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Lavender), contentAlignment = Alignment.Center) {
                Text(application.name.take(1).uppercase(), color = CoralDark, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(12.dp))
            Text(application.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (application.publisher.isNotBlank()) {
                Text(application.publisher, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
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
private fun TalkHandoffPanel(state: UiState, onOpenTalk: (String, String) -> Unit) {
    if (!state.communicationServiceConfigured) return
    var talkUrl by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<LinkTarget?>(null) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Lavender),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.talk_handoff_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
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
                        Text((if (target.online) "● " else "○ ") + target.name, color = if (active) CoralDark else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (state.linkTargets.isEmpty()) {
                Text(stringResource(R.string.no_conference_device), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { selected?.let { onOpenTalk(it.id, talkUrl) } },
                enabled = selected != null && talkUrl.isNotBlank() && !state.busy,
            ) { Text(stringResource(R.string.open_on_target_device)) }
        }
    }
}

@Composable
private fun NotificationPrivacyPanel(
    state: UiState,
    onChange: (NotificationPrivacy) -> Unit,
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
            }
        }
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(message, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
