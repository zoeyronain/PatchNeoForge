package net.neoforged.neoforge.registries;

import java.util.function.Supplier;

public class DeferredHolder<R, T extends R> implements Supplier<T> {

    private final String id;
    private final Supplier<T> supplier;
    private volatile T value;

    public DeferredHolder(String id, Supplier<T> supplier) {
        this.id = id;
        this.supplier = supplier;
    }

    public String getId() {
        return id;
    }

    @Override
    public T get() {
        if (value == null) {
            synchronized (this) {
                if (value == null) {
                    value = supplier.get();
                }
            }
        }
        return value;
    }
}
