plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.0"
}

val protobufVersion = "4.36.2"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

sourceSets {
    main {
        proto {
            srcDir("../../proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }

    plugins {
        create("ffi") {
            path = "${rootProject.projectDir}/protoc-gen-ffi/build/libs/protoc-gen-ffi.jar"
        }
    }

    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("ffi")
            }
        }
    }
}

tasks.named("generateProto") {
    dependsOn(":protoc-gen-ffi:jar")
}

repositories {
    mavenCentral()
    maven {
        name = "neoforged"
        url = uri("https://maven.neoforged.net/releases/")
    }
    maven {
        name = "minecraft"
        url = uri("https://libraries.minecraft.net/")
    }
}

dependencies {
    api("com.mojang:brigadier:1.2.9")
    api("org.slf4j:slf4j-api:2.0.12")
    compileOnly("net.jodah:typetools:0.6.3")
    compileOnly(fileTree("${rootProject.projectDir}/../server/patchneoforge/cache/neoforge/libraries") {
        include("**/*.jar")
    })
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    compileOnly("org.apache.logging.log4j:log4j-api:2.23.1")
    compileOnly("org.apache.logging.log4j:log4j-core:2.23.1")
    implementation("net.bytebuddy:byte-buddy:1.18.15")
    implementation("net.bytebuddy:byte-buddy-agent:1.18.15")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("--enable-preview")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("patchneoforge")
    isZip64 = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Premain-Class" to "org.patchneoforge.bootstrap.HeadlessNeoForgeServer",
            "Agent-Class" to "org.patchneoforge.bootstrap.HeadlessNeoForgeServer",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Can-Set-Native-Method-Prefix" to "true"
        )
    }

    dependsOn(configurations.runtimeClasspath)

    from({
        configurations.runtimeClasspath.get().map { file ->
            if (file.isDirectory) file else zipTree(file)
        }
    })

    exclude(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA"
    )
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs(
        "-XX:+EnableDynamicAgentLoading",
        "-XX:+StartAttachListener",
        "-Djdk.attach.allowAttachSelf=true",
        "-Dnet.bytebuddy.experimental=true",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED",
        "--add-opens=java.management/sun.management=ALL-UNNAMED"
    )
}
