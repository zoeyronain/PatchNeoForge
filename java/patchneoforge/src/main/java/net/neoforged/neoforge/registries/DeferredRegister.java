package net.neoforged.neoforge.registries;

import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

public class DeferredRegister<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeferredRegister.class);

    protected final String registryKey;
    protected final String modId;
    protected final Map<String, DeferredHolder<T, ? extends T>> entries = new LinkedHashMap<>();

    protected DeferredRegister(String registryKey, String modId) {
        this.registryKey = registryKey;
        this.modId = modId;
    }

    public static <B> DeferredRegister<B> create(String registryKey, String modId) {
        return new DeferredRegister<>(registryKey, modId);
    }

    public static <B> DeferredRegister<B> create(net.minecraft.resources.ResourceKey<?> registryKey, String modId) {
        String key = (registryKey != null && registryKey.location() != null)
                ? registryKey.location().toString()
                : "minecraft:custom";
        return new DeferredRegister<>(key, modId);
    }

    public static <B> DeferredRegister<B> create(net.minecraft.resources.ResourceLocation registryLocation, String modId) {
        String key = registryLocation != null ? registryLocation.toString() : "minecraft:custom";
        return new DeferredRegister<>(key, modId);
    }

    public static <B> DeferredRegister<B> create(net.minecraft.core.Registry<?> registry, String modId) {
        String key = (registry != null && registry.key() != null && registry.key().location() != null)
                ? registry.key().location().toString()
                : "minecraft:custom";
        return new DeferredRegister<>(key, modId);
    }

    public static Blocks createBlocks(String modId) {
        return new Blocks(modId);
    }

    public static Items createItems(String modId) {
        return new Items(modId);
    }

    public static DataComponents createDataComponents(String modId) {
        return new DataComponents(modId);
    }

    public static DataComponents createDataComponents(Object registryKey, String modId) {
        return new DataComponents(modId);
    }

    public <I extends T> DeferredHolder<T, I> register(String name, Supplier<I> supplier) {
        String fullId = modId + ":" + name;
        DeferredHolder<T, I> holder = new DeferredHolder<>(fullId, supplier);
        entries.put(name, holder);
        return holder;
    }

    public <I extends T> DeferredHolder<T, I> register(String name, java.util.function.Function<net.minecraft.resources.ResourceLocation, ? extends I> function) {
        String fullId = modId + ":" + name;
        net.minecraft.resources.ResourceLocation loc = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(modId, name);
        DeferredHolder<T, I> holder = new DeferredHolder<>(fullId, () -> function.apply(loc));
        entries.put(name, holder);
        return holder;
    }

    public void register(IEventBus bus) {
        bus.addListener(RegisterEvent.class, this::onRegister);
    }

    private void onRegister(RegisterEvent event) {
        if (event.getRegistryKey() != null && (event.getRegistryKey().location().toString().equals(registryKey) || event.getRegistryKey().toString().equals(registryKey))) {
            LOGGER.info("[DeferredRegister] Firing registrations for {} entries in {} ({})",
                    entries.size(), registryKey, modId);
            for (Map.Entry<String, DeferredHolder<T, ? extends T>> entry : entries.entrySet()) {
                try {
                    T val = entry.getValue().get();
                    if (event.getRegistry() != null && val != null) {
                        net.minecraft.resources.ResourceLocation loc = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(modId, entry.getKey());
                        net.minecraft.core.Registry.register((net.minecraft.core.Registry<Object>) event.getRegistry(), loc, val);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[DeferredRegister] Deferred entry '{}' threw on initial resolution: {}",
                            entry.getKey(), t.getMessage());
                }
            }
        }
    }

    public static class Blocks extends DeferredRegister<Object> {
        private final Map<String, DeferredBlock<?>> blockEntries = new LinkedHashMap<>();

        public Blocks(String modId) {
            super("minecraft:block", modId);
        }

        public <B> DeferredBlock<B> registerSimpleBlock(String name, Supplier<B> supplier) {
            String fullId = this.modId + ":" + name;
            DeferredBlock<B> holder = new DeferredBlock<>(fullId, supplier);
            blockEntries.put(name, holder);
            return holder;
        }
    }

    public static class Items extends DeferredRegister<Object> {
        private final Map<String, DeferredItem<?>> itemEntries = new LinkedHashMap<>();

        public Items(String modId) {
            super("minecraft:item", modId);
        }

        public <I> DeferredItem<I> registerSimpleItem(String name, Supplier<I> supplier) {
            String fullId = this.modId + ":" + name;
            DeferredItem<I> holder = new DeferredItem<>(fullId, supplier);
            itemEntries.put(name, holder);
            return holder;
        }

        public <I> DeferredItem<I> registerSimpleBlockItem(String name, Supplier<?> blockSupplier) {
            String fullId = this.modId + ":" + name;
            @SuppressWarnings("unchecked")
            Supplier<I> itemSupplier = () -> (I) blockSupplier.get();
            DeferredItem<I> holder = new DeferredItem<>(fullId, itemSupplier);
            itemEntries.put(name, holder);
            return holder;
        }
    }

    public static class DataComponents extends DeferredRegister<Object> {
        public DataComponents(String modId) {
            super("minecraft:data_component_type", modId);
        }
    }
}
