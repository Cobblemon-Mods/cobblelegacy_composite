plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
    id("org.jetbrains.compose") version "1.9.3"
    id("maven-publish")
    id("net.fabricmc.fabric-loom-remap") version "1.15-SNAPSHOT"
}

version = "0.5.0"
group = "dev.aperso"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    google()
}

val natives = arrayListOf<File>()

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:0.18.4")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.3+1.21.11")
    modImplementation("net.fabricmc:fabric-language-kotlin:1.13.9+kotlin.2.3.10")

    val transitiveInclude by configurations.creating
    transitiveInclude(implementation(compose.material3)!!)
    transitiveInclude(implementation(compose.materialIconsExtended)!!)
    transitiveInclude(implementation(compose.desktop.windows_x64)!!)
    transitiveInclude(implementation(compose.desktop.windows_arm64)!!)
    transitiveInclude(implementation(compose.desktop.macos_x64)!!)
    transitiveInclude(implementation(compose.desktop.macos_arm64)!!)
    transitiveInclude(implementation(compose.desktop.linux_x64)!!)
    transitiveInclude(implementation(compose.desktop.linux_arm64)!!)
    transitiveInclude(implementation("androidx.collection:collection:1.5.0")!!)
    transitiveInclude.resolvedConfiguration.resolvedArtifacts.forEach {
        val id = it.moduleVersion.id
        if (id.group == "org.jetbrains.skiko") {
            natives.add(it.file)
        } else {
            include(id.toString())
        }
    }
}

tasks.jar {
    from(natives.map { zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.name
            artifact(tasks["remapJar"]) {
                classifier = ""
            }
        }
    }
    repositories {
        mavenLocal()
    }
}