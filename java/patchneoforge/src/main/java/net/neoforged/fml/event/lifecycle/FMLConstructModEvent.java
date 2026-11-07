package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.IModBusEvent;

public class FMLConstructModEvent extends Event implements IModBusEvent {
    private final ModContainer container;

    public FMLConstructModEvent(ModContainer container) {
        this.container = container;
    }

    public ModContainer getContainer() {
        return container;
    }
}
