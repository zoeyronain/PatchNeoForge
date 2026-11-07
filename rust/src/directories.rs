use std::{
    fs,
    path::{Path, PathBuf},
};

use pumpkin::plugin::Context;

pub struct PatchNeoForgeDirectories {
    pub base: PathBuf,
    pub mods: PathBuf,
    pub server_mods: PathBuf,
    pub jassets: PathBuf,
    pub neoforge: PathBuf,
    pub neoforge_runtime: PathBuf,
}

pub fn get_server_root(server: &Context) -> Result<PathBuf, String> {
    let data_folder = std::path::absolute(server.get_data_folder())
        .map_err(|_| "Failed to get absolute directory from relative")?;
    // data_folder is <server_root>/plugins/data/patchneoforge
    let server_root = data_folder
        .parent() // plugins/data
        .and_then(Path::parent) // plugins
        .and_then(Path::parent) // server root
        .map(Path::to_path_buf)
        .unwrap_or_else(|| std::env::current_dir().unwrap_or_else(|_| data_folder.clone()));
    Ok(server_root)
}

pub fn get_base_directory(server: &Context) -> Result<PathBuf, String> {
    let server_root = get_server_root(server)?;
    Ok(server_root.join("patchneoforge"))
}

pub fn setup_directories(
    server: &Context,
    mods_dir_name: &str,
) -> Result<PatchNeoForgeDirectories, String> {
    let server_root = get_server_root(server)?;
    let base = server_root.join("patchneoforge");
    let server_mods = server_root.join("mods");

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

    // Also ensure plugins/data/patchneoforge exists
    let data_folder = server.get_data_folder();
    let _ = fs::create_dir_all(&data_folder);

    fs::create_dir_all(&base)
        .map_err(|err| format!("Failed to create base folder {}: {err:?}", base.display()))?;

    fs::create_dir_all(&mods)
        .map_err(|err| format!("Failed to create mods folder {}: {err:?}", mods.display()))?;

    fs::create_dir_all(&server_mods)
        .map_err(|err| format!("Failed to create server mods folder {}: {err:?}", server_mods.display()))?;

    fs::create_dir_all(&jassets)
        .map_err(|err| format!("Failed to create jassets folder {}: {err:?}", jassets.display()))?;

    fs::create_dir_all(&neoforge)
        .map_err(|err| format!("Failed to create neoforge cache folder {}: {err:?}", neoforge.display()))?;

    fs::create_dir_all(&neoforge_runtime)
        .map_err(|err| format!("Failed to create neoforge-runtime folder {}: {err:?}", neoforge_runtime.display()))?;

    tracing::info!("PatchNeoForge directories successfully verified at {}", base.display());

    Ok(PatchNeoForgeDirectories {
        base,
        mods,
        server_mods,
        jassets,
        neoforge,
        neoforge_runtime,
    })
}
