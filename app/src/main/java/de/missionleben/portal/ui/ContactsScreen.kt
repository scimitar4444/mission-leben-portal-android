package de.missionleben.portal.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.missionleben.portal.R
import de.missionleben.portal.data.ContactPage
import de.missionleben.portal.data.ContactActions
import de.missionleben.portal.data.ContactsAccessException
import de.missionleben.portal.data.EmployeeContact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Contact data lives only in this screen, never in saved state or on disk. */
@Composable
fun ContactsScreen(
    search: suspend (String, Boolean, Int) -> ContactPage,
    onBack: () -> Unit,
    onEmail: (String) -> Unit,
    busy: Boolean,
    message: String?,
) {
    var query by remember { mutableStateOf("") }
    var mine by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<ContactPage?>(null) }
    var contacts by remember { mutableStateOf(emptyList<EmployeeContact>()) }
    var offset by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Int?>(null) }
    val owner = LocalLifecycleOwner.current
    var active by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    BackHandler(onBack = onBack)

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                active = false
                contacts = emptyList()
                page = null
                offset = 0
            } else if (event == Lifecycle.Event.ON_START) {
                active = true
                refresh++
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(query, mine, offset, refresh, active) {
        if (!active) return@LaunchedEffect
        loading = true
        error = null
        // Changes to search/filter clear visible results immediately in handlers.
        try {
            if (offset == 0) delay(350)
            val result = search(query, mine, offset)
            contacts = if (offset == 0) result.contacts else (contacts + result.contacts).distinctBy { it.id }
            page = result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            contacts = emptyList()
            page = null
            error = if (failure is ContactsAccessException) R.string.contacts_denied else R.string.contacts_error
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        PortalHeader(stringResource(R.string.contacts_title), onBack,
            onRefresh = { offset = 0; contacts = emptyList(); page = null; refresh++ })
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(100); offset = 0; contacts = emptyList(); page = null },
            label = { Text(stringResource(R.string.contacts_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !mine, onClick = { mine = false; offset = 0; contacts = emptyList(); page = null; refresh++ },
                label = { Text(stringResource(R.string.contacts_all)) })
            FilterChip(selected = mine, onClick = { mine = true; offset = 0; contacts = emptyList(); page = null; refresh++ },
                label = { Text(stringResource(R.string.contacts_mine)) })
        }
        if (mine && page != null) {
            Text(page!!.myFacilities.joinToString(" · ").ifBlank { stringResource(R.string.contacts_no_facility) },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        error?.let {
            Text(stringResource(it), modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { offset = 0; refresh++ }) { Text(stringResource(R.string.contacts_retry)) }
        }
        if (!loading && error == null && contacts.isEmpty()) {
            Text(stringResource(R.string.contacts_empty), modifier = Modifier.padding(vertical = 16.dp))
        }
        if (page != null) Text(stringResource(R.string.contacts_count, page!!.total),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(contacts, key = { it.id }) { ContactCard(it, onEmail, enabled = !busy) }
            page?.nextOffset?.let { next ->
                item {
                    OutlinedButton(onClick = { offset = next }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.contacts_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactCard(contact: EmployeeContact, onEmail: (String) -> Unit, enabled: Boolean) {
    val context = LocalContext.current
    var noHandler by remember(contact.id) { mutableStateOf(false) }
    fun open(intent: Intent) {
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { noHandler = true }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(contact.name, style = MaterialTheme.typography.titleMedium)
            val details = listOf(contact.jobTitle, contact.department, contact.facilities.joinToString(" · "))
                .filter(String::isNotBlank).distinct().joinToString(" · ")
            if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.fillMaxWidth()) {
                if (contact.phone.isNotBlank()) ContactLink(stringResource(R.string.contacts_phone, "").trim(), contact.phone, enabled) {
                    ContactActions.dialable(contact.phone)?.let { dialable ->
                        open(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", dialable, null)))
                    }
                }
                if (contact.mobile.isNotBlank()) ContactLink(stringResource(R.string.contacts_mobile, "").trim(), contact.mobile, enabled) {
                    ContactActions.dialable(contact.mobile)?.let { dialable ->
                        open(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", dialable, null)))
                    }
                }
                if (contact.email.isNotBlank()) ContactLink(stringResource(R.string.contacts_email), contact.email, enabled) {
                    onEmail(contact.email)
                }
            }
            if (noHandler) Text(stringResource(R.string.contacts_no_handler), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ContactLink(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    // Natural text height: no fixed-height button rows between the contact details.
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 1.dp),
    ) {
        Text(label, modifier = Modifier.width(60.dp).padding(end = 6.dp).alignByBaseline(),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f).alignByBaseline(),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
