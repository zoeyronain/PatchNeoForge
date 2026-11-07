package net.neoforged.fml.common;

public class ModLoadingContext {

    private static final ThreadLocal<ModLoadingContext> CURRENT = ThreadLocal.withInitial(ModLoadingContext::new);

    private ModContainer activeContainer;

    public static ModLoadingContext get() {
        return CURRENT.get();
    }

    public ModContainer getActiveContainer() {
        return activeContainer;
    }

    public void setActiveContainer(ModContainer container) {
        this.activeContainer = container;
    }
}
