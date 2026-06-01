# Java Debugger CLI

Minimal interactive Java debugger built on JDI, focused on terminal workflows.

For a consolidated Java CLI + JS Ink CLI feature baseline used by LLM contributors, see [docs/feature-baseline.md](docs/feature-baseline.md).

## Features

- Attach to an existing JVM (`127.0.0.1:5005` by default)
- Launch a JVM under debugger control
- Persistent breakpoints across restarts
- File picker and breakpoint manager TUI workflows
- Step into, step over, step out, continue
- Async breakpoint pickup while the prompt is idle (good for REST API traffic)
- Source snippet rendering with fallback messages for mapped runtime-only locations

## Requirements

- Java 17+
- Maven 3.9+
- Target app started with JDWP when using attach mode

Example JDWP flags for an external app:

```bash
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

## Build And Test

```bash
mvn test
```

Package only:

```bash
mvn -DskipTests clean package
```

## Install On macOS

```bash
./scripts/install-jdbg.sh
```

Installer outputs:

- `jdbg` in `/usr/local/bin` when writable, otherwise `~/.local/bin`
- jar at `~/.local/share/jdbg/java-debugger.jar`

Uninstall:

```bash
./scripts/uninstall-jdbg.sh
```

## Command Modes

### Default (Attach)

Running `jdbg` with no mode flags uses attach mode:

```bash
jdbg
```

Equivalent explicit form:

```bash
jdbg attach --host 127.0.0.1 --port 5005
```

### Launch Under Debugger

```bash
jdbg --launch \
  --main-class dev.javadebugger.sample.SampleApp \
  --classpath target/test-classes:target/classes
```

Equivalent subcommand form:

```bash
jdbg launch \
  --main-class dev.javadebugger.sample.SampleApp \
  --classpath target/test-classes:target/classes
```

## Deno TUI (Java Engine + JS Front-End)

This repository now includes a Deno-based Ink TUI in `deno-tui/` that drives the same Java JDI debugger engine through a lightweight bridge process.

### Why this exists

- Test whether Java debugging is practical from a JS runtime UI
- Keep JDI logic in Java, while experimenting with richer terminal UX in Deno
- Measure bridge overhead independently with a benchmark command

### Requirements

- Deno 2.x+
- Java 17+
- Maven 3.9+

### Run in attach mode

```bash
cd deno-tui
deno task start attach --host 127.0.0.1 --port 5005 --source-root /absolute/path/to/src/main/java
```

### Run in launch mode

```bash
cd deno-tui
deno task start launch \
  --main-class dev.javadebugger.sample.SampleApp \
  --classpath ../target/test-classes:../target/classes
```

### Measure Deno<->Java bridge overhead

```bash
cd deno-tui
deno task bench
```

This benchmark measures protocol round-trip latency (ping) between Deno and the Java bridge, which helps estimate UI-layer overhead separate from JVM debug event cost.

In the Deno TUI, `files` opens an interactive picker: use the arrow keys to choose a file, then use the arrow keys and Enter to toggle breakpoints on executable lines directly from the selected file view.

## Spring Boot / REST API Workflow (Recommended)

1. Start Spring Boot with JDWP enabled (`suspend=n` for normal service startup).
2. Start debugger in attach mode.
3. Use `files` to set breakpoints.
4. Send API traffic with curl.
5. When a breakpoint is hit, debugger stops automatically and shows context.

Example:

```bash
# Terminal 1: spring boot app with debug socket
./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments='-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005'

# Terminal 2: debugger
jdbg --port 5005 --source-root /absolute/path/to/app/src/main/java

# Inside jdbg: set breakpoints (files), then resume if needed
continue

# Terminal 3: trigger endpoint
curl -i http://localhost:8080/api/v1/orders/42
```

Notes:

- In attach mode, debugger starts in running state and waits for runtime stops.
- `continue` is non-blocking and returns prompt immediately.
- Breakpoint hits are surfaced asynchronously while prompt is active.

## Interactive Commands

- `start` or `continue`: resume execution
- `step into|over|out`: stepping controls while paused
- `status`: current stop summary
- `breaks`: list breakpoints
- `clear <id>`: remove breakpoint by id
- `files`: browse files and toggle breakpoints
- `manage`: enable/disable/delete breakpoints in bulk
- `state clear`: clear persisted breakpoint state
- `restart`: restart session and reapply saved breakpoints
- `quit` or `exit`: leave debugger

## Keyboard Shortcuts

- `↓`: continue
- `↑`: step into
- `→`: step over
- `←`: step out
- `f`: open file picker
- `m`: open breakpoint manager
- `x`: prompt for breakpoint id to clear
- `s`: status
- `l`: list breakpoints
- `r`: restart
- `q`: quit
- Enter on empty prompt: repeat previous continue/step command

## Source Rendering Behavior

- While running: source pane shows unavailable/running status.
- On regular source line stops: current line marker is shown with nearby lines.
- On runtime mapped lines without local text (common method epilogue/closing brace mappings):
  - debugger prints explicit mapped-location messaging so users know stepping did happen.

## Breakpoint Persistence

- Persisted automatically in `~/.java-debugger/breakpoints.dbg`
- Stored fields: source path, line, enabled state
- Removed with `state clear`

## Attach-Mode Source Roots

- No broad filesystem scanning is performed in attach mode.
- If running `jdbg attach` from project root, defaults include:
  - `./src/main/java`
  - `./src/test/java`
- Use one or more explicit roots when debugging an external app:

```bash
jdbg attach \
  --host 127.0.0.1 \
  --port 5005 \
  --source-root /path/to/app/src/main/java \
  --source-root /path/to/app/src/test/java
```

## Troubleshooting

### jdbg runs old code

Reinstall to rebuild and refresh launcher artifact:

```bash
./scripts/install-jdbg.sh
hash -r
```

### I pressed step before first stop

Expected behavior: CLI reminds you debugger is running and not paused yet.

### Step over near method end pauses again

Expected in Java debug metadata. Method epilogue can map to another stoppable location before termination. Use `continue` to finish immediately.

## Local Developer Examples

### Debug the sample test app

```bash
mvn -DskipTests clean package
jdbg --launch \
  --main-class dev.javadebugger.sample.SampleApp \
  --classpath target/test-classes:target/classes
```

### Attach to non-default port

```bash
jdbg --host 127.0.0.1 --port 6006
```

### Attach with external source roots only

```bash
jdbg attach \
  --port 5005 \
  --source-root /workspace/orders-service/src/main/java
```
