package app.droidnative.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.droidnative.R
import app.droidnative.diagnostics.DiagnosticReport
import app.droidnative.env.EnvController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException

private const val INBOX = "https://galaxunil-device-debug.bbggkkk.chatgpt.site/#from-app"

@Composable
internal fun DiagnosticAction(modifier: Modifier = Modifier, appFailureTail: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            loading = true
            scope.launch {
                try {
                    preview = withContext(Dispatchers.IO) { collect(context, appFailureTail).json() }
                } catch (_: IOException) {
                    Toast.makeText(context, R.string.diagnostic_collect_failed, Toast.LENGTH_SHORT).show()
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    Toast.makeText(context, R.string.diagnostic_collect_failed, Toast.LENGTH_SHORT).show()
                } finally {
                    loading = false
                }
            }
        },
        modifier = modifier,
        enabled = !loading,
    ) { Text(stringResource(R.string.diagnostic_send)) }
    preview?.let { payload ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.diagnostic_send)) },
            text = {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.diagnostic_preview_hint))
                    Text(payload, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    if (clipboard == null) {
                        Toast.makeText(context, R.string.diagnostic_collect_failed, Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val clip = ClipData.newPlainText("Galaxunil diagnostic", payload)
                    if (Build.VERSION.SDK_INT >= 33) {
                        clip.description.extras = PersistableBundle().apply {
                            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                    }
                    clipboard.setPrimaryClip(clip)
                    preview = null
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(INBOX))
                            .addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.diagnostic_browser_missing, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.diagnostic_copy_open)) }
            },
            dismissButton = {
                TextButton(onClick = { preview = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Suppress("DEPRECATION")
private fun collect(context: Context, appFailureTail: String?): DiagnosticReport {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val status = EnvController.status.value.state
    val setup = when {
        status == EnvController.State.RUNNING || status == EnvController.State.STOPPED -> {
            if (File(context.filesDir, "setup.done").isFile) DiagnosticReport.Setup.COMPLETE
            else DiagnosticReport.Setup.UNKNOWN
        }
        status == EnvController.State.ERROR && appFailureTail == null -> DiagnosticReport.Setup.FAILED
        else -> DiagnosticReport.Setup.UNKNOWN
    }
    val symptom = when {
        appFailureTail != null -> DiagnosticReport.classify(appFailureTail)
        setup == DiagnosticReport.Setup.FAILED -> DiagnosticReport.classify(
            readPrivateLogTail(File(context.filesDir, "logs/setup.log")),
        )
        else -> DiagnosticReport.Symptom.NOT_TESTED
    }
    val emulated = Build.FINGERPRINT.startsWith("generic") ||
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
    return DiagnosticReport(
        Build.MODEL.orEmpty(), Build.VERSION.RELEASE.orEmpty(), Build.VERSION.SDK_INT,
        info.versionName.orEmpty(),
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
        emulated, setup, symptom,
    )
}

private fun readPrivateLogTail(file: File): String {
    if (!file.isFile) return ""
    return try {
        RandomAccessFile(file, "r").use {
            val size = minOf(it.length(), 32_768L)
            it.seek(it.length() - size)
            val data = ByteArray(size.toInt())
            it.readFully(data)
            data.toString(Charsets.UTF_8)
        }
    } catch (_: IOException) { "" }
}
