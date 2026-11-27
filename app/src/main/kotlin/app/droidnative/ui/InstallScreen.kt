package app.droidnative.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droidnative.LaunchActivity
import app.droidnative.R
import app.droidnative.apps.*
import app.droidnative.env.EnvController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Locale

private val categories = linkedMapOf(
    "All" to R.string.store_all,
    "Network" to R.string.cat_internet,
    "Graphics" to R.string.cat_graphics,
    "AudioVideo" to R.string.cat_multimedia,
    "Office" to R.string.cat_office,
    "Development" to R.string.cat_development,
    "Game" to R.string.cat_games,
)

@Composable
fun InstallScreen() {
    val activity = LocalContext.current
    val context = activity.applicationContext
    val state by EnvController.status.collectAsStateWithLifecycle()
    val job by AptJobs.job.collectAsStateWithLifecycle()
    val ready = state.state in setOf(EnvController.State.STOPPED, EnvController.State.RUNNING)
    val busy = job?.running == true
    var snapshot by remember { mutableStateOf<DebianCatalog.Snapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var launchError by remember { mutableStateOf<String?>(null) }
    var runningEntry by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<DebianCatalog.App?>(null) }
    var removal by remember { mutableStateOf<DebianCatalog.App?>(null) }
    var removalPlan by remember { mutableStateOf<AptRemoval.Plan?>(null) }
    var removalFailure by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("All") }
    var change by remember { mutableIntStateOf(0) }
    var limit by remember { mutableIntStateOf(30) }
    var term by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val fallbackError = stringResource(R.string.store_load_error)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { change++ }
    LaunchedEffect(ready, busy, change) {
        if (!ready || busy) { snapshot = null; return@LaunchedEffect }
        error = null
        try { snapshot = withContext(Dispatchers.IO) { DebianCatalog.snapshot(context) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { snapshot = null; error = e.message?.takeLast(800) ?: fallbackError }
    }
    LaunchedEffect(removal, snapshot, busy) {
        removalPlan = null
        removalFailure = false
        val target = removal ?: return@LaunchedEffect
        val current = snapshot ?: return@LaunchedEffect
        if (busy) return@LaunchedEffect
        try {
            removalPlan = withContext(Dispatchers.IO) { DebianCatalog.removalPlan(context, current, target) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { removalFailure = true }
    }
    val rows = remember(snapshot, term, category) {
        val query = term.trim().lowercase(Locale.ROOT)
        snapshot?.apps.orEmpty().filter {
            (category == "All" || category in it.categories) &&
                (query.isEmpty() || "${it.name} ${it.pkg} ${it.summary}".lowercase(Locale.ROOT).contains(query))
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)) {
        item {
            Text(stringResource(R.string.store_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.store_subtitle))
            Text(stringResource(R.string.store_compatibility), style = MaterialTheme.typography.bodySmall)
            if (!ready) Text(stringResource(R.string.install_needs_env))
            else {
                OutlinedTextField(term, { term = it; limit = 30 }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.store_search)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    var expanded by remember { mutableStateOf(false) }
                    OutlinedButton({ expanded = true }) { Text(stringResource(R.string.store_category, stringResource(categories.getValue(category)))) }
                    DropdownMenu(expanded, { expanded = false }) {
                        categories.forEach { (value, label) -> DropdownMenuItem({ Text(stringResource(label)) }, {
                            category = value; limit = 30; expanded = false
                        }) }
                    }
                    OutlinedButton({ AptJobs.start(context, null, null, activity.getString(R.string.store_refresh_job)) }, enabled = !busy) {
                        Text(stringResource(R.string.store_refresh))
                    }
                }
                if (snapshot == null && error == null && !busy) Text(stringResource(R.string.store_loading))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                job?.let {
                    val label = when {
                        it.running -> R.string.store_job_running
                        it.error != null -> R.string.store_job_failed
                        else -> R.string.store_job_done
                    }
                    Text(stringResource(label, it.title))
                    if (it.running && it.lastLine.isNotBlank()) Text(it.lastLine, style = MaterialTheme.typography.bodySmall)
                    it.error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
                }
                launchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        items(rows.take(limit), key = { it.pkg }) { app ->
            val installed = snapshot?.installed?.contains(app.pkg) == true
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StoreAppIcon(app)
                        Column {
                            Text(app.name, style = MaterialTheme.typography.titleMedium)
                            Text(app.summary)
                            Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ launchError = null; detail = app }, enabled = !busy && !runningEntry) {
                            Text(stringResource(if (installed) R.string.store_installed_open else R.string.action_install))
                        }
                        if (installed && AptRemoval.eligible(app.pkg)) {
                            TextButton({
                                removalPlan = null
                                removalFailure = false
                                removal = app
                            }, enabled = !busy && !runningEntry) { Text(stringResource(R.string.store_remove)) }
                        }
                    }
                }
            }
        }
        if (rows.size > limit) item { OutlinedButton({ limit += 30 }) { Text(stringResource(R.string.store_more)) } }
        if (ready && snapshot != null && rows.isEmpty()) item { Text(stringResource(R.string.store_no_results)) }
    }
    detail?.let { app ->
        val current = snapshot
        val installed = current?.installed?.contains(app.pkg) == true
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(app.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (installed) stringResource(R.string.store_open_confirm)
                         else stringResource(R.string.store_install_confirm, app.pkg))
                    if (installed && current != null && Shortcuts.isSupported(activity)) {
                        TextButton({
                            runningEntry = true
                            scope.launch {
                                try {
                                    val launch = withContext(Dispatchers.IO) {
                                        DebianCatalog.entry(context, current, app)?.let { entry ->
                                            entry to (LinuxApps(context).icon(entry) ?: DebianCatalog.icon(context, app))
                                        }
                                    }
                                    if (launch == null) {
                                        launchError = activity.getString(R.string.store_launcher_missing, app.name)
                                    } else {
                                        val (entry, bitmap) = launch
                                        val ok = Shortcuts.pin(activity, entry, bitmap)
                                        Toast.makeText(context,
                                            if (ok) activity.getString(R.string.app_add_home_requested, entry.name)
                                            else activity.getString(R.string.app_add_home_unsupported),
                                            Toast.LENGTH_SHORT).show()
                                    }
                                    detail = null
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) {
                                    launchError = e.message?.takeLast(800)
                                    detail = null
                                }
                                finally { runningEntry = false }
                            }
                        }, enabled = !busy && !runningEntry) { Text(stringResource(R.string.app_add_home)) }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    detail = null
                    if (!installed) AptJobs.start(context, current, app, app.name)
                    else {
                        runningEntry = true
                        scope.launch {
                            try {
                                val entry = withContext(Dispatchers.IO) { DebianCatalog.entry(context, current, app) }
                                if (entry == null) launchError = activity.getString(R.string.store_launcher_missing, app.name)
                                else {
                                    val url = Uri.Builder().scheme(Shortcuts.SCHEME).authority("debian-app").appendPath(entry.id).build()
                                    activity.startActivity(Intent(Intent.ACTION_VIEW, url, activity, LaunchActivity::class.java))
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { launchError = e.message?.takeLast(800) }
                            finally { runningEntry = false }
                        }
                    }
                }, enabled = current != null && !busy && !runningEntry) { Text(stringResource(if (installed) R.string.store_open else R.string.action_install)) }
            },
            dismissButton = { TextButton({ detail = null }) { Text(stringResource(R.string.store_cancel)) } })
    }
    removal?.let { app ->
        val plan = removalPlan?.takeIf { it.target == app.pkg }
        val current = snapshot
        AlertDialog(
            onDismissRequest = { removal = null },
            title = { Text(stringResource(R.string.store_remove_title, app.name)) },
            text = {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        removalFailure -> Text(stringResource(R.string.store_remove_unavailable))
                        plan == null -> Text(stringResource(R.string.store_remove_checking))
                        plan.allowed -> Text(stringResource(R.string.store_remove_confirm, app.pkg))
                        else -> {
                            Text(stringResource(R.string.store_remove_blocked))
                            plan.removals.forEach { Text(it) }
                        }
                    }
                }
            },
            confirmButton = {
                if (plan?.allowed == true && current != null) {
                    TextButton({
                        removal = null
                        AptJobs.remove(context, current, app, plan,
                            activity.getString(R.string.store_remove_title, app.name))
                    }, enabled = !busy && app.pkg in current.installed) {
                        Text(stringResource(R.string.store_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = { TextButton({ removal = null }) { Text(stringResource(R.string.store_cancel)) } })
    }

}

@Composable
private fun StoreAppIcon(app: DebianCatalog.App) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.icon) {
        value = withContext(Dispatchers.IO) { DebianCatalog.icon(context, app)?.asImageBitmap() }
    }
    val image = bitmap
    if (image != null) Image(image, contentDescription = null, modifier = Modifier.size(44.dp))
    else Icon(painterResource(R.drawable.ic_penguin), null, modifier = Modifier.size(44.dp).padding(8.dp))
}
