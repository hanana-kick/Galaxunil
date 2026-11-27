package app.droidnative.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droidnative.R
import app.droidnative.diagnostics.LiveDiagnostics

@Composable
internal fun LiveDiagnosticToggle(modifier: Modifier = Modifier) {
    val state by LiveDiagnostics.state.collectAsStateWithLifecycle()
    val active = state == LiveDiagnostics.State.CONNECTING || state == LiveDiagnostics.State.LIVE
    var pairing by remember { mutableStateOf(false) }
    val title = stringResource(R.string.live_diagnostic_title)
    val detail = when (state) {
        LiveDiagnostics.State.OFF -> R.string.live_diagnostic_hint
        LiveDiagnostics.State.CONNECTING -> R.string.live_diagnostic_connecting
        LiveDiagnostics.State.LIVE -> R.string.live_diagnostic_active
        LiveDiagnostics.State.REJECTED -> R.string.live_diagnostic_rejected
        LiveDiagnostics.State.UNREACHABLE -> R.string.live_diagnostic_unreachable
    }
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(detail), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = active, onCheckedChange = { enabled ->
            if (enabled) pairing = true else LiveDiagnostics.stop()
        }, modifier = Modifier.semantics { contentDescription = title })
    }
    if (pairing) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pairing = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.live_diagnostic_pair_hint))
                    OutlinedTextField(value = code, onValueChange = { if (it.length <= 4 && it.all { char -> char in '0'..'9' }) code = it },
                        label = { Text(stringResource(R.string.live_diagnostic_code)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(onClick = { LiveDiagnostics.start(code); pairing = false },
                    enabled = LiveDiagnostics.accepts(code)) { Text(stringResource(R.string.live_diagnostic_connect)) }
            },
            dismissButton = {
                TextButton(onClick = { pairing = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
