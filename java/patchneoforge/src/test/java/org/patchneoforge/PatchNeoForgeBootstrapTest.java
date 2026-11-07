package org.patchneoforge;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import org.junit.jupiter.api.Test;
import org.patchneoforge.bootstrap.CommandBridge;
import org.patchneoforge.loader.PatchNeoForgeBootstrap;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

public class PatchNeoForgeBootstrapTest {

    @Test
    public void testBootstrapWithTestMod() {
        File testModJar = new File("../patchneoforge-test-mod/build/libs/test-neoforge-mod.jar");
        assertTrue(testModJar.exists(), "test-neoforge-mod.jar should exist: " + testModJar.getAbsolutePath());

        File modsDir = testModJar.getParentFile();
        boolean success = PatchNeoForgeBootstrap.bootstrapMods(modsDir.getAbsolutePath());
        assertTrue(success, "Bootstrap should succeed");

        // Verify testmod is loaded
        assertTrue(PatchNeoForgeBootstrap.getLoadedMods().containsKey("testmod"),
                "Loaded mods should contain 'testmod'");

        var container = PatchNeoForgeBootstrap.getLoadedMods().get("testmod");
        assertNotNull(container.getModInstance(), "Mod instance should be instantiated");
        assertEquals("testmod", container.getModId());
        assertEquals("Test NeoForge Mod", container.getDisplayName());

        // Verify Brigadier command was registered
        assertNotNull(CommandBridge.getDispatcher().getRoot().getChild("testmod"),
                "Command /testmod should be registered in CommandBridge");

        // Execute command
        int res = CommandBridge.executeCommand("/testmod", new Object());
        assertEquals(1, res, "Command /testmod execution result should be 1");

        // Test event posting on NeoForge.EVENT_BUS
        ServerChatEvent chatEvent = new ServerChatEvent("Player1", "Hello NeoForge!");
        NeoForge.EVENT_BUS.post(chatEvent);
        assertFalse(chatEvent.isCanceled());

        chatEvent.setCanceled(true);
        assertTrue(chatEvent.isCanceled());

        // Test shutdown
        PatchNeoForgeBootstrap.shutdown();
        assertTrue(PatchNeoForgeBootstrap.getLoadedMods().isEmpty());
    }
}
