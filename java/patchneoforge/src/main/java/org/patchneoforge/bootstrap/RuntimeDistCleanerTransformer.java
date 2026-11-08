package org.patchneoforge.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;

public class RuntimeDistCleanerTransformer implements ClassFileTransformer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeDistCleanerTransformer.class);
    private static final byte[] ONLYIN_BYTES = "Lnet/neoforged/api/distmarker/OnlyIn;".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ONLYINS_BYTES = "Lnet/neoforged/api/distmarker/OnlyIns;".getBytes(StandardCharsets.US_ASCII);

    private Object cleanerInstance;
    private java.lang.reflect.Method processMethod;
    private Object phaseAfter;
    private volatile boolean initialized = false;

    private static boolean containsBytes(byte[] buffer, byte[] pattern) {
        if (buffer == null || buffer.length < pattern.length) return false;
        outer:
        for (int i = 0; i <= buffer.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (buffer[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private synchronized void init(ClassLoader loader) {
        if (initialized) return;
        try {
            Class<?> distClass = Class.forName("net.neoforged.api.distmarker.Dist", true, loader);
            @SuppressWarnings({"rawtypes", "unchecked"})
            Class distRaw = distClass;
            Object serverDist = Enum.valueOf(distRaw, "DEDICATED_SERVER");

            Class<?> cleanerClass = Class.forName("net.neoforged.fml.common.asm.RuntimeDistCleaner", true, loader);
            cleanerInstance = cleanerClass.getDeclaredConstructor().newInstance();
            cleanerClass.getMethod("setDistribution", distClass).invoke(cleanerInstance, serverDist);

            Class<?> phaseClass = Class.forName("cpw.mods.modlauncher.serviceapi.ILaunchPluginService$Phase", true, loader);
            @SuppressWarnings({"rawtypes", "unchecked"})
            Class phaseRaw = phaseClass;
            phaseAfter = Enum.valueOf(phaseRaw, "AFTER");

            Class<?> classNodeClass = Class.forName("org.objectweb.asm.tree.ClassNode", true, loader);
            Class<?> typeClass = Class.forName("org.objectweb.asm.Type", true, loader);

            processMethod = cleanerClass.getMethod("processClassWithFlags", phaseClass, classNodeClass, typeClass, String.class);
            initialized = true;
            LOGGER.info("[PatchNeoForge] RuntimeDistCleaner (DISTXFORM) transformer initialized for DEDICATED_SERVER");
        } catch (Throwable ignored) {}
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || loader == null || classfileBuffer == null) return null;

        if (className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("org/patchneoforge/")
                || className.startsWith("org/objectweb/asm/")
                || className.startsWith("cpw/mods/modlauncher/")) {
            return null;
        }

        // Fast-path: check if bytecode contains @OnlyIn or @OnlyIns
        if (!containsBytes(classfileBuffer, ONLYIN_BYTES) && !containsBytes(classfileBuffer, ONLYINS_BYTES)) {
            return null;
        }

        if (!initialized) {
            init(loader);
            if (!initialized) return null;
        }

        try {
            Class<?> classReaderClass = Class.forName("org.objectweb.asm.ClassReader", true, loader);
            Class<?> classNodeClass = Class.forName("org.objectweb.asm.tree.ClassNode", true, loader);
            Class<?> classWriterClass = Class.forName("org.objectweb.asm.ClassWriter", true, loader);
            Class<?> typeClass = Class.forName("org.objectweb.asm.Type", true, loader);

            Object cr = classReaderClass.getConstructor(byte[].class).newInstance((Object) classfileBuffer);
            Object cn = classNodeClass.getDeclaredConstructor().newInstance();

            classReaderClass.getMethod("accept", Class.forName("org.objectweb.asm.ClassVisitor", true, loader), int.class)
                    .invoke(cr, cn, 0);

            Object classType = typeClass.getMethod("getObjectType", String.class).invoke(null, className);

            int result = (int) processMethod.invoke(cleanerInstance, phaseAfter, cn, classType, "mod");
            if (result != 0) {
                Object cw = classWriterClass.getConstructor(int.class).newInstance(0);
                classNodeClass.getMethod("accept", Class.forName("org.objectweb.asm.ClassVisitor", true, loader))
                        .invoke(cn, cw);
                return (byte[]) classWriterClass.getMethod("toByteArray").invoke(cw);
            }
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable target = ite.getTargetException();
            if (target instanceof RuntimeException re && target.getMessage() != null && target.getMessage().contains("invalid dist")) {
                throw re;
            }
        } catch (Throwable ignored) {}

        return null;
    }
}
