plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

repositories {
    mavenCentral()
}

val kotestVersion = "5.9.1"

dependencies {
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
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
    baseline = file("$rootDir/config/detekt/core-baseline.xml")
}

ktlint {
    baseline.set(file("$rootDir/config/ktlint/core-baseline.xml"))
}
