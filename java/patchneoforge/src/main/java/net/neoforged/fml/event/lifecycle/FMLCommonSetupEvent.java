package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.IModBusEvent;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FMLCommonSetupEvent extends Event implements IModBusEvent {

    private final ModContainer container;
    private final Queue<Runnable> workQueue = new ConcurrentLinkedQueue<>();

    public FMLCommonSetupEvent(ModContainer container) {
        this.container = container;
    }

    public ModContainer getContainer() {
        return container;
    }

    public CompletableFuture<Void> enqueueWork(Runnable work) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        workQueue.add(() -> {
            try {
                work.run();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public void processEnqueuedWork() {
        while (!workQueue.isEmpty()) {
            Runnable r = workQueue.poll();
            if (r != null) {
                r.run();
            }
        }
    }
}
