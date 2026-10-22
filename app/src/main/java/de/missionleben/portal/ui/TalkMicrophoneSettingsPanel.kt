package de.missionleben.portal.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.missionleben.portal.R
import de.missionleben.portal.ui.ReadableText as Text

/** Shows Android's actual permission, not a second app preference or automatic recording. */
@Composable
internal fun TalkMicrophoneSettingsPanel(compact: Boolean = false) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    fun permissionGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    var granted by remember(context) { mutableStateOf(permissionGranted()) }
    var requestDenied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        requestDenied = !allowed
    }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = permissionGranted()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("talk_microphone_settings"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(if (compact) 12.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.talk_voice_settings_title), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.talk_voice_settings_description),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(if (granted) R.string.talk_voice_permission_allowed else R.string.talk_voice_permission_missing))
            if (!granted) {
                OutlinedButton(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.fillMaxWidth().testTag("talk_microphone_request")) {
                    Text(stringResource(R.string.talk_voice_permission_request))
                }
            }
            if (granted || requestDenied) {
                if (!granted) Text(stringResource(R.string.talk_voice_permission_settings_hint),
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:${context.packageName}".toUri()))
                }, modifier = Modifier.fillMaxWidth().testTag("talk_microphone_system_settings")) {
                    Text(stringResource(R.string.talk_voice_permission_settings))
                }
            }
        }
    }
}
