package org.patchneoforge.events;

import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class PatchNeoForgeEventFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(PatchNeoForgeEventFactory.class);

    public static boolean firePlayerJoin(UUID playerUuid, String name) {
        PlayerEvent.PlayerLoggedInEvent event = new PlayerEvent.PlayerLoggedInEvent(playerUuid, name);
        NeoForge.EVENT_BUS.post(event);
        return false;
    }

    public static boolean firePlayerQuit(UUID playerUuid, String name) {
        PlayerEvent.PlayerLoggedOutEvent event = new PlayerEvent.PlayerLoggedOutEvent(playerUuid, name);
        NeoForge.EVENT_BUS.post(event);
        return false;
    }

    public static boolean fireBlockBreak(UUID worldUuid, int x, int y, int z, UUID playerUuid) {
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(worldUuid, x, y, z, playerUuid);
        NeoForge.EVENT_BUS.post(event);
        return event.isCanceled();
    }

    public static boolean fireBlockPlace(UUID worldUuid, int x, int y, int z, UUID entityUuid) {
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(worldUuid, x, y, z, entityUuid);
        NeoForge.EVENT_BUS.post(event);
        return event.isCanceled();
    }

    public static boolean firePlayerInteract(UUID playerUuid, int x, int y, int z, boolean isRightClick) {
        PlayerInteractEvent event = isRightClick
                ? new PlayerInteractEvent.RightClickBlock(playerUuid, x, y, z)
                : new PlayerInteractEvent.LeftClickBlock(playerUuid, x, y, z);
        NeoForge.EVENT_BUS.post(event);
        return event.isCanceled();
    }

    public static boolean fireChat(String username, String message) {
        ServerChatEvent event = new ServerChatEvent(username, message);
        NeoForge.EVENT_BUS.post(event);
        return event.isCanceled();
    }

    public static void fireServerTickPre() {
        NeoForge.EVENT_BUS.post(new ServerTickEvent.Pre());
    }

    public static void fireServerTickPost() {
        NeoForge.EVENT_BUS.post(new ServerTickEvent.Post());
    }
}
