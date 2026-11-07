package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.common.ModContainer;

public class FMLLoadCompleteEvent extends Event {
    private final ModContainer container;

    public FMLLoadCompleteEvent(ModContainer container) {
        this.container = container;
    }

    public ModContainer getContainer() {
        return container;
    }
}
