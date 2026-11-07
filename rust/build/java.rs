use std::{fs, path::PathBuf};

pub fn setup_java(base: PathBuf) {
    let resources = base.join("resources");
    let jassets = resources.join("jassets");

    let mut java_path = base;
    java_path.pop();
    let java_path = java_path.join("java");

    let patchneoforge_jar = java_path
        .join("patchneoforge")
        .join("build")
        .join("libs")
        .join("patchneoforge.jar");

    if !patchneoforge_jar.exists() {
        panic!(
            "Failed to find patchneoforge.jar, build the java library first by running `./gradlew build` in the java directory!"
        );
    }

    fs::create_dir_all(&jassets).unwrap();
    let dest_patchneoforge = jassets.join("patchneoforge.jar");
    fs::copy(&patchneoforge_jar, &dest_patchneoforge)
        .map_err(|err| format!("Failed to copy patchneoforge.jar to resources/jassets: {err:?}"))
        .unwrap();
}
