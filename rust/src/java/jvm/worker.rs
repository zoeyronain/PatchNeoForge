use std::{
    path::{Path, PathBuf},
    sync::{
        Arc, OnceLock,
        atomic::{AtomicUsize, Ordering},
    },
};

use jni::{InitArgsBuilder, JNIVersion, JavaVM};
use pumpkin::{entity::player::Player, plugin::Context, server::Server};
use pumpkin_protocol::java::client::play::CommandSuggestion;
use tokio::sync::mpsc;

use crate::{
    commands::SimpleCommandSender,
    config::patchneoforge::PatchNeoForgeConfig,
    events::handler::JvmEventPayload,
    java::{
        jvm::commands::{JvmCommand, Location},
        mod_manager::manager::ModManager,
        native_callbacks::{
            fast_path::try_dispatch_packet_upcall, init_callback_context, initialize_callbacks,
        },
    },
    neoforge::ensure_neoforge_server,
    proto::patchneoforge::events::FireEventResponse,
};

static GLOBAL_JVM: OnceLock<Arc<JavaVM>> = OnceLock::new();
static PRIMARY_LOCK: ReentrantJvmLock = ReentrantJvmLock::new();

pub struct ReentrantJvmLock {
    owner: AtomicUsize,
    depth: AtomicUsize,
}

pub struct ReentrantJvmGuard<'a> {
    lock: &'a ReentrantJvmLock,
}

impl Default for ReentrantJvmLock {
    fn default() -> Self {
        Self::new()
    }
}

impl ReentrantJvmLock {
    #[must_use]
    pub const fn new() -> Self {
        Self {
            owner: AtomicUsize::new(0),
            depth: AtomicUsize::new(0),
        }
    }

    #[inline]
    pub fn is_locked_by_current_thread(&self) -> bool {
        self.owner.load(Ordering::Relaxed) != 0
    }

    #[inline]
    pub fn lock(&self) -> ReentrantJvmGuard<'_> {
        let tid = 1;
        if self.owner.load(Ordering::Relaxed) == tid {
            self.depth.fetch_add(1, Ordering::Relaxed);
            return ReentrantJvmGuard { lock: self };
        }
        while self
            .owner
            .compare_exchange_weak(0, tid, Ordering::Acquire, Ordering::Relaxed)
            .is_err()
        {
            std::hint::spin_loop();
        }
        self.depth.store(1, Ordering::Relaxed);
        ReentrantJvmGuard { lock: self }
    }
}

impl Drop for ReentrantJvmGuard<'_> {
    fn drop(&mut self) {
        let prev = self.lock.depth.fetch_sub(1, Ordering::Relaxed);
        if prev == 1 {
            self.lock.owner.store(0, Ordering::Release);
        }
    }
}

#[must_use]
pub fn dispatch_packet_direct(
    player: &Arc<Player>,
    _server: &Arc<Server>,
    packet_id: i32,
    payload: &[u8],
    is_outbound: bool,
) -> bool {
    let Some(_jvm) = GLOBAL_JVM.get() else {
        return false;
    };
    crate::java::native_callbacks::utils::cache_player(player.clone());
    try_dispatch_packet_upcall(player.gameprofile.id, packet_id, payload, is_outbound)
}

#[must_use]
pub fn dispatch_packet_direct_result(
    player: &Arc<Player>,
    _server: &Arc<Server>,
    packet_id: i32,
    payload: &[u8],
    is_outbound: bool,
) -> Option<i32> {
    let _jvm = GLOBAL_JVM.get()?;
    crate::java::native_callbacks::utils::cache_player(player.clone());
    crate::java::native_callbacks::fast_path::try_dispatch_packet_upcall_result(
        player.gameprofile.id,
        packet_id,
        payload,
        is_outbound,
    )
}

#[must_use]
pub fn fire_event_direct(payload: JvmEventPayload, _plugin: String) -> Option<FireEventResponse> {
    let _jvm = GLOBAL_JVM.get()?;
    let original = payload.event.clone();
    Some(FireEventResponse {
        cancelled: false,
        data: Some(original),
    })
}

