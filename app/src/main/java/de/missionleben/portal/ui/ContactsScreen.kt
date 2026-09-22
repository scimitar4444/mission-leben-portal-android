package de.missionleben.portal.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
fun ContactsScreen(search: suspend (String, Boolean, Int) -> ContactPage, onBack: () -> Unit) {
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.contacts_back)) }
            Text(stringResource(R.string.contacts_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp))
            TextButton(onClick = { offset = 0; contacts = emptyList(); page = null; refresh++ }) {
                Text(stringResource(R.string.refresh))
            }
        }
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
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
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
            items(contacts, key = { it.id }) { ContactCard(it) }
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
private fun ContactCard(contact: EmployeeContact) {
    val context = LocalContext.current
    var noHandler by remember(contact.id) { mutableStateOf(false) }
    fun open(intent: Intent) {
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { noHandler = true }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(contact.name, style = MaterialTheme.typography.titleMedium)
            listOf(contact.jobTitle, contact.department, contact.facilities.joinToString(" · "))
                .filter(String::isNotBlank).distinct().forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            if (contact.phone.isNotBlank()) TextButton(onClick = {
                ContactActions.dialable(contact.phone)?.let { dialable ->
                    open(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", dialable, null)))
                }
            }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.contacts_phone, contact.phone))
            }
            if (contact.email.isNotBlank()) TextButton(onClick = {
                open(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", contact.email, null)))
            }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Text(contact.email)
            }
            if (contact.mobile.isNotBlank()) TextButton(onClick = {
                ContactActions.dialable(contact.mobile)?.let { dialable ->
                    open(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", dialable, null)))
                }
            }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.contacts_mobile, contact.mobile))
            }
            if (noHandler) Text(stringResource(R.string.contacts_no_handler), style = MaterialTheme.typography.bodySmall)
        }
    }
}
