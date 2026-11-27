package app.droidnative.diagnostics

/** Fixed contract for the owner-only intake. It never carries raw log text. */
internal class DiagnosticReport(
    model: String,
    android: String,
    sdk: Int,
    appVersion: String,
    versionCode: Long,
    emulator: Boolean,
    setup: Setup,
    symptom: Symptom,
) {
    enum class Setup(val wire: String) {
        UNKNOWN("not-tested"), FAILED("setup-failed"), COMPLETE("setup-complete")
    }

    enum class Symptom(val wire: String) {
        NOT_TESTED("not-tested"), DNS("apt-dns"), WAYLAND("wayland"),
        GRAPHICS("graphics"), SANDBOX("sandbox"), DEPENDENCY("dependency"), UNKNOWN("unknown")
    }

    private val fields = linkedMapOf(
        "model" to (model.filter { it in modelChars }.trim().take(49)
            .takeIf { it.firstOrNull()?.let { c -> c in alphaNumeric } == true } ?: "Unknown"),
        "android" to (android.takeIf { androidPattern.matches(it) } ?: sdk.coerceIn(1, 999).toString()),
        "sdk" to sdk.coerceIn(1, 999).toString(),
        "appVersion" to (appVersion.takeIf { appVersionPattern.matches(it) } ?: "0"),
        "versionCode" to (versionCode.takeIf { it in 0..999_999_999_999 }?.toString() ?: "unknown"),
        "build" to if (emulator) "emulator" else "physical",
        "runtime" to setup.wire,
        "symptom" to symptom.wire,
    )

    // All field names are fixed. Values have only the narrow ASCII alphabets
    // above; no escape characters, user content, command, URL or log lines.
    fun json(): String = fields.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }

    companion object {
        private val alphaNumeric = ('a'..'z').toSet() + ('A'..'Z').toSet() + ('0'..'9').toSet()
        private val modelChars = alphaNumeric + " ._+()-".toSet()
        private val androidPattern = Regex("[0-9][0-9A-Za-z.]{0,14}")
        private val appVersionPattern = Regex("[0-9][0-9A-Za-z.+-]{0,23}")
        private val rules = listOf(
            Symptom.DNS to Regex("temporary failure resolving|could not resolve", RegexOption.IGNORE_CASE),
            Symptom.WAYLAND to Regex("failed to connect to (the )?wayland|wayland display.*(not found|failed)|wayland-0.*no such file", RegexOption.IGNORE_CASE),
            Symptom.GRAPHICS to Regex("virgl\\.sock.*(refused|failed)|EGL_NOT_INITIALIZED|failed to create.*GL context", RegexOption.IGNORE_CASE),
            Symptom.SANDBOX to Regex("sandbox.*(failed|denied|not permitted)|canCreateUserNamespace.*(EPERM|failed)", RegexOption.IGNORE_CASE),
            Symptom.DEPENDENCY to Regex("error while loading shared libraries|cannot open shared object file", RegexOption.IGNORE_CASE),
        )

        fun classify(tail: String): Symptom {
            if (tail.isBlank()) return Symptom.NOT_TESTED
            val bounded = tail.takeLast(32_768)
            return rules.firstOrNull { (_, p) -> p.containsMatchIn(bounded) }?.first ?: Symptom.UNKNOWN
        }
    }
}
