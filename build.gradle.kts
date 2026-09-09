plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    id("com.gradleup.shadow") version "9.4.1"
    id("com.typewritermc.module-plugin") version "2.2.0"
}

group = "btcrenaud"
version = "0.5"

base {
    archivesName.set("PuzzleExtension")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.typewritermc.com/beta/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("com.typewritermc:BasicExtension:0.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
}

// PuzzleService carries Typewriter/Paper annotations that must stay resolvable
// at test runtime, otherwise instantiating it throws NoClassDefFoundError.
configurations.testImplementation.configure {
    extendsFrom(configurations.compileOnly.get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

typewriter {
    namespace = "btcrenaud"
    extension {
        name = "Puzzle"
        shortDescription = "Folia-safe client-side puzzle objectives for Typewriter"
        description = """
            Nine reusable puzzle objective types with per-player client-side visuals,
            idempotent progress, cooldowns, time limits, persistent statistics and
            Typewriter triggers. The public build uses only official Typewriter and
            Paper APIs; BTC-specific integrations remain in the custom distribution.
        """.trimIndent()
        engineVersion = "0.9.0-beta-177"
        channel = com.typewritermc.moduleplugin.ReleaseChannel.BETA
        paper()
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Le chargeur du moteur (TypewriterPaperLoader) fournit kotlinx-serialization-core mais
// PAS -json, et toutes les extensions partagent un seul URLClassLoader : l'artefact json
// doit donc voyager dans ce jar, sinon la premiere classe qui le touche leve
// NoClassDefFoundError a l'execution alors que la compilation est verte.
// N'embarquer que lui : les extensions soeurs et la stdlib Kotlin sont chargees par le
// moteur et ne doivent jamais etre dupliquees ici.
tasks.jar {
    archiveClassifier.set("thin")
}

tasks.shadowJar {
    archiveClassifier.set("")
    dependencies {
        include(dependency("org.jetbrains.kotlinx:kotlinx-serialization-json"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
