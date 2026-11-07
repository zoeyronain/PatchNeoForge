package net.neoforged.neoforge.event.level;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import java.util.UUID;

public class BlockEvent extends Event {

    private final UUID worldUuid;
    private final int x;
    private final int y;
    private final int z;

    public BlockEvent(UUID worldUuid, int x, int y, int z) {
        this.worldUuid = worldUuid;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public UUID getWorldUuid() { return worldUuid; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }

    public static class BreakEvent extends BlockEvent implements ICancellableEvent {
        private final UUID playerUuid;

        public BreakEvent(UUID worldUuid, int x, int y, int z, UUID playerUuid) {
            super(worldUuid, x, y, z);
            this.playerUuid = playerUuid;
        }

        public UUID getPlayerUuid() { return playerUuid; }
    }

    public static class EntityPlaceEvent extends BlockEvent implements ICancellableEvent {
        private final UUID entityUuid;

        public EntityPlaceEvent(UUID worldUuid, int x, int y, int z, UUID entityUuid) {
            super(worldUuid, x, y, z);
            this.entityUuid = entityUuid;
        }

        public UUID getEntityUuid() { return entityUuid; }
    }
}
