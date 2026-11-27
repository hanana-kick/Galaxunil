package app.droidnative.apps

/** Static XBPS allowlist: never translate a URL, query, or Intent extra into a package request. */
internal object CuratedCatalog {
    data class App(val packageName: String, val desktopIds: Set<String>, val name: String)
    val apps = listOf(
        App("firefox", setOf("firefox", "org.mozilla.firefox"), "Firefox"),
        App("gimp", setOf("org.gimp.GIMP", "gimp"), "GIMP"),
        App("inkscape", setOf("org.inkscape.Inkscape", "inkscape"), "Inkscape"),
        App("audacity", setOf("audacity", "org.audacityteam.Audacity"), "Audacity"),
    )
    fun accepts(packageName: String) = apps.any { it.packageName == packageName }

    fun matchDesktopId(app: App, ownedFiles: Set<String>, visibleIds: Set<String>): String? =
        app.desktopIds.firstOrNull { it in visibleIds && "/usr/share/applications/$it.desktop" in ownedFiles }

    enum class State { CHECKING, CHECK_FAILED, NOT_INSTALLED, INSTALLED_WITHOUT_LAUNCHER, OPEN }
    fun state(installed: Set<String>?, failed: Boolean, desktopIds: Set<String>, app: App): State = when {
        failed -> State.CHECK_FAILED
        installed == null -> State.CHECKING
        app.packageName !in installed -> State.NOT_INSTALLED
        desktopIds.none { it in app.desktopIds } -> State.INSTALLED_WITHOUT_LAUNCHER
        else -> State.OPEN
    }

    // XBPS list markers: only ii denotes actually installed/configured, not uu or hr.
    fun installedNames(output: String): Set<String> = output.lineSequence()
        .mapNotNull { installedLine.find(it)?.groupValues?.get(1)?.substringBeforeLast('-') }
        .toSet()

    private val installedLine = Regex("""^ii\s+(\S+)\s""")
}
