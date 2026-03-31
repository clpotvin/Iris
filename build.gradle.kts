
plugins {
    id("java")
    id("fabric-loom") version("1.14.4") apply(false)
}

val MINECRAFT_VERSION by extra { "1.21.11" }
val NEOFORGE_VERSION by extra { "21.11.5-beta" }
val FABRIC_LOADER_VERSION by extra { "0.18.1" }
val FABRIC_API_VERSION by extra { "0.140.2+1.21.11" }

val SODIUM_DEPENDENCY_FABRIC by extra { files(rootDir.resolve("custom_sodium/sodium-fabric-0.8.7+mc1.21.11.jar")) }
val SODIUM_DEPENDENCY_NEO by extra { files(rootDir.resolve("custom_sodium/net.caffeinemc.sodium-neoforge-0.8.6+mc1.21.11-mod.jar")) }

// This value can be set to null to disable Parchment.
// TODO: Re-add Parchment
val PARCHMENT_VERSION by extra { null }

// WynnIris versioning: IRIS_BASE_VERSION is the upstream Iris version we forked from.
// It's used as the mod version so Fabric/Sodium compatibility checks pass.
// WYNNIRIS_VERSION is our own release counter, used in the jar filename only.
val WYNNIRIS_VERSION by extra { "1.0.1" }
val IRIS_BASE_VERSION by extra { "1.10.7" }
val MOD_VERSION by extra { IRIS_BASE_VERSION }

allprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.jar {
    enabled = false
}

subprojects {
    apply(plugin = "maven-publish")

    java.toolchain.languageVersion = JavaLanguageVersion.of(21)


    // Mod metadata version — must look like a real Iris version for Sodium compatibility
    fun modVersionString(): String = "${MOD_VERSION}+mc${MINECRAFT_VERSION}"

    // User-facing jar filename version
    fun archiveVersionString(): String {
        val isReleaseBuild = project.hasProperty("build.release")
        val suffix = if (isReleaseBuild) "" else "-dev"
        return "${WYNNIRIS_VERSION}${suffix}+mc${MINECRAFT_VERSION}"
    }

    tasks.processResources {
        filesMatching("META-INF/neoforge.mods.toml") {
            expand(mapOf("version" to modVersionString()))
        }
    }

    version = modVersionString()
    group = "net.irisshaders"

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    // Disables Gradle's custom module metadata from being published to maven. The
    // metadata includes mapped dependencies which are not reasonably consumable by
    // other mod developers.
    tasks.withType<GenerateModuleMetadata>().configureEach {
        enabled = false
    }
}
