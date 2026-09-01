package no.skasti.serialmodem

import java.util.Properties

object BuildInfo {
    private val properties: Properties by lazy {
        Properties().apply {
            BuildInfo::class.java.getResourceAsStream("/yame-build.properties")?.use(::load)
        }
    }

    val version: String
        get() = properties.getProperty("version", "development")

    val gitCommit: String
        get() = properties.getProperty("gitCommit", "unknown")

    val display: String
        get() = "v$version ($gitCommit)"
}
