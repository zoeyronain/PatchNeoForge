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
                    if (className != null && className.startsWith("net/minecraft/")) {
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
                                    if (!isInterface[0] && !isEnum[0]) {
                                        newAccess = access & ~net.bytebuddy.jar.asm.Opcodes.ACC_FINAL;
                                    }
                                    super.visit(version, newAccess, name, signature, superName, interfaces);
                                }

                                @Override
                                public net.bytebuddy.jar.asm.FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                                    int newAccess = access;
                                    if (!isInterface[0] && !isEnum[0]) {
                                        newAccess = (access & ~net.bytebuddy.jar.asm.Opcodes.ACC_PRIVATE & ~net.bytebuddy.jar.asm.Opcodes.ACC_PROTECTED) | net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC;
                                    }
                                    return super.visitField(newAccess, name, descriptor, signature, value);
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

                                    return super.visitMethod(newAccess, name, descriptor, signature, exceptions);
                                }
                            };
                            cr.accept(cv, 0);
                            return cw.toByteArray();
                        } catch (Throwable ignored) {}
                    }
                    return null;
                }
            }, true);
            inst.addTransformer(new MixinInvokerTransformer(), true);
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
        booted = true;
        return true;
    }
}
