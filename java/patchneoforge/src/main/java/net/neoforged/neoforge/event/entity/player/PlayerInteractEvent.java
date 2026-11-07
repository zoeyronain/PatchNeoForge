package net.neoforged.neoforge.event.entity.player;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import java.util.UUID;

public class PlayerInteractEvent extends Event implements ICancellableEvent {

    private final UUID playerUuid;
    private final int x;
    private final int y;
    private final int z;

    public PlayerInteractEvent(UUID playerUuid, int x, int y, int z) {
        this.playerUuid = playerUuid;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }

    public static class RightClickBlock extends PlayerInteractEvent {
        public RightClickBlock(UUID playerUuid, int x, int y, int z) {
            super(playerUuid, x, y, z);
        }
    }

    public static class LeftClickBlock extends PlayerInteractEvent {
        public LeftClickBlock(UUID playerUuid, int x, int y, int z) {
            super(playerUuid, x, y, z);
        }
    }
}
