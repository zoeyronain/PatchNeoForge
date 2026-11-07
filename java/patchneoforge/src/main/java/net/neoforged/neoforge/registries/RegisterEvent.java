package net.neoforged.neoforge.registries;

import net.neoforged.bus.api.Event;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class RegisterEvent extends Event {

    private final String registryKey;

    public RegisterEvent(String registryKey) {
        this.registryKey = registryKey;
    }

    public String getRegistryKey() {
        return registryKey;
    }

    public <T> void register(String key, Consumer<RegisterHelper<T>> consumer) {
        if (this.registryKey.equals(key)) {
            consumer.accept((id, supplier) -> {
                // Register into Pumpkin/Minecraft runtime registry
            });
        }
    }

    @FunctionalInterface
    public interface RegisterHelper<T> {
        void register(String name, Supplier<T> supplier);
    }
}
