package no.skasti.yame.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationArchitectureTest {
    private val sources = Path.of("src/main/kotlin/no/skasti/yame")

    @Test fun `production consumers only use the installed configuration facade`() {
        Files.walk(sources).use { files ->
            files.filter { it.toString().endsWith(".kt") }.forEach { file ->
                val relative = sources.relativize(file).toString()
                if (relative.startsWith("config/")) return@forEach
                val text = Files.readString(file)
                assertFalse(Regex("\\b\\w+\\s*:\\s*ConfigStore\\b").containsMatchIn(text),
                    "$file accepts or retains a ConfigStore instance")
                assertFalse(text.contains("ConfigStore.current"), "$file accesses the instance instead of the facade")
                if (relative != "Main.kt" && relative != "persistence/IniConfigStore.kt") {
                    assertFalse(Regex("\\bConfigStore\\(").containsMatchIn(text), "$file constructs a local store")
                }
            }
        }
    }

    @Test
    fun `presentation and core do not own one another`() {
        for (folder in listOf("ui", "presentation", "runtime")) {
            Files.walk(sources.resolve(folder)).use { files ->
                files.filter { it.toString().endsWith(".kt") }.forEach { file ->
                    val text = Files.readString(file)
                    if (folder == "runtime") {
                        assertFalse(text.contains("no.skasti.yame.ui."), "$file depends on TUI")
                        assertFalse(text.contains("no.skasti.yame.presentation."), "$file depends on presentation")
                    } else {
                        assertFalse(text.contains("no.skasti.yame.runtime."), "$file depends on runtime")
                        assertFalse(text.contains("NavigationResourceRegistry"), "$file owns a resource registry")
                        assertFalse(text.contains("YameRegistries"), "$file resolves a resource registry")
                    }
                }
            }
        }
    }

    @Test
    fun `generic stores have no feature persistence or frontend dependencies`() {
        for (folder in listOf("config", "function")) {
            Files.walk(sources.resolve(folder)).use { files ->
                files.filter { it.toString().endsWith(".kt") }.forEach { file ->
                    val text = Files.readString(file)
                    val dependencies = Regex("no\\.skasti\\.yame\\.[A-Za-z0-9_.]+").findAll(text)
                        .map { it.value }.toList()
                    assertTrue(dependencies.all { it.startsWith("no.skasti.yame.$folder") }, "$file: $dependencies")
                }
            }
        }
    }

    @Test
    fun `no production source retains the aggregate or binding layer`() {
        Files.walk(sources).use { files ->
            files.filter { it.toString().endsWith(".kt") }.forEach { file ->
                val relative = sources.relativize(file).toString()
                val text = Files.readString(file)
                assertFalse(text.contains("YameConfiguration"), "$file imports application configuration")
                if (relative != "Main.kt" && !file.startsWith(sources.resolve("persistence"))) {
                    assertFalse(text.contains("no.skasti.yame.persistence"), "$file imports persistence")
                }
                assertFalse(text.contains("ConfigurationBinding"), "$file owns an application binding")
            }
        }
    }
}
