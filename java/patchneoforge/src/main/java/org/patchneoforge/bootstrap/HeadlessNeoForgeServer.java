package org.patchneoforge.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;

public final class HeadlessNeoForgeServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeadlessNeoForgeServer.class);
    private static volatile boolean booted = false;
    private static volatile Instrumentation instrumentation;

    private HeadlessNeoForgeServer() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        LOGGER.info("[PatchNeoForge] Java agent premain initialized");
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        LOGGER.info("[PatchNeoForge] Java agent agentmain initialized");
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static boolean isBooted() {
        return booted;
    }

    public static boolean boot(String runtimeDirPath) {
        LOGGER.info("[PatchNeoForge] Booting Headless NeoForge Server runtime in: {}", runtimeDirPath);
        booted = true;
        return true;
    }
}
