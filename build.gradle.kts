plugins {
    kotlin("jvm") version "2.4.20"
    application
}

group = "no.skasti.yame"
version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0-SNAPSHOT")

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fazecast:jSerialComm:2.11.4")
    implementation("com.github.ajalt.mordant:mordant:3.1.0")
    implementation("org.jline:jline-terminal-jni:4.4.5")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("no.skasti.yame.MainKt")
}

tasks.test {
    useJUnitPlatform()
}


val yameGitCommit = providers.provider {
    runCatching {
        val process = ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val value = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && value.isNotBlank()) value else "unknown"
    }.getOrDefault("unknown")
}

val generatedBuildInfoDir = layout.buildDirectory.dir("generated/resources/buildInfo")
val generateBuildInfo by tasks.registering {
    inputs.property("version", project.version.toString())
    inputs.property("gitCommit", yameGitCommit)
    outputs.dir(generatedBuildInfoDir)

    doLast {
        val output = generatedBuildInfoDir.get().file("yame-build.properties").asFile
        output.parentFile.mkdirs()
        output.writeText(
            "version=${project.version}\n" +
                "gitCommit=${yameGitCommit.get()}\n"
        )
    }
}

sourceSets.named("main") {
    resources.srcDir(generatedBuildInfoDir)
}

tasks.processResources {
    dependsOn(generateBuildInfo)
}
