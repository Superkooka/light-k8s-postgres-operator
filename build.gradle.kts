import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("kapt") version "2.3.10"
    application
    id("com.google.cloud.tools.jib") version "3.5.2"
    id("org.jlleitschuh.gradle.ktlint") version "14.1.0"
}

group = "com.superkooka"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val josdkVersion = "5.2.3"

dependencies {
    // java-operator-sdk core
    implementation(platform("io.javaoperatorsdk:operator-framework-bom:$josdkVersion"))
    implementation("io.javaoperatorsdk:operator-framework")
    kapt("io.javaoperatorsdk:operator-framework:$josdkVersion")

    // postgresql-connector
    implementation("org.postgresql:postgresql:42.7.9")

    // loging
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")

    // Test
    testImplementation("io.javaoperatorsdk:operator-framework-junit-5")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.awaitility:awaitility-kotlin:4.3.0")
}

application {
    mainClass.set("com.superkooka.operator.postgres.Main")
}

ktlint {
    verbose.set(true)
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict") // strict java's nullability annotation
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("e2e")
    }
}

tasks.register<Test>("e2eTest") {
    useJUnitPlatform {
        includeTags("e2e")
    }
}

tasks.register("syncHelmVersion") {
    group = "helm"
    doLast {
        val chartFile = file("charts/my-operator/Chart.yaml")
        chartFile.writeText(
            chartFile
                .readText()
                .replace(Regex("version: .+"), "version: $version")
                .replace(Regex("appVersion: .+"), "appVersion: \"$version\""),
        )
        val valuesFile = file("charts/my-operator/values.yaml")
        valuesFile.writeText(
            valuesFile
                .readText()
                .replace(Regex("tag: .+"), "tag: \"$version\""),
        )
    }
}

tasks.register<Copy>("syncCrds") {
    group = "helm"
    from(fileTree("build/tmp/kapt3/classes/main/META-INF/fabric8") { include("*.yml") })
    into("charts/my-operator/templates/crds")
    dependsOn("kaptKotlin")
}

tasks.named("syncHelmVersion") {
    dependsOn("syncCrds")
}

jib {
    from {
        image = "eclipse-temurin:21-jre-alpine"
    }
    to {
        image = "my-operator"
        tags = setOf(project.version.toString())
    }
    container {
        jvmFlags =
            listOf(
                "-XX:+UseContainerSupport",
                "-XX:MaxRAMPercentage=75.0",
                "-XX:+ExitOnOutOfMemoryError",
            )
        user = "1000"
    }
}

tasks.named("jib") {
    enabled = false
}
