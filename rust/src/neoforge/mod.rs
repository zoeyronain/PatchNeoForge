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
    let mut needs_install = !marker_path.exists();
    if let Ok(content) = fs::read_to_string(&marker_path) {
        if !content.contains(&format!("version={}", config.version)) {
            needs_install = true;
        }
    }
    if needs_install {
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
    let mut scan_dirs = vec![cache_dir.join("libraries")];
    if let Some(parent) = cache_dir.parent() {
        let jarjar = parent.join("jarjar");
        if jarjar.exists() {
            scan_dirs.push(jarjar);
        }
    }
    let server_libs = Path::new("libraries");
    if server_libs.exists() {
        scan_dirs.push(server_libs.to_path_buf());
    }

    let strip_marker = cache_dir.join(".signatures-stripped");
    let need_strip = !strip_marker.exists();

    let mut seen = std::collections::HashSet::new();
    for dir in &scan_dirs {
        if dir.exists() {
            for entry in walkdir::WalkDir::new(dir)
                .into_iter()
                .filter_map(|e| e.ok())
                .filter(|e| e.file_type().is_file())
            {
                let path = entry.path().to_path_buf();
                if path.extension().is_some_and(|ext| ext == "jar") {
                    let path_str = path.to_string_lossy();
                    // Exclude intermediate installer tools and raw obfuscated Minecraft jars
                    if path_str.ends_with("-unpacked.jar")
                        || path_str.ends_with("-slim.jar")
                        || path_str.contains("/server/1.21.1/server-1.21.1.jar")
                        || path_str.contains("/installertools/")
                        || path_str.contains("/AutoRenamingTool/")
                        || path_str.contains("/SpecialSource/")
                    {
                        continue;
                    }

                    if let Some(fname) = path.file_name() {
                        if !seen.insert(fname.to_os_string()) {
                            continue;
                        }
                    }

                    // Strip signatures to prevent SecurityException (signer mismatch across split jars)
                    if need_strip {
                        let _ = std::process::Command::new("zip")
                            .arg("-d")
                            .arg(&path)
                            .arg("META-INF/*.SF")
                            .arg("META-INF/*.RSA")
                            .arg("META-INF/*.DSA")
                            .output();
                    }
                    jars.push(path);
                }
            }
        }
    }

    // Prioritize neoforge jars so patched classes take precedence over unpatched vanilla srg classes
    jars.sort_by(|a, b| {
        let a_neoforge = a.to_string_lossy().contains("neoforge-");
        let b_neoforge = b.to_string_lossy().contains("neoforge-");
        b_neoforge.cmp(&a_neoforge)
    });

    tracing::info!("Discovered {} NeoForge runtime jar(s) in cache", jars.len());
    Ok(jars)
}

pub mod handshake;
