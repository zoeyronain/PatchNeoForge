# Architecture of PatchNeoForge

PatchNeoForge is a native plugin for [PumpkinMC](https://pumpkinmc.org/) that enables NeoForge mods to run seamlessly on the Rust-based Minecraft server. It bridges Pumpkin's high-performance Rust core with Java's NeoForge ecosystem by embedding a JVM and implementing the server-side NeoForge runtime, mod loading specifications, and event bus mechanics according to [docs.neoforged.net](https://docs.neoforged.net/).

The project consists of two core components:
1. **A Rust dynamic library (`cdylib`)**: The native PumpkinMC plugin that controls lifecycle, hooks Pumpkin events, hosts the embedded JVM, and exposes native callbacks.
2. **A Java library (`patchneoforge.jar`)**: The NeoForge API implementation and mod loader bootstrap running inside the JVM.

They communicate via JNI and high-speed Panama Foreign Function and Memory (FFM) with Protocol Buffer serialization and direct fast paths.

---

## 1. NeoForge Mod Lifecycle

NeoForge mods adhere to a structured lifecycle specified in NeoForge documentation. PatchNeoForge faithfully emulates this lifecycle in authentic order:

```
[Discovery]
    │  Scan patchneoforge/mods/*.jar
    │  Parse META-INF/neoforge.mods.toml
    ▼
[Mod Instantiation]
    │  Create ModContainer & per-mod IEventBus (modEventBus)
    │  Invoke @Mod("<modId>") constructor (with IEventBus injection)
    │  Scan & register @EventBusSubscriber classes
    ▼
[Mod Event Bus Lifecycle]
    │  1. FMLConstructModEvent
    │  2. RegisterEvent (DeferredRegister hooks execute here)
    │  3. FMLCommonSetupEvent (enqueueWork synchronous tasks run)
    │  4. FMLDedicatedServerSetupEvent
    │  5. FMLLoadCompleteEvent
    ▼
[Game Event Bus Lifecycle (NeoForge.EVENT_BUS)]
    │  1. ServerAboutToStartEvent
    │  2. ServerStartingEvent
    │  3. RegisterCommandsEvent (Brigadier commands registered & synced to Pumpkin)
    │  4. ServerStartedEvent
    │  ── Active Gameplay Phase ──
    │  5. ServerStoppingEvent
    │  6. ServerStoppedEvent
```

### Mod Event Bus vs Game Event Bus
As defined by NeoForge:
- **Mod Event Bus (`IEventBus`)**: Used exclusively for initialization and startup lifecycle events (`FMLCommonSetupEvent`, `RegisterEvent`, etc.). Each mod receives its own bus instance injected into its constructor or accessible via `ModLoadingContext`.
- **Game Event Bus (`NeoForge.EVENT_BUS`)**: Used for runtime server lifecycle and gameplay events (`ServerStartingEvent`, `RegisterCommandsEvent`, player interaction, block events, server ticks). Events on this bus execute synchronously with world state.

---

## 2. Server Runtime Acquisition

PatchNeoForge adheres to the principle of never redistributing proprietary Mojang or NeoForge assets:
1. On first launch, `rust/src/neoforge.rs` checks `patchneoforge/cache/neoforge/`.
2. If not already present, it downloads the official NeoForge installer `.jar` matching the configured version (e.g., `21.1.106`) from `https://maven.neoforged.net/releases/net/neoforged/neoforge/<version>/neoforge-<version>-installer.jar`.
3. It runs `java -jar neoforge-installer.jar --installServer` headlessly within the cache directory to generate libraries and runtime arguments (`unix_args.txt` / module path / classpath).
4. All verified runtime libraries are placed on the embedded JVM classpath / module layer along with `patchneoforge.jar`.

---

## 3. Communication Bridge: Rust ⇄ Java

The communication bridge uses two primary mechanisms:
1. **Panama FFM (Foreign Function and Memory API) & Protobuf**: High-performance downcalls and upcalls without manual JNI wrappers.
   - `proto/patchneoforge/` defines service methods for block queries, entity manipulation, commands, player notifications, and event payloads.
   - `java/protoc-gen-ffi` generates typed Java wrappers (`NativeBridgeFfi`).
2. **Direct Fast-Path FFM**: Zero-copy native method calls for hot gameplay operations (block state updates, player coordinate queries).

### Concurrency and Thread Safety
- The JVM is kept on its own dedicated worker thread (`JvmWorker`) using a reentrant lock (`ReentrantJvmLock`).
- Async events (like chat or pre-login) execute concurrently without holding the primary server lock.
- Synchronous gameplay events (block breaks, player interactions, command execution) acquire the lock to guarantee deterministic single-threaded state consistency with Minecraft conventions.

---

## 4. Architectural Principles: Zero Mod Hacks

1. **Zero Mod-Specific Branches**: Never write code checking for specific mod IDs or class names in runtime logic (`if (modId.equals("foo"))`).
2. **Authentic Lifecycle Contracts**: Implement standard NeoForge specifications generically. When a mod encounters an issue, resolve the underlying API contract deviation rather than writing a custom shim.
3. **Generic Command Interception**: Commands registered through Brigadier via `RegisterCommandsEvent` are generically translated to Pumpkin's command tree, allowing tab completion and execution seamlessly.
4. **Isolated Test Suites**: Mod-specific testing occurs exclusively in test environments (`tests/`, `patchneoforge-test-mod`), never leaking into production bridges.
