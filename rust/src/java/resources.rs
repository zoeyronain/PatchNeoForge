use std::{
    collections::HashSet,
    fs,
    path::{Path, PathBuf},
};

use anyhow::Result;
use rust_embed::Embed;

#[derive(Embed)]
#[folder = "resources/jassets/"]
pub struct Resources;

pub fn setup_resources(resources_folder: &Path) -> Result<()> {
    sync_embedded_resources(resources_folder)?;
    cleanup_stale_files(resources_folder);
    Ok(())
}

fn cleanup_stale_files(resources_folder: &Path) {
    let embedded_paths: HashSet<PathBuf> = Resources::iter()
        .map(|p| PathBuf::from(p.to_string()))
        .collect();

    for entry in walkdir::WalkDir::new(resources_folder)
        .into_iter()
        .filter_map(std::result::Result::ok)
        .filter(|e| e.file_type().is_file())
    {
        let path = entry.path();
        if let Ok(rel_path) = path.strip_prefix(resources_folder)
            && !embedded_paths.contains(&rel_path.to_path_buf())
        {
            tracing::warn!("Removing stale embedded file: {}", rel_path.display());
            let _ = fs::remove_file(path);
        }
    }
}

fn sync_embedded_resources(resources_folder: &Path) -> Result<()> {
    fs::create_dir_all(resources_folder)?;

    for file_path in Resources::iter() {
        let embedded_file = match Resources::get(&file_path) {
            Some(file) => file,
            None => continue,
        };

        let target_path = resources_folder.join(file_path.as_ref());
        if let Some(parent) = target_path.parent() {
            fs::create_dir_all(parent)?;
        }

        let needs_write = match fs::read(&target_path) {
            Ok(existing_data) => existing_data != embedded_file.data.as_ref(),
            Err(_) => true,
        };

        if needs_write {
            fs::write(&target_path, embedded_file.data.as_ref())?;
            tracing::info!("Extracted embedded resource: {}", file_path);
        }
    }

    Ok(())
}
