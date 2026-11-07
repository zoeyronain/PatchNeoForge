# PatchNeoForge

A plugin for [PumpkinMC](https://pumpkinmc.org/) that adds support for [NeoForge](https://neoforged.net/) mods (Minecraft 1.21.1+).

Guided by the official NeoForge documentation at [docs.neoforged.net](https://docs.neoforged.net/).

## Features

- **NeoForge Mod Loading**: Discovers and loads mods packaged with `META-INF/neoforge.mods.toml`.
- **Authentic Lifecycle Emulation**: Executes the complete NeoForge mod lifecycle:
  - Mod class instantiation via `@Mod("mod_id")`
  - Automated `@EventBusSubscriber` annotation scanning and listener registration
  - Early lifecycle events: `FMLConstructModEvent`
  - Registry events: `RegisterEvent` and `DeferredRegister` support (blocks, items, entities, etc.)
  - Common setup: `FMLCommonSetupEvent` with thread-safe `enqueueWork(...)` support
  - Dedicated server setup: `FMLDedicatedServerSetupEvent`
  - Load completion: `FMLLoadCompleteEvent`
  - Server lifecycle: `ServerAboutToStartEvent`, `ServerStartingEvent`, `ServerStartedEvent`
  - Server shutdown: `ServerStoppingEvent`, `ServerStoppedEvent`
- **Brigadier Command Registration**: Syncs commands registered by mods via `RegisterCommandsEvent` with Pumpkin's command dispatcher.
- **Gameplay Event Bridge**: Bi-directional event dispatching on `NeoForge.EVENT_BUS`:
  - `PlayerEvent.PlayerLoggedInEvent`, `PlayerEvent.PlayerLoggedOutEvent`
  - `PlayerInteractEvent.RightClickBlock`, `PlayerInteractEvent.LeftClickBlock`
  - `BlockEvent.BreakEvent`, `BlockEvent.EntityPlaceEvent`
  - `ServerChatEvent`
  - `ServerTickEvent.Pre`, `ServerTickEvent.Post`
- **Zero-Hack Architecture**: Pure capability and contract emulation without plugin- or mod-specific shims.
- **Embedded JVM Bridge**: Low-overhead communication using Panama FFM (Foreign Function & Memory API) and Protocol Buffers.
- **Automatic Runtime Acquisition**: Downloads and prepares the NeoForge server installer and runtime into the cache directory without redistributing proprietary assets.

## Installation

1. **Requirement**: Install Java 21 or newer (Java 25 supported).
2. **Deploy**: Build or place `libpatchneoforge.so` (or the `.dll` on Windows / `.dylib` on macOS) into your PumpkinMC `plugins/` directory.
3. **Initialize**: Start PumpkinMC once. This automatically creates the `patchneoforge/` directory in your server root with configuration and subdirectories.
4. **Add Mods**: Drop your NeoForge `.jar` mod files into `patchneoforge/mods/` (or `plugins/data/patchneoforge/mods/`).
5. **Restart**: Start PumpkinMC. Your NeoForge mods will now load and run!

## Directory Layout

```
server-root/
└── patchneoforge/
    ├── patchneoforge.config.toml    # Server and loader configuration
    ├── mods/                         # Drop NeoForge .jar mods here
    ├── config/                       # Mod configuration files (.toml)
    ├── jassets/                      # Embedded runtime JARs (patchneoforge.jar)
    └── cache/
        └── neoforge/                 # Downloaded NeoForge server libraries
```

## Development & Building

1. Ensure Java 21+ and Rust (with Cargo) are installed.
2. Run `./build.sh` (or `./build.sh --release` for optimized builds).
3. The build script compiles both the Java runtime (`patchneoforge.jar`) and the Rust native plugin (`libpatchneoforge.so`), and copies them to your Pumpkin server directory if found.

For in-depth architectural details, see [ARCHITECTURE.md](ARCHITECTURE.md).
