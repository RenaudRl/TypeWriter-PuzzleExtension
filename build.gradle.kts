plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("com.typewritermc.module-plugin") version "2.1.0"
}

group = "btcrenaud"
version = "0.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.typewritermc.com/beta/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("com.typewritermc:BasicExtension:0.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
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
        engineVersion = "0.9.0-beta-175"
        channel = com.typewritermc.moduleplugin.ReleaseChannel.BETA
        paper()
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}
