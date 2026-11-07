package net.neoforged.fml.common;

import net.neoforged.bus.api.IEventBus;

public class ModContainer {

    private final String modId;
    private final String displayName;
    private final String version;
    private final IEventBus eventBus;

    public ModContainer(String modId, String displayName, String version, IEventBus eventBus) {
        this.modId = modId;
        this.displayName = displayName;
        this.version = version;
        this.eventBus = eventBus;
    }

    public String getModId() {
        return modId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getVersion() {
        return version;
    }

    public IEventBus getEventBus() {
        return eventBus;
    }
}
