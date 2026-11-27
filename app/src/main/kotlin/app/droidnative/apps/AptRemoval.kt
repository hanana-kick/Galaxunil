package app.droidnative.apps

/** Fail closed: this UI removes exactly one installed app, never its dependents. */
internal object AptRemoval {
    // The bootstrap and session packages are not user-removable even if catalogued.
    private val runtime = setOf(
        "apt", "dpkg", "ca-certificates", "python3-yaml", "dbus", "pulseaudio",
        "pulseaudio-utils", "xkb-data", "libxkbcommon-tools", "mesa-libgallium",
        "libgl1-mesa-dri", "libegl1", "mesa-utils", "fonts-dejavu-core",
        "fonts-noto-cjk", "librsvg2-bin",
    )
    private val packageName = Regex("[a-z0-9][a-z0-9+.-]{1,127}")
    private val action = Regex("""^(Remv|Purg|Inst|Conf)\s+(\S+).*$""")
    private val summary = Regex("""^0 upgraded, 0 newly installed, (\d+) to remove and \d+ not upgraded\.$""")

    data class Plan(val target: String, val removals: List<String>) {
        val allowed: Boolean get() = eligible(target) && removals == listOf(target)
    }

    fun eligible(target: String): Boolean = packageName.matches(target) && target !in runtime

    fun unprotected(status: String): Boolean {
        val fields = status.trimEnd('\r', '\n').split('\t')
        return fields.size == 4 && fields[0] == "installed" &&
            fields[1] in setOf("", "no") && fields[2] in setOf("", "no") &&
            fields[3] in setOf("optional", "extra")
    }

    fun parse(target: String, output: String): Plan {
        require(eligible(target))
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val actions = lines.mapNotNull(action::matchEntire)
        val total = lines.mapNotNull { summary.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        check(actions.isNotEmpty() && actions.all { it.groupValues[1] == "Remv" } && total == listOf(actions.size)) {
            "APT did not return a single-purpose removal plan."
        }
        val packages = actions.map { it.groupValues[2] }
        check(packages.all(packageName::matches) && packages.distinct().size == packages.size && target in packages) {
            "APT returned an unknown removal target."
        }
        return Plan(target, packages)
    }
}
