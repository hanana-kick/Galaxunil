package app.droidnative.apps

import app.droidnative.diagnostics.LiveDiagnostics
import app.droidnative.diagnostics.LiveDiagnostics.Event
import android.content.Context
import app.droidnative.env.EnvPaths
import app.droidnative.env.Proot
import org.json.JSONObject

internal object DebianCatalog {
    data class App(val pkg: String, val name: String, val summary: String,
                   val desktops: Set<String>, val categories: Set<String>, val icon: String? = null)
    data class Snapshot(val apps: List<App>, val installed: Set<String>)
    val packageName = Regex("[a-z0-9][a-z0-9+.-]{1,127}")
    private val iconName = Regex("[A-Za-z0-9][A-Za-z0-9_.+-]{0,159}\\.png")
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
            values("desktop").filter { desktopName.matches(it) }.toSet(), values("categories"),
            obj.optString("icon", "").takeIf { iconName.matches(it) })
    }.filter { packageName.matches(it.pkg) }.toList()

    fun snapshot(context: Context): Snapshot {
        val proot = Proot(EnvPaths(context))
        val catalog = proot.run(listOf("/usr/bin/python3", "/usr/libexec/droidnative/debian-catalog.py"),
            timeoutMs = 90_000, maxOutput = 12 shl 20)
        check(catalog.ok) { catalog.output.takeLast(1500) }
        val indexed = decode(catalog.output)
        val firefox = indexed.firstOrNull { it.pkg == featured.pkg }
        val rows = (listOf(featured.copy(icon = firefox?.icon)) + indexed.filterNot { it.pkg == featured.pkg })
            .distinctBy { it.pkg }.sortedWith(compareBy<App> { it.pkg != featured.pkg }.thenBy { it.name.lowercase() })
        val receipt = proot.run(listOf("/usr/bin/dpkg-query", "-W", "-f", "\${db:Status-Status}\t\${Package}\n"),
            timeoutMs = 30_000, maxOutput = 2 shl 20)
        check(receipt.ok) { receipt.output.takeLast(1000) }
        val installed = receipt.output.lineSequence().mapNotNull {
            it.substringAfter("installed\t", "").takeIf { name -> it.startsWith("installed\t") && packageName.matches(name) }
        }.toSet()
        return Snapshot(rows, installed)
    }

    /** Icons are decorations only; use bounded, app-private, signed-index cache bytes. */
    fun icon(context: Context, app: App): android.graphics.Bitmap? {
        val name = app.icon?.takeIf(iconName::matches) ?: return null
        return runCatching {
            val dir = java.io.File(EnvPaths(context).runDir, "catalog-icons").canonicalFile
            val requested = java.io.File(dir, name)
            val file = requested.canonicalFile
            if (file.parentFile != dir || file != requested || file.length() !in 24L..262_144L) return null
            val opts = android.graphics.BitmapFactory.Options().also { it.inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, opts)
            if (opts.outWidth != 64 || opts.outHeight != 64 || opts.outMimeType != "image/png") return null
            android.graphics.BitmapFactory.decodeFile(file.path)
        }.getOrNull()
    }

    fun entry(context: Context, snapshot: Snapshot, app: App): DesktopEntry? {
        if (app.pkg !in snapshot.installed || app.desktops.isEmpty()) return null
        val files = Proot(EnvPaths(context)).run(listOf("/usr/bin/dpkg-query", "-L", app.pkg), timeoutMs = 15_000)
        check(files.ok) { files.output.takeLast(1500) }
        val owned = files.output.lineSequence().toSet()
        return app.desktops.asSequence().filter { "/usr/share/applications/$it" in owned }
            .map { it.removeSuffix(".desktop") }.firstNotNullOfOrNull(LinuxApps(context)::systemDesktopEntry)
    }

    /** Inspect using APT's resolver without changing any packages. */
    fun removalPlan(context: Context, snapshot: Snapshot, target: App): AptRemoval.Plan {
        require(target in snapshot.apps && target.pkg in snapshot.installed && AptRemoval.eligible(target.pkg))
        val proot = Proot(EnvPaths(context))
        val env = mapOf("LC_ALL" to "C", "DEBIAN_FRONTEND" to "noninteractive")
        val status = proot.run(listOf("/usr/bin/dpkg-query", "-W", "-f",
            "\${db:Status-Status}\t\${Essential}\t\${Protected}\t\${Priority}\n", "--", target.pkg),
            extraEnv = env, timeoutMs = 15_000)
        check(status.ok && AptRemoval.unprotected(status.output)) { "This package is not eligible for removal." }
        val simulation = proot.run(listOf("/usr/bin/apt-get", "--simulate", "--no-auto-remove",
            "--no-purge", "remove", "--", target.pkg), extraEnv = env, root = true, timeoutMs = 60_000)
        check(simulation.ok) { simulation.output.takeLast(1500) }
        return AptRemoval.parse(target.pkg, simulation.output)
    }

    fun remove(context: Context, snapshot: Snapshot, target: App, approved: AptRemoval.Plan, onLine: (String) -> Unit) {
        require(approved.allowed && approved.target == target.pkg)
        check(removalPlan(context, snapshot, target) == approved) { "The removal plan changed. Review it again." }
        // Never execute APT's mutable dependency plan. dpkg receives exactly the
        // approved package, refuses broken dependencies/protected packages, and
        // --remove (unlike --purge) retains package configuration files.
        runPackageJob(context, listOf("/usr/bin/dpkg", "--refuse-depends", "--refuse-remove-essential",
            "--refuse-remove-protected", "--remove", "--", target.pkg), onLine)
    }

    fun run(context: Context, snapshot: Snapshot?, target: App?, onLine: (String) -> Unit) {
        val args = if (target == null) {
            listOf("/usr/bin/apt-get", "-o", "APT::Update::Error-Mode=any", "update")
        } else {
            require(snapshot?.apps?.contains(target) == true && packageName.matches(target.pkg))
            listOf("/usr/bin/apt-get", "-y", "--no-install-recommends", "install", "--", target.pkg)
        }
        runPackageJob(context, args, onLine)
    }

    private fun runPackageJob(context: Context, args: List<String>, onLine: (String) -> Unit) {
        val paths = EnvPaths(context)
        paths.logDir.mkdirs()
        val log = java.io.File(paths.logDir, "catalog-apt.log")
        val res = log.bufferedWriter().use { writer ->
            Proot(paths).run(args, extraEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
                root = true, timeoutMs = 900_000, onLine = { line ->
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                    onLine(line)
                })
        }
        check(res.ok) { res.output.takeLast(1800) }
        LinuxApps.invalidate()
    }
}

