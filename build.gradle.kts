plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.realfarlands"
version = "1.0.0"
description = "Recreates the classic Minecraft Beta Far Lands terrain corruption"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.1.2+ — new versioning format introduced in Paper 26.1.
    // "26.1.2.build.+" resolves the latest stable build automatically,
    // equivalent to the old -R0.1-SNAPSHOT behavior.
    // DO NOT use "26.1.+" — that could resolve a breaking version like 26.1.1
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    processResources {
        val props = mapOf(
            "version" to project.version,
            "description" to project.description
        )
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        archiveClassifier.set("")
        minimize()
    }

    build {
        dependsOn(shadowJar)
    }

    jar {
        archiveClassifier.set("unshaded")
    }
}