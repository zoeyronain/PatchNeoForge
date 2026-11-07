package org.patchneoforge.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HeadlessNeoForgeServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeadlessNeoForgeServer.class);
    private static volatile boolean booted = false;

    private HeadlessNeoForgeServer() {}

    public static boolean isBooted() {
        return booted;
    }

    public static boolean boot(String runtimeDirPath) {
        LOGGER.info("[PatchNeoForge] Booting Headless NeoForge Server runtime in: {}", runtimeDirPath);
        booted = true;
        return true;
    }
}
