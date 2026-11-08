package org.patchneoforge.bootstrap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class CommandBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandBridge.class);
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final CommandDispatcher<net.minecraft.commands.CommandSourceStack> DISPATCHER = (CommandDispatcher) new CommandDispatcher<>();
    private static final Set<String> REGISTERED_COMMANDS = Collections.synchronizedSet(new HashSet<>());

    private CommandBridge() {}

    public static CommandDispatcher<net.minecraft.commands.CommandSourceStack> getDispatcher() {
        return DISPATCHER;
    }

    public static void syncWithPumpkin() {
        for (CommandNode<?> node : DISPATCHER.getRoot().getChildren()) {
            String name = node.getName();
            if (REGISTERED_COMMANDS.add(name)) {
                LOGGER.info("[PatchNeoForge] Syncing NeoForge command '{}' with Pumpkin", name);
                try {
                    var req = patchneoforge.command.RegisterCommandRequest.newBuilder()
                            .setCmdName(name)
                            .setDescription("NeoForge mod command: " + name)
                            .setPluginName("PatchNeoForge")
                            .build();
                    patchneoforge.bridge.NativeBridgeFfi.registerCommand(req);
                } catch (Throwable t) {
                    LOGGER.warn("[PatchNeoForge] Native command registration for '{}' not available yet: {}", name, t.getMessage());
                }
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static int executeCommand(String commandLine, Object source) {
        try {
            if (commandLine.startsWith("/")) {
                commandLine = commandLine.substring(1);
            }
            return ((CommandDispatcher) DISPATCHER).execute(commandLine, source);
        } catch (Throwable t) {
            LOGGER.error("[PatchNeoForge] Error executing command: '{}'", commandLine, t);
            return 0;
        }
    }
}
