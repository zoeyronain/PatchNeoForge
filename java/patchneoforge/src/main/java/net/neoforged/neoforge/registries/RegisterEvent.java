package net.neoforged.neoforge.registries;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class RegisterEvent extends Event implements IModBusEvent {

    private final ResourceKey<? extends Registry<?>> registryKey;
    private final Registry<?> registry;

    public RegisterEvent(ResourceKey<? extends Registry<?>> registryKey, Registry<?> registry) {
        this.registryKey = registryKey;
        this.registry = registry;
    }

    public ResourceKey<? extends Registry<?>> getRegistryKey() {
        return registryKey;
    }

    public Registry<?> getRegistry() {
        return registry;
    }

    @SuppressWarnings("unchecked")
    public <T> Registry<T> getRegistry(ResourceKey<? extends Registry<T>> key) {
        return Objects.equals(this.registryKey, key) ? (Registry<T>) this.registry : null;
    }

    @SuppressWarnings("unchecked")
    public <T> void register(ResourceKey<? extends Registry<T>> key, ResourceLocation name, Supplier<T> value) {
        if (Objects.equals(this.registryKey, key) && this.registry != null) {
            Registry.register((Registry<Object>) this.registry, name, value.get());
        }
    }

    @SuppressWarnings("unchecked")
    public <T> void register(ResourceKey<? extends Registry<T>> key, Consumer<RegisterHelper<T>> consumer) {
        if (Objects.equals(this.registryKey, key) && consumer != null) {
            consumer.accept(new RegisterHelper<T>() {
                @Override
                public void register(ResourceLocation name, T value) {
                    if (registry != null) {
                        Registry.register((Registry<Object>) registry, name, value);
                    }
                }
            });
        }
    }

    public interface RegisterHelper<T> {
        default void register(ResourceKey<T> key, T value) {
            register(key.location(), value);
        }

        void register(ResourceLocation name, T value);
    }
}
