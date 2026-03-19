package bisecthosting.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

import org.objectweb.asm.*;

/**
 * Java agent that patches HytaleLogFormatter to use the system/TZ-env timezone
 * instead of the hardcoded ZoneOffset.UTC.
 *
 * Intentionally avoids java.util.logging during premain — touching JUL before
 * the server starts causes HytaleLogManager initialisation to fail.
 *
 * Compatibility note — EarlyPlugin conflict:
 *
 *   Hytale's EarlyPlugin system uses TransformingClassLoader (a URLClassLoader
 *   subclass) to load and define classes from HytaleServer.jar. Before each
 *   defineClass() call it passes bytecode through registered ClassTransformer
 *   implementations. Because TransformingClassLoader calls defineClass(), the
 *   JVM's JVMTI hook fires — meaning our transform() callback is invoked with
 *   loader = TransformingClassLoader during the early-plugin pass, and then
 *   again when the class reaches its final definition point.
 */
public class HytaleTzAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[HytaleTimezoneFix] Initializing agent");
        // Append the agent jar to the bootstrap classpath so that bisecthosting.agent.TzResolver is visible from every classloader
        // Otherwise, the INVOKESTATIC to TzResolver causes a NoClassDefFoundError when the class is loaded by the classloader (and can't see said agent).
        try {
            java.security.CodeSource cs =
                HytaleTzAgent.class.getProtectionDomain().getCodeSource();
            if (cs != null) {
                inst.appendToBootstrapClassLoaderSearch(
                    new java.util.jar.JarFile(cs.getLocation().toURI().getPath()));
            }
        } catch (Exception e) {
            System.err.println("[HytaleTimezoneFix] Could not append agent to bootstrap classpath, " +
                "TzResolver may be invisible at runtime: " + e);
        }
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
            System.out.println("[HytaleTimezoneFix] Resolution ready! Log timestamps will use: " + TzResolver.resolvedZoneId());
        } catch (Throwable t) {
            System.err.println("[HytaleTimezoneFix] Timezone resolution failed, server will likely fall back to UTC: " + t);
        }
    }

    // Also support attach after start
    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    static class LogFormatterTransformer implements ClassFileTransformer {
        private static final String TARGET_CLASS = "com/hypixel/hytale/logger/backend/HytaleLogFormatter";

        /**
         * Guard against double-patching.
         *
         * When Hytale's EarlyPlugin system is active, TransformingClassLoader
         * defines classes from HytaleServer.jar after passing them through its
         * ClassTransformer list. Our transformer fires during that pass AND again at the final class definition point. 
         * Without this guard, HytaleLogFormatter is patched twice; the second pass corrupts the already patched bytecode.
         */
        private final AtomicBoolean patched = new AtomicBoolean(false);

        @Override
        public byte[] transform(
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer) {

            if (!TARGET_CLASS.equals(className)) return null;

            if (!patched.compareAndSet(false, true)) {
                System.out.println("[HytaleTimezoneFix] Skipping duplicate transform of HytaleLogFormatter");
                return null; // null = leave bytecode unchanged
            }

            // When triggered by the EarlyPlugin, `loader` is Hytale's TransformingClassLoader, which sends secure packages to the
            // original appClassLoader and can therefore see all server classes. When there are no early plugins, `loader` is whatever classloader
            // is defining HytaleLogFormatter. Fall back to the system classloader only as a last resort.
            final ClassLoader targetLoader = (loader != null)
                    ? loader
                    : ClassLoader.getSystemClassLoader();

            try {
                ClassReader cr = new ClassReader(classfileBuffer);
                // Override getClassLoader() so ASM's compute frames logic resolves HytaleLogFormatter's hierarchy 
                // through `targetLoader` rather than the system classloader.
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
                    @Override
                    protected ClassLoader getClassLoader() {
                        return targetLoader;
                    }
                };

                cr.accept(new LogFormatterClassVisitor(cw), ClassReader.EXPAND_FRAMES);
                byte[] patchedBytes = cw.toByteArray();
                System.out.println("[HytaleTimezoneFix] Successfully patched HytaleLogFormatter");
                return patchedBytes;
            } catch (Throwable e) {
                System.err.println("[HytaleTimezoneFix] Patch failed, class will remain unmodified: " + e);
                // Reset so a retransform attempt can try again
                patched.set(false);
                return null;
            }
        }
    }

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
