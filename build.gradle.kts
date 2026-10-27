plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "no.skasti.yame"
version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0-SNAPSHOT")

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.fazecast:jSerialComm:2.11.4")
    implementation("com.github.ajalt.mordant:mordant:3.1.0")
    implementation("org.jline:jline-terminal-jni:4.4.8")
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
    exclude("**/ResourceLifecycleEventTest.class", "**/YameRuntimeConnectionTest.class", "**/YameEventsLazyTest.class", "**/RuntimeObservationIntegrationTest.class", "**/InteractiveYameApplicationTest.class", "**/TuiShutdownIntegrationTest.class", "**/FunctionStoreDefaultsTest.class", "**/ApplicationPresentationTest.class", "**/YameRuntimeLifecycleTest.class")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Global default/facade tests require a fresh JVM per class; ordinary broker tests stay isolated in-process.
val eventFacadeTest by tasks.registering(Test::class) {
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("yame.test.runtimeClasspath", sourceSets.test.get().runtimeClasspath.asPath)
    include("**/ResourceLifecycleEventTest.class", "**/YameRuntimeConnectionTest.class", "**/YameEventsLazyTest.class", "**/RuntimeObservationIntegrationTest.class", "**/InteractiveYameApplicationTest.class", "**/TuiShutdownIntegrationTest.class", "**/FunctionStoreDefaultsTest.class", "**/ApplicationPresentationTest.class", "**/YameRuntimeLifecycleTest.class")
    forkEvery = 1
    maxParallelForks = 1
    useJUnitPlatform()
}

tasks.named("check") { dependsOn(eventFacadeTest) }


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
