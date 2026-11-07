package net.neoforged.neoforge.event.server;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;

public abstract class ServerLifecycleEvent extends Event {
    protected final MinecraftServer server;

    public ServerLifecycleEvent(MinecraftServer server) {
        this.server = server;
    }

    public MinecraftServer getServer() {
        return server;
    }
}
