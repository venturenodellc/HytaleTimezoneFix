package bisecthosting.agent;

import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Resolves the effective ZoneId at runtime, checked in priority order:
 *
 *  1. TZ  environment variable  (e.g. "America/New_York", "Europe/London", "+05:30")
 *  2. user.timezone  JVM system property  (set via -Duser.timezone=...)
 *  3. ZoneId.systemDefault()  (whatever the JVM defaulted to on startup)
 *
 * The result is cached after the first call — timezone is not expected to
 * change mid-run, and caching avoids a map lookup on every log line.
 *
 * Intentionally avoids java.util.logging — touching JUL during premain
 * causes HytaleLogManager initialisation to fail.
 */
public final class TzResolver {

    private static volatile ZoneId CACHED;

    private TzResolver() {}

    public static ZoneId resolvedZoneId() {
        ZoneId cached = CACHED;
        if (cached != null) return cached;

        synchronized (TzResolver.class) {
            if (CACHED == null) {
                try {
                    CACHED = resolve();
                } catch (Throwable t) {
                    System.err.println("[HytaleTimezoneFix] Timezone resolution failed, falling back to JVM default: " + t);
                    CACHED = ZoneId.systemDefault();
                }
            }
            return CACHED;
        }
    }

    private static ZoneId resolve() {
        // 1. TZ env var
        String tz = System.getenv("TZ");
        if (tz != null && !tz.isBlank()) {
            try {
                ZoneId z = ZoneId.of(tz.trim());
                System.out.println("[HytaleTimezoneFix] Using TZ environment variable: " + z);
                return z;
            } catch (Exception e) {
                System.err.println("[HytaleTimezoneFix] Invalid TZ env var '" + tz + "', trying next source: " + e.getMessage());
            }
        }

        // 2. user.timezone JVM property
        String prop = System.getProperty("user.timezone");
        if (prop != null && !prop.isBlank()) {
            try {
                ZoneId z = ZoneId.of(prop.trim());
                System.out.println("[HytaleTimezoneFix] Using user.timezone property: " + z);
                return z;
            } catch (Exception e) {
                System.err.println("[HytaleTimezoneFix] Invalid user.timezone '" + prop + "', trying next source: " + e.getMessage());
            }
        }

        // 3. JVM default
        ZoneId z = ZoneId.systemDefault();
        if (ZoneOffset.UTC.equals(z) || "UTC".equals(z.getId())) {
            System.out.println("[HytaleTimezoneFix] WARNING: Resolved timezone is still UTC. Set TZ or -Duser.timezone= to override.");
        } else {
            System.out.println("[HytaleTimezoneFix] Using JVM default timezone: " + z);
        }
        return z;
    }
}
