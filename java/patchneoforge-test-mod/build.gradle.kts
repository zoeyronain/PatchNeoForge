plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven {
        name = "minecraft"
        url = uri("https://libraries.minecraft.net/")
    }
}

dependencies {
    implementation(project(":patchneoforge"))
    implementation("com.mojang:brigadier:1.2.9")
    implementation("org.slf4j:slf4j-api:2.0.12")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("test-neoforge-mod")
}
