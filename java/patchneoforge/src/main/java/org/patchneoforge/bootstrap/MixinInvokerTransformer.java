package org.patchneoforge.bootstrap;

import net.bytebuddy.jar.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;

public class MixinInvokerTransformer implements ClassFileTransformer {

    private static final byte[] MIXIN_ANNOTATION_BYTES = "Lorg/spongepowered/asm/mixin/Mixin;".getBytes(StandardCharsets.US_ASCII);

    private static boolean containsBytes(byte[] buffer, byte[] pattern) {
        if (buffer == null || buffer.length < pattern.length) return false;
        outer:
        for (int i = 0; i <= buffer.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (buffer[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null
                || className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("patchneoforge/")
                || className.startsWith("org/patchneoforge/")
                || className.startsWith("net/minecraft/")
                || className.startsWith("net/neoforged/")
                || className.startsWith("org/spongepowered/")
                || className.startsWith("com/google/")
                || className.startsWith("net/bytebuddy/")
                || className.startsWith("org/objectweb/")
                || className.startsWith("org/slf4j/")
                || className.startsWith("org/apache/")) {
            return null;
        }

        // Fast-path: only examine classes with Mixin in constant pool
        if (!containsBytes(classfileBuffer, MIXIN_ANNOTATION_BYTES)) {
            return null;
        }

        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

            final String[] targetClassHolder = new String[1];
            final boolean[] hasMixinMethods = new boolean[1];

            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(descriptor)) {
                        return new AnnotationVisitor(Opcodes.ASM9, super.visitAnnotation(descriptor, visible)) {
                            @Override
                            public AnnotationVisitor visitArray(String name) {
                                return new AnnotationVisitor(Opcodes.ASM9, super.visitArray(name)) {
                                    @Override
                                    public void visit(String aName, Object value) {
                                        if (targetClassHolder[0] == null) {
                                            if (value instanceof Type t) {
                                                targetClassHolder[0] = t.getInternalName();
                                            } else if (value instanceof String s) {
                                                targetClassHolder[0] = s.replace('.', '/');
                                            }
                                        }
                                        super.visit(aName, value);
                                    }
                                };
                            }
                        };
                    }
                    return super.visitAnnotation(descriptor, visible);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    MethodVisitor parentMv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, parentMv) {
                        private boolean isInvoker = false;
                        private boolean isAccessor = false;
                        private String customTarget = null;

                        @Override
                        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                            if ("Lorg/spongepowered/asm/mixin/gen/Invoker;".equals(desc)) {
                                isInvoker = true;
                                hasMixinMethods[0] = true;
                                return new AnnotationVisitor(Opcodes.ASM9, super.visitAnnotation(desc, visible)) {
                                    @Override
                                    public void visit(String aName, Object value) {
                                        if ("value".equals(aName) && value instanceof String s) {
                                            customTarget = s;
                                        }
                                        super.visit(aName, value);
                                    }
                                };
                            }
                            if ("Lorg/spongepowered/asm/mixin/gen/Accessor;".equals(desc)) {
                                isAccessor = true;
                                hasMixinMethods[0] = true;
                                return new AnnotationVisitor(Opcodes.ASM9, super.visitAnnotation(desc, visible)) {
                                    @Override
                                    public void visit(String aName, Object value) {
                                        if ("value".equals(aName) && value instanceof String s) {
                                            customTarget = s;
                                        }
                                        super.visit(aName, value);
                                    }
                                };
                            }
                            return super.visitAnnotation(desc, visible);
                        }

                        @Override
                        public void visitCode() {
                            String targetClass = targetClassHolder[0];
                            if ((isInvoker || isAccessor) && targetClass != null) {
                                super.visitCode();
                                Type methodType = Type.getMethodType(descriptor);
                                Type[] args = methodType.getArgumentTypes();
                                Type returnType = methodType.getReturnType();
                                boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;

                                if (isInvoker) {
                                    String targetMethod = (customTarget != null && !customTarget.isEmpty()) ? customTarget : name;
                                    if (targetMethod.contains("$")) {
                                        targetMethod = targetMethod.substring(targetMethod.lastIndexOf('$') + 1);
                                    }
                                    if (targetMethod.startsWith("invoke")) {
                                        targetMethod = targetMethod.substring(6);
                                        if (!targetMethod.isEmpty() && Character.isUpperCase(targetMethod.charAt(0))) {
                                            targetMethod = Character.toLowerCase(targetMethod.charAt(0)) + targetMethod.substring(1);
                                        }
                                    } else if (targetMethod.startsWith("call")) {
                                        targetMethod = targetMethod.substring(4);
                                        if (!targetMethod.isEmpty() && Character.isUpperCase(targetMethod.charAt(0))) {
                                            targetMethod = Character.toLowerCase(targetMethod.charAt(0)) + targetMethod.substring(1);
                                        }
                                    }

                                    if ("<init>".equals(targetMethod)) {
                                        // Constructor factory invoker
                                        super.visitTypeInsn(Opcodes.NEW, targetClass);
                                        super.visitInsn(Opcodes.DUP);
                                        int varIdx = isStatic ? 0 : 1;
                                        for (Type arg : args) {
                                            super.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), varIdx);
                                            varIdx += arg.getSize();
                                        }
                                        String constructorDesc = Type.getMethodDescriptor(Type.VOID_TYPE, args);
                                        super.visitMethodInsn(Opcodes.INVOKESPECIAL, targetClass, "<init>", constructorDesc, false);
                                        super.visitInsn(Opcodes.ARETURN);
                                    } else if (isStatic) {
                                        // Static method invoker
                                        int varIdx = 0;
                                        for (Type arg : args) {
                                            super.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), varIdx);
                                            varIdx += arg.getSize();
                                        }
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, targetClass, targetMethod, descriptor, false);
                                        super.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
                                    } else {
                                        // Virtual method invoker: arg 0 is the receiver instance
                                        super.visitVarInsn(Opcodes.ALOAD, 0);
                                        int varIdx = 1;
                                        Type[] targetArgs = new Type[args.length - 1];
                                        System.arraycopy(args, 1, targetArgs, 0, args.length - 1);
                                        for (Type arg : targetArgs) {
                                            super.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), varIdx);
                                            varIdx += arg.getSize();
                                        }
                                        String targetDesc = Type.getMethodDescriptor(returnType, targetArgs);
                                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, targetClass, targetMethod, targetDesc, false);
                                        super.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
                                    }
                                } else { // isAccessor
                                    String targetField = (customTarget != null && !customTarget.isEmpty()) ? customTarget : name;
                                    if (targetField.contains("$")) {
                                        targetField = targetField.substring(targetField.lastIndexOf('$') + 1);
                                    }
                                    if (targetField.startsWith("get")) {
                                        targetField = targetField.substring(3);
                                    } else if (targetField.startsWith("is")) {
                                        targetField = targetField.substring(2);
                                    } else if (targetField.startsWith("set")) {
                                        targetField = targetField.substring(3);
                                    }
                                    if (!targetField.isEmpty() && Character.isUpperCase(targetField.charAt(0)) && !targetField.contains("_")) {
                                        targetField = Character.toLowerCase(targetField.charAt(0)) + targetField.substring(1);
                                    }

                                    int paramCount = args.length;
                                    if ((isStatic && paramCount == 0) || (!isStatic && paramCount == 0)) {
                                        // Field Getter
                                        if (isStatic) {
                                            super.visitFieldInsn(Opcodes.GETSTATIC, targetClass, targetField, returnType.getDescriptor());
                                        } else {
                                            super.visitVarInsn(Opcodes.ALOAD, 0);
                                            super.visitFieldInsn(Opcodes.GETFIELD, targetClass, targetField, returnType.getDescriptor());
                                        }
                                        super.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
                                    } else {
                                        // Field Setter
                                        if (isStatic) {
                                            super.visitVarInsn(args[0].getOpcode(Opcodes.ILOAD), 0);
                                            super.visitFieldInsn(Opcodes.PUTSTATIC, targetClass, targetField, args[0].getDescriptor());
                                        } else {
                                            super.visitVarInsn(Opcodes.ALOAD, 0);
                                            super.visitVarInsn(args[0].getOpcode(Opcodes.ILOAD), 1);
                                            super.visitFieldInsn(Opcodes.PUTFIELD, targetClass, targetField, args[0].getDescriptor());
                                        }
                                        super.visitInsn(Opcodes.RETURN);
                                    }
                                }

                                super.visitMaxs(0, 0);
                                super.visitEnd();
                            } else {
                                super.visitCode();
                            }
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (!isInvoker && !isAccessor) super.visitInsn(opcode);
                        }

                        @Override
                        public void visitIntInsn(int opcode, int operand) {
                            if (!isInvoker && !isAccessor) super.visitIntInsn(opcode, operand);
                        }

                        @Override
                        public void visitVarInsn(int opcode, int varIndex) {
                            if (!isInvoker && !isAccessor) super.visitVarInsn(opcode, varIndex);
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            if (!isInvoker && !isAccessor) super.visitTypeInsn(opcode, type);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            if (!isInvoker && !isAccessor) super.visitFieldInsn(opcode, owner, name, descriptor);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            if (!isInvoker && !isAccessor) super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }

                        @Override
                        public void visitJumpInsn(int opcode, Label label) {
                            if (!isInvoker && !isAccessor) super.visitJumpInsn(opcode, label);
                        }

                        @Override
                        public void visitLabel(Label label) {
                            if (!isInvoker && !isAccessor) super.visitLabel(label);
                        }

                        @Override
                        public void visitLineNumber(int line, Label start) {
                            if (!isInvoker && !isAccessor) super.visitLineNumber(line, start);
                        }

                        @Override
                        public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
                            if (!isInvoker && !isAccessor) super.visitLocalVariable(name, descriptor, signature, start, end, index);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            if (!isInvoker && !isAccessor) super.visitMaxs(maxStack, maxLocals);
                        }
                    };
                }
            };

            cr.accept(cv, 0);

            if (hasMixinMethods[0] && targetClassHolder[0] != null) {
                System.out.println("[PatchNeoForge] Implemented Mixin Invokers/Accessors: " + className + " -> " + targetClassHolder[0]);
                return cw.toByteArray();
            }
        } catch (Throwable ignored) {
        }

        return null;
    }
}
