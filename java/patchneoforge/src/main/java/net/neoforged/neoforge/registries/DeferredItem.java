package net.neoforged.neoforge.registries;

import java.util.function.Supplier;

public class DeferredItem<T> extends DeferredHolder<Object, T> {
    public DeferredItem(String id, Supplier<T> supplier) {
        super(id, supplier);
    }
}
