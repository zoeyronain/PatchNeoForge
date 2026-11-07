package org.patchneoforge.loader;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.ModLoadingContext;
import net.neoforged.fml.event.lifecycle.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.patchneoforge.bootstrap.CommandBridge;
import org.patchneoforge.events.NeoForgeEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class PatchNeoForgeBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(PatchNeoForgeBootstrap.class);
    private static final Map<String, NeoForgeModContainer> LOADED_MODS = new LinkedHashMap<>();

    public static Map<String, NeoForgeModContainer> getLoadedMods() {
        return Collections.unmodifiableMap(LOADED_MODS);
    }

    public static boolean bootstrapMods(String modsDirPath) {
        LOGGER.info("[PatchNeoForge] Starting NeoForge mod bootstrap from: {}", modsDirPath);

        File modsDir = new File(modsDirPath);
        if (!modsDir.exists()) {
            modsDir.mkdirs();
        }

        File[] files = modsDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (files == null || files.length == 0) {
            LOGGER.info("[PatchNeoForge] No NeoForge mod JARs found in: {}", modsDirPath);
            fireServerStartingEvents();
            return true;
        }

        List<File> modJars = Arrays.asList(files);
        LOGGER.info("[PatchNeoForge] Found {} candidate mod JAR(s)", modJars.size());

        for (File jarFile : modJars) {
            try {
                loadModJar(jarFile);
            } catch (Throwable t) {
                LOGGER.error("[PatchNeoForge] Failed to load mod JAR: {}", jarFile.getName(), t);
            }
        }

        LOGGER.info("[PatchNeoForge] Successfully initialized {} NeoForge mod(s)", LOADED_MODS.size());

        // Run authentic NeoForge startup lifecycle across all loaded mods
        runModLifecycle();

        // Run server starting lifecycle and command registration
        fireServerStartingEvents();

        return true;
    }

    private static void loadModJar(File jarFile) throws Exception {
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

            URLClassLoader classLoader = new URLClassLoader(
                    new URL[]{jarFile.toURI().toURL()},
                    PatchNeoForgeBootstrap.class.getClassLoader()
            );

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

                IEventBus modEventBus = new NeoForgeEventBus("ModBus[" + modId + "]");
                NeoForgeModContainer container = new NeoForgeModContainer(meta, jarFile, modEventBus, classLoader);

                ModLoadingContext.get().setActiveContainer(container);

                // Find class annotated with @Mod matching modId
                Class<?> mainModClass = null;
                for (Class<?> clazz : jarClasses) {
                    Mod modAnn = clazz.getAnnotation(Mod.class);
                    if (modAnn != null && (modAnn.value().equals(modId) || metadataList.size() == 1)) {
                        mainModClass = clazz;
                        break;
                    }
                }

                if (mainModClass != null) {
                    LOGGER.info("[PatchNeoForge] Instantiating mod '{}' ({}) via {}",
                            meta.getDisplayName(), meta.getVersion(), mainModClass.getName());
                    Object instance = instantiateModClass(mainModClass, modEventBus, container);
                    container.setModInstance(instance);
                } else {
                    LOGGER.warn("[PatchNeoForge] Mod '{}' defined in toml but no matching @Mod class found", modId);
                }

                // Process @EventBusSubscriber classes for this mod
                for (Class<?> clazz : jarClasses) {
                    EventBusSubscriber sub = clazz.getAnnotation(EventBusSubscriber.class);
                    if (sub != null && (sub.modid().equals(modId) || sub.modid().isEmpty())) {
                        if (sub.bus() == EventBusSubscriber.Bus.MOD) {
                            modEventBus.register(clazz);
                        } else {
                            NeoForge.EVENT_BUS.register(clazz);
                        }
                    }
                }

                LOADED_MODS.put(modId, container);
            }
        }
    }

    private static Object instantiateModClass(Class<?> clazz, IEventBus modEventBus, NeoForgeModContainer container) throws Exception {
        // Try constructor accepting IEventBus
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor(IEventBus.class);
            ctor.setAccessible(true);
            return ctor.newInstance(modEventBus);
        } catch (NoSuchMethodException ignored) {}

        // Try constructor accepting ModContainer
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor(net.neoforged.fml.common.ModContainer.class);
            ctor.setAccessible(true);
            return ctor.newInstance(container);
        } catch (NoSuchMethodException ignored) {}

        // Fallback to default no-args constructor
        Constructor<?> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static void runModLifecycle() {
        LOGGER.info("[PatchNeoForge] Executing NeoForge mod lifecycle phases...");

        // 1. FMLConstructModEvent
        for (NeoForgeModContainer container : LOADED_MODS.values()) {
            ModLoadingContext.get().setActiveContainer(container);
            container.getEventBus().post(new FMLConstructModEvent(container));
        }

        // 2. RegisterEvent (DeferredRegister hooks)
        String[] standardRegistries = {
                "minecraft:block",
                "minecraft:item",
                "minecraft:entity_type",
                "minecraft:sound_event",
                "minecraft:custom_stat"
        };
        for (String reg : standardRegistries) {
            for (NeoForgeModContainer container : LOADED_MODS.values()) {
                ModLoadingContext.get().setActiveContainer(container);
                container.getEventBus().post(new RegisterEvent(reg));
            }
        }

        // 3. FMLCommonSetupEvent
        for (NeoForgeModContainer container : LOADED_MODS.values()) {
            ModLoadingContext.get().setActiveContainer(container);
            FMLCommonSetupEvent event = new FMLCommonSetupEvent(container);
            container.getEventBus().post(event);
            event.processEnqueuedWork();
        }

        // 4. FMLDedicatedServerSetupEvent
        for (NeoForgeModContainer container : LOADED_MODS.values()) {
            ModLoadingContext.get().setActiveContainer(container);
            container.getEventBus().post(new FMLDedicatedServerSetupEvent(container));
        }

        // 5. FMLLoadCompleteEvent
        for (NeoForgeModContainer container : LOADED_MODS.values()) {
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
        LOGGER.info("[PatchNeoForge] Shutdown complete.");
    }
}
