plugins {
    java
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.protobuf:protobuf-java:4.36.2")
}

application {
    mainClass.set("org.patchneoforge.protocgen.FfiGenerator")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.patchneoforge.protocgen.FfiGenerator"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
