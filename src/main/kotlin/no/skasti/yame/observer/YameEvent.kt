package no.skasti.yame.observer

/** Stable identity and schema version are metadata, separate from the payload map. */
interface YameEvent : Map<String, Any?> {
    val typeName: String
    val version: VersionNumber
}

data class VersionNumber(val major: Int, val minor: Int, val patch: Int = 0) {
    init { require(major >= 0 && minor >= 0 && patch >= 0) }

    override fun toString(): String = "$major.$minor.$patch"
}