#[must_use]
pub fn trigger_command_direct(
    full_command: String,
    _command_sender: SimpleCommandSender,
) -> Option<anyhow::Result<()>> {
    let jvm = GLOBAL_JVM.get()?;
    let _guard = PRIMARY_LOCK.lock();
    let res = jvm.attach_current_thread(|env| -> anyhow::Result<()> {
        let cmd_str = env.new_string(&full_command)?;
        let _ = env.call_static_method(
            jni::jni_str!("org/patchneoforge/bootstrap/CommandBridge"),
            jni::jni_str!("executeCommand"),
            jni::jni_sig!("(Ljava/lang/String;Ljava/lang/Object;)I"),
            &[(&cmd_str).into(), (&jni::objects::JObject::null()).into()],
        )?;
        Ok(())
    });
    Some(res)
}

#[must_use]
pub fn get_tab_complete_direct(
    _command_sender: SimpleCommandSender,
    _full_command: String,
    _location: Option<Location>,
) -> Option<Option<Vec<CommandSuggestion>>> {
    Some(None)
}

pub struct JvmWorker {
    command_rx: mpsc::Receiver<JvmCommand>,
    mod_manager: ModManager,
    jvm: Option<Arc<JavaVM>>,
    context: Option<Arc<Context>>,
}

impl JvmWorker {
    #[must_use]
    pub fn new(command_rx: mpsc::Receiver<JvmCommand>) -> Self {
        Self {
            command_rx,
            mod_manager: ModManager::new(),
            jvm: None,
            context: None,
        }
    }

    pub fn attach_thread(mut self) {
        tracing::info!("PatchNeoForge JVM worker thread started");

        while let Some(command) = self.command_rx.blocking_recv() {
            match command {
                JvmCommand::Initialize {
                    jassets_path,
                    neoforge_cache_path,
                    neoforge_runtime_path,
                    respond_to,
                    context,
                    runtime_handle,
                    command_tx,
                    config,
                } => {
                    init_callback_context(
                        context.clone(),
                        runtime_handle,
                        command_tx.clone(),
                        config.clone(),
                    )
                    .unwrap();
                    self.context = Some(context);
                    let result = self.initialize_jvm(
                        &jassets_path,
                        &neoforge_cache_path,
                        &neoforge_runtime_path,
                        &config,
                    );
                    let _ = respond_to.send(result);
                }
                JvmCommand::InstantiateAllMods {
                    mods_dir,
                    respond_to,
                    context: _,
                } => {
                    let _guard = PRIMARY_LOCK.lock();
                    let res = if let Some(ref jvm) = self.jvm {
                        jvm.attach_current_thread(|env| -> anyhow::Result<()> {
                            self.mod_manager.instantiate_all_mods(env, &mods_dir)
                        })
                        .map_err(|e| anyhow::anyhow!("Failed to instantiate NeoForge mods: {e}"))
                    } else {
                        Err(anyhow::anyhow!("JVM is not initialized"))
                    };
                    let _ = respond_to.send(res);
                }
                JvmCommand::Shutdown { respond_to } => {
                    let _guard = PRIMARY_LOCK.lock();
                    if let Some(ref jvm) = self.jvm {
                        let _ = jvm.attach_current_thread(|env| -> anyhow::Result<()> {
                            self.mod_manager.shutdown(env)
                        });
                    }
                    let _ = respond_to.send(());
                    break;
                }
                JvmCommand::FireEvent {
                    payload,
                    plugin,
                    respond_to,
                } => {
                    let response = fire_event_direct(payload, plugin).unwrap_or(FireEventResponse {
                        cancelled: false,
                        data: None,
                    });
                    if let Some(respond_to) = respond_to {
                        let _ = respond_to.send(response);
                    }
                }
                JvmCommand::TriggerCommand {
                    full_command,
                    command_sender,
                    respond_to,
                } => {
                    let res = trigger_command_direct(full_command, command_sender)
                        .unwrap_or_else(|| Err(anyhow::anyhow!("JVM is not initialized")));
                    let _ = respond_to.send(res);
                }
                JvmCommand::GetCommandTabComplete {
                    command_sender,
                    full_command,
                    respond_to,
                    location,
                } => {
                    let res = get_tab_complete_direct(command_sender, full_command, location).flatten();
                    let _ = respond_to.send(res);
                }
            }
        }

        tracing::info!("PatchNeoForge JVM worker thread exited");
    }

