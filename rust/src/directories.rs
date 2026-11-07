use std::{
    fs,
    path::{Path, PathBuf},
};

use pumpkin::plugin::Context;

pub struct PatchNeoForgeDirectories {
    pub base: PathBuf,
    pub mods: PathBuf,
    pub jassets: PathBuf,
    pub neoforge: PathBuf,
    pub neoforge_runtime: PathBuf,
}

pub fn get_base_directory(server: &Context) -> Result<PathBuf, String> {
    let data_folder = std::path::absolute(server.get_data_folder())
        .map_err(|_| "Failed to get absolute directory from relative")?;
    let server_root = data_folder
        .parent()
        .ok_or("Failed to determine server root from PatchNeoForge data folder")?;
    Ok(server_root.join("patchneoforge"))
}

pub fn setup_directories(
    server: &Context,
    mods_dir_name: &str,
) -> Result<PatchNeoForgeDirectories, String> {
    let data_folder = std::path::absolute(server.get_data_folder())
        .map_err(|_| "Failed to get absolute directory from relative")?;
    let server_root = data_folder
        .parent()
        .ok_or("Failed to determine server root from PatchNeoForge data folder")?;
    let base = server_root.join("patchneoforge");

    let mods = if Path::new(mods_dir_name).is_absolute() {
        PathBuf::from(mods_dir_name)
    } else if mods_dir_name == "mods" {
        base.join("mods")
    } else {
        server_root.join(mods_dir_name)
    };

    let jassets = base.join("jassets");
    let neoforge = base.join("cache").join("neoforge");
    let neoforge_runtime = base.join("neoforge-runtime");

    fs::create_dir_all(&jassets)
        .map_err(|err| format!("Failed to create jassets folder: {err:?}"))?;

    fs::create_dir_all(&mods)
        .map_err(|err| format!("Failed to create mods folder: {err:?}"))?;

    fs::create_dir_all(&neoforge)
        .map_err(|err| format!("Failed to create neoforge cache folder: {err:?}"))?;

    fs::create_dir_all(&neoforge_runtime)
        .map_err(|err| format!("Failed to create neoforge-runtime folder: {err:?}"))?;

    Ok(PatchNeoForgeDirectories {
        base,
        mods,
        jassets,
        neoforge,
        neoforge_runtime,
    })
}
