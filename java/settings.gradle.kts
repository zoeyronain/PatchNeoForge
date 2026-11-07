pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "neoforged"
            url = uri("https://maven.neoforged.net/releases/")
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "patchneoforge"
include("patchneoforge")
include("protoc-gen-ffi")
include("patchneoforge-test-mod")
