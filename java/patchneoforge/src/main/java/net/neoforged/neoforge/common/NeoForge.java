package net.neoforged.neoforge.common;

import net.neoforged.bus.api.IEventBus;
import org.patchneoforge.events.NeoForgeEventBus;

public final class NeoForge {

    public static final IEventBus EVENT_BUS = new NeoForgeEventBus("NeoForge Game Event Bus");

    private NeoForge() {}
}
