use std::{path::PathBuf, sync::Arc};

use anyhow::Result;
use pumpkin::plugin::Context;
use pumpkin_protocol::java::client::play::CommandSuggestion;
use tokio::sync::{mpsc, oneshot};
use uuid::Uuid;

use crate::{
    commands::SimpleCommandSender,
    config::patchneoforge::PatchNeoForgeConfig,
    events::handler::JvmEventPayload,
    proto::patchneoforge::events::FireEventResponse,
};

pub enum JvmCommand {
    Initialize {
        jassets_path: PathBuf,
        neoforge_cache_path: PathBuf,
        neoforge_runtime_path: PathBuf,
        respond_to: oneshot::Sender<Result<()>>,
        context: Arc<Context>,
        runtime_handle: tokio::runtime::Handle,
        command_tx: mpsc::Sender<Self>,
        config: PatchNeoForgeConfig,
    },
    InstantiateAllMods {
        mods_dir: PathBuf,
        respond_to: oneshot::Sender<Result<()>>,
        context: Arc<Context>,
    },
    Shutdown {
        respond_to: oneshot::Sender<()>,
    },
    FireEvent {
        payload: JvmEventPayload,
        plugin: String,
        respond_to: Option<oneshot::Sender<FireEventResponse>>,
    },
    TriggerCommand {
        full_command: String,
        command_sender: SimpleCommandSender,
        respond_to: oneshot::Sender<Result<()>>,
    },
    GetCommandTabComplete {
        command_sender: SimpleCommandSender,
        full_command: String,
        respond_to: oneshot::Sender<Option<Vec<CommandSuggestion>>>,
        location: Option<Location>,
    },
}

#[derive(Clone, Copy)]
pub struct Rotation {
    pub yaw: f32,
    pub pitch: f32,
}

impl Rotation {
    #[must_use]
    pub const fn new(yaw: f32, pitch: f32) -> Self {
        Self { yaw, pitch }
    }
}

#[derive(Clone, Copy)]
pub struct Location {
    pub world: Uuid,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub rotation: Option<Rotation>,
}

impl Location {
    #[must_use]
    pub const fn new(world: Uuid, x: f64, y: f64, z: f64, rotation: Option<Rotation>) -> Self {
        Self {
            world,
            x,
            y,
            z,
            rotation,
        }
    }
}
