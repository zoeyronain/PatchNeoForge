use std::{
    fs,
    path::{Path, PathBuf},
    process::Command,
};

use anyhow::{Context, Result, anyhow, bail};

use crate::config::patchneoforge::NeoForgeConfig;

const NEOFORGE_MAVEN: &str = "https://maven.neoforged.net/releases/net/neoforged/neoforge";
const INSTALL_MARKER: &str = ".patchneoforge-installed";

/// Ensures the NeoForge server and libraries exist in `cache_dir`
/// and returns the jars to add to the JVM classpath / module path.
pub fn ensure_neoforge_server(cache_dir: &Path, config: &NeoForgeConfig) -> Result<Vec<PathBuf>> {
    fs::create_dir_all(cache_dir)
        .with_context(|| format!("Failed to create NeoForge cache dir {}", cache_dir.display()))?;

    let marker_path = cache_dir.join(INSTALL_MARKER);
    if !marker_path.exists() {
        let version = &config.version;
        let installer_name = format!("neoforge-{version}-installer.jar");
        let installer_path = cache_dir.join(&installer_name);

        let url = config.installer_url.clone().unwrap_or_else(|| {
            format!("{NEOFORGE_MAVEN}/{version}/{installer_name}")
        });

        tracing::info!("Downloading NeoForge installer {version} from {url}...");

        let resp = ureq::get(&url)
            .call()
            .map_err(|e| anyhow!("Failed to download NeoForge installer from {url}: {e}"))?;

        let mut body = resp.into_body();
        let mut file = fs::File::create(&installer_path)
            .with_context(|| format!("Failed to create installer file {}", installer_path.display()))?;
        std::io::copy(&mut body.as_reader(), &mut file)
            .with_context(|| "Failed to save downloaded NeoForge installer")?;

        tracing::info!("Running NeoForge server installer (java -jar {} --installServer)...", installer_name);

        let status = Command::new("java")
            .arg("-jar")
            .arg(&installer_path)
            .arg("--installServer")
            .current_dir(cache_dir)
            .status()
            .with_context(|| "Failed to execute java for NeoForge installer")?;

        if !status.success() {
            bail!("NeoForge installer exited with non-zero status: {status}");
        }

        fs::write(&marker_path, format!("version={version}\n"))
            .with_context(|| "Failed to create installation marker")?;

        tracing::info!("NeoForge server installation completed successfully.");
    }

    // Collect all installed library jars
    let mut jars = Vec::new();
    let libraries_dir = cache_dir.join("libraries");
    if libraries_dir.exists() {
        for entry in walkdir::WalkDir::new(&libraries_dir)
            .into_iter()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_type().is_file())
        {
            let path = entry.path().to_path_buf();
            if path.extension().is_some_and(|ext| ext == "jar") {
                jars.push(path);
            }
        }
    }

    tracing::info!("Discovered {} NeoForge runtime jar(s) in cache", jars.len());
    Ok(jars)
}
