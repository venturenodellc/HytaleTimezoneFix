package bisecthosting.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

import org.objectweb.asm.*;

/**
 * Java agent that patches HytaleLogFormatter to use the system/TZ-env timezone
 * instead of the hardcoded ZoneOffset.UTC.
 *
 * Attach with: -javaagent:HytaleTimezoneFix.jar
 * Respects (in priority order):
 *   1. TZ environment variable  (e.g. TZ=America/New_York)
 *   2. user.timezone JVM property  (e.g. -Duser.timezone=Europe/Berlin)
 *   3. ZoneId.systemDefault()
 *
 * Intentionally avoids java.util.logging during premain — touching JUL before
 * the server starts causes HytaleLogManager initialisation to fail.
 */
public class HytaleTzAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[HytaleTimezoneFix] Initializing agent");
        inst.addTransformer(new LogFormatterTransformer(), true);

        // Re-transform if class was somehow already loaded
        for (Class<?> cls : inst.getAllLoadedClasses()) {
            if ("com.hypixel.hytale.logger.backend.HytaleLogFormatter".equals(cls.getName())) {
                try {
                    inst.retransformClasses(cls);
                } catch (Exception e) {
                    System.err.println("[HytaleTimezoneFix] Failed to retransform already-loaded HytaleLogFormatter: " + e);
                }
            }
        }

        try {
            System.out.println("[HytaleTimezoneFix] Loaded — log timestamps will use: " + TzResolver.resolvedZoneId());
        } catch (Throwable t) {
            System.err.println("[HytaleTimezoneFix] Timezone resolution failed, server will likely fall back to UTC: " + t);
        }
    }

    // Also support attach-after-start
    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    // -------------------------------------------------------------------------

    static class LogFormatterTransformer implements ClassFileTransformer {

        private static final String TARGET_CLASS = "com/hypixel/hytale/logger/backend/HytaleLogFormatter";

        @Override
        public byte[] transform(
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer) {

            if (!TARGET_CLASS.equals(className)) return null;

            try {
                ClassReader cr = new ClassReader(classfileBuffer);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                cr.accept(new LogFormatterClassVisitor(cw), ClassReader.EXPAND_FRAMES);
                byte[] patched = cw.toByteArray();
                System.out.println("[HytaleTimezoneFix] Successfully patched HytaleLogFormatter");
                return patched;
            } catch (Throwable e) {
                System.err.println("[HytaleTimezoneFix] Patch failed, class will remain unmodified: " + e);
                return null;
            }
        }
    }

    // -------------------------------------------------------------------------

    static class LogFormatterClassVisitor extends ClassVisitor {
        LogFormatterClassVisitor(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("format".equals(name) && descriptor.startsWith("(Ljava/util/logging/LogRecord;)")) {
                return new UtcReplacingMethodVisitor(mv);
            }
            return mv;
        }
    }

    // -------------------------------------------------------------------------

    static class UtcReplacingMethodVisitor extends MethodVisitor {

        private static final String ZONE_OFFSET_OWNER = "java/time/ZoneOffset";
        private static final String ZONE_OFFSET_NAME  = "UTC";
        private static final String ZONE_OFFSET_DESC  = "Ljava/time/ZoneOffset;";

        private static final String RESOLVER_OWNER = "bisecthosting/agent/TzResolver";
        private static final String RESOLVER_METHOD = "resolvedZoneId";
        private static final String RESOLVER_DESC   = "()Ljava/time/ZoneId;";

        UtcReplacingMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.GETSTATIC
                    && ZONE_OFFSET_OWNER.equals(owner)
                    && ZONE_OFFSET_NAME.equals(name)
                    && ZONE_OFFSET_DESC.equals(descriptor)) {
                // Swap ZoneOffset.UTC for TzResolver.resolvedZoneId()
                mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        RESOLVER_OWNER,
                        RESOLVER_METHOD,
                        RESOLVER_DESC,
                        false);
            } else {
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }
        }
    }
}
