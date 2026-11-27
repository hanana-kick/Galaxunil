package app.droidnative.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

private val categories = listOf("All", "Network", "Graphics", "AudioVideo", "Office", "Development", "Game")

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
    var category by remember { mutableStateOf("All") }
    var change by remember { mutableIntStateOf(0) }
    var limit by remember { mutableIntStateOf(30) }
    var term by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { change++ }
    LaunchedEffect(ready, busy, change) {
        if (!ready || busy) { snapshot = null; return@LaunchedEffect }
        error = null
        try { snapshot = withContext(Dispatchers.IO) { DebianCatalog.snapshot(context) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { snapshot = null; error = e.message?.takeLast(800) ?: "Unable to read Debian metadata." }
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
            Text("Debian 앱 스토어", style = MaterialTheme.typography.headlineSmall)
            Text("Debian 13 · 서명된 APT 저장소 · AppStream")
            Text("Wayland 데스크톱 앱 대상. 설치 가능 여부와 실행 호환성은 다를 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            if (!ready) Text(stringResource(R.string.install_needs_env))
            else {
                OutlinedTextField(term, { term = it; limit = 30 }, Modifier.fillMaxWidth(),
                    label = { Text("앱 검색") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    var expanded by remember { mutableStateOf(false) }
                    OutlinedButton({ expanded = true }) { Text("분류: $category") }
                    DropdownMenu(expanded, { expanded = false }) {
                        categories.forEach { value -> DropdownMenuItem({ Text(value) }, {
                            category = value; limit = 30; expanded = false
                        }) }
                    }
                    OutlinedButton({ AptJobs.start(context, null, null, "저장소 새로고침") }, enabled = !busy) {
                        Text("새로고침")
                    }
                }
                if (snapshot == null && error == null && !busy) Text("앱 목록을 확인하고 있습니다.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                job?.let {
                    Text((if (it.running) "진행 중: " else if (it.error != null) "실패: " else "완료: ") + it.title)
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
                    Text(app.name, style = MaterialTheme.typography.titleMedium)
                    Text(app.summary)
                    Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                    Button({ launchError = null; detail = app }, enabled = !busy && !runningEntry) {
                        Text(if (installed) "설치됨 · 열기" else "설치")
                    }
                }
            }
        }
        if (rows.size > limit) item { OutlinedButton({ limit += 30 }) { Text("더 보기") } }
        if (ready && snapshot != null && rows.isEmpty()) item { Text("검색 결과가 없습니다.") }
    }
    detail?.let { app ->
        val current = snapshot
        val installed = current?.installed?.contains(app.pkg) == true
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(app.name) },
            text = { Text(if (installed) "Debian 패키지의 데스크톱 실행 항목을 확인합니다." else "서명된 Debian 저장소에서 ${app.pkg} 및 필요한 의존성을 설치합니다.") },
            confirmButton = {
                TextButton({
                    detail = null
                    if (!installed) AptJobs.start(context, current, app, app.name)
                    else {
                        runningEntry = true
                        scope.launch {
                            try {
                                val entry = withContext(Dispatchers.IO) { DebianCatalog.entry(context, current, app) }
                                if (entry == null) launchError = "${app.name}: 패키지가 소유한 실행 항목을 찾지 못했습니다."
                                else {
                                    val url = Uri.Builder().scheme(Shortcuts.SCHEME).authority("debian-app").appendPath(entry.id).build()
                                    activity.startActivity(Intent(Intent.ACTION_VIEW, url, activity, LaunchActivity::class.java))
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { launchError = e.message?.takeLast(800) }
                            finally { runningEntry = false }
                        }
                    }
                }, enabled = current != null && !busy) { Text(if (installed) "열기" else "설치") }
            },
            dismissButton = { TextButton({ detail = null }) { Text("취소") } })
    }
}
