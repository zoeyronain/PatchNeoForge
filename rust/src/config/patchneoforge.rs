use std::{fs, path::Path};

use serde::{Deserialize, Serialize};

#[derive(Deserialize, Serialize, Debug, Clone, Default, PartialEq, Eq)]
pub struct PatchNeoForgeConfig {
    #[serde(default)]
    pub jvm: JvmConfig,
    #[serde(default)]
    pub mods: ModsConfig,
    #[serde(default)]
    pub diagnostics: DiagnosticsConfig,
    #[serde(default)]
    pub neoforge: NeoForgeConfig,
}

#[derive(Deserialize, Serialize, Debug, Clone, Default, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub struct JvmConfig {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_heap: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub initial_heap: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub extra_args: Vec<String>,
}

#[derive(Deserialize, Serialize, Debug, Clone, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub struct ModsConfig {
    #[serde(default = "default_mods_dir")]
    pub directory: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub disabled: Vec<String>,
}

fn default_mods_dir() -> String {
    "mods".to_string()
}

impl Default for ModsConfig {
    fn default() -> Self {
        Self {
            directory: default_mods_dir(),
            disabled: Vec::new(),
        }
    }
}

#[derive(Deserialize, Serialize, Debug, Clone, Default, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub struct DiagnosticsConfig {
    #[serde(default)]
    pub debug_bridge: bool,
}

#[derive(Deserialize, Serialize, Debug, Clone, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub struct NeoForgeConfig {
    /// NeoForge version to download and run, e.g. "21.1.106"
    #[serde(default = "default_neoforge_version")]
    pub version: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub installer_url: Option<String>,
}

fn default_neoforge_version() -> String {
    "21.1.106".to_string()
}

impl Default for NeoForgeConfig {
    fn default() -> Self {
        Self {
            version: default_neoforge_version(),
            installer_url: None,
        }
    }
}

impl PatchNeoForgeConfig {
    pub fn get_or_create(path: impl AsRef<Path>) -> Result<Self, String> {
        let path = path.as_ref();
        if path.exists() {
            let content = fs::read_to_string(path)
                .map_err(|e| format!("Failed to read config file {}: {e}", path.display()))?;
            toml::from_str(&content)
                .map_err(|e| format!("Failed to parse config file {}: {e}", path.display()))
        } else {
            let config = Self::default();
            let toml = toml::to_string_pretty(&config)
                .map_err(|e| format!("Failed to serialize default config: {e}"))?;
            fs::write(path, toml)
                .map_err(|e| format!("Failed to write default config {}: {e}", path.display()))?;
            Ok(config)
        }
    }
}
