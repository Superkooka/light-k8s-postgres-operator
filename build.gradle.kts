import io.fabric8.crd.generator.collector.CustomResourceCollector
import io.fabric8.crdv2.generator.CRDGenerationInfo
import io.fabric8.crdv2.generator.CRDGenerator
import java.nio.file.Files

buildscript {
    dependencies {
        classpath(libs.fabric8.crd.generator.v2)
        classpath(libs.fabric8.crd.collector)
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.jib)
    alias(libs.plugins.ktlint)
}

group = "com.superkooka"
version = "1.0.0-alpha.1"

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

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict") // strict java's nullability annotation
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
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

// From https://github.com/fabric8io/kubernetes-client/blob/main/crd-generator/gradle/README.md
tasks.register("generateCrds") {
    description = "Generate CRDs from compiled custom resource classes"
    group = "crd"

    val sourceSet = project.sourceSets["main"]

    val compileClasspathElements = sourceSet.compileClasspath.map { e -> e.absolutePath }

    val outputClassesDirs = sourceSet.output.classesDirs
    val outputClasspathElements = outputClassesDirs.map { d -> d.absolutePath }

    val classpathElements = listOf(outputClasspathElements, compileClasspathElements).flatten()
    val filesToScan = outputClassesDirs.filter { it.exists() }.toList()
    val outputDir = sourceSet.output.resourcesDir

    doLast {
        Files.createDirectories(outputDir!!.toPath())

        val collector =
            CustomResourceCollector()
                .withParentClassLoader(Thread.currentThread().contextClassLoader)
                .withClasspathElements(classpathElements)
                .withFilesToScan(filesToScan)

        val crdGenerator =
            CRDGenerator()
                .customResourceClasses(collector.findCustomResourceClasses())
                .inOutputDir(outputDir)

        val crdGenerationInfo: CRDGenerationInfo = crdGenerator.detailedGenerate()

        crdGenerationInfo.crdDetailsPerNameAndVersion.forEach { (crdName, versionToInfo) ->
            println("Generated CRD $crdName:")
            versionToInfo.forEach { (version, info) -> println(" $version -> ${info.filePath}") }
        }
    }

    dependsOn("compileKotlin")
}

tasks.register<Copy>("syncCrds") {
    group = "helm"

    val chartDir = chartsDirectory

    from(
        fileTree(layout.buildDirectory.dir("resources/main/")) {
            include("*.yml")
        },
    )
    into("$chartDir/crds")
    dependsOn("generateCrds")
}

listOf("jibDockerBuild", "jibBuildTar", "jib").forEach { taskName ->
    getTasksByName(taskName, true).forEach { task ->
        task.notCompatibleWithConfigurationCache("Jib is not compatible with configuration cache")
    }
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
    enabled = project.hasProperty("enableJib")
}
