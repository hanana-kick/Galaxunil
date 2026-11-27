package app.droidnative.apps

import android.content.Context
import app.droidnative.env.EnvPaths
import app.droidnative.env.Proot
import org.json.JSONObject

internal object DebianCatalog {
    data class App(val pkg: String, val name: String, val summary: String,
                   val desktops: Set<String>, val categories: Set<String>)
    data class Snapshot(val apps: List<App>, val installed: Set<String>)
    val packageName = Regex("[a-z0-9][a-z0-9+.-]{1,127}")
    private val desktopName = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,159}\\.desktop")
    private val featured = App("firefox-esr", "Firefox ESR", "Debian Extended Support Release",
        setOf("firefox-esr.desktop", "org.mozilla.firefox-esr.desktop"), setOf("Network", "WebBrowser"))

    internal fun decode(raw: String): List<App> = raw.lineSequence().filter { it.isNotEmpty() }.map { line ->
        val obj = JSONObject(line)
        fun values(key: String): Set<String> {
            val a = obj.getJSONArray(key)
            return (0 until minOf(a.length(), 32)).map { a.getString(it) }.toSet()
        }
        App(obj.getString("package"), obj.getString("name"), obj.getString("summary"),
            values("desktop").filter { desktopName.matches(it) }.toSet(), values("categories"))
    }.filter { packageName.matches(it.pkg) }.toList()

    fun snapshot(context: Context): Snapshot {
        val proot = Proot(EnvPaths(context))
        val catalog = proot.run(listOf("/usr/bin/python3", "/usr/libexec/droidnative/debian-catalog.py"),
            timeoutMs = 90_000, maxOutput = 12 shl 20)
        check(catalog.ok) { catalog.output.takeLast(1500) }
        val rows = (listOf(featured) + decode(catalog.output).filterNot { it.pkg == featured.pkg })
            .distinctBy { it.pkg }.sortedWith(compareBy<App> { it.pkg != featured.pkg }.thenBy { it.name.lowercase() })
        val receipt = proot.run(listOf("/usr/bin/dpkg-query", "-W", "-f", "\${db:Status-Status}\t\${Package}\n"),
            timeoutMs = 30_000, maxOutput = 2 shl 20)
        check(receipt.ok) { receipt.output.takeLast(1000) }
        val installed = receipt.output.lineSequence().mapNotNull {
            it.substringAfter("installed\t", "").takeIf { name -> it.startsWith("installed\t") && packageName.matches(name) }
        }.toSet()
        return Snapshot(rows, installed)
    }

    fun entry(context: Context, snapshot: Snapshot, app: App): DesktopEntry? {
        if (app.pkg !in snapshot.installed || app.desktops.isEmpty()) return null
        val files = Proot(EnvPaths(context)).run(listOf("/usr/bin/dpkg-query", "-L", app.pkg), timeoutMs = 15_000)
        check(files.ok) { files.output.takeLast(1500) }
        val owned = files.output.lineSequence().toSet()
        return app.desktops.asSequence().filter { "/usr/share/applications/$it" in owned }
            .map { it.removeSuffix(".desktop") }.firstNotNullOfOrNull(LinuxApps(context)::systemDesktopEntry)
    }

    fun run(context: Context, snapshot: Snapshot?, target: App?, onLine: (String) -> Unit) {
        val args = if (target == null) {
            listOf("/usr/bin/apt-get", "-o", "APT::Update::Error-Mode=any", "update")
        } else {
            require(snapshot?.apps?.contains(target) == true && packageName.matches(target.pkg))
            listOf("/usr/bin/apt-get", "-y", "--no-install-recommends", "install", "--", target.pkg)
        }
        val res = Proot(EnvPaths(context)).run(args, extraEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
            root = true, timeoutMs = 900_000, onLine = onLine)
        check(res.ok) { res.output.takeLast(1800) }
        LinuxApps.invalidate()
    }
}

internal object AptJobs {
    data class Job(val title: String, val running: Boolean, val lastLine: String = "", val error: String? = null)
    private val _job = kotlinx.coroutines.flow.MutableStateFlow<Job?>(null)
    val job: kotlinx.coroutines.flow.StateFlow<Job?> = _job
    @Synchronized fun start(context: Context, snapshot: DebianCatalog.Snapshot?, app: DebianCatalog.App?, title: String) {
        if (_job.value?.running == true) return
        _job.value = Job(title, true)
        kotlin.concurrent.thread(name = "debian-apt") {
            val result = runCatching {
                DebianCatalog.run(context.applicationContext, snapshot, app) { line ->
                    _job.value = Job(title, true, line.takeLast(350))
                }
            }
            _job.value = Job(title, false, error = result.exceptionOrNull()?.message)
        }
    }
}
