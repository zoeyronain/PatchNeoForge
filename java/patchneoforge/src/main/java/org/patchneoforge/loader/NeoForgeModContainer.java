package org.patchneoforge.loader;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.io.File;
import java.lang.reflect.Proxy;

public class NeoForgeModContainer extends ModContainer {

    private final File jarFile;
    private final NeoForgeModMetadata metadata;
    private final IEventBus eventBus;
    private Object modInstance;
    private ClassLoader classLoader;

    public NeoForgeModContainer(NeoForgeModMetadata metadata, File jarFile, IEventBus eventBus, ClassLoader classLoader) {
        super(createModInfo(metadata));
        this.metadata = metadata;
        this.jarFile = jarFile;
        this.eventBus = eventBus;
        this.classLoader = classLoader;
    }

    public static IModInfo createModInfo(NeoForgeModMetadata meta) {
        Class<?> modFileInfoClass;
        try {
            modFileInfoClass = Class.forName("net.neoforged.neoforgespi.language.IModFileInfo");
        } catch (ClassNotFoundException e) {
            modFileInfoClass = null;
        }

        final Object owningFile;
        if (modFileInfoClass != null) {
            owningFile = Proxy.newProxyInstance(
                    modFileInfoClass.getClassLoader(),
                    new Class<?>[]{modFileInfoClass},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("moduleName".equals(name)) return "java.base";
                        if ("version".equals(name)) return meta.getVersion();
                        return null;
                    }
            );
        } else {
            owningFile = null;
        }

        return (IModInfo) Proxy.newProxyInstance(
                IModInfo.class.getClassLoader(),
                new Class<?>[]{IModInfo.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getModId".equals(name) || "getNamespace".equals(name)) return meta.getModId();
                    if ("getDisplayName".equals(name)) return meta.getDisplayName();
                    if ("getDescription".equals(name)) return meta.getDescription();
                    if ("getVersion".equals(name)) return new DefaultArtifactVersion(meta.getVersion());
                    if ("getOwningFile".equals(name)) return owningFile;
                    return null;
                }
        );
    }

    @Override
    public IEventBus getEventBus() {
        return eventBus;
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
