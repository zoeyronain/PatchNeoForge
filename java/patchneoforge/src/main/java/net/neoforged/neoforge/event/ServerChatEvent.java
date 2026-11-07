package net.neoforged.neoforge.event;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

public class ServerChatEvent extends Event implements ICancellableEvent {

    private final String username;
    private String message;

    public ServerChatEvent(String username, String message) {
        this.username = username;
        this.message = message;
    }

    public String getUsername() {
        return username;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
