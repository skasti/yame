package no.skasti.yame.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationArchitectureTest {
    private val sources = Path.of("src/main/kotlin/no/skasti/yame")

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
    fun `only persistence and startup know the application file representation`() {
        Files.walk(sources).use { files ->
            files.filter { it.toString().endsWith(".kt") }.forEach { file ->
                val relative = sources.relativize(file).toString()
                if (relative == "Main.kt" || file.startsWith(sources.resolve("persistence"))) return@forEach
                val text = Files.readString(file)
                assertFalse(text.contains("YameConfiguration"), "$file imports application configuration")
                assertFalse(text.contains("no.skasti.yame.persistence"), "$file imports persistence")
                assertFalse(text.contains("ConfigurationBinding"), "$file owns an application binding")
            }
        }
    }
}
