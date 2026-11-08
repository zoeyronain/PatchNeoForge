package org.patchneoforge.loader;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.javafmlmod.FMLModContainer;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.List;

public class NeoForgeModContainer extends FMLModContainer {

    private final File jarFile;
    private final NeoForgeModMetadata metadata;
    private final IEventBus eventBus;
    private Object modInstance;
    private ClassLoader classLoader;

    public NeoForgeModContainer(NeoForgeModMetadata metadata, File jarFile, IEventBus eventBus, ClassLoader classLoader) {
        super(createModInfo(metadata), List.of(), new net.neoforged.neoforgespi.language.ModFileScanData(), ModuleLayer.boot());
        this.metadata = metadata;
        this.jarFile = jarFile;
        this.eventBus = eventBus;
        this.classLoader = classLoader;
    }

    public NeoForgeModContainer(NeoForgeModMetadata metadata, IEventBus eventBus) {
        this(metadata, null, eventBus, NeoForgeModContainer.class.getClassLoader());
    }

    public static IModInfo createModInfo(NeoForgeModMetadata meta) {
        Class<?> modFileInfoClass;
        Class<?> modFileClass;
        try {
            modFileInfoClass = Class.forName("net.neoforged.neoforgespi.language.IModFileInfo");
            modFileClass = Class.forName("net.neoforged.neoforgespi.locating.IModFile");
        } catch (ClassNotFoundException e) {
            modFileInfoClass = null;
            modFileClass = null;
        }

        final IModInfo[] modInfoHolder = new IModInfo[1];
        final Object[] modFileHolder = new Object[1];
        final Object[] owningFileHolder = new Object[1];

        if (modFileClass != null) {
            modFileHolder[0] = Proxy.newProxyInstance(
                    modFileClass.getClassLoader(),
                    new Class<?>[]{modFileClass},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("getModInfos".equals(name)) {
                            return modInfoHolder[0] != null ? List.of(modInfoHolder[0]) : List.of();
                        }
                        if ("getModFileInfo".equals(name)) return owningFileHolder[0];
                        if ("getFilePath".equals(name)) return java.nio.file.Path.of(".");
                        if ("getFileName".equals(name)) return meta.getModId() + ".jar";
                        if ("getScanResult".equals(name)) return new net.neoforged.neoforgespi.language.ModFileScanData();
                        if ("findResource".equals(name)) {
                            try {
                                net.neoforged.fml.ModList modList = net.neoforged.fml.ModList.get();
                                if (modList != null) {
                                    net.neoforged.neoforgespi.language.IModFileInfo realInfo = modList.getModFileById(meta.getModId());
                                    if (realInfo != null && realInfo.getFile() != null) {
                                        Object realFile = realInfo.getFile();
                                        if (realFile != proxy) {
                                            return method.invoke(realFile, args);
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                            String sub = "res";
                            if (args != null && args.length > 0 && args[0] instanceof String[] parts) {
                                sub = String.join("/", parts);
                            }
                            return java.nio.file.Path.of("./nonexistent-resource/" + sub);
                        }
                        return null;
                    }
            );
        }

        if (modFileInfoClass != null) {
            owningFileHolder[0] = Proxy.newProxyInstance(
                    modFileInfoClass.getClassLoader(),
                    new Class<?>[]{modFileInfoClass},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("moduleName".equals(name)) return "java.base";
                        if ("version".equals(name)) return meta.getVersion();
                        if ("getFile".equals(name)) return modFileHolder[0];
                        if ("getMods".equals(name)) {
                            return modInfoHolder[0] != null ? List.of(modInfoHolder[0]) : List.of();
                        }
                        return null;
                    }
            );
        }

        modInfoHolder[0] = (IModInfo) Proxy.newProxyInstance(
                IModInfo.class.getClassLoader(),
                new Class<?>[]{IModInfo.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getModId".equals(name) || "getNamespace".equals(name)) return meta.getModId();
                    if ("getDisplayName".equals(name)) return meta.getDisplayName();
                    if ("getDescription".equals(name)) return meta.getDescription();
                    if ("getVersion".equals(name)) return new DefaultArtifactVersion(meta.getVersion());
                    if ("getOwningFile".equals(name)) return owningFileHolder[0];
                    return null;
                }
        );

        return modInfoHolder[0];
    }

    @Override
    public IEventBus getEventBus() {
        return eventBus;
    }

    public Object getMod() {
        return modInstance;
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
