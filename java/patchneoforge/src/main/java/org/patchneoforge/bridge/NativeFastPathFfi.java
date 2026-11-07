package org.patchneoforge.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zero-allocation primitive Panama FFM fast-path bridge for hot-path operations.
 */
public final class NativeFastPathFfi {

    private static final Logger LOGGER = LoggerFactory.getLogger(NativeFastPathFfi.class);
    private static final Linker LINKER = Linker.nativeLinker();

    private static MethodHandle getBlockStateIdHandle;
    private static MethodHandle setBlockStateIdHandle;
    private static MethodHandle teleportEntityHandle;
    private static MethodHandle setEntityVelocityHandle;
    private static MethodHandle setPacketInterceptionHandle;
    private static MethodHandle syncEntityStateHandle;
    private static MethodHandle sendRawPacketHandle;
    private static MethodHandle loadChunkHandle;
    private static MethodHandle isChunkGeneratedHandle;

    private NativeFastPathFfi() {}

    public static void init(
        long getBlockStateIdAddr,
        long setBlockStateIdAddr,
        long teleportEntityAddr,
        long setEntityVelocityAddr,
        long setPacketInterceptionAddr,
        long syncEntityStateAddr,
        long sendRawPacketAddr,
        long registerUpcallsAddr,
        long loadChunkAddr,
        long isChunkGeneratedAddr
    ) {
        try {
            if (getBlockStateIdAddr != 0L) {
                getBlockStateIdHandle = LINKER.downcallHandle(
                    MemorySegment.ofAddress(getBlockStateIdAddr),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT
                    )
                );
            }

            if (setBlockStateIdAddr != 0L) {
                setBlockStateIdHandle = LINKER.downcallHandle(
                    MemorySegment.ofAddress(setBlockStateIdAddr),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT
                    )
                );
            }

            if (teleportEntityAddr != 0L) {
                teleportEntityHandle = LINKER.downcallHandle(
                    MemorySegment.ofAddress(teleportEntityAddr),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_DOUBLE,
                        ValueLayout.JAVA_DOUBLE,
                        ValueLayout.JAVA_DOUBLE,
                        ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT
                    )
                );
            }

            if (setEntityVelocityAddr != 0L) {
                setEntityVelocityHandle = LINKER.downcallHandle(
                    MemorySegment.ofAddress(setEntityVelocityAddr),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_DOUBLE,
                        ValueLayout.JAVA_DOUBLE,
                        ValueLayout.JAVA_DOUBLE
                    )
                );
            }

            if (setPacketInterceptionAddr != 0L) {
                setPacketInterceptionHandle = LINKER.downcallHandle(
                    MemorySegment.ofAddress(setPacketInterceptionAddr),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT)
                );
            }

            if (syncEntityStateAddr != 0L) {
                syncEntityStateHandle = LINKER.downcallHandle(
                    MemorySegment.ofAddress(syncEntityStateAddr),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS
                    )
                );
            }

            if (sendRawPacketAddr != 0L) {
                sendRawPacketHandle = LINKER.downcallHandle(
                    MemorySegment.ofAddress(sendRawPacketAddr),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT
                    )
                );
            }

            if (loadChunkAddr != 0L) {
                loadChunkHandle = LINKER.downcallHandle(
                    MemorySegment.ofAddress(loadChunkAddr),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT
                    )
                );
            }

            if (isChunkGeneratedAddr != 0L) {
                isChunkGeneratedHandle = LINKER.downcallHandle(
                    MemorySegment.ofAddress(isChunkGeneratedAddr),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT
                    )
                );
            }

            if (registerUpcallsAddr != 0L) {
                MethodHandle registerUpcallsHandle = LINKER.downcallHandle(
                    MemorySegment.ofAddress(registerUpcallsAddr),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
                );

                MethodHandles.Lookup lookup = MethodHandles.lookup();
                MethodHandle packetTarget = lookup.findStatic(
                    NativeFastPathFfi.class,
                    "handlePacketUpcall",
                    MethodType.methodType(
                        int.class,
                        long.class,
                        long.class,
                        int.class,
                        long.class,
                        int.class,
                        int.class
                    )
                );
                MemorySegment packetUpcallStub = LINKER.upcallStub(
                    packetTarget,
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT
                    ),
                    Arena.global()
                );

                MethodHandle heartbeatTarget = lookup.findStatic(
                    NativeFastPathFfi.class,
                    "onServerTick",
                    MethodType.methodType(void.class)
                );
                MemorySegment heartbeatUpcallStub = LINKER.upcallStub(
                    heartbeatTarget,
                    FunctionDescriptor.ofVoid(),
                    Arena.global()
                );

                registerUpcallsHandle.invokeExact(
                    packetUpcallStub.address(),
                    heartbeatUpcallStub.address()
                );
            }

            LOGGER.info("[PatchNeoForge] NativeFastPathFfi initialized successfully");
        } catch (Throwable t) {
            LOGGER.error("[PatchNeoForge FastFFI] Failed init: " + t.getMessage(), t);
        }
    }

    public static int handlePacketUpcall(
        long playerMsb,
        long playerLsb,
        int packetId,
        long payloadAddr,
        int payloadLen,
        int isOutbound
    ) {
        return 0; // 0 = not intercepted
    }

    public static void onServerTick() {
        // NeoForge server tick hook
    }

    public static boolean isInitialized() {
        return getBlockStateIdHandle != null;
    }

    public static int getBlockStateId(long worldMsb, long worldLsb, int x, int y, int z) {
        MethodHandle h = getBlockStateIdHandle;
        if (h == null) return -1;
        try {
            return (int) h.invokeExact(worldMsb, worldLsb, x, y, z);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean setBlockStateId(
        long worldMsb,
        long worldLsb,
        int x,
        int y,
        int z,
        int blockStateId,
        int flags
    ) {
        MethodHandle h = setBlockStateIdHandle;
        if (h == null) return false;
        try {
            int res = (int) h.invokeExact(worldMsb, worldLsb, x, y, z, blockStateId, flags);
            return res != 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