    fn initialize_jvm(
        &mut self,
        jassets_path: &PathBuf,
        neoforge_cache_path: &Path,
        neoforge_runtime_path: &Path,
        config: &PatchNeoForgeConfig,
    ) -> anyhow::Result<()> {
        tracing::info!("Initializing JVM with assets path: {jassets_path:?}");

        let mut jar_paths = Vec::new();

        for entry in walkdir::WalkDir::new(jassets_path)
            .into_iter()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_type().is_file())
        {
            let path = entry.path().to_path_buf();
            if let Some(filename) = path.file_name().and_then(|n| n.to_str())
                && filename.ends_with(".jar")
            {
                jar_paths.push(path);
            }
        }

        let patchneoforge_jar = jar_paths
            .iter()
            .find(|p| {
                p.file_name()
                    .and_then(|n| n.to_str())
                    .is_some_and(|n| n.contains("patchneoforge"))
            })
            .cloned();

        // Download and prepare NeoForge runtime libraries
        let neoforge_jars = ensure_neoforge_server(neoforge_cache_path, &config.neoforge)?;
        tracing::info!(
            "Acquired NeoForge {} ({} runtime library entries)",
            config.neoforge.version,
            neoforge_jars.len()
        );
        jar_paths.extend(neoforge_jars);

        let separator = if cfg!(windows) { ";" } else { ":" };
        let classpath = jar_paths
            .iter()
            .map(|p| p.to_string_lossy())
            .collect::<Vec<_>>()
            .join(separator);

        let mut builder = InitArgsBuilder::new()
            .version(JNIVersion::V21)
            .option(format!("-Djava.class.path={classpath}"))
            .option("-XX:+IgnoreUnrecognizedVMOptions")
            .option("-XX:+EnableDynamicAgentLoading")
            .option("-XX:+StartAttachListener")
            .option("-Djdk.attach.allowAttachSelf=true")
            .option("--enable-preview")
            .option("--enable-native-access=ALL-UNNAMED")
            .option("--add-opens=java.base/java.lang=ALL-UNNAMED")
            .option("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED")
            .option("--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED")
            .option("--add-opens=java.management/sun.management=ALL-UNNAMED");

        if let Some(ref agent_jar) = patchneoforge_jar {
            builder = builder.option(format!("-javaagent:{}", agent_jar.to_string_lossy()));
        }

        if let Some(ref max_heap) = config.jvm.max_heap {
            builder = builder.option(format!("-Xmx{max_heap}"));
        }
        if let Some(ref initial_heap) = config.jvm.initial_heap {
            builder = builder.option(format!("-Xms{initial_heap}"));
        }
        for arg in &config.jvm.extra_args {
            builder = builder.option(arg.clone());
        }

        let jvm_args = builder
            .build()
            .map_err(|e| anyhow::anyhow!("Failed to build JVM init args: {e:?}"))?;

        let jvm = match JavaVM::new(jvm_args) {
            Ok(jvm) => Arc::new(jvm),
            Err(e) => {
                if let Ok(existing) = JavaVM::singleton() {
                    tracing::info!("Reusing existing JavaVM in process");
                    Arc::new(existing)
                } else {
                    return Err(anyhow::anyhow!("Failed to create JavaVM: {e:?}"));
                }
            }
        };

        jvm.attach_current_thread(|env| -> anyhow::Result<()> {
            initialize_callbacks(env).map_err(|e| {
                tracing::error!("Failed to initialize callbacks: {e:?}");
                e
            })?;

            let dir_str = env.new_string(neoforge_runtime_path.to_string_lossy())?;
            env.call_static_method(
                jni::jni_str!("org/patchneoforge/bootstrap/HeadlessNeoForgeServer"),
                jni::jni_str!("boot"),
                jni::jni_sig!("(Ljava/lang/String;)Z"),
                &[(&dir_str).into()],
            )?;

            Ok(())
        })
        .map_err(|e| anyhow::anyhow!("Failed to attach thread for JVM initialization: {e:?}"))?;

        let _ = GLOBAL_JVM.set(jvm.clone());
        self.jvm = Some(jvm);

        tracing::info!("PatchNeoForge JVM initialized successfully");
        Ok(())
    }
}
