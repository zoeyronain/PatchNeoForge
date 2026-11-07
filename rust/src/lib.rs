#![allow(clippy::async_yields_async)]

use std::sync::Arc;

use pumpkin::plugin::Context;
use pumpkin_api_macros::{plugin_impl, plugin_method};

pub mod commands;
pub mod config;
pub mod directories;
pub mod events;
pub mod java;
pub mod neoforge;
pub mod proto;

use directories::{get_base_directory, setup_directories};
use tokio::sync::{mpsc, oneshot};

use crate::{
    config::patchneoforge::PatchNeoForgeConfig,
    java::{
        jvm::{commands::JvmCommand, worker::JvmWorker},
        resources::setup_resources,
    },
};

pub async fn on_load_inner(plugin: &PatchNeoForgePlugin, server: Arc<Context>) -> Result<(), String> {
    server.init_log();
    tracing::info!("Starting PatchNeoForge (Minecraft 1.21.1 / NeoForge 21.1.x)");

    let base = get_base_directory(&server)?;
    std::fs::create_dir_all(&base)
        .map_err(|e| format!("Failed to create PatchNeoForge base directory {}: {e}", base.display()))?;

    let mut config_path = base;
    config_path.push("patchneoforge.config.toml");
    let config = PatchNeoForgeConfig::get_or_create(config_path)
        .map_err(|e| format!("Failed to setup PatchNeoForge config: {e}"))?;

    // Setup directories (mods, jassets, cache, runtime)
    let dirs = setup_directories(&server, &config.mods.directory)?;

    // Manage embedded resources (patchneoforge.jar)
    setup_resources(&dirs.jassets).map_err(|e| format!("Failed to setup resources: {e}"))?;

    let runtime_handle = plugin.runtime.handle().clone();
    let command_tx = plugin.command_tx.clone();
    let server_clone = server.clone();

    // Run JVM initialization and NeoForge mod bootstrap in a background task
    // on PatchNeoForge's dedicated runtime so Pumpkin's main startup is never blocked.
    plugin.runtime.spawn(async move {
        tracing::info!("Initializing NeoForge JVM in background...");

        // 1. Initialize JVM & NeoForge runtime
        let (tx, rx) = oneshot::channel();
        if let Err(e) = command_tx
            .send(JvmCommand::Initialize {
                jassets_path: dirs.jassets.clone(),
                neoforge_cache_path: dirs.neoforge.clone(),
                neoforge_runtime_path: dirs.neoforge_runtime.clone(),
                respond_to: tx,
                context: server_clone.clone(),
                runtime_handle,
                command_tx: command_tx.clone(),
                config,
            })
            .await
        {
            tracing::error!("Failed to send command to initialize JVM: {e}");
            return;
        }

        match rx.await {
            Ok(Ok(())) => tracing::info!("NeoForge JVM runtime initialized successfully"),
            Ok(Err(e)) => {
                tracing::error!("Failed to initialize NeoForge JVM: {e}");
                return;
            }
            Err(e) => {
                tracing::error!("Unable to receive response from JVM initialization: {e}");
                return;
            }
        }

        // 2. Discover, instantiate, and bootstrap all NeoForge mods
        let (tx, rx) = oneshot::channel();
        if let Err(e) = command_tx
            .send(JvmCommand::InstantiateAllMods {
                mods_dir: dirs.mods.clone(),
                respond_to: tx,
                context: server_clone.clone(),
            })
            .await
        {
            tracing::error!("Failed to send command to bootstrap NeoForge mods: {e}");
            return;
        }

        match rx.await {
            Ok(Ok(())) => tracing::info!("All NeoForge mods loaded and bootstrapped successfully"),
            Ok(Err(e)) => tracing::error!("Failed to bootstrap NeoForge mods: {e}"),
            Err(e) => tracing::error!("Unable to receive response from mod bootstrap: {e}"),
        }
    });

    tracing::info!("PatchNeoForge loaded successfully");
    Ok(())
}

pub async fn on_unload_inner(
    plugin: &PatchNeoForgePlugin,
    _server: Arc<Context>,
) -> Result<(), String> {
    tracing::info!("Unloading PatchNeoForge plugin");
    let (tx, rx) = oneshot::channel();
    if let Err(e) = plugin.command_tx.send(JvmCommand::Shutdown { respond_to: tx }).await {
        tracing::error!("Failed to send shutdown command to JVM worker: {e}");
        return Ok(());
    }
    let _ = rx.await;
    Ok(())
}

#[plugin_method]
async fn on_load(&self, server: Arc<Context>) -> Result<(), String> {
    on_load_inner(self, server).await
}

#[plugin_method]
async fn on_unload(&self, server: Arc<Context>) -> Result<(), String> {
    on_unload_inner(self, server).await
}

#[plugin_impl]
pub struct PatchNeoForgePlugin {
    pub command_tx: mpsc::Sender<JvmCommand>,
    pub runtime: Arc<tokio::runtime::Runtime>,
}

impl PatchNeoForgePlugin {
    #[must_use]
    pub fn new() -> Self {
        let (command_tx, command_rx) = mpsc::channel(4096);
        let worker = JvmWorker::new(command_rx);

        std::thread::Builder::new()
            .name("PatchNeoForge-JVM-Worker".into())
            .spawn(move || {
                worker.attach_thread();
            })
            .expect("Failed to spawn PatchNeoForge JVM Worker thread");

        let runtime = Arc::new(
            tokio::runtime::Builder::new_multi_thread()
                .worker_threads(4)
                .thread_name("patchneoforge-worker")
                .enable_all()
                .build()
                .expect("Failed to create PatchNeoForge Tokio runtime"),
        );

        Self {
            command_tx,
            runtime,
        }
    }
}

impl Default for PatchNeoForgePlugin {
    fn default() -> Self {
        Self::new()
    }
}
