package re.abbot.librecr.app.data

/** Local inter-app sharing is opt-in. Blank destinations means all compatible receivers. */
data class GlucoseBroadcastSettings(
    val enabled: Boolean = false,
    val destinationPackages: String = "",
)

private val packageSeparator = Regex("[\\s,;]+")
private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

/** Null means invalid input; never turn an invalid destination into an unrestricted broadcast. */
internal fun glucoseBroadcastDestinations(value: String): List<String>? {
    if (value.isBlank()) return emptyList()
    val packages = value.split(packageSeparator).filter { it.isNotBlank() }.distinct()
    return packages.takeIf { it.isNotEmpty() && it.all(packageName::matches) }
}
