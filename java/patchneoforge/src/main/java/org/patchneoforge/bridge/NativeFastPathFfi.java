package org.patchneoforge.bridge;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NativeFastPathFfi {

    private static final Logger LOGGER = LoggerFactory.getLogger(NativeFastPathFfi.class);
    private static volatile MethodHandle setBlockStateHandle;
    private static volatile MethodHandle getBlockStateHandle;

    private NativeFastPathFfi() {}

    public static void registerSetBlockState(long address) {
        if (address == 0) return;
        try {
            MemorySegment target = MemorySegment.ofAddress(address);
            setBlockStateHandle = Linker.nativeLinker().downcallHandle(
                    target,
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_BOOLEAN,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT
                    )
            );
            LOGGER.info("[PatchNeoForge] Registered fast-path setBlockState callback");
        } catch (Throwable t) {
            LOGGER.error("[PatchNeoForge] Failed to register setBlockState handle", t);
        }
    }

    public static boolean setBlockState(long uuidHigh, long uuidLow, int x, int y, int z, int blockStateId) {
        MethodHandle mh = setBlockStateHandle;
        if (mh == null) return false;
        try {
            return (boolean) mh.invokeExact(uuidHigh, uuidLow, x, y, z, blockStateId);
        } catch (Throwable t) {
            LOGGER.error("[PatchNeoForge] Fast-path setBlockState invocation failed", t);
            return false;
        }
    }
}
