use std::sync::{Arc, LazyLock};
use bytes::Bytes;
use dashmap::DashMap;
use pumpkin::{
    net::java::{JavaClient, send_packet_to_pending},
    plugin::{
        BoxFuture, Context, EventHandler, EventPriority,
        server::packet::{ConnectionPacketReceivedEvent, ConnectionPacketSentEvent},
    },
    server::Server,
};
use pumpkin_data::packet::{
    CURRENT_MC_VERSION,
    clientbound::config::FINISH_CONFIGURATION,
    serverbound::config::{
        CUSTOM_PAYLOAD as S_CUSTOM_PAYLOAD,
        FINISH_CONFIGURATION as S_ACK_FINISH_CONFIG,
        PONG as S_PONG,
    },
};
use pumpkin_protocol::{
    ConnectionState,
    java::client::config::{CConfigPing, CFinishConfig, CPluginMessage},
    ser::{NetworkReadExt, NetworkReadSliceExt},
};

use crate::java::jvm::worker::{get_neoforge_initial_payloads, negotiate_neoforge_network};

static SENT_INITIAL_NEOFORGE_QUERY: LazyLock<DashMap<u64, bool>> = LazyLock::new(DashMap::new);
static HANDSHAKE_COMPLETED: LazyLock<DashMap<u64, bool>> = LazyLock::new(DashMap::new);
static WAITING_FINISH_CONFIG: LazyLock<DashMap<u64, bool>> = LazyLock::new(DashMap::new);

/// Helper to serialize a CPluginMessage for CURRENT_MC_VERSION and queue it to the pending connection.
fn send_config_plugin_message(connection_id: u64, channel: &str, data: &[u8]) -> bool {
    let msg = CPluginMessage::new(channel, data);
    let mut buf = Vec::new();
    if let Err(err) = JavaClient::write_packet_for_version(&msg, CURRENT_MC_VERSION, &mut buf) {
        tracing::error!("[PatchNeoForge] Failed to encode CPluginMessage for channel {}: {:?}", channel, err);
        return false;
    }
    send_packet_to_pending(connection_id, Bytes::from(buf))
}

/// Helper to serialize a CConfigPing for CURRENT_MC_VERSION and queue it to the pending connection.
fn send_config_ping(connection_id: u64, ping_id: i32) -> bool {
    let ping = CConfigPing::new(ping_id);
    let mut buf = Vec::new();
    if let Err(err) = JavaClient::write_packet_for_version(&ping, CURRENT_MC_VERSION, &mut buf) {
        tracing::error!("[PatchNeoForge] Failed to encode CConfigPing: {:?}", err);
        return false;
    }
    send_packet_to_pending(connection_id, Bytes::from(buf))
}

/// Helper to serialize a CFinishConfig for CURRENT_MC_VERSION and queue it to the pending connection.
fn send_config_finish(connection_id: u64) -> bool {
    let finish = CFinishConfig;
    let mut buf = Vec::new();
    if let Err(err) = JavaClient::write_packet_for_version(&finish, CURRENT_MC_VERSION, &mut buf) {
        tracing::error!("[PatchNeoForge] Failed to encode CFinishConfig: {:?}", err);
        return false;
    }
    send_packet_to_pending(connection_id, Bytes::from(buf))
}

pub struct NeoForgePacketSentHandler;

impl EventHandler<ConnectionPacketSentEvent> for NeoForgePacketSentHandler {
    fn handle<'a>(
        &'a self,
        _server: &'a Arc<Server>,
        _event: &'a ConnectionPacketSentEvent,
    ) -> BoxFuture<'a, ()> {
        Box::pin(async {})
    }

    fn handle_blocking<'a>(
        &'a self,
        _server: &'a Arc<Server>,
        event: &'a mut ConnectionPacketSentEvent,
    ) -> BoxFuture<'a, ()> {
        if event.state == ConnectionState::Config {
            // 1. On first config packet sent to this connection, initiate NeoForge negotiation
            if SENT_INITIAL_NEOFORGE_QUERY.insert(event.connection_id, true).is_none() {
                tracing::info!(
                    "[PatchNeoForge] Initiating NeoForge handshake for pending connection {}",
                    event.connection_id
                );
                if let Some((unreg_bytes, reg_bytes, query_bytes)) = get_neoforge_initial_payloads() {
                    // 1. minecraft:unregister
                    send_config_plugin_message(event.connection_id, "minecraft:unregister", &unreg_bytes);
                    // 2. minecraft:register
                    send_config_plugin_message(event.connection_id, "minecraft:register", &reg_bytes);
                    // 3. neoforge:register (query payload)
                    send_config_plugin_message(event.connection_id, "neoforge:register", &query_bytes);
                    // 4. Clientbound ping 0 (vanilla barrier)
                    send_config_ping(event.connection_id, 0);
                    tracing::info!(
                        "[PatchNeoForge] Sent initial NeoForge queries and ping 0 to pending connection {}",
                        event.connection_id
                    );
                } else {
                    tracing::warn!(
                        "[PatchNeoForge] NeoForge JVM not ready yet for pending connection {}",
                        event.connection_id
                    );
                }
            }

            // 2. Intercept CFinishConfig until NeoForge handshake has concluded
            if event.packet_id == FINISH_CONFIGURATION.0 {
                let completed = HANDSHAKE_COMPLETED
                    .get(&event.connection_id)
                    .as_deref()
                    .copied()
                    .unwrap_or(false);
                if !completed {
                    tracing::info!(
                        "[PatchNeoForge] Intercepted premature CFinishConfig for connection {}, holding until NeoForge handshake completes",
                        event.connection_id
                    );
                    WAITING_FINISH_CONFIG.insert(event.connection_id, true);
                    event.cancelled = true;
                } else {
                    tracing::info!(
                        "[PatchNeoForge] Allowing CFinishConfig through for connection {} (handshake already completed)",
                        event.connection_id
                    );
                }
            }
        }
        Box::pin(async {})
    }
}

