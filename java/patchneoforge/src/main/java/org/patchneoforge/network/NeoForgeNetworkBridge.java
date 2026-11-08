package org.patchneoforge.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.negotiation.NegotiableNetworkComponent;
import net.neoforged.neoforge.network.negotiation.NegotiationResult;
import net.neoforged.neoforge.network.negotiation.NetworkComponentNegotiator;
import net.neoforged.neoforge.network.payload.MinecraftRegisterPayload;
import net.neoforged.neoforge.network.payload.MinecraftUnregisterPayload;
import net.neoforged.neoforge.network.payload.ModdedNetworkPayload;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryComponent;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryPayload;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import org.patchneoforge.bridge.NativeFastPathFfi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge network negotiation bridge between Pumpkin and the Headless NeoForge Server.
 */
public final class NeoForgeNetworkBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(NeoForgeNetworkBridge.class);

    private static final Set<UUID> MODDED_CONNECTIONS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, NetworkPayloadSetup> PAYLOAD_SETUPS = new ConcurrentHashMap<>();
    private static final Map<UUID, Connection> VIRTUAL_CONNECTIONS = new ConcurrentHashMap<>();

    private static MinecraftServer minecraftServer;

    private NeoForgeNetworkBridge() {}

    public static void setMinecraftServer(MinecraftServer server) {
        minecraftServer = server;
    }

    public static MinecraftServer getMinecraftServer() {
        return minecraftServer;
    }

    public static boolean isModded(UUID uuid) {
        return uuid != null && MODDED_CONNECTIONS.contains(uuid);
    }

    public static void markModded(UUID uuid) {
        if (uuid != null) {
            MODDED_CONNECTIONS.add(uuid);
        }
    }

    private static Object getServerConnection(Object server) {
        if (server == null) return null;
        try {
            Method m = server.getClass().getMethod("getConnection");
            return m.invoke(server);
        } catch (Throwable t) {
            try {
                Field f = server.getClass().getDeclaredField("connection");
                f.setAccessible(true);
                return f.get(server);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public static void removeModded(UUID uuid) {
        if (uuid != null) {
            MODDED_CONNECTIONS.remove(uuid);
            PAYLOAD_SETUPS.remove(uuid);
            Connection conn = VIRTUAL_CONNECTIONS.remove(uuid);
            if (conn != null && minecraftServer != null) {
                try {
                    Object connectionListener = getServerConnection(minecraftServer);
                    if (connectionListener != null) {
                        Method getConnectionsMethod = connectionListener.getClass().getMethod("getConnections");
                        @SuppressWarnings("unchecked")
                        List<Connection> connections = (List<Connection>) getConnectionsMethod.invoke(connectionListener);
                        if (connections != null) {
                            connections.remove(conn);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    public static NetworkPayloadSetup getPayloadSetup(UUID uuid) {
        return uuid != null ? PAYLOAD_SETUPS.get(uuid) : null;
    }

    public static byte[] createModdedQueryPayloadBytes() {
        try {
            ModdedNetworkQueryPayload queryPayload = new ModdedNetworkQueryPayload(Map.of());
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            ModdedNetworkQueryPayload.STREAM_CODEC.encode(buf, queryPayload);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            buf.release();
            return bytes;
        } catch (Throwable t) {
            LOGGER.error("[PatchNeoForge] Failed to create ModdedNetworkQueryPayload", t);
            return new byte[]{0};
        }
    }

    public static byte[] createInitialRegisterPayloadBytes() {
        try {
            Set<ResourceLocation> channels = NetworkRegistry.getInitialListeningChannels(PacketFlow.SERVERBOUND);
            MinecraftRegisterPayload payload = new MinecraftRegisterPayload(channels);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            MinecraftRegisterPayload.STREAM_CODEC.encode(buf, payload);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            buf.release();
            return bytes;
        } catch (Throwable t) {
            LOGGER.error("[PatchNeoForge] Failed to create initial MinecraftRegisterPayload", t);
            return new byte[0];
        }
    }

    public static byte[] createInitialUnregisterPayloadBytes() {
        try {
            Set<ResourceLocation> channels = NetworkRegistry.getInitialServerUnregisterChannels();
            MinecraftUnregisterPayload payload = new MinecraftUnregisterPayload(channels);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            MinecraftUnregisterPayload.STREAM_CODEC.encode(buf, payload);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            buf.release();
            return bytes;
        } catch (Throwable t) {
            LOGGER.error("[PatchNeoForge] Failed to create initial MinecraftUnregisterPayload", t);
            return new byte[0];
        }
    }

    public static final class NegotiationResultHolder {
        public final boolean success;
        public final String failureReason;
        public final byte[] moddedNetworkPayloadBytes;
        public final byte[] minecraftRegisterPayloadBytes;

        public NegotiationResultHolder(
            boolean success,
            String failureReason,
            byte[] moddedNetworkPayloadBytes,
            byte[] minecraftRegisterPayloadBytes
        ) {
            this.success = success;
            this.failureReason = failureReason;
            this.moddedNetworkPayloadBytes = moddedNetworkPayloadBytes;
            this.minecraftRegisterPayloadBytes = minecraftRegisterPayloadBytes;
        }
    }

    public static NegotiationResultHolder handleClientModdedQuery(UUID playerUuid, byte[] payloadBytes) {
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(payloadBytes));
            ModdedNetworkQueryPayload clientQuery = ModdedNetworkQueryPayload.STREAM_CODEC.decode(buf);
            buf.release();

            if (playerUuid != null) {
                markModded(playerUuid);
            }

            Field regField = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
            regField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>> serverRegistrations =
                (Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>>) regField.get(null);

            Map<ConnectionProtocol, NegotiationResult> negotiationResults = new HashMap<>();

            for (ConnectionProtocol protocol : serverRegistrations.keySet()) {
                Collection<PayloadRegistration<?>> serverComponents = serverRegistrations.get(protocol).values();
                List<NegotiableNetworkComponent> serverList = serverComponents.stream()
                    .map(NegotiableNetworkComponent::new)
                    .toList();

                Set<ModdedNetworkQueryComponent> clientComponents = clientQuery.queries().getOrDefault(protocol, Collections.emptySet());
                List<NegotiableNetworkComponent> clientList = clientComponents.stream()
                    .map(NegotiableNetworkComponent::new)
                    .toList();

                NegotiationResult result = NetworkComponentNegotiator.negotiate(serverList, clientList);
                if (!result.success()) {
                    String reason = "Negotiation failed for protocol " + protocol + ": " + result.failureReasons();
                    LOGGER.warn("[PatchNeoForge] {}", reason);
                    return new NegotiationResultHolder(false, reason, null, null);
                }
                negotiationResults.put(protocol, result);
            }

            NetworkPayloadSetup setup = NetworkPayloadSetup.from(negotiationResults);
            if (playerUuid != null) {
                PAYLOAD_SETUPS.put(playerUuid, setup);
            }

            // Encode neoforge:network payload
            ModdedNetworkPayload moddedPayload = new ModdedNetworkPayload(setup);
            FriendlyByteBuf moddedBuf = new FriendlyByteBuf(Unpooled.buffer());
            ModdedNetworkPayload.STREAM_CODEC.encode(moddedBuf, moddedPayload);
            byte[] moddedNetworkBytes = new byte[moddedBuf.readableBytes()];
            moddedBuf.readBytes(moddedNetworkBytes);
            moddedBuf.release();

            // Encode negotiated channels for minecraft:register
            Set<ResourceLocation> listeningChannels = new HashSet<>(NetworkRegistry.getInitialListeningChannels(PacketFlow.SERVERBOUND));
            Map<ResourceLocation, ?> configChannels = setup.getChannels(ConnectionProtocol.CONFIGURATION);
            if (configChannels != null) {
                listeningChannels.addAll(configChannels.keySet());
            }
            MinecraftRegisterPayload registerPayload = new MinecraftRegisterPayload(listeningChannels);
            FriendlyByteBuf regBuf = new FriendlyByteBuf(Unpooled.buffer());
            MinecraftRegisterPayload.STREAM_CODEC.encode(regBuf, registerPayload);
            byte[] minecraftRegisterBytes = new byte[regBuf.readableBytes()];
            regBuf.readBytes(minecraftRegisterBytes);
            regBuf.release();

            LOGGER.info("[PatchNeoForge] Successfully negotiated NeoForge network connection for {} (config channels: {}, play channels: {})",
                playerUuid,
                configChannels != null ? configChannels.size() : 0,
                setup.getChannels(ConnectionProtocol.PLAY) != null ? setup.getChannels(ConnectionProtocol.PLAY).size() : 0);

            return new NegotiationResultHolder(true, null, moddedNetworkBytes, minecraftRegisterBytes);
        } catch (Throwable t) {
            LOGGER.error("[PatchNeoForge] Error during client modded query negotiation", t);
            return new NegotiationResultHolder(false, t.getMessage(), null, null);
        }
    }

    public static byte[][] handleClientModdedQueryRaw(long mostSigBits, long leastSigBits, byte[] payloadBytes) {
        UUID uuid = (mostSigBits != 0 || leastSigBits != 0) ? new UUID(mostSigBits, leastSigBits) : null;
        NegotiationResultHolder holder = handleClientModdedQuery(uuid, payloadBytes);
        if (holder != null && holder.success && holder.moddedNetworkPayloadBytes != null && holder.minecraftRegisterPayloadBytes != null) {
            return new byte[][] { holder.moddedNetworkPayloadBytes, holder.minecraftRegisterPayloadBytes };
        }
        return null;
    }
}
