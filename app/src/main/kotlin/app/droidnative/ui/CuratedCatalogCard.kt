package app.droidnative.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droidnative.LaunchActivity
import app.droidnative.R
import app.droidnative.apps.AppSettingsStore
import app.droidnative.apps.CuratedCatalog
import app.droidnative.apps.DesktopEntry
import app.droidnative.apps.LinuxApps
import app.droidnative.apps.Xbps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class CatalogInventory(val installed: Set<String>, val entriesByPackage: Map<String, DesktopEntry>)

@Composable
internal fun CuratedCatalogCard(ready: Boolean) {
    val activity = LocalContext.current
    val context = activity.applicationContext
    val job by Xbps.job.collectAsStateWithLifecycle()
    val changes by Xbps.changes.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    var inventory by remember { mutableStateOf<CatalogInventory?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var terminal by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<DesktopEntry?>(null) }
    val busy = job?.running == true

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(ready, busy, changes, refresh) {
        if (!ready) {
            inventory = null
            error = null
        } else if (!busy) {
            inventory = null
            error = null
            try {
                inventory = withContext(Dispatchers.IO) {
                    val installed = Xbps.curatedInstalled(context)
                    val entries = LinuxApps(context).list().associateBy { it.id }
                    val matched = CuratedCatalog.apps.filter { it.packageName in installed }.mapNotNull { item ->
                        val owned = Xbps.curatedFiles(context, item.packageName)
                        CuratedCatalog.matchDesktopId(item, owned, entries.keys)
                            ?.let(entries::get)?.let { item.packageName to it }
                    }.toMap()
                    CatalogInventory(installed, matched)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message?.take(300).orEmpty()
            }
        }
    }

    val settings = remember(context) { AppSettingsStore(context) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.curated_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.curated_summary), style = MaterialTheme.typography.bodyMedium)
            when {
                !ready -> Text(stringResource(R.string.install_needs_env))
                busy -> Text(stringResource(R.string.curated_busy))
                error != null -> {
                    Text(stringResource(R.string.curated_query_error), color = MaterialTheme.colorScheme.error)
                    if (!error.isNullOrBlank()) Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
                inventory == null -> Text(stringResource(R.string.curated_checking))
            }
            job?.takeIf { current ->
                CuratedCatalog.apps.any { current.title == context.getString(R.string.xbps_job_install, it.name) }
            }?.let { XbpsJobCard(it, Modifier) { terminal = true } }
            for (item in CuratedCatalog.apps) {
                HorizontalDivider()
                val snapshot = inventory
                val entry = snapshot?.entriesByPackage?.get(item.packageName)
                val state = CuratedCatalog.state(snapshot?.installed, error != null, setOfNotNull(entry?.id), item)
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.curated_package, item.packageName), style = MaterialTheme.typography.bodySmall)
                if (ready && !busy) when (state) {
                    CuratedCatalog.State.OPEN -> if (entry != null) {
                        // read after dialog dismissal; dialog remains the existing per-entry settings store writer
                        val backend = settings.get(entry.id).backend?.arg ?: stringResource(R.string.curated_graphics_default)
                        Text(stringResource(R.string.curated_graphics, backend), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({
                                val uri = Uri.Builder().scheme(app.droidnative.apps.Shortcuts.SCHEME).authority("app").appendPath(entry.id).build()
                                activity.startActivity(Intent(Intent.ACTION_VIEW, uri, activity, LaunchActivity::class.java))
                            }) { Text(stringResource(R.string.curated_open)) }
                            OutlinedButton({ selected = entry }) { Text(stringResource(R.string.curated_configure)) }
                        }
                    }
                    CuratedCatalog.State.NOT_INSTALLED -> Button({
                        Xbps.installCurated(context, item.packageName, item.name)
                    }) { Text(stringResource(R.string.curated_install)) }
                    CuratedCatalog.State.INSTALLED_WITHOUT_LAUNCHER -> Text(stringResource(R.string.curated_no_launcher))
                    else -> Unit
                }
            }
            if (ready && !busy) TextButton({ refresh++ }) { Text(stringResource(R.string.curated_refresh)) }
        }
    }
    if (terminal) XbpsTerminalView { terminal = false }
    selected?.let { AppSettingsDialog(it) { selected = null; refresh++ } }
}
