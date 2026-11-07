package net.neoforged.neoforge.event;

import com.mojang.brigadier.CommandDispatcher;
import net.neoforged.bus.api.Event;

public class RegisterCommandsEvent extends Event {

    private final CommandDispatcher<Object> dispatcher;

    public RegisterCommandsEvent(CommandDispatcher<Object> dispatcher) {
        this.dispatcher = dispatcher;
    }

    public CommandDispatcher<Object> getDispatcher() {
        return dispatcher;
    }
}
