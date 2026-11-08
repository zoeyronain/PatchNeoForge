package org.patchneoforge.loader;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.DeferredWorkQueue;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.registries.GameData;
import net.neoforged.neoforge.registries.NewRegistryEvent;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class PatchNeoForgeBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(PatchNeoForgeBootstrap.class);
    private static final Set<String> DISCOVERED_MOD_IDS = ConcurrentHashMap.newKeySet();
    private static final Map<String, net.neoforged.fml.ModContainer> LOADED_MODS = new ConcurrentHashMap<>();
    private static final Map<String, net.neoforged.fml.ModContainer> INDEXED_MODS = new ConcurrentHashMap<>() {
        @Override
        public boolean containsKey(Object key) {
            return super.containsKey(key) || (key instanceof String modId && DISCOVERED_MOD_IDS.contains(modId));
        }

        @Override
        public net.neoforged.fml.ModContainer get(Object key) {
            net.neoforged.fml.ModContainer c = super.get(key);
            if (c != null) return c;
            if (key instanceof String modId && DISCOVERED_MOD_IDS.contains(modId)) {
                NeoForgeEventBus bus = new NeoForgeEventBus("PlaceholderBus[" + modId + "]");
                NeoForgeModMetadata meta = new NeoForgeModMetadata(modId, "1.0.0", modId, "");
                NeoForgeModContainer placeholder = new NeoForgeModContainer(meta, bus);
                super.put(modId, placeholder);
                return placeholder;
            }
            return null;
        }
    };

    private PatchNeoForgeBootstrap() {}

    public static Map<String, net.neoforged.fml.ModContainer> getLoadedMods() {
        return Collections.unmodifiableMap(LOADED_MODS);
    }

    public static boolean bootstrapMods(String modsDirPath) {
        LOGGER.info("[PatchNeoForge] Starting NeoForge mod bootstrap from: {}", modsDirPath);

        // 1. Initialize FMLPaths so config directory, game directory, and mods directory are available
        try {
            Path rootPath = Path.of(".").toAbsolutePath().normalize();
            Files.createDirectories(rootPath.resolve("config"));
            Files.createDirectories(rootPath.resolve("world"));
            Files.createDirectories(rootPath.resolve("mods"));

            Class<?> fmlPathsClass = Class.forName("net.neoforged.fml.loading.FMLPaths", true, PatchNeoForgeBootstrap.class.getClassLoader());
            Method loadPaths = fmlPathsClass.getMethod("loadAbsolutePaths", Path.class);
            loadPaths.invoke(null, rootPath);
            LOGGER.info("[PatchNeoForge] Initialized FMLPaths with root: {}", rootPath);
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Could not initialize FMLPaths: {}", t.getMessage());
        }

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

        DISCOVERED_MOD_IDS.clear();
        DISCOVERED_MOD_IDS.add("minecraft");
        DISCOVERED_MOD_IDS.add("neoforge");

        for (File jarFile : modJars) {
            try (JarFile jar = new JarFile(jarFile)) {
                JarEntry tomlEntry = jar.getJarEntry("META-INF/neoforge.mods.toml");
                if (tomlEntry == null) tomlEntry = jar.getJarEntry("META-INF/mods.toml");
                if (tomlEntry != null) {
                    try (InputStream in = jar.getInputStream(tomlEntry)) {
                        for (NeoForgeModMetadata m : NeoForgeModMetadata.parseFromToml(in)) {
                            if (m.getModId() != null) {
                                DISCOVERED_MOD_IDS.add(m.getModId());
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        LOGGER.info("[PatchNeoForge] Discovered {} unique mod IDs from JARs", DISCOVERED_MOD_IDS.size());

        // Extract nested JarJar and JarInJar dependencies (META-INF/jarjar/ and META-INF/jars/)
        File baseDir = primaryDir.getParentFile() != null ? primaryDir.getParentFile() : primaryDir;
        File rootServerDir = baseDir.getParentFile() != null ? baseDir.getParentFile() : baseDir;
        File jarJarCache = new File(baseDir, "cache/jarjar");
        jarJarCache.mkdirs();

        List<File> allClassPathJars = new ArrayList<>(modJars);

        for (File jarFile : modJars) {
            extractJarJarDependencies(jarFile, jarJarCache, allClassPathJars, modJars);
        }

        // Add server libraries to allClassPathJars so both the system classloader search and mod classloader can resolve all dependencies
        List<File> libraryDirs = List.of(
                new File(rootServerDir, "libraries"),
                new File(baseDir, "cache/neoforge/libraries"),
                new File(baseDir, "neoforge-runtime"),
                jarJarCache
        );
        for (File dir : libraryDirs) {
            if (dir.exists()) {
                try (java.util.stream.Stream<Path> s = Files.walk(dir.toPath())) {
                    s.filter(p -> p.toString().endsWith(".jar")).forEach(p -> {
                        File f = p.toFile();
                        if (!allClassPathJars.contains(f)) {
                            allClassPathJars.add(f);
                        }
                    });
                } catch (Throwable ignored) {}
            }
        }

        // Append all jars to system classloader search if Instrumentation is available
        Instrumentation inst = HeadlessNeoForgeServer.getInstrumentation();
        ensureShadedNightConfig(baseDir, inst, allClassPathJars);
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
        ) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                try {
                    return super.findClass(name);
                } catch (ClassNotFoundException e) {
                    if (name != null && name.startsWith("house.greenhouse.greenhouseconfig.nightconfig.shade.")) {
                        String origName = "com.electronwill.nightconfig." + name.substring("house.greenhouse.greenhouseconfig.nightconfig.shade.".length());
                        String resPath = origName.replace('.', '/') + ".class";
                        try (InputStream in = getResourceAsStream(resPath)) {
                            if (in != null) {
                                byte[] origBytes = in.readAllBytes();
                                net.bytebuddy.jar.asm.ClassReader cr = new net.bytebuddy.jar.asm.ClassReader(origBytes);
                                net.bytebuddy.jar.asm.ClassWriter cw = new net.bytebuddy.jar.asm.ClassWriter(0);
                                net.bytebuddy.jar.asm.commons.ClassRemapper remapper = new net.bytebuddy.jar.asm.commons.ClassRemapper(
                                        cw,
                                        new net.bytebuddy.jar.asm.commons.SimpleRemapper("com/electronwill/nightconfig", "house/greenhouse/greenhouseconfig/nightconfig/shade")
                                );
                                cr.accept(remapper, 0);
                                byte[] transformed = cw.toByteArray();
                                return defineClass(name, transformed, 0, transformed.length);
                            }
                        } catch (Throwable ignored) {}
                    }
                    throw e;
                }
            }
        };
        Thread.currentThread().setContextClassLoader(sharedModClassLoader);

        // Pre-initialize ModList with non-null indexedMods, fileById, and modFiles so early checks succeed
        try {
            net.neoforged.fml.ModList modList = net.neoforged.fml.ModList.of(List.of(), List.of());
            if (modList == null) {
                modList = net.neoforged.fml.ModList.get();
            }
            if (modList != null) {
                try {
                    Field indexedModsField = net.neoforged.fml.ModList.class.getDeclaredField("indexedMods");
                    indexedModsField.setAccessible(true);
                    indexedModsField.set(modList, INDEXED_MODS);
                } catch (Throwable ignored) {}

                try {
                    Field fileByIdField = net.neoforged.fml.ModList.class.getDeclaredField("fileById");
                    fileByIdField.setAccessible(true);
                    fileByIdField.set(modList, new ConcurrentHashMap<>());
                } catch (Throwable ignored) {}

                try {
                    Field modFilesField = net.neoforged.fml.ModList.class.getDeclaredField("modFiles");
                    modFilesField.setAccessible(true);
                    modFilesField.set(modList, new java.util.concurrent.CopyOnWriteArrayList<>());
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

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

            try {
                Field gamePathField = fmlLoaderClass.getDeclaredField("gamePath");
                gamePathField.setAccessible(true);
                gamePathField.set(null, Path.of(".").toAbsolutePath());
            } catch (Throwable ignored) {}

            System.setProperty("production", "true");
            try {
                Field fmlLoaderProd = fmlLoaderClass.getDeclaredField("production");
                fmlLoaderProd.setAccessible(true);
                fmlLoaderProd.setBoolean(null, true);
            } catch (Throwable ignored) {}

            try {
                Class<?> viClass = Class.forName("net.neoforged.fml.loading.VersionInfo", true, sharedModClassLoader);
                Object vi = viClass.getConstructor(String.class, String.class, String.class, String.class)
                        .newInstance("21.1.211", "4.0.42", "1.21.1", "20240808.144430");
                setFieldStatic(fmlLoaderClass, "versionInfo", vi);
            } catch (Throwable ignored) {}

            try {
                Class<?> envClass = Class.forName("net.neoforged.neoforgespi.Environment", true, sharedModClassLoader);
                Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                sun.misc.Unsafe u = (sun.misc.Unsafe) unsafeField.get(null);
                Object envInstance = u.allocateInstance(envClass);
                Field envDistField = envClass.getDeclaredField("dist");
                envDistField.setAccessible(true);
                envDistField.set(envInstance, dedicatedServerDist);
                Field envInstField = envClass.getDeclaredField("INSTANCE");
                envInstField.setAccessible(true);
                envInstField.set(null, envInstance);
            } catch (Throwable ignored) {}

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

                Field prodField = fmlEnvClass.getDeclaredField("production");
                Object prodBase = staticFieldBase.invoke(unsafe, prodField);
                long prodOffset = ((Number) staticFieldOffset.invoke(unsafe, prodField)).longValue();
                Method putBoolean = unsafe.getClass().getMethod("putBoolean", Object.class, long.class, boolean.class);
                putBoolean.invoke(unsafe, prodBase, prodOffset, true);
            } catch (Throwable t2) {
                try {
                    Field fmlEnvDist = fmlEnvClass.getDeclaredField("dist");
                    fmlEnvDist.setAccessible(true);
                    fmlEnvDist.set(null, dedicatedServerDist);
                } catch (Throwable ignored) {}
                try {
                    Field prodField = fmlEnvClass.getDeclaredField("production");
                    prodField.setAccessible(true);
                    prodField.setBoolean(null, true);
                } catch (Throwable ignored) {}
            }

            for (ClassLoader cl : new ClassLoader[]{PatchNeoForgeBootstrap.class.getClassLoader(), sharedModClassLoader}) {
                try {
                    Class<?> mfpClass = Class.forName("org.embeddedt.modernfix.core.ModernFixMixinPlugin", true, cl);
                    java.lang.reflect.Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
                    unsafeField.setAccessible(true);
                    sun.misc.Unsafe u = (sun.misc.Unsafe) unsafeField.get(null);
                    Object mfp = u.allocateInstance(mfpClass);
                    Field instField = mfpClass.getDeclaredField("instance");
                    instField.setAccessible(true);
                    instField.set(null, mfp);
                } catch (Throwable ignored) {}
            }

            try {
                Class<?> commonPlatformClass = Class.forName("com.epherical.epherolib.CommonPlatform", true, sharedModClassLoader);
                Class<?> forgePlatformClass = Class.forName("com.epherical.epherolib.ForgePlatform", true, sharedModClassLoader);
                Object fp = forgePlatformClass.getConstructor().newInstance();
                Method createMethod = commonPlatformClass.getMethod("create", commonPlatformClass);
                createMethod.invoke(null, fp);
            } catch (Throwable ignored) {}

            LOGGER.info("[PatchNeoForge] Initialized FMLLoader and FMLEnvironment dist = DEDICATED_SERVER");
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Could not initialize FMLLoader.dist: {}", t.getMessage());
        }

        try {
            Class<?> loadingModListClass = Class.forName("net.neoforged.fml.loading.LoadingModList", true, sharedModClassLoader);
            Method ofMethod = loadingModListClass.getMethod("of", List.class, List.class, List.class, List.class, Map.class);
            Object lml = ofMethod.invoke(null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
            try {
                Field fileByIdField = loadingModListClass.getDeclaredField("fileById");
                fileByIdField.setAccessible(true);
                fileByIdField.set(lml, new ConcurrentHashMap<>());
            } catch (Throwable ignored) {}
            try {
                Field modFilesField = loadingModListClass.getDeclaredField("modFiles");
                modFilesField.setAccessible(true);
                modFilesField.set(lml, new java.util.concurrent.CopyOnWriteArrayList<>());
            } catch (Throwable ignored) {}
            try {
                Class<?> fmlLoaderClass = Class.forName("net.neoforged.fml.loading.FMLLoader", true, sharedModClassLoader);
                Field lmlField = fmlLoaderClass.getDeclaredField("loadingModList");
                lmlField.setAccessible(true);
                lmlField.set(null, lml);
            } catch (Throwable ignored) {}
            LOGGER.info("[PatchNeoForge] Initialized LoadingModList");
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Could not initialize LoadingModList: {}", t.getMessage());
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

        // Initialize internal NeoForgeMod container
        try {
            Class<?> neoForgeModClass = Class.forName("net.neoforged.neoforge.common.NeoForgeMod", true, sharedModClassLoader);
            NeoForgeEventBus neoBus = new NeoForgeEventBus("ModBus[neoforge]");
            NeoForgeModMetadata neoMeta = new NeoForgeModMetadata("neoforge", "NeoForge", "21.1.211", "NeoForge");
            File neoJar = new File("libraries/net/neoforged/neoforge/21.1.211/neoforge-21.1.211-universal.jar");
            NeoForgeModContainer neoContainer = new NeoForgeModContainer(neoMeta, neoJar, neoBus, sharedModClassLoader);
            Class<?> distClass = Class.forName("net.neoforged.api.distmarker.Dist", true, sharedModClassLoader);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object distDedicatedServer = Enum.valueOf((Class<Enum>) distClass, "DEDICATED_SERVER");
            Constructor<?> ctor = neoForgeModClass.getConstructor(
                Class.forName("net.neoforged.bus.api.IEventBus", true, sharedModClassLoader),
                distClass,
                Class.forName("net.neoforged.fml.ModContainer", true, sharedModClassLoader)
            );
            Object neoModInstance = ctor.newInstance(neoBus, distDedicatedServer, neoContainer);
            neoContainer.setModInstance(neoModInstance);
            LOADED_MODS.put("neoforge", neoContainer);
            INDEXED_MODS.put("neoforge", neoContainer);
            registerSyntheticModFile(neoJar, neoContainer.getModInfo(), "neoforge", sharedModClassLoader);
            LOGGER.info("[PatchNeoForge] Successfully initialized internal NeoForgeMod container");
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Could not initialize internal NeoForgeMod: {}", t.getMessage());
        }

        try {
            Class.forName("dev.shadowsoffire.gateways.GatewayObjects", true, sharedModClassLoader);
            LOGGER.info("[PatchNeoForge] Pre-initialized Gateways GatewayObjects cleanly");
        } catch (Throwable ignored) {}

        for (File jarFile : modJars) {
            try {
                loadModJar(jarFile, sharedModClassLoader);
            } catch (Throwable t) {
                LOGGER.error("[PatchNeoForge] Failed to load mod JAR: {}", jarFile.getName(), t);
            }
        }

        LOGGER.info("[PatchNeoForge] Successfully initialized {} NeoForge mod(s)", LOADED_MODS.size());

        // Update ModList.get() with all loaded mod containers
        try {
            net.neoforged.fml.ModList modList = net.neoforged.fml.ModList.get();
            if (modList != null) {
                List<net.neoforged.fml.ModContainer> containers = new ArrayList<>(LOADED_MODS.values());
                try {
                    Method setLoadedMods = net.neoforged.fml.ModList.class.getDeclaredMethod("setLoadedMods", List.class);
                    setLoadedMods.setAccessible(true);
                    setLoadedMods.invoke(modList, containers);
                } catch (Throwable t2) {
                    LOGGER.debug("[PatchNeoForge] Could not invoke setLoadedMods: {}", t2.getMessage());
                }

                try {
                    Field indexedModsField = net.neoforged.fml.ModList.class.getDeclaredField("indexedMods");
                    indexedModsField.setAccessible(true);
                    indexedModsField.set(modList, INDEXED_MODS);
                } catch (Throwable ignored) {}

                try {
                    Field sortedContainersField = net.neoforged.fml.ModList.class.getDeclaredField("sortedContainers");
                    sortedContainersField.setAccessible(true);
                    sortedContainersField.set(modList, containers);
                } catch (Throwable ignored) {}

                try {
                    Field modsField = net.neoforged.fml.ModList.class.getDeclaredField("mods");
                    modsField.setAccessible(true);
                    modsField.set(modList, containers);
                } catch (Throwable ignored) {}

                LOGGER.info("[PatchNeoForge] Updated ModList with {} loaded container(s)", LOADED_MODS.size());
            }
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Could not update ModList with loaded mods: {}", t.getMessage());
        }

        // Run authentic NeoForge startup lifecycle across all loaded mods
        runModLifecycle(sharedModClassLoader);

        // Run server starting lifecycle and command registration
        fireServerStartingEvents(sharedModClassLoader);

        return true;
    }

    private static void extractJarJarDependencies(File modJar, File cacheDir, List<File> outputList, List<File> modJars) {
        try (JarFile jar = new JarFile(modJar)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if ((name.startsWith("META-INF/jarjar/") || name.startsWith("META-INF/jars/")) && name.endsWith(".jar")) {
                    String simpleName = name.substring(name.lastIndexOf('/') + 1);
                    if (isShadowedByModJars(simpleName, modJars)) {
                        continue;
                    }
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
                        // Recursively extract nested jar-in-jar dependencies
                        extractJarJarDependencies(extracted, cacheDir, outputList, modJars);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[PatchNeoForge] No JarJar processed in {}: {}", modJar.getName(), t.getMessage());
        }
    }

    private static boolean isShadowedByModJars(String jarName, List<File> modJars) {
        String clean = jarName.toLowerCase();
        int dash = clean.indexOf('-');
        String prefix = dash > 0 ? clean.substring(0, dash) : clean;
        for (File mj : modJars) {
            String mjName = mj.getName().toLowerCase();
            if (mjName.startsWith(prefix + "-") || mjName.equals(clean)) {
                return true;
            }
        }
        return false;
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
                        LOGGER.warn("[PatchNeoForge] Could not instantiate KotlinModContainer for '{}', falling back: {}", modId, t.getMessage());
                        net.neoforged.fml.ModContainer existing = INDEXED_MODS.get(modId);
                        if (existing != null && existing.getEventBus() != null) {
                            modEventBus = existing.getEventBus();
                        } else {
                            modEventBus = new NeoForgeEventBus("ModBus[" + modId + "]");
                        }
                        container = new NeoForgeModContainer(meta, jarFile, modEventBus, classLoader);
                    }
                } else {
                    net.neoforged.fml.ModContainer existing = INDEXED_MODS.get(modId);
                    if (existing != null && existing.getEventBus() != null) {
                        modEventBus = existing.getEventBus();
                    } else {
                        modEventBus = new NeoForgeEventBus("ModBus[" + modId + "]");
                    }
                    container = new NeoForgeModContainer(meta, jarFile, modEventBus, classLoader);
                }

                // Register container immediately so early isLoaded() calls from other constructors succeed
                LOADED_MODS.put(modId, container);
                INDEXED_MODS.put(modId, container);
                ModLoadingContext.get().setActiveContainer(container);

                // Register synthetic ModFile & ModFileInfo for ModList.getModFileById()
                registerSyntheticModFile(jarFile, container.getModInfo(), modId, classLoader);

                if (mainModClass != null) {
                    try {
                        for (java.lang.annotation.Annotation ann : mainModClass.getAnnotations()) {
                            if ("net.neoforged.api.distmarker.OnlyIn".equals(ann.annotationType().getName())) {
                                Method valMethod = ann.annotationType().getMethod("value");
                                Object val = valMethod.invoke(ann);
                                if (val != null && "CLIENT".equals(val.toString())) {
                                    LOGGER.warn("[PatchNeoForge] Skipping client-only mod '{}' (@OnlyIn(Dist.CLIENT) on class)", modId);
                                    LOADED_MODS.remove(modId);
                                    INDEXED_MODS.remove(modId);
                                    ModLoadingContext.get().setActiveContainer(null);
                                    continue;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}

                    LOGGER.info("[PatchNeoForge] Instantiating mod '{}' ({}) via {}",
                            meta.getDisplayName(), meta.getVersion(), mainModClass.getName());
                    try {
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
                    } catch (Throwable e) {
                        Throwable root = e;
                        while (root.getCause() != null && root.getCause() != root) {
                            root = root.getCause();
                        }
                        String msg = root.getMessage() != null ? root.getMessage() : root.toString();
                        if (msg.contains("invalid dist DEDICATED_SERVER")
                                || msg.contains("net/minecraft/client")
                                || msg.contains("net.minecraft.client")
                                || msg.contains("blaze3d")
                                || root instanceof ClassNotFoundException && (msg.contains("client") || msg.contains("blaze3d"))
                                || root instanceof NoClassDefFoundError && (msg.contains("client") || msg.contains("blaze3d"))) {
                            LOGGER.warn("[PatchNeoForge] Skipping client-only mod '{}' ({})", modId, msg);
                            LOADED_MODS.remove(modId);
                            INDEXED_MODS.remove(modId);
                            ModLoadingContext.get().setActiveContainer(null);
                            continue;
                        }
                        throw e;
                    }
                } else {
                    LOGGER.warn("[PatchNeoForge] Mod '{}' defined in toml but no matching @Mod class found", modId);
                }

                // Process @EventBusSubscriber classes for this mod
                for (Class<?> clazz : jarClasses) {
                    EventBusSubscriber subscriber = clazz.getAnnotation(EventBusSubscriber.class);
                    if (subscriber != null) {
                        if (subscriber.modid().isEmpty() || subscriber.modid().equals(modId)) {
                            boolean isDedicatedServer = false;
                            for (net.neoforged.api.distmarker.Dist d : subscriber.value()) {
                                if (d == net.neoforged.api.distmarker.Dist.DEDICATED_SERVER) {
                                    isDedicatedServer = true;
                                    break;
                                }
                            }
                            if (!isDedicatedServer) {
                                LOGGER.debug("[PatchNeoForge] Skipping client-only @EventBusSubscriber: {}", clazz.getName());
                                continue;
                            }
                            IEventBus targetBus = (subscriber.bus() == EventBusSubscriber.Bus.MOD)
                                    ? modEventBus
                                    : NeoForge.EVENT_BUS;
                            try {
                                targetBus.register(clazz);
                                LOGGER.debug("[PatchNeoForge] Registered static @EventBusSubscriber: {}", clazz.getName());
                            } catch (Throwable t) {
                                LOGGER.debug("[PatchNeoForge] Could not register subscriber {}: {}", clazz.getName(), t.getMessage());
                            }
                        }
                    }
                }

                ModLoadingContext.get().setActiveContainer(null);
            }
        }
    }

    private static Object instantiateModClass(Class<?> clazz, IEventBus modEventBus, net.neoforged.fml.ModContainer container) throws Exception {
        Class<?> distClass = null;
        Object distDedicatedServer = null;
        try {
            distClass = Class.forName("net.neoforged.api.distmarker.Dist", true, clazz.getClassLoader());
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object distVal = Enum.valueOf((Class<Enum>) distClass, "DEDICATED_SERVER");
            distDedicatedServer = distVal;
        } catch (Throwable ignored) {}

        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            Object[] args = new Object[params.length];
            boolean match = true;

            for (int i = 0; i < params.length; i++) {
                Class<?> p = params[i];
                if (IEventBus.class.isAssignableFrom(p)) {
                    args[i] = modEventBus;
                } else if (p.isAssignableFrom(container.getClass())) {
                    args[i] = container;
                } else if (distClass != null && (p == distClass || (p.isEnum() && "Dist".equals(p.getSimpleName())))) {
                    args[i] = distDedicatedServer;
                } else {
                    match = false;
                    break;
                }
            }

            if (match) {
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            }
        }

        Constructor<?> noArg = clazz.getDeclaredConstructor();
        noArg.setAccessible(true);
        return noArg.newInstance();
    }

    public static void unfreezeRegistries(ClassLoader classLoader) {
        try {
            Class<?> builtInRegistriesClass = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, classLoader);
            Class<?> mappedRegistryClass = Class.forName("net.minecraft.core.MappedRegistry", true, classLoader);
            Field frozenField = mappedRegistryClass.getDeclaredField("frozen");
            frozenField.setAccessible(true);

            for (Field field : builtInRegistriesClass.getFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    try {
                        Object reg = field.get(null);
                        if (mappedRegistryClass.isInstance(reg)) {
                            frozenField.set(reg, false);
                        }
                    } catch (Throwable ignored) {}
                }
            }
            LOGGER.info("[PatchNeoForge] Unfroze BuiltInRegistries for mod registration");

            // Pre-create dynamic enum constants
            try {
                Class<?> rarityClass = Class.forName("net.minecraft.world.item.Rarity", true, classLoader);
                HeadlessNeoForgeServer.getOrCreateEnum(rarityClass, "AETHER_LOOT");
                Class<?> recipeBookTypeClass = Class.forName("net.minecraft.world.inventory.RecipeBookType", true, classLoader);
                HeadlessNeoForgeServer.getOrCreateEnum(recipeBookTypeClass, "AETHER_ALTAR");
                HeadlessNeoForgeServer.getOrCreateEnum(recipeBookTypeClass, "DEEP_AETHER_COMBINER");
                Class<?> mobCategoryClass = Class.forName("net.minecraft.world.entity.MobCategory", true, classLoader);
                HeadlessNeoForgeServer.getOrCreateEnum(mobCategoryClass, "AETHER_AETHER_SURFACE_MONSTER");
            } catch (Throwable t) {
                LOGGER.debug("[PatchNeoForge] Could not pre-create enum constants: {}", t.getMessage());
            }

            try {
                Class<?> moClass = Class.forName("net.minecraft.world.item.trading.MerchantOffer", true, classLoader);
                Field codecField = moClass.getDeclaredField("CODEC");
                codecField.setAccessible(true);
                setFieldStatic(moClass, "cODEC", codecField.get(null));
            } catch (Throwable ignored) {}

            // Pre-initialize SpongeMixin Service
            try {
                Class<?> mixinServiceClass = Class.forName("org.spongepowered.asm.service.MixinService", true, classLoader);
                Class<?> iMixinServiceClass = Class.forName("org.spongepowered.asm.service.IMixinService", true, classLoader);
                Class<?> iPropServiceClass = Class.forName("org.spongepowered.asm.service.IGlobalPropertyService", true, classLoader);

                Class<?> iLoggerClass = null;
                try {
                    iLoggerClass = Class.forName("org.spongepowered.asm.logging.ILogger", true, classLoader);
                } catch (Throwable ignored) {}

                final Object dummyLogger = iLoggerClass != null ? java.lang.reflect.Proxy.newProxyInstance(
                        classLoader,
                        new Class<?>[]{iLoggerClass},
                        (proxy, method, args) -> {
                            if (method.getReturnType() == String.class) return "HeadlessMixinLogger";
                            if (method.getReturnType() == boolean.class) return false;
                            return null;
                        }
                ) : null;

                Class<?> containerVirtualClass = null;
                Object rootContainer = null;
                try {
                    containerVirtualClass = Class.forName("org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual", true, classLoader);
                    rootContainer = containerVirtualClass.getConstructor(String.class).newInstance("root");
                } catch (Throwable ignored) {}
                final Object primaryContainer = rootContainer;

                Object dummyService = java.lang.reflect.Proxy.newProxyInstance(
                        classLoader,
                        new Class<?>[]{iMixinServiceClass},
                        (proxy, method, args) -> {
                            String name = method.getName();
                            if ("getName".equals(name)) return "HeadlessMixinService";
                            if ("isValid".equals(name)) return true;
                            if ("getInitialPhase".equals(name)) {
                                Class<?> phaseClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment$Phase", true, classLoader);
                                return phaseClass.getField("PREINIT").get(null);
                            }
                            if ("getReEntranceLock".equals(name)) {
                                Class<?> lockClass = Class.forName("org.spongepowered.asm.util.ReEntranceLock", true, classLoader);
                                return lockClass.getConstructor(int.class).newInstance(1);
                            }
                            if ("getPlatformAgents".equals(name) || "getMixinContainers".equals(name)) return Collections.emptyList();
                            if ("getSideName".equals(name)) return "SERVER";
                            if ("getLogger".equals(name)) return dummyLogger;
                            if ("getPrimaryContainer".equals(name)) return primaryContainer;
                            Class<?> ret = method.getReturnType();
                            if (ret == boolean.class) return false;
                            if (ret == int.class) return 0;
                            return null;
                        }
                );

                Object dummyPropService = java.lang.reflect.Proxy.newProxyInstance(
                        classLoader,
                        new Class<?>[]{iPropServiceClass},
                        (proxy, method, args) -> null
                );

                Method getInstance = mixinServiceClass.getDeclaredMethod("getInstance");
                getInstance.setAccessible(true);
                Object msInstance = getInstance.invoke(null);

                Field serviceField = mixinServiceClass.getDeclaredField("service");
                serviceField.setAccessible(true);
                serviceField.set(msInstance, dummyService);

                Field propField = mixinServiceClass.getDeclaredField("propertyService");
                propField.setAccessible(true);
                propField.set(msInstance, dummyPropService);

                try {
                    Class<?> mpmClass = Class.forName("org.spongepowered.asm.launch.platform.MixinPlatformManager", true, classLoader);
                    Field loggerField = mpmClass.getDeclaredField("logger");
                    loggerField.setAccessible(true);
                    loggerField.set(null, dummyLogger);
                } catch (Throwable ignored) {}

                LOGGER.info("[PatchNeoForge] Pre-initialized MixinService with HeadlessMixinService proxy");

                try {
                    Class<?> mixinBootstrapClass = Class.forName("org.spongepowered.asm.launch.MixinBootstrap", true, classLoader);
                    mixinBootstrapClass.getMethod("init").invoke(null);
                    mixinBootstrapClass.getMethod("start").invoke(null);
                } catch (Throwable ignored) {}
            } catch (Throwable t) {
                LOGGER.debug("[PatchNeoForge] Could not pre-initialize MixinService: {}", t.getMessage());
            }
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
        DeferredWorkQueue constructQueue = new DeferredWorkQueue("construct");
        for (net.neoforged.fml.ModContainer container : LOADED_MODS.values()) {
            ModLoadingContext.get().setActiveContainer(container);
            container.getEventBus().post(new FMLConstructModEvent(container, constructQueue));
        }
        try {
            constructQueue.runTasks();
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Some deferred construct tasks failed: {}", t.getMessage());
        }

        // 1.5. NewRegistryEvent
        try {
            Constructor<NewRegistryEvent> newRegCtor = NewRegistryEvent.class.getDeclaredConstructor();
            newRegCtor.setAccessible(true);
            NewRegistryEvent newRegEvent = newRegCtor.newInstance();

            for (net.neoforged.fml.ModContainer container : LOADED_MODS.values()) {
                ModLoadingContext.get().setActiveContainer(container);
                container.getEventBus().post(newRegEvent);
            }

            Method fillMethod = NewRegistryEvent.class.getDeclaredMethod("fill");
            fillMethod.setAccessible(true);
            fillMethod.invoke(newRegEvent);
            LOGGER.info("[PatchNeoForge] Successfully processed NewRegistryEvent and filled root registry");
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Could not fire NewRegistryEvent: {}", t.getMessage());
        }

        // 2. RegisterEvent ordered by GameData.getRegistrationOrder()
        try {
            Class<?> builtInRegistriesClass = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, classLoader);
            Object rootRegistry = builtInRegistriesClass.getField("REGISTRY").get(null);
            if (rootRegistry instanceof net.minecraft.core.Registry<?> rootReg) {
                Constructor<RegisterEvent> regEventCtor = RegisterEvent.class.getDeclaredConstructor(
                        net.minecraft.resources.ResourceKey.class,
                        net.minecraft.core.Registry.class
                );
                regEventCtor.setAccessible(true);

                Set<net.minecraft.resources.ResourceLocation> orderedLocations = new LinkedHashSet<>();
                try {
                    orderedLocations.addAll(GameData.getRegistrationOrder());
                } catch (Throwable t) {
                    LOGGER.debug("[PatchNeoForge] Could not get GameData registration order: {}", t.getMessage());
                }

                // Add any remaining registries from rootReg not in GameData order
                for (Map.Entry<? extends net.minecraft.resources.ResourceKey<?>, ?> entry : rootReg.entrySet()) {
                    orderedLocations.add(entry.getKey().location());
                }

                for (net.minecraft.resources.ResourceLocation loc : orderedLocations) {
                    net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>> regKey =
                            net.minecraft.resources.ResourceKey.createRegistryKey(loc);
                    Object subRegistryObj = rootReg.get(loc);
                    if (subRegistryObj instanceof net.minecraft.core.Registry<?> subRegistry) {
                        try {
                            RegisterEvent regEvent = regEventCtor.newInstance(regKey, subRegistry);

                            for (net.neoforged.fml.ModContainer container : LOADED_MODS.values()) {
                                try {
                                    ModLoadingContext.get().setActiveContainer(container);
                                    container.getEventBus().post(regEvent);
                                } catch (Throwable t) {
                                    LOGGER.debug("[PatchNeoForge] RegisterEvent error for {} in {}: {}", loc, container.getModId(), t.getMessage());
                                }
                            }
                        } catch (Throwable t) {
                            LOGGER.warn("[PatchNeoForge] Could not instantiate RegisterEvent for {}: {}", loc, t.getMessage());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Could not enumerate BuiltInRegistries for RegisterEvent: {}", t.getMessage());
        }

        // Pre-load COMMON and SERVER configs so setup event listeners can safely query configs
        try {
            Class<?> configTrackerClass = Class.forName("net.neoforged.fml.config.ConfigTracker", true, classLoader);
            Object tracker = configTrackerClass.getField("INSTANCE").get(null);
            Class<?> modConfigTypeClass = Class.forName("net.neoforged.fml.config.ModConfig$Type", true, classLoader);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object commonType = Enum.valueOf((Class<Enum>) modConfigTypeClass, "COMMON");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object serverType = Enum.valueOf((Class<Enum>) modConfigTypeClass, "SERVER");
            Path serverConfigPath = Path.of("server/config").toAbsolutePath();
            if (!Files.exists(serverConfigPath)) {
                serverConfigPath = Path.of("config").toAbsolutePath();
            }
            configTrackerClass.getMethod("loadConfigs", modConfigTypeClass, Path.class).invoke(tracker, commonType, serverConfigPath);
            configTrackerClass.getMethod("loadConfigs", modConfigTypeClass, Path.class).invoke(tracker, serverType, serverConfigPath);
            LOGGER.info("[PatchNeoForge] Loaded ModConfig Type.COMMON and Type.SERVER configs");
        } catch (Throwable t) {
            LOGGER.debug("[PatchNeoForge] Could not pre-load configs: {}", t.getMessage());
        }

        // 3. FMLCommonSetupEvent
        DeferredWorkQueue commonQueue = new DeferredWorkQueue("common_setup");
        for (net.neoforged.fml.ModContainer container : LOADED_MODS.values()) {
            try {
                ModLoadingContext.get().setActiveContainer(container);
                container.getEventBus().post(new FMLCommonSetupEvent(container, commonQueue));
            } catch (Throwable t) {
                LOGGER.warn("[PatchNeoForge] Error posting FMLCommonSetupEvent to mod '{}': {}", container.getModId(), t.getMessage());
            }
        }
        try {
            commonQueue.runTasks();
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Some deferred common setup tasks failed: {}", t.getMessage());
        }

        // 4. FMLDedicatedServerSetupEvent
        DeferredWorkQueue serverQueue = new DeferredWorkQueue("server_setup");
        for (net.neoforged.fml.ModContainer container : LOADED_MODS.values()) {
            try {
                ModLoadingContext.get().setActiveContainer(container);
                container.getEventBus().post(new FMLDedicatedServerSetupEvent(container, serverQueue));
            } catch (Throwable t) {
                LOGGER.warn("[PatchNeoForge] Error posting FMLDedicatedServerSetupEvent to mod '{}': {}", container.getModId(), t.getMessage());
            }
        }
        try {
            serverQueue.runTasks();
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Some deferred server setup tasks failed: {}", t.getMessage());
        }

        // 5. FMLLoadCompleteEvent
        DeferredWorkQueue loadCompleteQueue = new DeferredWorkQueue("load_complete");
        for (net.neoforged.fml.ModContainer container : LOADED_MODS.values()) {
            try {
                ModLoadingContext.get().setActiveContainer(container);
                container.getEventBus().post(new FMLLoadCompleteEvent(container, loadCompleteQueue));
            } catch (Throwable t) {
                LOGGER.warn("[PatchNeoForge] Error posting FMLLoadCompleteEvent to mod '{}': {}", container.getModId(), t.getMessage());
            }
        }
        try {
            loadCompleteQueue.runTasks();
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Some deferred load complete tasks failed: {}", t.getMessage());
        }

        LOGGER.info("[PatchNeoForge] Mod lifecycle initialization finished successfully.");
    }

    private static net.minecraft.server.MinecraftServer createHeadlessServer(ClassLoader classLoader) {
        try {
            Class<?> baseServerClass = Class.forName("net.minecraft.server.MinecraftServer", true, classLoader);
            Class<?> serverClass;
            try {
                serverClass = Class.forName("net.minecraft.server.dedicated.DedicatedServer", true, classLoader);
            } catch (ClassNotFoundException e) {
                serverClass = baseServerClass;
            }

            Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);

            net.minecraft.server.MinecraftServer server =
                    (net.minecraft.server.MinecraftServer) unsafe.allocateInstance(serverClass);

            for (Class<?> c = serverClass; c != null; c = c.getSuperclass()) {
                try {
                    Field pendingField = c.getDeclaredField("pendingRunnables");
                    pendingField.setAccessible(true);
                    pendingField.set(server, new ConcurrentLinkedQueue<>());
                } catch (NoSuchFieldException ignored) {}

                try {
                    Field nameField = c.getDeclaredField("name");
                    nameField.setAccessible(true);
                    nameField.set(server, "Server");
                } catch (NoSuchFieldException ignored) {}

                try {
                    Field threadField = c.getDeclaredField("serverThread");
                    threadField.setAccessible(true);
                    threadField.set(server, Thread.currentThread());
                } catch (NoSuchFieldException ignored) {}
            }

            Class<?> builtInRegistriesClass = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, classLoader);
            Object rootReg = builtInRegistriesClass.getField("REGISTRY").get(null);
            Class<?> mappedRegistryClass = Class.forName("net.minecraft.core.MappedRegistry", true, classLoader);
            try {
                Field intrusiveField = mappedRegistryClass.getDeclaredField("unregisteredIntrusiveHolders");
                intrusiveField.setAccessible(true);
                if (rootReg instanceof Iterable<?> iterable) {
                    for (Object subRegistry : iterable) {
                        if (mappedRegistryClass.isInstance(subRegistry)) {
                            Object map = intrusiveField.get(subRegistry);
                            if (map instanceof Map<?, ?> m) {
                                m.clear();
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            Class<?> registryAccessClass = Class.forName("net.minecraft.core.RegistryAccess", true, classLoader);
            Object frozenAccess = registryAccessClass.getMethod("fromRegistryOfRegistries", net.minecraft.core.Registry.class).invoke(null, rootReg);

            Class<?> registryLayerClass = Class.forName("net.minecraft.server.RegistryLayer", true, classLoader);
            Object[] layers = (Object[]) registryLayerClass.getMethod("values").invoke(null);
            List<Object> layerList = Arrays.asList(layers);

            Class<?> layeredAccessClass = Class.forName("net.minecraft.core.LayeredRegistryAccess", true, classLoader);
            Object layeredAccess = layeredAccessClass.getConstructor(List.class).newInstance(layerList);
            Method replaceFrom = layeredAccessClass.getMethod("replaceFrom", Object.class, List.class);
            layeredAccess = replaceFrom.invoke(layeredAccess, layers[0], List.of(frozenAccess));

            Object proxyAccess = frozenAccess;
            final Object[] sharedWorldRefs = new Object[5];
            try {
                Class<?> registryAccessFrozenClass = Class.forName("net.minecraft.core.RegistryAccess$Frozen", true, classLoader);
                Class<?> lifecycleClass = Class.forName("com.mojang.serialization.Lifecycle", true, classLoader);
                Object stableLifecycle = lifecycleClass.getMethod("stable").invoke(null);

                Map<Object, Object> dynamicRegistries = new ConcurrentHashMap<>();

                proxyAccess = java.lang.reflect.Proxy.newProxyInstance(classLoader,
                        new Class<?>[]{registryAccessFrozenClass},
                        (proxy, method, args) -> {
                            String methodName = method.getName();
                            if (("registry".equals(methodName) || "registryOrThrow".equals(methodName)
                                    || "lookup".equals(methodName) || "lookupOrThrow".equals(methodName)) && args != null && args.length == 1) {
                                Object key = args[0];
                                try {
                                    Object result = method.invoke(frozenAccess, args);
                                    if (("registry".equals(methodName) || "lookup".equals(methodName)) && result instanceof Optional<?> opt && opt.isPresent()) {
                                        return result;
                                    }
                                    if (("registryOrThrow".equals(methodName) || "lookupOrThrow".equals(methodName)) && result != null) {
                                        return result;
                                    }
                                } catch (Throwable ignored) {}

                                // Provide fallback dynamic registry
                                Object dynReg = dynamicRegistries.computeIfAbsent(key, k -> {
                                    try {
                                        Object reg = mappedRegistryClass.getConstructor(
                                                Class.forName("net.minecraft.resources.ResourceKey", true, classLoader),
                                                lifecycleClass
                                        ).newInstance(k, stableLifecycle);
                                        if (k.toString().contains("chat_type")) {
                                            try {
                                                Class<?> chatTypeClass = Class.forName("net.minecraft.network.chat.ChatType", true, classLoader);
                                                Object defaultDec = chatTypeClass.getField("DEFAULT_CHAT_DECORATION").get(null);
                                                Constructor<?> chatTypeCtor = chatTypeClass.getConstructor(
                                                        Class.forName("net.minecraft.network.chat.ChatTypeDecoration", true, classLoader),
                                                        Class.forName("net.minecraft.network.chat.ChatTypeDecoration", true, classLoader)
                                                );
                                                Object chatTypeObj = chatTypeCtor.newInstance(defaultDec, defaultDec);
                                                Object chatKey = chatTypeClass.getField("CHAT").get(null);
                                                Class<?> regInfoClass = Class.forName("net.minecraft.core.RegistrationInfo", true, classLoader);
                                                Object builtInInfo = regInfoClass.getField("BUILT_IN").get(null);
                                                Method regMethod = mappedRegistryClass.getMethod("register",
                                                        Class.forName("net.minecraft.resources.ResourceKey", true, classLoader),
                                                        Object.class,
                                                        regInfoClass
                                                );
                                                regMethod.invoke(reg, chatKey, chatTypeObj, builtInInfo);
                                            } catch (Throwable ignored) {}
                                        }

                                        // Configure auto-creating byKey and byLocation on reg
                                        String regKeyName = k != null ? k.toString() : "";
                                        if (regKeyName.contains("damage_type") || regKeyName.contains("chat_type") || regKeyName.contains("noise")
                                                || regKeyName.contains("biome") || regKeyName.contains("processor_list")) {
                                            try {
                                                Field byKeyField = mappedRegistryClass.getDeclaredField("byKey");
                                                byKeyField.setAccessible(true);
                                                Field byLocField = mappedRegistryClass.getDeclaredField("byLocation");
                                                byLocField.setAccessible(true);

                                                @SuppressWarnings("unchecked")
                                                Map<Object, Object> existingByKey = (Map<Object, Object>) byKeyField.get(reg);
                                                Map<Object, Object> autoCreatingByKey = new ConcurrentHashMap<>(existingByKey != null ? existingByKey : Collections.emptyMap()) {
                                                    @Override
                                                    public boolean containsKey(Object key) {
                                                        return true;
                                                    }

                                                    @Override
                                                    public Object get(Object key) {
                                                        Object val = super.get(key);
                                                        if (val != null) return val;
                                                        try {
                                                            Method createMethod = mappedRegistryClass.getDeclaredMethod("getOrCreateHolderOrThrow",
                                                                    Class.forName("net.minecraft.resources.ResourceKey", true, classLoader));
                                                            createMethod.setAccessible(true);
                                                            Object holder = createMethod.invoke(reg, key);

                                                            try {
                                                                Method isBoundMethod = holder.getClass().getMethod("isBound");
                                                                if (!((Boolean) isBoundMethod.invoke(holder))) {
                                                                    Method bindValueMethod = holder.getClass().getDeclaredMethod("bindValue", Object.class);
                                                                    bindValueMethod.setAccessible(true);
                                                                    String kStr = key.toString();
                                                                    if (kStr.contains("damage_type")) {
                                                                        Class<?> dtClass = Class.forName("net.minecraft.world.damagesource.DamageType", true, classLoader);
                                                                        Constructor<?> dtCtor = dtClass.getConstructor(String.class, float.class);
                                                                        bindValueMethod.invoke(holder, dtCtor.newInstance("dummy", 0.0f));
                                                                    } else if (kStr.contains("chat_type")) {
                                                                        Class<?> chatTypeClass = Class.forName("net.minecraft.network.chat.ChatType", true, classLoader);
                                                                        Object defaultDec = chatTypeClass.getField("DEFAULT_CHAT_DECORATION").get(null);
                                                                        Constructor<?> chatTypeCtor = chatTypeClass.getConstructor(
                                                                                Class.forName("net.minecraft.network.chat.ChatTypeDecoration", true, classLoader),
                                                                                Class.forName("net.minecraft.network.chat.ChatTypeDecoration", true, classLoader)
                                                                        );
                                                                        bindValueMethod.invoke(holder, chatTypeCtor.newInstance(defaultDec, defaultDec));
                                                                    } else if (kStr.contains("noise")) {
                                                                        Class<?> npClass = Class.forName("net.minecraft.world.level.levelgen.synth.NormalNoise$NoiseParameters", true, classLoader);
                                                                        Constructor<?> npCtor = npClass.getConstructor(int.class, double.class, double[].class);
                                                                        bindValueMethod.invoke(holder, npCtor.newInstance(-7, 1.0, new double[]{1.0}));
                                                                    } else if (kStr.contains("biome")) {
                                                                        Class<?> biomeClass = Class.forName("net.minecraft.world.level.biome.Biome", true, classLoader);
                                                                        bindValueMethod.invoke(holder, unsafe.allocateInstance(biomeClass));
                                                                    } else if (kStr.contains("enchantment")) {
                                                                        Class<?> enchClass = Class.forName("net.minecraft.world.item.enchantment.Enchantment", true, classLoader);
                                                                        bindValueMethod.invoke(holder, unsafe.allocateInstance(enchClass));
                                                                    } else if (kStr.contains("processor_list")) {
                                                                        Class<?> procListClass = Class.forName("net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList", true, classLoader);
                                                                        Constructor<?> procCtor = procListClass.getConstructor(List.class);
                                                                        bindValueMethod.invoke(holder, procCtor.newInstance(Collections.emptyList()));
                                                                    }
                                                                }
                                                            } catch (Throwable ignored) {}

                                                            super.put(key, holder);
                                                            return holder;
                                                        } catch (Throwable t) {
                                                            return null;
                                                        }
                                                    }
                                                };
                                                byKeyField.set(reg, autoCreatingByKey);

                                                @SuppressWarnings("unchecked")
                                                Map<Object, Object> existingByLoc = (Map<Object, Object>) byLocField.get(reg);
                                                Map<Object, Object> autoCreatingByLocation = new ConcurrentHashMap<>(existingByLoc != null ? existingByLoc : Collections.emptyMap()) {
                                                    @Override
                                                    public boolean containsKey(Object key) {
                                                        return true;
                                                    }

                                                    @Override
                                                    public Object get(Object key) {
                                                        Object val = super.get(key);
                                                        if (val != null) return val;
                                                        try {
                                                            Class<?> resKeyClass = Class.forName("net.minecraft.resources.ResourceKey", true, classLoader);
                                                            Method createKey = resKeyClass.getMethod("create", resKeyClass, Class.forName("net.minecraft.resources.ResourceLocation", true, classLoader));
                                                            Object regKey = reg.getClass().getMethod("key").invoke(reg);
                                                            Object rKey = createKey.invoke(null, regKey, key);
                                                            Object holder = autoCreatingByKey.get(rKey);
                                                            if (holder != null) {
                                                                super.put(key, holder);
                                                            }
                                                            return holder;
                                                        } catch (Throwable t) {
                                                            return null;
                                                        }
                                                    }
                                                };
                                                byLocField.set(reg, autoCreatingByLocation);
                                            } catch (Throwable t) {
                                                LOGGER.debug("[PatchNeoForge] Could not install auto-creating map on dynReg: {}", t.getMessage());
                                            }
                                        }
                                        return reg;
                                    } catch (Throwable e) {
                                        return null;
                                    }
                                });
                                if (dynReg != null) {
                                    Object lookupObj = null;
                                    try {
                                        Object rawLookup = mappedRegistryClass.getMethod("asLookup").invoke(dynReg);
                                        Class<?> regLookupClass = Class.forName("net.minecraft.core.HolderLookup$RegistryLookup", true, classLoader);
                                        lookupObj = java.lang.reflect.Proxy.newProxyInstance(classLoader, new Class<?>[]{regLookupClass}, (lookupProxy, m, mArgs) -> {
                                            if ("get".equals(m.getName()) && mArgs != null && mArgs.length == 1) {
                                                Object res = m.invoke(rawLookup, mArgs);
                                                if (res instanceof Optional<?> opt && opt.isPresent()) {
                                                    return res;
                                                }
                                                if (mArgs[0] != null && mArgs[0].getClass().getName().contains("ResourceKey")) {
                                                    Method createMethod = mappedRegistryClass.getDeclaredMethod("getOrCreateHolderOrThrow",
                                                            Class.forName("net.minecraft.resources.ResourceKey", true, classLoader));
                                                    createMethod.setAccessible(true);
                                                    Object holder = createMethod.invoke(dynReg, mArgs[0]);
                                                    return Optional.ofNullable(holder);
                                                }
                                            }
                                            return m.invoke(rawLookup, mArgs);
                                        });
                                    } catch (Throwable t) {
                                        lookupObj = dynReg;
                                    }

                                    if ("lookup".equals(methodName)) {
                                        return Optional.of(lookupObj);
                                    }
                                    if ("lookupOrThrow".equals(methodName)) {
                                        return lookupObj;
                                    }
                                    if ("registry".equals(methodName)) {
                                        return Optional.of(dynReg);
                                    }
                                    return dynReg;
                                }
                            }
                            return method.invoke(frozenAccess, args);
                        });

                Field compositeField = layeredAccessClass.getDeclaredField("composite");
                compositeField.setAccessible(true);
                compositeField.set(layeredAccess, proxyAccess);
            } catch (Throwable t) {
                LOGGER.warn("[PatchNeoForge] Could not configure dynamic RegistryAccess proxy: {}", t.getMessage());
            }

            Field registriesField = baseServerClass.getDeclaredField("registries");
            registriesField.setAccessible(true);
            registriesField.set(server, layeredAccess);

            try {
                Class<?> levelStorageAccessClass = Class.forName("net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess", true, classLoader);
                Object storageAccess = unsafe.allocateInstance(levelStorageAccessClass);
                Class<?> levelDirectoryClass = Class.forName("net.minecraft.world.level.storage.LevelStorageSource$LevelDirectory", true, classLoader);
                Constructor<?> dirCtor = levelDirectoryClass.getConstructor(Path.class);
                Path worldDir = Path.of("world").toAbsolutePath();
                Files.createDirectories(worldDir);
                Object levelDirectory = dirCtor.newInstance(worldDir);

                Field dirField = levelStorageAccessClass.getDeclaredField("levelDirectory");
                dirField.setAccessible(true);
                dirField.set(storageAccess, levelDirectory);

                Field resourcesField = levelStorageAccessClass.getDeclaredField("resources");
                resourcesField.setAccessible(true);
                resourcesField.set(storageAccess, new ConcurrentHashMap<>());

                Field levelIdField = levelStorageAccessClass.getDeclaredField("levelId");
                levelIdField.setAccessible(true);
                levelIdField.set(storageAccess, "world");

                Field storageField = baseServerClass.getDeclaredField("storageSource");
                storageField.setAccessible(true);
                storageField.set(server, storageAccess);

                try {
                    Class<?> dataFixersClass = Class.forName("net.minecraft.util.datafix.DataFixers", true, classLoader);
                    Object dataFixer = dataFixersClass.getMethod("getDataFixer").invoke(null);
                    setField(server, "fixerUpper", dataFixer);

                    Class<?> structManagerClass = Class.forName("net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager", true, classLoader);
                    Object structManager = unsafe.allocateInstance(structManagerClass);
                    setField(structManager, "structureRepository", new ConcurrentHashMap<>());
                    setField(structManager, "sources", Collections.emptyList());
                    setField(structManager, "fixerUpper", dataFixer);
                    setField(server, "structureTemplateManager", structManager);
                } catch (Throwable t) {
                    LOGGER.warn("[PatchNeoForge] Could not initialize fixerUpper/structureTemplateManager: {}", t.getMessage());
                }

                try {
                    Class<?> serverScoreboardClass = Class.forName("net.minecraft.server.ServerScoreboard", true, classLoader);
                    Constructor<?> sbCtor = serverScoreboardClass.getConstructor(baseServerClass);
                    Object serverScoreboard = sbCtor.newInstance(server);
                    setField(server, "scoreboard", serverScoreboard);
                } catch (Throwable t) {
                    LOGGER.warn("[PatchNeoForge] Could not initialize scoreboard: {}", t.getMessage());
                }
            } catch (Throwable t) {
                LOGGER.warn("[PatchNeoForge] Could not initialize storageSource on server: {}", t.getMessage());
            }

            try {
                Class<?> serverLevelClass = Class.forName("net.minecraft.server.level.ServerLevel", true, classLoader);
                Object headlessLevel = unsafe.allocateInstance(serverLevelClass);

                Class<?> levelClass = Class.forName("net.minecraft.world.level.Level", true, classLoader);
                Object overworldKey = levelClass.getField("OVERWORLD").get(null);

                Field dimKeyField = null;
                for (Class<?> c = serverLevelClass; c != null; c = c.getSuperclass()) {
                    try {
                        dimKeyField = c.getDeclaredField("dimension");
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
                if (dimKeyField != null) {
                    dimKeyField.setAccessible(true);
                    dimKeyField.set(headlessLevel, overworldKey);
                }

                Field serverField = null;
                for (Class<?> c = serverLevelClass; c != null; c = c.getSuperclass()) {
                    try {
                        serverField = c.getDeclaredField("server");
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
                if (serverField != null) {
                    serverField.setAccessible(true);
                    serverField.set(headlessLevel, server);
                }

                setField(headlessLevel, "registryAccess", proxyAccess);

                try {
                    Class<?> damageSourcesClass = Class.forName("net.minecraft.world.damagesource.DamageSources", true, classLoader);
                    Constructor<?> dsCtor = damageSourcesClass.getConstructor(Class.forName("net.minecraft.core.RegistryAccess", true, classLoader));
                    Object ds = dsCtor.newInstance(proxyAccess);
                    setField(headlessLevel, "damageSources", ds);
                } catch (Throwable ignored) {}

                try {
                    Class<?> dimTypeClass = Class.forName("net.minecraft.world.level.dimension.DimensionType", true, classLoader);
                    Class<?> monsterSettingsClass = Class.forName("net.minecraft.world.level.dimension.DimensionType$MonsterSettings", true, classLoader);
                    Class<?> constantIntClass = Class.forName("net.minecraft.util.valueproviders.ConstantInt", true, classLoader);
                    Object zeroInt = constantIntClass.getMethod("of", int.class).invoke(null, 0);

                    Class<?> intProviderClass = Class.forName("net.minecraft.util.valueproviders.IntProvider", true, classLoader);
                    Constructor<?> msCtor = monsterSettingsClass.getConstructor(boolean.class, boolean.class, intProviderClass, int.class);
                    Object monsterSettings = msCtor.newInstance(false, true, zeroInt, 0);

                    Class<?> blockTagsClass = Class.forName("net.minecraft.tags.BlockTags", true, classLoader);
                    Object infiniburn = blockTagsClass.getField("INFINIBURN_OVERWORLD").get(null);

                    Class<?> resLocClass = Class.forName("net.minecraft.resources.ResourceLocation", true, classLoader);
                    Method parseLoc = resLocClass.getMethod("parse", String.class);
                    Object effectsLoc = parseLoc.invoke(null, "minecraft:overworld");

                    Class<?> tagKeyClass = Class.forName("net.minecraft.tags.TagKey", true, classLoader);
                    Constructor<?> dimTypeCtor = dimTypeClass.getConstructor(
                        OptionalLong.class, boolean.class, boolean.class, boolean.class, boolean.class,
                        double.class, boolean.class, boolean.class, int.class, int.class, int.class,
                        tagKeyClass, resLocClass, float.class, monsterSettingsClass
                    );

                    Object dimType = dimTypeCtor.newInstance(
                        OptionalLong.empty(),
                        true,
                        false,
                        false,
                        true,
                        1.0,
                        true,
                        false,
                        -64,
                        384,
                        384,
                        infiniburn,
                        effectsLoc,
                        0.0f,
                        monsterSettings
                    );

                    Method directMethod = Class.forName("net.minecraft.core.Holder", true, classLoader).getMethod("direct", Object.class);
                    Object dimTypeHolder = directMethod.invoke(null, dimType);

                    setField(headlessLevel, "dimensionTypeRegistration", dimTypeHolder);
                    setField(headlessLevel, "dimensionType", dimType);
                } catch (Throwable t) {
                    LOGGER.warn("[PatchNeoForge] Could not initialize dimensionType on headless level: {}", t.getMessage());
                }

                try {
                    Class<?> inactiveProfilerClass = Class.forName("net.minecraft.util.profiling.InactiveProfiler", true, classLoader);
                    Field instanceField = inactiveProfilerClass.getDeclaredField("INSTANCE");
                    instanceField.setAccessible(true);
                    Object inactiveProfiler = instanceField.get(null);
                    setField(server, "profiler", inactiveProfiler);
                    setField(headlessLevel, "profiler", (java.util.function.Supplier<?>) () -> inactiveProfiler);
                } catch (Throwable t) {
                    LOGGER.warn("[PatchNeoForge] Could not initialize profiler: {}", t.getMessage());
                }

                try {
                    Class<?> primaryLevelDataClass = Class.forName("net.minecraft.world.level.storage.PrimaryLevelData", true, classLoader);
                    Object levelData = unsafe.allocateInstance(primaryLevelDataClass);
                    Class<?> gameRulesClass = Class.forName("net.minecraft.world.level.GameRules", true, classLoader);
                    Object gameRules = gameRulesClass.getConstructor().newInstance();
                    setField(levelData, "gameRules", gameRules);

                    Class<?> difficultyClass = Class.forName("net.minecraft.world.Difficulty", true, classLoader);
                    Object difficultyNormal = difficultyClass.getField("NORMAL").get(null);
                    setField(levelData, "difficulty", difficultyNormal);

                    Class<?> gameTypeClass = Class.forName("net.minecraft.world.level.GameType", true, classLoader);
                    Object gameTypeSurvival = gameTypeClass.getField("SURVIVAL").get(null);

                    Class<?> worldDataConfigClass = Class.forName("net.minecraft.world.level.WorldDataConfiguration", true, classLoader);
                    Object defaultDataConfig = worldDataConfigClass.getField("DEFAULT").get(null);

                    Class<?> levelSettingsClass = Class.forName("net.minecraft.world.level.LevelSettings", true, classLoader);
                    Constructor<?> settingsCtor = levelSettingsClass.getConstructor(
                            String.class, gameTypeClass, boolean.class, difficultyClass, boolean.class, gameRulesClass, worldDataConfigClass
                    );
                    Object levelSettings = settingsCtor.newInstance("world", gameTypeSurvival, false, difficultyNormal, true, gameRules, defaultDataConfig);
                    setField(levelData, "settings", levelSettings);

                    Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos", true, classLoader);
                    Object zeroPos = blockPosClass.getField("ZERO").get(null);
                    setField(levelData, "spawnPos", zeroPos);

                    setField(headlessLevel, "serverLevelData", levelData);
                    setField(headlessLevel, "levelData", levelData);

                    sharedWorldRefs[0] = levelData;
                    sharedWorldRefs[1] = levelSettings;
                    sharedWorldRefs[2] = gameRules;
                    sharedWorldRefs[3] = difficultyNormal;
                    sharedWorldRefs[4] = gameTypeSurvival;
                } catch (Throwable t) {
                    LOGGER.warn("[PatchNeoForge] Could not initialize serverLevelData on headless level: {}", t.getMessage());
                }

                // Initialize chunkSource and dataStorage on headless level
                try {
                    Class<?> serverChunkCacheClass = Class.forName("net.minecraft.server.level.ServerChunkCache", true, classLoader);
                    Class<?> dimensionDataStorageClass = Class.forName("net.minecraft.world.level.storage.DimensionDataStorage", true, classLoader);

                    Object chunkCache = unsafe.allocateInstance(serverChunkCacheClass);
                    Object dataStorage = unsafe.allocateInstance(dimensionDataStorageClass);

                    try {
                        Field cacheMap = dimensionDataStorageClass.getDeclaredField("cache");
                        cacheMap.setAccessible(true);
                        cacheMap.set(dataStorage, Collections.synchronizedMap(new HashMap<>()));
                    } catch (Throwable ignored) {}

                    File dataDir = new File("server/world/data");
                    dataDir.mkdirs();
                    setField(dataStorage, "dataFolder", dataDir);

                    // Initialize worldBorder on headlessLevel
                    try {
                        Class<?> worldBorderClass = Class.forName("net.minecraft.world.level.border.WorldBorder", true, classLoader);
                        Object wb = worldBorderClass.getConstructor().newInstance();
                        setField(headlessLevel, "worldBorder", wb);
                    } catch (Throwable ignored) {}

                    // Initialize ChunkMap on ServerChunkCache
                    try {
                        Class<?> chunkMapClass = Class.forName("net.minecraft.server.level.ChunkMap", true, classLoader);
                        Class<?> worldGenContextClass = Class.forName("net.minecraft.world.level.chunk.status.WorldGenContext", true, classLoader);
                        Class<?> flatLevelSourceClass = Class.forName("net.minecraft.world.level.levelgen.FlatLevelSource", true, classLoader);

                        Object chunkMap = unsafe.allocateInstance(chunkMapClass);
                        Object chunkGenerator = unsafe.allocateInstance(flatLevelSourceClass);
                        Object worldGenContext = unsafe.allocateInstance(worldGenContextClass);

                        try {
                            Class<?> biomeClass = Class.forName("net.minecraft.world.level.biome.Biome", true, classLoader);
                            Object dummyBiome = unsafe.allocateInstance(biomeClass);
                            Object emptyGenSettings = null;
                            try {
                                Class<?> biomeGenSettingsClass = Class.forName("net.minecraft.world.level.biome.BiomeGenerationSettings", true, classLoader);
                                Field emptyGenField = biomeGenSettingsClass.getDeclaredField("EMPTY");
                                emptyGenField.setAccessible(true);
                                emptyGenSettings = emptyGenField.get(null);
                                setField(dummyBiome, "generationSettings", emptyGenSettings);
                            } catch (Throwable ignored) {}

                            Method directMethod = Class.forName("net.minecraft.core.Holder", true, classLoader).getMethod("direct", Object.class);
                            Object biomeHolder = directMethod.invoke(null, dummyBiome);

                            Class<?> fixedBiomeSourceClass = Class.forName("net.minecraft.world.level.biome.FixedBiomeSource", true, classLoader);
                            Object biomeSource;
                            try {
                                Constructor<?> fixedBiomeSourceCtor = fixedBiomeSourceClass.getConstructor(Class.forName("net.minecraft.core.Holder", true, classLoader));
                                biomeSource = fixedBiomeSourceCtor.newInstance(biomeHolder);
                            } catch (Throwable t) {
                                biomeSource = unsafe.allocateInstance(fixedBiomeSourceClass);
                            }
                            setField(biomeSource, "biome", biomeHolder);
                            setField(biomeSource, "possibleBiomes", (java.util.function.Supplier<?>) () -> Collections.singleton(biomeHolder));
                            setField(chunkGenerator, "biomeSource", biomeSource);

                            Class<?> flatLevelSettingsClass = Class.forName("net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings", true, classLoader);
                            Object flatSettings = unsafe.allocateInstance(flatLevelSettingsClass);

                            Class<?> holderSetClass = Class.forName("net.minecraft.core.HolderSet", true, classLoader);
                            Method directSetMethod = holderSetClass.getMethod("direct", List.class);
                            Object emptyHolderSet = directSetMethod.invoke(null, Collections.emptyList());

                            setField(flatSettings, "structureOverrides", Optional.of(emptyHolderSet));
                            setField(flatSettings, "biome", biomeHolder);
                            setField(flatSettings, "layersInfo", new ArrayList<>());
                            setField(flatSettings, "layers", new ArrayList<>());
                            setField(flatSettings, "lakes", Collections.emptyList());
                            setField(chunkGenerator, "settings", flatSettings);

                            if (emptyGenSettings != null) {
                                final Object finalEmptyGen = emptyGenSettings;
                                setField(chunkGenerator, "generationSettingsGetter", (java.util.function.Function) (b) -> finalEmptyGen);
                            }
                            setField(chunkGenerator, "featuresPerStep", (java.util.function.Supplier) () -> Collections.emptyList());
                        } catch (Throwable t) {
                            LOGGER.warn("[PatchNeoForge] Could not configure flatLevelSource generator: {}", t.getMessage());
                        }

                        setField(worldGenContext, "level", headlessLevel);
                        setField(worldGenContext, "generator", chunkGenerator);

                        setField(chunkMap, "worldGenContext", worldGenContext);
                        setField(chunkMap, "level", headlessLevel);

                        try {
                            Class<?> structureStateClass = Class.forName("net.minecraft.world.level.chunk.ChunkGeneratorStructureState", true, classLoader);
                            Object structureState = unsafe.allocateInstance(structureStateClass);
                            setField(chunkMap, "chunkGeneratorState", structureState);
                        } catch (Throwable ignored) {}

                        try {
                            Class<?> randomStateClass = Class.forName("net.minecraft.world.level.levelgen.RandomState", true, classLoader);
                            Object randomState = unsafe.allocateInstance(randomStateClass);
                            setField(chunkMap, "randomState", randomState);
                        } catch (Throwable ignored) {}

                        setField(chunkCache, "chunkMap", chunkMap);
                        LOGGER.info("[PatchNeoForge] Initialized headless ChunkMap and WorldGenContext");
                    } catch (Throwable t) {
                        LOGGER.warn("[PatchNeoForge] Could not initialize headless chunkMap: {}", t.getMessage());
                    }

                    setField(chunkCache, "dataStorage", dataStorage);
                    setField(chunkCache, "level", headlessLevel);
                    setField(chunkCache, "lastChunkPos", new long[4]);
                    try {
                        Class<?> chunkStatusClass = Class.forName("net.minecraft.world.level.chunk.status.ChunkStatus", true, classLoader);
                        setField(chunkCache, "lastChunkStatus", java.lang.reflect.Array.newInstance(chunkStatusClass, 4));
                        Class<?> chunkAccessClass = Class.forName("net.minecraft.world.level.chunk.ChunkAccess", true, classLoader);
                        setField(chunkCache, "lastChunk", java.lang.reflect.Array.newInstance(chunkAccessClass, 4));
                    } catch (Throwable ignored) {}
                    setField(chunkCache, "mainThread", Thread.currentThread());
                    setField(chunkCache, "spawnEnemies", true);
                    setField(chunkCache, "spawnFriendlies", true);
                    setField(headlessLevel, "chunkSource", chunkCache);
                    LOGGER.info("[PatchNeoForge] Initialized headless ServerChunkCache and DimensionDataStorage");
                } catch (Throwable t) {
                    LOGGER.warn("[PatchNeoForge] Could not initialize headless chunkSource: {}", t.getMessage());
                }

                Map<Object, Object> levelsMap = new ConcurrentHashMap<>();
                levelsMap.put(overworldKey, headlessLevel);

                Field levelsField = baseServerClass.getDeclaredField("levels");
                levelsField.setAccessible(true);
                levelsField.set(server, levelsMap);
                LOGGER.info("[PatchNeoForge] Initialized server.levels with headless OVERWORLD level");
            } catch (Throwable t) {
                LOGGER.warn("[PatchNeoForge] Could not initialize server.levels: {}", t.getMessage());
            }

            try {
                Class<?> reloadableResourcesClass = Class.forName("net.minecraft.server.MinecraftServer$ReloadableResources", true, classLoader);
                Class<?> reloadableServerResourcesClass = Class.forName("net.minecraft.server.ReloadableServerResources", true, classLoader);
                Class<?> advancementManagerClass = Class.forName("net.minecraft.server.ServerAdvancementManager", true, classLoader);
                Class<?> advancementTreeClass = Class.forName("net.minecraft.advancements.AdvancementTree", true, classLoader);

                Object advancementTree = unsafe.allocateInstance(advancementTreeClass);
                Object advancementManager = unsafe.allocateInstance(advancementManagerClass);
                try {
                    Field treeField = advancementManagerClass.getDeclaredField("tree");
                    treeField.setAccessible(true);
                    treeField.set(advancementManager, advancementTree);
                } catch (Throwable ignored) {}

                try {
                    Field advMapField = advancementManagerClass.getDeclaredField("advancements");
                    advMapField.setAccessible(true);
                    advMapField.set(advancementManager, new ConcurrentHashMap<>());
                } catch (Throwable ignored) {}

                Object reloadableServerResources = unsafe.allocateInstance(reloadableServerResourcesClass);
                try {
                    Field advsField = reloadableServerResourcesClass.getDeclaredField("advancements");
                    advsField.setAccessible(true);
                    advsField.set(reloadableServerResources, advancementManager);
                } catch (Throwable ignored) {}

                try {
                    Class<?> commandsClass = Class.forName("net.minecraft.commands.Commands", true, classLoader);
                    Object commands = unsafe.allocateInstance(commandsClass);
                    setField(commands, "dispatcher", CommandBridge.getDispatcher());
                    setField(reloadableServerResources, "commands", commands);

                    try {
                        Class<?> ftbRanksCmdMgrClass = Class.forName("dev.ftb.mods.ftbranks.impl.FTBRanksCommandManager", true, classLoader);
                        Constructor<?> ctor = ftbRanksCmdMgrClass.getConstructor(commandsClass);
                        Object mgr = ctor.newInstance(commands);
                        Field instanceField = ftbRanksCmdMgrClass.getField("INSTANCE");
                        instanceField.set(null, mgr);
                        LOGGER.info("[PatchNeoForge] Initialized FTBRanksCommandManager.INSTANCE");
                    } catch (Throwable t) {
                        LOGGER.debug("[PatchNeoForge] Could not init FTBRanksCommandManager: {}", t.getMessage());
                    }
                } catch (Throwable ignored) {}

                try {
                    Class<?> recipeManagerClass = Class.forName("net.minecraft.world.item.crafting.RecipeManager", true, classLoader);
                    Object recipeManager = unsafe.allocateInstance(recipeManagerClass);
                    setField(recipeManager, "byType", com.google.common.collect.ArrayListMultimap.create());
                    setField(recipeManager, "byName", new HashMap<>());
                    setField(reloadableServerResources, "recipes", recipeManager);
                } catch (Throwable ignored) {}

                Class<?> closeableResourceManagerClass = Class.forName("net.minecraft.server.packs.resources.CloseableResourceManager", true, classLoader);
                Object resourceManagerProxy = java.lang.reflect.Proxy.newProxyInstance(
                        classLoader,
                        new Class<?>[]{closeableResourceManagerClass},
                        (proxy, method, args) -> {
                            String mName = method.getName();
                            if ("listResources".equals(mName) || "listResourceStacks".equals(mName)) {
                                return Collections.emptyMap();
                            }
                            if ("getResourceStack".equals(mName)) {
                                return Collections.emptyList();
                            }
                            if ("getResource".equals(mName)) {
                                return Optional.empty();
                            }
                            if ("getNamespaces".equals(mName)) {
                                return Collections.emptySet();
                            }
                            if ("listPacks".equals(mName)) {
                                return java.util.stream.Stream.empty();
                            }
                            if ("close".equals(mName)) {
                                return null;
                            }
                            return null;
                        }
                );

                Constructor<?> rrCtor = reloadableResourcesClass.getDeclaredConstructor(
                        closeableResourceManagerClass,
                        reloadableServerResourcesClass
                );
                rrCtor.setAccessible(true);
                Object rr = rrCtor.newInstance(resourceManagerProxy, reloadableServerResources);

                Field resField = baseServerClass.getDeclaredField("resources");
                resField.setAccessible(true);
                resField.set(server, rr);
            } catch (Throwable t) {
                LOGGER.warn("[PatchNeoForge] Could not initialize server.resources: {}", t.getMessage());
            }

            try {
                Class<?> worldDataClass = Class.forName("net.minecraft.world.level.storage.WorldData", true, classLoader);
                Class<?> featureFlagsClass = Class.forName("net.minecraft.world.flag.FeatureFlags", true, classLoader);
                Object vanillaSet = featureFlagsClass.getField("VANILLA_SET").get(null);
                Class<?> dataConfigClass = Class.forName("net.minecraft.world.level.WorldDataConfiguration", true, classLoader);
                Object defaultDataConfig = dataConfigClass.getField("DEFAULT").get(null);
                Class<?> worldOptionsClass = Class.forName("net.minecraft.world.level.levelgen.WorldOptions", true, classLoader);
                Object worldOptionsObj = worldOptionsClass.getMethod("defaultWithRandomSeed").invoke(null);
                Class<?> lifecycleClassRef = Class.forName("com.mojang.serialization.Lifecycle", true, classLoader);
                Object stableLifecycleRef = lifecycleClassRef.getMethod("stable").invoke(null);

                Object worldDataProxy = java.lang.reflect.Proxy.newProxyInstance(
                        classLoader,
                        new Class<?>[]{worldDataClass},
                        (proxy, method, args) -> {
                            String mName = method.getName();
                            if ("enabledFeatures".equals(mName)) {
                                return vanillaSet;
                            }
                            if ("getDataConfiguration".equals(mName)) {
                                return defaultDataConfig;
                            }
                            if ("getLevelName".equals(mName)) {
                                return "world";
                            }
                            if ("isHardcore".equals(mName)) {
                                return false;
                            }
                            if ("isAllowCommands".equals(mName)) {
                                return true;
                            }
                            if ("getVersion".equals(mName)) {
                                return 3955;
                            }
                            if ("wasModded".equals(mName)) {
                                return true;
                            }
                            if ("worldGenOptions".equals(mName)) {
                                return worldOptionsObj;
                            }
                            if ("overworldData".equals(mName)) {
                                return sharedWorldRefs[0];
                            }
                            if ("getLevelSettings".equals(mName)) {
                                return sharedWorldRefs[1];
                            }
                            if ("getGameRules".equals(mName)) {
                                return sharedWorldRefs[2];
                            }
                            if ("getDifficulty".equals(mName)) {
                                return sharedWorldRefs[3];
                            }
                            if ("getGameType".equals(mName)) {
                                return sharedWorldRefs[4];
                            }
                            if ("isDifficultyLocked".equals(mName) || "isFlatWorld".equals(mName) || "isDebugWorld".equals(mName)) {
                                return false;
                            }
                            if ("worldGenSettingsLifecycle".equals(mName)) {
                                return stableLifecycleRef;
                            }
                            return null;
                        }
                );

                Field worldDataField = baseServerClass.getDeclaredField("worldData");
                worldDataField.setAccessible(true);
                worldDataField.set(server, worldDataProxy);
            } catch (Throwable t) {
                LOGGER.warn("[PatchNeoForge] Could not initialize server.worldData: {}", t.getMessage());
            }

            // Initialize headless DedicatedPlayerList
            try {
                Class<?> playerListClass = Class.forName("net.minecraft.server.dedicated.DedicatedPlayerList", true, classLoader);
                Object playerList = unsafe.allocateInstance(playerListClass);
                List<Object> emptyPlayers = new java.util.concurrent.CopyOnWriteArrayList<>();
                setField(playerList, "server", server);
                setField(playerList, "players", emptyPlayers);
                setField(playerList, "playersView", Collections.unmodifiableList(emptyPlayers));
                setField(playerList, "playersByUUID", new ConcurrentHashMap<>());
                setField(playerList, "stats", new ConcurrentHashMap<>());
                setField(playerList, "advancements", new ConcurrentHashMap<>());
                setField(playerList, "maxPlayers", 20);
                setField(playerList, "viewDistance", 10);
                setField(playerList, "simulationDistance", 10);
                setField(server, "playerList", playerList);
                LOGGER.info("[PatchNeoForge] Initialized headless DedicatedPlayerList");
            } catch (Throwable t) {
                LOGGER.warn("[PatchNeoForge] Could not initialize headless playerList: {}", t.getMessage());
            }

            // Set ServerLifecycleHooks.currentServer and LogicalSidedProvider.setServer
            try {
                Class<?> hooksClass = Class.forName("net.neoforged.neoforge.server.ServerLifecycleHooks", true, classLoader);
                Field currentServerField = hooksClass.getDeclaredField("currentServer");
                currentServerField.setAccessible(true);
                currentServerField.set(null, server);

                Class<?> logicalSidedProviderClass = Class.forName("net.neoforged.neoforge.common.util.LogicalSidedProvider", true, classLoader);
                Method setServerMethod = logicalSidedProviderClass.getMethod("setServer", java.util.function.Supplier.class);
                java.util.function.Supplier<Object> serverSupplier = () -> server;
                setServerMethod.invoke(null, serverSupplier);
                LOGGER.info("[PatchNeoForge] Initialized ServerLifecycleHooks.currentServer and LogicalSidedProvider.setServer");
            } catch (Throwable t) {
                LOGGER.warn("[PatchNeoForge] Could not set ServerLifecycleHooks.currentServer: {}", t.getMessage());
            }

            // DedicatedServerSettings on server
            try {
                Class<?> settingsClass = Class.forName("net.minecraft.server.dedicated.DedicatedServerSettings", true, classLoader);
                Constructor<?> settingsCtor = settingsClass.getConstructor(Path.class);
                Path propsPath = Path.of("server.properties").toAbsolutePath();
                if (!Files.exists(propsPath)) {
                    Files.writeString(propsPath, "server-port=25565\n");
                }
                Object settings = settingsCtor.newInstance(propsPath);
                setField(server, "settings", settings);
                LOGGER.info("[PatchNeoForge] Initialized server.settings (DedicatedServerSettings)");
            } catch (Throwable t) {
                LOGGER.warn("[PatchNeoForge] Could not initialize server.settings: {}", t.getMessage());
            }

            // ServerTickRateManager on server
            try {
                Class<?> tickRateManagerClass = Class.forName("net.minecraft.server.ServerTickRateManager", true, classLoader);
                Constructor<?> trmCtor = tickRateManagerClass.getConstructor(baseServerClass);
                Object trm = trmCtor.newInstance(server);
                setField(server, "tickRateManager", trm);
                LOGGER.info("[PatchNeoForge] Initialized server.tickRateManager");
            } catch (Throwable t) {
                LOGGER.warn("[PatchNeoForge] Could not initialize server.tickRateManager: {}", t.getMessage());
            }

            // ServerConnectionListener on server
            try {
                Class<?> connListenerClass = Class.forName("net.minecraft.server.network.ServerConnectionListener", true, classLoader);
                Constructor<?> connCtor = connListenerClass.getConstructor(baseServerClass);
                Object connListener = connCtor.newInstance(server);
                setField(server, "connection", connListener);
                LOGGER.info("[PatchNeoForge] Initialized server.connection (ServerConnectionListener)");
            } catch (Throwable t) {
                LOGGER.warn("[PatchNeoForge] Could not initialize server.connection: {}", t.getMessage());
            }

            // Register MinecraftServer in NeoForgeNetworkBridge
            try {
                if (server instanceof net.minecraft.server.MinecraftServer ms) {
                    org.patchneoforge.network.NeoForgeNetworkBridge.setMinecraftServer(ms);
                    LOGGER.info("[PatchNeoForge] Registered headless server in NeoForgeNetworkBridge");
                }
            } catch (Throwable t) {
                LOGGER.warn("[PatchNeoForge] Could not set server in NeoForgeNetworkBridge: {}", t.getMessage());
            }

            return server;
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Could not create headless MinecraftServer instance", t);
            return null;
        }
    }

    private static net.minecraft.commands.CommandBuildContext createCommandBuildContext(ClassLoader classLoader) {
        try {
            Class<?> builtInRegistriesClass = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, classLoader);
            Object rootReg = builtInRegistriesClass.getField("REGISTRY").get(null);
            Class<?> mappedRegistryClass = Class.forName("net.minecraft.core.MappedRegistry", true, classLoader);
            try {
                Field intrusiveField = mappedRegistryClass.getDeclaredField("unregisteredIntrusiveHolders");
                intrusiveField.setAccessible(true);
                if (rootReg instanceof Iterable<?> iterable) {
                    for (Object subRegistry : iterable) {
                        if (mappedRegistryClass.isInstance(subRegistry)) {
                            intrusiveField.set(subRegistry, null);
                        }
                    }
                }
            } catch (Throwable ignored) {}

            Class<?> registryAccessClass = Class.forName("net.minecraft.core.RegistryAccess", true, classLoader);
            Object frozenAccess = registryAccessClass.getMethod("fromRegistryOfRegistries", net.minecraft.core.Registry.class).invoke(null, rootReg);

            Class<?> providerClass = Class.forName("net.minecraft.core.HolderLookup$Provider", true, classLoader);
            Class<?> resourceKeyClass = Class.forName("net.minecraft.resources.ResourceKey", true, classLoader);
            Class<?> resourceLocationClass = Class.forName("net.minecraft.resources.ResourceLocation", true, classLoader);
            Class<?> registryClass = Class.forName("net.minecraft.core.Registry", true, classLoader);
            Class<?> registriesClass = Class.forName("net.minecraft.core.registries.Registries", true, classLoader);

            Class<?> lifecycleClass = Class.forName("com.mojang.serialization.Lifecycle", true, classLoader);
            Object stableLifecycle = lifecycleClass.getMethod("stable").invoke(null);
            Map<String, Object> fallbackDynamicRegistries = new ConcurrentHashMap<>();

            Object wrappedProvider = java.lang.reflect.Proxy.newProxyInstance(
                    classLoader,
                    new Class<?>[]{providerClass},
                    (proxy, method, args) -> {
                        String mName = method.getName();
                        if ("lookup".equals(mName) && args != null && args.length == 1) {
                            Object rKey = args[0];
                            Object loc = resourceKeyClass.getMethod("location").invoke(rKey);
                            String locStr = loc != null ? loc.toString() : "";

                            Object reg = registryClass.getMethod("get", resourceLocationClass).invoke(rootReg, loc);
                            if (reg != null) {
                                Object lookup = mappedRegistryClass.getMethod("asLookup").invoke(reg);
                                return Optional.of(lookup);
                            }

                            Object fallbackReg = fallbackDynamicRegistries.computeIfAbsent(locStr, k -> {
                                try {
                                    Object fallback = mappedRegistryClass.getConstructor(resourceKeyClass, lifecycleClass).newInstance(rKey, stableLifecycle);
                                    Field fField = mappedRegistryClass.getDeclaredField("frozen");
                                    fField.setAccessible(true);
                                    fField.set(fallback, false);
                                    return fallback;
                                } catch (Throwable t) {
                                    return null;
                                }
                            });
                            if (fallbackReg != null) {
                                Object lookup = mappedRegistryClass.getMethod("asLookup").invoke(fallbackReg);
                                return Optional.of(lookup);
                            }
                        }
                        try {
                            return method.invoke(frozenAccess, args);
                        } catch (java.lang.reflect.InvocationTargetException ite) {
                            throw ite.getTargetException();
                        }
                    }
            );

            Class<?> featureFlagsClass = Class.forName("net.minecraft.world.flag.FeatureFlags", true, classLoader);
            Object vanillaSet = featureFlagsClass.getField("VANILLA_SET").get(null);

            Class<?> commandBuildContextClass = Class.forName("net.minecraft.commands.CommandBuildContext", true, classLoader);
            Method simpleMethod = commandBuildContextClass.getMethod("simple",
                    providerClass,
                    Class.forName("net.minecraft.world.flag.FeatureFlagSet", true, classLoader));

            return (net.minecraft.commands.CommandBuildContext) simpleMethod.invoke(null, wrappedProvider, vanillaSet);
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Could not create CommandBuildContext: {}", t.getMessage());
            return null;
        }
    }

    private static void fireServerStartingEvents() {
        fireServerStartingEvents(PatchNeoForgeBootstrap.class.getClassLoader());
    }

    private static void drainServerQueue(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        try {
            for (Class<?> c = server.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field pendingField = c.getDeclaredField("pendingRunnables");
                    pendingField.setAccessible(true);
                    Object q = pendingField.get(server);
                    if (q instanceof Queue<?> queue) {
                        while (!queue.isEmpty()) {
                            Object task = queue.poll();
                            if (task instanceof Runnable r) {
                                r.run();
                            } else if (task != null) {
                                try {
                                    Method runMethod = task.getClass().getMethod("run");
                                    runMethod.invoke(task);
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable t) {
            LOGGER.debug("[PatchNeoForge] Error draining server runnables: {}", t.getMessage());
        }
    }

    private static void postLifecycleEventResilient(net.neoforged.bus.api.IEventBus bus, net.neoforged.bus.api.Event event) {
        if (bus == null || event == null) return;
        try {
            Method getListMethod = bus.getClass().getDeclaredMethod("getListenerList", Class.class);
            getListMethod.setAccessible(true);
            Object list = getListMethod.invoke(bus, event.getClass());
            Method getListenersMethod = list.getClass().getMethod("getListeners");
            net.neoforged.bus.api.EventListener[] listeners =
                    (net.neoforged.bus.api.EventListener[]) getListenersMethod.invoke(list);
            for (int i = 0; i < listeners.length; i++) {
                net.neoforged.bus.api.EventListener listener = listeners[i];
                try {
                    listener.invoke(event);
                } catch (Throwable t) {
                    LOGGER.warn("[PatchNeoForge] Listener #{} ({}) threw exception on {}: {}",
                            i, listener.getClass().getName(), event.getClass().getSimpleName(), t.getMessage() != null ? t.getMessage() : t.toString(), t);
                }
            }
        } catch (Throwable t) {
            try {
                bus.post(event);
            } catch (Throwable inner) {
                LOGGER.warn("[PatchNeoForge] {} encountered error: {}", event.getClass().getSimpleName(), inner.getMessage());
            }
        }
    }

    private static void fireServerStartingEvents(ClassLoader classLoader) {
        LOGGER.info("[PatchNeoForge] Firing server starting lifecycle on NeoForge.EVENT_BUS...");

        net.minecraft.server.MinecraftServer server = createHeadlessServer(classLoader);
        net.minecraft.commands.CommandBuildContext context = createCommandBuildContext(classLoader);

        try {
            Class<?> cc = Class.forName("com.turtlehoarder.cobblemonchallenge.common.CobblemonChallenge", true, classLoader);
            Field f = cc.getDeclaredField("CHALLENGE_DISTANCE_RESTRICTION");
            f.setAccessible(true);
            if (f.get(null) == null) {
                f.set(null, Boolean.FALSE);
            }
        } catch (Throwable ignored) {}

        // 1. RegisterCommandsEvent (commands must be registered before server lifecycle starts)
        try {
            RegisterCommandsEvent cmdEvent = new RegisterCommandsEvent(CommandBridge.getDispatcher(), net.minecraft.commands.Commands.CommandSelection.ALL, context);
            postLifecycleEventResilient(NeoForge.EVENT_BUS, cmdEvent);
            CommandBridge.syncWithPumpkin();
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] RegisterCommandsEvent encountered error: {}", t.getMessage());
        }

        // Pre-load server configs before firing ServerAboutToStartEvent
        try {
            Class<?> configTrackerClass = Class.forName("net.neoforged.fml.config.ConfigTracker", true, classLoader);
            Object tracker = configTrackerClass.getField("INSTANCE").get(null);
            Class<?> modConfigTypeClass = Class.forName("net.neoforged.fml.config.ModConfig$Type", true, classLoader);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object serverType = Enum.valueOf((Class<Enum>) modConfigTypeClass, "SERVER");
            Path serverConfigPath = Path.of("server/config").toAbsolutePath();
            if (!Files.exists(serverConfigPath)) {
                serverConfigPath = Path.of("config").toAbsolutePath();
            }
            configTrackerClass.getMethod("loadConfigs", modConfigTypeClass, Path.class).invoke(tracker, serverType, serverConfigPath);
            LOGGER.info("[PatchNeoForge] Loaded ModConfig Type.SERVER configs");
        } catch (Throwable t) {
            LOGGER.debug("[PatchNeoForge] Could not pre-load server configs: {}", t.getMessage());
        }

        // 2. ServerAboutToStartEvent
        try {
            postLifecycleEventResilient(NeoForge.EVENT_BUS, new ServerAboutToStartEvent(server));
            drainServerQueue(server);
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] ServerAboutToStartEvent encountered error: {}", t.getMessage());
        }

        // Pre-initialize AliMod.INFO_PROPAGATOR if present
        try {
            Class<?> aliClass = Class.forName("com.yanny.ali.AliMod", true, classLoader);
            Field infoField = aliClass.getDeclaredField("INFO_PROPAGATOR");
            infoField.setAccessible(true);
            if (infoField.get(null) == null) {
                Class<?> serverClass = Class.forName("com.yanny.ali.network.neoforge.Server", true, classLoader);
                Object srv = serverClass.getConstructor().newInstance();
                Class<?> distHolderClass = Class.forName("com.yanny.ali.network.DistHolder", true, classLoader);
                Object distHolder = distHolderClass.getConstructor(
                        Class.forName("com.yanny.ali.network.AbstractClient", true, classLoader),
                        Class.forName("com.yanny.ali.network.AbstractServer", true, classLoader)
                ).newInstance(null, srv);
                infoField.set(null, distHolder);
            }
        } catch (Throwable ignored) {}

        // 3. ServerStartingEvent
        try {
            postLifecycleEventResilient(NeoForge.EVENT_BUS, new ServerStartingEvent(server));
            drainServerQueue(server);
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] ServerStartingEvent encountered error: {}", t.getMessage());
        }

        // Ensure Chunky is initialized before ServerStartedEvent
        try {
            Class<?> chunkyProviderClass = Class.forName("org.popcraft.chunky.ChunkyProvider", true, classLoader);
            Field instanceField = chunkyProviderClass.getDeclaredField("instance");
            instanceField.setAccessible(true);
            if (instanceField.get(null) == null) {
                Class<?> chunkyClass = Class.forName("org.popcraft.chunky.Chunky", true, classLoader);
                Class<?> neoServerClass = Class.forName("org.popcraft.chunky.platform.NeoForgeServer", true, classLoader);
                Class<?> gsonConfigClass = Class.forName("org.popcraft.chunky.platform.impl.GsonConfig", true, classLoader);
                Class<?> fmlPathsClass = Class.forName("net.neoforged.fml.loading.FMLPaths", true, classLoader);
                Object configDirObj = fmlPathsClass.getField("CONFIGDIR").get(null);
                Path configDir = (Path) configDirObj.getClass().getMethod("get").invoke(configDirObj);
                Path chunkyConfigPath = configDir.resolve("chunky/config.json");
                Files.createDirectories(chunkyConfigPath.getParent());

                Class<?> chunkyNeoForgeClass = Class.forName("org.popcraft.chunky.ChunkyNeoForge", true, classLoader);
                Object chunkyNeoForge = chunkyNeoForgeClass.getConstructor().newInstance();

                Object neoServer = neoServerClass.getConstructor(chunkyNeoForgeClass, net.minecraft.server.MinecraftServer.class)
                        .newInstance(chunkyNeoForge, server);
                Object gsonConfig = gsonConfigClass.getConstructor(Path.class).newInstance(chunkyConfigPath);
                Object chunkyObj = chunkyClass.getConstructor(
                        Class.forName("org.popcraft.chunky.platform.Server", true, classLoader),
                        Class.forName("org.popcraft.chunky.platform.Config", true, classLoader)
                ).newInstance(neoServer, gsonConfig);
                instanceField.set(null, chunkyObj);
                LOGGER.info("[PatchNeoForge] Initialized Chunky fallback instance on ChunkyProvider");
            }
        } catch (Throwable t) {
            LOGGER.debug("[PatchNeoForge] ChunkyProvider check note: {}", t.getMessage());
        }

        // 4. ServerStartedEvent
        try {
            postLifecycleEventResilient(NeoForge.EVENT_BUS, new ServerStartedEvent(server));
            drainServerQueue(server);
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] ServerStartedEvent encountered error: {}", t.getMessage());
        }

        LOGGER.info("[PatchNeoForge] NeoForge server ready and active! Type commands into console (or 'stop' to shut down).");
    }

    private static void registerSyntheticModFile(File jarFile, net.neoforged.neoforgespi.language.IModInfo modInfo, String modId, ClassLoader classLoader) {
        try {
            Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe u = (sun.misc.Unsafe) unsafeField.get(null);

            Class<?> modFileClass = Class.forName("net.neoforged.fml.loading.moddiscovery.ModFile", true, classLoader);
            Class<?> modFileInfoClass = Class.forName("net.neoforged.fml.loading.moddiscovery.ModFileInfo", true, classLoader);
            Class<?> secureJarClass = Class.forName("cpw.mods.jarhandling.SecureJar", true, classLoader);

            Object secureJar = secureJarClass.getMethod("from", Path[].class).invoke(null, (Object) new Path[]{jarFile.toPath()});

            Object modFile = u.allocateInstance(modFileClass);
            Object modFileInfo = u.allocateInstance(modFileInfoClass);

            setField(modFile, "jar", secureJar);
            setField(modFile, "modFileInfo", modFileInfo);
            try {
                Class<?> typeEnum = Class.forName("net.neoforged.neoforgespi.locating.IModFile$Type", true, classLoader);
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object modType = Enum.valueOf((Class<Enum>) typeEnum, "MOD");
                setField(modFile, "modFileType", modType);
            } catch (Throwable ignored) {}
            try {
                Class<?> discAttrClass = Class.forName("net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes", true, classLoader);
                setField(modFile, "discoveryAttributes", discAttrClass.getField("DEFAULT").get(null));
            } catch (Throwable ignored) {}
            setField(modFile, "fileProperties", Collections.emptyMap());
            setField(modFile, "loaders", Collections.emptyList());
            setField(modFile, "coreMods", Collections.emptyList());
            setField(modFile, "mixinConfigs", Collections.emptyList());
            setField(modFile, "accessTransformers", Collections.emptyList());

            // Initialize ModFileScanData for titanium / functionalstorage
            try {
                Class<?> scanDataClass = Class.forName("net.neoforged.neoforgespi.language.ModFileScanData", true, classLoader);
                Object scanData = scanDataClass.getDeclaredConstructor().newInstance();
                setField(modFile, "fileModFileScanData", scanData);
                setField(modFile, "futureScanResult", java.util.concurrent.CompletableFuture.completedFuture(scanData));
            } catch (Throwable ignored) {}

            setField(modFileInfo, "modFile", modFile);
            List<Object> infoList = modInfo != null ? List.of(modInfo) : Collections.emptyList();
            setField(modFileInfo, "mods", infoList);
            setField(modFileInfo, "properties", Collections.emptyMap());
            setField(modFileInfo, "languageSpecs", Collections.emptyList());
            setField(modFileInfo, "usesServices", Collections.emptyList());
            setField(modFileInfo, "license", "All Rights Reserved");

            // Inject into ModList.get().fileById and modFiles
            try {
                net.neoforged.fml.ModList modList = net.neoforged.fml.ModList.get();
                if (modList != null) {
                    Field fileByIdField = net.neoforged.fml.ModList.class.getDeclaredField("fileById");
                    fileByIdField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) fileByIdField.get(modList);
                    if (map == null || map.getClass().getName().contains("Immutable") || map.getClass().getName().contains("Empty")) {
                        map = new ConcurrentHashMap<>(map != null ? map : Collections.emptyMap());
                        fileByIdField.set(modList, map);
                    }
                    map.put(modId, modFileInfo);

                    try {
                        Field modFilesField = net.neoforged.fml.ModList.class.getDeclaredField("modFiles");
                        modFilesField.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        List<Object> list = (List<Object>) modFilesField.get(modList);
                        if (list == null || list.getClass().getName().contains("Immutable") || list.getClass().getName().contains("Empty")) {
                            list = new java.util.concurrent.CopyOnWriteArrayList<>(list != null ? list : Collections.emptyList());
                            modFilesField.set(modList, list);
                        }
                        if (!list.contains(modFileInfo)) {
                            list.add(modFileInfo);
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}

            // Inject into LoadingModList.get().fileById and modFiles
            try {
                Class<?> lmlClass = Class.forName("net.neoforged.fml.loading.LoadingModList", true, classLoader);
                Object lml = lmlClass.getMethod("get").invoke(null);
                if (lml != null) {
                    Field fileByIdField = lmlClass.getDeclaredField("fileById");
                    fileByIdField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) fileByIdField.get(lml);
                    if (map == null || map.getClass().getName().contains("Immutable") || map.getClass().getName().contains("Empty")) {
                        map = new ConcurrentHashMap<>(map != null ? map : Collections.emptyMap());
                        fileByIdField.set(lml, map);
                    }
                    map.put(modId, modFileInfo);

                    try {
                        Field modFilesField = lmlClass.getDeclaredField("modFiles");
                        modFilesField.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        List<Object> list = (List<Object>) modFilesField.get(lml);
                        if (list == null || list.getClass().getName().contains("Immutable") || list.getClass().getName().contains("Empty")) {
                            list = new java.util.concurrent.CopyOnWriteArrayList<>(list != null ? list : Collections.emptyList());
                            modFilesField.set(lml, list);
                        }
                        if (!list.contains(modFileInfo)) {
                            list.add(modFileInfo);
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LOGGER.debug("[PatchNeoForge] Could not create synthetic ModFileInfo for '{}': {}", modId, t.getMessage());
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe u = (sun.misc.Unsafe) unsafeField.get(null);
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(fieldName);
                    long offset = u.objectFieldOffset(f);
                    u.putObject(target, offset, value);
                    return;
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable t) {
            LOGGER.debug("[PatchNeoForge] Could not set field {}: {}", fieldName, t.getMessage());
        }
    }

    private static void setFieldStatic(Class<?> clazz, String fieldName, Object value) {
        try {
            Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe u = (sun.misc.Unsafe) unsafeField.get(null);
            Field f = clazz.getDeclaredField(fieldName);
            Object base = u.staticFieldBase(f);
            long offset = u.staticFieldOffset(f);
            u.putObject(base, offset, value);
        } catch (Throwable t) {
            LOGGER.debug("[PatchNeoForge] Could not set static field {}: {}", fieldName, t.getMessage());
        }
    }

    private static void ensureShadedNightConfig(File baseDir, Instrumentation inst, List<File> allClassPathJars) {
        try {
            File rootDir = baseDir.getParentFile() != null ? baseDir.getParentFile() : baseDir;
            File jarjarDir = new File(baseDir, "cache/jarjar");
            jarjarDir.mkdirs();
            File shadedJar = new File(jarjarDir, "greenhouseconfig_core_shaded.jar");

            File[] candidates = new File[]{
                    new File(rootDir, "libraries/com/electronwill/night-config/core/3.8.3/core-3.8.3.jar"),
                    new File(baseDir, "cache/neoforge/libraries/com/electronwill/night-config/core/3.8.2/core-3.8.2.jar"),
                    new File(rootDir, "libraries/com/electronwill/night-config/core/3.8.2/core-3.8.2.jar")
            };
            File coreJar = null;
            for (File c : candidates) {
                if (c.exists()) {
                    coreJar = c;
                    break;
                }
            }

            if (coreJar != null && (!shadedJar.exists() || shadedJar.length() < 10000)) {
                try (java.util.jar.JarFile src = new java.util.jar.JarFile(coreJar);
                     java.util.jar.JarOutputStream dst = new java.util.jar.JarOutputStream(new java.io.FileOutputStream(shadedJar))) {
                    java.util.Enumeration<java.util.jar.JarEntry> entries = src.entries();
                    net.bytebuddy.jar.asm.commons.Remapper remapper = new net.bytebuddy.jar.asm.commons.Remapper() {
                        @Override
                        public String map(String internalName) {
                            if (internalName != null && internalName.startsWith("com/electronwill/nightconfig/")) {
                                return "house/greenhouse/greenhouseconfig/nightconfig/shade/" + internalName.substring("com/electronwill/nightconfig/".length());
                            }
                            return internalName;
                        }
                    };
                    while (entries.hasMoreElements()) {
                        java.util.jar.JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.endsWith(".class")) {
                            byte[] origBytes = src.getInputStream(entry).readAllBytes();
                            net.bytebuddy.jar.asm.ClassReader cr = new net.bytebuddy.jar.asm.ClassReader(origBytes);
                            net.bytebuddy.jar.asm.ClassWriter cw = new net.bytebuddy.jar.asm.ClassWriter(0);
                            cr.accept(new net.bytebuddy.jar.asm.commons.ClassRemapper(cw, remapper), 0);
                            byte[] transformed = cw.toByteArray();
                            String newName = name;
                            if (newName.startsWith("com/electronwill/nightconfig/")) {
                                newName = "house/greenhouse/greenhouseconfig/nightconfig/shade/" + newName.substring("com/electronwill/nightconfig/".length());
                            }
                            dst.putNextEntry(new java.util.jar.JarEntry(newName));
                            dst.write(transformed);
                            dst.closeEntry();
                        }
                    }
                }
                LOGGER.info("[PatchNeoForge] Generated shaded greenhouseconfig core jar at {}", shadedJar);
                try {
                    File cacheJar = new File(baseDir, "cache/jarjar/greenhouseconfig_core_shaded.jar");
                    cacheJar.getParentFile().mkdirs();
                    java.nio.file.Files.copy(shadedJar.toPath(), cacheJar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable ignored) {}
            }

            if (shadedJar.exists()) {
                allClassPathJars.add(shadedJar);
                if (inst != null) {
                    try {
                        inst.appendToSystemClassLoaderSearch(new JarFile(shadedJar));
                        LOGGER.info("[PatchNeoForge] Appended {} to system class loader search", shadedJar.getName());
                    } catch (Throwable t) {
                        LOGGER.debug("[PatchNeoForge] Could not append shaded jar: {}", t.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[PatchNeoForge] Could not ensure shaded nightconfig: {}", t.getMessage());
        }
    }

    public static void shutdown() {
        LOGGER.info("[PatchNeoForge] Shutting down NeoForge mods...");
        NeoForge.EVENT_BUS.post(new ServerStoppingEvent(null));
        NeoForge.EVENT_BUS.post(new ServerStoppedEvent(null));
        LOADED_MODS.clear();
        INDEXED_MODS.clear();
        LOGGER.info("[PatchNeoForge] NeoForge mods shut down cleanly.");
    }
}
