package org.patchneoforge.events;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class NeoForgeEventBus implements IEventBus {

    private static final Logger LOGGER = LoggerFactory.getLogger(NeoForgeEventBus.class);

    private final String busName;
    private final Map<Class<?>, List<RegisteredListener>> listenersByEventType = new ConcurrentHashMap<>();

    private record RegisteredListener(
            Class<?> eventType,
            EventPriority priority,
            boolean receiveCanceled,
            Consumer<Event> handler,
            Object owner
    ) implements Comparable<RegisteredListener> {
        @Override
        public int compareTo(RegisteredListener o) {
            return this.priority.compareTo(o.priority); // Enum order: HIGHEST=0, LOWEST=4
        }
    }

    public NeoForgeEventBus(String busName) {
        this.busName = busName;
    }

    public String getName() {
        return busName;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void addListener(Consumer<T> consumer) {
        addListener(EventPriority.NORMAL, false, consumer);
    }

    @Override
    public <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer) {
        addListener(EventPriority.NORMAL, false, eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) {
        addListener(priority, false, consumer);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Consumer<T> consumer) {
        // Resolve type from lambda or consumer if possible; default to Event
        Class<T> type = (Class<T>) resolveConsumerEventType(consumer);
        addListener(priority, receiveCanceled, type, consumer);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(consumer, "consumer must not be null");

        RegisteredListener rl = new RegisteredListener(
                eventType,
                priority,
                receiveCanceled,
                (Consumer<Event>) consumer,
                consumer
        );

        listenersByEventType.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(rl);
        sortListeners(eventType);
    }

    @Override
    public void register(Object target) {
        Objects.requireNonNull(target, "Target must not be null");
        boolean isStatic = (target instanceof Class<?>);
        Class<?> clazz = isStatic ? (Class<?>) target : target.getClass();

        for (Method method : clazz.getDeclaredMethods()) {
            SubscribeEvent annotation = method.getAnnotation(SubscribeEvent.class);
            if (annotation == null) {
                continue;
            }

            if (isStatic && !Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (!isStatic && Modifier.isStatic(method.getModifiers())) {
                continue;
            }

            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || !Event.class.isAssignableFrom(params[0])) {
                LOGGER.warn("[{}] Method {} has @SubscribeEvent but invalid parameters", busName, method.getName());
                continue;
            }

            @SuppressWarnings("unchecked")
            Class<? extends Event> eventType = (Class<? extends Event>) params[0];
            method.setAccessible(true);

            RegisteredListener rl = new RegisteredListener(
                    eventType,
                    annotation.priority(),
                    annotation.receiveCanceled(),
                    event -> {
                        try {
                            method.invoke(isStatic ? null : target, event);
                        } catch (Throwable t) {
                            LOGGER.error("[{}] Exception handling event {} in {}", busName, event.getClass().getSimpleName(), method, t);
                        }
                    },
                    target
            );

            listenersByEventType.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(rl);
            sortListeners(eventType);
        }
    }

    @Override
    public void unregister(Object target) {
        Objects.requireNonNull(target, "Target must not be null");
        for (List<RegisteredListener> list : listenersByEventType.values()) {
            list.removeIf(l -> l.owner == target || Objects.equals(l.owner, target));
        }
    }

    @Override
    public <T extends Event> T post(T event) {
        Objects.requireNonNull(event, "Event must not be null");
        Class<?> current = event.getClass();

        // Dispatch to all matching hierarchy levels (e.g. BreakEvent -> BlockEvent -> Event)
        while (current != null && Event.class.isAssignableFrom(current)) {
            List<RegisteredListener> listeners = listenersByEventType.get(current);
            if (listeners != null && !listeners.isEmpty()) {
                for (RegisteredListener listener : listeners) {
                    if (event.isCanceled() && !listener.receiveCanceled()) {
                        continue;
                    }
                    try {
                        listener.handler().accept(event);
                    } catch (Throwable t) {
                        LOGGER.error("[{}] Error firing event {} to listener", busName, event.getClass().getSimpleName(), t);
                    }
                }
            }
            current = current.getSuperclass();
        }

        return event;
    }

    private void sortListeners(Class<?> eventType) {
        List<RegisteredListener> list = listenersByEventType.get(eventType);
        if (list != null) {
            list.sort(Comparator.naturalOrder());
        }
    }

    private Class<?> resolveConsumerEventType(Consumer<?> consumer) {
        for (Method method : consumer.getClass().getDeclaredMethods()) {
            if ("accept".equals(method.getName()) && !method.isBridge()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && Event.class.isAssignableFrom(params[0])) {
                    return params[0];
                }
            }
        }
        return Event.class;
    }
}
