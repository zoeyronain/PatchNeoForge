package org.patchneoforge.loader;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.ModContainer;

import java.io.File;

public class NeoForgeModContainer extends ModContainer {

    private final File jarFile;
    private final NeoForgeModMetadata metadata;
    private Object modInstance;
    private ClassLoader classLoader;

    public NeoForgeModContainer(NeoForgeModMetadata metadata, File jarFile, IEventBus eventBus, ClassLoader classLoader) {
        super(metadata.getModId(), metadata.getDisplayName(), metadata.getVersion(), eventBus);
        this.metadata = metadata;
        this.jarFile = jarFile;
        this.classLoader = classLoader;
    }

    public File getJarFile() {
        return jarFile;
    }

    public NeoForgeModMetadata getMetadata() {
        return metadata;
    }

    public Object getModInstance() {
        return modInstance;
    }

    public void setModInstance(Object modInstance) {
        this.modInstance = modInstance;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }
}
