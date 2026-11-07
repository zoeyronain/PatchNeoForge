use std::path::Path;
use anyhow::Result;
use jni::Env;

pub struct ModManager;

impl Default for ModManager {
    fn default() -> Self {
        Self::new()
    }
}

impl ModManager {
    #[must_use]
    pub const fn new() -> Self {
        Self
    }

    pub fn instantiate_all_mods(&mut self, env: &mut Env, mods_folder: &Path) -> Result<()> {
        tracing::info!("Bootstrapping NeoForge mods from {:?}", mods_folder);
        let path_str = mods_folder.to_string_lossy();
        let j_str = env.new_string(&path_str)?;
        let result = env.call_static_method(
            jni::jni_str!("org/patchneoforge/loader/PatchNeoForgeBootstrap"),
            jni::jni_str!("bootstrapMods"),
            jni::jni_sig!("(Ljava/lang/String;)Z"),
            &[(&j_str).into()],
        );

        match result {
            Ok(val) => {
                if !val.z()? {
                    tracing::error!("Bootstrap of NeoForge mods reported errors");
                }
            }
            Err(e) => {
                tracing::error!("Failed to bootstrap NeoForge mods: {:?}", e);
                return Err(e.into());
            }
        }
        Ok(())
    }

    pub fn shutdown(&mut self, env: &mut Env) -> Result<()> {
        tracing::info!("Shutting down NeoForge mods...");
        let result = env.call_static_method(
            jni::jni_str!("org/patchneoforge/loader/PatchNeoForgeBootstrap"),
            jni::jni_str!("shutdown"),
            jni::jni_sig!("()V"),
            &[],
        );

        if let Err(e) = result {
            env.exception_clear();
            tracing::warn!("Failed to cleanly shut down NeoForge mods: {:?}", e);
        }
        Ok(())
    }
}
