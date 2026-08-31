plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "no.skasti.serialmodem"
version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0-SNAPSHOT")

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fazecast:jSerialComm:2.11.2")
    implementation("com.github.ajalt.mordant:mordant:3.0.2")
    implementation("org.jline:jline-terminal-jni:3.30.16")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("no.skasti.serialmodem.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
