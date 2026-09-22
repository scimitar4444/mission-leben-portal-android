package de.missionleben.portal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.missionleben.portal.R

/** The same compact portal navigation for native pages and all protected WebViews. */
@Composable
fun PortalHeader(title: String, onBack: () -> Unit, onRefresh: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.browser_back), maxLines = 1)
            }
            Text(title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (onRefresh != null) TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.refresh), maxLines = 1)
            }
        }
    }
}
