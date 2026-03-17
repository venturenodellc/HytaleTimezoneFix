# HytaleTimezoneFix

A lightweight, production-ready Java agent that makes Hytale’s log timestamps honor your local timezone. It rewrites the game’s log formatter at class-load time so the hardcoded UTC is replaced with a properly resolved ZoneId.

- Honors, in order: TZ environment variable, -Duser.timezone, JVM default.
- Robust fallback: if transformation or resolution fails, logging continues with the game’s typical behavior (usually UTC).
- No runtime dependencies: ASM is shaded into the jar.

## Why?

Many servers run with UTC by default (e.g., containers), which makes log timestamps harder to read for operators. This agent aligns timestamps with your intended timezone without modifying the game files.

## How it works

At JVM startup the agent:
1. Locates the game’s log formatter during class loading.
2. Rewrites the bytecode that forces UTC to instead call a resolver returning the desired ZoneId.
3. If it can’t transform the class, it safely does nothing and the game continues normally.

The resolver picks the timezone using this priority:
1) TZ environment variable (e.g., TZ=America/New_York)
2) -Duser.timezone JVM property (e.g., -Duser.timezone=Europe/Berlin)
3) JVM default (ZoneId.systemDefault())

If all resolution attempts fail, it falls back to the JVM default, effectively preserving typical behavior.

## Requirements

- Java 11 or newer
- Maven 3.6+ (to build from source)

## Installation

Download or build the agent jar:
- Name: HytaleTimezoneFix.jar
- Location after build: target/HytaleTimezoneFix.jar

Place it where you launch your server so the path can be used with -javaagent.

## Usage

- Using TZ environment variable (recommended for servers/containers):
```bash
TZ="America/New_York" java -javaagent:target/HytaleTimezoneFix.jar -jar hytale-server.jar
```
- Using JVM property:
```bash
java -javaagent:target/HytaleTimezoneFix.jar -Duser.timezone="Europe/Berlin" -jar hytale-server.jar
```
- Relying on JVM/OS default:
```bash
java -javaagent:target/HytaleTimezoneFix.jar -jar hytale-server.jar
```

On startup, you’ll see informational logs indicating:
- Whether the formatter was patched.
- Which timezone will be used (or that UTC remains in effect if patching or resolution is skipped).

## Building from source
```bash
mvn clean package
```
Artifacts:
- target/HytaleTimezoneFix.jar

The build shades ASM into the agent, so there are no external runtime dependencies.

## Logging and error handling

- The agent logs via java.util.logging with clear levels (INFO/WARNING).
- If transformation fails, the class remains unmodified and the server continues with typical behavior.
- If timezone resolution fails, it falls back to the JVM default.

## Compatibility and safety

- The agent is designed to be minimally invasive. If the target formatter changes in future game versions, the agent will detect mismatch, skip patching, and let the game run normally.
- Supports retransformation if the formatter was already loaded (best-effort).
- No changes are made to user data or server configuration.

## Frequently asked questions

- Q: Do I need to change server configs?
  A: No. Optionally set TZ or -Duser.timezone if you want an explicit timezone.

- Q: What timezone is used if nothing is configured?
  A: The JVM default (often inherited from the host OS). If the agent can’t patch or resolve, logs remain in the game’s typical behavior (usually UTC).

- Q: Does this affect gameplay?
  A: No. Only log formatting is impacted.

## License

This project is provided under an open-source license. See LICENSE for details.
