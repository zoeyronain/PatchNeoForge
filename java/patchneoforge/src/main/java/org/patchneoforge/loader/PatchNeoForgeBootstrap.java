package org.patchneoforge.loader;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.patchneoforge.bootstrap.CommandBridge;
import org.patchneoforge.bootstrap.HeadlessNeoForgeServer;
import org.patchneoforge.events.NeoForgeEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class PatchNeoForgeBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(PatchNeoForgeBootstrap.class);
    private static final Map<String, net.neoforged.fml.ModContainer> LOADED_MODS = new ConcurrentHashMap<>();

    private PatchNeoForgeBootstrap() {}

    public static Map<String, net.neoforged.fml.ModContainer> getLoadedMods() {
        return Collections.unmodifiableMap(LOADED_MODS);
    }

    public static boolean bootstrapMods(String modsDirPath) {
        LOGGER.info("[PatchNeoForge] Starting NeoForge mod bootstrap from: {}", modsDirPath);

        List<File> searchDirs = new ArrayList<>();
        File primaryDir = new File(modsDirPath);
        if (!primaryDir.exists()) {
            primaryDir.mkdirs();
        }
        searchDirs.add(primaryDir);

        if (primaryDir.getParentFile() != null) {
            File rootMods = new File(primaryDir.getParentFile(), "mods");
            if (rootMods.exists() && !searchDirs.contains(rootMods)) {
                searchDirs.add(rootMods);
            }
            if (primaryDir.getParentFile().getParentFile() != null) {
                File topMods = new File(primaryDir.getParentFile().getParentFile(), "mods");
                if (topMods.exists() && !searchDirs.contains(topMods)) {
                    searchDirs.add(topMods);
                }
            }
        }

        List<File> modJars = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (File dir : searchDirs) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".jar"));
            if (files != null) {
                for (File f : files) {
                    if (seenNames.add(f.getName())) {
                        modJars.add(f);
                    }
                }
            }
        }

        if (modJars.isEmpty()) {
            LOGGER.info("[PatchNeoForge] No NeoForge mod JARs found in mod search paths: {}", searchDirs);
            fireServerStartingEvents();
            return true;
        }

        LOGGER.info("[PatchNeoForge] Found {} candidate mod JAR(s)", modJars.size());

        // Extract nested JarJar dependencies (e.g. Kotlin runtime, libraries)
        File jarJarCache = new File(primaryDir.getParentFile(), "cache/jarjar");
        List<File> allClassPathJars = new ArrayList<>(modJars);
        for (File jarFile : modJars) {
            extractJarJarDependencies(jarFile, jarJarCache, allClassPathJars);
        }

        // Append all jars to system classloader search if Instrumentation is available
        Instrumentation inst = HeadlessNeoForgeServer.getInstrumentation();
        for (File f : allClassPathJars) {
            if (inst != null) {
                try {
                    inst.appendToSystemClassLoaderSearch(new JarFile(f));
                } catch (Throwable t) {
                    LOGGER.debug("[PatchNeoForge] Could not append {} to system class loader search: {}", f.getName(), t.getMessage());
                }
            }
        }

        // Build unified Mod ClassLoader
        List<URL> urls = new ArrayList<>();
        for (File f : allClassPathJars) {
            try {
                urls.add(f.toURI().toURL());
            } catch (Throwable ignored) {}
        }

        URLClassLoader sharedModClassLoader = new URLClassLoader(
                urls.toArray(URL[]::new),
                PatchNeoForgeBootstrap.class.getClassLoader()
        );
        Thread.currentThread().setContextClassLoader(sharedModClassLoader);

        try {
            net.neoforged.fml.ModList.of(List.of(), List.of());
        } catch (Throwable t) {
            LOGGER.debug("[PatchNeoForge] Could not init ModList.of: {}", t.getMessage());
        }

        try {
            Class.forName("net.neoforged.fml.ModContainer", true, PatchNeoForgeBootstrap.class.getClassLoader());
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Pre-initializing ModContainer clinit: {}", t.getMessage());
        }

        try {
            Class<?> distClass = Class.forName("net.neoforged.api.distmarker.Dist", true, sharedModClassLoader);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object dedicatedServerDist = Enum.valueOf((Class<Enum>) distClass, "DEDICATED_SERVER");

            Class<?> fmlLoaderClass = Class.forName("net.neoforged.fml.loading.FMLLoader", true, sharedModClassLoader);
            Field fmlLoaderDist = fmlLoaderClass.getDeclaredField("dist");
            fmlLoaderDist.setAccessible(true);
            fmlLoaderDist.set(null, dedicatedServerDist);

            Class<?> fmlEnvClass = Class.forName("net.neoforged.fml.loading.FMLEnvironment", true, sharedModClassLoader);
            try {
                Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                Object unsafe = unsafeField.get(null);
                Field distField = fmlEnvClass.getDeclaredField("dist");
                Method staticFieldBase = unsafe.getClass().getMethod("staticFieldBase", Field.class);
                Method staticFieldOffset = unsafe.getClass().getMethod("staticFieldOffset", Field.class);
                Method putObject = unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class);
                Object base = staticFieldBase.invoke(unsafe, distField);
                long offset = ((Number) staticFieldOffset.invoke(unsafe, distField)).longValue();
                putObject.invoke(unsafe, base, offset, dedicatedServerDist);
            } catch (Throwable t2) {
                try {
                    Field fmlEnvDist = fmlEnvClass.getDeclaredField("dist");
                    fmlEnvDist.setAccessible(true);
                    fmlEnvDist.set(null, dedicatedServerDist);
                } catch (Throwable ignored) {}
            }

            LOGGER.info("[PatchNeoForge] Initialized FMLLoader and FMLEnvironment dist = DEDICATED_SERVER");
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Could not initialize FMLLoader.dist: {}", t.getMessage());
        }

        try {
            Class<?> sharedConstants = Class.forName("net.minecraft.SharedConstants", true, sharedModClassLoader);
            sharedConstants.getMethod("tryDetectVersion").invoke(null);
            Class<?> bootstrapClass = Class.forName("net.minecraft.server.Bootstrap", true, sharedModClassLoader);
            bootstrapClass.getMethod("bootStrap").invoke(null);
            LOGGER.info("[PatchNeoForge] Minecraft Bootstrap.bootStrap() completed successfully");
            unfreezeRegistries(sharedModClassLoader);
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Could not run Minecraft Bootstrap.bootStrap(): {}", t.getMessage(), t);
        }

        for (File jarFile : modJars) {
            try {
                loadModJar(jarFile, sharedModClassLoader);
            } catch (Throwable t) {
                LOGGER.error("[PatchNeoForge] Failed to load mod JAR: {}", jarFile.getName(), t);
            }
        }

        LOGGER.info("[PatchNeoForge] Successfully initialized {} NeoForge mod(s)", LOADED_MODS.size());

        // Run authentic NeoForge startup lifecycle across all loaded mods
        runModLifecycle(sharedModClassLoader);

        // Run server starting lifecycle and command registration
        fireServerStartingEvents();

        return true;
    }

    private static void extractJarJarDependencies(File modJar, File cacheDir, List<File> outputList) {
        try (JarFile jar = new JarFile(modJar)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF/jarjar/") && name.endsWith(".jar")) {
                    String simpleName = name.substring(name.lastIndexOf('/') + 1);
                    File extracted = new File(cacheDir, simpleName);
                    if (!extracted.exists() || (entry.getSize() > 0 && extracted.length() != entry.getSize())) {
                        extracted.getParentFile().mkdirs();
                        try (InputStream in = jar.getInputStream(entry);
                             OutputStream out = new FileOutputStream(extracted)) {
                            in.transferTo(out);
                        }
                    }
                    if (!outputList.contains(extracted)) {
                        outputList.add(extracted);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[PatchNeoForge] No JarJar processed in {}: {}", modJar.getName(), t.getMessage());
        }
    }

    private static void loadModJar(File jarFile, ClassLoader classLoader) throws Exception {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry tomlEntry = jar.getJarEntry("META-INF/neoforge.mods.toml");
            if (tomlEntry == null) {
                tomlEntry = jar.getJarEntry("META-INF/mods.toml");
            }

            if (tomlEntry == null) {
                LOGGER.warn("[PatchNeoForge] Skipping {}: missing META-INF/neoforge.mods.toml", jarFile.getName());
                return;
            }

            List<NeoForgeModMetadata> metadataList;
            try (InputStream in = jar.getInputStream(tomlEntry)) {
                metadataList = NeoForgeModMetadata.parseFromToml(in);
            }

            if (metadataList.isEmpty()) {
                LOGGER.warn("[PatchNeoForge] Skipping {}: no mod definitions found in mods.toml", jarFile.getName());
                return;
            }

            // Scan classes inside JAR for @Mod and @EventBusSubscriber
            Enumeration<JarEntry> entries = jar.entries();
            List<Class<?>> jarClasses = new ArrayList<>();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.contains("$")) {
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    try {
                        Class<?> clazz = Class.forName(className, false, classLoader);
                        jarClasses.add(clazz);
                    } catch (Throwable ignored) {}
                }
            }

            for (NeoForgeModMetadata meta : metadataList) {
                String modId = meta.getModId();
                if (modId == null || modId.isEmpty()) continue;

                // Find class annotated with @Mod matching modId
                Class<?> mainModClass = null;
                for (Class<?> clazz : jarClasses) {
                    Mod modAnn = clazz.getAnnotation(Mod.class);
                    if (modAnn != null && (modAnn.value().equals(modId) || metadataList.size() == 1)) {
                        mainModClass = clazz;
                        break;
                    }
                }

                net.neoforged.fml.ModContainer container;
                IEventBus modEventBus;

                if (meta.getModLoader().contains("kotlin")) {
                    try {
                        Class<?> kffClass = Class.forName("thedarkcolour.kotlinforforge.neoforge.KotlinModContainer", true, classLoader);
                        Constructor<?> kffCtor = kffClass.getConstructor(
                                net.neoforged.neoforgespi.language.IModInfo.class,
                                List.class,
                                net.neoforged.neoforgespi.language.ModFileScanData.class,
                                ModuleLayer.class
                        );
                        net.neoforged.neoforgespi.language.IModInfo modInfo = NeoForgeModContainer.createModInfo(meta);
                        // Pass empty entrypoints list so KotlinModContainer doesn't attempt to load classes via ModuleLayer
                        List<String> entrypoints = List.of();
                        container = (net.neoforged.fml.ModContainer) kffCtor.newInstance(
                                modInfo,
                                entrypoints,
                                new net.neoforged.neoforgespi.language.ModFileScanData(),
                                ModuleLayer.boot()
                        );
                        modEventBus = container.getEventBus();
                        LOGGER.info("[PatchNeoForge] Created KotlinModContainer for '{}'", modId);
                    } catch (Throwable t) {
                        LOGGER.warn("[PatchNeoForge] Could not instantiate KotlinModContainer for '{}', falling back: {}", modId, t.getMessage(), t);
                        modEventBus = new NeoForgeEventBus("ModBus[" + modId + "]");
                        container = new NeoForgeModContainer(meta, jarFile, modEventBus, classLoader);
                    }
                } else {
                    modEventBus = new NeoForgeEventBus("ModBus[" + modId + "]");
                    container = new NeoForgeModContainer(meta, jarFile, modEventBus, classLoader);
                }

                ModLoadingContext.get().setActiveContainer(container);

                if (mainModClass != null) {
                    LOGGER.info("[PatchNeoForge] Instantiating mod '{}' ({}) via {}",
                            meta.getDisplayName(), meta.getVersion(), mainModClass.getName());
                    Object instance = instantiateModClass(mainModClass, modEventBus, container);
                    if (container instanceof NeoForgeModContainer nmc) {
                        nmc.setModInstance(instance);
                    } else {
                        try {
                            Field f = container.getClass().getDeclaredField("modInstance");
                            f.setAccessible(true);
                            f.set(container, instance);
                        } catch (Throwable ignored) {}
                    }
                } else {
                    LOGGER.warn("[PatchNeoForge] Mod '{}' defined in toml but no matching @Mod class found", modId);
                }

                // Process @EventBusSubscriber classes for this mod
                for (Class<?> clazz : jarClasses) {
                    EventBusSubscriber subscriber = clazz.getAnnotation(EventBusSubscriber.class);
                    if (subscriber != null) {
                        if (subscriber.modid().isEmpty() || subscriber.modid().equals(modId)) {
                            IEventBus targetBus = (subscriber.bus() == EventBusSubscriber.Bus.MOD)
                                    ? modEventBus
                                    : NeoForge.EVENT_BUS;
                            targetBus.register(clazz);
                            LOGGER.debug("[PatchNeoForge] Registered static @EventBusSubscriber: {}", clazz.getName());
                        }
                    }
                }

                LOADED_MODS.put(modId, container);
                ModLoadingContext.get().setActiveContainer(null);
            }
        }
    }

    private static Object instantiateModClass(Class<?> clazz, IEventBus modEventBus, net.neoforged.fml.ModContainer container) throws Exception {
        // Look for constructors matching standard NeoForge patterns:
        // 1. (IEventBus, ModContainer)
        // 2. (IEventBus)
        // 3. (ModContainer)
        // 4. ()
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            ctor.setAccessible(true);

            if (params.length == 2 && params[0].isAssignableFrom(modEventBus.getClass()) && params[1].isAssignableFrom(container.getClass())) {
                return ctor.newInstance(modEventBus, container);
            }
            if (params.length == 2 && IEventBus.class.isAssignableFrom(params[0])) {
                return ctor.newInstance(modEventBus, container);
            }
            if (params.length == 1 && IEventBus.class.isAssignableFrom(params[0])) {
                return ctor.newInstance(modEventBus);
            }
            if (params.length == 1 && params[0].isAssignableFrom(container.getClass())) {
                return ctor.newInstance(container);
            }
            if (params.length == 0) {
                return ctor.newInstance();
            }
        }

        // Fallback: try no-arg
        Constructor<?> noArg = clazz.getDeclaredConstructor();
        noArg.setAccessible(true);
        return noArg.newInstance();
    }

    public static void unfreezeRegistries(ClassLoader classLoader) {
        try {
            Class<?> builtInRegistriesClass = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, classLoader);
            Class<?> mappedRegistryClass = Class.forName("net.minecraft.core.MappedRegistry", true, classLoader);
            java.lang.reflect.Field frozenField = mappedRegistryClass.getDeclaredField("frozen");
            frozenField.setAccessible(true);

            for (java.lang.reflect.Field field : builtInRegistriesClass.getFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    try {
                        Object reg = field.get(null);
                        if (mappedRegistryClass.isInstance(reg)) {
                            frozenField.set(reg, false);
                        }
                    } catch (Throwable ignored) {}
                }
            }

            Object rootRegistry = builtInRegistriesClass.getField("REGISTRY").get(null);
            if (mappedRegistryClass.isInstance(rootRegistry)) {
                frozenField.set(rootRegistry, false);
            }
            if (rootRegistry instanceof Iterable<?> iterable) {
                for (Object subRegistry : iterable) {
                    if (mappedRegistryClass.isInstance(subRegistry)) {
                        frozenField.set(subRegistry, false);
                    }
                }
            }
            LOGGER.info("[PatchNeoForge] Successfully unfroze Minecraft registries for mod registration");
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Could not unfreeze registries: {}", t.getMessage());
        }
    }

    private static void runModLifecycle(ClassLoader classLoader) {
        LOGGER.info("[PatchNeoForge] Executing NeoForge mod lifecycle phases...");

        // Ensure registries are unfrozen during mod registration
        if (classLoader != null) {
            unfreezeRegistries(classLoader);
        }

        // 1. FMLConstructModEvent
        for (net.neoforged.fml.ModContainer container : LOADED_MODS.values()) {
            ModLoadingContext.get().setActiveContainer(container);
            container.getEventBus().post(new FMLConstructModEvent(container));
        }

        // 2. RegisterEvent across all registries in BuiltInRegistries.REGISTRY
        try {
            Class<?> builtInRegistriesClass = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, classLoader);
            Object rootRegistry = builtInRegistriesClass.getField("REGISTRY").get(null);
            if (rootRegistry instanceof net.minecraft.core.Registry<?> rootReg) {
                for (java.util.Map.Entry<? extends net.minecraft.resources.ResourceKey<?>, ?> entry : rootReg.entrySet()) {
                    net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>> regKey =
                            (net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>) entry.getKey();
                    net.minecraft.core.Registry<?> subRegistry = (net.minecraft.core.Registry<?>) entry.getValue();
                    net.neoforged.neoforge.registries.RegisterEvent regEvent =
                            new net.neoforged.neoforge.registries.RegisterEvent(regKey, subRegistry);

                    for (net.neoforged.fml.ModContainer container : LOADED_MODS.values()) {
                        ModLoadingContext.get().setActiveContainer(container);
                        container.getEventBus().post(regEvent);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Could not enumerate BuiltInRegistries for RegisterEvent: {}", t.getMessage());
        }

        // 3. FMLCommonSetupEvent
        for (net.neoforged.fml.ModContainer container : LOADED_MODS.values()) {
            ModLoadingContext.get().setActiveContainer(container);
            FMLCommonSetupEvent event = new FMLCommonSetupEvent(container);
            container.getEventBus().post(event);
            event.processEnqueuedWork();
        }

        // 4. FMLDedicatedServerSetupEvent
        for (net.neoforged.fml.ModContainer container : LOADED_MODS.values()) {
            ModLoadingContext.get().setActiveContainer(container);
            container.getEventBus().post(new FMLDedicatedServerSetupEvent(container));
        }

        // 5. FMLLoadCompleteEvent
        for (net.neoforged.fml.ModContainer container : LOADED_MODS.values()) {
            ModLoadingContext.get().setActiveContainer(container);
            container.getEventBus().post(new FMLLoadCompleteEvent(container));
        }

        LOGGER.info("[PatchNeoForge] Mod lifecycle initialization finished successfully.");
    }

    private static void fireServerStartingEvents() {
        LOGGER.info("[PatchNeoForge] Firing server starting lifecycle on NeoForge.EVENT_BUS...");

        // 1. ServerAboutToStartEvent
        NeoForge.EVENT_BUS.post(new ServerAboutToStartEvent());

        // 2. ServerStartingEvent
        NeoForge.EVENT_BUS.post(new ServerStartingEvent());

        // 3. RegisterCommandsEvent
        RegisterCommandsEvent cmdEvent = new RegisterCommandsEvent(CommandBridge.getDispatcher());
        NeoForge.EVENT_BUS.post(cmdEvent);
        CommandBridge.syncWithPumpkin();

        // 4. ServerStartedEvent
        NeoForge.EVENT_BUS.post(new ServerStartedEvent());

        LOGGER.info("[PatchNeoForge] Server started events processed.");
    }

    public static void shutdown() {
        LOGGER.info("[PatchNeoForge] Shutting down NeoForge mods...");
        NeoForge.EVENT_BUS.post(new ServerStoppingEvent());
        NeoForge.EVENT_BUS.post(new ServerStoppedEvent());
        LOADED_MODS.clear();
        LOGGER.info("[PatchNeoForge] NeoForge mods shut down cleanly.");
    }
}
