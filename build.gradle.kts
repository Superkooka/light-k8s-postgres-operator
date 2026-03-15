import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt)
    application
    alias(libs.plugins.jib)
    alias(libs.plugins.ktlint)
}

group = "com.superkooka"
version = "early-dev"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // java-operator-sdk core
    implementation(platform(libs.josdk.bom))
    implementation(libs.josdk.framework)
    kapt(libs.fabric8.crd.generator)

    implementation(libs.postgresql)

    // loging
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    // Test
    testImplementation(libs.josdk.junit5)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.awaitility.kotlin)
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

val chartsDirectory = project.findProperty("chartsDirectory") as String? ?: "charts/"

tasks.register<Copy>("syncCrds") {
    group = "helm"

    val chartDir = chartsDirectory

    from(
        fileTree("build/tmp/kapt3/classes/main/META-INF/fabric8") {
            include("*.yml")
            exclude("*v1beta1*")
        },
    )
    into("$chartDir/crds")
    dependsOn("kaptKotlin")
}

tasks.register("syncHelmVersion") {
    group = "helm"

    val chartDir = chartsDirectory
    val appVersion = version.toString()

    doLast {
        val chartFile = File("$chartDir/Chart.yaml")
        chartFile.writeText(
            chartFile
                .readText()
                .replace(Regex("appVersion: .+# managed-by-gradle"), "appVersion: \"$appVersion\"  # managed-by-gradle"),
        )
        val valuesFile = File("$chartDir/values.yaml")
        valuesFile.writeText(
            valuesFile
                .readText()
                .replace(Regex("tag: .+# managed-by-gradle"), "tag: \"$appVersion\"  # managed-by-gradle"),
        )
        val valuesLocalFile = File("$chartDir/values.local.yaml")
        valuesLocalFile.writeText(
            valuesLocalFile
                .readText()
                .replace(Regex("tag: .+# managed-by-gradle"), "tag: \"$appVersion\"  # managed-by-gradle"),
        )
    }
}

tasks.named("jibDockerBuild") {
    notCompatibleWithConfigurationCache("Jib plugin is not compatible with configuration cache")
}

jib {
    from {
        image = "eclipse-temurin:21-jre-alpine"
    }
    to {
        image = "light-k8s-postgres-operator"
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
