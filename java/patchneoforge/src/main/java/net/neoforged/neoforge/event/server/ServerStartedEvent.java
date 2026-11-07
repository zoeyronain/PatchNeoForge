package net.neoforged.neoforge.event.server;

import net.minecraft.server.MinecraftServer;

public class ServerStartedEvent extends ServerLifecycleEvent {
    public ServerStartedEvent(MinecraftServer server) {
        super(server);
    }
}
