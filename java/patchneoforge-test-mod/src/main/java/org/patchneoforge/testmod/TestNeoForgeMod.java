package org.patchneoforge.testmod;

import net.minecraft.commands.Commands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("testmod")
public class TestNeoForgeMod {

    public static final String MODID = "testmod";
    private static final Logger LOGGER = LoggerFactory.getLogger(TestNeoForgeMod.class);

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public TestNeoForgeMod(IEventBus modEventBus) {
        LOGGER.info("[TestNeoForgeMod] Initializing TestNeoForgeMod constructor");

        // Register deferred registers
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);

        // Register mod bus listener
        modEventBus.addListener(this::commonSetup);

        // Register to game event bus
        NeoForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[TestNeoForgeMod] FMLCommonSetupEvent received");
        event.enqueueWork(() -> {
            LOGGER.info("[TestNeoForgeMod] Thread-safe common setup work executed!");
        });
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[TestNeoForgeMod] ServerStartingEvent received on NeoForge.EVENT_BUS!");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LOGGER.info("[TestNeoForgeMod] Registering /testmod command with Brigadier");
        event.getDispatcher().register(
                Commands.literal("testmod")
                        .executes(context -> {
                            LOGGER.info("[TestNeoForgeMod] /testmod executed successfully!");
                            return 1;
                        })
        );
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        LOGGER.info("[TestNeoForgeMod] Player logged in: {}", event.getEntity().getName().getString());
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        LOGGER.info("[TestNeoForgeMod] Block broken at {} by {}",
                event.getPos(), event.getPlayer().getName().getString());
    }
}
