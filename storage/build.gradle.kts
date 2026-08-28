plugins {
    kotlin("jvm") version "2.4.0"
    `java-library`
}

repositories {
    mavenCentral()
}

val kotestVersion = "5.9.1"
val exposedVersion = "1.5.0"
val flywayVersion = "13.4.0"

dependencies {
    implementation(project(":core"))

    api("org.jetbrains.exposed:exposed-core:$exposedVersion")
    api("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-nc-sqlite:$flywayVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