pub struct NeoForgePacketReceivedHandler;

impl EventHandler<ConnectionPacketReceivedEvent> for NeoForgePacketReceivedHandler {
    fn handle<'a>(
        &'a self,
        _server: &'a Arc<Server>,
        _event: &'a ConnectionPacketReceivedEvent,
    ) -> BoxFuture<'a, ()> {
        Box::pin(async {})
    }

    fn handle_blocking<'a>(
        &'a self,
        _server: &'a Arc<Server>,
        event: &'a mut ConnectionPacketReceivedEvent,
    ) -> BoxFuture<'a, ()> {
        if event.state == ConnectionState::Config {
            if event.packet_id == S_CUSTOM_PAYLOAD.0 {
                // Packet ID 2 in Serverbound Config is CUSTOM_PAYLOAD (SPluginMessage)
                let mut slice = &event.payload[..];
                if let Ok(channel) = slice.get_str_borrowed() {
                    if channel == "neoforge:register" {
                        let client_query_bytes = slice;
                        tracing::info!(
                            "[PatchNeoForge] Received neoforge:register response ({} bytes) from connection {}",
                            client_query_bytes.len(),
                            event.connection_id
                        );

                        // Cancel this event so Pumpkin doesn't complain about unhandled plugin message
                        event.cancelled = true;

                        // Negotiate mod channels via JNI
                        if let Some((modded_network_bytes, register_bytes)) =
                            negotiate_neoforge_network(0, 0, client_query_bytes)
                        {
                            // 1. Send neoforge:network (ModdedNetworkPayload)
                            send_config_plugin_message(
                                event.connection_id,
                                "neoforge:network",
                                &modded_network_bytes,
                            );
                            // 2. Send minecraft:register (with negotiated channels)
                            send_config_plugin_message(
                                event.connection_id,
                                "minecraft:register",
                                &register_bytes,
                            );
                            tracing::info!(
                                "[PatchNeoForge] Successfully completed NeoForge handshake with connection {}!",
                                event.connection_id
                            );

                            HANDSHAKE_COMPLETED.insert(event.connection_id, true);

                            // If CFinishConfig was held, release it now!
                            if WAITING_FINISH_CONFIG.remove(&event.connection_id).is_some() {
                                tracing::info!(
                                    "[PatchNeoForge] Releasing FINISH_CONFIGURATION to connection {} after NeoForge handshake completion",
                                    event.connection_id
                                );
                                send_config_finish(event.connection_id);
                            }
                        } else {
                            tracing::error!(
                                "[PatchNeoForge] Network negotiation failed for connection {}",
                                event.connection_id
                            );
                        }
                    } else if channel == "minecraft:register" {
                        tracing::debug!(
                            "[PatchNeoForge] Received minecraft:register from connection {}",
                            event.connection_id
                        );
                    }
                }
            } else if event.packet_id == S_PONG.0 {
                // Packet ID 5 in Serverbound Config is PONG (SConfigPong)
                let mut slice = &event.payload[..];
                if let Ok(pong_id) = slice.get_i32_be() {
                    if pong_id == 0 {
                        tracing::info!(
                            "[PatchNeoForge] Received pong 0 barrier from connection {}",
                            event.connection_id
                        );
                        if HANDSHAKE_COMPLETED.insert(event.connection_id, true).is_none() {
                            tracing::info!(
                                "[PatchNeoForge] Client connection {} concluded handshake via pong barrier",
                                event.connection_id
                            );
                        }
                        if WAITING_FINISH_CONFIG.remove(&event.connection_id).is_some() {
                            tracing::info!(
                                "[PatchNeoForge] Releasing FINISH_CONFIGURATION to connection {} after pong barrier",
                                event.connection_id
                            );
                            send_config_finish(event.connection_id);
                        }
                    }
                }
            } else if event.packet_id == S_ACK_FINISH_CONFIG.0 {
                tracing::info!(
                    "[PatchNeoForge] Client acknowledged finish config for connection {}, transitioning to Play!",
                    event.connection_id
                );
                SENT_INITIAL_NEOFORGE_QUERY.remove(&event.connection_id);
                HANDSHAKE_COMPLETED.remove(&event.connection_id);
                WAITING_FINISH_CONFIG.remove(&event.connection_id);
            }
        }
        Box::pin(async {})
    }
}

pub fn register_handshake_handlers(context: &Arc<Context>) {
    context.register_event::<ConnectionPacketSentEvent, NeoForgePacketSentHandler>(
        Arc::new(NeoForgePacketSentHandler),
        EventPriority::Highest,
        true,
    );
    context.register_event::<ConnectionPacketReceivedEvent, NeoForgePacketReceivedHandler>(
        Arc::new(NeoForgePacketReceivedHandler),
        EventPriority::Highest,
        true,
    );
    tracing::info!("[PatchNeoForge] Registered NeoForge network handshake event handlers");
}
