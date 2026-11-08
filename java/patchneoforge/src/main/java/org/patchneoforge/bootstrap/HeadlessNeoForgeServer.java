package org.patchneoforge.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;

public final class HeadlessNeoForgeServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeadlessNeoForgeServer.class);
    private static volatile boolean booted = false;
    private static volatile Instrumentation instrumentation;
    private static final java.util.Map<String, Boolean> CLASS_PRESENT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean isClassPresent(ClassLoader loader, String internalClassName) {
        if (internalClassName == null) return false;
        return CLASS_PRESENT_CACHE.computeIfAbsent(internalClassName, name -> {
            String resPath = name + ".class";
            try {
                if (loader != null && loader.getResource(resPath) != null) {
                    return true;
                }
                if (ClassLoader.getSystemResource(resPath) != null) {
                    return true;
                }
                Class.forName(name.replace('/', '.'), false, loader != null ? loader : ClassLoader.getSystemClassLoader());
                return true;
            } catch (Throwable t) {
                return false;
            }
        });
    }

    private HeadlessNeoForgeServer() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        try {
            net.bytebuddy.agent.Installer.premain(agentArgs, inst);
        } catch (Throwable ignored) {}
        setupTransformers(inst);
        System.out.println("[PatchNeoForge] Java agent premain initialized");
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        try {
            net.bytebuddy.agent.Installer.agentmain(agentArgs, inst);
        } catch (Throwable ignored) {}
        setupTransformers(inst);
        System.out.println("[PatchNeoForge] Java agent agentmain initialized");
    }

    private static void setupTransformers(Instrumentation inst) {
        if (inst == null) return;
        try {
            inst.addTransformer(new java.lang.instrument.ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                       java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                    if (className != null && (className.startsWith("net/minecraft/")
                            || "io/wispforest/accessories/compat/config/AccessoriesConfig".equals(className)
                            || "net/neoforged/fml/common/asm/enumextension/EnumProxy".equals(className)
                            || "net/neoforged/neoforge/common/ModConfigSpec$ConfigValue".equals(className)
                            || "com/anthonyhilyard/iceberg/neoforge/config/ConfigValueWrapper".equals(className)
                            || "org/embeddedt/modernfix/core/ModernFixMixinPlugin".equals(className)
                            || "dev/latvian/mods/rhino/ContextFactory".equals(className)
                            || "net/neoforged/fml/javafmlmod/FMLModContainer".equals(className)
                            || "net/neoforged/neoforge/event/EventHooks".equals(className)
                            || "technology/roughness/whitenoise/config/WhiteNoiseConfigSpec$ConfigValue".equals(className)
                            || "com/yanny/ali/NeoForgeServerBusSubscriber".equals(className)
                            || "dev/architectury/registry/registries/DeferredRegister$Entry".equals(className)
                            || "net/mehvahdjukaar/moonlight/api/fluids/FluidContainerList$Category".equals(className)
                            || "com/cobblemon/mod/common/pokemon/helditem/CobblemonHeldItemManager".equals(className)
                            || "dev/ftb/mods/ftbquests/quest/QuestObjectBase".equals(className)
                            || "io/github/lightman314/lightmanscurrency/api/money/coins/data/ChainData".equals(className)
                            || "net/neoforged/neoforge/registries/DeferredHolder".equals(className))) {
                        if (className.contains("MobCategory") || className.contains("EnumProxy") || className.contains("ModernFixMixinPlugin")) {
                            System.out.println("[PatchNeoForge] TRANSFORM CALLED for: " + className + ", classBeingRedefined=" + classBeingRedefined);
                        }
                        try {
                            net.bytebuddy.jar.asm.ClassReader cr = new net.bytebuddy.jar.asm.ClassReader(classfileBuffer);
                            net.bytebuddy.jar.asm.ClassWriter cw = new net.bytebuddy.jar.asm.ClassWriter(cr, 0);
                            final boolean[] isInterface = new boolean[1];
                            final boolean[] isEnum = new boolean[1];
                            net.bytebuddy.jar.asm.ClassVisitor cv = new net.bytebuddy.jar.asm.ClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, cw) {
                                @Override
                                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                                    int newAccess = access;
                                    isInterface[0] = (access & net.bytebuddy.jar.asm.Opcodes.ACC_INTERFACE) != 0;
                                    isEnum[0] = (access & net.bytebuddy.jar.asm.Opcodes.ACC_ENUM) != 0;
                                    if (classBeingRedefined == null && !isInterface[0] && !isEnum[0]) {
                                        newAccess = (access & ~net.bytebuddy.jar.asm.Opcodes.ACC_FINAL & ~net.bytebuddy.jar.asm.Opcodes.ACC_PRIVATE & ~net.bytebuddy.jar.asm.Opcodes.ACC_PROTECTED) | net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC;
                                    }

                                    java.util.List<String> effectiveInterfaces = new java.util.ArrayList<>();
                                    if (interfaces != null) {
                                        java.util.Collections.addAll(effectiveInterfaces, interfaces);
                                    }
                                    if (classBeingRedefined == null) {
                                        if ("net/minecraft/world/level/block/FireBlock".equals(className)) {
                                            if (isClassPresent(loader, "com/cobblemon/mod/common/mixin/invoker/FireBlockInvoker")) {
                                                effectiveInterfaces.add("com/cobblemon/mod/common/mixin/invoker/FireBlockInvoker");
                                            }
                                        } else if ("net/minecraft/world/level/levelgen/structure/pools/StructureTemplatePool".equals(className)) {
                                            if (isClassPresent(loader, "com/cobblemon/mod/common/mixin/StructurePoolAccessor")) {
                                                effectiveInterfaces.add("com/cobblemon/mod/common/mixin/StructurePoolAccessor");
                                            }
                                        } else if ("io/wispforest/accessories/compat/config/AccessoriesConfig".equals(className)) {
                                            if (isClassPresent(loader, "io/wispforest/accessories/mixin/owo/ConfigWrapperAccessor")) {
                                                effectiveInterfaces.add("io/wispforest/accessories/mixin/owo/ConfigWrapperAccessor");
                                            }
                                        } else if ("net/minecraft/world/entity/MobCategory".equals(className)) {
                                            if (isClassPresent(loader, "me/wesley1808/servercore/common/interfaces/IMobCategory")) {
                                                effectiveInterfaces.add("me/wesley1808/servercore/common/interfaces/IMobCategory");
                                                System.out.println("[PatchNeoForge] Injected IMobCategory interface on MobCategory!");
                                            }
                                        } else if ("net/minecraft/world/item/Item".equals(className)) {
                                            if (isClassPresent(loader, "dev/ftb/mods/ftblibrary/core/ItemFTBL")) {
                                                effectiveInterfaces.add("dev/ftb/mods/ftblibrary/core/ItemFTBL");
                                            }
                                            if (isClassPresent(loader, "net/mehvahdjukaar/moonlight/core/misc/IExtendedItem")) {
                                                effectiveInterfaces.add("net/mehvahdjukaar/moonlight/core/misc/IExtendedItem");
                                            }
                                        } else if ("net/minecraft/server/dedicated/DedicatedServer".equals(className)
                                                || "net/minecraft/server/MinecraftServer".equals(className)) {
                                            if (isClassPresent(loader, "xaero/common/server/IMinecraftServer")) {
                                                effectiveInterfaces.add("xaero/common/server/IMinecraftServer");
                                            }
                                            if (isClassPresent(loader, "xaero/map/server/IMinecraftServer")) {
                                                effectiveInterfaces.add("xaero/map/server/IMinecraftServer");
                                            }
                                            if (isClassPresent(loader, "me/wesley1808/servercore/common/interfaces/IMinecraftServer")) {
                                                effectiveInterfaces.add("me/wesley1808/servercore/common/interfaces/IMinecraftServer");
                                            }
                                        }
                                    }

                                    super.visit(version, newAccess, name, signature, superName, effectiveInterfaces.toArray(new String[0]));
                                }

                                @Override
                                public net.bytebuddy.jar.asm.FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                                    int newAccess = access;
                                    if (!isInterface[0]) {
                                        newAccess = access & ~net.bytebuddy.jar.asm.Opcodes.ACC_FINAL;
                                        if (!isEnum[0]) {
                                            newAccess = (newAccess & ~net.bytebuddy.jar.asm.Opcodes.ACC_PRIVATE & ~net.bytebuddy.jar.asm.Opcodes.ACC_PROTECTED) | net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC;
                                        }
                                    }
                                    return super.visitField(newAccess, name, descriptor, signature, value);
                                }

                                @Override
                                public void visitEnd() {
                                    if ("net/minecraft/world/level/block/FireBlock".equals(className)) {
                                        net.bytebuddy.jar.asm.MethodVisitor mv = super.visitMethod(
                                                net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC,
                                                "registerNewFlammableBlock",
                                                "(Lnet/minecraft/world/level/block/Block;II)V", null, null);
                                        mv.visitCode();
                                        mv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                        mv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 1);
                                        mv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ILOAD, 2);
                                        mv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ILOAD, 3);
                                        mv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKEVIRTUAL,
                                                "net/minecraft/world/level/block/FireBlock",
                                                "setFlammable",
                                                "(Lnet/minecraft/world/level/block/Block;II)V", false);
                                        mv.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                        mv.visitMaxs(4, 4);
                                        mv.visitEnd();
                                    } else if ("net/minecraft/world/level/levelgen/structure/pools/StructureTemplatePool".equals(className)) {
                                        net.bytebuddy.jar.asm.MethodVisitor mv1 = super.visitMethod(
                                                net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC,
                                                "getElementCounts",
                                                "()Ljava/util/List;", null, null);
                                        mv1.visitCode();
                                        mv1.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                        mv1.visitFieldInsn(net.bytebuddy.jar.asm.Opcodes.GETFIELD,
                                                "net/minecraft/world/level/levelgen/structure/pools/StructureTemplatePool",
                                                "rawTemplates",
                                                "Ljava/util/List;");
                                        mv1.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                        mv1.visitMaxs(1, 1);
                                        mv1.visitEnd();

                                        net.bytebuddy.jar.asm.MethodVisitor mv2 = super.visitMethod(
                                                net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC,
                                                "setElementCounts",
                                                "(Ljava/util/List;)V", null, null);
                                        mv2.visitCode();
                                        mv2.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                        mv2.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 1);
                                        mv2.visitFieldInsn(net.bytebuddy.jar.asm.Opcodes.PUTFIELD,
                                                "net/minecraft/world/level/levelgen/structure/pools/StructureTemplatePool",
                                                "rawTemplates",
                                                "Ljava/util/List;");
                                        mv2.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                        mv2.visitMaxs(2, 2);
                                        mv2.visitEnd();

                                        net.bytebuddy.jar.asm.MethodVisitor mv3 = super.visitMethod(
                                                net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC,
                                                "getElements",
                                                "()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;", null, null);
                                        mv3.visitCode();
                                        mv3.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                        mv3.visitFieldInsn(net.bytebuddy.jar.asm.Opcodes.GETFIELD,
                                                "net/minecraft/world/level/levelgen/structure/pools/StructureTemplatePool",
                                                "templates",
                                                "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;");
                                        mv3.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                        mv3.visitMaxs(1, 1);
                                        mv3.visitEnd();
                                    } else if ("io/wispforest/accessories/compat/config/AccessoriesConfig".equals(className)) {
                                        net.bytebuddy.jar.asm.MethodVisitor mv = super.visitMethod(
                                                net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC,
                                                "accessories$builder",
                                                "()Lio/wispforest/endec/impl/ReflectiveEndecBuilder;", null, null);
                                        mv.visitCode();
                                        mv.visitTypeInsn(net.bytebuddy.jar.asm.Opcodes.NEW, "io/wispforest/endec/impl/ReflectiveEndecBuilder");
                                        mv.visitInsn(net.bytebuddy.jar.asm.Opcodes.DUP);
                                        mv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESPECIAL, "io/wispforest/endec/impl/ReflectiveEndecBuilder", "<init>", "()V", false);
                                        mv.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                        mv.visitMaxs(2, 1);
                                        mv.visitEnd();
                                    } else if ("net/minecraft/world/item/trading/MerchantOffer".equals(className)) {
                                        net.bytebuddy.jar.asm.FieldVisitor fv = super.visitField(
                                                net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC | net.bytebuddy.jar.asm.Opcodes.ACC_STATIC,
                                                "cODEC",
                                                "Lcom/mojang/serialization/Codec;",
                                                null, null);
                                        if (fv != null) fv.visitEnd();
                                    } else if ("net/minecraft/world/item/Item$Properties".equals(className)) {
                                        for (String desc : new String[]{
                                                "(Ldev/architectury/registry/registries/DeferredSupplier;)Lnet/minecraft/world/item/Item$Properties;",
                                                "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/world/item/Item$Properties;",
                                                "(Ljava/lang/Object;)Lnet/minecraft/world/item/Item$Properties;"}) {
                                            net.bytebuddy.jar.asm.MethodVisitor mv = super.visitMethod(
                                                    net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "arch$tab", desc, null, null);
                                            mv.visitCode();
                                            mv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                            mv.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                            mv.visitMaxs(1, 2);
                                            mv.visitEnd();
                                        }
                                    } else if ("net/minecraft/world/item/Item".equals(className)) {
                                        // setCraftingRemainingItemFTBL
                                        net.bytebuddy.jar.asm.MethodVisitor mv1 = super.visitMethod(
                                                net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "setCraftingRemainingItemFTBL",
                                                "(Lnet/minecraft/world/item/Item;)V", null, null);
                                        mv1.visitCode();
                                        mv1.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                        mv1.visitMaxs(0, 2);
                                        mv1.visitEnd();

                                        // moonlight$getAdditionalBehavior
                                        net.bytebuddy.jar.asm.MethodVisitor mv2 = super.visitMethod(
                                                net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "moonlight$getAdditionalBehavior",
                                                "()Lnet/mehvahdjukaar/moonlight/api/item/additional_placements/AdditionalItemPlacement;", null, null);
                                        mv2.visitCode();
                                        mv2.visitInsn(net.bytebuddy.jar.asm.Opcodes.ACONST_NULL);
                                        mv2.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                        mv2.visitMaxs(1, 1);
                                        mv2.visitEnd();

                                        // moonlight$setAdditionalBehavior
                                        net.bytebuddy.jar.asm.MethodVisitor mv3 = super.visitMethod(
                                                net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "moonlight$setAdditionalBehavior",
                                                "(Lnet/mehvahdjukaar/moonlight/api/item/additional_placements/AdditionalItemPlacement;)V", null, null);
                                        mv3.visitCode();
                                        mv3.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                        mv3.visitMaxs(0, 2);
                                        mv3.visitEnd();

                                        // moonlight$getClientAnimationExtension
                                        net.bytebuddy.jar.asm.MethodVisitor mv4 = super.visitMethod(
                                                net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "moonlight$getClientAnimationExtension",
                                                "()Ljava/lang/Object;", null, null);
                                        mv4.visitCode();
                                        mv4.visitInsn(net.bytebuddy.jar.asm.Opcodes.ACONST_NULL);
                                        mv4.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                        mv4.visitMaxs(1, 1);
                                        mv4.visitEnd();

                                        // moonlight$setClientAnimationExtension
                                        net.bytebuddy.jar.asm.MethodVisitor mv5 = super.visitMethod(
                                                net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "moonlight$setClientAnimationExtension",
                                                "(Ljava/lang/Object;)V", null, null);
                                        mv5.visitCode();
                                        mv5.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                        mv5.visitMaxs(0, 2);
                                        mv5.visitEnd();
                                    } else if ("net/minecraft/world/entity/MobCategory".equals(className)) {
                                        // servercore$getSpawnInterval
                                        net.bytebuddy.jar.asm.MethodVisitor mv1 = super.visitMethod(
                                                net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "servercore$getSpawnInterval",
                                                "()I", null, null);
                                        mv1.visitCode();
                                        mv1.visitInsn(net.bytebuddy.jar.asm.Opcodes.ICONST_1);
                                        mv1.visitInsn(net.bytebuddy.jar.asm.Opcodes.IRETURN);
                                        mv1.visitMaxs(1, 1);
                                        mv1.visitEnd();

                                        // servercore$getOriginalCapacity
                                        net.bytebuddy.jar.asm.MethodVisitor mv2 = super.visitMethod(
                                                net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "servercore$getOriginalCapacity",
                                                "()I", null, null);
                                        mv2.visitCode();
                                        mv2.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                        mv2.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKEVIRTUAL,
                                                "net/minecraft/world/entity/MobCategory", "getMaxInstancesPerChunk", "()I", false);
                                        mv2.visitInsn(net.bytebuddy.jar.asm.Opcodes.IRETURN);
                                        mv2.visitMaxs(1, 1);
                                        mv2.visitEnd();

                                        // servercore$modifyCapacity
                                        net.bytebuddy.jar.asm.MethodVisitor mv3 = super.visitMethod(
                                                net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "servercore$modifyCapacity",
                                                "(D)V", null, null);
                                        mv3.visitCode();
                                        mv3.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                        mv3.visitMaxs(0, 3);
                                        mv3.visitEnd();

                                        // servercore$modifySpawningConfig
                                        net.bytebuddy.jar.asm.MethodVisitor mv4 = super.visitMethod(
                                                net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "servercore$modifySpawningConfig",
                                                "(II)V", null, null);
                                        mv4.visitCode();
                                        mv4.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                        mv4.visitMaxs(0, 3);
                                        mv4.visitEnd();
                                    } else if ("net/minecraft/server/dedicated/DedicatedServer".equals(className)
                                            || "net/minecraft/server/MinecraftServer".equals(className)) {
                                        if (isClassPresent(loader, "xaero/common/server/MinecraftServerData")) {
                                            // xaero.common.server.IMinecraftServer
                                            net.bytebuddy.jar.asm.MethodVisitor mvX1 = super.visitMethod(
                                                    net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "getXaeroMinimapServerData",
                                                    "()Lxaero/common/server/MinecraftServerData;", null, null);
                                            mvX1.visitCode();
                                            mvX1.visitInsn(net.bytebuddy.jar.asm.Opcodes.ACONST_NULL);
                                            mvX1.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                            mvX1.visitMaxs(1, 1);
                                            mvX1.visitEnd();

                                            net.bytebuddy.jar.asm.MethodVisitor mvX2 = super.visitMethod(
                                                    net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "setXaeroMinimapServerData",
                                                    "(Lxaero/common/server/MinecraftServerData;)V", null, null);
                                            mvX2.visitCode();
                                            mvX2.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                            mvX2.visitMaxs(0, 2);
                                            mvX2.visitEnd();
                                        }

                                        if (isClassPresent(loader, "xaero/map/server/MinecraftServerData")) {
                                            // xaero.map.server.IMinecraftServer
                                            net.bytebuddy.jar.asm.MethodVisitor mvX3 = super.visitMethod(
                                                    net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "getXaeroWorldMapServerData",
                                                    "()Lxaero/map/server/MinecraftServerData;", null, null);
                                            mvX3.visitCode();
                                            mvX3.visitInsn(net.bytebuddy.jar.asm.Opcodes.ACONST_NULL);
                                            mvX3.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                            mvX3.visitMaxs(1, 1);
                                            mvX3.visitEnd();

                                            net.bytebuddy.jar.asm.MethodVisitor mvX4 = super.visitMethod(
                                                    net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "setXaeroWorldMapServerData",
                                                    "(Lxaero/map/server/MinecraftServerData;)V", null, null);
                                            mvX4.visitCode();
                                            mvX4.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                            mvX4.visitMaxs(0, 2);
                                            mvX4.visitEnd();
                                        }

                                        if (isClassPresent(loader, "me/wesley1808/servercore/common/interfaces/IMinecraftServer")) {
                                            // me.wesley1808.servercore.common.interfaces.IMinecraftServer
                                            net.bytebuddy.jar.asm.MethodVisitor mvS1 = super.visitMethod(
                                                    net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "servercore$onStarted",
                                                    "(Lnet/minecraft/server/MinecraftServer;)V", null, null);
                                            mvS1.visitCode();
                                            mvS1.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                            mvS1.visitMaxs(0, 2);
                                            mvS1.visitEnd();

                                            if (isClassPresent(loader, "me/wesley1808/servercore/common/utils/statistics/Statistics")) {
                                                net.bytebuddy.jar.asm.MethodVisitor mvS2 = super.visitMethod(
                                                        net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "servercore$getStatistics",
                                                        "()Lme/wesley1808/servercore/common/utils/statistics/Statistics;", null, null);
                                                mvS2.visitCode();
                                                mvS2.visitInsn(net.bytebuddy.jar.asm.Opcodes.ACONST_NULL);
                                                mvS2.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                                mvS2.visitMaxs(1, 1);
                                                mvS2.visitEnd();
                                            }

                                            if (isClassPresent(loader, "me/wesley1808/servercore/common/dynamic/DynamicManager")) {
                                                net.bytebuddy.jar.asm.MethodVisitor mvS3 = super.visitMethod(
                                                        net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "servercore$getDynamicManager",
                                                        "()Lme/wesley1808/servercore/common/dynamic/DynamicManager;", null, null);
                                                mvS3.visitCode();
                                                mvS3.visitInsn(net.bytebuddy.jar.asm.Opcodes.ACONST_NULL);
                                                mvS3.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                                mvS3.visitMaxs(1, 1);
                                                mvS3.visitEnd();
                                            }
                                        }
                                    }
                                    super.visitEnd();
                                }


                                @Override
                                public net.bytebuddy.jar.asm.MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                                    int newAccess = access;
                                    if ("<init>".equals(name)) {
                                        if (!isEnum[0]) {
                                            newAccess = (access & ~net.bytebuddy.jar.asm.Opcodes.ACC_PRIVATE & ~net.bytebuddy.jar.asm.Opcodes.ACC_PROTECTED) | net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC;
                                        }
                                    } else if (!isInterface[0]) {
                                        newAccess = (access & ~net.bytebuddy.jar.asm.Opcodes.ACC_PRIVATE & ~net.bytebuddy.jar.asm.Opcodes.ACC_PROTECTED & ~net.bytebuddy.jar.asm.Opcodes.ACC_FINAL) | net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC;
                                    } else {
                                        if ((access & net.bytebuddy.jar.asm.Opcodes.ACC_PRIVATE) == 0) {
                                            newAccess = access & ~net.bytebuddy.jar.asm.Opcodes.ACC_FINAL;
                                        }
                                    }

                                    if ("net/neoforged/fml/javafmlmod/FMLModContainer".equals(className) && "<init>".equals(name)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, targetMv) {
                                            @Override
                                            public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                                                if ("net/neoforged/fml/ModLoadingContext".equals(owner) && "setActiveContainer".equals(mName)) {
                                                    super.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                            "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                            "onFMLModContainerSetActiveContainer",
                                                            "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                                } else {
                                                    super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                                                }
                                            }
                                        };
                                    }

                                    if ("net/neoforged/neoforge/registries/DeferredHolder".equals(className) && "value".equals(name) && "()Ljava/lang/Object;".equals(descriptor)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                                targetMv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                        "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                        "getDeferredHolderValue",
                                                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                                targetMv.visitMaxs(1, 1);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("net/minecraft/core/Holder$Reference".equals(className) && "value".equals(name) && "()Ljava/lang/Object;".equals(descriptor)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                                targetMv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                        "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                        "getHolderReferenceValue",
                                                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                                targetMv.visitMaxs(1, 1);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("net/neoforged/neoforge/event/EventHooks".equals(className) && "onCreativeModeTabBuildContents".equals(name)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                                targetMv.visitMaxs(0, 5);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("net/neoforged/neoforge/event/EventHooks".equals(className) && "lambda$onCreativeModeTabBuildContents$0".equals(name)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 1);
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 2);
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 3);
                                                targetMv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                        "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                        "onCreativeModeTabBuildContentsLambda",
                                                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                                targetMv.visitMaxs(4, 4);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("dev/latvian/mods/rhino/ContextFactory".equals(className) && "registerDefaultRecordProperties".equals(name)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 1);
                                                targetMv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                        "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                        "registerDefaultRecordProperties",
                                                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                                targetMv.visitMaxs(2, 2);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("net/minecraft/world/item/BlockItem".equals(className) && "requiredFeatures".equals(name) && "()Lnet/minecraft/world/flag/FeatureFlagSet;".equals(descriptor)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                                targetMv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                        "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                        "getBlockItemRequiredFeatures",
                                                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                                targetMv.visitTypeInsn(net.bytebuddy.jar.asm.Opcodes.CHECKCAST, "net/minecraft/world/flag/FeatureFlagSet");
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                                targetMv.visitMaxs(1, 1);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("org/embeddedt/modernfix/core/ModernFixMixinPlugin".equals(className) && "isOptionEnabled".equals(name)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.ICONST_0);
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.IRETURN);
                                                targetMv.visitMaxs(1, 2);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("net/neoforged/fml/common/asm/enumextension/EnumProxy".equals(className) && "getValue".equals(name)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                                targetMv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                        "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                        "resolveEnumProxy",
                                                        "(Ljava/lang/Object;)Ljava/lang/Enum;", false);
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                                targetMv.visitMaxs(1, 1);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("net/neoforged/neoforge/common/ModConfigSpec$ConfigValue".equals(className) && "getRaw".equals(name) && "()Ljava/lang/Object;".equals(descriptor)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                                targetMv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                        "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                        "getConfigValueRaw",
                                                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                                targetMv.visitMaxs(1, 1);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("com/anthonyhilyard/iceberg/neoforge/config/ConfigValueWrapper".equals(className) && "get".equals(name) && "()Ljava/lang/Object;".equals(descriptor)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                                targetMv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                        "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                        "getConfigValueWrapperGet",
                                                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                                targetMv.visitMaxs(1, 1);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("technology/roughness/whitenoise/config/WhiteNoiseConfigSpec$ConfigValue".equals(className) && "get".equals(name) && "()Ljava/lang/Object;".equals(descriptor)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                                targetMv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                        "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                        "getWhiteNoiseConfigValue",
                                                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                                targetMv.visitMaxs(1, 1);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("com/yanny/ali/NeoForgeServerBusSubscriber".equals(className)
                                            && ("onServerStarting".equals(name) || "onPlayerLogIn".equals(name))) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                                targetMv.visitMaxs(1, 1);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("dev/architectury/registry/registries/DeferredRegister$Entry".equals(className) && "get".equals(name) && "()Ljava/lang/Object;".equals(descriptor)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                                targetMv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                        "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                        "getArchitecturyEntry",
                                                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                                targetMv.visitMaxs(1, 1);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("net/mehvahdjukaar/moonlight/api/fluids/FluidContainerList$Category".equals(className)
                                            && "addItem".equals(name) && "(Lnet/minecraft/world/item/Item;)V".equals(descriptor)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 1);
                                                targetMv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                        "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                        "moonlightCategoryAddItem",
                                                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                                targetMv.visitMaxs(2, 2);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("com/cobblemon/mod/common/pokemon/helditem/CobblemonHeldItemManager".equals(className)
                                            && "registerRemap".equals(name) && "(Lnet/minecraft/world/item/Item;Ljava/lang/String;)V".equals(descriptor)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 1);
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 2);
                                                targetMv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                        "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                        "cobblemonRegisterRemap",
                                                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V", false);
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                                targetMv.visitMaxs(3, 3);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("dev/ftb/mods/ftbquests/quest/QuestObjectBase".equals(className)
                                            && "createMissing".equals(name) && "(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/item/ItemStack;".equals(descriptor)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                                targetMv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                        "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                        "ftbQuestsCreateMissing",
                                                        "(Ljava/lang/Object;)Lnet/minecraft/world/item/ItemStack;", false);
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                                targetMv.visitMaxs(1, 1);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("io/github/lightman314/lightmanscurrency/api/money/coins/data/ChainData".equals(className)
                                            && "validateNoDuplicateCoins".equals(name)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN);
                                                targetMv.visitMaxs(0, 2);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if (isEnum[0] && className != null && className.startsWith("net/minecraft/")
                                            && "valueOf".equals(name) && ("(Ljava/lang/String;)L" + className + ";").equals(descriptor)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitLdcInsn(net.bytebuddy.jar.asm.Type.getObjectType(className));
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                                targetMv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                        "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                        "getOrCreateEnum",
                                                        "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
                                                targetMv.visitTypeInsn(net.bytebuddy.jar.asm.Opcodes.CHECKCAST, className);
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                                targetMv.visitMaxs(2, 1);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    if ("net/minecraft/server/level/ServerChunkCache".equals(className) && "getChunk".equals(name)
                                            && "(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;".equals(descriptor)) {
                                        final net.bytebuddy.jar.asm.MethodVisitor targetMv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                        return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, null) {
                                            @Override
                                            public void visitEnd() {
                                                targetMv.visitCode();
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ILOAD, 1);
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ILOAD, 2);
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 3);
                                                targetMv.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ILOAD, 4);
                                                targetMv.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC,
                                                        "org/patchneoforge/bootstrap/HeadlessNeoForgeServer",
                                                        "getChunkOrEmpty",
                                                        "(Ljava/lang/Object;IILjava/lang/Object;Z)Ljava/lang/Object;", false);
                                                targetMv.visitTypeInsn(net.bytebuddy.jar.asm.Opcodes.CHECKCAST, "net/minecraft/world/level/chunk/ChunkAccess");
                                                targetMv.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN);
                                                targetMv.visitMaxs(5, 5);
                                                targetMv.visitEnd();
                                            }
                                        };
                                    }

                                    return super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                }
                            };
                            cr.accept(cv, 0);
                            return cw.toByteArray();
                        } catch (Throwable t) {
                            System.err.println("[PatchNeoForge] FAILED TRANSFORM for " + className + ": " + t);
                            t.printStackTrace();
                        }
                    }
                    return null;
                }
            }, true);
            inst.addTransformer(new MixinInvokerTransformer(), true);
            inst.addTransformer(new RuntimeDistCleanerTransformer(), true);

            for (Class<?> clazz : inst.getAllLoadedClasses()) {
                String name = clazz.getName();
                if (name.equals("net.neoforged.fml.common.asm.enumextension.EnumProxy")
                        || name.equals("net.neoforged.neoforge.common.ModConfigSpec$ConfigValue")
                        || name.equals("technology.roughness.whitenoise.config.WhiteNoiseConfigSpec$ConfigValue")
                        || name.equals("com.yanny.ali.NeoForgeServerBusSubscriber")
                        || name.equals("net.minecraft.world.entity.MobCategory")
                        || name.equals("net.minecraft.world.item.Item")
                        || name.equals("org.embeddedt.modernfix.core.ModernFixMixinPlugin")
                        || name.startsWith("net.minecraft.")) {
                    try {
                        inst.retransformClasses(clazz);
                    } catch (Throwable ignored) {}
                }
            }

            System.out.println("[PatchNeoForge] Bytecode transformers registered successfully");
        } catch (Throwable t) {
            System.err.println("[PatchNeoForge] Failed to register bytecode transformer: " + t.getMessage());
        }
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static boolean isBooted() {
        return booted;
    }

    public static boolean boot(String runtimeDirPath) {
        LOGGER.info("[PatchNeoForge] Booting Headless NeoForge Server runtime in: {}", runtimeDirPath);
        if (instrumentation == null) {
            try {
                instrumentation = net.bytebuddy.agent.ByteBuddyAgent.install();
                setupTransformers(instrumentation);
                LOGGER.info("[PatchNeoForge] ByteBuddyAgent installed and transformers registered successfully!");
            } catch (Throwable t) {
                LOGGER.error("[PatchNeoForge] Failed to install ByteBuddyAgent: {}", t.getMessage(), t);
            }
        }
        booted = true;
        return true;
    }

    public static synchronized Enum<?> getOrCreateEnum(Class<?> enumClass, String name) {
        if (name == null || enumClass == null) return null;
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Enum<?> existing = Enum.valueOf((Class<Enum>) enumClass, name);
            if (existing != null) return existing;
        } catch (IllegalArgumentException ignored) {}

        try {
            java.lang.reflect.Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe u = (sun.misc.Unsafe) unsafeField.get(null);

            Enum<?> newConstant = (Enum<?>) u.allocateInstance(enumClass);

            java.lang.reflect.Field valuesField = null;
            for (java.lang.reflect.Field f : enumClass.getDeclaredFields()) {
                if (f.getName().equals("$VALUES") || (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType().isArray() && f.getType().getComponentType() == enumClass)) {
                    valuesField = f;
                    break;
                }
            }

            Object[] oldValues = null;
            if (valuesField != null) {
                Object base = u.staticFieldBase(valuesField);
                long offset = u.staticFieldOffset(valuesField);
                oldValues = (Object[]) u.getObject(base, offset);
            }
            int ordinal = oldValues != null ? oldValues.length : 0;

            java.lang.reflect.Field nameField = Enum.class.getDeclaredField("name");
            long nameOffset = u.objectFieldOffset(nameField);
            u.putObject(newConstant, nameOffset, name);

            java.lang.reflect.Field ordinalField = Enum.class.getDeclaredField("ordinal");
            long ordOffset = u.objectFieldOffset(ordinalField);
            u.putInt(newConstant, ordOffset, ordinal);

            for (java.lang.reflect.Field f : enumClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                long off = u.objectFieldOffset(f);
                if (f.getType() == String.class) {
                    u.putObject(newConstant, off, name.toLowerCase(java.util.Locale.ROOT));
                } else if (f.getType() == int.class) {
                    u.putInt(newConstant, off, ordinal);
                } else if (f.getType() == boolean.class) {
                    u.putBoolean(newConstant, off, true);
                }
            }

            if (valuesField != null && oldValues != null) {
                Object[] newValues = (Object[]) java.lang.reflect.Array.newInstance(enumClass, oldValues.length + 1);
                System.arraycopy(oldValues, 0, newValues, 0, oldValues.length);
                newValues[oldValues.length] = newConstant;

                Object base = u.staticFieldBase(valuesField);
                long offset = u.staticFieldOffset(valuesField);
                u.putObject(base, offset, newValues);
            }

            for (java.lang.reflect.Field f : Class.class.getDeclaredFields()) {
                if ("enumConstantDirectory".equals(f.getName()) || "enumConstants".equals(f.getName())) {
                    long off = u.objectFieldOffset(f);
                    u.putObject(enumClass, off, null);
                }
            }

            System.out.println("[PatchNeoForge] Dynamically created enum constant " + enumClass.getName() + "." + name);
            return newConstant;
        } catch (Throwable t) {
            System.err.println("[PatchNeoForge] Failed to dynamically create enum constant " + enumClass.getName() + "." + name + ": " + t);
            return null;
        }
    }

    public static Enum<?> resolveEnumProxy(Object proxy) {
        if (proxy == null) return null;
        try {
            java.lang.reflect.Field fVal = proxy.getClass().getDeclaredField("enumValue");
            fVal.setAccessible(true);
            Enum<?> val = (Enum<?>) fVal.get(proxy);
            if (val != null) return val;

            java.lang.reflect.Field fClass = proxy.getClass().getDeclaredField("enumClass");
            fClass.setAccessible(true);
            Class<?> enumClass = (Class<?>) fClass.get(proxy);
            if (enumClass == null) return null;

            java.lang.reflect.Field fParams = proxy.getClass().getDeclaredField("parameters");
            fParams.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<Object> params = (java.util.List<Object>) fParams.get(proxy);

            String name = null;
            if (params != null) {
                for (Object param : params) {
                    if (param instanceof String s && !s.isEmpty()) {
                        name = s;
                        break;
                    }
                }
            }
            if (name == null) {
                name = "EXT_" + Math.abs(System.identityHashCode(proxy));
            }

            String safeName = name.replace(':', '_').replace('/', '_').toUpperCase(java.util.Locale.ROOT);
            val = getOrCreateEnum(enumClass, safeName);
            if (val == null) {
                val = getOrCreateEnum(enumClass, name);
            }
            if (val == null) {
                Object[] consts = enumClass.getEnumConstants();
                if (consts != null && consts.length > 0) {
                    val = (Enum<?>) consts[0];
                }
            }
            if (val != null) {
                fVal.set(proxy, val);
            }
            return val;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void onFMLModContainerSetActiveContainer(Object context, Object container) {
        // No-op: prevent FMLModContainer.<init> from mutating or clearing activeContainer on the thread.
        // Active container lifecycle is explicitly managed by PatchNeoForgeBootstrap.
    }

    public static void onCreativeModeTabBuildContentsLambda(Object set1, Object set2, Object stackObj, Object visibilityObj) {
        if (stackObj instanceof net.minecraft.world.item.ItemStack stack) {
            if (stack.getCount() != 1) {
                return;
            }
            String visName = visibilityObj != null ? visibilityObj.toString() : "";
            boolean isParent = "PARENT_TAB_ONLY".equals(visName) || "PARENT_AND_SEARCH_TABS".equals(visName);
            boolean isSearch = "SEARCH_TAB_ONLY".equals(visName) || "PARENT_AND_SEARCH_TABS".equals(visName);

            if (isParent && set1 instanceof java.util.Collection parentSet) {
                parentSet.add(stack);
            }
            if (isSearch && set2 instanceof java.util.Collection searchSet) {
                searchSet.add(stack);
            }
        }
    }

    public static Object getHolderReferenceValue(Object holder) {
        if (holder == null) return null;
        try {
            java.lang.reflect.Field valueField = holder.getClass().getDeclaredField("value");
            valueField.setAccessible(true);
            Object val = valueField.get(holder);
            if (val != null) return val;

            java.lang.reflect.Field keyField = holder.getClass().getDeclaredField("key");
            keyField.setAccessible(true);
            Object key = keyField.get(holder);
            String kStr = key != null ? key.toString() : "";

            ClassLoader cl = holder.getClass().getClassLoader();
            java.lang.reflect.Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe u = (sun.misc.Unsafe) unsafeField.get(null);

            Object fallback = null;
            if (kStr.contains("noise")) {
                try {
                    Class<?> npClass = Class.forName("net.minecraft.world.level.levelgen.synth.NormalNoise$NoiseParameters", true, cl);
                    java.lang.reflect.Constructor<?> npCtor = npClass.getConstructor(int.class, double.class, double[].class);
                    fallback = npCtor.newInstance(-7, 1.0, new double[]{1.0});
                } catch (Throwable ignored) {}
            } else if (kStr.contains("damage_type")) {
                try {
                    Class<?> dtClass = Class.forName("net.minecraft.world.damagesource.DamageType", true, cl);
                    java.lang.reflect.Constructor<?> dtCtor = dtClass.getConstructor(String.class, float.class);
                    fallback = dtCtor.newInstance("dummy", 0.0f);
                } catch (Throwable ignored) {}
            } else if (kStr.contains("chat_type")) {
                try {
                    Class<?> chatTypeClass = Class.forName("net.minecraft.network.chat.ChatType", true, cl);
                    Object defaultDec = chatTypeClass.getField("DEFAULT_CHAT_DECORATION").get(null);
                    java.lang.reflect.Constructor<?> chatTypeCtor = chatTypeClass.getConstructor(
                            Class.forName("net.minecraft.network.chat.ChatTypeDecoration", true, cl),
                            Class.forName("net.minecraft.network.chat.ChatTypeDecoration", true, cl)
                    );
                    fallback = chatTypeCtor.newInstance(defaultDec, defaultDec);
                } catch (Throwable ignored) {}
            } else if (kStr.contains("biome")) {
                try {
                    Class<?> biomeClass = Class.forName("net.minecraft.world.level.biome.Biome", true, cl);
                    fallback = u.allocateInstance(biomeClass);
                } catch (Throwable ignored) {}
            } else if (kStr.contains("enchantment")) {
                try {
                    Class<?> enchClass = Class.forName("net.minecraft.world.item.enchantment.Enchantment", true, cl);
                    fallback = u.allocateInstance(enchClass);
                } catch (Throwable ignored) {}
            } else if (kStr.contains("block")) {
                fallback = net.minecraft.world.level.block.Blocks.AIR;
            } else if (kStr.contains("fluid")) {
                fallback = net.minecraft.world.level.material.Fluids.EMPTY;
            } else if (kStr.contains("entity_type")) {
                fallback = net.minecraft.world.entity.EntityType.PIG;
            } else if (kStr.contains("item")) {
                try {
                    String locStr = null;
                    if (key != null) {
                        try {
                            Object loc = key.getClass().getMethod("location").invoke(key);
                            if (loc != null) locStr = loc.toString();
                        } catch (Throwable ignored) {}
                    }
                    fallback = getFallbackItem(locStr);
                } catch (Throwable ignored) {
                    fallback = net.minecraft.world.item.Items.AIR;
                }
            } else if (kStr.contains("processor_list")) {
                try {
                    Class<?> procListClass = Class.forName("net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList", true, cl);
                    java.lang.reflect.Constructor<?> procCtor = procListClass.getConstructor(java.util.List.class);
                    fallback = procCtor.newInstance(java.util.Collections.emptyList());
                } catch (Throwable ignored) {}
            } else if (kStr.contains("template_pool")) {
                try {
                    Class<?> poolClass = Class.forName("net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool", true, cl);
                    Object pool = u.allocateInstance(poolClass);
                    java.lang.reflect.Field rawF = poolClass.getDeclaredField("rawTemplates");
                    rawF.setAccessible(true);
                    rawF.set(pool, new java.util.ArrayList<>());
                    java.lang.reflect.Field templF = poolClass.getDeclaredField("templates");
                    templF.setAccessible(true);
                    templF.set(pool, new it.unimi.dsi.fastutil.objects.ObjectArrayList<>());
                    java.lang.reflect.Field fallF = poolClass.getDeclaredField("fallback");
                    fallF.setAccessible(true);
                    fallF.set(pool, holder);
                    fallback = pool;
                } catch (Throwable ignored) {}
            }

            if (fallback == null) {
                fallback = new Object();
            }

            valueField.set(holder, fallback);
            return fallback;
        } catch (Throwable t) {
            return new Object();
        }
    }

    private static final java.util.Map<String, Object> FALLBACK_ITEMS = new java.util.concurrent.ConcurrentHashMap<>();

    public static Object getFallbackItem(String locStr) {
        if (locStr == null || locStr.isEmpty()) {
            locStr = "patchneoforge:dummy_item_" + FALLBACK_ITEMS.size();
        }
        return FALLBACK_ITEMS.computeIfAbsent(locStr, l -> new net.minecraft.world.item.Item(new net.minecraft.world.item.Item.Properties()));
    }

    public static Object getArchitecturyEntry(Object entry) {
        if (entry == null) return null;
        try {
            java.lang.reflect.Field vf = entry.getClass().getDeclaredField("value");
            vf.setAccessible(true);
            Object val = vf.get(entry);
            if (val != null) {
                java.lang.reflect.Method isPres = val.getClass().getMethod("isPresent");
                if ((Boolean) isPres.invoke(val)) {
                    return val.getClass().getMethod("get").invoke(val);
                }
            }
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Field sf = entry.getClass().getDeclaredField("supplier");
            sf.setAccessible(true);
            java.util.function.Supplier<?> sup = (java.util.function.Supplier<?>) sf.get(entry);
            if (sup != null) return sup.get();
        } catch (Throwable ignored) {}
        return null;
    }

    public static void moonlightCategoryAddItem(Object category, Object item) {
        if (category == null || item == null) return;
        try {
            java.lang.reflect.Method m = item.getClass().getMethod("getDefaultInstance");
            Object stack = m.invoke(item);
            if (stack != null && !((Boolean) stack.getClass().getMethod("isEmpty").invoke(stack))) {
                java.lang.reflect.Field f = category.getClass().getDeclaredField("filled");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.List<Object> filled = (java.util.List<Object>) f.get(category);
                if (filled != null && !filled.contains(item)) {
                    filled.add(item);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void cobblemonRegisterRemap(Object manager, Object item, String showdownId) {
        if (item == null || showdownId == null) return;
        try {
            java.lang.reflect.Field f = manager.getClass().getDeclaredField("remaps");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<Object, Object> remaps = (java.util.Map<Object, Object>) f.get(null);
            if (remaps != null) {
                remaps.put(item, showdownId);
            }
        } catch (Throwable ignored) {}
    }

    public static net.minecraft.world.item.ItemStack ftbQuestsCreateMissing(Object tag) {
        try {
            return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BARRIER);
        } catch (Throwable t) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
    }

    public static Object getWhiteNoiseConfigValue(Object configValue) {
        if (configValue == null) return null;
        try {
            java.lang.reflect.Method def = configValue.getClass().getMethod("getDefault");
            return def.invoke(configValue);
        } catch (Throwable t) {
            try {
                java.lang.reflect.Field f = configValue.getClass().getDeclaredField("defaultSupplier");
                f.setAccessible(true);
                java.util.function.Supplier<?> sup = (java.util.function.Supplier<?>) f.get(configValue);
                if (sup != null) return sup.get();
            } catch (Throwable ignored) {}
            return null;
        }
    }

    public static Object getDeferredHolderValue(Object holderObj) {
        if (holderObj == null) return null;
        try {
            Class<?> dhClass = holderObj.getClass();
            if (dhClass.getName().equals("net.neoforged.neoforge.registries.DeferredHolder")) {
                try {
                    java.lang.reflect.Method m = dhClass.getDeclaredMethod("bind", boolean.class);
                    m.setAccessible(true);
                    m.invoke(holderObj, true);
                } catch (Throwable ignored) {}
                try {
                    java.lang.reflect.Field f = dhClass.getDeclaredField("holder");
                    f.setAccessible(true);
                    Object h = f.get(holderObj);
                    if (h != null) {
                        java.lang.reflect.Method isBoundMethod = h.getClass().getMethod("isBound");
                        if ((Boolean) isBoundMethod.invoke(h)) {
                            return h.getClass().getMethod("value").invoke(h);
                        }
                    }
                } catch (Throwable ignored) {}

                try {
                    java.lang.reflect.Method getKeyMethod = dhClass.getMethod("getKey");
                    Object key = getKeyMethod.invoke(holderObj);
                    if (key != null) {
                        Object loc = key.getClass().getMethod("location").invoke(key);
                        Object regLoc = key.getClass().getMethod("registry").invoke(key);
                        String locStr = loc != null ? loc.toString() : "";
                        String regStr = regLoc != null ? regLoc.toString() : "";
                        if (regStr.contains("block")) {
                            return net.minecraft.world.level.block.Blocks.AIR;
                        } else if (regStr.contains("fluid")) {
                            return net.minecraft.world.level.material.Fluids.EMPTY;
                        } else if (regStr.contains("entity_type")) {
                            return net.minecraft.world.entity.EntityType.PIG;
                        } else if (regStr.contains("item")) {
                            return getFallbackItem(locStr);
                        }
                    }
                } catch (Throwable ignored) {}
                return getFallbackItem(null);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Object getBlockItemRequiredFeatures(Object blockItem) {
        if (blockItem instanceof net.minecraft.world.item.BlockItem bi) {
            net.minecraft.world.level.block.Block b = bi.getBlock();
            if (b != null) {
                return b.requiredFeatures();
            }
        }
        return net.minecraft.world.flag.FeatureFlags.VANILLA_SET;
    }

    public static void registerDefaultRecordProperties(Object contextFactory, Object record) {
        if (record == null) return;
        try {
            java.lang.reflect.RecordComponent[] components = record.getClass().getRecordComponents();
            if (components == null) return;
            Object[] properties = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                properties[i] = components[i].getAccessor().invoke(record);
            }
            java.lang.reflect.Field f = contextFactory.getClass().getDeclaredField("defaultRecordProperties");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<Class<?>, Object[]> map = (java.util.Map<Class<?>, Object[]>) f.get(contextFactory);
            map.put(record.getClass(), properties);
        } catch (Throwable ignored) {}
    }

    public static Object getConfigValueRaw(Object configValue) {
        if (configValue == null) return null;
        try {
            java.lang.reflect.Field specField = configValue.getClass().getDeclaredField("spec");
            specField.setAccessible(true);
            Object spec = specField.get(configValue);
            if (spec != null) {
                java.lang.reflect.Field loadedConfigField = spec.getClass().getDeclaredField("loadedConfig");
                loadedConfigField.setAccessible(true);
                Object loadedConfig = loadedConfigField.get(spec);
                if (loadedConfig != null) {
                    java.lang.reflect.Method getRaw = configValue.getClass().getMethod("getRaw",
                        Class.forName("com.electronwill.nightconfig.core.Config"),
                        java.util.List.class,
                        java.util.function.Supplier.class);
                    java.lang.reflect.Method configMethod = loadedConfig.getClass().getMethod("config");
                    Object config = configMethod.invoke(loadedConfig);
                    java.lang.reflect.Field pathField = configValue.getClass().getDeclaredField("path");
                    pathField.setAccessible(true);
                    java.lang.reflect.Field defField = configValue.getClass().getDeclaredField("defaultSupplier");
                    defField.setAccessible(true);
                    return getRaw.invoke(configValue, config, pathField.get(configValue), defField.get(configValue));
                }
            }
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method def = configValue.getClass().getMethod("getDefault");
            return def.invoke(configValue);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object getConfigValueWrapperGet(Object wrapper) {
        if (wrapper == null) return null;
        try {
            java.lang.reflect.Field f = wrapper.getClass().getDeclaredField("configValue");
            f.setAccessible(true);
            Object cv = f.get(wrapper);
            return getConfigValueRaw(cv);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final java.util.Map<Long, Object> HEADLESS_EMPTY_CHUNKS = new java.util.concurrent.ConcurrentHashMap<>();

    public static Object getChunkOrEmpty(Object cache, int x, int z, Object status, boolean load) {
        if (cache == null) return null;
        long chunkKey = (((long) x) << 32) | (((long) z) & 0xFFFFFFFFL);
        Object cached = HEADLESS_EMPTY_CHUNKS.get(chunkKey);
        if (cached != null) return cached;
        try {
            ClassLoader cl = cache.getClass().getClassLoader();
            java.lang.reflect.Field levelField = null;
            for (Class<?> c = cache.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    levelField = c.getDeclaredField("level");
                    levelField.setAccessible(true);
                    break;
                } catch (Throwable ignored) {}
            }
            Object level = levelField != null ? levelField.get(cache) : null;
            if (level == null) return null;

            Class<?> chunkPosClass = Class.forName("net.minecraft.world.level.ChunkPos", true, cl);
            java.lang.reflect.Constructor<?> chunkPosCtor = chunkPosClass.getConstructor(int.class, int.class);
            Object chunkPos = chunkPosCtor.newInstance(x, z);

            Object biomeHolder = null;
            // 1. Try to get biome from generator's biome source
            try {
                java.lang.reflect.Method getGenMethod = cache.getClass().getMethod("getGenerator");
                Object gen = getGenMethod.invoke(cache);
                if (gen != null) {
                    java.lang.reflect.Method getBiomeSourceMethod = gen.getClass().getMethod("getBiomeSource");
                    Object biomeSource = getBiomeSourceMethod.invoke(gen);
                    if (biomeSource != null) {
                        java.lang.reflect.Field biomeField = biomeSource.getClass().getDeclaredField("biome");
                        biomeField.setAccessible(true);
                        biomeHolder = biomeField.get(biomeSource);
                    }
                }
            } catch (Throwable ignored) {}

            // 2. Fallback: synthesize dummy Biome holder
            if (biomeHolder == null) {
                try {
                    Class<?> biomeClass = Class.forName("net.minecraft.world.level.biome.Biome", true, cl);
                    java.lang.reflect.Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                    theUnsafe.setAccessible(true);
                    sun.misc.Unsafe u = (sun.misc.Unsafe) theUnsafe.get(null);
                    Object dummyBiome = u.allocateInstance(biomeClass);
                    java.lang.reflect.Method directMethod = Class.forName("net.minecraft.core.Holder", true, cl).getMethod("direct", Object.class);
                    biomeHolder = directMethod.invoke(null, dummyBiome);
                } catch (Throwable ignored) {}
            }

            if (biomeHolder != null) {
                Class<?> emptyChunkClass = Class.forName("net.minecraft.world.level.chunk.EmptyLevelChunk", true, cl);
                Class<?> levelClass = Class.forName("net.minecraft.world.level.Level", true, cl);
                Class<?> holderClass = Class.forName("net.minecraft.core.Holder", true, cl);
                java.lang.reflect.Constructor<?> emptyChunkCtor = emptyChunkClass.getConstructor(levelClass, chunkPosClass, holderClass);
                Object chunk = emptyChunkCtor.newInstance(level, chunkPos, biomeHolder);
                HEADLESS_EMPTY_CHUNKS.put(chunkKey, chunk);
                return chunk;
            }
        } catch (Throwable t) {
            System.err.println("[PatchNeoForge] Error creating EmptyLevelChunk: " + t.getMessage());
            t.printStackTrace();
        }
        return null;
    }
}
