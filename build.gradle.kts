plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "no.skasti.serialmodem"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fazecast:jSerialComm:2.11.2")
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
