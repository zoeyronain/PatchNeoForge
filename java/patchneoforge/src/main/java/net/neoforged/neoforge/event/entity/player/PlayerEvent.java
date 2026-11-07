package net.neoforged.neoforge.event.entity.player;

import net.neoforged.bus.api.Event;
import java.util.UUID;

public class PlayerEvent extends Event {

    private final UUID playerUuid;
    private final String playerName;

    public PlayerEvent(UUID playerUuid, String playerName) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public static class PlayerLoggedInEvent extends PlayerEvent {
        public PlayerLoggedInEvent(UUID playerUuid, String playerName) {
            super(playerUuid, playerName);
        }
    }

    public static class PlayerLoggedOutEvent extends PlayerEvent {
        public PlayerLoggedOutEvent(UUID playerUuid, String playerName) {
            super(playerUuid, playerName);
        }
    }
}