internal object AptJobs {
    data class Job(val title: String, val running: Boolean, val lastLine: String = "", val error: String? = null)
    private val _job = kotlinx.coroutines.flow.MutableStateFlow<Job?>(null)
    val job: kotlinx.coroutines.flow.StateFlow<Job?> = _job
    fun start(context: Context, snapshot: DebianCatalog.Snapshot?, app: DebianCatalog.App?, title: String) {
        startJob(title) { DebianCatalog.run(context.applicationContext, snapshot, app, it) }
    }

    fun remove(context: Context, snapshot: DebianCatalog.Snapshot, app: DebianCatalog.App,
               approved: AptRemoval.Plan, title: String) {
        startJob(title) { DebianCatalog.remove(context.applicationContext, snapshot, app, approved, it) }
    }

    @Synchronized private fun startJob(title: String, run: ((String) -> Unit) -> Unit) {
        if (_job.value?.running == true) return
        _job.value = Job(title, true)
        LiveDiagnostics.event(Event.CATALOG_START)
        kotlin.concurrent.thread(name = "debian-apt") {
            val result = runCatching {
                run { line ->
                    _job.value = Job(title, true, line.takeLast(350))
                }
            }
            _job.value = Job(title, false, error = result.exceptionOrNull()?.message)
            LiveDiagnostics.event(if (result.isSuccess) Event.CATALOG_COMPLETE else Event.CATALOG_FAILED)
        }
    }
}
