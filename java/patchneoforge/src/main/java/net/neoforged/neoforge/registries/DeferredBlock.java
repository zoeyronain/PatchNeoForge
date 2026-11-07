package net.neoforged.neoforge.registries;

import java.util.function.Supplier;

public class DeferredBlock<T> extends DeferredHolder<Object, T> {
    public DeferredBlock(String id, Supplier<T> supplier) {
        super(id, supplier);
    }
}
