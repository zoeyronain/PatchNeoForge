package net.neoforged.bus.api;

import java.util.function.Consumer;

public interface IEventBus {
    <T extends Event> void addListener(Consumer<T> consumer);
    <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer);
    <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer);
    <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Consumer<T> consumer);
    <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer);

    void register(Object target);
    void unregister(Object target);

    <T extends Event> T post(T event);
}
