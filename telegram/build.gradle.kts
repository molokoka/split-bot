plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    application
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("split.telegram.BotApplicationKt")
}

// Seeds test data for manual testing. workingDir is the repo root so a relative SPLIT_DB_PATH
// resolves the same way here as it does for anything else run from the root, unlike :telegram:run.
tasks.register<JavaExec>("seed") {
    group = "application"
    description = "Applies a named test scenario to the database (--args=\"<chatId> <scenario>\")"
    mainClass.set("split.telegram.SeedKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

val kotestVersion = "5.9.1"
val ktorVersion = "3.5.2"

dependencies {
    implementation(project(":core"))
    implementation(project(":storage"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.named("ktlintCheck"), tasks.named("detekt"))
}

kotlin {
    jvmToolchain(17)
}

detekt {
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    buildUponDefaultConfig = true
    baseline = file("$rootDir/config/detekt/telegram-baseline.xml")
}

ktlint {
    baseline.set(file("$rootDir/config/ktlint/telegram-baseline.xml"))
}
